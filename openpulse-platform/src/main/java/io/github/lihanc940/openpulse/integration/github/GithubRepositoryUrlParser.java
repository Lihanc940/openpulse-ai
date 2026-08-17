package io.github.lihanc940.openpulse.integration.github;

import io.github.lihanc940.openpulse.project.domain.GithubRepositoryCoordinates;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

@Component
public class GithubRepositoryUrlParser {

    private static final String GITHUB_HOST = "github.com";
    private static final String GIT_SUFFIX = ".git";

    public GithubRepositoryCoordinates parse(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()
                || repositoryUrl.indexOf('\\') >= 0
                || containsControlCharacter(repositoryUrl)) {
            throw invalidUrl();
        }

        URI uri;
        try {
            uri = URI.create(repositoryUrl);
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }

        validateDecodedCharacters(uri);
        validateOrigin(uri);
        String[] segments = repositorySegments(uri);
        String owner = segments[0];
        String repository = removeGitSuffix(segments[1]);
        validateOwner(owner);
        validateRepository(repository);

        return new GithubRepositoryCoordinates(
                owner,
                repository,
                "https://github.com/" + owner + "/" + repository
        );
    }

    private void validateOrigin(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !GITHUB_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1) {
            throw invalidUrl();
        }
    }

    private void validateDecodedCharacters(URI uri) {
        if (containsUnsafeDecodedCharacter(uri.getPath())
                || containsUnsafeDecodedCharacter(uri.getQuery())
                || containsUnsafeDecodedCharacter(uri.getFragment())) {
            throw invalidUrl();
        }
    }

    private String[] repositorySegments(URI uri) {
        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith("/") || containsEncodedSeparator(rawPath)) {
            throw invalidUrl();
        }

        String path = uri.getPath();
        if (path == null) {
            throw invalidUrl();
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.length() <= 1) {
            throw invalidUrl();
        }
        String[] segments = path.substring(1).split("/", -1);
        if (segments.length != 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
            throw invalidUrl();
        }
        return segments;
    }

    private String removeGitSuffix(String repository) {
        if (repository.endsWith(GIT_SUFFIX)) {
            return repository.substring(0, repository.length() - GIT_SUFFIX.length());
        }
        return repository;
    }

    private void validateOwner(String owner) {
        if (owner.length() > 39
                || owner.startsWith("-")
                || owner.endsWith("-")
                || !owner.codePoints().allMatch(this::isOwnerCharacter)) {
            throw invalidUrl();
        }
    }

    private void validateRepository(String repository) {
        if (repository.isEmpty()
                || repository.length() > 100
                || ".".equals(repository)
                || "..".equals(repository)
                || !repository.codePoints().allMatch(this::isRepositoryCharacter)) {
            throw invalidUrl();
        }
    }

    private boolean isOwnerCharacter(int character) {
        return isAsciiLetterOrDigit(character) || character == '-';
    }

    private boolean isRepositoryCharacter(int character) {
        return isAsciiLetterOrDigit(character) || character == '-' || character == '_' || character == '.';
    }

    private boolean isAsciiLetterOrDigit(int character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9';
    }

    private boolean containsEncodedSeparator(String rawPath) {
        String normalized = rawPath.toLowerCase(Locale.ROOT);
        return normalized.contains("%2f") || normalized.contains("%5c");
    }

    private boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private boolean containsUnsafeDecodedCharacter(String value) {
        return value != null && (value.indexOf('\\') >= 0 || containsControlCharacter(value));
    }

    private GithubRepositoryException invalidUrl() {
        return new GithubRepositoryException(
                GithubRepositoryFailure.INVALID_REPOSITORY_URL,
                "GitHub repository URL is invalid."
        );
    }
}
