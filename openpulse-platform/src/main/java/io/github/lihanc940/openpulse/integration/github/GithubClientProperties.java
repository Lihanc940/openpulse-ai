package io.github.lihanc940.openpulse.integration.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

@ConfigurationProperties("openpulse.github")
public record GithubClientProperties(
        URI apiBaseUrl,
        String token,
        Duration connectTimeout,
        Duration readTimeout
) {

    public GithubClientProperties {
        validateApiBaseUrl(apiBaseUrl);
        requirePositive(connectTimeout, "connect timeout");
        requirePositive(readTimeout, "read timeout");
        String configuredToken = token == null ? "" : token;
        validateTokenTransport(apiBaseUrl, configuredToken);
        token = configuredToken.isBlank() ? "" : configuredToken;
    }

    public boolean hasToken() {
        return !token.isBlank();
    }

    @Override
    public String toString() {
        return "GithubClientProperties[apiBaseUrl=" + apiBaseUrl
                + ", tokenConfigured=" + hasToken()
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static void validateApiBaseUrl(URI apiBaseUrl) {
        if (apiBaseUrl == null || !apiBaseUrl.isAbsolute() || apiBaseUrl.getHost() == null) {
            throw new IllegalArgumentException("GitHub API base URL must be an absolute HTTP URI with a host");
        }
        String scheme = apiBaseUrl.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("GitHub API base URL must use HTTP or HTTPS");
        }
        if (apiBaseUrl.getUserInfo() != null
                || apiBaseUrl.getRawQuery() != null
                || apiBaseUrl.getRawFragment() != null) {
            throw new IllegalArgumentException("GitHub API base URL must not contain user-info, query, or fragment");
        }
        String rawPath = apiBaseUrl.getRawPath();
        String decodedPath = apiBaseUrl.getPath();
        if (rawPath != null && (rawPath.indexOf('\\') >= 0 || containsControlCharacter(rawPath))
                || decodedPath != null && (decodedPath.indexOf('\\') >= 0 || containsControlCharacter(decodedPath))) {
            throw new IllegalArgumentException("GitHub API base URL path is invalid");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("GitHub " + name + " must be positive");
        }
    }

    private static void validateTokenTransport(URI apiBaseUrl, String token) {
        if (containsControlCharacter(token)) {
            throw new IllegalArgumentException("GitHub token must not contain control characters");
        }
        if (!"http".equalsIgnoreCase(apiBaseUrl.getScheme())) {
            return;
        }
        if (!isLoopbackHost(apiBaseUrl.getHost())) {
            throw new IllegalArgumentException(
                    "GitHub HTTP API base URL is only allowed for loopback test fixtures"
            );
        }
        if (!token.isEmpty()) {
            throw new IllegalArgumentException("GitHub token requires an HTTPS API base URL");
        }
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
