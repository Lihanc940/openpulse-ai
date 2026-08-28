package io.github.lihanc940.openpulse.project.infrastructure.persistence;

import io.github.lihanc940.openpulse.project.application.ProjectRepository;
import io.github.lihanc940.openpulse.project.domain.Project;
import io.github.lihanc940.openpulse.project.domain.ProjectRepositoryKey;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Repository
public class JpaProjectRepository implements ProjectRepository {

    private final SpringDataProjectJpaRepository repository;

    public JpaProjectRepository(SpringDataProjectJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Project save(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        try {
            ProjectJpaEntity entity = repository.findByRepositoryKey(project.repositoryKey().value())
                    .map(existing -> {
                        existing.update(project);
                        return existing;
                    })
                    .orElseGet(() -> ProjectJpaEntity.create(project));
            return repository.saveAndFlush(entity).toDomain();
        } catch (DataIntegrityViolationException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.CONSTRAINT_VIOLATION,
                    "Project could not be saved because a database constraint was violated.",
                    exception
            );
        } catch (DataAccessException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.DATABASE_UNAVAILABLE,
                    "Project could not be saved.",
                    exception
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Project> findByRepositoryKey(ProjectRepositoryKey repositoryKey) {
        Objects.requireNonNull(repositoryKey, "repositoryKey must not be null");
        try {
            return repository.findByRepositoryKey(repositoryKey.value()).map(ProjectJpaEntity::toDomain);
        } catch (DataAccessException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.DATABASE_UNAVAILABLE,
                    "Project could not be read.",
                    exception
            );
        }
    }
}
