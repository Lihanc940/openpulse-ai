package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Evidence;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.EvidenceKind;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Finding;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.FindingType;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Limitation;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.LimitationKind;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Scope;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Status;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

@Component
public class AnalyzerReportV2SemanticValidator {

    public static final String FIXED_MISSING_LICENSE_ID =
            "sha256:b9dcda3c6f619d65163780c30df7792cc3baf402ba42e2adeab4d8031ac161a8";

    private static final char UNIT_SEPARATOR = '\u001f';
    private static final Comparator<String> CODE_POINT_ORDER = AnalyzerReportV2SemanticValidator::compareCodePoints;
    private static final DateTimeFormatter STRICT_DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern ABSOLUTE_PATH_TEXT = Pattern.compile(
            "(?i)(?:[a-z]:[\\\\/]|\\\\\\\\[^\\\\\\s]+|(?<![\\p{L}\\p{N}._-])/(?!/)[^\\s]+)"
    );
    private static final Pattern CREDENTIAL_TEXT = Pattern.compile(
            "(?i)(?:authorization\\s*:|bearer\\s+[a-z0-9._~+/=-]+|"
                    + "(?:api[_ -]?key|token|password|passwd|secret|private[_ -]?key)\\s*[:=]\\s*\\S+|"
                    + "\\bgh[pousr]_[a-z0-9]{20,}\\b|\\bAKIA[0-9A-Z]{16}\\b|"
                    + "\\bsk-[a-z0-9_-]{16,}\\b|-----BEGIN [A-Z ]*PRIVATE KEY-----)"
    );
    private static final Pattern PROCESS_OUTPUT_TEXT = Pattern.compile("(?i)\\b(?:stdout|stderr)\\b");
    private static final Pattern STACK_TEXT = Pattern.compile(
            "(?i)(?:\\b(?:exception|stack\\s*trace)\\b\\s*[:\\r\\n]|"
                    + "\\bat\\s+[a-z_$][\\w$]*(?:\\.[\\w$]+)+\\([^\\r\\n)]*\\))"
    );
    private static final Set<String> REVISION_PLACEHOLDERS = Set.of(
            "unknown", "n/a", "na", "none", "null", "unset", "tbd", "pending", "-"
    );
    private static final Map<CatalogKey, Map<String, RuleDefinition>> RULE_CATALOGS = Map.of(
            new CatalogKey("openpulse-default", "0.2.0"), Map.of(
                    "LONG_FUNCTION", new RuleDefinition(
                            FindingType.SYNTAX_TREE, Scope.FILE, EvidenceKind.SYNTAX_METRIC
                    ),
                    "MISSING_CI", new RuleDefinition(
                            FindingType.PROJECT_STRUCTURE, Scope.REPOSITORY,
                            EvidenceKind.EXPECTED_PATHS_ABSENT
                    ),
                    "MISSING_LICENSE", new RuleDefinition(
                            FindingType.PROJECT_STRUCTURE, Scope.REPOSITORY,
                            EvidenceKind.EXPECTED_PATHS_ABSENT
                    ),
                    "MISSING_README", new RuleDefinition(
                            FindingType.PROJECT_STRUCTURE, Scope.REPOSITORY,
                            EvidenceKind.EXPECTED_PATHS_ABSENT
                    )
            )
    );

    private final ObjectMapper canonicalMapper;

