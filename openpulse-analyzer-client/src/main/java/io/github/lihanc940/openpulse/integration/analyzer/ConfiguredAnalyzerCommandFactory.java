package io.github.lihanc940.openpulse.integration.analyzer;

import java.nio.file.Path;
import java.util.List;

public final class ConfiguredAnalyzerCommandFactory implements AnalyzerCommandFactory {

    private final AnalyzerProcessProperties properties;

    public ConfiguredAnalyzerCommandFactory(AnalyzerProcessProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<String> create(Path repositoryPath, Path reportPath) {
        return List.of(
                properties.executable(),
                "--protocol",
                properties.protocolVersion().cliValue(),
                "--path",
                repositoryPath.toString(),
                "--output",
                reportPath.toString()
        );
    }
}
