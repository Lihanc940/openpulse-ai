package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.*;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class DesktopProcessTest {
    @TempDir Path temporary;
    private final AnalyzerReportReader reader = ReportFilesTest.reader();
    private final ReportFiles files = new ReportFiles(ReportFilesTest.MAPPER, reader);

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "timeout", "close"})
    void serviceTerminatesActualProcessTreeAndCleansWorkDirectory(String operation) throws Exception {
        AnalyzerProcessRunner runner = SlowProcessMain.runner(temporary, operation.equals("timeout") ? Duration.ofSeconds(2) : Duration.ofSeconds(30));
        var service = new DesktopAnalysisService(path -> (AnalyzerReportV2) runner.analyze(path), files, Runnable::run, ignored -> { });
        try (service) {
            service.analyze(temporary.toString());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(temporary.resolve("child.pid")) && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(temporary.resolve("child.pid")).exists();
            if (operation.equals("cancel")) service.cancel();
            if (operation.equals("close")) service.close();
            service.whenIdle().get(8, TimeUnit.SECONDS);
            for (String name : List.of("parent.pid", "child.pid")) {
                long pid = Long.parseLong(Files.readString(temporary.resolve(name)));
                assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            }
            assertThat(Path.of(Files.readString(temporary.resolve("workdir.txt")))).doesNotExist();
            if (operation.equals("timeout")) assertThat(service.snapshot().message()).contains("超时");
            if (operation.equals("cancel")) assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.CANCELLED);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "openpulse.analyzer.test.executable", matches = ".+")
    void actualCppThroughDesktopServiceExportsReopensAndMatchesCli() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("中文 空格多模块"));
        Path module = Files.createDirectories(repository.resolve("Java 模块/src/test/java"));
        Files.writeString(module.resolve("HelloTest.java"), "class HelloTest {}\n");
        Files.writeString(repository.resolve("Java 模块/pom.xml"), "<project/>\n");
        var properties = new AnalyzerProcessProperties(System.getProperty("openpulse.analyzer.test.executable"), Duration.ofSeconds(30), "2.0");
        var command = new ConfiguredAnalyzerCommandFactory(properties);
        var runner = new AnalyzerProcessRunner(properties, command, reader);
        try (var service = new DesktopAnalysisService(path -> (AnalyzerReportV2) runner.analyze(path), files, Runnable::run, ignored -> { })) {
            service.analyze(repository.toString()); service.whenIdle().get(8, TimeUnit.SECONDS);
            var result = service.snapshot().report();
            assertThat(result).isNotNull();
            assertThat(result.structure().buildFiles()).containsExactly("Java 模块/pom.xml");
            assertThat(result.structure().hasTests()).isTrue();
            Path saved = temporary.resolve("导出.json");
            service.save(saved); service.whenIdle().get(8, TimeUnit.SECONDS);
            service.open(saved); service.whenIdle().get(8, TimeUnit.SECONDS);
            assertThat(service.snapshot().report()).isEqualTo(result);
            Path direct = temporary.resolve("cli.json");
            Process process = new ProcessBuilder(command.create(repository, direct))
                    .redirectOutput(temporary.resolve("cli.log").toFile()).redirectErrorStream(true).start();
            try { assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
            ObjectNode actual = (ObjectNode) ReportFilesTest.MAPPER.valueToTree(reader.readV2(direct));
            ObjectNode expected = (ObjectNode) ReportFilesTest.MAPPER.valueToTree(result);
            for (ObjectNode tree : List.of(actual, expected)) { tree.remove("taskId"); tree.remove("generatedAt"); }
            assertThat(actual).isEqualTo(expected);
        }
    }
}
