package io.github.lihanc940.openpulse.report.application;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerStatus;
import io.github.lihanc940.openpulse.integration.analyzer.model.RiskLevel;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportRecord;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportStatus;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisReportSnapshotFactoryTest {

    private static final String TEMPORARY_PATH = "C:\\private\\openpulse-github-123\\repo";
    private static final String TEST_TOKEN = "test-token-that-must-not-persist";
    private static final AnalysisTaskId PLATFORM_TASK_ID = new AnalysisTaskId(
            UUID.fromString("11111111-1111-1111-1111-111111111111")
    );
    private static final Instant CREATED_AT = Instant.parse("2026-08-20T00:00:00Z");

    private final AnalysisReportSnapshotFactory factory = new AnalysisReportSnapshotFactory(new ObjectMapper());

    @Test
    void createsStructuredSnapshotWithoutPathOrDiagnosticFields() {
        AnalysisReportRecord snapshot = factory.create(
                PLATFORM_TASK_ID,
                reportWithSensitiveEvidence(),
                Clock.fixed(CREATED_AT, ZoneOffset.UTC)
        );

        assertThat(snapshot.analysisTaskId()).isEqualTo(PLATFORM_TASK_ID);
        assertThat(snapshot.analyzerTaskId()).isEqualTo("analyzer-task-777");
        assertThat(snapshot.analysisTaskId().toString()).isNotEqualTo(snapshot.analyzerTaskId());
        assertThat(snapshot.reportJson())
                .contains("\"taskId\":\"analyzer-task-777\"")
                .contains("\"functionName\":\"registerUser\"")
                .doesNotContain(TEMPORARY_PATH)
                .doesNotContain(TEST_TOKEN)
                .doesNotContain("stdout-secret")
                .doesNotContain("stderr-secret")
                .doesNotContain("diagnostic-secret")
                .doesNotContain("stack-secret")
                .doesNotContain("\"path\"");
        factory.validateSnapshot(snapshot);
    }

    @Test
    void rejectsMalformedOrMetadataInconsistentSnapshotJson() {
        AnalysisReportRecord malformed = new AnalysisReportRecord(
                PLATFORM_TASK_ID,
                "1.0",
                "analyzer-task-777",
                AnalysisReportStatus.SUCCESS,
                "{not-json",
                CREATED_AT,
                CREATED_AT
        );

        assertInvalidData(() -> factory.validateSnapshot(malformed));

        AnalysisReportRecord inconsistent = new AnalysisReportRecord(
                PLATFORM_TASK_ID,
                "1.0",
                "different-analyzer-id",
                AnalysisReportStatus.SUCCESS,
                factory.create(
                        PLATFORM_TASK_ID,
                        reportWithSensitiveEvidence(),
                        Clock.fixed(CREATED_AT, ZoneOffset.UTC)
                ).reportJson(),
                OffsetDateTime.parse("2026-08-20T08:00:00+08:00").toInstant(),
                CREATED_AT
        );

        assertInvalidData(() -> factory.validateSnapshot(inconsistent));
    }

    @Test
    void rejectsManuallyConstructedSnapshotFieldsOutsideWhitelist() {
        AnalysisReportRecord valid = factory.create(
                PLATFORM_TASK_ID,
                reportWithSensitiveEvidence(),
                Clock.fixed(CREATED_AT, ZoneOffset.UTC)
        );
        String rootBypassJson = valid.reportJson().substring(0, valid.reportJson().length() - 1)
                + ",\"secret\":\"" + TEST_TOKEN + "\"}";
        AnalysisReportRecord rootBypass = withJson(valid, rootBypassJson);

        String evidenceBypassJson = valid.reportJson().replace(
                "\"functionName\":\"registerUser\"",
                "\"functionName\":\"registerUser\",\"secret\":\"" + TEST_TOKEN + "\""
        );
        AnalysisReportRecord evidenceBypass = withJson(valid, evidenceBypassJson);

        assertInvalidData(() -> factory.validateSnapshot(rootBypass));
        assertInvalidData(() -> factory.validateSnapshot(evidenceBypass));
    }

    @Test
    void rejectsAbsoluteRiskPathsBeforeSerialization() {
        AnalyzerReport report = baseReport(
                List.of(new AnalyzerReport.Risk(
                        "LONG_FILE",
                        "CODE_SMELL",
                        RiskLevel.HIGH,
                        TEMPORARY_PATH,
                        1,
                        "File is too long.",
                        Map.of()
                ))
        );

        assertInvalidData(() -> factory.create(
                PLATFORM_TASK_ID,
                report,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC)
        ));
    }

    private AnalyzerReport reportWithSensitiveEvidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("functionName", "registerUser");
        evidence.put("path", TEMPORARY_PATH);
        evidence.put("token", TEST_TOKEN);
        evidence.put("stdout", "stdout-secret");
        evidence.put("stderr", "stderr-secret");
        evidence.put("diagnosticSummary", "diagnostic-secret");
        evidence.put("exceptionStackTrace", "stack-secret");
        evidence.put("secret", TEST_TOKEN);
        return baseReport(List.of(new AnalyzerReport.Risk(
                "LONG_FUNCTION",
                "CODE_SMELL",
                RiskLevel.HIGH,
                "src/UserService.java",
                84,
                "Function is too long.",
                evidence
        )));
    }

    private AnalyzerReport baseReport(List<AnalyzerReport.Risk> risks) {
        return new AnalyzerReport(
                "1.0",
                "analyzer-task-777",
                AnalyzerStatus.SUCCESS,
                new AnalyzerReport.Repository(TEMPORARY_PATH, "openpulse"),
                new AnalyzerReport.Summary(3, 2, 1, 0, 1, 30, 20, 5, 5),
                List.of(new AnalyzerReport.Language("Java", 2, 20)),
                new AnalyzerReport.Structure(true, false, false, false, true, true, false, List.of("pom.xml")),
                new AnalyzerReport.Quality(80, 80, 70, 60),
                risks,
                new AnalyzerReport.Dependencies(List.of(), List.of()),
                OffsetDateTime.parse("2026-08-20T08:00:00+08:00")
        );
    }

    private void assertInvalidData(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(PersistenceOperationException.class)
                .extracting(exception -> ((PersistenceOperationException) exception).failure())
                .isEqualTo(PersistenceFailure.INVALID_DATA);
    }

    private AnalysisReportRecord withJson(AnalysisReportRecord source, String reportJson) {
        return new AnalysisReportRecord(
                source.analysisTaskId(),
                source.protocolVersion(),
                source.analyzerTaskId(),
                source.reportStatus(),
                reportJson,
                source.generatedAt(),
                source.createdAt()
        );
    }
}
