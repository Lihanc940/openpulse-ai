package io.github.lihanc940.openpulse.integration.github;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;

@Component
public class GithubWorkspaceCleaner {

    private static final String WORKSPACE_PREFIX = "openpulse-github-";
    private static final int CLEANUP_ATTEMPTS = 10;
    private static final Duration CLEANUP_RETRY_DELAY = Duration.ofMillis(50);

    public void clean(Path workspaceRoot) throws IOException {
        Path normalizedRoot = validateOwnedWorkspace(workspaceRoot);
        IOException deletionFailure = null;
        for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++) {
            try {
                deleteOnce(normalizedRoot);
                return;
            } catch (IOException exception) {
                deletionFailure = exception;
                if (attempt < CLEANUP_ATTEMPTS) {
                    waitBeforeRetry();
                }
            }
        }
        throw deletionFailure;
    }

    private Path validateOwnedWorkspace(Path workspaceRoot) throws IOException {
        if (workspaceRoot == null || !workspaceRoot.isAbsolute()) {
            throw new IOException("Temporary workspace ownership validation failed");
        }
        Path normalizedRoot = workspaceRoot.normalize();
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!normalizedRoot.equals(workspaceRoot)
                || normalizedRoot.getFileName() == null
                || !normalizedRoot.getFileName().toString().startsWith(WORKSPACE_PREFIX)
                || !tempRoot.equals(normalizedRoot.getParent())
                || Files.isSymbolicLink(normalizedRoot)) {
            throw new IOException("Temporary workspace ownership validation failed");
        }
        return normalizedRoot;
    }

    private void deleteOnce(Path workspaceRoot) throws IOException {
        if (!Files.exists(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void waitBeforeRetry() {
        boolean interrupted = Thread.interrupted();
        try {
            Thread.sleep(CLEANUP_RETRY_DELAY.toMillis());
        } catch (InterruptedException exception) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
