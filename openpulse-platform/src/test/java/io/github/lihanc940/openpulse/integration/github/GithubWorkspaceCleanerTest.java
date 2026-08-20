package io.github.lihanc940.openpulse.integration.github;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubWorkspaceCleanerTest {

    private final GithubWorkspaceCleaner cleaner = new GithubWorkspaceCleaner();

    @Test
    void deletesOnlyOwnedTemporaryWorkspaceAndAllowsMissingWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("openpulse-github-").toAbsolutePath().normalize();
        try {
            Files.createDirectories(workspace.resolve("extracted/repo-main"));
            Files.writeString(workspace.resolve("extracted/repo-main/README.md"), "readme");

            cleaner.clean(workspace);
            cleaner.clean(workspace);

            assertThat(workspace).doesNotExist();
        } finally {
            if (Files.exists(workspace)) {
                Files.deleteIfExists(workspace.resolve("extracted/repo-main/README.md"));
                Files.deleteIfExists(workspace.resolve("extracted/repo-main"));
                Files.deleteIfExists(workspace.resolve("extracted"));
                Files.deleteIfExists(workspace);
            }
        }
    }

    @Test
    void rejectsPathsThatAreNotOwnedWorkspaces() throws Exception {
        Path unrelated = Files.createTempDirectory("not-openpulse-").toAbsolutePath().normalize();
        try {
            assertThatThrownBy(() -> cleaner.clean(unrelated))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("Temporary workspace ownership validation failed");
            assertThat(unrelated).exists();
        } finally {
            Files.deleteIfExists(unrelated);
        }
    }
}
