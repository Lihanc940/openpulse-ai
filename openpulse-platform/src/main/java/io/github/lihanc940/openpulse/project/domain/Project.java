package io.github.lihanc940.openpulse.project.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class Project {

    private final ProjectRepositoryKey repositoryKey;
    private final String owner;
    private final String name;
    private final String fullName;
    private final String canonicalUrl;
    private final String description;
    private final String defaultBranch;
    private final String primaryLanguage;
    private final long stars;
    private final long forks;
    private final boolean archived;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Project(
            ProjectRepositoryKey repositoryKey,
            String owner,
            String name,
            String fullName,
            String canonicalUrl,
            String description,
            String defaultBranch,
            String primaryLanguage,
            long stars,
            long forks,
            boolean archived,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey must not be null");
        this.owner = requireNonBlank(owner, "owner");
        this.name = requireNonBlank(name, "name");
        this.fullName = requireNonBlank(fullName, "fullName");
        this.canonicalUrl = requireNonBlank(canonicalUrl, "canonicalUrl");
        this.description = description;
        this.defaultBranch = requireNonBlank(defaultBranch, "defaultBranch");
        this.primaryLanguage = primaryLanguage;
        if (stars < 0 || forks < 0) {
            throw new IllegalArgumentException("Project stars and forks must not be negative");
        }
        this.stars = stars;
        this.forks = forks;
        this.archived = archived;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Project updatedAt must not be before createdAt");
        }
    }

    public static Project fromMetadata(GithubRepositoryMetadata metadata, Clock clock) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Instant now = Objects.requireNonNull(clock, "clock must not be null").instant();
        return fromMetadata(metadata, now, now);
    }

    public Project refresh(GithubRepositoryMetadata metadata, Clock clock) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        ProjectRepositoryKey refreshedKey = ProjectRepositoryKey.of(metadata.owner(), metadata.name());
        if (!repositoryKey.equals(refreshedKey)) {
            throw new IllegalArgumentException("Project metadata must refer to the same repository key");
        }
        Instant now = Objects.requireNonNull(clock, "clock must not be null").instant();
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Project refresh time must not move backwards");
        }
        return fromMetadata(metadata, createdAt, now);
    }

    public static Project rehydrate(
            ProjectRepositoryKey repositoryKey,
            String owner,
            String name,
            String fullName,
            String canonicalUrl,
            String description,
            String defaultBranch,
            String primaryLanguage,
            long stars,
            long forks,
            boolean archived,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new Project(
                repositoryKey,
                owner,
                name,
                fullName,
                canonicalUrl,
                description,
                defaultBranch,
                primaryLanguage,
                stars,
                forks,
                archived,
                createdAt,
                updatedAt
        );
    }

    private static Project fromMetadata(GithubRepositoryMetadata metadata, Instant createdAt, Instant updatedAt) {
        return new Project(
                ProjectRepositoryKey.of(metadata.owner(), metadata.name()),
                metadata.owner(),
                metadata.name(),
                metadata.fullName(),
                metadata.canonicalUrl(),
                metadata.description(),
                metadata.defaultBranch(),
                metadata.primaryLanguage(),
                metadata.stars(),
                metadata.forks(),
                metadata.archived(),
                createdAt,
                updatedAt
        );
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Project " + fieldName + " must not be blank");
        }
        return value;
    }

    public ProjectRepositoryKey repositoryKey() { return repositoryKey; }
    public String owner() { return owner; }
    public String name() { return name; }
    public String fullName() { return fullName; }
    public String canonicalUrl() { return canonicalUrl; }
    public String description() { return description; }
    public String defaultBranch() { return defaultBranch; }
    public String primaryLanguage() { return primaryLanguage; }
    public long stars() { return stars; }
    public long forks() { return forks; }
    public boolean archived() { return archived; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
