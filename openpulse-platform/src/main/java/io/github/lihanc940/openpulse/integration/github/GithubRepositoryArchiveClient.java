package io.github.lihanc940.openpulse.integration.github;

import io.github.lihanc940.openpulse.project.domain.GithubRepositoryMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

@Component
public class GithubRepositoryArchiveClient {

    static final String API_VERSION = "2026-03-10";
    static final String USER_AGENT = "openpulse-ai";
    static final String ACCEPT = "application/vnd.github+json";
    private static final String PRODUCTION_API_HOST = "api.github.com";
    private static final String PRODUCTION_DOWNLOAD_HOST = "codeload.github.com";
    private static final int BUFFER_SIZE = 8192;

    private final GithubClientProperties clientProperties;
    private final GithubArchiveProperties archiveProperties;
    private final RestClient restClient;

    @Autowired
    public GithubRepositoryArchiveClient(
            GithubClientProperties clientProperties,
            GithubArchiveProperties archiveProperties
    ) {
        this(clientProperties, archiveProperties, createRestClient(clientProperties, archiveProperties));
    }

    GithubRepositoryArchiveClient(
            GithubClientProperties clientProperties,
            GithubArchiveProperties archiveProperties,
            RestClient restClient
    ) {
        this.clientProperties = clientProperties;
        this.archiveProperties = archiveProperties;
        this.restClient = restClient;
    }

    public long download(GithubRepositoryMetadata metadata, Path archivePath) {
        validateMetadata(metadata);
        URI requestUri = UriComponentsBuilder.fromUri(clientProperties.apiBaseUrl())
                .pathSegment("repos", metadata.owner(), metadata.name(), "zipball")
                .build()
                .encode()
                .toUri();

        try {
            return restClient.get()
                    .uri(requestUri)
                    .headers(this::addFirstHopHeaders)
                    .exchange((request, response) -> handleFirstHop(response, archivePath));
        } catch (GithubArchiveException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw mapResourceFailure(exception);
        } catch (RestClientException exception) {
            throw failure(GithubArchiveFailure.GITHUB_UNAVAILABLE, "GitHub archive is unavailable.");
        }
    }

