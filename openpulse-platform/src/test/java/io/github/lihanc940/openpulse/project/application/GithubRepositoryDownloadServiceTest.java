package io.github.lihanc940.openpulse.project.application;

import io.github.lihanc940.openpulse.integration.github.GithubArchiveException;
import io.github.lihanc940.openpulse.integration.github.GithubArchiveExtractor;
import io.github.lihanc940.openpulse.integration.github.GithubArchiveFailure;
import io.github.lihanc940.openpulse.integration.github.GithubRepositoryArchiveClient;
import io.github.lihanc940.openpulse.integration.github.GithubWorkspaceCleaner;
import io.github.lihanc940.openpulse.project.domain.DownloadedRepositoryWorkspace;
import io.github.lihanc940.openpulse.project.domain.GithubRepositoryMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GithubRepositoryDownloadServiceTest {

    @TempDir
    Path testDirectory;

    @Test
    void keepsSuccessfulWorkspaceUntilCloseAndCloseIsIdempotent() throws Exception {
        TestService fixture = successfulService("openpulse-github-success");

        DownloadedRepositoryWorkspace workspace = fixture.service.download(metadata());

        assertThat(workspace.workspaceRoot()).exists();
        assertThat(workspace.repositoryRoot()).isDirectory();
        assertThat(workspace.repositoryRoot().resolve("README.md")).hasContent("readme");
        assertThat(workspace.archiveBytes()).isEqualTo(7);
        assertThat(workspace.extractedBytes()).isEqualTo(6);
        assertThat(workspace.entryCount()).isEqualTo(2);

        workspace.close();
        workspace.close();

        assertThat(workspace.workspaceRoot()).doesNotExist();
        verify(fixture.cleaner).clean(workspace.workspaceRoot());
    }

    @Test
    void tryWithResourcesCleansWorkspaceWhenCallerThrows() throws Exception {
        TestService fixture = successfulService("openpulse-github-caller-failure");
        Path[] workspacePath = new Path[1];

        assertThatThrownBy(() -> {
            try (DownloadedRepositoryWorkspace workspace = fixture.service.download(metadata())) {
                workspacePath[0] = workspace.workspaceRoot();
                throw new IllegalStateException("simulated caller failure");
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated caller failure");

        assertThat(workspacePath[0]).doesNotExist();
    }

    @Test
    void cleansWorkspaceWhenDownloadOrExtractionFails() throws Exception {
        GithubArchiveException downloadFailure = new GithubArchiveException(
                GithubArchiveFailure.ARCHIVE_TOO_LARGE,
                "archive too large"
        );
        TestService downloadFixture = failingService(
                "openpulse-github-download-failure",
                downloadFailure,
                null
        );

        GithubArchiveException actualDownloadFailure = catchThrowableOfType(
                GithubArchiveException.class,
                () -> downloadFixture.service.download(metadata())
        );

        assertThat(actualDownloadFailure).isSameAs(downloadFailure);
        assertThat(downloadFixture.workspacePath).doesNotExist();
        verify(downloadFixture.cleaner).clean(downloadFixture.workspacePath);

        GithubArchiveException extractionFailure = new GithubArchiveException(
                GithubArchiveFailure.UNSAFE_ARCHIVE_ENTRY,
                "unsafe archive"
        );
        TestService extractionFixture = failingService(
                "openpulse-github-extraction-failure",
                null,
                extractionFailure
        );

        GithubArchiveException actualExtractionFailure = catchThrowableOfType(
                GithubArchiveException.class,
                () -> extractionFixture.service.download(metadata())
        );

        assertThat(actualExtractionFailure).isSameAs(extractionFailure);
        assertThat(extractionFixture.workspacePath).doesNotExist();
        verify(extractionFixture.cleaner).clean(extractionFixture.workspacePath);
    }

    @Test
    void preservesPrimaryFailureAndAddsControlledCleanupDiagnostic() throws Exception {
        Path workspacePath = Files.createDirectory(testDirectory.resolve("openpulse-github-suppressed"));
        GithubRepositoryArchiveClient client = mock(GithubRepositoryArchiveClient.class);
        GithubArchiveExtractor extractor = mock(GithubArchiveExtractor.class);
        GithubWorkspaceCleaner cleaner = mock(GithubWorkspaceCleaner.class);
        GithubArchiveException primary = new GithubArchiveException(
                GithubArchiveFailure.GITHUB_UNAVAILABLE,
                "primary failure"
        );
        when(client.download(any(), any())).thenThrow(primary);
        doThrow(new IOException("simulated cleanup failure")).when(cleaner).clean(workspacePath);
        GithubRepositoryDownloadService service = service(client, extractor, cleaner, workspacePath);

        GithubArchiveException actual = catchThrowableOfType(
                GithubArchiveException.class,
                () -> service.download(metadata())
        );

        assertThat(actual).isSameAs(primary);
        assertThat(actual.getSuppressed()).hasSize(1);
        assertThat(actual.getSuppressed()[0]).isInstanceOf(GithubArchiveException.class);
        GithubArchiveException cleanupDiagnostic = (GithubArchiveException) actual.getSuppressed()[0];
        assertThat(cleanupDiagnostic.failure()).isEqualTo(GithubArchiveFailure.CLEANUP_FAILED);
        assertThat(cleanupDiagnostic.getMessage()).doesNotContain(workspacePath.toString());
        deleteTree(workspacePath);
    }

    @Test
    void reportsCleanupFailureFromSuccessfulWorkspaceAndAllowsRetry() throws Exception {
        Path workspacePath = Files.createDirectory(testDirectory.resolve("openpulse-github-close-failure"));
        GithubRepositoryArchiveClient client = mock(GithubRepositoryArchiveClient.class);
        GithubArchiveExtractor extractor = mock(GithubArchiveExtractor.class);
        GithubWorkspaceCleaner cleaner = mock(GithubWorkspaceCleaner.class);
        configureSuccessfulDownload(client, extractor, workspacePath);
        doThrow(new IOException("first cleanup failure"))
                .doAnswer(invocation -> {
                    deleteTree(workspacePath);
                    return null;
                })
                .when(cleaner).clean(workspacePath);
        GithubRepositoryDownloadService service = service(client, extractor, cleaner, workspacePath);
        DownloadedRepositoryWorkspace workspace = service.download(metadata());

        GithubArchiveException cleanupFailure = catchThrowableOfType(
                GithubArchiveException.class,
                workspace::close
        );

        assertThat(cleanupFailure.failure()).isEqualTo(GithubArchiveFailure.CLEANUP_FAILED);
        assertThat(cleanupFailure.getMessage()).doesNotContain(workspacePath.toString());
        assertThat(workspacePath).exists();

        workspace.close();
        assertThat(workspacePath).doesNotExist();
    }

    @Test
    void mapsTemporaryDirectoryCreationFailureAndRejectsNullMetadataBeforeCreation() throws Exception {
        GithubRepositoryArchiveClient client = mock(GithubRepositoryArchiveClient.class);
        GithubArchiveExtractor extractor = mock(GithubArchiveExtractor.class);
        GithubWorkspaceCleaner cleaner = mock(GithubWorkspaceCleaner.class);
        GithubRepositoryDownloadService creationFailureService = new GithubRepositoryDownloadService(
                client,
                extractor,
                cleaner,
                prefix -> {
                    throw new IOException("simulated creation failure");
                }
        );

        GithubArchiveException creationFailure = catchThrowableOfType(
                GithubArchiveException.class,
                () -> creationFailureService.download(metadata())
        );
        assertThat(creationFailure.failure())
                .isEqualTo(GithubArchiveFailure.TEMPORARY_DIRECTORY_CREATION_FAILED);
        verifyNoInteractions(client, extractor, cleaner);

        GithubRepositoryDownloadService invalidMetadataService = new GithubRepositoryDownloadService(
                client,
                extractor,
                cleaner,
                prefix -> {
                    throw new AssertionError("temporary directory must not be created");
                }
        );
        GithubArchiveException invalidMetadata = catchThrowableOfType(
                GithubArchiveException.class,
                () -> invalidMetadataService.download(null)
        );
        assertThat(invalidMetadata.failure()).isEqualTo(GithubArchiveFailure.INVALID_REPOSITORY_METADATA);
        verify(client, never()).download(any(), any());
    }

    private TestService successfulService(String workspaceName) throws Exception {
        Path workspacePath = Files.createDirectory(testDirectory.resolve(workspaceName));
        GithubRepositoryArchiveClient client = mock(GithubRepositoryArchiveClient.class);
        GithubArchiveExtractor extractor = mock(GithubArchiveExtractor.class);
        GithubWorkspaceCleaner cleaner = mock(GithubWorkspaceCleaner.class);
        configureSuccessfulDownload(client, extractor, workspacePath);
        doAnswer(invocation -> {
            deleteTree(invocation.getArgument(0));
            return null;
        }).when(cleaner).clean(workspacePath);
        return new TestService(service(client, extractor, cleaner, workspacePath), cleaner, workspacePath);
    }

    private TestService failingService(
            String workspaceName,
            GithubArchiveException downloadFailure,
            GithubArchiveException extractionFailure
    ) throws Exception {
        Path workspacePath = Files.createDirectory(testDirectory.resolve(workspaceName));
        GithubRepositoryArchiveClient client = mock(GithubRepositoryArchiveClient.class);
        GithubArchiveExtractor extractor = mock(GithubArchiveExtractor.class);
        GithubWorkspaceCleaner cleaner = mock(GithubWorkspaceCleaner.class);
        if (downloadFailure != null) {
            when(client.download(any(), any())).thenThrow(downloadFailure);
        } else {
            when(client.download(any(), any())).thenAnswer(invocation -> {
                Files.writeString(invocation.getArgument(1), "archive");
                return 7L;
            });
            when(extractor.extract(any(), any())).thenThrow(extractionFailure);
        }
        doAnswer(invocation -> {
            deleteTree(invocation.getArgument(0));
            return null;
        }).when(cleaner).clean(workspacePath);
        return new TestService(service(client, extractor, cleaner, workspacePath), cleaner, workspacePath);
    }

    private void configureSuccessfulDownload(
            GithubRepositoryArchiveClient client,
            GithubArchiveExtractor extractor,
            Path workspacePath
    ) {
        when(client.download(any(), any())).thenAnswer(invocation -> {
            Files.writeString(invocation.getArgument(1), "archive");
            return 7L;
        });
        when(extractor.extract(any(), any())).thenAnswer(invocation -> {
            Path extractionRoot = invocation.getArgument(1);
            Path repositoryRoot = Files.createDirectories(extractionRoot.resolve("repo-main"));
            Files.writeString(repositoryRoot.resolve("README.md"), "readme");
            return new GithubArchiveExtractor.ExtractionResult(repositoryRoot, 6, 2);
        });
    }

    private GithubRepositoryDownloadService service(
            GithubRepositoryArchiveClient client,
            GithubArchiveExtractor extractor,
            GithubWorkspaceCleaner cleaner,
            Path workspacePath
    ) {
        return new GithubRepositoryDownloadService(
                client,
                extractor,
                cleaner,
                prefix -> workspacePath
        );
    }

    private GithubRepositoryMetadata metadata() {
        return new GithubRepositoryMetadata(
                "OpenAI",
                "OpenAI",
                "OpenAI/OpenAI",
                "https://github.com/OpenAI/OpenAI",
                null,
                "main",
                null,
                0,
                0,
                false
        );
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record TestService(
            GithubRepositoryDownloadService service,
            GithubWorkspaceCleaner cleaner,
            Path workspacePath
    ) {
    }
}
