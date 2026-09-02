package io.github.lihanc940.openpulse.analysis.domain;

import io.github.lihanc940.openpulse.project.domain.ProjectRepositoryKey;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisTaskTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final ProjectRepositoryKey PROJECT_KEY = new ProjectRepositoryKey("openai/openpulse");

    @Test
    void followsPendingRunningSuccessLifecycleWithDeterministicDuration() {
        AnalysisTask pending = task();
        AnalysisTask running = pending.start(clock(CREATED_AT.plusSeconds(2)));
        AnalysisTask success = running.succeed(clock(CREATED_AT.plusSeconds(5)));

        assertThat(pending.status()).isEqualTo(AnalysisTaskStatus.PENDING);
        assertThat(running.status()).isEqualTo(AnalysisTaskStatus.RUNNING);
        assertThat(running.startedAt()).isEqualTo(CREATED_AT.plusSeconds(2));
        assertThat(success.status()).isEqualTo(AnalysisTaskStatus.SUCCESS);
        assertThat(success.completedAt()).isEqualTo(CREATED_AT.plusSeconds(5));
        assertThat(success.durationMs()).isEqualTo(3_000);
        assertThat(success.failureCode()).isNull();
        assertThat(success.failureMessage()).isNull();
    }

    @Test
    void recordsStableFailureCodeAndBoundedSafeMessage() {
        AnalysisTask failed = task()
                .start(clock(CREATED_AT.plusSeconds(1)))
                .fail(
                        AnalysisFailureCode.ANALYZER_FAILED,
                        "Analyzer could not produce a valid report.",
                        clock(CREATED_AT.plusSeconds(3))
                );

        assertThat(failed.status()).isEqualTo(AnalysisTaskStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo(AnalysisFailureCode.ANALYZER_FAILED);
        assertThat(failed.durationMs()).isEqualTo(2_000);

        assertThatThrownBy(() -> task()
                .start(clock(CREATED_AT.plusSeconds(1)))
                .fail(
                        AnalysisFailureCode.INTERNAL_ERROR,
                        "stack trace\n at private.path.Service",
                        clock(CREATED_AT.plusSeconds(2))
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void rejectsSkippedBackwardAndRepeatedTransitions() {
        AnalysisTask pending = task();
        AnalysisTask running = pending.start(clock(CREATED_AT.plusSeconds(1)));
        AnalysisTask success = running.succeed(clock(CREATED_AT.plusSeconds(2)));

        assertThatThrownBy(() -> pending.succeed(clock(CREATED_AT.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> running.start(clock(CREATED_AT.plusSeconds(2))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> success.start(clock(CREATED_AT.plusSeconds(3))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> success.succeed(clock(CREATED_AT.plusSeconds(3))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> running.succeed(clock(CREATED_AT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before start time");
    }

    @Test
    void rejectsInconsistentRehydratedTerminalStates() {
        assertThatThrownBy(() -> AnalysisTask.rehydrate(
                AnalysisTaskId.create(),
                PROJECT_KEY,
                AnalysisTaskStatus.SUCCESS,
                AnalysisFailureCode.INTERNAL_ERROR,
                null,
                CREATED_AT,
                CREATED_AT.plusSeconds(1),
                CREATED_AT.plusSeconds(2),
                1_000L,
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Successful");

        assertThatThrownBy(() -> AnalysisTask.rehydrate(
                AnalysisTaskId.create(),
                PROJECT_KEY,
                AnalysisTaskStatus.FAILED,
                null,
                null,
                CREATED_AT,
                CREATED_AT.plusSeconds(1),
                CREATED_AT.plusSeconds(2),
                1_000L,
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed");
    }

    private AnalysisTask task() {
        return AnalysisTask.create(
                new AnalysisTaskId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                PROJECT_KEY,
                clock(CREATED_AT)
        );
    }

    private Clock clock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
