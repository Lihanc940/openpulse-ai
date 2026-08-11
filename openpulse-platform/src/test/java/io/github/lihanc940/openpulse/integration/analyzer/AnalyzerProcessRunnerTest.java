package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
class AnalyzerProcessRunnerTest {

    private static final Pattern DESCENDANT_PID_PATTERN = Pattern.compile("descendant-pid=(\\d+)");

    @TempDir
    Path testDirectory;

    @Autowired
    AnalyzerReportReader reportReader;

    @Test
    void executesAnalyzerForRepositoryPathWithSpacesAndCleansWorkDirectory() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("demo repository"));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerCommandFactory commandFactory = fakeAnalyzerCommand("success", workDirectory);
        AnalyzerProcessRunner runner = runner(Duration.ofSeconds(5), commandFactory);

        AnalyzerReport report = runner.analyze(repositoryPath);

        assertThat(report.protocolVersion()).isEqualTo("1.0");
        assertThat(report.taskId()).isEqualTo("task_demo_001");
        assertThat(workDirectory.get()).isNotNull();
        assertThat(workDirectory.get()).doesNotExist();
    }

    @ParameterizedTest
    @CsvSource({
            "exit-1, INVALID_ARGUMENTS, 1",
            "exit-2, REPOSITORY_NOT_FOUND, 2",
            "exit-3, SCAN_FAILED, 3",
            "exit-4, REPORT_OUTPUT_FAILED, 4"
    })
    void mapsKnownExitCodesAndCleansWorkDirectory(
            String scenario,
            AnalyzerExecutionFailure expectedFailure,
            int expectedExitCode
    ) throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("repository-" + expectedExitCode));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerProcessRunner runner = runner(
                Duration.ofSeconds(5),
                fakeAnalyzerCommand(scenario, workDirectory)
        );

        AnalyzerExecutionException exception = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(repositoryPath)
        );

        assertThat(exception.failure()).isEqualTo(expectedFailure);
        assertThat(exception.exitCode()).isEqualTo(expectedExitCode);
        assertThat(exception).hasMessageContaining("code " + expectedExitCode);
        assertThat(workDirectory.get()).doesNotExist();
    }

    @Test
    void rejectsUnknownExitCodeAndCleansWorkDirectory() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("unknown-exit-repository"));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerProcessRunner runner = runner(
                Duration.ofSeconds(5),
                fakeAnalyzerCommand("exit-unknown", workDirectory)
        );

        AnalyzerExecutionException exception = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(repositoryPath)
        );

        assertThat(exception.failure()).isEqualTo(AnalyzerExecutionFailure.UNKNOWN_EXIT_CODE);
        assertThat(exception.exitCode()).isEqualTo(17);
        assertThat(exception).hasMessageContaining("unknown failure");
        assertThat(workDirectory.get()).doesNotExist();
    }

    @Test
    void rejectsMissingReportAndCleansWorkDirectory() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("missing-report-repository"));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerProcessRunner runner = runner(
                Duration.ofSeconds(5),
                fakeAnalyzerCommand("missing-report", workDirectory)
        );

        AnalyzerExecutionException exception = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(repositoryPath)
        );

        assertThat(exception.failure()).isEqualTo(AnalyzerExecutionFailure.REPORT_MISSING);
        assertThat(exception).hasMessageContaining("did not create a regular report file");
        assertThat(workDirectory.get()).doesNotExist();
    }

    @Test
    void wrapsInvalidReportAndCleansWorkDirectory() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("invalid-report-repository"));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerProcessRunner runner = runner(
                Duration.ofSeconds(5),
                fakeAnalyzerCommand("invalid-report", workDirectory)
        );

        AnalyzerExecutionException exception = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(repositoryPath)
        );

        assertThat(exception.failure()).isEqualTo(AnalyzerExecutionFailure.REPORT_INVALID);
        assertThat(exception).hasCauseInstanceOf(AnalyzerReportReadException.class);
        assertThat(workDirectory.get()).doesNotExist();
    }

    @Test
    void keepsIOExceptionWhenProcessCannotStartAndCleansWorkDirectory() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("start-failure-repository"));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerCommandFactory commandFactory = (ignoredRepositoryPath, reportPath) -> {
            workDirectory.set(reportPath.getParent());
            return List.of(testDirectory.resolve("missing analyzer executable").toString());
        };
        AnalyzerProcessRunner runner = runner(Duration.ofSeconds(5), commandFactory);

        AnalyzerExecutionException exception = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(repositoryPath)
        );

        assertThat(exception.failure()).isEqualTo(AnalyzerExecutionFailure.START_FAILED);
        assertThat(exception).hasCauseInstanceOf(java.io.IOException.class);
        assertThat(workDirectory.get()).doesNotExist();
    }

    @Test
    void rejectsMissingAndRegularFileInputsBeforeBuildingCommand() throws Exception {
        AtomicBoolean commandFactoryCalled = new AtomicBoolean();
        AnalyzerCommandFactory commandFactory = (repositoryPath, reportPath) -> {
            commandFactoryCalled.set(true);
            return List.of("must-not-run");
        };
        AnalyzerProcessRunner runner = runner(Duration.ofSeconds(5), commandFactory);
        Path missingPath = testDirectory.resolve("missing repository");
        Path regularFile = Files.createFile(testDirectory.resolve("repository.txt"));

        AnalyzerExecutionException missingPathException = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(missingPath)
        );
        AnalyzerExecutionException regularFileException = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(regularFile)
        );

        assertThat(missingPathException.failure())
                .isEqualTo(AnalyzerExecutionFailure.INVALID_REPOSITORY_PATH);
        assertThat(regularFileException.failure())
                .isEqualTo(AnalyzerExecutionFailure.INVALID_REPOSITORY_PATH);
        assertThat(commandFactoryCalled).isFalse();
    }

    @Test
    void terminatesTimedOutProcessPromptlyAndCleansWorkDirectory() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("timeout-repository"));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerProcessRunner runner = runner(
                Duration.ofMillis(100),
                fakeAnalyzerCommand("timeout", workDirectory)
        );
        long startedAt = System.nanoTime();

        AnalyzerExecutionException exception = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(repositoryPath)
        );
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(exception.failure()).isEqualTo(AnalyzerExecutionFailure.TIMEOUT);
        assertThat(exception).hasMessageContaining("timed out");
        assertThat(elapsedMillis).isLessThan(5_000);
        assertThat(workDirectory.get()).doesNotExist();
    }

    @Test
    void terminatesProcessAndRestoresInterruptFlag() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("interrupted-repository"));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerProcessRunner runner = runner(
                Duration.ofSeconds(30),
                fakeAnalyzerCommand("timeout", workDirectory)
        );
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                runner.analyze(repositoryPath);
            } catch (Throwable exception) {
                thrown.set(exception);
                interruptFlagRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "analyzer-runner-interruption-test");

        worker.start();
        waitUntilFakeAnalyzerStarts(workDirectory);
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(AnalyzerExecutionException.class);
        AnalyzerExecutionException exception = (AnalyzerExecutionException) thrown.get();
        assertThat(exception.failure()).isEqualTo(AnalyzerExecutionFailure.INTERRUPTED);
        assertThat(exception).hasCauseInstanceOf(InterruptedException.class);
        assertThat(interruptFlagRestored).isTrue();
        assertThat(workDirectory.get()).doesNotExist();
    }

    @Test
    void boundsLargeStdoutAndStderrDiagnosticTails() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("large-output-repository"));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerProcessRunner runner = runner(
                Duration.ofSeconds(5),
                fakeAnalyzerCommand("large-output", workDirectory)
        );

        AnalyzerExecutionException exception = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(repositoryPath)
        );

        assertThat(exception.failure()).isEqualTo(AnalyzerExecutionFailure.SCAN_FAILED);
        assertThat(exception.getMessage())
                .contains("stdout-end", "stderr-end")
                .doesNotContain("stdout-start", "stderr-start");
        assertThat(exception.getMessage().length()).isLessThan(17_000);
        assertThat(workDirectory.get()).doesNotExist();
    }

    @Test
    void terminatesDescendantProcessOnTimeout() throws Exception {
        Path repositoryPath = Files.createDirectory(testDirectory.resolve("descendant-repository"));
        AtomicReference<Path> workDirectory = new AtomicReference<>();
        AnalyzerProcessRunner runner = runner(
                Duration.ofSeconds(1),
                fakeAnalyzerCommand("timeout-with-descendant", workDirectory)
        );

        AnalyzerExecutionException exception = catchThrowableOfType(
                AnalyzerExecutionException.class,
                () -> runner.analyze(repositoryPath)
        );
        Matcher pidMatcher = DESCENDANT_PID_PATTERN.matcher(exception.getMessage());

        assertThat(exception.failure()).isEqualTo(AnalyzerExecutionFailure.TIMEOUT);
        assertThat(pidMatcher.find()).isTrue();
        long descendantPid = Long.parseLong(pidMatcher.group(1));
        assertThat(isProcessAlive(descendantPid)).isFalse();
        assertThat(workDirectory.get()).doesNotExist();
    }

    private AnalyzerProcessRunner runner(Duration timeout, AnalyzerCommandFactory commandFactory) {
        AnalyzerProcessProperties properties = new AnalyzerProcessProperties("unused-in-tests", timeout);
        return new AnalyzerProcessRunner(properties, commandFactory, reportReader);
    }

    private AnalyzerCommandFactory fakeAnalyzerCommand(
            String scenario,
            AtomicReference<Path> workDirectory
    ) {
        return (repositoryPath, reportPath) -> {
            workDirectory.set(reportPath.getParent());
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-cp");
            command.add(testClasspath());
            command.add(FakeAnalyzerMain.class.getName());
            command.add(scenario);
            command.add("--path");
            command.add(repositoryPath.toString());
            command.add("--output");
            command.add(reportPath.toString());
            return command;
        };
    }

    private String javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private String testClasspath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private void waitUntilFakeAnalyzerStarts(AtomicReference<Path> workDirectory) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Path directory = workDirectory.get();
            if (directory != null) {
                Path stdoutPath = directory.resolve("stdout.log");
                if (Files.isRegularFile(stdoutPath) && Files.size(stdoutPath) > 0) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Fake analyzer did not start within five seconds");
    }

    private boolean isProcessAlive(long processId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean alive = ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                return false;
            }
            Thread.sleep(10);
        }
        return ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false);
    }
}
