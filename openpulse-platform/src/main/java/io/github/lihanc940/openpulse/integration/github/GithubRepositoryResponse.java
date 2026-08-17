package io.github.lihanc940.openpulse.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record GithubRepositoryResponse(
        String name,
        @JsonProperty("full_name") String fullName,
        Owner owner,
        String description,
        @JsonProperty("default_branch") String defaultBranch,
        String language,
        @JsonProperty("stargazers_count") Long stargazersCount,
        @JsonProperty("forks_count") Long forksCount,
        Boolean archived,
        @JsonProperty("private") Boolean privateRepository
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Owner(String login) {
    }
}
