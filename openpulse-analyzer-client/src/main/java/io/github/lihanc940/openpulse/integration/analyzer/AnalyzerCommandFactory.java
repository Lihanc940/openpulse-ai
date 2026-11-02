package io.github.lihanc940.openpulse.integration.analyzer;

import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
public interface AnalyzerCommandFactory {

    List<String> create(Path repositoryPath, Path reportPath);
}