    private static RestClient createRestClient(
            GithubClientProperties clientProperties,
            GithubArchiveProperties archiveProperties
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(clientProperties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(archiveProperties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private void addFirstHopHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.ACCEPT, ACCEPT);
        headers.set("X-GitHub-Api-Version", API_VERSION);
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        if (clientProperties.hasToken()) {
            headers.setBearerAuth(clientProperties.token());
        }
    }

    private long handleFirstHop(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            Path archivePath
    ) {
        HttpStatusCode statusCode = readStatus(response);
        int status = statusCode.value();
        if (isRedirect(status)) {
            URI redirectUri = validateRedirect(response.getHeaders().getFirst(HttpHeaders.LOCATION));
            return downloadSecondHop(redirectUri, archivePath);
        }
        throw statusFailure(statusCode, response.getHeaders(), true);
    }

    private long downloadSecondHop(URI redirectUri, Path archivePath) {
        return restClient.get()
                .uri(redirectUri)
                .exchange((request, response) -> handleSecondHop(response, archivePath));
    }

    private long handleSecondHop(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            Path archivePath
    ) {
        HttpStatusCode statusCode = readStatus(response);
        int status = statusCode.value();
        if (isRedirect(status)) {
            throw failure(
                    GithubArchiveFailure.UNTRUSTED_REDIRECT,
                    "GitHub archive returned more than one redirect."
            );
        }
        if (status != 200) {
            throw statusFailure(statusCode, response.getHeaders(), false);
        }
        rejectOversizedContentLength(response.getHeaders());
        return streamArchive(response, archivePath);
    }

    private HttpStatusCode readStatus(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response
    ) {
        try {
            return response.getStatusCode();
        } catch (IOException exception) {
            throw new GithubArchiveException(
                    GithubArchiveFailure.GITHUB_UNAVAILABLE,
                    "GitHub archive response could not be read.",
                    exception
            );
        }
    }

    private URI validateRedirect(String location) {
        if (location == null || location.isBlank()
                || location.indexOf('\\') >= 0
                || containsControlCharacter(location)) {
            throw untrustedRedirect();
        }

        URI target;
        try {
            target = URI.create(location);
        } catch (IllegalArgumentException exception) {
            throw untrustedRedirect();
        }
        if (!target.isAbsolute() || target.getHost() == null
                || target.getUserInfo() != null || target.getRawFragment() != null
                || containsUnsafeUriCharacters(target)) {
            throw untrustedRedirect();
        }

        URI base = clientProperties.apiBaseUrl();
        if (isLoopbackHost(base.getHost())) {
            if (!sameOrigin(base, target)) {
                throw untrustedRedirect();
            }
            return target;
        }

        if (!"https".equalsIgnoreCase(base.getScheme())
                || !PRODUCTION_API_HOST.equalsIgnoreCase(base.getHost())
                || !"https".equalsIgnoreCase(target.getScheme())
                || !PRODUCTION_DOWNLOAD_HOST.equalsIgnoreCase(target.getHost())
                || !usesDefaultPort(target)) {
            throw untrustedRedirect();
        }
        return target;
    }

    private long streamArchive(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            Path archivePath
    ) {
        boolean copyCompleted = false;
        try (InputStream input = response.getBody();
             OutputStream output = openArchiveFile(archivePath)) {
            long downloadedBytes = copyWithLimit(input, output, archiveProperties.maxArchiveBytes());
            copyCompleted = true;
            return downloadedBytes;
        } catch (GithubArchiveException exception) {
            throw exception;
        } catch (SocketTimeoutException exception) {
            throw failure(GithubArchiveFailure.DOWNLOAD_TIMEOUT, "GitHub archive download timed out.");
        } catch (ClosedByInterruptException exception) {
            Thread.currentThread().interrupt();
            throw new GithubArchiveException(
                    GithubArchiveFailure.INTERRUPTED,
                    "GitHub archive download was interrupted."
            );
        } catch (InterruptedIOException exception) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new GithubArchiveException(
                        GithubArchiveFailure.INTERRUPTED,
                        "GitHub archive download was interrupted."
                );
            }
            throw failure(GithubArchiveFailure.DOWNLOAD_TIMEOUT, "GitHub archive download timed out.");
        } catch (IOException exception) {
            if (copyCompleted) {
                throw failure(
                        GithubArchiveFailure.ARCHIVE_WRITE_FAILED,
                        "Failed to finish writing the temporary archive file."
                );
            }
            throw new GithubArchiveException(
                    GithubArchiveFailure.GITHUB_UNAVAILABLE,
                    "GitHub archive download failed."
            );
        }
    }

    private OutputStream openArchiveFile(Path archivePath) {
        try {
            return Files.newOutputStream(
                    archivePath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new GithubArchiveException(
                    GithubArchiveFailure.ARCHIVE_WRITE_FAILED,
                    "Failed to create the temporary archive file."
            );
        }
    }

    private long copyWithLimit(InputStream input, OutputStream output, long maximumBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        while (true) {
            long remaining = maximumBytes - total;
            int allowedRead = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
            int read = input.read(buffer, 0, allowedRead);
            if (read == -1) {
                return total;
            }
            if (read == 0) {
                continue;
            }
            if (total + read > maximumBytes) {
                throw failure(
                        GithubArchiveFailure.ARCHIVE_TOO_LARGE,
                        "GitHub archive exceeds the configured compressed size limit."
                );
            }
            try {
                output.write(buffer, 0, read);
            } catch (IOException exception) {
                throw new GithubArchiveException(
                        GithubArchiveFailure.ARCHIVE_WRITE_FAILED,
                        "Failed to write the temporary archive file."
                );
            }
            total += read;
        }
    }

    private void rejectOversizedContentLength(HttpHeaders headers) {
        String contentLength = headers.getFirst(HttpHeaders.CONTENT_LENGTH);
        if (contentLength == null) {
            return;
        }
        try {
            long declaredBytes = Long.parseLong(contentLength.strip());
            if (declaredBytes > archiveProperties.maxArchiveBytes()) {
                throw failure(
                        GithubArchiveFailure.ARCHIVE_TOO_LARGE,
                        "GitHub archive exceeds the configured compressed size limit."
                );
            }
        } catch (NumberFormatException ignored) {
            // A missing, negative, or malformed value is not trusted; actual bytes are still counted.
        }
    }

    private GithubArchiveException statusFailure(
            HttpStatusCode status,
            HttpHeaders headers,
            boolean expectedRedirect
    ) {
        int value = status.value();
        if (value == 401) {
            return failure(GithubArchiveFailure.AUTHENTICATION_FAILED, "GitHub authentication failed.");
        }
        if (value == 404) {
            return failure(
                    GithubArchiveFailure.REPOSITORY_NOT_FOUND_OR_INACCESSIBLE,
                    "GitHub repository archive was not found or is inaccessible."
            );
        }
        if (value == 429 || (value == 403 && hasRateLimitSignal(headers))) {
            return failure(GithubArchiveFailure.RATE_LIMITED, "GitHub rate limit was exceeded.");
        }
        if (expectedRedirect && value >= 200 && value < 300) {
            return failure(
                    GithubArchiveFailure.UNTRUSTED_REDIRECT,
                    "GitHub archive response did not provide the required redirect."
            );
        }
        return failure(GithubArchiveFailure.GITHUB_UNAVAILABLE, "GitHub archive is unavailable.");
    }

    private GithubArchiveException mapResourceFailure(ResourceAccessException exception) {
        if (hasCause(exception, InterruptedException.class)
                || hasCause(exception, ClosedByInterruptException.class)) {
            Thread.currentThread().interrupt();
            return new GithubArchiveException(
                    GithubArchiveFailure.INTERRUPTED,
                    "GitHub archive download was interrupted."
            );
        }
        if (hasCause(exception, HttpTimeoutException.class)
                || hasCause(exception, SocketTimeoutException.class)
                || hasCause(exception, InterruptedIOException.class)) {
            return new GithubArchiveException(
                    GithubArchiveFailure.DOWNLOAD_TIMEOUT,
                    "GitHub archive download timed out."
            );
        }
        return new GithubArchiveException(
                GithubArchiveFailure.GITHUB_UNAVAILABLE,
                "GitHub archive is unavailable."
        );
    }

    private void validateMetadata(GithubRepositoryMetadata metadata) {
        if (metadata == null
                || isUnsafeSegment(metadata.owner())
                || isUnsafeSegment(metadata.name())
                || !metadata.fullName().equals(metadata.owner() + "/" + metadata.name())
                || !metadata.canonicalUrl().equals(
                        "https://github.com/" + metadata.owner() + "/" + metadata.name()
                )) {
            throw failure(
                    GithubArchiveFailure.INVALID_REPOSITORY_METADATA,
                    "GitHub repository metadata is invalid."
            );
        }
    }

    private boolean isUnsafeSegment(String value) {
        return value == null || value.isBlank()
                || value.equals(".") || value.equals("..")
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf('%') >= 0 || containsControlCharacter(value);
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean usesDefaultPort(URI uri) {
        return uri.getPort() == -1 || uri.getPort() == effectivePort(URI.create(uri.getScheme() + "://host"));
    }

    private boolean hasRateLimitSignal(HttpHeaders headers) {
        return "0".equals(headers.getFirst("X-RateLimit-Remaining"))
                || !isBlank(headers.getFirst(HttpHeaders.RETRY_AFTER));
    }

    private boolean containsUnsafeUriCharacters(URI uri) {
        String rawPath = uri.getRawPath();
        String path = uri.getPath();
        String rawQuery = uri.getRawQuery();
        String query = uri.getQuery();
        return hasBackslashOrControl(rawPath)
                || hasBackslashOrControl(path)
                || hasBackslashOrControl(rawQuery)
                || hasBackslashOrControl(query);
    }

    private boolean hasBackslashOrControl(String value) {
        return value != null && (value.indexOf('\\') >= 0 || containsControlCharacter(value));
    }

    private boolean containsControlCharacter(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }

    private boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized);
    }

    private boolean isRedirect(int status) {
        return status >= 300 && status < 400;
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

    private GithubArchiveException untrustedRedirect() {
        return failure(
                GithubArchiveFailure.UNTRUSTED_REDIRECT,
                "GitHub archive redirect target is not trusted."
        );
    }

    private GithubArchiveException failure(GithubArchiveFailure failure, String message) {
        return new GithubArchiveException(failure, message);
    }
}
