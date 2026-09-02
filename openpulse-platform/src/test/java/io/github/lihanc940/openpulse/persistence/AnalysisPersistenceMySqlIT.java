package io.github.lihanc940.openpulse.persistence;

import io.github.lihanc940.openpulse.analysis.application.AnalysisTaskRepository;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisFailureCode;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTask;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskStatus;
import io.github.lihanc940.openpulse.analysis.infrastructure.persistence.AnalysisTaskJpaEntity;
import io.github.lihanc940.openpulse.analysis.infrastructure.persistence.SpringDataAnalysisTaskJpaRepository;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerStatus;
import io.github.lihanc940.openpulse.integration.analyzer.model.RiskLevel;
import io.github.lihanc940.openpulse.project.application.ProjectRepository;
import io.github.lihanc940.openpulse.project.domain.GithubRepositoryMetadata;
import io.github.lihanc940.openpulse.project.domain.Project;
import io.github.lihanc940.openpulse.project.domain.ProjectRepositoryKey;
import io.github.lihanc940.openpulse.report.application.AnalysisReportRepository;
import io.github.lihanc940.openpulse.report.application.AnalysisReportSnapshotFactory;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportRecord;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportStatus;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

@SpringBootTest
@Testcontainers
class AnalysisPersistenceMySqlIT {

