package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportDocument;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
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
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AnalyzerReportV2ReaderTest {

    private static final Path DOCS_EXAMPLES = Path.of("..", "docs", "examples");
    private static final Path SUCCESS = DOCS_EXAMPLES.resolve("analyzer-report-v2.success.sample.json");
    private static final Path PARTIAL = DOCS_EXAMPLES.resolve("analyzer-report-v2.partial-success.sample.json");
    private static final Path V1 = Path.of("src", "test", "resources", "contracts",
            "analyzer-report-v1.sample.json");

    @Autowired
    AnalyzerReportReader reader;

    @Autowired
    AnalyzerReportV2SemanticValidator semanticValidator;

    @Autowired
    ObjectMapper objectMapper;

    @TempDir
    Path temporaryDirectory;

    @Test
    void dispatchesExactV1AndV2Versions() {
        AnalyzerReportDocument v1 = reader.read(V1);
        AnalyzerReportDocument v2 = reader.read(SUCCESS);

        assertThat(v1).isInstanceOf(AnalyzerReport.class);
        assertThat(v2).isInstanceOf(AnalyzerReportV2.class);
        assertThat(v1.protocolVersion()).isEqualTo("1.0");
        assertThat(v2.protocolVersion()).isEqualTo("2.0");
    }

    @Test
    void readsBothFormalV2ExamplesAndRecomputesEveryFindingId() {
        AnalyzerReportV2 success = reader.readV2(SUCCESS);
        AnalyzerReportV2 partial = reader.readV2(PARTIAL);

        assertThat(success.status()).isEqualTo(AnalyzerReportV2.Status.SUCCESS);
        assertThat(partial.status()).isEqualTo(AnalyzerReportV2.Status.PARTIAL_SUCCESS);
        assertThat(success.findings()).allSatisfy(finding ->
                assertThat(semanticValidator.recomputeFindingId(finding)).isEqualTo(finding.findingId()));
        assertThat(partial.findings()).allSatisfy(finding ->
                assertThat(semanticValidator.recomputeFindingId(finding)).isEqualTo(finding.findingId()));
        assertThat(success.findings().get(1).findingId())
                .isEqualTo(AnalyzerReportV2SemanticValidator.FIXED_MISSING_LICENSE_ID);
    }

    @Test
    void rejectsNullMissingDirectoryAndOversizedFiles() throws IOException {
        Path missing = temporaryDirectory.resolve("missing.json");
        Path oversized = temporaryDirectory.resolve("oversized.json");
        try (SeekableByteChannel channel = Files.newByteChannel(
                oversized,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            channel.position(AnalyzerReportReader.MAX_REPORT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }

        assertFailure(() -> reader.read(null), AnalyzerReportReadFailure.INVALID_FILE);
        assertFailure(() -> reader.read(missing), AnalyzerReportReadFailure.INVALID_FILE);
        assertFailure(() -> reader.read(temporaryDirectory), AnalyzerReportReadFailure.INVALID_FILE);
        assertFailure(() -> reader.read(oversized), AnalyzerReportReadFailure.FILE_TOO_LARGE);
    }

    @Test
    void rejectsMalformedRootAndDuplicateKeysWithoutLeakingInput() throws IOException {
        Path malformed = write("{secret-json");
        Path array = write("[]");
        Path duplicate = write("{\"protocolVersion\":\"1.0\",\"protocolVersion\":\"2.0\"}");

        assertSafeFailure(malformed, AnalyzerReportReadFailure.MALFORMED_JSON);
        assertSafeFailure(array, AnalyzerReportReadFailure.INVALID_ROOT);
        assertSafeFailure(duplicate, AnalyzerReportReadFailure.MALFORMED_JSON);
    }

    static Stream<Arguments> invalidVersions() {
        return Stream.of(
                Arguments.of("missing", "{}", AnalyzerReportReadFailure.INVALID_PROTOCOL_VERSION),
                Arguments.of("null", "{\"protocolVersion\":null}",
                        AnalyzerReportReadFailure.INVALID_PROTOCOL_VERSION),
                Arguments.of("number", "{\"protocolVersion\":2}",
                        AnalyzerReportReadFailure.INVALID_PROTOCOL_VERSION),
                Arguments.of("string-1", "{\"protocolVersion\":\"1\"}",
                        AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION),
                Arguments.of("string-2", "{\"protocolVersion\":\"2\"}",
                        AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION),
                Arguments.of("minor", "{\"protocolVersion\":\"2.1\"}",
                        AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION),
                Arguments.of("unknown", "{\"protocolVersion\":\"9.9\"}",
                        AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION)
        );
    }

    @ParameterizedTest(name = "rejects invalid version: {0}")
    @MethodSource("invalidVersions")
    void rejectsMissingNonStringAndNonExactVersions(
            String ignored,
            String json,
            AnalyzerReportReadFailure expected
    ) throws IOException {
        Path report = write(json);
        assertFailure(() -> reader.read(report), expected);
    }

    static Stream<Arguments> schemaViolations() {
        return Stream.of(
                mutation("unknown top-level field", root -> root.put("unexpected", true)),
                mutation("unknown nested field", root -> object(root, "analyzer").put("unexpected", true)),
                mutation("Windows absolute path", root -> buildFiles(root).set(0, "C:\\private\\pom.xml")),
                mutation("Unix absolute path", root -> buildFiles(root).set(0, "/tmp/pom.xml")),
                mutation("URI path", root -> buildFiles(root).set(0, "file://private/pom.xml")),
                mutation("backslash path", root -> buildFiles(root).set(0, "src\\pom.xml")),
                mutation("path traversal", root -> buildFiles(root).set(0, "../pom.xml")),
                mutation("repository fake empty path and line zero", root -> {
                    ObjectNode location = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    location.put("relativePath", "");
                    location.put("startLine", 0);
                    location.put("startColumn", 0);
                    location.put("endLine", 0);
                    location.put("endColumn", 0);
                    finding(root, 1).set("location", location);
                }),
                mutation("FILE location missing", root -> finding(root, 0).remove("location")),
                mutation("unapproved evidence field", root -> object(finding(root, 0), "evidence")
                        .put("source", "hidden")),
                mutation("empty remediation", root -> finding(root, 0).put("remediation", "")),
                mutation("illegal enum", root -> finding(root, 0).put("severity", "URGENT")),
                mutation("status combination", root -> root.put("reviewability", "PARTIAL")),
                mutation("invalid date shape", root -> root.put("generatedAt", "2026-09-14 10:00:00+08:00")),
                mutation("missing timezone", root -> root.put("generatedAt", "2026-09-14T10:00:00")),
                mutation("nonexistent date", root -> root.put("generatedAt", "2026-02-30T10:00:00+08:00"))
        );
    }

    @ParameterizedTest(name = "Schema rejects: {0}")
    @MethodSource("schemaViolations")
    void rejectsSchemaViolations(String ignored, Consumer<ObjectNode> mutation) throws IOException {
        Path report = writeMutated(SUCCESS, mutation);
        assertFailure(() -> reader.read(report),
                AnalyzerReportReadFailure.SCHEMA_VIOLATION);
    }

    static Stream<Arguments> semanticViolations() {
        return Stream.of(
                mutation("location ends before start", root -> object(finding(root, 0), "location")
                        .put("endLine", 39)),
                mutation("line counts exceed total", root -> object(root, "summary").put("totalLines", 1)),
                mutation("languages are unsorted", root -> reverse(array(root, "languages"))),
                mutation("build files are unsorted", root -> reverse(buildFiles(root))),
                mutation("findings are unsorted", root -> reverse(array(root, "findings"))),
                mutation("limitation keys are unsorted", root -> reverse(arrayFrom(PARTIAL, root, "limitations"))),
                mutation("language stable key repeats", root -> {
                    ObjectNode duplicate = objectMapperIndependentCopy(array(root, "languages").get(0));
                    duplicate.put("files", 99);
                    array(root, "languages").add(duplicate);
                }),
                mutation("findingId differs", root -> finding(root, 0).put(
                        "findingId", AnalyzerReportV2SemanticValidator.FIXED_MISSING_LICENSE_ID)),
                mutation("rule type differs", root -> finding(root, 1).put("type", "SYNTAX_TREE")),
                mutation("expected paths are unsorted", root -> reverse(
                        array(object(finding(root, 1), "evidence"), "expectedPaths"))),
                mutation("revision is placeholder", root -> object(root, "repository").put("revision", "unknown")),
                mutation("absolute path in text", root -> finding(root, 0)
                        .put("message", "Found C:\\Users\\alice\\private.txt")),
                mutation("Unix absolute path in text", root -> finding(root, 0)
                        .put("message", "Found /srv/private/repository.txt")),
                mutation("credential in text", root -> finding(root, 0).put("message", "password=secret-value")),
                mutation("process output in text", root -> finding(root, 0).put("message", "stdout captured")),
                mutation("stack text", root -> finding(root, 0).put("message", "Exception: scan failed"))
        );
    }

    @ParameterizedTest(name = "semantics reject: {0}")
    @MethodSource("semanticViolations")
    void rejectsSemanticViolations(String ignored, Consumer<ObjectNode> mutation) throws IOException {
        Path source = ignored.equals("limitation keys are unsorted") ? PARTIAL : SUCCESS;
        Path report = writeMutated(source, mutation);
        assertFailure(() -> reader.read(report),
                AnalyzerReportReadFailure.SEMANTIC_VIOLATION);
    }

    @Test
    void v1CompatibilityEntryRejectsV2WithoutChangingRunnerDefault() {
        assertFailure(() -> reader.readV1(SUCCESS),
                AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION);
    }

    private static Arguments mutation(String name, Consumer<ObjectNode> mutation) {
        return Arguments.of(name, mutation);
    }

    private Path writeMutated(Path source, Consumer<ObjectNode> mutation) throws IOException {
        ObjectNode root = (ObjectNode) objectMapper.readTree(source);
        mutation.accept(root);
        Path target = temporaryDirectory.resolve("mutated-" + System.nanoTime() + ".json");
        objectMapper.writeValue(target, root);
        return target;
    }

    private Path write(String json) throws IOException {
        Path target = temporaryDirectory.resolve("report-" + System.nanoTime() + ".json");
        Files.writeString(target, json);
        return target;
    }

    private void assertSafeFailure(Path path, AnalyzerReportReadFailure expected) {
        assertThatThrownBy(() -> reader.read(path))
                .isInstanceOfSatisfying(AnalyzerReportReadException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(expected);
                    assertThat(exception.getMessage()).doesNotContain(path.toAbsolutePath().toString());
                    assertThat(exception.getMessage()).doesNotContain("secret-json");
                });
    }

    private void assertFailure(Runnable operation, AnalyzerReportReadFailure expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(AnalyzerReportReadException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(expected));
    }

    private static ObjectNode object(ObjectNode root, String name) {
        return object((JsonNode) root, name);
    }

    private static ObjectNode object(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value instanceof ObjectNode object) {
            return object;
        }
        ObjectNode created = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        ((ObjectNode) root).set(name, created);
        return created;
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
            JsonNode leftValue = values.get(left);
            values.set(left, values.get(right));
            values.set(right, leftValue);
        }
    }

    private static ArrayNode arrayFrom(Path ignored, ObjectNode root, String name) {
        return array(root, name);
    }

    private static ObjectNode objectMapperIndependentCopy(JsonNode source) {
        return (ObjectNode) source.deepCopy();
    }
}
