package io.github.lihanc940.openpulse.integration.analyzer.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record AnalyzerReport(
        String protocolVersion,
        String taskId,
        AnalyzerStatus status,
        Repository repository,
        Summary summary,
        List<Language> languages,
        Structure structure,
        Quality quality,
        List<Risk> risks,
        Dependencies dependencies,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime generatedAt
) {

    public record Repository(String path, String name) {
    }

    public record Summary(
            int totalFiles,
            int sourceFiles,
            int documentFiles,
            int configFiles,
            int testFiles,
            int totalLines,
            int codeLines,
            int commentLines,
            int blankLines
    ) {
    }

    public record Language(String name, int files, int lines) {
    }

    public record Structure(
            boolean hasReadme,
            boolean hasLicense,
            boolean hasContributing,
            boolean hasChangelog,
            boolean hasCi,
            boolean hasTests,
            boolean hasDockerfile,
            List<String> buildFiles
    ) {
    }

    public record Quality(
            int complexityScore,
            int maintainabilityScore,
            int documentationScore,
            int testScore
    ) {
    }

    public record Risk(
            String ruleId,
            String type,
            RiskLevel level,
            String file,
            int line,
            String message,
            Map<String, Object> evidence
    ) {
    }

    public record Dependencies(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges
    ) {
    }
}
