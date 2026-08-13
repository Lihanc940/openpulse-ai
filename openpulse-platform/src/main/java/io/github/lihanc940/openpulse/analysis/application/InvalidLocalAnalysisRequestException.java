package io.github.lihanc940.openpulse.analysis.application;

public class InvalidLocalAnalysisRequestException extends RuntimeException {

    public InvalidLocalAnalysisRequestException() {
        super("Local analysis request is invalid");
    }
}
