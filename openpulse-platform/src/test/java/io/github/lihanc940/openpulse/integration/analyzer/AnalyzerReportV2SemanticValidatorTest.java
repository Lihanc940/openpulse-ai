package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Evidence;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.EvidenceKind;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.FileMetric;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.FileMetricEvidence;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Finding;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.FindingType;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Scope;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2.Severity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class AnalyzerReportV2SemanticValidatorTest {

    private final AnalyzerReportV2SemanticValidator validator =
            new AnalyzerReportV2SemanticValidator(new ObjectMapper());

    @Test
    void acceptsCppRuleSetVersionOneCatalogAndRecomputesEveryFindingId() {
        AnalyzerReportV2 report = report("1.0.0", List.of(
                catalogFinding(
                        "MISSING_CI",
                        Severity.LOW,
                        List.of(
                                ".circleci",
                                ".github/workflows",
                                ".gitlab-ci.yml",
                                ".travis.yml",
                                "Jenkinsfile",
                                "azure-pipelines.yml"
                        )
                ),
                catalogFinding(
                        "MISSING_LICENSE",
                        Severity.MEDIUM,
                        List.of("LICENSE", "LICENSE.md", "LICENSE.txt")
                ),
                catalogFinding(
                        "MISSING_README",
                        Severity.MEDIUM,
                        List.of("README", "README.md", "README.rst", "README.txt")
                )
        ));

        assertThatCode(() -> validator.validate(report)).doesNotThrowAnyException();
        assertThat(report.findings())
                .extracting(Finding::ruleId)
                .containsExactlyInAnyOrder("MISSING_CI", "MISSING_LICENSE", "MISSING_README");
        assertThat(report.findings())
                .extracting(Finding::ruleId, Finding::findingId)
                .containsExactlyInAnyOrder(
                        tuple(
                                "MISSING_CI",
                                "sha256:da40e56d7e706baed9f961c147037ff412f3c0d942f166631df2cc315ac1d0e3"
                        ),
                        tuple(
                                "MISSING_LICENSE",
                                "sha256:8313d87acf736e6d1f6112261e703bcef60fd37a2609bbd38ba83531e4d854af"
                        ),
                        tuple(
                                "MISSING_README",
                                "sha256:f8566b4e81b6029ff8ce1c3e7c562fe13f285991f522905b540a07899b6c2f9d"
                        )
                );
        assertThat(report.findings()).allSatisfy(finding -> {
            assertThat(finding.type()).isEqualTo(FindingType.PROJECT_STRUCTURE);
            assertThat(finding.scope()).isEqualTo(Scope.REPOSITORY);
            assertThat(finding.evidence().kind()).isEqualTo(EvidenceKind.EXPECTED_PATHS_ABSENT);
            assertThat(validator.recomputeFindingId(finding)).isEqualTo(finding.findingId());
        });
    }

    @Test
    void rejectsUnknownRuleSetVersion() {
        AnalyzerReportV2 report = report("1.0.1", List.of(
                catalogFinding(
                        "MISSING_README",
                        Severity.MEDIUM,
                        List.of("README", "README.md", "README.rst", "README.txt")
                )
        ));

        assertSemanticViolation(report, "rule set catalog is unsupported");
    }

    @Test
    void rejectsRuleAbsentFromVersionOneCatalog() {
        AnalyzerReportV2 report = report("1.0.0", List.of(
                catalogFinding("LONG_FUNCTION", Severity.MEDIUM, List.of("src"))
        ));

        assertSemanticViolation(report, "finding rule is absent from the declared catalog");
    }

    @Test
    void rejectsEvidenceNotApprovedByVersionOneRule() {
        AnalyzerReportV2 report = report("1.0.0", List.of(finding(
                "MISSING_README",
                Severity.MEDIUM,
                new FileMetricEvidence(EvidenceKind.FILE_METRIC, FileMetric.LINE_COUNT, 0, 1)
        )));

        assertSemanticViolation(report, "finding evidence does not match its rule");
    }

    private AnalyzerReportV2 report(String ruleSetVersion, List<Finding> findings) {
        List<Finding> sortedFindings = findings.stream()
                .sorted(Comparator.comparing(Finding::findingId))
                .toList();
        return new AnalyzerReportV2(
                "2.0",
                "task-catalog-alignment",
                new AnalyzerReportV2.AnalyzerIdentity("openpulse-analyzer", "0.2.0"),
                new AnalyzerReportV2.RuleSetIdentity("openpulse-default", ruleSetVersion),
                AnalyzerReportV2.Status.SUCCESS,
                AnalyzerReportV2.Reviewability.COMPLETE,
                new AnalyzerReportV2.Repository("fixture", ".", null),
                new AnalyzerReportV2.Summary(0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                new AnalyzerReportV2.Structure(false, false, false, false,
                        false, false, false, List.of()),
                sortedFindings,
                List.of(),
                OffsetDateTime.parse("2026-09-29T00:00:00Z")
        );
    }

    private Finding catalogFinding(String ruleId, Severity severity, List<String> expectedPaths) {
        return finding(
                ruleId,
                severity,
                new AnalyzerReportV2.ExpectedPathsAbsentEvidence(
                        EvidenceKind.EXPECTED_PATHS_ABSENT,
                        expectedPaths
                )
        );
    }

    private Finding finding(String ruleId, Severity severity, Evidence evidence) {
        Finding withoutId = new Finding(
                "sha256:" + "0".repeat(64),
                ruleId,
                FindingType.PROJECT_STRUCTURE,
                severity,
                Scope.REPOSITORY,
                null,
                "仓库结构规则测试。",
                evidence,
                "补充对应的仓库结构文件。"
        );
        return new Finding(
                validator.recomputeFindingId(withoutId),
                withoutId.ruleId(),
                withoutId.type(),
                withoutId.severity(),
                withoutId.scope(),
                withoutId.location(),
                withoutId.message(),
                withoutId.evidence(),
                withoutId.remediation()
        );
    }

    private void assertSemanticViolation(AnalyzerReportV2 report, String reason) {
        assertThatThrownBy(() -> validator.validate(report))
                .isInstanceOfSatisfying(AnalyzerReportReadException.class, exception -> {
                    assertThat(exception.failure())
                            .isEqualTo(AnalyzerReportReadFailure.SEMANTIC_VIOLATION);
                    assertThat(exception.getMessage()).contains(reason);
                });
    }
}
