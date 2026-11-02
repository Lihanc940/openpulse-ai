package io.github.lihanc940.openpulse.integration.analyzer;

public class AnalyzerExecutionException extends RuntimeException {

    private final AnalyzerExecutionFailure failure;
    private final Integer exitCode;

    public AnalyzerExecutionException(AnalyzerExecutionFailure failure, String message) {
        this(failure, null, message, null);
    }

    public AnalyzerExecutionException(
            AnalyzerExecutionFailure failure,
            String message,
            Throwable cause
    ) {
        this(failure, null, message, cause);
    }

    public AnalyzerExecutionException(
            AnalyzerExecutionFailure failure,
            int exitCode,
            String message
    ) {
        this(failure, exitCode, message, null);
    }

    private AnalyzerExecutionException(
            AnalyzerExecutionFailure failure,
            Integer exitCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failure = failure;
        this.exitCode = exitCode;
    }

    public AnalyzerExecutionFailure failure() {
        return failure;
    }

    public Integer exitCode() {
        return exitCode;
    }
}
