package io.github.lihanc940.openpulse.project.domain;

import io.github.lihanc940.openpulse.integration.github.GithubArchiveException;
import io.github.lihanc940.openpulse.integration.github.GithubArchiveFailure;

import java.io.IOException;
import java.nio.file.Path;

public final class DownloadedRepositoryWorkspace implements AutoCloseable {

    private final Path workspaceRoot;
    private final Path repositoryRoot;
    private final long archiveBytes;
    private final long extractedBytes;
    private final int entryCount;
    private final Cleanup cleanup;
    private boolean closed;

    public DownloadedRepositoryWorkspace(
            Path workspaceRoot,
            Path repositoryRoot,
            long archiveBytes,
            long extractedBytes,
            int entryCount,
            Cleanup cleanup
    ) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        if (this.repositoryRoot.equals(this.workspaceRoot)
                || !this.repositoryRoot.startsWith(this.workspaceRoot)) {
            throw new IllegalArgumentException("Repository root must be inside its temporary workspace");
        }
        if (archiveBytes < 0 || extractedBytes < 0 || entryCount < 0 || cleanup == null) {
            throw new IllegalArgumentException("Repository workspace statistics and cleanup must be valid");
        }
        this.archiveBytes = archiveBytes;
        this.extractedBytes = extractedBytes;
        this.entryCount = entryCount;
        this.cleanup = cleanup;
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public long archiveBytes() {
        return archiveBytes;
    }

    public long extractedBytes() {
        return extractedBytes;
    }

    public int entryCount() {
        return entryCount;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            cleanup.clean();
            closed = true;
        } catch (IOException exception) {
            throw new GithubArchiveException(
                    GithubArchiveFailure.CLEANUP_FAILED,
                    "Failed to clean the GitHub repository temporary workspace."
            );
        }
    }

    @FunctionalInterface
    public interface Cleanup {
        void clean() throws IOException;
    }
}
