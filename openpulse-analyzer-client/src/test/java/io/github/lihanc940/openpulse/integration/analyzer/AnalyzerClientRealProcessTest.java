package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Optional real-executable check, with no Spring or database on this module's classpath. */
@EnabledIfSystemProperty(named = "openpulse.analyzer.test.executable", matches = ".+")
class AnalyzerClientRealProcessTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void sharedClientMatchesDirectCliForChineseAndSpaceContainingMonorepo() throws Exception {
        Path repository = Files.createDirectory(temporaryDirectory.resolve("中文 多模块项目"));
        Path module = Files.createDirectory(repository.resolve("Java 子模块"));
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Path tests = Files.createDirectories(module.resolve("src/test/java"));
        Files.writeString(tests.resolve("ExampleTest.java"), "class ExampleTest {}\n");
        Files.writeString(repository.resolve("README.md"), "# 演示项目\n");

        AnalyzerProcessProperties properties = new AnalyzerProcessProperties(
                System.getProperty("openpulse.analyzer.test.executable"), Duration.ofSeconds(30), "2.0");
        AnalyzerCommandFactory commands = new ConfiguredAnalyzerCommandFactory(properties);
        AnalyzerReportReader reader = ClientTestSupport.reader();
        AnalyzerReportV2 report = (AnalyzerReportV2) new AnalyzerProcessRunner(properties, commands, reader)
                .analyze(repository);

        assertThat(report.status()).isEqualTo(AnalyzerReportV2.Status.SUCCESS);
        assertThat(report.structure().hasTests()).isTrue();
        assertThat(report.structure().buildFiles()).containsExactly("Java 子模块/pom.xml");
        assertThat(report.summary().testFiles()).isEqualTo(1);

        Path directJson = temporaryDirectory.resolve("direct.json");
        Process direct = new ProcessBuilder(commands.create(repository, directJson))
                .redirectOutput(temporaryDirectory.resolve("stdout.log").toFile())
                .redirectError(temporaryDirectory.resolve("stderr.log").toFile()).start();
        try {
            assertThat(direct.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(direct.exitValue()).isZero();
        } finally {
            if (direct.isAlive()) {
                direct.destroyForcibly();
                direct.waitFor(5, TimeUnit.SECONDS);
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode viaClient = (ObjectNode) mapper.valueToTree(report);
        ObjectNode viaCli = (ObjectNode) mapper.valueToTree(reader.readV2(directJson));
        viaClient.remove("taskId");
        viaClient.remove("generatedAt");
        viaCli.remove("taskId");
        viaCli.remove("generatedAt");
        assertThat(viaClient).isEqualTo(viaCli);
    }
}
