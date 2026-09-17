package io.github.lihanc940.openpulse.report.application;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerReportReadException;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerReportV2SchemaValidator;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerReportV2SemanticValidator;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Evidence;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Finding;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Limitation;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportRecord;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportStatus;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class AnalysisReportV2SnapshotMapper {

    private static final String PROTOCOL_V2 = "2.0";
    private static final DateTimeFormatter RFC_3339_WITH_SECONDS = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .appendOffsetId()
            .toFormatter(Locale.ROOT);

    private final ObjectMapper objectMapper;
    private final ObjectReader treeReader;
    private final ObjectReader strictV2Reader;
    private final AnalyzerReportV2SchemaValidator schemaValidator;
    private final AnalyzerReportV2SemanticValidator semanticValidator;

    public AnalysisReportV2SnapshotMapper(
            ObjectMapper objectMapper,
            AnalyzerReportV2SchemaValidator schemaValidator,
            AnalyzerReportV2SemanticValidator semanticValidator
    ) {
        this.objectMapper = objectMapper;
        treeReader = objectMapper.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        strictV2Reader = objectMapper.readerFor(AnalyzerReportV2.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        this.schemaValidator = schemaValidator;
        this.semanticValidator = semanticValidator;
    }

    public AnalysisReportRecord create(
            AnalysisTaskId platformTaskId,
            AnalyzerReportV2 analyzerReport,
            Clock clock
    ) {
        Objects.requireNonNull(platformTaskId, "platformTaskId must not be null");
        if (analyzerReport == null) {
            throw invalid("Analyzer report must not be null");
        }
        if (analyzerReport.status() == AnalyzerReportV2.Status.FAILED) {
            throw invalid("A FAILED analyzer report cannot be persisted as a usable snapshot");
        }
        try {
            semanticValidator.validate(analyzerReport);
        } catch (AnalyzerReportReadException exception) {
            throw invalid("Analyzer report violates protocol 2.0 semantics", exception);
        }

        String reportJson = write(snapshot(analyzerReport));
        AnalysisReportRecord record = new AnalysisReportRecord(
                platformTaskId,
                analyzerReport.protocolVersion(),
                analyzerReport.taskId(),
                AnalysisReportStatus.valueOf(analyzerReport.status().name()),
                reportJson,
                analyzerReport.generatedAt().toInstant(),
                Objects.requireNonNull(clock, "clock must not be null").instant()
        );
        validateSnapshot(record);
        return record;
    }

    public void validateSnapshot(AnalysisReportRecord record) {
        Objects.requireNonNull(record, "report must not be null");
        if (!PROTOCOL_V2.equals(record.protocolVersion())) {
            throw invalid("Analysis report snapshot protocol version is not 2.0");
        }
        JsonNode root;
        AnalyzerReportV2 report;
        try {
            root = treeReader.readTree(record.reportJson());
            schemaValidator.validate(root);
            report = strictV2Reader.readValue(root);
            semanticValidator.validate(report);
        } catch (JacksonException | AnalyzerReportReadException exception) {
            throw invalid("Analysis report snapshot violates the protocol 2.0 whitelist", exception);
        }
        if (report.status() == AnalyzerReportV2.Status.FAILED) {
            throw invalid("A FAILED analyzer report snapshot is not a usable persisted report");
        }
        if (!record.protocolVersion().equals(report.protocolVersion())
                || !record.analyzerTaskId().equals(report.taskId())
                || !record.reportStatus().name().equals(report.status().name())
                || !record.generatedAt().equals(report.generatedAt().toInstant())) {
            throw invalid("Analysis report snapshot metadata is inconsistent");
        }
    }

    private Map<String, Object> snapshot(AnalyzerReportV2 report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("protocolVersion", report.protocolVersion());
        root.put("taskId", report.taskId());
        root.put("analyzer", analyzerIdentity(report.analyzer()));
        root.put("ruleSet", ruleSetIdentity(report.ruleSet()));
        root.put("status", report.status().name());
        root.put("reviewability", report.reviewability().name());
        root.put("repository", repository(report.repository()));
        if (report.summary() != null) {
            root.put("summary", summary(report.summary()));
        }
        if (report.languages() != null) {
            root.put("languages", report.languages().stream().map(this::language).toList());
        }
        if (report.structure() != null) {
            root.put("structure", structure(report.structure()));
        }
        root.put("findings", report.findings().stream().map(this::finding).toList());
        root.put("limitations", report.limitations().stream().map(this::limitation).toList());
        root.put("generatedAt", RFC_3339_WITH_SECONDS.format(report.generatedAt()));
        return root;
    }

    private Map<String, Object> analyzerIdentity(AnalyzerReportV2.AnalyzerIdentity identity) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", identity.name());
        value.put("version", identity.version());
        return value;
    }

    private Map<String, Object> ruleSetIdentity(AnalyzerReportV2.RuleSetIdentity identity) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", identity.id());
        value.put("version", identity.version());
        return value;
    }

    private Map<String, Object> repository(AnalyzerReportV2.Repository repository) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", repository.name());
        value.put("root", repository.root());
        if (repository.revision() != null) {
            value.put("revision", repository.revision());
        }
        return value;
    }

    private Map<String, Object> summary(AnalyzerReportV2.Summary summary) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("totalFiles", summary.totalFiles());
        value.put("sourceFiles", summary.sourceFiles());
        value.put("documentFiles", summary.documentFiles());
        value.put("configFiles", summary.configFiles());
        value.put("testFiles", summary.testFiles());
        value.put("totalLines", summary.totalLines());
        value.put("codeLines", summary.codeLines());
        value.put("commentLines", summary.commentLines());
        value.put("blankLines", summary.blankLines());
        return value;
    }

    private Map<String, Object> language(AnalyzerReportV2.Language language) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", language.name());
        value.put("files", language.files());
        value.put("lines", language.lines());
        return value;
    }

    private Map<String, Object> structure(AnalyzerReportV2.Structure structure) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("hasReadme", structure.hasReadme());
        value.put("hasLicense", structure.hasLicense());
        value.put("hasContributing", structure.hasContributing());
        value.put("hasChangelog", structure.hasChangelog());
        value.put("hasCi", structure.hasCi());
        value.put("hasTests", structure.hasTests());
        value.put("hasDockerfile", structure.hasDockerfile());
        value.put("buildFiles", List.copyOf(structure.buildFiles()));
        return value;
    }

    private Map<String, Object> finding(Finding finding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("findingId", finding.findingId());
        value.put("ruleId", finding.ruleId());
        value.put("type", finding.type().name());
        value.put("severity", finding.severity().name());
        value.put("scope", finding.scope().name());
        value.put("location", finding.location() == null ? null : location(finding.location()));
        value.put("message", finding.message());
        value.put("evidence", evidence(finding.evidence()));
        value.put("remediation", finding.remediation());
        return value;
    }

    private Map<String, Object> location(AnalyzerReportV2.Location location) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("relativePath", location.relativePath());
        value.put("startLine", location.startLine());
        value.put("startColumn", location.startColumn());
        value.put("endLine", location.endLine());
        value.put("endColumn", location.endColumn());
        return value;
    }

    private Map<String, Object> evidence(Evidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", evidence.kind().name());
        switch (evidence) {
            case AnalyzerReportV2.ExpectedPathsAbsentEvidence item ->
                    value.put("expectedPaths", List.copyOf(item.expectedPaths()));
            case AnalyzerReportV2.FileMetricEvidence item -> {
                value.put("metric", item.metric().name());
                value.put("observed", item.observed());
                value.put("threshold", item.threshold());
            }
            case AnalyzerReportV2.TextMatchEvidence item -> {
                value.put("matchKind", item.matchKind().name());
                value.put("occurrences", item.occurrences());
                value.put("threshold", item.threshold());
            }
            case AnalyzerReportV2.SyntaxMetricEvidence item -> {
                value.put("symbolKind", item.symbolKind().name());
                value.put("symbolName", item.symbolName());
                value.put("metric", item.metric().name());
                value.put("observed", item.observed());
                value.put("threshold", item.threshold());
            }
        }
        return value;
    }

    private Map<String, Object> limitation(Limitation limitation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", limitation.kind().name());
        value.put("scope", limitation.scope().name());
        switch (limitation) {
            case AnalyzerReportV2.FileSkippedLimitation item -> {
                value.put("relativePath", item.relativePath());
                value.put("reason", item.reason().name());
            }
            case AnalyzerReportV2.UnsupportedCapabilityLimitation item -> {
                value.put("capabilityType", item.capabilityType().name());
                value.put("capability", item.capability());
            }
            case AnalyzerReportV2.ResourceLimitReachedLimitation item -> {
                value.put("limitType", item.limitType().name());
                value.put("configuredLimit", item.configuredLimit());
                value.put("observedAtLeast", item.observedAtLeast());
            }
            case AnalyzerReportV2.ScanFailedLimitation item -> value.put("reason", item.reason().name());
        }
        value.put("message", limitation.message());
        return value;
    }

    private String write(Map<String, Object> snapshot) {
        try {
            JsonNode root = objectMapper.valueToTree(snapshot);
            schemaValidator.validate(root);
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException | AnalyzerReportReadException exception) {
            throw invalid("Analysis report snapshot could not be serialized safely", exception);
        }
    }

    private PersistenceOperationException invalid(String message) {
        return new PersistenceOperationException(PersistenceFailure.INVALID_DATA, message);
    }

    private PersistenceOperationException invalid(String message, Throwable cause) {
        return new PersistenceOperationException(PersistenceFailure.INVALID_DATA, message, cause);
    }
}
