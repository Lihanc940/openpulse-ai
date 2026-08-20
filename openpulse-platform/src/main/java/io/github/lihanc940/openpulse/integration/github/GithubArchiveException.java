package io.github.lihanc940.openpulse.integration.github;

public class GithubArchiveException extends RuntimeException {

    private final GithubArchiveFailure failure;

    public GithubArchiveException(GithubArchiveFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public GithubArchiveException(GithubArchiveFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public GithubArchiveFailure failure() {
        return failure;
    }
}
