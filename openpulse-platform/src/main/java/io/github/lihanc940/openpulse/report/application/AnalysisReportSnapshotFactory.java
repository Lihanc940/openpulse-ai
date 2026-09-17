package io.github.lihanc940.openpulse.report.application;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerReportV2SchemaValidator;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerReportV2SemanticValidator;
import io.github.lihanc940.openpulse.integration.analyzer.model.RiskLevel;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportRecord;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportStatus;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class AnalysisReportSnapshotFactory {

    private static final String SUPPORTED_PROTOCOL_VERSION = "1.0";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "protocolVersion", "taskId", "status", "repository", "summary", "languages",
            "structure", "quality", "risks", "dependencies", "generatedAt"
    );
    private static final Set<String> REPOSITORY_FIELDS = Set.of("name");
    private static final Set<String> SUMMARY_FIELDS = Set.of(
            "totalFiles", "sourceFiles", "documentFiles", "configFiles", "testFiles",
            "totalLines", "codeLines", "commentLines", "blankLines"
    );
    private static final Set<String> LANGUAGE_FIELDS = Set.of("name", "files", "lines");
    private static final Set<String> STRUCTURE_FIELDS = Set.of(
            "hasReadme", "hasLicense", "hasContributing", "hasChangelog", "hasCi",
            "hasTests", "hasDockerfile", "buildFiles"
    );
    private static final Set<String> QUALITY_FIELDS = Set.of(
            "complexityScore", "maintainabilityScore", "documentationScore", "testScore"
    );
    private static final Set<String> RISK_FIELDS = Set.of(
            "ruleId", "type", "level", "file", "line", "message", "evidence"
    );
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "expectedFile", "functionName", "functionLines", "threshold"
    );
    private static final Set<String> DEPENDENCY_FIELDS = Set.of("nodes", "edges");

    private final ObjectMapper objectMapper;
    private final AnalysisReportV2SnapshotMapper v2SnapshotMapper;

    @Autowired
    public AnalysisReportSnapshotFactory(
            ObjectMapper objectMapper,
            AnalysisReportV2SnapshotMapper v2SnapshotMapper
    ) {
        this.objectMapper = objectMapper;
        this.v2SnapshotMapper = v2SnapshotMapper;
    }

    AnalysisReportSnapshotFactory(ObjectMapper objectMapper) {
        this(
                objectMapper,
                new AnalysisReportV2SnapshotMapper(
                        objectMapper,
                        new AnalyzerReportV2SchemaValidator(),
                        new AnalyzerReportV2SemanticValidator(objectMapper)
                )
        );
    }

    public AnalysisReportRecord create(
            AnalysisTaskId platformTaskId,
            AnalyzerReport analyzerReport,
            Clock clock
    ) {
        Objects.requireNonNull(platformTaskId, "platformTaskId must not be null");
        validateAnalyzerReport(analyzerReport);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("protocolVersion", analyzerReport.protocolVersion());
        snapshot.put("taskId", analyzerReport.taskId());
        snapshot.put("status", analyzerReport.status().name());
        snapshot.put("repository", Map.of("name", analyzerReport.repository().name()));
        snapshot.put("summary", summary(analyzerReport.summary()));
        snapshot.put("languages", languages(analyzerReport.languages()));
        snapshot.put("structure", structure(analyzerReport.structure()));
        snapshot.put("quality", quality(analyzerReport.quality()));
        snapshot.put("risks", risks(analyzerReport.risks()));
        snapshot.put("dependencies", dependencies());
        snapshot.put("generatedAt", analyzerReport.generatedAt().toString());

        String reportJson = writeSnapshot(snapshot);
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

    public AnalysisReportRecord create(
            AnalysisTaskId platformTaskId,
            AnalyzerReportV2 analyzerReport,
            Clock clock
    ) {
        return v2SnapshotMapper.create(platformTaskId, analyzerReport, clock);
    }

    public void validateSnapshot(AnalysisReportRecord report) {
        Objects.requireNonNull(report, "report must not be null");
        if ("2.0".equals(report.protocolVersion())) {
            v2SnapshotMapper.validateSnapshot(report);
            return;
        }
        Object decoded;
        try {
            decoded = objectMapper.readValue(report.reportJson(), Object.class);
        } catch (JacksonException exception) {
            throw invalidSnapshot("Analysis report snapshot must contain valid JSON", exception);
        }
        if (!(decoded instanceof Map<?, ?> root)) {
            throw invalidSnapshot("Analysis report snapshot root must be an object");
        }
        requireExactFields(root, ROOT_FIELDS, "root");
        if (!SUPPORTED_PROTOCOL_VERSION.equals(report.protocolVersion())) {
            throw invalidSnapshot("Analysis report snapshot protocol version is unsupported");
        }
        requireEquals(root.get("protocolVersion"), report.protocolVersion(), "protocolVersion");
        requireEquals(root.get("taskId"), report.analyzerTaskId(), "analyzer taskId");
        requireEquals(root.get("status"), report.reportStatus().name(), "status");
        validateRepository(root.get("repository"));
        validateSummary(root.get("summary"));
        validateLanguages(root.get("languages"));
        validateStructure(root.get("structure"));
        validateQuality(root.get("quality"));
        validateRisks(root.get("risks"));
        validateDependencies(root.get("dependencies"));
        Object generatedAt = root.get("generatedAt");
        try {
            if (!(generatedAt instanceof String generatedAtText)
                    || !OffsetDateTime.parse(generatedAtText).toInstant().equals(report.generatedAt())) {
                throw invalidSnapshot("Analysis report snapshot generatedAt is inconsistent");
            }
        } catch (java.time.DateTimeException exception) {
            throw invalidSnapshot("Analysis report snapshot generatedAt is invalid", exception);
        }
    }

    private void validateAnalyzerReport(AnalyzerReport report) {
        if (report == null) {
            throw invalidSnapshot("Analyzer report must not be null");
        }
        if (!SUPPORTED_PROTOCOL_VERSION.equals(report.protocolVersion())) {
            throw invalidSnapshot("Analyzer report protocol version is unsupported");
        }
        requireNonBlank(report.taskId(), "Analyzer report taskId");
        Objects.requireNonNull(report.status(), "Analyzer report status must not be null");
        Objects.requireNonNull(report.repository(), "Analyzer report repository must not be null");
        requireNonBlank(report.repository().name(), "Analyzer report repository name");
        Objects.requireNonNull(report.summary(), "Analyzer report summary must not be null");
        requireNonNegative(
                report.summary().totalFiles(),
                report.summary().sourceFiles(),
                report.summary().documentFiles(),
                report.summary().configFiles(),
                report.summary().testFiles(),
                report.summary().totalLines(),
                report.summary().codeLines(),
                report.summary().commentLines(),
                report.summary().blankLines()
        );
        Objects.requireNonNull(report.languages(), "Analyzer report languages must not be null");
        for (AnalyzerReport.Language language : report.languages()) {
            Objects.requireNonNull(language, "Analyzer report language must not be null");
            requireNonBlank(language.name(), "Analyzer report language name");
            requireNonNegative(language.files(), language.lines());
        }
        Objects.requireNonNull(report.structure(), "Analyzer report structure must not be null");
        Objects.requireNonNull(report.structure().buildFiles(), "Analyzer report buildFiles must not be null");
        report.structure().buildFiles().forEach(file -> requireSafeRelativePath(file, "build file"));
        Objects.requireNonNull(report.quality(), "Analyzer report quality must not be null");
        requireScore(report.quality().complexityScore());
        requireScore(report.quality().maintainabilityScore());
        requireScore(report.quality().documentationScore());
        requireScore(report.quality().testScore());
        Objects.requireNonNull(report.risks(), "Analyzer report risks must not be null");
        for (AnalyzerReport.Risk risk : report.risks()) {
            Objects.requireNonNull(risk, "Analyzer report risk must not be null");
            requireNonBlank(risk.ruleId(), "Analyzer report risk ruleId");
            requireNonBlank(risk.type(), "Analyzer report risk type");
            Objects.requireNonNull(risk.level(), "Analyzer report risk level must not be null");
            requireSafeRelativePath(risk.file(), "risk file");
            requireNonNegative(risk.line());
            requireNonBlank(risk.message(), "Analyzer report risk message");
            Objects.requireNonNull(risk.evidence(), "Analyzer report risk evidence must not be null");
        }
        Objects.requireNonNull(report.dependencies(), "Analyzer report dependencies must not be null");
        Objects.requireNonNull(report.dependencies().nodes(), "Analyzer report dependency nodes must not be null");
        Objects.requireNonNull(report.dependencies().edges(), "Analyzer report dependency edges must not be null");
        report.dependencies().nodes().forEach(node -> Objects.requireNonNull(node, "Dependency node must not be null"));
        report.dependencies().edges().forEach(edge -> Objects.requireNonNull(edge, "Dependency edge must not be null"));
        Objects.requireNonNull(report.generatedAt(), "Analyzer report generatedAt must not be null");
    }

    private Map<String, Object> summary(AnalyzerReport.Summary summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalFiles", summary.totalFiles());
        result.put("sourceFiles", summary.sourceFiles());
        result.put("documentFiles", summary.documentFiles());
        result.put("configFiles", summary.configFiles());
        result.put("testFiles", summary.testFiles());
        result.put("totalLines", summary.totalLines());
        result.put("codeLines", summary.codeLines());
        result.put("commentLines", summary.commentLines());
        result.put("blankLines", summary.blankLines());
        return result;
    }

    private List<Map<String, Object>> languages(List<AnalyzerReport.Language> languages) {
        return languages.stream().map(language -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", language.name());
            result.put("files", language.files());
            result.put("lines", language.lines());
            return result;
        }).toList();
    }

    private Map<String, Object> structure(AnalyzerReport.Structure structure) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasReadme", structure.hasReadme());
        result.put("hasLicense", structure.hasLicense());
        result.put("hasContributing", structure.hasContributing());
        result.put("hasChangelog", structure.hasChangelog());
        result.put("hasCi", structure.hasCi());
        result.put("hasTests", structure.hasTests());
        result.put("hasDockerfile", structure.hasDockerfile());
        result.put("buildFiles", List.copyOf(structure.buildFiles()));
        return result;
    }

    private Map<String, Object> quality(AnalyzerReport.Quality quality) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complexityScore", quality.complexityScore());
        result.put("maintainabilityScore", quality.maintainabilityScore());
        result.put("documentationScore", quality.documentationScore());
        result.put("testScore", quality.testScore());
        return result;
    }

    private List<Map<String, Object>> risks(List<AnalyzerReport.Risk> risks) {
        return risks.stream().map(risk -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", risk.ruleId());
            result.put("type", risk.type());
            result.put("level", risk.level().name());
            result.put("file", risk.file());
            result.put("line", risk.line());
            result.put("message", risk.message());
            result.put("evidence", evidence(risk.evidence()));
            return result;
        }).toList();
    }

    private Map<String, Object> dependencies() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", List.of());
        result.put("edges", List.of());
        return result;
    }

    private Map<String, Object> evidence(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : EVIDENCE_FIELDS) {
            if (!source.containsKey(key)) {
                continue;
            }
            Object value = source.get(key);
            switch (key) {
                case "expectedFile" -> {
                    if (!(value instanceof String text)) {
                        throw invalidSnapshot("Analyzer report evidence expectedFile must be a string");
                    }
                    requireSafeRelativePath(text, "evidence expectedFile");
                    result.put(key, text);
                }
                case "functionName" -> {
                    if (!(value instanceof String text)) {
                        throw invalidSnapshot("Analyzer report evidence functionName must be a string");
                    }
                    requireNonBlank(text, "Analyzer report evidence functionName");
                    requireSafeString(text, "risk evidence.functionName");
                    result.put(key, text);
                }
                case "functionLines", "threshold" -> {
                    long number = requireNonNegativeInteger(value, "risk evidence." + key);
                    result.put(key, number);
                }
                default -> throw new IllegalStateException("Unexpected evidence field: " + key);
            }
        }
        return result;
    }

    private void validateRepository(Object value) {
        Map<?, ?> repository = requireObject(value, "repository");
        requireExactFields(repository, REPOSITORY_FIELDS, "repository");
        requireSafeNonBlankString(repository.get("name"), "repository.name");
    }

    private void validateSummary(Object value) {
        Map<?, ?> summary = requireObject(value, "summary");
        requireExactFields(summary, SUMMARY_FIELDS, "summary");
        SUMMARY_FIELDS.forEach(field -> requireNonNegativeInteger(summary.get(field), "summary." + field));
    }

    private void validateLanguages(Object value) {
        List<?> languages = requireList(value, "languages");
        for (Object element : languages) {
            Map<?, ?> language = requireObject(element, "languages[]");
            requireExactFields(language, LANGUAGE_FIELDS, "languages[]");
            requireSafeNonBlankString(language.get("name"), "languages[].name");
            requireNonNegativeInteger(language.get("files"), "languages[].files");
            requireNonNegativeInteger(language.get("lines"), "languages[].lines");
        }
    }

    private void validateStructure(Object value) {
        Map<?, ?> structure = requireObject(value, "structure");
        requireExactFields(structure, STRUCTURE_FIELDS, "structure");
        for (String field : STRUCTURE_FIELDS) {
            if (!field.equals("buildFiles") && !(structure.get(field) instanceof Boolean)) {
                throw invalidSnapshot("Analysis report structure." + field + " must be a boolean");
            }
        }
        List<?> buildFiles = requireList(structure.get("buildFiles"), "structure.buildFiles");
        for (Object file : buildFiles) {
            if (!(file instanceof String text)) {
                throw invalidSnapshot("Analysis report structure.buildFiles values must be strings");
            }
            requireSafeRelativePath(text, "build file");
        }
    }

    private void validateQuality(Object value) {
        Map<?, ?> quality = requireObject(value, "quality");
        requireExactFields(quality, QUALITY_FIELDS, "quality");
        for (String field : QUALITY_FIELDS) {
            long score = requireNonNegativeInteger(quality.get(field), "quality." + field);
            if (score > 100) {
                throw invalidSnapshot("Analysis report quality score must be between 0 and 100");
            }
        }
    }

    private void validateRisks(Object value) {
        List<?> risks = requireList(value, "risks");
        for (Object element : risks) {
            Map<?, ?> risk = requireObject(element, "risks[]");
            requireExactFields(risk, RISK_FIELDS, "risks[]");
            requireSafeNonBlankString(risk.get("ruleId"), "risks[].ruleId");
            requireSafeNonBlankString(risk.get("type"), "risks[].type");
            String level = requireSafeNonBlankString(risk.get("level"), "risks[].level");
            try {
                RiskLevel.valueOf(level);
            } catch (IllegalArgumentException exception) {
                throw invalidSnapshot("Analysis report risks[].level is unsupported", exception);
            }
            if (!(risk.get("file") instanceof String file)) {
                throw invalidSnapshot("Analysis report risks[].file must be a string");
            }
            requireSafeRelativePath(file, "risk file");
            requireNonNegativeInteger(risk.get("line"), "risks[].line");
            requireSafeNonBlankString(risk.get("message"), "risks[].message");
            validateEvidence(risk.get("evidence"));
        }
    }

    private void validateEvidence(Object value) {
        Map<?, ?> evidence = requireObject(value, "risks[].evidence");
        requireAllowedFields(evidence, EVIDENCE_FIELDS, "risks[].evidence");
        if (evidence.containsKey("expectedFile")) {
            Object expectedFile = evidence.get("expectedFile");
            if (!(expectedFile instanceof String text)) {
                throw invalidSnapshot("Analysis report evidence expectedFile must be a string");
            }
            requireSafeRelativePath(text, "evidence expectedFile");
        }
        if (evidence.containsKey("functionName")) {
            requireSafeNonBlankString(evidence.get("functionName"), "risks[].evidence.functionName");
        }
        if (evidence.containsKey("functionLines")) {
            requireNonNegativeInteger(evidence.get("functionLines"), "risks[].evidence.functionLines");
        }
        if (evidence.containsKey("threshold")) {
            requireNonNegativeInteger(evidence.get("threshold"), "risks[].evidence.threshold");
        }
    }

    private void validateDependencies(Object value) {
        Map<?, ?> dependencies = requireObject(value, "dependencies");
        requireExactFields(dependencies, DEPENDENCY_FIELDS, "dependencies");
        if (!requireList(dependencies.get("nodes"), "dependencies.nodes").isEmpty()
                || !requireList(dependencies.get("edges"), "dependencies.edges").isEmpty()) {
            throw invalidSnapshot("Analysis report dependency details are not persistable in protocol 1.0");
        }
    }

    private String writeSnapshot(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw invalidSnapshot("Analysis report snapshot could not be serialized", exception);
        }
    }

    private Map<?, ?> requireObject(Object value, String location) {
        if (!(value instanceof Map<?, ?> object)) {
            throw invalidSnapshot("Analysis report " + location + " must be an object");
        }
        return object;
    }

    private List<?> requireList(Object value, String location) {
        if (!(value instanceof List<?> list)) {
            throw invalidSnapshot("Analysis report " + location + " must be an array");
        }
        return list;
    }

    private void requireExactFields(Map<?, ?> object, Set<String> expected, String location) {
        Set<String> actual = requireStringKeys(object, location);
        if (!actual.equals(expected)) {
            throw invalidSnapshot("Analysis report " + location + " fields do not match the protocol whitelist");
        }
    }

    private void requireAllowedFields(Map<?, ?> object, Set<String> allowed, String location) {
        Set<String> actual = requireStringKeys(object, location);
        if (!allowed.containsAll(actual)) {
            throw invalidSnapshot("Analysis report " + location + " contains a field outside the whitelist");
        }
    }

    private Set<String> requireStringKeys(Map<?, ?> object, String location) {
        Set<String> keys = new HashSet<>();
        for (Object key : object.keySet()) {
            if (!(key instanceof String text)) {
                throw invalidSnapshot("Analysis report " + location + " field names must be strings");
            }
            keys.add(text);
        }
        return keys;
    }

    private String requireSafeNonBlankString(Object value, String location) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalidSnapshot("Analysis report " + location + " must be a non-blank string");
        }
        requireSafeString(text, location);
        return text;
    }

    private long requireNonNegativeInteger(Object value, String location) {
        long number;
        try {
            if (value instanceof Byte byteValue) {
                number = byteValue.longValue();
            } else if (value instanceof Short shortValue) {
                number = shortValue.longValue();
            } else if (value instanceof Integer integerValue) {
                number = integerValue.longValue();
            } else if (value instanceof Long longValue) {
                number = longValue;
            } else if (value instanceof BigInteger bigInteger) {
                number = bigInteger.longValueExact();
            } else {
                throw invalidSnapshot("Analysis report " + location + " must be an integer");
            }
        } catch (ArithmeticException exception) {
            throw invalidSnapshot("Analysis report " + location + " is outside the supported integer range", exception);
        }
        if (number < 0) {
            throw invalidSnapshot("Analysis report " + location + " must not be negative");
        }
        return number;
    }

    private void requireSafeString(String value, String location) {
        if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw invalidSnapshot("Analysis report " + location + " contains credential-like data");
        }
        if (looksLikeAbsolutePath(value)) {
            throw invalidSnapshot("Analysis report " + location + " contains an absolute path");
        }
        try {
            URI uri = new URI(value);
            if (uri.isAbsolute() && uri.getRawQuery() != null) {
                throw invalidSnapshot("Analysis report " + location + " contains a URL query");
            }
        } catch (URISyntaxException ignored) {
            // Ordinary report text is not required to be a URI.
        }
    }

    private boolean looksLikeAbsolutePath(String value) {
        if (value.startsWith("/") || value.startsWith("\\\\") || value.startsWith("//")) {
            return true;
        }
        if (value.length() >= 3 && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':' && (value.charAt(2) == '\\' || value.charAt(2) == '/')) {
            return true;
        }
        try {
            return Path.of(value).isAbsolute();
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    private void requireSafeRelativePath(String value, String fieldName) {
        if (value == null) {
            throw invalidSnapshot("Analyzer report " + fieldName + " must not be null");
        }
        if (value.isEmpty()) {
            return;
        }
        if (looksLikeAbsolutePath(value)) {
            throw invalidSnapshot("Analyzer report " + fieldName + " must be relative");
        }
        try {
            Path path = Path.of(value).normalize();
            if (path.startsWith("..")) {
                throw invalidSnapshot("Analyzer report " + fieldName + " must not escape the repository");
            }
        } catch (InvalidPathException exception) {
            throw invalidSnapshot("Analyzer report " + fieldName + " is invalid", exception);
        }
    }

    private void requireScore(int score) {
        if (score < 0 || score > 100) {
            throw invalidSnapshot("Analyzer report quality score must be between 0 and 100");
        }
    }

    private void requireNonNegative(long... values) {
        for (long value : values) {
            if (value < 0) {
                throw invalidSnapshot("Analyzer report counts must not be negative");
            }
        }
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw invalidSnapshot(fieldName + " must not be blank");
        }
        return value;
    }

    private void requireEquals(Object actual, String expected, String fieldName) {
        if (!expected.equals(actual)) {
            throw invalidSnapshot("Analysis report snapshot " + fieldName + " is inconsistent");
        }
    }

    private PersistenceOperationException invalidSnapshot(String message) {
        return new PersistenceOperationException(PersistenceFailure.INVALID_DATA, message);
    }

    private PersistenceOperationException invalidSnapshot(String message, Throwable cause) {
        return new PersistenceOperationException(PersistenceFailure.INVALID_DATA, message, cause);
    }
}
