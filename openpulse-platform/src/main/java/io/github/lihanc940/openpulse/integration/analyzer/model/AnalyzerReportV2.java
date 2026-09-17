package io.github.lihanc940.openpulse.integration.analyzer.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.OffsetDateTime;
import java.util.List;

public record AnalyzerReportV2(
        String protocolVersion,
        String taskId,
        AnalyzerIdentity analyzer,
        RuleSetIdentity ruleSet,
        Status status,
        Reviewability reviewability,
        Repository repository,
        Summary summary,
        List<Language> languages,
        Structure structure,
        List<Finding> findings,
        List<Limitation> limitations,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime generatedAt
) implements AnalyzerReportDocument {

    @Override
    public String reportStatus() {
        return status == null ? null : status.name();
    }

    public record AnalyzerIdentity(String name, String version) {
    }

    public record RuleSetIdentity(String id, String version) {
    }

    public record Repository(String name, String root, String revision) {
    }

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

    public record Language(String name, long files, long lines) {
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

    public record Finding(
            String findingId,
            String ruleId,
            FindingType type,
            Severity severity,
            Scope scope,
            Location location,
            String message,
            Evidence evidence,
            String remediation
    ) {
    }

    public record Location(
            String relativePath,
            long startLine,
            long startColumn,
            long endLine,
            long endColumn
    ) {
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind",
            visible = true
    )
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ExpectedPathsAbsentEvidence.class, name = "EXPECTED_PATHS_ABSENT"),
            @JsonSubTypes.Type(value = FileMetricEvidence.class, name = "FILE_METRIC"),
            @JsonSubTypes.Type(value = TextMatchEvidence.class, name = "TEXT_MATCH"),
            @JsonSubTypes.Type(value = SyntaxMetricEvidence.class, name = "SYNTAX_METRIC")
    })
    public sealed interface Evidence permits ExpectedPathsAbsentEvidence, FileMetricEvidence,
            TextMatchEvidence, SyntaxMetricEvidence {

        EvidenceKind kind();
    }

    public record ExpectedPathsAbsentEvidence(
            EvidenceKind kind,
            List<String> expectedPaths
    ) implements Evidence {
    }

    public record FileMetricEvidence(
            EvidenceKind kind,
            FileMetric metric,
            long observed,
            long threshold
    ) implements Evidence {
    }

    public record TextMatchEvidence(
            EvidenceKind kind,
            TextMatchKind matchKind,
            long occurrences,
            long threshold
    ) implements Evidence {
    }

    public record SyntaxMetricEvidence(
            EvidenceKind kind,
            SymbolKind symbolKind,
            String symbolName,
            SyntaxMetric metric,
            long observed,
            long threshold
    ) implements Evidence {
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind",
            visible = true
    )
    @JsonSubTypes({
            @JsonSubTypes.Type(value = FileSkippedLimitation.class, name = "FILE_SKIPPED"),
            @JsonSubTypes.Type(value = UnsupportedCapabilityLimitation.class, name = "UNSUPPORTED_CAPABILITY"),
            @JsonSubTypes.Type(value = ResourceLimitReachedLimitation.class, name = "RESOURCE_LIMIT_REACHED"),
            @JsonSubTypes.Type(value = ScanFailedLimitation.class, name = "SCAN_FAILED")
    })
    public sealed interface Limitation permits FileSkippedLimitation, UnsupportedCapabilityLimitation,
            ResourceLimitReachedLimitation, ScanFailedLimitation {

        LimitationKind kind();

        Scope scope();

        String message();
    }

    public record FileSkippedLimitation(
            LimitationKind kind,
            Scope scope,
            String relativePath,
            FileSkippedReason reason,
            String message
    ) implements Limitation {
    }

    public record UnsupportedCapabilityLimitation(
            LimitationKind kind,
            Scope scope,
            CapabilityType capabilityType,
            String capability,
            String message
    ) implements Limitation {
    }

    public record ResourceLimitReachedLimitation(
            LimitationKind kind,
            Scope scope,
            LimitType limitType,
            long configuredLimit,
            long observedAtLeast,
            String message
    ) implements Limitation {
    }

    public record ScanFailedLimitation(
            LimitationKind kind,
            Scope scope,
            ScanFailedReason reason,
            String message
    ) implements Limitation {
    }

    public enum Status {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILED
    }

    public enum Reviewability {
        COMPLETE,
        PARTIAL,
        NOT_USABLE
    }

    public enum FindingType {
        PROJECT_STRUCTURE,
        TEXT,
        SYNTAX_TREE
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum Scope {
        REPOSITORY,
        FILE
    }

    public enum EvidenceKind {
        EXPECTED_PATHS_ABSENT,
        FILE_METRIC,
        TEXT_MATCH,
        SYNTAX_METRIC
    }

    public enum FileMetric {
        BYTE_COUNT,
        LINE_COUNT
    }

    public enum TextMatchKind {
        DANGEROUS_FUNCTION,
        FIXME_MARKER,
        TODO_MARKER
    }

    public enum SymbolKind {
        FUNCTION,
        METHOD
    }

    public enum SyntaxMetric {
        FUNCTION_LINES,
        MAX_NESTING_DEPTH,
        PARAMETER_COUNT
    }

    public enum LimitationKind {
        FILE_SKIPPED,
        UNSUPPORTED_CAPABILITY,
        RESOURCE_LIMIT_REACHED,
        SCAN_FAILED
    }

    public enum FileSkippedReason {
        FILE_TOO_LARGE,
        PERMISSION_DENIED,
        READ_ERROR,
        UNSUPPORTED_ENCODING,
        UNSUPPORTED_FILE_TYPE
    }

    public enum CapabilityType {
        LANGUAGE,
        RULE_TYPE
    }

    public enum LimitType {
        BYTE_COUNT,
        DURATION_MS,
        FILE_COUNT
    }

    public enum ScanFailedReason {
        INTERNAL_ERROR,
        TRAVERSAL_ERROR
    }
}
