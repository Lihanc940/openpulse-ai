package io.github.lihanc940.openpulse.project.infrastructure.persistence;

import io.github.lihanc940.openpulse.project.domain.Project;
import io.github.lihanc940.openpulse.project.domain.ProjectRepositoryKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "projects")
public class ProjectJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_key", nullable = false, length = 255, unique = true)
    private String repositoryKey;

    @Column(name = "owner", nullable = false, length = 255)
    private String owner;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "full_name", nullable = false, length = 511)
    private String fullName;

    @Column(name = "canonical_url", nullable = false, length = 1024)
    private String canonicalUrl;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "default_branch", nullable = false, length = 255)
    private String defaultBranch;

    @Column(name = "primary_language", length = 255)
    private String primaryLanguage;

    @Column(name = "stars", nullable = false)
    private long stars;

    @Column(name = "forks", nullable = false)
    private long forks;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp(6)")
    private Instant updatedAt;

    protected ProjectJpaEntity() {
    }

    static ProjectJpaEntity create(Project project) {
        ProjectJpaEntity entity = new ProjectJpaEntity();
        entity.repositoryKey = project.repositoryKey().value();
        entity.createdAt = project.createdAt();
        entity.update(project);
        return entity;
    }

    void update(Project project) {
        owner = project.owner();
        name = project.name();
        fullName = project.fullName();
        canonicalUrl = project.canonicalUrl();
        description = project.description();
        defaultBranch = project.defaultBranch();
        primaryLanguage = project.primaryLanguage();
        stars = project.stars();
        forks = project.forks();
        archived = project.archived();
        updatedAt = project.updatedAt();
    }

    Project toDomain() {
        return Project.rehydrate(
                new ProjectRepositoryKey(repositoryKey),
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

    Long id() {
        return id;
    }

    public String repositoryKey() {
        return repositoryKey;
    }
}
