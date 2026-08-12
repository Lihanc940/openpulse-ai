package io.github.lihanc940.openpulse.analysis.api;

import io.github.lihanc940.openpulse.analysis.application.InvalidLocalAnalysisRequestException;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerExecutionException;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerExecutionFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LocalAnalysisController.class)
public class LocalAnalysisExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalAnalysisExceptionHandler.class);
    private static final String INVALID_REQUEST_MESSAGE = "Request body is invalid.";
    private static final String INVALID_PATH_MESSAGE = "Repository path is invalid or unavailable.";
    private static final String ANALYZER_UNAVAILABLE_MESSAGE = "Analyzer is temporarily unavailable.";
    private static final String ANALYZER_TIMEOUT_MESSAGE = "Analyzer timed out.";
    private static final String ANALYZER_FAILED_MESSAGE = "Analyzer failed to produce a valid report.";
    private static final String INTERNAL_ERROR_MESSAGE = "An internal error occurred.";

    @ExceptionHandler({InvalidLocalAnalysisRequestException.class, HttpMessageNotReadableException.class})
    ResponseEntity<AnalysisErrorResponse> handleInvalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", INVALID_REQUEST_MESSAGE);
    }

    @ExceptionHandler(AnalyzerExecutionException.class)
    ResponseEntity<AnalysisErrorResponse> handleAnalyzerExecution(AnalyzerExecutionException exception) {
        AnalyzerExecutionFailure failure = exception.failure();
        return switch (failure) {
            case INVALID_REPOSITORY_PATH -> error(HttpStatus.BAD_REQUEST, failure.name(), INVALID_PATH_MESSAGE);
            case START_FAILED -> error(HttpStatus.SERVICE_UNAVAILABLE, failure.name(), ANALYZER_UNAVAILABLE_MESSAGE);
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, failure.name(), ANALYZER_TIMEOUT_MESSAGE);
            case INVALID_ARGUMENTS, REPOSITORY_NOT_FOUND, SCAN_FAILED, REPORT_OUTPUT_FAILED,
                    UNKNOWN_EXIT_CODE, REPORT_MISSING, REPORT_INVALID ->
                    error(HttpStatus.BAD_GATEWAY, failure.name(), ANALYZER_FAILED_MESSAGE);
            case TEMPORARY_DIRECTORY_CREATION_FAILED, INTERRUPTED, CLEANUP_FAILED ->
                    error(HttpStatus.INTERNAL_SERVER_ERROR, failure.name(), INTERNAL_ERROR_MESSAGE);
        };
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<AnalysisErrorResponse> handleUnexpected(Exception exception) {
        LOGGER.error("Unexpected local analysis API failure", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE);
    }

    private ResponseEntity<AnalysisErrorResponse> error(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new AnalysisErrorResponse(error, message));
    }
}
