package io.github.lihanc940.openpulse.analysis.api;

import io.github.lihanc940.openpulse.analysis.application.LocalAnalysisService;
import io.github.lihanc940.openpulse.analysis.application.InvalidLocalAnalysisRequestException;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis")
@ConditionalOnProperty(prefix = "openpulse.analysis", name = "local-api-enabled", havingValue = "true")
public class LocalAnalysisController {

    private final LocalAnalysisService localAnalysisService;

    public LocalAnalysisController(LocalAnalysisService localAnalysisService) {
        this.localAnalysisService = localAnalysisService;
    }

    @PostMapping("/local")
    public AnalyzerReport analyze(@RequestBody LocalAnalysisRequest request) {
        if (request == null || request.repositoryPath() == null || request.repositoryPath().isBlank()) {
            throw new InvalidLocalAnalysisRequestException();
        }
        return localAnalysisService.analyze(request.repositoryPath());
    }
}
