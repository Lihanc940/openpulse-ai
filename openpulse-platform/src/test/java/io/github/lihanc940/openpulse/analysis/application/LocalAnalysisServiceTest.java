package io.github.lihanc940.openpulse.analysis.application;

import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerProcessRunner;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAnalysisServiceTest {

    private final AnalyzerProcessRunner analyzerProcessRunner = mock(AnalyzerProcessRunner.class);
    private final LocalAnalysisService service = new LocalAnalysisService(analyzerProcessRunner);

    @Test
    void convertsRequestPathAndCallsAnalyzerRunner() {
        Path repositoryPath = Path.of("sample repository");
        AnalyzerReport report = mock(AnalyzerReport.class);
        when(analyzerProcessRunner.analyze(repositoryPath)).thenReturn(report);

        service.analyze(repositoryPath.toString());

        verify(analyzerProcessRunner).analyze(repositoryPath);
    }

    @Test
    void rejectsPathWithInvalidSyntax() {
        assertThatExceptionOfType(InvalidLocalAnalysisRequestException.class)
                .isThrownBy(() -> service.analyze("invalid\u0000path"));
    }
}
