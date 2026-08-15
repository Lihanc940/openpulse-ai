package io.github.lihanc940.openpulse.project.domain;

public record GithubRepositoryMetadata(
        String owner,
        String name,
        String fullName,
        String canonicalUrl,
        String description,
        String defaultBranch,
        String primaryLanguage,
        long stars,
        long forks,
        boolean archived
) {

    public GithubRepositoryMetadata {
        requireNonBlank(owner, "owner");
        requireNonBlank(name, "name");
        requireNonBlank(fullName, "fullName");
        requireNonBlank(canonicalUrl, "canonicalUrl");
        requireNonBlank(defaultBranch, "defaultBranch");
        if (stars < 0) {
            throw new IllegalArgumentException("GitHub repository stars must not be negative");
        }
        if (forks < 0) {
            throw new IllegalArgumentException("GitHub repository forks must not be negative");
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitHub repository " + fieldName + " must not be blank");
        }
    }
}
