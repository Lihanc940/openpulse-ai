package io.github.lihanc940.openpulse.analysis.application;

import io.github.lihanc940.openpulse.analysis.domain.AnalysisTask;
import io.github.lihanc940.openpulse.analysis.domain.AnalysisTaskId;

import java.util.Optional;

public interface AnalysisTaskRepository {

    AnalysisTask save(AnalysisTask task);

    Optional<AnalysisTask> findByTaskId(AnalysisTaskId taskId);
}
