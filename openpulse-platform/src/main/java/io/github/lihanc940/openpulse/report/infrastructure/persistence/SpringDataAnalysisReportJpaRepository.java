package io.github.lihanc940.openpulse.report.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface SpringDataAnalysisReportJpaRepository extends JpaRepository<AnalysisReportJpaEntity, Long> {

    @Query("""
            select report
            from AnalysisReportJpaEntity report
            join fetch report.analysisTask task
            where task.taskId = :taskId
            """)
    Optional<AnalysisReportJpaEntity> findByTaskId(@Param("taskId") String taskId);
}
