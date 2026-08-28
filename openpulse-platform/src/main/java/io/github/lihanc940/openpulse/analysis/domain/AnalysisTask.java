package io.github.lihanc940.openpulse.analysis.domain;

import io.github.lihanc940.openpulse.project.domain.ProjectRepositoryKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class AnalysisTask {

    public static final int MAX_FAILURE_MESSAGE_LENGTH = 500;

    private final AnalysisTaskId taskId;
    private final ProjectRepositoryKey projectRepositoryKey;
    private final AnalysisTaskStatus status;
    private final AnalysisFailureCode failureCode;
    private final String failureMessage;
    private final Instant createdAt;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Long durationMs;
    private final long version;

    private AnalysisTask(
            AnalysisTaskId taskId,
            ProjectRepositoryKey projectRepositoryKey,
            AnalysisTaskStatus status,
            AnalysisFailureCode failureCode,
            String failureMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            long version
    ) {
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.projectRepositoryKey = Objects.requireNonNull(
                projectRepositoryKey,
                "projectRepositoryKey must not be null"
        );
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("Analysis task version must not be negative");
        }
        this.version = version;
        validateState(status, failureCode, failureMessage, startedAt, completedAt, durationMs, createdAt);
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
    }

    public static AnalysisTask create(
            AnalysisTaskId taskId,
            ProjectRepositoryKey projectRepositoryKey,
            Clock clock
    ) {
        return new AnalysisTask(
                taskId,
                projectRepositoryKey,
                AnalysisTaskStatus.PENDING,
                null,
                null,
                Objects.requireNonNull(clock, "clock must not be null").instant(),
                null,
                null,
                null,
                0
        );
    }

    public AnalysisTask start(Clock clock) {
        requireStatus(AnalysisTaskStatus.PENDING);
        Instant started = Objects.requireNonNull(clock, "clock must not be null").instant();
        return new AnalysisTask(
                taskId,
                projectRepositoryKey,
                AnalysisTaskStatus.RUNNING,
                null,
                null,
                createdAt,
                started,
                null,
                null,
                version
        );
    }

    public AnalysisTask succeed(Clock clock) {
        requireStatus(AnalysisTaskStatus.RUNNING);
        Instant completed = Objects.requireNonNull(clock, "clock must not be null").instant();
        return completed(AnalysisTaskStatus.SUCCESS, null, null, completed);
    }

    public AnalysisTask fail(AnalysisFailureCode code, String safeMessage, Clock clock) {
        requireStatus(AnalysisTaskStatus.RUNNING);
        Objects.requireNonNull(code, "failure code must not be null");
        validateFailureMessage(safeMessage);
        Instant completed = Objects.requireNonNull(clock, "clock must not be null").instant();
        return completed(AnalysisTaskStatus.FAILED, code, safeMessage, completed);
    }

    public static AnalysisTask rehydrate(
            AnalysisTaskId taskId,
            ProjectRepositoryKey projectRepositoryKey,
            AnalysisTaskStatus status,
            AnalysisFailureCode failureCode,
            String failureMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            long version
    ) {
        return new AnalysisTask(
                taskId,
                projectRepositoryKey,
                status,
                failureCode,
                failureMessage,
                createdAt,
                startedAt,
                completedAt,
                durationMs,
                version
        );
    }

    private AnalysisTask completed(
            AnalysisTaskStatus completedStatus,
            AnalysisFailureCode completedFailureCode,
            String completedFailureMessage,
            Instant completed
    ) {
        if (completed.isBefore(startedAt)) {
            throw new IllegalArgumentException("Analysis task completion time must not be before start time");
        }
        long elapsedMillis = Duration.between(startedAt, completed).toMillis();
        return new AnalysisTask(
                taskId,
                projectRepositoryKey,
                completedStatus,
                completedFailureCode,
                completedFailureMessage,
                createdAt,
                startedAt,
                completed,
                elapsedMillis,
                version
        );
    }

    private void requireStatus(AnalysisTaskStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Analysis task cannot transition from " + status + "; expected " + expected
            );
        }
    }

    private static void validateState(
            AnalysisTaskStatus status,
            AnalysisFailureCode failureCode,
            String failureMessage,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            Instant createdAt
    ) {
        if (startedAt != null && startedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Analysis task start time must not be before creation time");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("Analysis task duration must not be negative");
        }
        validateFailureMessage(failureMessage);
        switch (status) {
            case PENDING -> requireState(
                    startedAt == null && completedAt == null && durationMs == null
                            && failureCode == null && failureMessage == null,
                    "Pending analysis task fields are inconsistent"
            );
            case RUNNING -> requireState(
                    startedAt != null && completedAt == null && durationMs == null
                            && failureCode == null && failureMessage == null,
                    "Running analysis task fields are inconsistent"
            );
            case SUCCESS -> requireState(
                    startedAt != null && completedAt != null && durationMs != null
                            && failureCode == null && failureMessage == null,
                    "Successful analysis task fields are inconsistent"
            );
            case FAILED -> requireState(
                    startedAt != null && completedAt != null && durationMs != null && failureCode != null,
                    "Failed analysis task fields are inconsistent"
            );
        }
        if (completedAt != null && (startedAt == null || completedAt.isBefore(startedAt))) {
            throw new IllegalArgumentException("Analysis task completion time is inconsistent");
        }
        if (completedAt != null && durationMs != Duration.between(startedAt, completedAt).toMillis()) {
            throw new IllegalArgumentException("Analysis task duration does not match its timestamps");
        }
    }

    private static void validateFailureMessage(String message) {
        if (message == null) {
            return;
        }
        if (message.length() > MAX_FAILURE_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Analysis task failure message is too long");
        }
        if (message.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Analysis task failure message must not contain control characters");
        }
    }

    private static void requireState(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public AnalysisTaskId taskId() { return taskId; }
    public ProjectRepositoryKey projectRepositoryKey() { return projectRepositoryKey; }
    public AnalysisTaskStatus status() { return status; }
    public AnalysisFailureCode failureCode() { return failureCode; }
    public String failureMessage() { return failureMessage; }
    public Instant createdAt() { return createdAt; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public Long durationMs() { return durationMs; }
    public long version() { return version; }
}
