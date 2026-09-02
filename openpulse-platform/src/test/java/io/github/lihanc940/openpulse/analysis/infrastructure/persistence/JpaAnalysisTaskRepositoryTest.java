package io.github.lihanc940.openpulse.analysis.infrastructure.persistence;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTask;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.project.domain.ProjectRepositoryKey;
import io.github.lihanc940.openpulse.project.infrastructure.persistence.ProjectJpaEntity;
import io.github.lihanc940.openpulse.project.infrastructure.persistence.SpringDataProjectJpaRepository;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaAnalysisTaskRepositoryTest {

    @Mock
    private SpringDataAnalysisTaskJpaRepository taskRepository;

    @Mock
    private SpringDataProjectJpaRepository projectRepository;

    @InjectMocks
    private JpaAnalysisTaskRepository repository;

    @Test
    void mapsSpringOptimisticLockFailureToConcurrentModification() {
        ProjectRepositoryKey projectKey = ProjectRepositoryKey.of("OpenPulse", "Demo");
        AnalysisTask task = AnalysisTask.create(
                AnalysisTaskId.create(),
                projectKey,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC)
        );
        when(taskRepository.findByTaskId(task.taskId().toString())).thenReturn(Optional.empty());
        when(projectRepository.findByRepositoryKey(projectKey.value()))
                .thenReturn(Optional.of(mock(ProjectJpaEntity.class)));
        when(taskRepository.saveAndFlush(any(AnalysisTaskJpaEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(AnalysisTaskJpaEntity.class, 1L));

        assertThatThrownBy(() -> repository.save(task))
                .isInstanceOf(PersistenceOperationException.class)
                .extracting(exception -> ((PersistenceOperationException) exception).failure())
                .isEqualTo(PersistenceFailure.CONCURRENT_MODIFICATION);
    }
}
