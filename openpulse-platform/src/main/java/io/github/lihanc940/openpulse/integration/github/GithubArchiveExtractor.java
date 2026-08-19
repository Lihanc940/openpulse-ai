package io.github.lihanc940.openpulse.integration.github;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

@Component
public class GithubArchiveExtractor {

    private static final int BUFFER_SIZE = 8192;

    private final GithubArchiveProperties properties;

    public GithubArchiveExtractor(GithubArchiveProperties properties) {
        this.properties = properties;
    }

    public ExtractionResult extract(Path archivePath, Path destinationRoot) {
        Path normalizedDestination = destinationRoot.toAbsolutePath().normalize();
        if (!Files.isRegularFile(archivePath, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidArchive();
        }

        try {
            Files.createDirectory(normalizedDestination);
            ExtractionCounters counters = extractEntries(archivePath, normalizedDestination);
            Path repositoryRoot = validateTopLevelStructure(normalizedDestination);
            return new ExtractionResult(
                    repositoryRoot,
                    counters.extractedBytes,
                    counters.entryCount
            );
        } catch (GithubArchiveException exception) {
            throw exception;
        } catch (ZipException exception) {
            throw new GithubArchiveException(
                    GithubArchiveFailure.ARCHIVE_INVALID,
                    "GitHub archive is not a valid ZIP file."
            );
        } catch (IOException exception) {
            throw new GithubArchiveException(
                    GithubArchiveFailure.ARCHIVE_INVALID,
                    "GitHub archive could not be safely extracted."
            );
        }
    }

    private ExtractionCounters extractEntries(Path archivePath, Path destinationRoot) throws IOException {
        long extractedBytes = 0;
        int entryCount = 0;
        Map<Path, EntryKind> declaredTargets = new HashMap<>();

        try (ZipFile archive = new ZipFile(archivePath.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > properties.maxEntryCount()) {
                    throw new GithubArchiveException(
                            GithubArchiveFailure.ARCHIVE_ENTRY_LIMIT_EXCEEDED,
                            "GitHub archive exceeds the configured entry count limit."
                    );
                }

                Path target = safeTarget(destinationRoot, entry.getName());
                EntryKind kind = entry.isDirectory() ? EntryKind.DIRECTORY : EntryKind.FILE;
                rejectDuplicateOrConflictingTarget(destinationRoot, target, kind, declaredTargets);
                declaredTargets.put(target, kind);

                if (kind == EntryKind.DIRECTORY) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream input = archive.getInputStream(entry);
                         OutputStream output = Files.newOutputStream(
                                 target,
                                 StandardOpenOption.CREATE_NEW,
                                 StandardOpenOption.WRITE
                         )) {
                        extractedBytes = copyEntry(input, output, extractedBytes);
                    }
                }
            }
        }
        return new ExtractionCounters(extractedBytes, entryCount);
    }

    private Path safeTarget(Path destinationRoot, String entryName) {
        if (entryName == null || entryName.isEmpty()
                || entryName.indexOf('\\') >= 0
                || entryName.codePoints().anyMatch(Character::isISOControl)
                || entryName.startsWith("/")
                || hasWindowsDrivePrefix(entryName)) {
            throw unsafeEntry();
        }

        Path target;
        try {
            target = destinationRoot.resolve(entryName).normalize();
        } catch (RuntimeException exception) {
            throw unsafeEntry();
        }
        if (target.equals(destinationRoot) || !target.startsWith(destinationRoot)) {
            throw unsafeEntry();
        }
        return target;
    }

    private void rejectDuplicateOrConflictingTarget(
            Path destinationRoot,
            Path target,
            EntryKind kind,
            Map<Path, EntryKind> declaredTargets
    ) {
        if (declaredTargets.containsKey(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw unsafeEntry();
        }

        for (Path ancestor = target.getParent();
             ancestor != null && !ancestor.equals(destinationRoot);
             ancestor = ancestor.getParent()) {
            if (declaredTargets.get(ancestor) == EntryKind.FILE) {
                throw unsafeEntry();
            }
        }
        if (kind == EntryKind.FILE
                && declaredTargets.keySet().stream().anyMatch(existing -> existing.startsWith(target))) {
            throw unsafeEntry();
        }
    }

    private long copyEntry(InputStream input, OutputStream output, long extractedBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = extractedBytes;
        long maximumBytes = properties.maxExtractedBytes();
        while (true) {
            long remaining = maximumBytes - total;
            int allowedRead = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
            int read = input.read(buffer, 0, allowedRead);
            if (read == -1) {
                return total;
            }
            if (read == 0) {
                continue;
            }
            if (total + read > maximumBytes) {
                throw new GithubArchiveException(
                        GithubArchiveFailure.EXTRACTED_CONTENT_TOO_LARGE,
                        "GitHub archive exceeds the configured extracted size limit."
                );
            }
            output.write(buffer, 0, read);
            total += read;
        }
    }

    private Path validateTopLevelStructure(Path destinationRoot) throws IOException {
        List<Path> topLevelEntries;
        try (var children = Files.list(destinationRoot)) {
            topLevelEntries = children.sorted(Comparator.comparing(Path::toString)).toList();
        }
        if (topLevelEntries.size() != 1
                || !Files.isDirectory(topLevelEntries.getFirst(), LinkOption.NOFOLLOW_LINKS)) {
            throw invalidArchive();
        }

        Path repositoryRoot = topLevelEntries.getFirst();
        try (var children = Files.list(repositoryRoot)) {
            if (children.findAny().isEmpty()) {
                throw invalidArchive();
            }
        }
        return repositoryRoot;
    }

    private boolean hasWindowsDrivePrefix(String entryName) {
        return entryName.length() >= 2
                && Character.isLetter(entryName.charAt(0))
                && entryName.charAt(1) == ':';
    }

    private GithubArchiveException unsafeEntry() {
        return new GithubArchiveException(
                GithubArchiveFailure.UNSAFE_ARCHIVE_ENTRY,
                "GitHub archive contains an unsafe entry."
        );
    }

    private GithubArchiveException invalidArchive() {
        return new GithubArchiveException(
                GithubArchiveFailure.ARCHIVE_INVALID,
                "GitHub archive has an invalid top-level structure."
        );
    }

    private enum EntryKind {
        FILE,
        DIRECTORY
    }

    private record ExtractionCounters(long extractedBytes, int entryCount) {
    }

    public record ExtractionResult(Path repositoryRoot, long extractedBytes, int entryCount) {
    }
}
