package io.github.lihanc940.openpulse.integration.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties("openpulse.github.archive")
public record GithubArchiveProperties(
        Duration readTimeout,
        DataSize maxArchiveSize,
        DataSize maxExtractedSize,
        int maxEntryCount
) {

    public GithubArchiveProperties {
        requirePositive(readTimeout, "read timeout");
        requirePositive(maxArchiveSize, "maximum archive size");
        requirePositive(maxExtractedSize, "maximum extracted size");
        if (maxEntryCount <= 0) {
            throw new IllegalArgumentException("GitHub archive maximum entry count must be positive");
        }
    }

    public long maxArchiveBytes() {
        return maxArchiveSize.toBytes();
    }

    public long maxExtractedBytes() {
        return maxExtractedSize.toBytes();
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("GitHub archive " + name + " must be positive");
        }
    }

    private static void requirePositive(DataSize value, String name) {
        if (value == null || value.toBytes() <= 0) {
            throw new IllegalArgumentException("GitHub archive " + name + " must be positive");
        }
    }
}
