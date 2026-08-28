CREATE TABLE projects (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_key VARCHAR(255) NOT NULL,
    owner VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    full_name VARCHAR(511) NOT NULL,
    canonical_url VARCHAR(1024) NOT NULL,
    description TEXT NULL,
    default_branch VARCHAR(255) NOT NULL,
    primary_language VARCHAR(255) NULL,
    stars BIGINT NOT NULL,
    forks BIGINT NOT NULL,
    archived BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_projects PRIMARY KEY (id),
    CONSTRAINT uk_projects_repository_key UNIQUE (repository_key),
    CONSTRAINT chk_projects_stars_non_negative CHECK (stars >= 0),
    CONSTRAINT chk_projects_forks_non_negative CHECK (forks >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE analysis_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id CHAR(36) NOT NULL,
    project_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64) NULL,
    failure_message VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    duration_ms BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_analysis_tasks PRIMARY KEY (id),
    CONSTRAINT uk_analysis_tasks_task_id UNIQUE (task_id),
    CONSTRAINT fk_analysis_tasks_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT chk_analysis_tasks_duration_non_negative CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT chk_analysis_tasks_state CHECK (
        (status = 'PENDING' AND started_at IS NULL AND completed_at IS NULL AND duration_ms IS NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL AND duration_ms IS NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'SUCCESS' AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND duration_ms IS NOT NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'FAILED' AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND duration_ms IS NOT NULL AND failure_code IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_analysis_tasks_project_created_at
    ON analysis_tasks (project_id, created_at);
CREATE INDEX idx_analysis_tasks_status_created_at
    ON analysis_tasks (status, created_at);

CREATE TABLE analysis_reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    analysis_task_id BIGINT NOT NULL,
    protocol_version VARCHAR(64) NOT NULL,
    analyzer_task_id VARCHAR(255) NOT NULL,
    report_status VARCHAR(32) NOT NULL,
    report_json JSON NOT NULL,
    generated_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_analysis_reports PRIMARY KEY (id),
    CONSTRAINT uk_analysis_reports_analysis_task_id UNIQUE (analysis_task_id),
    CONSTRAINT fk_analysis_reports_analysis_task FOREIGN KEY (analysis_task_id) REFERENCES analysis_tasks (id),
    CONSTRAINT chk_analysis_reports_usable_status CHECK (report_status IN ('SUCCESS', 'PARTIAL_SUCCESS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
