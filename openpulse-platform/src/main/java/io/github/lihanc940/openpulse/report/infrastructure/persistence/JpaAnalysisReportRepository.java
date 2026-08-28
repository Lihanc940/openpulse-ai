package io.github.lihanc940.openpulse.report.infrastructure.persistence;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskStatus;
import io.github.lihanc940.openpulse.analysis.infrastructure.persistence.AnalysisTaskJpaEntity;
import io.github.lihanc940.openpulse.analysis.infrastructure.persistence.SpringDataAnalysisTaskJpaRepository;
import io.github.lihanc940.openpulse.report.application.AnalysisReportRepository;
import io.github.lihanc940.openpulse.report.application.AnalysisReportSnapshotFactory;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportRecord;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceFailure;
import io.github.lihanc940.openpulse.shared.persistence.PersistenceOperationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Repository
public class JpaAnalysisReportRepository implements AnalysisReportRepository {

    private final SpringDataAnalysisReportJpaRepository reportRepository;
    private final SpringDataAnalysisTaskJpaRepository taskRepository;
    private final AnalysisReportSnapshotFactory snapshotFactory;

    public JpaAnalysisReportRepository(
            SpringDataAnalysisReportJpaRepository reportRepository,
            SpringDataAnalysisTaskJpaRepository taskRepository,
            AnalysisReportSnapshotFactory snapshotFactory
    ) {
        this.reportRepository = reportRepository;
        this.taskRepository = taskRepository;
        this.snapshotFactory = snapshotFactory;
    }

    @Override
    @Transactional
    public AnalysisReportRecord save(AnalysisReportRecord report) {
        Objects.requireNonNull(report, "report must not be null");
        snapshotFactory.validateSnapshot(report);
        try {
            if (reportRepository.findByTaskId(report.analysisTaskId().toString()).isPresent()) {
                throw new PersistenceOperationException(
                        PersistenceFailure.CONSTRAINT_VIOLATION,
                        "Analysis task already has a report."
                );
            }
            AnalysisTaskJpaEntity task = taskRepository.findByTaskId(report.analysisTaskId().toString())
                    .orElseThrow(() -> new PersistenceOperationException(
                            PersistenceFailure.RELATED_RECORD_NOT_FOUND,
                            "Analysis report task does not exist."
                    ));
            if (task.status() != AnalysisTaskStatus.SUCCESS || !report.reportStatus().isUsableResult()) {
                throw new PersistenceOperationException(
                        PersistenceFailure.INVALID_DATA,
                        "Only a usable report can be saved for a successful analysis task."
                );
            }
            return reportRepository.saveAndFlush(AnalysisReportJpaEntity.create(report, task)).toDomain();
        } catch (PersistenceOperationException exception) {
            throw exception;
        } catch (DataIntegrityViolationException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.CONSTRAINT_VIOLATION,
                    "Analysis report could not be saved because a database constraint was violated.",
                    exception
            );
        } catch (DataAccessException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.DATABASE_UNAVAILABLE,
                    "Analysis report could not be saved.",
                    exception
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnalysisReportRecord> findByTaskId(AnalysisTaskId taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        try {
            return reportRepository.findByTaskId(taskId.toString()).map(AnalysisReportJpaEntity::toDomain);
        } catch (DataAccessException exception) {
            throw new PersistenceOperationException(
                    PersistenceFailure.DATABASE_UNAVAILABLE,
                    "Analysis report could not be read.",
                    exception
            );
        }
    }
}
