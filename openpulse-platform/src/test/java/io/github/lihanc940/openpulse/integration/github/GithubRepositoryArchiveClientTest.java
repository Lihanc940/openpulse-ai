package io.github.lihanc940.openpulse.integration.github;

import io.github.lihanc940.openpulse.project.domain.GithubRepositoryMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
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

class GithubRepositoryArchiveClientTest {

    private static final URI PRODUCTION_API = URI.create("https://api.github.com");
    private static final String FIRST_HOP = "https://api.github.com/repos/OpenAI/OpenAI/zipball";
    private static final String SECOND_HOP = "https://codeload.github.com/OpenAI/OpenAI/legacy.zip/main?signature=test";

    @TempDir
    Path testDirectory;

    private MockRestServiceServer server;
    private RestClient.Builder restClientBuilder;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    @AfterEach
    void verifyServer() {
        server.verify();
        Thread.interrupted();
    }

    @Test
    void downloadsThroughOneTrustedRedirectWithRequiredFirstHopHeaders() throws Exception {
        byte[] archive = bytes("zip-bytes");
        server.expect(once(), requestTo(FIRST_HOP))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.ACCEPT, GithubRepositoryArchiveClient.ACCEPT))
                .andExpect(header("X-GitHub-Api-Version", GithubRepositoryArchiveClient.API_VERSION))
                .andExpect(header(HttpHeaders.USER_AGENT, GithubRepositoryArchiveClient.USER_AGENT))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, SECOND_HOP));
        server.expect(once(), requestTo(SECOND_HOP))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(request -> new MockClientHttpResponse(archive, HttpStatus.OK));
        Path archivePath = testDirectory.resolve("repository.zip");

        long downloadedBytes = client(PRODUCTION_API, "", 1024).download(metadata(), archivePath);

        assertThat(downloadedBytes).isEqualTo(archive.length);
        assertThat(Files.readAllBytes(archivePath)).containsExactly(archive);
    }

    @Test
    void sendsTokenOnlyOnFirstHopAndNeverExposesIt() {
        String token = "runtime-" + UUID.randomUUID();
        server.expect(requestTo(FIRST_HOP))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, SECOND_HOP));
        server.expect(requestTo(SECOND_HOP))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        GithubArchiveException exception = catchThrowableOfType(
                GithubArchiveException.class,
                () -> client(PRODUCTION_API, token, 1024).download(
                        metadata(),
                        testDirectory.resolve("token.zip")
                )
        );

        assertThat(exception.failure()).isEqualTo(GithubArchiveFailure.GITHUB_UNAVAILABLE);
        assertThat(exception.toString()).doesNotContain(token);
        assertThat(exception.getMessage()).doesNotContain(token);
    }

    @Test
    void allowsOnlySameOriginRedirectForLoopbackFixture() throws Exception {
        URI loopbackApi = URI.create("http://127.0.0.1:18080/api");
        String firstHop = "http://127.0.0.1:18080/api/repos/OpenAI/OpenAI/zipball";
        String secondHop = "http://127.0.0.1:18080/download/archive.zip";
        server.expect(requestTo(firstHop))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, secondHop));
        server.expect(requestTo(secondHop))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(request -> new MockClientHttpResponse(bytes("zip"), HttpStatus.OK));

        long count = client(loopbackApi, "", 10).download(
                metadata(),
                testDirectory.resolve("loopback.zip")
        );

        assertThat(count).isEqualTo(3);
    }

    @ParameterizedTest
    @MethodSource("untrustedRedirects")
    void rejectsUntrustedRedirectWithoutRequestingTarget(String location) {
        var response = withStatus(HttpStatus.FOUND);
        if (location != null) {
            response.header(HttpHeaders.LOCATION, location);
        }
        server.expect(once(), requestTo(FIRST_HOP)).andRespond(response);

        assertFailure(
                GithubArchiveFailure.UNTRUSTED_REDIRECT,
                () -> client(PRODUCTION_API, "token-not-forwarded", 1024).download(
                        metadata(),
                        testDirectory.resolve("untrusted.zip")
                )
        );
    }

    @Test
    void rejectsSecondRedirect() {
        server.expect(requestTo(FIRST_HOP))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, SECOND_HOP));
        server.expect(requestTo(SECOND_HOP))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "https://codeload.github.com/another.zip"));

        assertFailure(
                GithubArchiveFailure.UNTRUSTED_REDIRECT,
                () -> client(PRODUCTION_API, "first-hop-only", 1024).download(
                        metadata(),
                        testDirectory.resolve("second-redirect.zip")
                )
        );
    }

    @ParameterizedTest
    @MethodSource("statusFailures")
    void mapsFirstHopHttpFailures(
            HttpStatus status,
            String headerName,
            String headerValue,
            GithubArchiveFailure expectedFailure
    ) {
        var response = withStatus(status);
        if (headerName != null) {
            response.header(headerName, headerValue);
        }
        server.expect(requestTo(FIRST_HOP)).andRespond(response);

        assertFailure(
                expectedFailure,
                () -> client(PRODUCTION_API, "", 1024).download(
                        metadata(),
                        testDirectory.resolve("status.zip")
                )
        );
    }

    @Test
    void rejectsDeclaredContentLengthBeforeReadingBody() {
        server.expect(requestTo(FIRST_HOP))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, SECOND_HOP));
        server.expect(requestTo(SECOND_HOP))
                .andRespond(withStatus(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_LENGTH, "4")
                        .body(bytes("x")));

        assertFailure(
                GithubArchiveFailure.ARCHIVE_TOO_LARGE,
                () -> client(PRODUCTION_API, "", 3).download(
                        metadata(),
                        testDirectory.resolve("declared-large.zip")
                )
        );
    }

    @Test
    void countsActualBytesWhenLengthIsMissingOrSmallerThanBody() throws Exception {
        server.expect(requestTo(FIRST_HOP))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, SECOND_HOP));
        server.expect(requestTo(SECOND_HOP))
                .andRespond(request -> new MockClientHttpResponse(bytes("123"), HttpStatus.OK));
        Path exactPath = testDirectory.resolve("exact.zip");

        long exact = client(PRODUCTION_API, "", 3).download(metadata(), exactPath);

        assertThat(exact).isEqualTo(3);
        assertThat(Files.readString(exactPath)).isEqualTo("123");

        RestClient.Builder secondBuilder = RestClient.builder();
        MockRestServiceServer secondServer = MockRestServiceServer.bindTo(secondBuilder).build();
        secondServer.expect(requestTo(FIRST_HOP))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, SECOND_HOP));
        secondServer.expect(requestTo(SECOND_HOP))
                .andRespond(withStatus(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_LENGTH, "2")
                        .body(bytes("1234")));
        GithubRepositoryArchiveClient secondClient = client(PRODUCTION_API, "", 3, secondBuilder);

        assertFailure(
                GithubArchiveFailure.ARCHIVE_TOO_LARGE,
                () -> secondClient.download(metadata(), testDirectory.resolve("actual-large.zip"))
        );
        secondServer.verify();
    }

    @Test
    void mapsTimeoutInterruptionAndConnectionFailureWithoutNetwork() {
        assertTransportFailure(
                new SocketTimeoutException("simulated timeout"),
                GithubArchiveFailure.DOWNLOAD_TIMEOUT,
                false
        );
        assertTransportFailure(
                new IOException("simulated interruption", new InterruptedException()),
                GithubArchiveFailure.INTERRUPTED,
                true
        );
        assertTransportFailure(
                new ConnectException("simulated connection failure"),
                GithubArchiveFailure.GITHUB_UNAVAILABLE,
                false
        );
    }

    @Test
    void mapsTimeoutAndInterruptionWhileStreamingResponseBody() {
        assertBodyReadFailure(
                new SocketTimeoutException("simulated body timeout"),
                GithubArchiveFailure.DOWNLOAD_TIMEOUT,
                false,
                "body-timeout.zip"
        );
        assertBodyReadFailure(
                new ClosedByInterruptException(),
                GithubArchiveFailure.INTERRUPTED,
                true,
                "body-interrupted.zip"
        );
    }

    @Test
    void reportsArchiveWriteFailureWithoutExposingLocalPath() throws Exception {
        server.expect(requestTo(FIRST_HOP))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, SECOND_HOP));
        server.expect(requestTo(SECOND_HOP))
                .andRespond(request -> new MockClientHttpResponse(bytes("zip"), HttpStatus.OK));
        Path existingFile = Files.writeString(testDirectory.resolve("already-exists.zip"), "existing");

        GithubArchiveException exception = catchThrowableOfType(
                GithubArchiveException.class,
                () -> client(PRODUCTION_API, "", 1024).download(metadata(), existingFile)
        );

        assertThat(exception.failure()).isEqualTo(GithubArchiveFailure.ARCHIVE_WRITE_FAILED);
        assertThat(exception.getMessage()).doesNotContain(existingFile.toString());
    }

    @Test
    void doesNotReadLargeErrorBodyIntoException() {
        String errorBody = "sensitive-body-" + "x".repeat(100_000);
        server.expect(requestTo(FIRST_HOP))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body(errorBody));

        GithubArchiveException exception = catchThrowableOfType(
                GithubArchiveException.class,
                () -> client(PRODUCTION_API, "", 1024).download(
                        metadata(),
                        testDirectory.resolve("error.zip")
                )
        );

        assertThat(exception.failure()).isEqualTo(GithubArchiveFailure.GITHUB_UNAVAILABLE);
        assertThat(exception.getMessage()).doesNotContain("sensitive-body");
        assertThat(exception.getMessage().length()).isLessThan(100);
    }

    @Test
    void rejectsInconsistentMetadataBeforeMakingRequest() {
        GithubRepositoryMetadata inconsistent = new GithubRepositoryMetadata(
                "OpenAI",
                "OpenAI",
                "another/repository",
                "https://github.com/OpenAI/OpenAI",
                null,
                "main",
                null,
                0,
                0,
                false
        );

        assertFailure(
                GithubArchiveFailure.INVALID_REPOSITORY_METADATA,
                () -> client(PRODUCTION_API, "", 1024).download(
                        inconsistent,
                        testDirectory.resolve("invalid-metadata.zip")
                )
        );
    }

    private GithubRepositoryArchiveClient client(URI apiBase, String token, long maximumBytes) {
        return client(apiBase, token, maximumBytes, restClientBuilder);
    }

    private GithubRepositoryArchiveClient client(
            URI apiBase,
            String token,
            long maximumBytes,
            RestClient.Builder builder
    ) {
        GithubClientProperties clientProperties = new GithubClientProperties(
                apiBase,
                token,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
        GithubArchiveProperties archiveProperties = new GithubArchiveProperties(
                Duration.ofSeconds(1),
                DataSize.ofBytes(maximumBytes),
                DataSize.ofKilobytes(1),
                10
        );
        return new GithubRepositoryArchiveClient(clientProperties, archiveProperties, builder.build());
    }

    private void assertTransportFailure(
            IOException transportFailure,
            GithubArchiveFailure expectedFailure,
            boolean expectInterrupted
    ) {
        RestClient restClient = RestClient.builder()
                .requestFactory((uri, method) -> {
                    throw transportFailure;
                })
                .build();
        GithubRepositoryArchiveClient client = new GithubRepositoryArchiveClient(
                new GithubClientProperties(
                        PRODUCTION_API,
                        "",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)
                ),
                new GithubArchiveProperties(
                        Duration.ofSeconds(1),
                        DataSize.ofKilobytes(1),
                        DataSize.ofKilobytes(1),
                        10
                ),
                restClient
        );

        assertFailure(
                expectedFailure,
                () -> client.download(metadata(), testDirectory.resolve("transport.zip"))
        );
        assertThat(Thread.currentThread().isInterrupted()).isEqualTo(expectInterrupted);
        Thread.interrupted();
    }

    private void assertBodyReadFailure(
            IOException readFailure,
            GithubArchiveFailure expectedFailure,
            boolean expectInterrupted,
            String archiveName
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer bodyServer = MockRestServiceServer.bindTo(builder).build();
        bodyServer.expect(requestTo(FIRST_HOP))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, SECOND_HOP));
        bodyServer.expect(requestTo(SECOND_HOP)).andRespond(request -> new MockClientHttpResponse(
                InputStream.nullInputStream(),
                HttpStatus.OK
        ) {
            @Override
            public InputStream getBody() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw readFailure;
                    }
                };
            }
        });
        GithubRepositoryArchiveClient bodyClient = client(PRODUCTION_API, "", 1024, builder);

        assertFailure(
                expectedFailure,
                () -> bodyClient.download(metadata(), testDirectory.resolve(archiveName))
        );
        assertThat(Thread.currentThread().isInterrupted()).isEqualTo(expectInterrupted);
        Thread.interrupted();
        bodyServer.verify();
    }

    private GithubRepositoryMetadata metadata() {
        return new GithubRepositoryMetadata(
                "OpenAI",
                "OpenAI",
                "OpenAI/OpenAI",
                "https://github.com/OpenAI/OpenAI",
                "description",
                "main",
                "Java",
                1,
                2,
                false
        );
    }

    private void assertFailure(GithubArchiveFailure failure, Runnable operation) {
        GithubArchiveException exception = catchThrowableOfType(
                GithubArchiveException.class,
                operation::run
        );
        assertThat(exception.failure()).isEqualTo(failure);
        assertThat(exception.getMessage()).doesNotContain(testDirectory.toAbsolutePath().toString());
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Stream<String> untrustedRedirects() {
        return Stream.of(
                null,
                "",
                "/relative/archive.zip",
                "http://codeload.github.com/OpenAI/OpenAI/archive.zip",
                "https://codeload.github.com.evil.example/archive.zip",
                "https://user@codeload.github.com/archive.zip",
                "https://codeload.github.com:8443/archive.zip",
                "https://codeload.github.com/archive.zip#fragment"
        );
    }

    private static Stream<Arguments> statusFailures() {
        return Stream.of(
                Arguments.of(HttpStatus.UNAUTHORIZED, null, null,
                        GithubArchiveFailure.AUTHENTICATION_FAILED),
                Arguments.of(HttpStatus.NOT_FOUND, null, null,
                        GithubArchiveFailure.REPOSITORY_NOT_FOUND_OR_INACCESSIBLE),
                Arguments.of(HttpStatus.FORBIDDEN, null, null,
                        GithubArchiveFailure.GITHUB_UNAVAILABLE),
                Arguments.of(HttpStatus.FORBIDDEN, "X-RateLimit-Remaining", "0",
                        GithubArchiveFailure.RATE_LIMITED),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, null, null,
                        GithubArchiveFailure.RATE_LIMITED),
                Arguments.of(HttpStatus.BAD_GATEWAY, null, null,
                        GithubArchiveFailure.GITHUB_UNAVAILABLE)
        );
    }
}
