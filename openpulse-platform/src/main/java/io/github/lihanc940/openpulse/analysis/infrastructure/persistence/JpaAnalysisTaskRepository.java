package io.github.lihanc940.openpulse.analysis.infrastructure.persistence;

import io.github.lihanc940.openpulse.analysis.application.AnalysisTaskRepository;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTask;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.project.infrastructure.persistence.ProjectJpaEntity;
import io.github.lihanc940.openpulse.project.infrastructure.persistence.SpringDataProjectJpaRepository;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Repository
public class JpaAnalysisTaskRepository implements AnalysisTaskRepository {

    private final SpringDataAnalysisTaskJpaRepository taskRepository;
    private final SpringDataProjectJpaRepository projectRepository;

    public JpaAnalysisTaskRepository(
            SpringDataAnalysisTaskJpaRepository taskRepository,
            SpringDataProjectJpaRepository projectRepository
    ) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
    }

    @Override
    @Transactional
    public AnalysisTask save(AnalysisTask task) {
        Objects.requireNonNull(task, "task must not be null");
        try {
            Optional<AnalysisTaskJpaEntity> existing = taskRepository.findByTaskId(task.taskId().toString());
            AnalysisTaskJpaEntity entity;
            if (existing.isPresent()) {
                entity = existing.orElseThrow();
                if (entity.version() == null || entity.version() != task.version()) {
                    throw new PersistenceOperationException(
                            PersistenceFailure.CONCURRENT_MODIFICATION,
                            "Analysis task was modified by another operation."
                    );
                }
                entity.update(task);
            } else {
                ProjectJpaEntity project = projectRepository
                        .findByRepositoryKey(task.projectRepositoryKey().value())
                        .orElseThrow(() -> new PersistenceOperationException(
                                PersistenceFailure.RELATED_RECORD_NOT_FOUND,
                                "Analysis task project does not exist."
                        ));
                entity = AnalysisTaskJpaEntity.create(task, project);
            }
            return taskRepository.saveAndFlush(entity).toDomain();
        } catch (PersistenceOperationException exception) {
            throw exception;
        } catch (OptimisticLockingFailureException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.CONCURRENT_MODIFICATION,
                    "Analysis task was modified by another operation.",
                    exception
            );
        } catch (DataIntegrityViolationException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.CONSTRAINT_VIOLATION,
                    "Analysis task could not be saved because a database constraint was violated.",
                    exception
            );
        } catch (DataAccessException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.DATABASE_UNAVAILABLE,
                    "Analysis task could not be saved.",
                    exception
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnalysisTask> findByTaskId(AnalysisTaskId taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        try {
            return taskRepository.findByTaskId(taskId.toString()).map(AnalysisTaskJpaEntity::toDomain);
        } catch (DataAccessException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.DATABASE_UNAVAILABLE,
                    "Analysis task could not be read.",
                    exception
            );
        }
    }
}
