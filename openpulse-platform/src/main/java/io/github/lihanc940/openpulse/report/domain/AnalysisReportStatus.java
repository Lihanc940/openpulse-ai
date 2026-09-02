package io.github.lihanc940.openpulse.report.domain;

public enum AnalysisReportStatus {
    SUCCESS,
    FAILED,
    PARTIAL_SUCCESS;

    public boolean isUsableResult() {
        return this == SUCCESS || this == PARTIAL_SUCCESS;
    }
}
