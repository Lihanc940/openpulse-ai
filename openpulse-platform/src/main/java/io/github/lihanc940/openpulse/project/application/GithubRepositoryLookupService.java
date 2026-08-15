package io.github.lihanc940.openpulse.project.application;

import io.github.lihanc940.openpulse.integration.github.GithubRepositoryClient;
import io.github.lihanc940.openpulse.integration.github.GithubRepositoryUrlParser;
import io.github.lihanc940.openpulse.project.domain.GithubRepositoryCoordinates;
import io.github.lihanc940.openpulse.project.domain.GithubRepositoryMetadata;
import org.springframework.stereotype.Service;

@Service
public class GithubRepositoryLookupService {

    private final GithubRepositoryUrlParser urlParser;
    private final GithubRepositoryClient repositoryClient;

    public GithubRepositoryLookupService(
            GithubRepositoryUrlParser urlParser,
            GithubRepositoryClient repositoryClient
    ) {
        this.urlParser = urlParser;
        this.repositoryClient = repositoryClient;
    }

    public GithubRepositoryMetadata lookup(String repositoryUrl) {
        GithubRepositoryCoordinates coordinates = urlParser.parse(repositoryUrl);
        return repositoryClient.fetch(coordinates);
    }
}
