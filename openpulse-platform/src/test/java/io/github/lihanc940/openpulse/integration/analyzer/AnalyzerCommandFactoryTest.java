package io.github.lihanc940.openpulse.integration.analyzer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyzerCommandFactoryTest {

    @Test
    void keepsPathsWithSpacesAsSingleArguments() {
        AnalyzerProcessProperties properties = new AnalyzerProcessProperties(
                "openpulse-analyzer",
                Duration.ofSeconds(30)
        );
        AnalyzerCommandFactory factory = new ConfiguredAnalyzerCommandFactory(properties);
        Path repositoryPath = Path.of("repositories", "demo project");
        Path reportPath = Path.of("temporary reports", "report.json");

        assertThat(factory.create(repositoryPath, reportPath)).containsExactly(
                "openpulse-analyzer",
                "--path",
                repositoryPath.toString(),
                "--output",
                reportPath.toString()
        );
    }

    @Test
    void rejectsBlankExecutable() {
        assertThatThrownBy(() -> new AnalyzerProcessProperties(" ", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executable");
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> new AnalyzerProcessProperties("openpulse-analyzer", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }
}
