package io.github.lihanc940.openpulse.analysis.domain;

import java.util.Objects;
import java.util.UUID;

public record AnalysisTaskId(UUID value) {

    public AnalysisTaskId {
        Objects.requireNonNull(value, "Analysis task id must not be null");
    }

    public static AnalysisTaskId create() {
        return new AnalysisTaskId(UUID.randomUUID());
    }

    public static AnalysisTaskId parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Analysis task id must not be blank");
        }
        return new AnalysisTaskId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
