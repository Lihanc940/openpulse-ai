package io.github.lihanc940.openpulse.report.domain;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import java.time.Instant;
import java.util.Objects;

public record AnalysisReportRecord(
        AnalysisTaskId analysisTaskId,
        String protocolVersion,
        String analyzerTaskId,
        AnalysisReportStatus reportStatus,
        String reportJson,
        Instant generatedAt,
        Instant createdAt
) {

    public AnalysisReportRecord {
        Objects.requireNonNull(analysisTaskId, "analysisTaskId must not be null");
        requireNonBlank(protocolVersion, "protocolVersion");
        requireNonBlank(analyzerTaskId, "analyzerTaskId");
        Objects.requireNonNull(reportStatus, "reportStatus must not be null");
        requireNonBlank(reportJson, "reportJson");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Analysis report " + fieldName + " must not be blank");
        }
    }
}
