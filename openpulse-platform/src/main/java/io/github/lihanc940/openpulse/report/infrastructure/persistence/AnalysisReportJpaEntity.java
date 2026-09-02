package io.github.lihanc940.openpulse.report.infrastructure.persistence;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.analysis.infrastructure.persistence.AnalysisTaskJpaEntity;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportRecord;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "analysis_reports")
public class AnalysisReportJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_task_id", nullable = false, unique = true)
    private AnalysisTaskJpaEntity analysisTask;

    @Column(name = "protocol_version", nullable = false, length = 64)
    private String protocolVersion;

    @Column(name = "analyzer_task_id", nullable = false, length = 255)
    private String analyzerTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_status", nullable = false, length = 32)
    private AnalysisReportStatus reportStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", nullable = false, columnDefinition = "json")
    private String reportJson;

    @Column(name = "generated_at", nullable = false, columnDefinition = "timestamp(6)")
    private Instant generatedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6)")
    private Instant createdAt;

    protected AnalysisReportJpaEntity() {
    }

    static AnalysisReportJpaEntity create(AnalysisReportRecord report, AnalysisTaskJpaEntity task) {
        AnalysisReportJpaEntity entity = new AnalysisReportJpaEntity();
        entity.analysisTask = task;
        entity.protocolVersion = report.protocolVersion();
        entity.analyzerTaskId = report.analyzerTaskId();
        entity.reportStatus = report.reportStatus();
        entity.reportJson = report.reportJson();
        entity.generatedAt = report.generatedAt();
        entity.createdAt = report.createdAt();
        return entity;
    }

    AnalysisReportRecord toDomain() {
        return new AnalysisReportRecord(
                AnalysisTaskId.parse(analysisTask.taskId()),
                protocolVersion,
                analyzerTaskId,
                reportStatus,
                reportJson,
                generatedAt,
                createdAt
        );
    }
}
