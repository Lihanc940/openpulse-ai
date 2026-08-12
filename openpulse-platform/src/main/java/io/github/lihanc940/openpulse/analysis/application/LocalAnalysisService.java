package io.github.lihanc940.openpulse.analysis.application;

import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerProcessRunner;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import org.springframework.stereotype.Service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@Service
public class LocalAnalysisService {

    private final AnalyzerProcessRunner analyzerProcessRunner;

    public LocalAnalysisService(AnalyzerProcessRunner analyzerProcessRunner) {
        this.analyzerProcessRunner = analyzerProcessRunner;
    }

    public AnalyzerReport analyze(String repositoryPath) {
        try {
            return analyzerProcessRunner.analyze(Path.of(repositoryPath));
        } catch (InvalidPathException exception) {
            throw new InvalidLocalAnalysisRequestException();
        }
    }
}