    private static final String MYSQL_IMAGE = "mysql:8.0.36";
    private static final String TEMPORARY_PATH = "C:\\private\\openpulse-github-123\\repo";
    private static final String TEST_TOKEN = "test-token-that-must-not-persist";
    private static final Instant BASE_TIME = Instant.parse("2026-08-20T00:00:00Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("openpulse")
            .withUsername("openpulse")
            .withPassword("openpulse-test-password");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AnalysisTaskRepository analysisTaskRepository;

    @Autowired
    private AnalysisReportRepository analysisReportRepository;

    @Autowired
    private AnalysisReportSnapshotFactory snapshotFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private SpringDataAnalysisTaskJpaRepository springDataTaskRepository;

    @BeforeEach
    void clearPersistenceTables() {
        jdbcTemplate.update("DELETE FROM analysis_reports");
        jdbcTemplate.update("DELETE FROM analysis_tasks");
        jdbcTemplate.update("DELETE FROM projects");
    }

    @Test
    void flywayCreatesMySqlSchemaBeforeHibernateValidation() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(tableProperty("projects", "ENGINE")).isEqualToIgnoringCase("InnoDB");
        assertThat(tableProperty("analysis_reports", "TABLE_COLLATION"))
                .startsWithIgnoringCase("utf8mb4_unicode_ci");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_reports' "
                        + "AND COLUMN_NAME = 'report_json'",
                String.class
        )).isEqualToIgnoringCase("json");
    }

    @Test
    void projectRepositoryUpsertsByNormalizedRepositoryKey() {
        Project first = projectRepository.save(Project.fromMetadata(metadata(10, "first"), clockAt(0)));
        Long firstDatabaseId = projectDatabaseId(first.repositoryKey());

        Project refreshed = first.refresh(metadata(25, "updated"), clockAt(60));
        Project saved = projectRepository.save(refreshed);
        Project reloaded = projectRepository.findByRepositoryKey(ProjectRepositoryKey.of("OPENPULSE", "DEMO"))
                .orElseThrow();

        assertThat(saved.repositoryKey().value()).isEqualTo("openpulse/demo");
        assertThat(reloaded.owner()).isEqualTo("OpenPulse");
        assertThat(reloaded.name()).isEqualTo("Demo");
        assertThat(reloaded.fullName()).isEqualTo("OpenPulse/Demo");
        assertThat(reloaded.canonicalUrl()).isEqualTo("https://github.com/OpenPulse/Demo");
        assertThat(reloaded.stars()).isEqualTo(25);
        assertThat(reloaded.description()).isEqualTo("updated");
        assertThat(reloaded.defaultBranch()).isEqualTo("main");
        assertThat(reloaded.primaryLanguage()).isEqualTo("Java");
        assertThat(reloaded.forks()).isEqualTo(3);
        assertThat(reloaded.archived()).isFalse();
        assertThat(reloaded.createdAt()).isEqualTo(BASE_TIME);
        assertThat(reloaded.updatedAt()).isEqualTo(BASE_TIME.plusSeconds(60));
        assertThat(projectDatabaseId(reloaded.repositoryKey())).isEqualTo(firstDatabaseId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM projects", Integer.class)).isEqualTo(1);
    }

    @Test
    void taskRepositoryPersistsLifecycleFailureAndOptimisticVersion() {
        Project project = saveProject();
        AnalysisTask pending = analysisTaskRepository.save(AnalysisTask.create(
                AnalysisTaskId.create(),
                project.repositoryKey(),
                clockAt(0)
        ));
        AnalysisTask stalePending = pending;
        AnalysisTask running = analysisTaskRepository.save(pending.start(clockAt(10)));

        assertThat(running.version()).isGreaterThan(stalePending.version());
        assertPersistenceFailure(
                () -> analysisTaskRepository.save(stalePending.start(clockAt(11))),
                PersistenceFailure.CONCURRENT_MODIFICATION
        );

        AnalysisTask successful = analysisTaskRepository.save(running.succeed(clockAt(15)));
        AnalysisTask successfulReloaded = analysisTaskRepository.findByTaskId(successful.taskId()).orElseThrow();
        assertThat(successfulReloaded.status()).isEqualTo(AnalysisTaskStatus.SUCCESS);
        assertThat(successfulReloaded.durationMs()).isEqualTo(5_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM analysis_tasks WHERE task_id = ?",
                String.class,
                successful.taskId().toString()
        )).isEqualTo("SUCCESS");

        AnalysisTask failedPending = analysisTaskRepository.save(AnalysisTask.create(
                AnalysisTaskId.create(),
                project.repositoryKey(),
                clockAt(20)
        ));
        AnalysisTask failedRunning = analysisTaskRepository.save(failedPending.start(clockAt(21)));
        AnalysisTask failed = analysisTaskRepository.save(failedRunning.fail(
                AnalysisFailureCode.ANALYZER_FAILED,
                "Analyzer exited with a safe failure code.",
                clockAt(22)
        ));

        AnalysisTask failedReloaded = analysisTaskRepository.findByTaskId(failed.taskId()).orElseThrow();
        assertThat(failedReloaded.status()).isEqualTo(AnalysisTaskStatus.FAILED);
        assertThat(failedReloaded.failureCode()).isEqualTo(AnalysisFailureCode.ANALYZER_FAILED);
        assertThat(failedReloaded.failureMessage()).isEqualTo("Analyzer exited with a safe failure code.");
    }

    @Test
    void taskRepositoryMapsRealConcurrentFlushConflict() {
        Project project = saveProject();
        AnalysisTask pending = analysisTaskRepository.save(AnalysisTask.create(
                AnalysisTaskId.create(),
                project.repositoryKey(),
                clockAt(0)
        ));
        CyclicBarrier flushBarrier = new CyclicBarrier(2);
        Answer<?> spyDefaultAnswer = mockingDetails(springDataTaskRepository)
                .getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            awaitBarrier(flushBarrier);
            return spyDefaultAnswer.answer(invocation);
        }).when(springDataTaskRepository).saveAndFlush(any(AnalysisTaskJpaEntity.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AnalysisTask> first = executor.submit(
                    () -> analysisTaskRepository.save(pending.start(clockAt(10)))
            );
            Future<AnalysisTask> second = executor.submit(
                    () -> analysisTaskRepository.save(pending.start(clockAt(11)))
            );
            List<Object> outcomes = List.of(awaitOutcome(first), awaitOutcome(second));

            assertThat(outcomes).filteredOn(AnalysisTask.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(PersistenceOperationException.class::isInstance)
                    .singleElement()
                    .extracting(outcome -> ((PersistenceOperationException) outcome).failure())
                    .isEqualTo(PersistenceFailure.CONCURRENT_MODIFICATION);
            AnalysisTask reloaded = analysisTaskRepository.findByTaskId(pending.taskId()).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(AnalysisTaskStatus.RUNNING);
            assertThat(reloaded.version()).isEqualTo(pending.version() + 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mysqlEnforcesUniqueForeignKeyAndTaskStateConstraints() {
        Project project = saveProject();
        Long projectId = projectDatabaseId(project.repositoryKey());
        String taskId = UUID.randomUUID().toString();

        assertMySqlConstraintViolation(() -> insertProject("openpulse/demo", 10), 1062);
        assertMySqlConstraintViolation(() -> insertProject("openpulse/invalid", -1), 3819);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM projects", Integer.class)).isEqualTo(1);

        insertPendingTask(taskId, projectId);

        assertMySqlConstraintViolation(() -> insertPendingTask(taskId, projectId), 1062);
        assertMySqlConstraintViolation(
                () -> insertPendingTask(UUID.randomUUID().toString(), Long.MAX_VALUE),
                1452
        );
        assertMySqlConstraintViolation(() -> jdbcTemplate.update(
                "INSERT INTO analysis_tasks "
                        + "(task_id, project_id, status, created_at, started_at, duration_ms, version) "
                        + "VALUES (?, ?, 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1, 0)",
                UUID.randomUUID().toString(),
                projectId
        ), 3819);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_tasks", Integer.class)).isEqualTo(1);
    }

    @Test
    void reportRepositoryStoresValidatedRedactedSnapshotWithSeparateTaskIds() throws Exception {
        AnalysisTask successfulTask = saveSuccessfulTask();
        AnalysisReportRecord snapshot = snapshotFactory.create(
                successfulTask.taskId(),
                analyzerReportWithSensitiveEvidence(),
                clockAt(30)
        );

        AnalysisReportRecord saved = analysisReportRepository.save(snapshot);
        AnalysisReportRecord reloaded = analysisReportRepository.findByTaskId(successfulTask.taskId()).orElseThrow();

        assertThat(reloaded.analysisTaskId()).isEqualTo(successfulTask.taskId());
        assertThat(reloaded.protocolVersion()).isEqualTo("1.0");
        assertThat(reloaded.analyzerTaskId()).isEqualTo("analyzer-task-777");
        assertThat(reloaded.reportStatus()).isEqualTo(saved.reportStatus());
        assertThat(reloaded.generatedAt()).isEqualTo(BASE_TIME);
        assertThat(reloaded.createdAt()).isEqualTo(BASE_TIME.plusSeconds(30));
        assertThat(reloaded.analysisTaskId().toString()).isNotEqualTo(reloaded.analyzerTaskId());
        assertThat(objectMapper.readTree(reloaded.reportJson()))
                .isEqualTo(objectMapper.readTree(saved.reportJson()));
        assertThat(objectMapper.readTree(reloaded.reportJson())
                .at("/risks/0/evidence/functionName").stringValue()).isEqualTo("registerUser");
        assertThat(reloaded.reportJson())
                .doesNotContain(TEMPORARY_PATH)
                .doesNotContain(TEST_TOKEN)
                .doesNotContain("stdout-secret")
                .doesNotContain("stderr-secret")
                .doesNotContain("diagnostic-secret")
                .doesNotContain("stack-secret")
                .doesNotContain("\"path\"");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT analyzer_task_id FROM analysis_reports",
                String.class
        )).isEqualTo("analyzer-task-777");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT JSON_VALID(report_json) FROM analysis_reports",
                Integer.class
        )).isEqualTo(1);

        assertPersistenceFailure(
                () -> analysisReportRepository.save(snapshot),
                PersistenceFailure.CONSTRAINT_VIOLATION
        );

        AnalysisReportRecord missingTask = new AnalysisReportRecord(
                AnalysisTaskId.create(),
                snapshot.protocolVersion(),
                snapshot.analyzerTaskId(),
                snapshot.reportStatus(),
                snapshot.reportJson(),
                snapshot.generatedAt(),
                snapshot.createdAt()
        );
        assertPersistenceFailure(
                () -> analysisReportRepository.save(missingTask),
                PersistenceFailure.RELATED_RECORD_NOT_FOUND
        );

        AnalysisTask pending = analysisTaskRepository.save(AnalysisTask.create(
                AnalysisTaskId.create(),
                successfulTask.projectRepositoryKey(),
                clockAt(31)
        ));
        AnalysisTask running = analysisTaskRepository.save(pending.start(clockAt(32)));
        AnalysisReportRecord unfinishedTaskReport = new AnalysisReportRecord(
                running.taskId(),
                snapshot.protocolVersion(),
                snapshot.analyzerTaskId(),
                snapshot.reportStatus(),
                snapshot.reportJson(),
                snapshot.generatedAt(),
                snapshot.createdAt()
        );
        assertPersistenceFailure(
                () -> analysisReportRepository.save(unfinishedTaskReport),
                PersistenceFailure.INVALID_DATA
        );

        AnalysisReportRecord malformed = new AnalysisReportRecord(
                successfulTask.taskId(),
                "1.0",
                "analyzer-task-777",
                AnalysisReportStatus.SUCCESS,
                "{not-json",
                BASE_TIME,
                BASE_TIME
        );
        assertPersistenceFailure(
                () -> analysisReportRepository.save(malformed),
                PersistenceFailure.INVALID_DATA
        );
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_reports", Integer.class)).isEqualTo(1);
    }

    @Test
    void reportRepositoryRejectsWhitelistBypassAndFailedStatusButAllowsPartialSuccess() {
        AnalysisTask successfulTask = saveSuccessfulTask();
        AnalysisReportRecord successfulSnapshot = snapshotFactory.create(
                successfulTask.taskId(),
                analyzerReportWithSensitiveEvidence(AnalyzerStatus.SUCCESS),
                clockAt(30)
        );
        String bypassJson = successfulSnapshot.reportJson().substring(
                0,
                successfulSnapshot.reportJson().length() - 1
        ) + ",\"secret\":\"" + TEST_TOKEN + "\"}";
        AnalysisReportRecord bypass = withJson(successfulSnapshot, bypassJson);

        assertPersistenceFailure(
                () -> analysisReportRepository.save(bypass),
                PersistenceFailure.INVALID_DATA
        );

        AnalysisReportRecord failedSnapshot = snapshotFactory.create(
                successfulTask.taskId(),
                analyzerReportWithSensitiveEvidence(AnalyzerStatus.FAILED),
                clockAt(31)
        );
        assertPersistenceFailure(
                () -> analysisReportRepository.save(failedSnapshot),
                PersistenceFailure.INVALID_DATA
        );

        AnalysisReportRecord partialSnapshot = snapshotFactory.create(
                successfulTask.taskId(),
                analyzerReportWithSensitiveEvidence(AnalyzerStatus.PARTIAL_SUCCESS),
                clockAt(32)
        );
        AnalysisReportRecord saved = analysisReportRepository.save(partialSnapshot);

        assertThat(saved.reportStatus()).isEqualTo(AnalysisReportStatus.PARTIAL_SUCCESS);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_reports", Integer.class)).isEqualTo(1);
    }

    private String tableProperty(String tableName, String propertyName) {
        return jdbcTemplate.queryForObject(
                "SELECT " + propertyName + " FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                String.class,
                tableName
        );
    }

    private Project saveProject() {
        return projectRepository.save(Project.fromMetadata(metadata(10, "first"), clockAt(0)));
    }

    private AnalysisTask saveSuccessfulTask() {
        Project project = saveProject();
        AnalysisTask pending = analysisTaskRepository.save(AnalysisTask.create(
                AnalysisTaskId.create(),
                project.repositoryKey(),
                clockAt(0)
        ));
        AnalysisTask running = analysisTaskRepository.save(pending.start(clockAt(5)));
        return analysisTaskRepository.save(running.succeed(clockAt(10)));
    }

    private GithubRepositoryMetadata metadata(long stars, String description) {
        return new GithubRepositoryMetadata(
                "OpenPulse",
                "Demo",
                "OpenPulse/Demo",
                "https://github.com/OpenPulse/Demo",
                description,
                "main",
                "Java",
                stars,
                3,
                false
        );
    }

    private AnalyzerReport analyzerReportWithSensitiveEvidence() {
        return analyzerReportWithSensitiveEvidence(AnalyzerStatus.SUCCESS);
    }

    private AnalyzerReport analyzerReportWithSensitiveEvidence(AnalyzerStatus status) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("functionName", "registerUser");
        evidence.put("path", TEMPORARY_PATH);
        evidence.put("token", TEST_TOKEN);
        evidence.put("stdout", "stdout-secret");
        evidence.put("stderr", "stderr-secret");
        evidence.put("diagnosticSummary", "diagnostic-secret");
        evidence.put("exceptionStackTrace", "stack-secret");
        return new AnalyzerReport(
                "1.0",
                "analyzer-task-777",
                status,
                new AnalyzerReport.Repository(TEMPORARY_PATH, "openpulse"),
                new AnalyzerReport.Summary(3, 2, 1, 0, 1, 30, 20, 5, 5),
                List.of(new AnalyzerReport.Language("Java", 2, 20)),
                new AnalyzerReport.Structure(
                        true,
                        false,
                        false,
                        false,
                        true,
                        true,
                        false,
                        List.of("pom.xml")
                ),
                new AnalyzerReport.Quality(80, 80, 70, 60),
                List.of(new AnalyzerReport.Risk(
                        "LONG_FUNCTION",
                        "CODE_SMELL",
                        RiskLevel.HIGH,
                        "src/UserService.java",
                        84,
                        "Function is too long.",
                        evidence
                )),
                new AnalyzerReport.Dependencies(List.of(), List.of()),
                OffsetDateTime.parse("2026-08-20T08:00:00+08:00")
        );
    }

    private Long projectDatabaseId(ProjectRepositoryKey key) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM projects WHERE repository_key = ?",
                Long.class,
                key.value()
        );
    }

    private void insertPendingTask(String taskId, long projectId) {
        jdbcTemplate.update(
                "INSERT INTO analysis_tasks (task_id, project_id, status, created_at, version) "
                        + "VALUES (?, ?, 'PENDING', CURRENT_TIMESTAMP(6), 0)",
                taskId,
                projectId
        );
    }

    private void insertProject(String repositoryKey, long stars) {
        jdbcTemplate.update(
                "INSERT INTO projects "
                        + "(repository_key, owner, name, full_name, canonical_url, description, "
                        + "default_branch, primary_language, stars, forks, archived, created_at, updated_at) "
                        + "VALUES (?, 'OpenPulse', 'Demo', 'OpenPulse/Demo', "
                        + "'https://github.com/OpenPulse/Demo', NULL, 'main', 'Java', ?, 3, false, "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                repositoryKey,
                stars
        );
    }

    private Clock clockAt(long secondsAfterBase) {
        return Clock.fixed(BASE_TIME.plusSeconds(secondsAfterBase), ZoneOffset.UTC);
    }

    private void assertPersistenceFailure(Runnable operation, PersistenceFailure expectedFailure) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(PersistenceOperationException.class)
                .extracting(exception -> ((PersistenceOperationException) exception).failure())
                .isEqualTo(expectedFailure);
    }

    private void assertMySqlConstraintViolation(Runnable operation, int expectedErrorCode) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(exception -> {
                    Throwable databaseCause = exception;
                    while (databaseCause.getCause() != null) {
                        databaseCause = databaseCause.getCause();
                    }
                    assertThat(databaseCause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) databaseCause).getErrorCode()).isEqualTo(expectedErrorCode);
                });
    }

    private AnalysisReportRecord withJson(AnalysisReportRecord source, String reportJson) {
        return new AnalysisReportRecord(
                source.analysisTaskId(),
                source.protocolVersion(),
                source.analyzerTaskId(),
                source.reportStatus(),
                reportJson,
                source.generatedAt(),
                source.createdAt()
        );
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent persistence test was interrupted", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException("Concurrent persistence test did not reach both flushes", exception);
        }
    }

    private Object awaitOutcome(Future<AnalysisTask> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent persistence test was interrupted", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Concurrent persistence operation timed out", exception);
        }
    }
}
