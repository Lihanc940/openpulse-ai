package io.github.lihanc940.openpulse.report.application;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerReportReader;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportRecord;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AnalysisReportV2SnapshotFactoryTest {

    private static final Path EXAMPLES = Path.of("..", "docs", "examples");
    private static final AnalysisTaskId PLATFORM_TASK_ID = new AnalysisTaskId(
            UUID.fromString("22222222-2222-2222-2222-222222222222")
    );
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-17T00:00:00Z"),
            ZoneOffset.UTC
    );

    @Autowired
    AnalyzerReportReader reader;

    @Autowired
    AnalysisReportSnapshotFactory factory;

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsStrictSuccessAndPartialSuccessWhitelists() {
        AnalyzerReportV2 success = reader.readV2(
                EXAMPLES.resolve("analyzer-report-v2.success.sample.json"));
        AnalyzerReportV2 partial = reader.readV2(
                EXAMPLES.resolve("analyzer-report-v2.partial-success.sample.json"));

        AnalysisReportRecord successRecord = factory.create(PLATFORM_TASK_ID, success, CLOCK);
        AnalysisReportRecord partialRecord = factory.create(PLATFORM_TASK_ID, partial, CLOCK);

        assertThat(successRecord.protocolVersion()).isEqualTo("2.0");
        assertThat(successRecord.reportJson())
                .contains("\"findings\"", "\"EXPECTED_PATHS_ABSENT\"", "\"SYNTAX_METRIC\"")
                .doesNotContain("\"quality\"", "\"risks\"", "stdout", "stderr", "stackTrace");
        assertThat(partialRecord.reportJson())
                .contains("\"PARTIAL_SUCCESS\"", "\"FILE_SKIPPED\"", "\"UNSUPPORTED_CAPABILITY\"")
                .doesNotContain("\"quality\"", "\"dependencies\"");
        factory.validateSnapshot(successRecord);
        factory.validateSnapshot(partialRecord);
    }

    @Test
    void rejectsFailedReportsAsUsableSnapshots() throws IOException {
        Path failedPath = temporaryDirectory.resolve("failed.json");
        Files.writeString(failedPath, """
                {
                  "protocolVersion":"2.0",
                  "taskId":"task_failed_001",
                  "analyzer":{"name":"openpulse-analyzer","version":"0.2.0"},
                  "ruleSet":{"id":"openpulse-default","version":"0.2.0"},
                  "status":"FAILED",
                  "reviewability":"NOT_USABLE",
                  "repository":{"name":"fictional-workspace","root":"."},
                  "findings":[],
                  "limitations":[{
                    "kind":"SCAN_FAILED",
                    "scope":"REPOSITORY",
                    "reason":"TRAVERSAL_ERROR",
                    "message":"仓库遍历未形成可复核报告。"
                  }],
                  "generatedAt":"2026-09-17T08:00:00+08:00"
                }
                """);
        AnalyzerReportV2 failed = reader.readV2(failedPath);

        assertThatThrownBy(() -> factory.create(PLATFORM_TASK_ID, failed, CLOCK))
                .isInstanceOfSatisfying(PersistenceOperationException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(PersistenceFailure.INVALID_DATA));
    }

    @Test
    void rejectsFieldsOutsideTheV2SnapshotWhitelist() {
        AnalyzerReportV2 success = reader.readV2(
                EXAMPLES.resolve("analyzer-report-v2.success.sample.json"));
        AnalysisReportRecord valid = factory.create(PLATFORM_TASK_ID, success, CLOCK);
        String tamperedJson = valid.reportJson().substring(0, valid.reportJson().length() - 1)
                + ",\"secret\":\"must-not-persist\"}";
        AnalysisReportRecord tampered = new AnalysisReportRecord(
                valid.analysisTaskId(),
                valid.protocolVersion(),
                valid.analyzerTaskId(),
                valid.reportStatus(),
                tamperedJson,
                valid.generatedAt(),
                valid.createdAt()
        );

        assertThatThrownBy(() -> factory.validateSnapshot(tampered))
                .isInstanceOfSatisfying(PersistenceOperationException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(PersistenceFailure.INVALID_DATA));
    }
}
