package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class AnalyzerProcessRunner {

    private static final String REPORT_FILE_NAME = "report.json";
    private static final String STDOUT_FILE_NAME = "stdout.log";
    private static final String STDERR_FILE_NAME = "stderr.log";
    private static final int MAX_DIAGNOSTIC_BYTES = 8 * 1024;
    private static final Duration TERMINATION_GRACE = Duration.ofMillis(250);
    private static final Duration FORCED_TERMINATION_GRACE = Duration.ofSeconds(1);
    private static final int CLEANUP_ATTEMPTS = 10;
    private static final Duration CLEANUP_RETRY_DELAY = Duration.ofMillis(50);

    private final AnalyzerProcessProperties properties;
    private final AnalyzerCommandFactory commandFactory;
    private final AnalyzerReportReader reportReader;

    public AnalyzerProcessRunner(
            AnalyzerProcessProperties properties,
            AnalyzerCommandFactory commandFactory,
            AnalyzerReportReader reportReader
    ) {
        this.properties = properties;
        this.commandFactory = commandFactory;
        this.reportReader = reportReader;
    }

    public AnalyzerReport analyze(Path repositoryPath) {
        Path normalizedRepositoryPath = validateRepositoryPath(repositoryPath);
        Path workDirectory = createWorkDirectory();
        AnalyzerExecutionException executionFailure = null;

        try {
            return runAnalyzer(normalizedRepositoryPath, workDirectory);
        } catch (AnalyzerExecutionException exception) {
            executionFailure = exception;
            throw exception;
        } finally {
            try {
                deleteWorkDirectory(workDirectory);
            } catch (IOException cleanupException) {
                if (executionFailure != null) {
                    executionFailure.addSuppressed(cleanupException);
                } else {
                    throw new AnalyzerExecutionException(
                            AnalyzerExecutionFailure.CLEANUP_FAILED,
                            "Failed to clean analyzer temporary work directory",
                            cleanupException
                    );
                }
            }
        }
    }

    private Path validateRepositoryPath(Path repositoryPath) {
        if (repositoryPath == null) {
            throw new AnalyzerExecutionException(
                    AnalyzerExecutionFailure.INVALID_REPOSITORY_PATH,
                    "Analyzer repository path must not be null"
            );
        }

        Path normalizedPath = repositoryPath.toAbsolutePath().normalize();
        if (!Files.exists(normalizedPath)) {
            throw new AnalyzerExecutionException(
                    AnalyzerExecutionFailure.INVALID_REPOSITORY_PATH,
                    "Analyzer repository path does not exist: " + normalizedPath
            );
        }
        if (!Files.isDirectory(normalizedPath)) {
            throw new AnalyzerExecutionException(
                    AnalyzerExecutionFailure.INVALID_REPOSITORY_PATH,
                    "Analyzer repository path is not a directory: " + normalizedPath
            );
        }
        return normalizedPath;
    }

    private Path createWorkDirectory() {
        try {
            return Files.createTempDirectory("openpulse-analyzer-").toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new AnalyzerExecutionException(
                    AnalyzerExecutionFailure.TEMPORARY_DIRECTORY_CREATION_FAILED,
                    "Failed to create analyzer temporary work directory",
                    exception
            );
        }
    }

    private AnalyzerReport runAnalyzer(Path repositoryPath, Path workDirectory) {
        Path reportPath = workDirectory.resolve(REPORT_FILE_NAME);
        Path stdoutPath = workDirectory.resolve(STDOUT_FILE_NAME);
        Path stderrPath = workDirectory.resolve(STDERR_FILE_NAME);
        List<String> command = commandFactory.create(repositoryPath, reportPath);
        Process process = startProcess(command, stdoutPath, stderrPath);

        int exitCode;
        try {
            waitForProcess(process, stdoutPath, stderrPath);
            exitCode = process.exitValue();
        } finally {
            closeProcessStreams(process);
        }
        if (exitCode != 0) {
            throw exitCodeFailure(exitCode, stdoutPath, stderrPath);
        }
        if (!Files.isRegularFile(reportPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new AnalyzerExecutionException(
                    AnalyzerExecutionFailure.REPORT_MISSING,
                    "Analyzer exited successfully but did not create a regular report file"
            );
        }

        try {
            return reportReader.read(reportPath);
        } catch (AnalyzerReportReadException exception) {
            throw new AnalyzerExecutionException(
                    AnalyzerExecutionFailure.REPORT_INVALID,
                    "Analyzer produced an invalid report",
                    exception
            );
        }
    }

    private Process startProcess(List<String> command, Path stdoutPath, Path stderrPath) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectOutput(stdoutPath.toFile());
        processBuilder.redirectError(stderrPath.toFile());
        try {
            return processBuilder.start();
        } catch (IOException exception) {
            throw new AnalyzerExecutionException(
                    AnalyzerExecutionFailure.START_FAILED,
                    "Failed to start analyzer process",
                    exception
            );
        }
    }

    private void waitForProcess(Process process, Path stdoutPath, Path stderrPath) {
        try {
            long timeoutMillis = Math.max(1, properties.timeout().toMillis());
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                terminateProcess(process);
                throw new AnalyzerExecutionException(
                        AnalyzerExecutionFailure.TIMEOUT,
                        "Analyzer process timed out after " + properties.timeout() + diagnosticSummary(stdoutPath, stderrPath)
                );
            }
        } catch (InterruptedException exception) {
            terminateProcess(process);
            Thread.currentThread().interrupt();
            throw new AnalyzerExecutionException(
                    AnalyzerExecutionFailure.INTERRUPTED,
                    "Interrupted while waiting for analyzer process",
                    exception
            );
        }
    }

    private void terminateProcess(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();

        boolean interrupted = false;
        try {
            boolean processExited = process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (!processExited) {
                process.destroyForcibly();
                process.waitFor(FORCED_TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(FORCED_TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException repeatedInterruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private AnalyzerExecutionException exitCodeFailure(int exitCode, Path stdoutPath, Path stderrPath) {
        AnalyzerExecutionFailure failure = switch (exitCode) {
            case 1 -> AnalyzerExecutionFailure.INVALID_ARGUMENTS;
            case 2 -> AnalyzerExecutionFailure.REPOSITORY_NOT_FOUND;
            case 3 -> AnalyzerExecutionFailure.SCAN_FAILED;
            case 4 -> AnalyzerExecutionFailure.REPORT_OUTPUT_FAILED;
            default -> AnalyzerExecutionFailure.UNKNOWN_EXIT_CODE;
        };
        String description = switch (exitCode) {
            case 1 -> "invalid arguments";
            case 2 -> "repository path not found";
            case 3 -> "scan failed";
            case 4 -> "report output failed";
            default -> "unknown failure";
        };
        return new AnalyzerExecutionException(
                failure,
                exitCode,
                "Analyzer process exited with code " + exitCode + " (" + description + ")"
                        + diagnosticSummary(stdoutPath, stderrPath)
        );
    }

    private void closeProcessStreams(Process process) {
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
    }

    private void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Process cleanup must continue even when an already-closed stream reports an error.
        }
    }

    private void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Process cleanup must continue even when an already-closed stream reports an error.
        }
    }

    private String diagnosticSummary(Path stdoutPath, Path stderrPath) {
        String stdout = readTail(stdoutPath);
        String stderr = readTail(stderrPath);
        if (stdout.isBlank() && stderr.isBlank()) {
            return "";
        }
        return "; diagnostic tail: stdout=[" + stdout + "], stderr=[" + stderr + "]";
    }

    private String readTail(Path path) {
        if (!Files.isRegularFile(path)) {
            return "";
        }

        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long start = Math.max(0, channel.size() - MAX_DIAGNOSTIC_BYTES);
            int bytesToRead = (int) (channel.size() - start);
            ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Keep reading until the bounded buffer is full or the file ends.
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            return "diagnostic unavailable";
        }
    }

    private void deleteWorkDirectory(Path workDirectory) throws IOException {
        IOException deletionFailure = null;
        for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++) {
            try {
                deleteWorkDirectoryOnce(workDirectory);
                return;
            } catch (IOException exception) {
                deletionFailure = exception;
                if (attempt < CLEANUP_ATTEMPTS) {
                    waitBeforeCleanupRetry();
                }
            }
        }
        throw deletionFailure;
    }

    private void deleteWorkDirectoryOnce(Path workDirectory) throws IOException {
        if (!Files.exists(workDirectory)) {
            return;
        }
        IOException deletionFailure = null;
        try (Stream<Path> paths = Files.walk(workDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    if (deletionFailure == null) {
                        deletionFailure = exception;
                    } else {
                        deletionFailure.addSuppressed(exception);
                    }
                }
            }
        }
        if (deletionFailure != null) {
            throw deletionFailure;
        }
    }

    private void waitBeforeCleanupRetry() {
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
