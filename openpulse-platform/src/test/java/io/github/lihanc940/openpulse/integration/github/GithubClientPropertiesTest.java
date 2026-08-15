package io.github.lihanc940.openpulse.integration.github;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubClientPropertiesTest {

    @Test
    void acceptsValidConfigurationAndRedactsTokenFromString() {
        String runtimeToken = "runtime-" + UUID.randomUUID();
        GithubClientProperties properties = new GithubClientProperties(
                URI.create("https://api.github.com"),
                runtimeToken,
                Duration.ofSeconds(3),
                Duration.ofSeconds(10)
        );

        assertThat(properties.hasToken()).isTrue();
        assertThat(properties.toString())
                .contains("tokenConfigured=true")
                .doesNotContain(runtimeToken);
    }

    @Test
    void treatsNullTokenAsEmpty() {
        GithubClientProperties properties = new GithubClientProperties(
                URI.create("http://localhost:8080/fixture"),
                null,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );

        assertThat(properties.token()).isEmpty();
        assertThat(properties.hasToken()).isFalse();
    }

    @Test
    void rejectsHttpOutsideLoopbackAndTokensOverHttp() {
        assertThatThrownBy(() -> new GithubClientProperties(
                URI.create("http://api.example.test"),
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");

        assertThatThrownBy(() -> new GithubClientProperties(
                URI.create("http://localhost:8080/fixture"),
                "secret-token",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsControlCharactersInToken() {
        assertThatThrownBy(() -> new GithubClientProperties(
                URI.create("https://api.github.com"),
                "token\r\nInjected: value",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void rejectsInvalidApiBaseUrls() {
        assertThatThrownBy(() -> properties(URI.create("/relative"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(URI.create("file:///tmp/github"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(URI.create("https://user@api.github.com"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(URI.create("https://api.github.com?query=value"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(URI.create("https://api.github.com#fragment"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(URI.create("https://api.github.com/fixture%00"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> new GithubClientProperties(
                URI.create("https://api.github.com"),
                "",
                Duration.ZERO,
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connect timeout");

        assertThatThrownBy(() -> new GithubClientProperties(
                URI.create("https://api.github.com"),
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(-1)
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("read timeout");
    }

    private GithubClientProperties properties(URI apiBaseUrl, Duration timeout) {
        return new GithubClientProperties(apiBaseUrl, "", timeout, timeout);
    }
}
