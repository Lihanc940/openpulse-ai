package io.github.lihanc940.openpulse.integration.github;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubArchivePropertiesTest {

    @Test
    void acceptsStructuredPositiveLimits() {
        GithubArchiveProperties properties = new GithubArchiveProperties(
                Duration.ofSeconds(60),
                DataSize.ofMegabytes(50),
                DataSize.ofMegabytes(250),
                50_000
        );

        assertThat(properties.maxArchiveBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(properties.maxExtractedBytes()).isEqualTo(250L * 1024 * 1024);
        assertThat(properties.maxEntryCount()).isEqualTo(50_000);
    }

    @Test
    void allowsSmallPositiveLimitsForBoundaryTests() {
        GithubArchiveProperties properties = new GithubArchiveProperties(
                Duration.ofMillis(1),
                DataSize.ofBytes(1),
                DataSize.ofBytes(1),
                1
        );

        assertThat(properties.maxArchiveBytes()).isOne();
        assertThat(properties.maxExtractedBytes()).isOne();
        assertThat(properties.maxEntryCount()).isOne();
    }

    @Test
    void rejectsNonPositiveTimeoutSizesAndEntryCount() {
        assertThatThrownBy(() -> properties(Duration.ZERO, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read timeout");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(-1), 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read timeout");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archive size");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 1, -1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extracted size");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry count");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 1, 1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry count");
    }

    private GithubArchiveProperties properties(
            Duration timeout,
            long archiveBytes,
            long extractedBytes,
            int entryCount
    ) {
        return new GithubArchiveProperties(
                timeout,
                DataSize.ofBytes(archiveBytes),
                DataSize.ofBytes(extractedBytes),
                entryCount
        );
    }
}
