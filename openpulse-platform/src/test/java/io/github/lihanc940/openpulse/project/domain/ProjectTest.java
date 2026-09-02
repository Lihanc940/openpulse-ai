package io.github.lihanc940.openpulse.project.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void normalizesRepositoryKeyWithoutChangingDisplayFields() {
        Project project = Project.fromMetadata(
                metadata("OpenAI", "OpenPulse", 10, 2),
                fixedClock(CREATED_AT)
        );

        assertThat(project.repositoryKey()).isEqualTo(new ProjectRepositoryKey("openai/openpulse"));
        assertThat(project.owner()).isEqualTo("OpenAI");
        assertThat(project.name()).isEqualTo("OpenPulse");
        assertThat(project.createdAt()).isEqualTo(CREATED_AT);
        assertThat(project.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void refreshesMutableMetadataAndPreservesCreationTime() {
        Project original = Project.fromMetadata(
                metadata("OpenAI", "OpenPulse", 10, 2),
                fixedClock(CREATED_AT)
        );
        Instant refreshedAt = CREATED_AT.plusSeconds(60);

        Project refreshed = original.refresh(
                metadata("OPENAI", "OPENPULSE", 20, 4),
                fixedClock(refreshedAt)
        );

        assertThat(refreshed.repositoryKey()).isEqualTo(original.repositoryKey());
        assertThat(refreshed.stars()).isEqualTo(20);
        assertThat(refreshed.forks()).isEqualTo(4);
        assertThat(refreshed.createdAt()).isEqualTo(CREATED_AT);
        assertThat(refreshed.updatedAt()).isEqualTo(refreshedAt);
    }

    @Test
    void rejectsMissingFieldsNegativeCountsAndInvalidKeys() {
        assertThatThrownBy(() -> Project.rehydrate(
                new ProjectRepositoryKey("owner/repository"),
                "owner",
                "repository",
                "owner/repository",
                "https://github.com/owner/repository",
                null,
                "main",
                null,
                -1,
                0,
                false,
                CREATED_AT,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");

        assertThatThrownBy(() -> Project.rehydrate(
                new ProjectRepositoryKey("owner/repository"),
                " ",
                "repository",
                "owner/repository",
                "https://github.com/owner/repository",
                null,
                "main",
                null,
                0,
                0,
                false,
                CREATED_AT,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");

        assertThatThrownBy(() -> new ProjectRepositoryKey("owner/repository/extra"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/name");
    }

    private GithubRepositoryMetadata metadata(String owner, String name, long stars, long forks) {
        return new GithubRepositoryMetadata(
                owner,
                name,
                owner + "/" + name,
                "https://github.com/" + owner + "/" + name,
                "description",
                "main",
                "Java",
                stars,
                forks,
                false
        );
    }

    private Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
