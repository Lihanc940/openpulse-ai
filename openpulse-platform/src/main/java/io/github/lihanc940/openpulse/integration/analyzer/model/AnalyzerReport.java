package io.github.lihanc940.openpulse.integration.analyzer.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(String path, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(
            long totalFiles,
            long sourceFiles,
            long documentFiles,
            long configFiles,
            long testFiles,
            long totalLines,
            long codeLines,
            long commentLines,
            long blankLines
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Language(String name, long files, long lines) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quality(
            long complexityScore,
            long maintainabilityScore,
            long documentationScore,
            long testScore
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Risk(
            String ruleId,
            String type,
            RiskLevel level,
            String file,
            long line,
            String message,
            Map<String, Object> evidence
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dependencies(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges
    ) {
    }
}
