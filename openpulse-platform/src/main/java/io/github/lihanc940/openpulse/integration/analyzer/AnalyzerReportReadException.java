package io.github.lihanc940.openpulse.integration.analyzer;

public class AnalyzerReportReadException extends RuntimeException {

    public AnalyzerReportReadException(String message) {
        super(message);
    }

    public AnalyzerReportReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
