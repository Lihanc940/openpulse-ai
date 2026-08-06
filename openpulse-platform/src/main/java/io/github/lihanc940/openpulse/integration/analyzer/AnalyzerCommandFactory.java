package io.github.lihanc940.openpulse.integration.analyzer;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
public interface AnalyzerCommandFactory {

    List<String> create(Path repositoryPath, Path reportPath);
}

@Component
final class ConfiguredAnalyzerCommandFactory implements AnalyzerCommandFactory {

    private final AnalyzerProcessProperties properties;

    ConfiguredAnalyzerCommandFactory(AnalyzerProcessProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<String> create(Path repositoryPath, Path reportPath) {
        return List.of(
                properties.executable(),
                "--path",
                repositoryPath.toString(),
                "--output",
                reportPath.toString()
        );
    }
}
