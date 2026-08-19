package io.github.lihanc940.openpulse.project.application;

import io.github.lihanc940.openpulse.integration.github.GithubArchiveException;
import io.github.lihanc940.openpulse.integration.github.GithubArchiveExtractor;
import io.github.lihanc940.openpulse.integration.github.GithubArchiveFailure;
import io.github.lihanc940.openpulse.integration.github.GithubRepositoryArchiveClient;
import io.github.lihanc940.openpulse.integration.github.GithubWorkspaceCleaner;
import io.github.lihanc940.openpulse.project.domain.DownloadedRepositoryWorkspace;
import io.github.lihanc940.openpulse.project.domain.GithubRepositoryMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class GithubRepositoryDownloadService {

    private static final String WORKSPACE_PREFIX = "openpulse-github-";
    private static final String ARCHIVE_FILE_NAME = "repository.zip";
    private static final String EXTRACTION_DIRECTORY_NAME = "extracted";

    private final GithubRepositoryArchiveClient archiveClient;
    private final GithubArchiveExtractor archiveExtractor;
    private final GithubWorkspaceCleaner workspaceCleaner;
    private final TemporaryDirectoryFactory temporaryDirectoryFactory;

    @Autowired
    public GithubRepositoryDownloadService(
            GithubRepositoryArchiveClient archiveClient,
            GithubArchiveExtractor archiveExtractor,
            GithubWorkspaceCleaner workspaceCleaner
    ) {
        this(archiveClient, archiveExtractor, workspaceCleaner, Files::createTempDirectory);
    }

    GithubRepositoryDownloadService(
            GithubRepositoryArchiveClient archiveClient,
            GithubArchiveExtractor archiveExtractor,
            GithubWorkspaceCleaner workspaceCleaner,
            TemporaryDirectoryFactory temporaryDirectoryFactory
    ) {
        this.archiveClient = archiveClient;
        this.archiveExtractor = archiveExtractor;
        this.workspaceCleaner = workspaceCleaner;
        this.temporaryDirectoryFactory = temporaryDirectoryFactory;
    }

    public DownloadedRepositoryWorkspace download(GithubRepositoryMetadata metadata) {
        validateMetadata(metadata);
        Path workspaceRoot = createWorkspace();
        try {
            Path archivePath = workspaceRoot.resolve(ARCHIVE_FILE_NAME);
            Path extractionRoot = workspaceRoot.resolve(EXTRACTION_DIRECTORY_NAME);
            long archiveBytes = archiveClient.download(metadata, archivePath);
            GithubArchiveExtractor.ExtractionResult extraction = archiveExtractor.extract(
                    archivePath,
                    extractionRoot
            );
            return new DownloadedRepositoryWorkspace(
                    workspaceRoot,
                    extraction.repositoryRoot(),
                    archiveBytes,
                    extraction.extractedBytes(),
                    extraction.entryCount(),
                    () -> workspaceCleaner.clean(workspaceRoot)
            );
        } catch (RuntimeException primaryFailure) {
            cleanAfterFailure(workspaceRoot, primaryFailure);
            throw primaryFailure;
        }
    }

    private void validateMetadata(GithubRepositoryMetadata metadata) {
        if (metadata == null
                || !metadata.fullName().equals(metadata.owner() + "/" + metadata.name())
                || !metadata.canonicalUrl().equals(
                        "https://github.com/" + metadata.owner() + "/" + metadata.name()
                )) {
            throw new GithubArchiveException(
                    GithubArchiveFailure.INVALID_REPOSITORY_METADATA,
                    "GitHub repository metadata is invalid."
            );
        }
    }

    private Path createWorkspace() {
        try {
            return temporaryDirectoryFactory.create(WORKSPACE_PREFIX).toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new GithubArchiveException(
                    GithubArchiveFailure.TEMPORARY_DIRECTORY_CREATION_FAILED,
                    "Failed to create the GitHub repository temporary workspace."
            );
        }
    }

    private void cleanAfterFailure(Path workspaceRoot, RuntimeException primaryFailure) {
        try {
            workspaceCleaner.clean(workspaceRoot);
        } catch (IOException cleanupFailure) {
            primaryFailure.addSuppressed(new GithubArchiveException(
                    GithubArchiveFailure.CLEANUP_FAILED,
                    "Failed to clean the GitHub repository temporary workspace."
            ));
        }
    }

    @FunctionalInterface
    interface TemporaryDirectoryFactory {
        Path create(String prefix) throws IOException;
    }
}
