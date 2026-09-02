package io.github.lihanc940.openpulse.project.application;

import io.github.lihanc940.openpulse.project.domain.Project;
import io.github.lihanc940.openpulse.project.domain.ProjectRepositoryKey;

import java.util.Optional;

public interface ProjectRepository {

    Project save(Project project);

    Optional<Project> findByRepositoryKey(ProjectRepositoryKey repositoryKey);
}
