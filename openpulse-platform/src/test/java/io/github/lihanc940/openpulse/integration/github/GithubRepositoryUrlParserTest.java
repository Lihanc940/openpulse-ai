package io.github.lihanc940.openpulse.integration.github;

import io.github.lihanc940.openpulse.project.domain.GithubRepositoryCoordinates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class GithubRepositoryUrlParserTest {

    private final GithubRepositoryUrlParser parser = new GithubRepositoryUrlParser();

    @ParameterizedTest
    @MethodSource("supportedUrls")
    void parsesSupportedUrls(String url) {
        GithubRepositoryCoordinates coordinates = parser.parse(url);

        assertThat(coordinates.owner()).isEqualTo("OpenAI");
        assertThat(coordinates.repository()).isEqualTo("OpenAI");
        assertThat(coordinates.canonicalUrl()).isEqualTo("https://github.com/OpenAI/OpenAI");
    }

    @Test
    void preservesOwnerAndRepositoryCase() {
        GithubRepositoryCoordinates coordinates = parser.parse("https://github.com/MixedOwner/Mixed_Repo");

        assertThat(coordinates.owner()).isEqualTo("MixedOwner");
        assertThat(coordinates.repository()).isEqualTo("Mixed_Repo");
    }

    @ParameterizedTest
    @MethodSource("rejectedUrls")
    void rejectsInvalidAndDeceptiveUrls(String url) {
        GithubRepositoryException exception = catchThrowableOfType(
                GithubRepositoryException.class,
                () -> parser.parse(url)
        );

        assertThat(exception.failure()).isEqualTo(GithubRepositoryFailure.INVALID_REPOSITORY_URL);
        assertThat(exception.getMessage()).isEqualTo("GitHub repository URL is invalid.");
    }

    private static Stream<String> supportedUrls() {
        return Stream.of(
                "https://github.com/OpenAI/OpenAI",
                "https://github.com/OpenAI/OpenAI/",
                "https://github.com/OpenAI/OpenAI.git",
                "https://github.com/OpenAI/OpenAI?tab=readme",
                "https://github.com/OpenAI/OpenAI#readme",
                "https://github.com/OpenAI/OpenAI/?tab=readme#readme",
                "https://GITHUB.COM/OpenAI/OpenAI"
        );
    }

    private static Stream<Arguments> rejectedUrls() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("not a URI"),
                Arguments.of("http://github.com/owner/repo"),
                Arguments.of("ssh://git@github.com/owner/repo.git"),
                Arguments.of("git://github.com/owner/repo.git"),
                Arguments.of("git@github.com:owner/repo.git"),
                Arguments.of("https://github.com.example.com/owner/repo"),
                Arguments.of("https://example.com/github.com/owner/repo"),
                Arguments.of("https://github.com./owner/repo"),
                Arguments.of("https://github.com@evil.example/owner/repo"),
                Arguments.of("https://user@github.com/owner/repo"),
                Arguments.of("https://github.com:443/owner/repo"),
                Arguments.of("https://github.com"),
                Arguments.of("https://github.com/"),
                Arguments.of("https://github.com/owner"),
                Arguments.of("https://github.com//repo"),
                Arguments.of("https://github.com/owner/"),
                Arguments.of("https://github.com/owner/repo/issues/1"),
                Arguments.of("https://github.com/owner/repo//"),
                Arguments.of("https://github.com/owner/.git"),
                Arguments.of("https://github.com/owner/repo%2Fissues"),
                Arguments.of("https://github.com/owner%2Frepo/name"),
                Arguments.of("https://github.com/owner/repo%5Cissues"),
                Arguments.of("https://github.com/owner\\repo/name"),
                Arguments.of("https://github.com/owner/repo\n"),
                Arguments.of("https://github.com/owner/repo%00"),
                Arguments.of("https://github.com/owner/repo?tab=readme%00"),
                Arguments.of("https://github.com/owner/repo#readme%5Cfragment"),
                Arguments.of("https://github.com/-owner/repo"),
                Arguments.of("https://github.com/owner-/repo"),
                Arguments.of("https://github.com/owner/repo%25name")
        );
    }
}
