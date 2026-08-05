package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerStatus;
import io.github.lihanc940.openpulse.integration.analyzer.model.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.core.JacksonException;

import java.nio.file.Path;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AnalyzerReportReaderTest {

    private static final Path CONTRACTS_DIR = Path.of("src", "test", "resources", "contracts");

    @Autowired
    private AnalyzerReportReader reader;

    @Test
    void readsValidAnalyzerReport() {
        AnalyzerReport report = reader.read(CONTRACTS_DIR.resolve("analyzer-report-v1.sample.json"));

        assertThat(report.protocolVersion()).isEqualTo("1.0");
        assertThat(report.taskId()).isEqualTo("task_demo_001");
        assertThat(report.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(report.summary().totalFiles()).isEqualTo(12);
        assertThat(report.languages()).hasSize(2);
        assertThat(report.risks()).hasSize(2);
        assertThat(report.risks().getFirst().ruleId()).isEqualTo("MISSING_LICENSE");
        assertThat(report.risks().getFirst().level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(report.generatedAt().getOffset()).isEqualTo(ZoneOffset.of("+08:00"));
        assertThat(report.risks().getFirst().evidence()).containsEntry("expectedFile", "LICENSE");
        assertThat(report.risks().get(1).evidence()).containsEntry("functionName", "registerUser");
    }

    @Test
    void ignoresUnknownFieldsInCompatibleProtocolVersion() {
        AnalyzerReport report = reader.read(CONTRACTS_DIR.resolve("analyzer-report-with-extra-fields.json"));

        assertThat(report.protocolVersion()).isEqualTo("1.0");
        assertThat(report.taskId()).isEqualTo("task_demo_extra_fields");
    }

    @Test
    void rejectsMissingReportFile() {
        Path missingReport = CONTRACTS_DIR.resolve("missing-report.json");

        assertThatThrownBy(() -> reader.read(missingReport))
                .isInstanceOf(AnalyzerReportReadException.class)
                .hasMessageContaining("does not exist")
                .hasMessageContaining("missing-report.json");
    }

    @Test
    void rejectsMalformedJsonAndKeepsCause() {
        Path malformedReport = CONTRACTS_DIR.resolve("analyzer-report-malformed.json");

        assertThatThrownBy(() -> reader.read(malformedReport))
                .isInstanceOf(AnalyzerReportReadException.class)
                .hasMessageContaining("JSON is malformed")
                .hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void rejectsUnsupportedProtocolVersion() {
        Path unsupportedVersionReport = CONTRACTS_DIR.resolve("analyzer-report-unsupported-version.json");

        assertThatThrownBy(() -> reader.read(unsupportedVersionReport))
                .isInstanceOf(AnalyzerReportReadException.class)
                .hasMessageContaining("Unsupported analyzer report protocol version")
                .hasMessageContaining("2.0");
    }
}
