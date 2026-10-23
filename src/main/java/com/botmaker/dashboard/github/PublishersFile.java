package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.cli.catalog.Publishers;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code publishers.json} on the catalog's {@code main}: the ranks the catalog's check reads, so the ones the
 * dashboard reads too ({@link Admin}, {@link Authors}, {@link CatalogEdits#setRank}). Read through the contents
 * API, never the raw URL: a rank edit has to send back the blob {@code sha} it read, and a raw copy may be minutes
 * old.
 *
 * @param sha the blob sha on {@code main}, which a rank change sends back
 */
public record PublishersFile(Publishers publishers, String sha) {

    /** The file on {@code main}, or empty when it could not be read or is not the schema. */
    public static CompletableFuture<Optional<PublishersFile>> fetch(GitHubClient client, GitHubAuth auth) {
        return Contents.read(client, auth, Catalog.REPO, Publishers.FILE, GitHubConfig.CATALOG_BRANCH)
                .thenApply(contents -> parse(Contents.decode(contents))
                        .map(p -> new PublishersFile(p, contents.path("sha").asText(""))));
    }

    /** The file's text as the cli's record; empty when it is not {@code publishers.json}'s schema. */
    static Optional<Publishers> parse(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(CatalogJson.strict().readValue(json, Publishers.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
