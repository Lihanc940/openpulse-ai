package io.github.lihanc940.openpulse.integration.analyzer;

public class AnalyzerReportReadException extends RuntimeException {

    private final AnalyzerReportReadFailure failure;

    public AnalyzerReportReadException(AnalyzerReportReadFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public AnalyzerReportReadException(
            AnalyzerReportReadFailure failure,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failure = failure;
    }

    public AnalyzerReportReadFailure failure() {
        return failure;
    }
}
