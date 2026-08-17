package io.github.lihanc940.openpulse.integration.github;

import io.github.lihanc940.openpulse.project.domain.GithubRepositoryCoordinates;
import io.github.lihanc940.openpulse.project.domain.GithubRepositoryMetadata;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;

@Component
public class GithubRepositoryClient {

    static final String API_VERSION = "2026-03-10";
    static final String USER_AGENT = "openpulse-ai";
    static final String ACCEPT = "application/vnd.github+json";

    private final GithubClientProperties properties;
    private final RestClient restClient;

    @Autowired
    public GithubRepositoryClient(GithubClientProperties properties) {
        this(properties, createRestClient(properties, RestClient.builder()));
    }

    GithubRepositoryClient(GithubClientProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public GithubRepositoryMetadata fetch(GithubRepositoryCoordinates coordinates) {
        URI requestUri = UriComponentsBuilder.fromUri(properties.apiBaseUrl())
                .pathSegment("repos", coordinates.owner(), coordinates.repository())
                .build()
                .encode()
                .toUri();

        try {
            return restClient.get()
                    .uri(requestUri)
                    .headers(this::addRequestHeaders)
                    .exchange((request, response) -> handleResponse(response.getStatusCode(), response, coordinates));
        } catch (GithubRepositoryException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (hasCause(exception, HttpTimeoutException.class)
                    || hasCause(exception, SocketTimeoutException.class)) {
                throw failure(GithubRepositoryFailure.TIMEOUT, "GitHub request timed out.");
            }
            throw failure(GithubRepositoryFailure.GITHUB_UNAVAILABLE, "GitHub is unavailable.");
        } catch (RestClientException exception) {
            throw failure(GithubRepositoryFailure.GITHUB_UNAVAILABLE, "GitHub is unavailable.");
        }
    }

    private static RestClient createRestClient(
            GithubClientProperties properties,
            RestClient.Builder restClientBuilder
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return restClientBuilder.requestFactory(requestFactory).build();
    }

    private void addRequestHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.ACCEPT, ACCEPT);
        headers.set("X-GitHub-Api-Version", API_VERSION);
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        if (properties.hasToken()) {
            headers.setBearerAuth(properties.token());
        }
    }

    private GithubRepositoryMetadata handleResponse(
            HttpStatusCode status,
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            GithubRepositoryCoordinates coordinates
    ) {
        int statusCode = status.value();
        if (statusCode == 200) {
            return readMetadata(response, coordinates);
        }
        if (statusCode == 401) {
            throw failure(GithubRepositoryFailure.AUTHENTICATION_FAILED, "GitHub authentication failed.");
        }
        if (statusCode == 404) {
            throw failure(
                    GithubRepositoryFailure.REPOSITORY_NOT_FOUND_OR_INACCESSIBLE,
                    "GitHub repository was not found or is inaccessible."
            );
        }
        if (statusCode == 429 || (statusCode == 403 && hasRateLimitSignal(response.getHeaders()))) {
            throw failure(GithubRepositoryFailure.RATE_LIMITED, "GitHub rate limit was exceeded.");
        }
        throw failure(GithubRepositoryFailure.GITHUB_UNAVAILABLE, "GitHub is unavailable.");
    }

    private GithubRepositoryMetadata readMetadata(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            GithubRepositoryCoordinates coordinates
    ) {
        GithubRepositoryResponse repositoryResponse;
        try {
            repositoryResponse = response.bodyTo(GithubRepositoryResponse.class);
        } catch (RuntimeException exception) {
            throw failure(GithubRepositoryFailure.INVALID_RESPONSE, "GitHub returned an invalid response.");
        }
        validateResponse(repositoryResponse, coordinates);

        String owner = repositoryResponse.owner().login();
        String name = repositoryResponse.name();
        return new GithubRepositoryMetadata(
                owner,
                name,
                repositoryResponse.fullName(),
                "https://github.com/" + owner + "/" + name,
                repositoryResponse.description(),
                repositoryResponse.defaultBranch(),
                repositoryResponse.language(),
                repositoryResponse.stargazersCount(),
                repositoryResponse.forksCount(),
                repositoryResponse.archived()
        );
    }

    private void validateResponse(
            GithubRepositoryResponse response,
            GithubRepositoryCoordinates coordinates
    ) {
        if (response == null
                || isBlank(response.name())
                || response.owner() == null
                || isBlank(response.owner().login())
                || isBlank(response.fullName())
                || isBlank(response.defaultBranch())
                || response.stargazersCount() == null
                || response.forksCount() == null
                || response.archived() == null
                || response.privateRepository() == null
                || response.stargazersCount() < 0
                || response.forksCount() < 0
                || !response.owner().login().equalsIgnoreCase(coordinates.owner())
                || !response.name().equalsIgnoreCase(coordinates.repository())
                || !response.fullName().equals(response.owner().login() + "/" + response.name())) {
            throw failure(GithubRepositoryFailure.INVALID_RESPONSE, "GitHub returned an invalid response.");
        }
        if (response.privateRepository()) {
            throw failure(
                    GithubRepositoryFailure.PRIVATE_REPOSITORY_UNSUPPORTED,
                    "Private GitHub repositories are not supported."
            );
        }
    }

    private boolean hasRateLimitSignal(HttpHeaders headers) {
        return "0".equals(headers.getFirst("X-RateLimit-Remaining"))
                || !isBlank(headers.getFirst(HttpHeaders.RETRY_AFTER));
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private GithubRepositoryException failure(GithubRepositoryFailure failure, String message) {
        return new GithubRepositoryException(failure, message);
    }
}
