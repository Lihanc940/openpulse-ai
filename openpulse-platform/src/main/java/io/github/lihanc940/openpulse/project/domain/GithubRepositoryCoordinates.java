package io.github.lihanc940.openpulse.project.domain;

public record GithubRepositoryCoordinates(
        String owner,
        String repository,
        String canonicalUrl
) {

    public GithubRepositoryCoordinates {
        requireNonBlank(owner, "owner");
        requireNonBlank(repository, "repository");
        requireNonBlank(canonicalUrl, "canonicalUrl");
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitHub repository " + fieldName + " must not be blank");
        }
    }
}
