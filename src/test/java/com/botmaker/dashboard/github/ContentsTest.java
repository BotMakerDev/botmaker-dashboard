package com.botmaker.dashboard.github;

import com.botmaker.shared.github.GitHubError;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A listing: a missing directory is empty, and every other refusal reaches the tab as an error. */
class ContentsTest {

    @Test
    void aMissingDirectoryIsAnEmptyListing() {
        assertNull(Contents.nullWhenMissing(new CompletionException(
                new GitHubError(404, "Not Found", OptionalLong.empty()))));
    }

    @Test
    void aRateLimitIsAnErrorNotAnEmptyCatalog() {
        GitHubError limit = new GitHubError(403, "rate limit", OptionalLong.of(0));
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> Contents.nullWhenMissing(new CompletionException(limit)));
        assertSame(limit, thrown.getCause());

        GitHubError offline = new GitHubError(GitHubError.UNREACHABLE, "offline", OptionalLong.empty());
        assertSame(offline, assertThrows(CompletionException.class, () -> Contents.nullWhenMissing(offline))
                .getCause());
    }
}
