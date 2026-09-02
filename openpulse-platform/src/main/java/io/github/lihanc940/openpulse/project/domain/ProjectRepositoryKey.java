package io.github.lihanc940.openpulse.project.domain;

import java.util.Locale;

public record ProjectRepositoryKey(String value) {

    private static final int MAX_LENGTH = 255;

    public ProjectRepositoryKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Project repository key must not be blank");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('/');
        if (separator <= 0 || separator != normalized.lastIndexOf('/') || separator == normalized.length() - 1) {
            throw new IllegalArgumentException("Project repository key must use the owner/name form");
        }
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Project repository key is too long");
        }
        value = normalized;
    }

    public static ProjectRepositoryKey of(String owner, String name) {
        requireSegment(owner, "owner");
        requireSegment(name, "name");
        return new ProjectRepositoryKey(owner + "/" + name);
    }

    private static void requireSegment(String value, String fieldName) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Project repository " + fieldName + " is invalid");
        }
    }
}
