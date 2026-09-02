package io.github.lihanc940.openpulse.report.application;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;
import io.github.lihanc940.openpulse.report.domain.AnalysisReportRecord;

import java.util.Optional;

public interface AnalysisReportRepository {

    AnalysisReportRecord save(AnalysisReportRecord report);

    Optional<AnalysisReportRecord> findByTaskId(AnalysisTaskId taskId);
}
