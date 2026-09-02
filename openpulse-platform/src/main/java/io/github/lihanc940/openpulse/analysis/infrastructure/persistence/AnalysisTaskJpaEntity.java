package io.github.lihanc940.openpulse.analysis.infrastructure.persistence;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisFailureCode;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTask;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskStatus;
import io.github.lihanc940.openpulse.project.domain.ProjectRepositoryKey;
import io.github.lihanc940.openpulse.project.infrastructure.persistence.ProjectJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "analysis_tasks")
public class AnalysisTaskJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 36, unique = true, columnDefinition = "char(36)")
    private String taskId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectJpaEntity project;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AnalysisTaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 64)
    private AnalysisFailureCode failureCode;

    @Column(name = "failure_message", length = AnalysisTask.MAX_FAILURE_MESSAGE_LENGTH)
    private String failureMessage;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6)")
    private Instant createdAt;

    @Column(name = "started_at", columnDefinition = "timestamp(6)")
    private Instant startedAt;

    @Column(name = "completed_at", columnDefinition = "timestamp(6)")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected AnalysisTaskJpaEntity() {
    }

    static AnalysisTaskJpaEntity create(AnalysisTask task, ProjectJpaEntity project) {
        AnalysisTaskJpaEntity entity = new AnalysisTaskJpaEntity();
        entity.taskId = task.taskId().toString();
        entity.project = project;
        entity.update(task);
        return entity;
    }

    void update(AnalysisTask task) {
        status = task.status();
        failureCode = task.failureCode();
        failureMessage = task.failureMessage();
        createdAt = task.createdAt();
        startedAt = task.startedAt();
        completedAt = task.completedAt();
        durationMs = task.durationMs();
    }

    AnalysisTask toDomain() {
        return AnalysisTask.rehydrate(
                AnalysisTaskId.parse(taskId),
                new ProjectRepositoryKey(project.repositoryKey()),
                status,
                failureCode,
                failureMessage,
                createdAt,
                startedAt,
                completedAt,
                durationMs,
                version == null ? 0 : version
        );
    }

    public String taskId() {
        return taskId;
    }

    public AnalysisTaskStatus status() {
        return status;
    }

    Long version() {
        return version;
    }
}
