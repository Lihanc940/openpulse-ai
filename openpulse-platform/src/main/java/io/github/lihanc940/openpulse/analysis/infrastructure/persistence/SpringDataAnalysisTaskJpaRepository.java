package io.github.lihanc940.openpulse.analysis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataAnalysisTaskJpaRepository extends JpaRepository<AnalysisTaskJpaEntity, Long> {

    Optional<AnalysisTaskJpaEntity> findByTaskId(String taskId);
}
