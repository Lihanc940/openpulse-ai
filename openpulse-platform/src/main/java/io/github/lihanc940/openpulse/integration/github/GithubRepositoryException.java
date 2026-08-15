package io.github.lihanc940.openpulse.integration.github;

public class GithubRepositoryException extends RuntimeException {

    private final GithubRepositoryFailure failure;

    public GithubRepositoryException(GithubRepositoryFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public GithubRepositoryFailure failure() {
        return failure;
    }
}
