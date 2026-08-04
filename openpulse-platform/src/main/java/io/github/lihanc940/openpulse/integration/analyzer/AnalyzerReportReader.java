package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class AnalyzerReportReader {

    private static final String SUPPORTED_PROTOCOL_VERSION = "1.0";

    private final ObjectMapper objectMapper;

    public AnalyzerReportReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AnalyzerReport read(Path reportPath) {
        if (reportPath == null) {
            throw new AnalyzerReportReadException("Analyzer report path must not be null");
        }
        if (!Files.exists(reportPath)) {
            throw new AnalyzerReportReadException("Analyzer report file does not exist: " + reportPath);
        }
        if (!Files.isRegularFile(reportPath)) {
            throw new AnalyzerReportReadException("Analyzer report path is not a regular file: " + reportPath);
        }

        AnalyzerReport report = readJson(reportPath);
        validate(report, reportPath);
        return report;
    }

    private AnalyzerReport readJson(Path reportPath) {
        try {
            return objectMapper.readValue(reportPath.toFile(), AnalyzerReport.class);
        } catch (JacksonException exception) {
            throw new AnalyzerReportReadException("Analyzer report JSON is malformed: " + reportPath, exception);
        }
    }

    private void validate(AnalyzerReport report, Path reportPath) {
        requireNonNull(report, "report", reportPath);
        if (!SUPPORTED_PROTOCOL_VERSION.equals(report.protocolVersion())) {
            throw new AnalyzerReportReadException(
                    "Unsupported analyzer report protocol version '" + report.protocolVersion() + "' in file: " + reportPath
            );
        }
        if (report.taskId() == null || report.taskId().isBlank()) {
            throw new AnalyzerReportReadException("Analyzer report taskId must not be blank: " + reportPath);
        }

        requireNonNull(report.status(), "status", reportPath);
        requireNonNull(report.repository(), "repository", reportPath);
        requireNonNull(report.summary(), "summary", reportPath);
        requireNonNull(report.languages(), "languages", reportPath);
        requireNonNull(report.structure(), "structure", reportPath);
        requireNonNull(report.quality(), "quality", reportPath);
        requireNonNull(report.risks(), "risks", reportPath);
        requireNonNull(report.dependencies(), "dependencies", reportPath);
        requireNonNull(report.generatedAt(), "generatedAt", reportPath);

        requireNonNull(report.structure().buildFiles(), "structure.buildFiles", reportPath);
        requireNonNull(report.dependencies().nodes(), "dependencies.nodes", reportPath);
        requireNonNull(report.dependencies().edges(), "dependencies.edges", reportPath);
        requireRiskLists(report.risks(), reportPath);
    }

    private void requireRiskLists(List<AnalyzerReport.Risk> risks, Path reportPath) {
        for (int index = 0; index < risks.size(); index++) {
            AnalyzerReport.Risk risk = risks.get(index);
            requireNonNull(risk, "risks[" + index + "]", reportPath);
            requireNonNull(risk.evidence(), "risks[" + index + "].evidence", reportPath);
        }
    }

    private void requireNonNull(Object value, String fieldName, Path reportPath) {
        if (value == null) {
            throw new AnalyzerReportReadException(
                    "Analyzer report required field '" + fieldName + "' is missing in file: " + reportPath
            );
        }
    }
}
