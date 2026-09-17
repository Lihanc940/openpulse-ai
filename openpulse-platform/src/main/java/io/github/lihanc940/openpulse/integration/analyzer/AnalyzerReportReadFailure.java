package io.github.lihanc940.openpulse.integration.analyzer;

public enum AnalyzerReportReadFailure {
    INVALID_FILE,
    FILE_TOO_LARGE,
    MALFORMED_JSON,
    INVALID_ROOT,
    INVALID_PROTOCOL_VERSION,
    UNSUPPORTED_PROTOCOL_VERSION,
    SCHEMA_VIOLATION,
    MODEL_MAPPING_FAILED,
    SEMANTIC_VIOLATION
}
