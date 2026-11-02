package io.github.lihanc940.openpulse.integration.analyzer.model;

import java.time.OffsetDateTime;

public sealed interface AnalyzerReportDocument permits AnalyzerReport, AnalyzerReportV2 {

    String protocolVersion();

    String taskId();

    String reportStatus();

    OffsetDateTime generatedAt();
}
