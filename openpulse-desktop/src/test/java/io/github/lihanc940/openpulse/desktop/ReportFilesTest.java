package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.*;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class ReportFilesTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static AnalyzerReportReader reader() {
        return new AnalyzerReportReader(MAPPER, new AnalyzerReportV2SchemaValidator(), new AnalyzerReportV2SemanticValidator(MAPPER));
    }
    static Path fixture(String status) {
        return status.equals("failed")
                ? Path.of("../docs/fixtures/analyzer-report-v2-contract/expected/failed.v2.normalized.json")
                : Path.of("../docs/examples/analyzer-report-v2." + status + ".sample.json");
    }
    @TempDir Path temporary;
    private final ReportFiles files = new ReportFiles(MAPPER, reader());

    @ParameterizedTest
    @ValueSource(strings = {"success", "partial-success", "failed"})
    void importsAndExportsAllStatusesWithoutChangingProtocolFacts(String status) throws Exception {
        // Normalized FAILED golden intentionally omits dynamic fields. Add legal values for the strict Reader.
        Path source = fixture(status);
        if (status.equals("failed")) {
            ObjectNode tree = (ObjectNode) MAPPER.readTree(Files.readString(source));
            tree.put("taskId", "desktop_failed_test");
            tree.put("generatedAt", "2026-11-02T00:00:00Z");
            source = temporary.resolve("failed.json");
            Files.writeString(source, MAPPER.writeValueAsString(tree));
        }
        AnalyzerReportV2 report = files.open(source);
        Path output = temporary.resolve("中文 报告.json");
        Files.writeString(output, "previous export");
        files.save(report, output);
        assertThat(files.open(output)).isEqualTo(report);
        if (report.status() != AnalyzerReportV2.Status.FAILED) {
            assertThat(new ReportPresentation().overview(report)).contains("不代表", "测试");
        } else {
            assertThat(new ReportPresentation().overview(report)).contains("扫描失败", "未生成可用统计");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"protocolVersion\":\"1.0\"}", "{\"protocolVersion\":\"2.1\"}",
            "{\"protocolVersion\":\"2.0\",\"protocolVersion\":\"2.0\"}"})
    void rejectsMalformedUnknownAndWrongVersions(String json) throws Exception {
        Path input = temporary.resolve("invalid.json");
        Files.writeString(input, json);
        assertThatThrownBy(() -> files.open(input)).isInstanceOf(AnalyzerReportReadException.class);
    }

    @Test
    void rejectsOversizedUnknownFieldsAndSemanticTampering() throws Exception {
        Path input = temporary.resolve("large.json");
        try (RandomAccessFile file = new RandomAccessFile(input.toFile(), "rw")) {
            file.setLength(AnalyzerReportReader.MAX_REPORT_BYTES + 1);
        }
        assertThatThrownBy(() -> files.open(input)).isInstanceOf(AnalyzerReportReadException.class);
        ObjectNode tree = (ObjectNode) MAPPER.readTree(Files.readString(fixture("success")));
        tree.put("execute", "<script>alert(1)</script>");
        Files.writeString(input, MAPPER.writeValueAsString(tree));
        assertThatThrownBy(() -> files.open(input)).isInstanceOf(AnalyzerReportReadException.class);
        tree.remove("execute");
        ((ObjectNode) tree.path("findings").get(0)).put("findingId", "sha256:" + "0".repeat(64));
        Files.writeString(input, MAPPER.writeValueAsString(tree));
        assertThatThrownBy(() -> files.open(input)).isInstanceOf(AnalyzerReportReadException.class);
    }

    @Test
    void failedExportDoesNotReplacePreviousFileAndCleansTemporaryFile() throws Exception {
        AnalyzerReportV2 original = files.open(fixture("success"));
        AnalyzerReportV2 invalid = new AnalyzerReportV2("2.1", original.taskId(), original.analyzer(), original.ruleSet(),
                original.status(), original.reviewability(), original.repository(), original.summary(), original.languages(),
                original.structure(), original.findings(), original.limitations(), original.generatedAt());
        Path target = temporary.resolve("existing.json");
        Files.writeString(target, "previous file");
        assertThatThrownBy(() -> files.save(invalid, target)).isInstanceOf(AnalyzerReportReadException.class);
        assertThat(Files.readString(target)).isEqualTo("previous file");
        try (var children = Files.list(temporary)) { assertThat(children.toList()).containsExactly(target); }
    }

    @Test
    void reportStringsRemainPlainTextAndRestrictionsStayVisible() throws Exception {
        ObjectNode tree = (ObjectNode) MAPPER.readTree(Files.readString(fixture("success")));
        ((ObjectNode) tree.path("findings").get(0)).put("message", "<img onerror=alert(1)> $(calc.exe)");
        Path source = temporary.resolve("text.json");
        Files.writeString(source, MAPPER.writeValueAsString(tree));
        var report = files.open(source);
        assertThat(new ReportPresentation().finding(report.findings().getFirst()))
                .contains("<img", "$(calc.exe)", "原始证据", "修复建议");
        assertThat(new ReportPresentation().limitations(files.open(fixture("partial-success"))))
                .contains("原始内容", "FILE_SKIPPED");
    }
}
