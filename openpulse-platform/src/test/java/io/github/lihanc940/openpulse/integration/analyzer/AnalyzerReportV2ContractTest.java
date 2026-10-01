package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import io.github.lihanc940.openpulse.report.application.AnalysisReportSnapshotFactory;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportRecord;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
class AnalyzerReportV2ContractTest {

    private static final String REPORT_DIRECTORY_PROPERTY = "openpulse.contract.report.dir";
    private static final Path ACCEPTED_FILE_FINDING_REPORT = Path.of(
            "..", "docs", "examples", "analyzer-report-v2.success.sample.json"
    );
    private static final AnalysisTaskId PLATFORM_TASK_ID = new AnalysisTaskId(
            UUID.fromString("12121212-1212-1212-1212-121212121212")
    );
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC
    );
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(authorization\\s*:|bearer\\s+|api[_ -]?key\\s*[:=]|"
                    + "token\\s*[:=]|password\\s*[:=]|passwd\\s*[:=]|"
                    + "secret\\s*[:=]|private[_ -]?key\\s*[:=]|"
                    + "-----BEGIN [A-Z ]*PRIVATE KEY-----)"
    );
    private static final Pattern PROCESS_OR_STACK = Pattern.compile(
            "(?i)(\\bstdout\\b|\\bstderr\\b|stack\\s*trace|exception\\s*:|"
                    + "\\bat\\s+[a-z_$][\\w$]*(?:\\.[\\w$]+)+\\()"
    );

    @Autowired
    AnalyzerReportReader reader;

    @Autowired
    AnalysisReportSnapshotFactory snapshotFactory;

    @Autowired
    ObjectMapper objectMapper;

    @TempDir
    Path temporaryDirectory;

    @Test
    void c01DefaultCliOutputRemainsV1AndJavaAcceptsIt() {
        Path reports = requireReports();
        assertThat(reader.readV1(reports.resolve("run-1/default-v1.json")).protocolVersion())
                .isEqualTo("1.0");
    }

    @Test
    void c02ExplicitV1MatchesDefaultV1AfterAllowedNormalization() throws IOException {
        Path reports = requireReports();
        byte[] defaultV1 = normalizedBytes(reports.resolve("run-1/default-v1.json"));
        byte[] explicitV1 = normalizedBytes(reports.resolve("run-1/explicit-v1.json"));
        assertThat(reader.readV1(reports.resolve("run-1/explicit-v1.json")).protocolVersion())
                .isEqualTo("1.0");
        assertThat(explicitV1).isEqualTo(defaultV1);
    }

    @Test
    void c03CompleteProductionCliReportPassesReaderAndSnapshotWhitelist() {
        AnalyzerReportV2 report = reader.readV2(
                requireReports().resolve("run-1/complete.v2.json"));

        assertThat(report.status()).isEqualTo(AnalyzerReportV2.Status.SUCCESS);
        assertThat(report.reviewability()).isEqualTo(AnalyzerReportV2.Reviewability.COMPLETE);
        assertThat(report.ruleSet().id()).isEqualTo("openpulse-default");
        assertThat(report.ruleSet().version()).isEqualTo("1.0.0");
        assertThat(report.findings()).isEmpty();
        assertThat(report.limitations()).isEmpty();
        assertThat(report.repository().root()).isEqualTo(".");

        AnalysisReportRecord snapshot = snapshotFactory.create(PLATFORM_TASK_ID, report, CLOCK);
        snapshotFactory.validateSnapshot(snapshot);
    }

    @Test
    void c04MissingStructureUsesTheProductionCatalogAndFixedIds() {
        AnalyzerReportV2 report = reader.readV2(
                requireReports().resolve("run-1/missing-structure.v2.json"));

        assertThat(report.status()).isEqualTo(AnalyzerReportV2.Status.SUCCESS);
        assertThat(report.reviewability()).isEqualTo(AnalyzerReportV2.Reviewability.COMPLETE);
        assertThat(report.findings())
                .extracting(AnalyzerReportV2.Finding::ruleId)
                .containsExactly("MISSING_LICENSE", "MISSING_CI", "MISSING_README");
        assertThat(report.findings())
                .extracting(AnalyzerReportV2.Finding::findingId)
                .containsExactly(
                        "sha256:8313d87acf736e6d1f6112261e703bcef60fd37a2609bbd38ba83531e4d854af",
                        "sha256:da40e56d7e706baed9f961c147037ff412f3c0d942f166631df2cc315ac1d0e3",
                        "sha256:f8566b4e81b6029ff8ce1c3e7c562fe13f285991f522905b540a07899b6c2f9d"
                );
        assertThat(report.findings()).allSatisfy(finding -> {
            assertThat(finding.type()).isEqualTo(AnalyzerReportV2.FindingType.PROJECT_STRUCTURE);
            assertThat(finding.scope()).isEqualTo(AnalyzerReportV2.Scope.REPOSITORY);
            assertThat(finding.location()).isNull();
            assertThat(finding.evidence().kind())
                    .isEqualTo(AnalyzerReportV2.EvidenceKind.EXPECTED_PATHS_ABSENT);
            assertThat(finding.remediation()).isNotBlank();
        });

        AnalysisReportRecord snapshot = snapshotFactory.create(PLATFORM_TASK_ID, report, CLOCK);
        snapshotFactory.validateSnapshot(snapshot);
    }

    @Test
    void c05PartialProductionBuilderReportIsUsableButExplicitlyLimited() {
        AnalyzerReportV2 report = reader.readV2(
                requireReports().resolve("run-1/partial-success.v2.json"));

        assertThat(report.status()).isEqualTo(AnalyzerReportV2.Status.PARTIAL_SUCCESS);
        assertThat(report.reviewability()).isEqualTo(AnalyzerReportV2.Reviewability.PARTIAL);
        assertThat(report.limitations()).hasSize(1);
        assertThat(report.limitations().getFirst().kind())
                .isEqualTo(AnalyzerReportV2.LimitationKind.FILE_SKIPPED);

        AnalysisReportRecord snapshot = snapshotFactory.create(PLATFORM_TASK_ID, report, CLOCK);
        snapshotFactory.validateSnapshot(snapshot);
    }

    @Test
    void c06FailedProductionBuilderReportCannotBecomeAUsableSnapshot() {
        AnalyzerReportV2 report = reader.readV2(
                requireReports().resolve("run-1/failed.v2.json"));

        assertThat(report.status()).isEqualTo(AnalyzerReportV2.Status.FAILED);
        assertThat(report.reviewability()).isEqualTo(AnalyzerReportV2.Reviewability.NOT_USABLE);
        assertThat(report.summary()).isNull();
        assertThat(report.languages()).isNull();
        assertThat(report.structure()).isNull();
        assertThat(report.findings()).isEmpty();
        assertThat(report.limitations())
                .anySatisfy(limitation -> assertThat(limitation.kind())
                        .isEqualTo(AnalyzerReportV2.LimitationKind.SCAN_FAILED));
        assertThatThrownBy(() -> snapshotFactory.create(PLATFORM_TASK_ID, report, CLOCK))
                .isInstanceOfSatisfying(PersistenceOperationException.class,
                        exception -> assertThat(exception.failure())
                                .isEqualTo(PersistenceFailure.INVALID_DATA));
    }

    @Test
    void c07TwoRunsAreByteIdenticalAfterRemovingOnlyTheTwoDynamicFields()
            throws IOException, NoSuchAlgorithmException {
        Path reports = requireReports();
        for (String name : List.of("complete", "missing-structure", "partial-success")) {
            byte[] first = normalizedBytes(reports.resolve("run-1/" + name + ".v2.json"));
            byte[] second = normalizedBytes(reports.resolve("run-2/" + name + ".v2.json"));
            assertThat(second).as(name + " normalized bytes").isEqualTo(first);
            assertThat(sha256(second)).as(name + " normalized SHA-256").isEqualTo(sha256(first));
        }
    }

    @ParameterizedTest(name = "C08 {0} -> {2}")
    @MethodSource("invalidMutations")
    void c08EverySingleMutationIsRejectedWithTheExpectedCategory(
            String id,
            Path sourceRelativeToReportDirectory,
            AnalyzerReportReadFailure expected,
            Consumer<ObjectNode> mutation
    ) throws IOException {
        Path reports = requireReports();
        Path source = sourceRelativeToReportDirectory.isAbsolute()
                ? sourceRelativeToReportDirectory
                : reports.resolve(sourceRelativeToReportDirectory);
        ObjectNode root = (ObjectNode) objectMapper.readTree(Files.readAllBytes(source));
        mutation.accept(root);
        Path mutated = temporaryDirectory.resolve(id + ".json");
        objectMapper.writeValue(mutated, root);

        assertThatThrownBy(() -> reader.read(mutated))
                .isInstanceOfSatisfying(AnalyzerReportReadException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(expected));
    }

    @Test
    void c09ReportsGoldensAndSnapshotsContainNoMachineOrSensitiveText() throws IOException {
        Path reports = requireReports();
        String fixtureAbsolute = Path.of("..", "docs", "fixtures", "analyzer-report-v2-contract")
                .toAbsolutePath().normalize().toString();
        String userName = System.getProperty("user.name", "");
        StringBuilder inspected = new StringBuilder();

        for (String name : List.of("complete", "missing-structure", "partial-success", "failed")) {
            Path raw = reports.resolve("run-1/" + name + ".v2.json");
            inspected.append(Files.readString(raw, StandardCharsets.UTF_8));
            inspected.append(Files.readString(Path.of("..", "docs", "fixtures",
                    "analyzer-report-v2-contract", "expected", name + ".v2.normalized.json"),
                    StandardCharsets.UTF_8));
            AnalyzerReportV2 report = reader.readV2(raw);
            if (report.status() != AnalyzerReportV2.Status.FAILED) {
                inspected.append(snapshotFactory.create(PLATFORM_TASK_ID, report, CLOCK).reportJson());
            }
        }

        String text = inspected.toString();
        assertThat(text).doesNotContain(fixtureAbsolute, fixtureAbsolute.replace('\\', '/'));
        if (!userName.isBlank()) {
            assertThat(text).doesNotContainIgnoringCase(userName);
        }
        assertThat(CREDENTIAL.matcher(text).find()).isFalse();
        assertThat(PROCESS_OR_STACK.matcher(text).find()).isFalse();
    }

    static Stream<Arguments> invalidMutations() {
        Path complete = Path.of("run-1", "complete.v2.json");
        Path missing = Path.of("run-1", "missing-structure.v2.json");
        return Stream.of(
                Arguments.of("N01", complete, AnalyzerReportReadFailure.INVALID_PROTOCOL_VERSION,
                        mutation(root -> root.remove("protocolVersion"))),
                Arguments.of("N02", complete, AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION,
                        mutation(root -> root.put("protocolVersion", "2.1"))),
                Arguments.of("N03", complete, AnalyzerReportReadFailure.SCHEMA_VIOLATION,
                        mutation(root -> root.put("unexpected", true))),
                Arguments.of("N04", complete, AnalyzerReportReadFailure.SCHEMA_VIOLATION,
                        mutation(root -> buildFiles(root).set(
                                0, JsonNodeFactory.instance.textNode("C:/private/CMakeLists.txt")))),
                Arguments.of("N05", missing, AnalyzerReportReadFailure.SEMANTIC_VIOLATION,
                        mutation(root -> reverse(array(root, "findings")))),
                Arguments.of("N06", missing, AnalyzerReportReadFailure.SEMANTIC_VIOLATION,
                        mutation(root -> finding(root, 0).put("findingId", "sha256:" + "0".repeat(64)))),
                Arguments.of("N07", missing, AnalyzerReportReadFailure.SEMANTIC_VIOLATION,
                        mutation(root -> finding(root, 0).put("message", "token=contract-secret"))),
                Arguments.of("N08", complete, AnalyzerReportReadFailure.SCHEMA_VIOLATION,
                        mutation(root -> root.put("reviewability", "PARTIAL"))),
                Arguments.of("N09", ACCEPTED_FILE_FINDING_REPORT.toAbsolutePath(),
                        AnalyzerReportReadFailure.SEMANTIC_VIOLATION,
                        mutation(root -> object(finding(root, 0), "location").put("endLine", 39))),
                Arguments.of("N10", missing, AnalyzerReportReadFailure.SCHEMA_VIOLATION,
                        mutation(root -> object(finding(root, 0), "evidence").put("rawOutput", "x")))
        );
    }

    private Path requireReports() {
        String configured = System.getProperty(REPORT_DIRECTORY_PROPERTY);
        assumeTrue(configured != null && !configured.isBlank(),
                REPORT_DIRECTORY_PROPERTY + " is not configured; C01-C09 external-report tests are skipped");
        Path reports = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(reports), "contract report directory does not exist");
        return reports;
    }

    private byte[] normalizedBytes(Path report) throws IOException {
        ObjectNode root = (ObjectNode) objectMapper.readTree(Files.readAllBytes(report));
        root.remove("taskId");
        root.remove("generatedAt");
        return objectMapper.writeValueAsBytes(root);
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Consumer<ObjectNode> mutation(Consumer<ObjectNode> mutation) {
        return mutation;
    }

    private static ObjectNode object(JsonNode root, String name) {
        return (ObjectNode) root.get(name);
    }

    private static ArrayNode array(ObjectNode root, String name) {
        return (ArrayNode) root.get(name);
    }

    private static ArrayNode buildFiles(ObjectNode root) {
        return array(object(root, "structure"), "buildFiles");
    }

    private static ObjectNode finding(ObjectNode root, int index) {
        return (ObjectNode) array(root, "findings").get(index);
    }

    private static void reverse(ArrayNode values) {
        for (int left = 0, right = values.size() - 1; left < right; left++, right--) {
            JsonNode value = values.get(left);
            values.set(left, values.get(right));
            values.set(right, value);
        }
    }
}
