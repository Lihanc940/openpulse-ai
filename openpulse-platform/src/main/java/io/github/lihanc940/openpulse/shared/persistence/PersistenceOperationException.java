package io.github.lihanc940.openpulse.shared.persistence;

public class PersistenceOperationException extends RuntimeException {

    private final PersistenceFailure failure;

    public PersistenceOperationException(PersistenceFailure failure, String safeMessage) {
        super(safeMessage);
        this.failure = failure;
    }

    public PersistenceOperationException(PersistenceFailure failure, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.failure = failure;
    }

    public PersistenceFailure failure() {
        return failure;
    }
}
