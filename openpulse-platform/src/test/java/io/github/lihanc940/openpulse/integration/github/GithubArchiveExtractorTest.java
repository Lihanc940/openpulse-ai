package io.github.lihanc940.openpulse.integration.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class GithubArchiveExtractorTest {

    @TempDir
    Path testDirectory;

    @Test
    void extractsSingleTopLevelRepositoryAndPreservesUnicodeAndSpaces() throws Exception {
        Path archive = zip("valid.zip", List.of(
                entry("repo-main/", null),
                entry("repo-main/docs and notes/", null),
                entry("repo-main/docs and notes/说明.txt", "安全内容".getBytes(StandardCharsets.UTF_8))
        ));
        GithubArchiveExtractor extractor = extractor(1024, 10);

        GithubArchiveExtractor.ExtractionResult result = extractor.extract(
                archive,
                testDirectory.resolve("extracted")
        );

        assertThat(result.repositoryRoot().getFileName().toString()).isEqualTo("repo-main");
        assertThat(result.extractedBytes()).isEqualTo("安全内容".getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.entryCount()).isEqualTo(3);
        assertThat(result.repositoryRoot().resolve("docs and notes/说明.txt"))
                .hasContent("安全内容");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../evil.txt",
            "repo-main/nested/../../../evil.txt",
            "/absolute.txt",
            "C:/windows.txt",
            "repo-main\\backslash.txt",
            "repo-main/control\u0001.txt"
    })
    void rejectsPathTraversalAbsoluteDriveBackslashAndControlEntries(String unsafeName) throws Exception {
        Path archive = zip("unsafe.zip", List.of(entry(unsafeName, bytes("evil"))));

        assertFailure(
                GithubArchiveFailure.UNSAFE_ARCHIVE_ENTRY,
                () -> extractor(1024, 10).extract(archive, testDirectory.resolve("unsafe-output"))
        );
    }

    @Test
    void rejectsDuplicateNormalizedTargetsAndFileDirectoryConflicts() throws Exception {
        Path duplicate = zip("duplicate.zip", List.of(
                entry("repo-main/", null),
                entry("repo-main/file.txt", bytes("first")),
                entry("repo-main/./file.txt", bytes("second"))
        ));
        Path conflict = zip("conflict.zip", List.of(
                entry("repo-main/", null),
                entry("repo-main/node", bytes("file")),
                entry("repo-main/node/child.txt", bytes("child"))
        ));

        assertFailure(
                GithubArchiveFailure.UNSAFE_ARCHIVE_ENTRY,
                () -> extractor(1024, 10).extract(duplicate, testDirectory.resolve("duplicate-output"))
        );
        assertFailure(
                GithubArchiveFailure.UNSAFE_ARCHIVE_ENTRY,
                () -> extractor(1024, 10).extract(conflict, testDirectory.resolve("conflict-output"))
        );
    }

    @Test
    void rejectsCorruptEmptyMultipleTopLevelAndTopLevelFileArchives() throws Exception {
        Path corrupt = testDirectory.resolve("corrupt.zip");
        Files.writeString(corrupt, "not a zip", StandardCharsets.UTF_8);
        Path empty = zip("empty.zip", List.of());
        Path multiple = zip("multiple.zip", List.of(
                entry("first/file.txt", bytes("one")),
                entry("second/file.txt", bytes("two"))
        ));
        Path topLevelFile = zip("top-file.zip", List.of(entry("README.md", bytes("readme"))));

        assertFailure(
                GithubArchiveFailure.ARCHIVE_INVALID,
                () -> extractor(1024, 10).extract(corrupt, testDirectory.resolve("corrupt-output"))
        );
        assertFailure(
                GithubArchiveFailure.ARCHIVE_INVALID,
                () -> extractor(1024, 10).extract(empty, testDirectory.resolve("empty-output"))
        );
        assertFailure(
                GithubArchiveFailure.ARCHIVE_INVALID,
                () -> extractor(1024, 10).extract(multiple, testDirectory.resolve("multiple-output"))
        );
        assertFailure(
                GithubArchiveFailure.ARCHIVE_INVALID,
                () -> extractor(1024, 10).extract(topLevelFile, testDirectory.resolve("top-file-output"))
        );
    }

    @Test
    void enforcesEntryCountAtTheExactBoundary() throws Exception {
        Path twoEntries = zip("two-entries.zip", List.of(
                entry("repo-main/", null),
                entry("repo-main/file.txt", bytes("x"))
        ));
        Path threeEntries = zip("three-entries.zip", List.of(
                entry("repo-main/", null),
                entry("repo-main/first.txt", bytes("x")),
                entry("repo-main/second.txt", bytes("y"))
        ));

        GithubArchiveExtractor.ExtractionResult accepted = extractor(10, 2).extract(
                twoEntries,
                testDirectory.resolve("entry-boundary-output")
        );
        assertThat(accepted.entryCount()).isEqualTo(2);
        assertFailure(
                GithubArchiveFailure.ARCHIVE_ENTRY_LIMIT_EXCEEDED,
                () -> extractor(10, 2).extract(
                        threeEntries,
                        testDirectory.resolve("entry-overflow-output")
                )
        );
    }

    @Test
    void countsActualExtractedBytesAndRejectsZipBombBeyondBoundary() throws Exception {
        Path exact = zip("exact-size.zip", List.of(
                entry("repo-main/", null),
                entry("repo-main/file.txt", bytes("1234"))
        ));
        byte[] repeated = new byte[128];
        Path bomb = zip("bomb.zip", List.of(
                entry("repo-main/", null),
                entry("repo-main/repeated.bin", repeated)
        ));

        GithubArchiveExtractor.ExtractionResult accepted = extractor(4, 10).extract(
                exact,
                testDirectory.resolve("exact-size-output")
        );
        assertThat(accepted.extractedBytes()).isEqualTo(4);
        assertFailure(
                GithubArchiveFailure.EXTRACTED_CONTENT_TOO_LARGE,
                () -> extractor(127, 10).extract(bomb, testDirectory.resolve("bomb-output"))
        );
    }

    private GithubArchiveExtractor extractor(long extractedBytes, int entries) {
        return new GithubArchiveExtractor(new GithubArchiveProperties(
                Duration.ofSeconds(1),
                DataSize.ofMegabytes(1),
                DataSize.ofBytes(extractedBytes),
                entries
        ));
    }

    private Path zip(String fileName, List<TestEntry> entries) throws IOException {
        Path path = testDirectory.resolve(fileName);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (TestEntry testEntry : entries) {
                output.putNextEntry(new ZipEntry(testEntry.name));
                if (testEntry.content != null) {
                    output.write(testEntry.content);
                }
                output.closeEntry();
            }
        }
        return path;
    }

    private TestEntry entry(String name, byte[] content) {
        return new TestEntry(name, content);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void assertFailure(GithubArchiveFailure failure, ThrowingOperation operation) {
        GithubArchiveException exception = catchThrowableOfType(
                GithubArchiveException.class,
                operation::run
        );
        assertThat(exception.failure()).isEqualTo(failure);
        assertThat(exception.getMessage())
                .doesNotContain(testDirectory.toAbsolutePath().toString());
    }

    private record TestEntry(String name, byte[] content) {
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
