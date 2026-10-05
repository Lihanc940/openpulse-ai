package io.github.lihanc940.openpulse.integration.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyzerCommandFactoryTest {

    @ParameterizedTest
    @CsvSource({
            "1.0, 1.0",
            "2.0, 2.0"
    })
    void createsExplicitProtocolArgumentsAndKeepsPathsWithSpaces(
            String configuredVersion,
            String expectedCliVersion
    ) {
        AnalyzerProcessProperties properties = new AnalyzerProcessProperties(
                "openpulse-analyzer",
                Duration.ofSeconds(30),
                configuredVersion
        );
        AnalyzerCommandFactory factory = new ConfiguredAnalyzerCommandFactory(properties);
        Path repositoryPath = Path.of("repositories", "demo project");
        Path reportPath = Path.of("temporary reports", "report.json");

        assertThat(factory.create(repositoryPath, reportPath)).containsExactly(
                "openpulse-analyzer",
                "--protocol",
                expectedCliVersion,
                "--path",
                repositoryPath.toString(),
                "--output",
                reportPath.toString()
        );
    }

    @Test
    void rejectsBlankExecutable() {
        assertThatThrownBy(() -> new AnalyzerProcessProperties(" ", Duration.ofSeconds(30), "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executable");
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> new AnalyzerProcessProperties(
                "openpulse-analyzer",
                Duration.ZERO,
                "1.0"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1", "2", "2.1", "latest", "9.9", " 1.0", "1.0 "})
    void rejectsUnsupportedOrInexactProtocolVersions(String protocolVersion) {
        assertThatThrownBy(() -> new AnalyzerProcessProperties(
                "openpulse-analyzer",
                Duration.ofSeconds(30),
                protocolVersion
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protocol version");
    }
}
