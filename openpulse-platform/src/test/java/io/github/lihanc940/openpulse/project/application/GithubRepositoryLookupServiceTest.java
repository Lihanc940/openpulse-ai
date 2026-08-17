package io.github.lihanc940.openpulse.project.application;

import io.github.lihanc940.openpulse.integration.github.GithubRepositoryClient;
import io.github.lihanc940.openpulse.integration.github.GithubRepositoryException;
import io.github.lihanc940.openpulse.integration.github.GithubRepositoryFailure;
import io.github.lihanc940.openpulse.integration.github.GithubRepositoryUrlParser;
import io.github.lihanc940.openpulse.project.domain.GithubRepositoryCoordinates;
import io.github.lihanc940.openpulse.project.domain.GithubRepositoryMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GithubRepositoryLookupServiceTest {

    private final GithubRepositoryUrlParser urlParser = mock(GithubRepositoryUrlParser.class);
    private final GithubRepositoryClient repositoryClient = mock(GithubRepositoryClient.class);
    private final GithubRepositoryLookupService service = new GithubRepositoryLookupService(
            urlParser,
            repositoryClient
    );

    @Test
    void parsesUrlBeforeFetchingAndReturnsInternalMetadata() {
        String repositoryUrl = "https://github.com/OpenAI/OpenAI";
        GithubRepositoryCoordinates coordinates = new GithubRepositoryCoordinates(
                "OpenAI",
                "OpenAI",
                repositoryUrl
        );
        GithubRepositoryMetadata metadata = new GithubRepositoryMetadata(
                "OpenAI",
                "OpenAI",
                "OpenAI/OpenAI",
                repositoryUrl,
                "description",
                "main",
                "Python",
                1,
                2,
                false
        );
        when(urlParser.parse(repositoryUrl)).thenReturn(coordinates);
        when(repositoryClient.fetch(coordinates)).thenReturn(metadata);

        GithubRepositoryMetadata result = service.lookup(repositoryUrl);

        assertThat(result).isSameAs(metadata);
        InOrder inOrder = inOrder(urlParser, repositoryClient);
        inOrder.verify(urlParser).parse(repositoryUrl);
        inOrder.verify(repositoryClient).fetch(coordinates);
    }

    @Test
    void doesNotCallClientWhenUrlParsingFails() {
        String invalidUrl = "https://github.com.example.com/owner/repo";
        when(urlParser.parse(invalidUrl)).thenThrow(new GithubRepositoryException(
                GithubRepositoryFailure.INVALID_REPOSITORY_URL,
                "GitHub repository URL is invalid."
        ));

        assertThatThrownBy(() -> service.lookup(invalidUrl))
                .isInstanceOf(GithubRepositoryException.class)
                .extracting(exception -> ((GithubRepositoryException) exception).failure())
                .isEqualTo(GithubRepositoryFailure.INVALID_REPOSITORY_URL);
        verifyNoInteractions(repositoryClient);
    }
}
