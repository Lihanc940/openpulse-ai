package io.github.lihanc940.openpulse.shared.persistence;

public enum PersistenceFailure {
    INVALID_DATA,
    RELATED_RECORD_NOT_FOUND,
    CONSTRAINT_VIOLATION,
    CONCURRENT_MODIFICATION,
    DATABASE_UNAVAILABLE
}