    public AnalyzerReportV2SemanticValidator(ObjectMapper objectMapper) {
        canonicalMapper = objectMapper.rebuild()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    public void validate(AnalyzerReportV2 report) {
        require(report != null, "report is missing");
        require("2.0".equals(report.protocolVersion()), "protocol version is not 2.0");
        require(report.analyzer() != null, "analyzer identity is missing");
        require(report.ruleSet() != null, "rule set identity is missing");
        require(report.repository() != null, "repository identity is missing");
        require(".".equals(report.repository().root()), "repository root is not the scan root");
        validateGeneratedAt(report.generatedAt());
        validateRevision(report.repository().revision());
        validateStatus(report);
        validateSummary(report);
        validateLanguages(report.languages());
        if (report.structure() != null) {
            validateSortedUnique(report.structure().buildFiles(), Function.identity(), "build files");
            report.structure().buildFiles().forEach(path -> validateRelativePath(path, "build file"));
        }
        validateFindings(report);
        validateLimitations(report.limitations());
        validateSensitiveText(report);
    }

    public String recomputeFindingId(Finding finding) {
        require(finding != null, "finding is missing");
        require(finding.scope() != null, "finding scope is missing");
        require(finding.evidence() != null, "finding evidence is missing");
        String pathPart;
        String locationPart;
        if (finding.scope() == Scope.REPOSITORY) {
            pathPart = "-";
            locationPart = "-";
        } else {
            require(finding.location() != null, "file finding location is missing");
            pathPart = finding.location().relativePath();
            locationPart = finding.location().startLine() + ":" + finding.location().startColumn()
                    + ":" + finding.location().endLine() + ":" + finding.location().endColumn();
        }
        String input = finding.ruleId() + UNIT_SEPARATOR + finding.scope().name()
                + UNIT_SEPARATOR + pathPart + UNIT_SEPARATOR + locationPart
                + UNIT_SEPARATOR + canonicalEvidence(finding.evidence());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void validateStatus(AnalyzerReportV2 report) {
        require(report.status() != null, "status is missing");
        require(report.reviewability() != null, "reviewability is missing");
        require(report.findings() != null, "findings are missing");
        require(report.limitations() != null, "limitations are missing");
        switch (report.status()) {
            case SUCCESS -> {
                require(report.reviewability() == AnalyzerReportV2.Reviewability.COMPLETE,
                        "SUCCESS reviewability is inconsistent");
                requireUsableFields(report);
                require(report.limitations().isEmpty(), "SUCCESS contains limitations");
            }
            case PARTIAL_SUCCESS -> {
                require(report.reviewability() == AnalyzerReportV2.Reviewability.PARTIAL,
                        "PARTIAL_SUCCESS reviewability is inconsistent");
                requireUsableFields(report);
                require(!report.limitations().isEmpty(), "PARTIAL_SUCCESS has no limitation");
                require(report.limitations().stream().noneMatch(
                                limitation -> limitation.kind() == LimitationKind.SCAN_FAILED),
                        "PARTIAL_SUCCESS contains a scan failure");
            }
            case FAILED -> {
                require(report.reviewability() == AnalyzerReportV2.Reviewability.NOT_USABLE,
                        "FAILED reviewability is inconsistent");
                require(report.summary() == null && report.languages() == null && report.structure() == null,
                        "FAILED contains usable measurements");
                require(report.findings().isEmpty(), "FAILED contains findings");
                require(report.limitations().stream().anyMatch(
                                limitation -> limitation.kind() == LimitationKind.SCAN_FAILED),
                        "FAILED has no scan failure limitation");
            }
        }
    }

    private void requireUsableFields(AnalyzerReportV2 report) {
        require(report.summary() != null, "summary is missing");
        require(report.languages() != null, "languages are missing");
        require(report.structure() != null, "structure is missing");
    }

    private void validateSummary(AnalyzerReportV2 report) {
        if (report.summary() == null) {
            return;
        }
        long classifiedLines = report.summary().codeLines()
                + report.summary().commentLines()
                + report.summary().blankLines();
        require(classifiedLines <= report.summary().totalLines(), "line counts exceed total lines");
        if (classifiedLines < report.summary().totalLines()) {
            boolean explainsUnreadContent = report.limitations().stream().anyMatch(limitation ->
                    limitation.kind() == LimitationKind.FILE_SKIPPED
                            || limitation.kind() == LimitationKind.RESOURCE_LIMIT_REACHED
            );
            require(explainsUnreadContent, "unclassified lines have no matching limitation");
        }
    }

    private void validateLanguages(List<AnalyzerReportV2.Language> languages) {
        if (languages != null) {
            validateSortedUnique(languages, AnalyzerReportV2.Language::name, "languages");
        }
    }

    private void validateFindings(AnalyzerReportV2 report) {
        validateSortedUnique(report.findings(), Finding::findingId, "findings");
        Map<String, RuleDefinition> rules = RULE_CATALOGS.get(new CatalogKey(
                report.ruleSet().id(), report.ruleSet().version()
        ));
        require(rules != null, "rule set catalog is unsupported");
        for (Finding finding : report.findings()) {
            require(finding != null, "finding is missing");
            validateLocation(finding);
            validateEvidence(finding.evidence());
            RuleDefinition rule = rules.get(finding.ruleId());
            require(rule != null, "finding rule is absent from the declared catalog");
            require(rule.type() == finding.type(), "finding type does not match its rule");
            require(rule.scope() == finding.scope(), "finding scope does not match its rule");
            require(rule.evidenceKind() == finding.evidence().kind(),
                    "finding evidence does not match its rule");
            require(recomputeFindingId(finding).equals(finding.findingId()),
                    "findingId does not match report content");
        }
    }

    private void validateLocation(Finding finding) {
        if (finding.scope() == Scope.REPOSITORY) {
            require(finding.location() == null, "repository finding has a file location");
            return;
        }
        require(finding.scope() == Scope.FILE, "finding scope is missing");
        require(finding.location() != null, "file finding location is missing");
        validateRelativePath(finding.location().relativePath(), "finding location");
        boolean ordered = finding.location().endLine() > finding.location().startLine()
                || finding.location().endLine() == finding.location().startLine()
                && finding.location().endColumn() >= finding.location().startColumn();
        require(ordered, "finding location ends before it starts");
    }

    private void validateEvidence(Evidence evidence) {
        require(evidence != null && evidence.kind() != null, "finding evidence kind is missing");
        if (evidence instanceof AnalyzerReportV2.ExpectedPathsAbsentEvidence expected) {
            validateSortedUnique(expected.expectedPaths(), Function.identity(), "expected paths");
            expected.expectedPaths().forEach(path -> validateRelativePath(path, "expected path"));
        }
    }

    private void validateLimitations(List<Limitation> limitations) {
        validateSortedUnique(limitations, this::limitationKey, "limitations");
        for (Limitation limitation : limitations) {
            require(limitation != null && limitation.kind() != null, "limitation kind is missing");
            if (limitation instanceof AnalyzerReportV2.FileSkippedLimitation fileSkipped) {
                validateRelativePath(fileSkipped.relativePath(), "skipped file");
            }
        }
    }

    private String limitationKey(Limitation limitation) {
        require(limitation != null && limitation.kind() != null, "limitation kind is missing");
        return switch (limitation) {
            case AnalyzerReportV2.FileSkippedLimitation value -> value.kind() + String.valueOf(UNIT_SEPARATOR)
                    + value.relativePath() + UNIT_SEPARATOR + value.reason();
            case AnalyzerReportV2.ResourceLimitReachedLimitation value -> value.kind()
                    + String.valueOf(UNIT_SEPARATOR) + value.limitType();
            case AnalyzerReportV2.ScanFailedLimitation value -> value.kind()
                    + String.valueOf(UNIT_SEPARATOR) + value.reason();
            case AnalyzerReportV2.UnsupportedCapabilityLimitation value -> value.kind()
                    + String.valueOf(UNIT_SEPARATOR) + value.capabilityType()
                    + UNIT_SEPARATOR + value.capability();
        };
    }

    private String canonicalEvidence(Evidence evidence) {
        Map<String, Object> fields = new TreeMap<>(CODE_POINT_ORDER);
        switch (evidence) {
            case AnalyzerReportV2.ExpectedPathsAbsentEvidence value -> {
                fields.put("expectedPaths", value.expectedPaths());
                fields.put("kind", value.kind().name());
            }
            case AnalyzerReportV2.FileMetricEvidence value -> {
                fields.put("kind", value.kind().name());
                fields.put("metric", value.metric().name());
                fields.put("observed", value.observed());
                fields.put("threshold", value.threshold());
            }
            case AnalyzerReportV2.TextMatchEvidence value -> {
                fields.put("kind", value.kind().name());
                fields.put("matchKind", value.matchKind().name());
                fields.put("occurrences", value.occurrences());
                fields.put("threshold", value.threshold());
            }
            case AnalyzerReportV2.SyntaxMetricEvidence value -> {
                fields.put("kind", value.kind().name());
                fields.put("metric", value.metric().name());
                fields.put("observed", value.observed());
                fields.put("symbolKind", value.symbolKind().name());
                fields.put("symbolName", value.symbolName());
                fields.put("threshold", value.threshold());
            }
        }
        try {
            return canonicalMapper.writeValueAsString(fields);
        } catch (JacksonException exception) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.SEMANTIC_VIOLATION,
                    "Analyzer report evidence cannot be canonicalized",
                    exception
            );
        }
    }

