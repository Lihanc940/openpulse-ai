package io.github.lihanc940.openpulse.integration.github;

import io.github.lihanc940.openpulse.project.domain.GithubRepositoryCoordinates;
import io.github.lihanc940.openpulse.project.domain.GithubRepositoryMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GithubRepositoryClientTest {

    private static final URI API_BASE_URL = URI.create("https://api.test.example");
    private static final String REPOSITORY_ENDPOINT = "https://api.test.example/repos/OpenAI/OpenAI";
    private static final GithubRepositoryCoordinates COORDINATES = new GithubRepositoryCoordinates(
            "OpenAI",
            "OpenAI",
            "https://github.com/OpenAI/OpenAI"
    );

    private MockRestServiceServer server;
    private GithubRepositoryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubRepositoryClient(properties(""), builder.build());
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    @Test
    void fetchesAndMapsPublicRepositoryMetadataWithRequiredHeaders() {
        server.expect(once(), requestTo(REPOSITORY_ENDPOINT))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.ACCEPT, GithubRepositoryClient.ACCEPT))
                .andExpect(header("X-GitHub-Api-Version", GithubRepositoryClient.API_VERSION))
                .andExpect(header(HttpHeaders.USER_AGENT, GithubRepositoryClient.USER_AGENT))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(validRepositoryJson(), MediaType.APPLICATION_JSON));

        GithubRepositoryMetadata metadata = client.fetch(COORDINATES);

        assertThat(metadata.owner()).isEqualTo("OpenAI");
        assertThat(metadata.name()).isEqualTo("OpenAI");
        assertThat(metadata.fullName()).isEqualTo("OpenAI/OpenAI");
        assertThat(metadata.canonicalUrl()).isEqualTo("https://github.com/OpenAI/OpenAI");
        assertThat(metadata.description()).isEqualTo("OpenAI repository");
        assertThat(metadata.defaultBranch()).isEqualTo("main");
        assertThat(metadata.primaryLanguage()).isEqualTo("Python");
        assertThat(metadata.stars()).isEqualTo(123);
        assertThat(metadata.forks()).isEqualTo(45);
        assertThat(metadata.archived()).isFalse();
    }

    @Test
    void acceptsNullableDescriptionAndLanguage() {
        server.expect(requestTo(REPOSITORY_ENDPOINT))
                .andRespond(withSuccess(validRepositoryJson()
                        .replace("\"OpenAI repository\"", "null")
                        .replace("\"Python\"", "null"), MediaType.APPLICATION_JSON));

        GithubRepositoryMetadata metadata = client.fetch(COORDINATES);

        assertThat(metadata.description()).isNull();
        assertThat(metadata.primaryLanguage()).isNull();
    }

    @Test
    void sendsBearerTokenOnlyWhenConfiguredAndNeverExposesIt() {
        String runtimeToken = "runtime-" + UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer tokenServer = MockRestServiceServer.bindTo(builder).build();
        GithubClientProperties tokenProperties = properties(runtimeToken);
        GithubRepositoryClient tokenClient = new GithubRepositoryClient(tokenProperties, builder.build());
        tokenServer.expect(requestTo(REPOSITORY_ENDPOINT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + runtimeToken))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        GithubRepositoryException exception = catchThrowableOfType(
                GithubRepositoryException.class,
                () -> tokenClient.fetch(COORDINATES)
        );

        tokenServer.verify();
        assertThat(exception.failure()).isEqualTo(GithubRepositoryFailure.GITHUB_UNAVAILABLE);
        assertThat(exception.toString()).doesNotContain(runtimeToken);
        assertThat(exception.getMessage()).doesNotContain(runtimeToken);
        assertThat(tokenProperties.toString()).doesNotContain(runtimeToken);
    }

    @ParameterizedTest
    @MethodSource("statusFailures")
    void mapsExternalStatusFailures(
            HttpStatus status,
            String headerName,
            String headerValue,
            GithubRepositoryFailure expectedFailure
    ) {
        var response = withStatus(status);
        if (headerName != null) {
            response.header(headerName, headerValue);
        }
        server.expect(requestTo(REPOSITORY_ENDPOINT)).andRespond(response);

        assertFailure(expectedFailure, () -> client.fetch(COORDINATES));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsInvalidJsonAndInvalidFields(String responseBody) {
        server.expect(requestTo(REPOSITORY_ENDPOINT))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertFailure(GithubRepositoryFailure.INVALID_RESPONSE, () -> client.fetch(COORDINATES));
    }

    @Test
    void rejectsPrivateRepository() {
        server.expect(requestTo(REPOSITORY_ENDPOINT))
                .andRespond(withSuccess(
                        validRepositoryJson().replace("\"private\": false", "\"private\": true"),
                        MediaType.APPLICATION_JSON
                ));

        assertFailure(GithubRepositoryFailure.PRIVATE_REPOSITORY_UNSUPPORTED, () -> client.fetch(COORDINATES));
    }

    @ParameterizedTest
    @MethodSource("redirectStatuses")
    void rejectsRedirectsWithoutFollowingThem(HttpStatus redirectStatus, String location) {
        server.expect(once(), requestTo(REPOSITORY_ENDPOINT))
                .andRespond(withStatus(redirectStatus).header(HttpHeaders.LOCATION, location));

        assertFailure(GithubRepositoryFailure.GITHUB_UNAVAILABLE, () -> client.fetch(COORDINATES));
    }

    @ParameterizedTest
    @MethodSource("timeoutFailures")
    void mapsConnectionAndReadTimeoutsWithoutRealNetwork(IOException simulatedTimeout) {
        RestClient timeoutRestClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw simulatedTimeout;
                })
                .build();
        GithubRepositoryClient timeoutClient = new GithubRepositoryClient(properties(""), timeoutRestClient);

        assertFailure(GithubRepositoryFailure.TIMEOUT, () -> timeoutClient.fetch(COORDINATES));
    }

    @Test
    void mapsOrdinaryConnectionFailureToUnavailable() {
        RestClient unavailableRestClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new ConnectException("simulated connection failure");
                })
                .build();
        GithubRepositoryClient unavailableClient = new GithubRepositoryClient(
                properties(""),
                unavailableRestClient
        );

        assertFailure(GithubRepositoryFailure.GITHUB_UNAVAILABLE, () -> unavailableClient.fetch(COORDINATES));
    }

    @Test
    void doesNotCopyLargeErrorResponseBodyIntoException() {
        String errorBody = "sensitive-response-body-" + "x".repeat(100_000);
        server.expect(requestTo(REPOSITORY_ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(errorBody));

        GithubRepositoryException exception = catchThrowableOfType(
                GithubRepositoryException.class,
                () -> client.fetch(COORDINATES)
        );

        assertThat(exception.failure()).isEqualTo(GithubRepositoryFailure.GITHUB_UNAVAILABLE);
        assertThat(exception.getMessage()).doesNotContain("sensitive-response-body");
        assertThat(exception.getMessage().length()).isLessThan(100);
    }

    private void assertFailure(GithubRepositoryFailure failure, Runnable operation) {
        GithubRepositoryException exception = catchThrowableOfType(
                GithubRepositoryException.class,
                operation::run
        );
        assertThat(exception.failure()).isEqualTo(failure);
    }

    private GithubClientProperties properties(String token) {
        return new GithubClientProperties(
                API_BASE_URL,
                token,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }

    private static Stream<Arguments> statusFailures() {
        return Stream.of(
                Arguments.of(HttpStatus.UNAUTHORIZED, null, null,
                        GithubRepositoryFailure.AUTHENTICATION_FAILED),
                Arguments.of(HttpStatus.NOT_FOUND, null, null,
                        GithubRepositoryFailure.REPOSITORY_NOT_FOUND_OR_INACCESSIBLE),
                Arguments.of(HttpStatus.FORBIDDEN, null, null,
                        GithubRepositoryFailure.GITHUB_UNAVAILABLE),
                Arguments.of(HttpStatus.FORBIDDEN, "X-RateLimit-Remaining", "0",
                        GithubRepositoryFailure.RATE_LIMITED),
                Arguments.of(HttpStatus.FORBIDDEN, "X-RateLimit-Reset", "1786800000",
                        GithubRepositoryFailure.GITHUB_UNAVAILABLE),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, HttpHeaders.RETRY_AFTER, "60",
                        GithubRepositoryFailure.RATE_LIMITED),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, null, null,
                        GithubRepositoryFailure.RATE_LIMITED),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, null, null,
                        GithubRepositoryFailure.GITHUB_UNAVAILABLE),
                Arguments.of(HttpStatus.BAD_GATEWAY, null, null,
                        GithubRepositoryFailure.GITHUB_UNAVAILABLE),
                Arguments.of(HttpStatus.SERVICE_UNAVAILABLE, null, null,
                        GithubRepositoryFailure.GITHUB_UNAVAILABLE)
        );
    }

    private static Stream<String> invalidResponses() {
        return Stream.of(
                "{not-json",
                "{}",
                validRepositoryJson().replace("\"default_branch\": \"main\",", ""),
                validRepositoryJson().replace("\"stargazers_count\": 123", "\"stargazers_count\": -1"),
                validRepositoryJson().replace("\"forks_count\": 45", "\"forks_count\": -1"),
                validRepositoryJson().replace("\"full_name\": \"OpenAI/OpenAI\"", "\"full_name\": \"Other/Repo\""),
                validRepositoryJson().replace("\"private\": false", "null")
        );
    }

    private static Stream<Arguments> redirectStatuses() {
        return Stream.of(
                Arguments.of(HttpStatus.MOVED_PERMANENTLY, "https://api.test.example/repos/OpenAI/renamed"),
                Arguments.of(HttpStatus.FOUND, "https://other.example/repos/OpenAI/OpenAI"),
                Arguments.of(HttpStatus.TEMPORARY_REDIRECT, "http://api.test.example/repos/OpenAI/OpenAI"),
                Arguments.of(HttpStatus.PERMANENT_REDIRECT, "https://other.example/repos/OpenAI/OpenAI")
        );
    }

    private static Stream<IOException> timeoutFailures() {
        return Stream.of(
                new HttpConnectTimeoutException("simulated connect timeout"),
                new SocketTimeoutException("simulated read timeout")
        );
    }

    private static String validRepositoryJson() {
        return """
                {
                  "name": "OpenAI",
                  "full_name": "OpenAI/OpenAI",
                  "owner": {"login": "OpenAI"},
                  "description": "OpenAI repository",
                  "default_branch": "main",
                  "language": "Python",
                  "stargazers_count": 123,
                  "forks_count": 45,
                  "archived": false,
                  "private": false
                }
                """;
    }
}