    private <T> void validateSortedUnique(List<T> values, Function<T, String> key, String description) {
        require(values != null, description + " are missing");
        String previous = null;
        Set<String> seen = new HashSet<>();
        for (T value : values) {
            require(value != null, description + " contain a null item");
            String current = key.apply(value);
            require(current != null, description + " contain a missing stable key");
            require(seen.add(current), description + " contain a duplicate stable key");
            require(previous == null || CODE_POINT_ORDER.compare(previous, current) < 0,
                    description + " are not in stable order");
            previous = current;
        }
    }

    private void validateRelativePath(String path, String description) {
        require(path != null && !path.isBlank() && path.length() <= 1024,
                description + " is not a safe relative path");
        require(!path.startsWith("/") && !path.contains("\\") && !path.contains(":"),
                description + " is not a safe relative path");
        require(path.codePoints().noneMatch(codePoint -> codePoint <= 0x1f || codePoint == 0x7f),
                description + " contains a control character");
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            require(!segment.isEmpty() && !segment.equals(".") && !segment.equals(".."),
                    description + " escapes or ambiguously names the scan root");
        }
    }

    private void validateGeneratedAt(OffsetDateTime generatedAt) {
        require(generatedAt != null, "generatedAt is missing");
        try {
            OffsetDateTime.parse(generatedAt.toString(), STRICT_DATE_TIME);
        } catch (DateTimeException exception) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.SEMANTIC_VIOLATION,
                    "Analyzer report generatedAt is not a real RFC 3339 timestamp",
                    exception
            );
        }
    }

    private void validateRevision(String revision) {
        if (revision != null) {
            require(!REVISION_PLACEHOLDERS.contains(revision.toLowerCase(Locale.ROOT)),
                    "repository revision is a placeholder");
        }
    }

    private void validateSensitiveText(AnalyzerReportV2 report) {
        List<String> texts = new ArrayList<>();
        add(texts, report.taskId());
        add(texts, report.analyzer().name());
        add(texts, report.analyzer().version());
        add(texts, report.ruleSet().id());
        add(texts, report.ruleSet().version());
        add(texts, report.repository().name());
        add(texts, report.repository().revision());
        if (report.languages() != null) {
            report.languages().forEach(language -> add(texts, language.name()));
        }
        if (report.structure() != null) {
            report.structure().buildFiles().forEach(value -> add(texts, value));
        }
        report.findings().forEach(finding -> {
            add(texts, finding.ruleId());
            add(texts, finding.message());
            add(texts, finding.remediation());
            if (finding.location() != null) {
                add(texts, finding.location().relativePath());
            }
            switch (finding.evidence()) {
                case AnalyzerReportV2.ExpectedPathsAbsentEvidence value ->
                        value.expectedPaths().forEach(path -> add(texts, path));
                case AnalyzerReportV2.SyntaxMetricEvidence value -> add(texts, value.symbolName());
                default -> {
                    // Other evidence variants contain only enums and integers.
                }
            }
        });
        report.limitations().forEach(limitation -> {
            add(texts, limitation.message());
            if (limitation instanceof AnalyzerReportV2.FileSkippedLimitation value) {
                add(texts, value.relativePath());
            } else if (limitation instanceof AnalyzerReportV2.UnsupportedCapabilityLimitation value) {
                add(texts, value.capability());
            }
        });
        for (String text : texts) {
            require(!ABSOLUTE_PATH_TEXT.matcher(text).find(), "text contains an absolute path");
            require(!CREDENTIAL_TEXT.matcher(text).find(), "text contains credential-like content");
            require(!PROCESS_OUTPUT_TEXT.matcher(text).find(), "text contains process output content");
            require(!STACK_TEXT.matcher(text).find(), "text contains exception or stack content");
        }
    }

    private void add(List<String> values, String value) {
        if (value != null) {
            values.add(value);
        }
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.SEMANTIC_VIOLATION,
                    "Analyzer report violates protocol 2.0 semantics: " + reason
            );
        }
    }

    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            int compared = Integer.compare(leftPoint, rightPoint);
            if (compared != 0) {
                return compared;
            }
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length(), right.length());
    }

    private record CatalogKey(String id, String version) {
    }

    private record RuleDefinition(FindingType type, Scope scope, EvidenceKind evidenceKind) {
    }
}
