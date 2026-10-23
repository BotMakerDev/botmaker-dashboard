package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.Publishers;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Whether the signed-in account is a catalog admin: its <b>account id</b> is in {@code admin} of
 * {@code publishers.json} on the catalog's {@code main} (2026-10-23).
 *
 * <p><b>The check's own list, not one of this app's.</b> The catalog's {@code EntryGate} lets an admin change
 * anyone's entry and the ranks, and it reads exactly that file by exactly that id. Reading the same file means the
 * dashboard offers the edits to the accounts the check will pass, and to no others. A login is never compared: it
 * can be renamed and then taken by somebody else.
 *
 * <p><b>Not {@code permissions.push} any more.</b> Push access merges by hand; nothing here merges since the catalog
 * merges itself. An account could have either without the other, and the check's answer is the one that counts.
 *
 * <p>A refusal here is not a security boundary: the check decides, and an edit by anyone else fails there with its
 * reason. Every failure is read-only with a sentence — not signed in, offline, a file that does not parse.
 *
 * @param login the signed-in login, blank when unknown; where a fork for an edit is made
 * @param id    its account id, {@code 0} when unknown
 */
public record Admin(boolean canWrite, String reason, String login, long id) {

    public Admin(boolean canWrite, String reason) {
        this(canWrite, reason, "", 0);
    }

    /** One line for the badge: what the operator may do, and — when they may not — why. */
    public String summary() {
        return canWrite ? "admin · " + Catalog.REPO : "read-only · " + reason;
    }

    public static CompletableFuture<Admin> probe(GitHubClient client, GitHubAuth auth) {
        if (!auth.isConfigured()) {
            return CompletableFuture.completedFuture(new Admin(false, "GitHub sign-in is not configured"));
        }
        if (!auth.isAuthenticated()) {
            return CompletableFuture.completedFuture(new Admin(false, "not signed in"));
        }
        return client.get(GitHubConfig.API_BASE + "/user", auth.token())
                .thenCombine(PublishersFile.fetch(client, auth),
                        (user, file) -> read(user, file.map(PublishersFile::publishers)));
    }

    /**
     * The verdict for {@code GET /user}'s answer against {@code publishers.json}. A null user is every failure the
     * client folds together; an empty file is one that could not be read or is not the schema.
     */
    static Admin read(JsonNode user, Optional<Publishers> publishers) {
        if (user == null || user.path("id").asLong(0) <= 0) {
            return new Admin(false, "could not read the signed-in GitHub account (offline, or the token expired)");
        }
        String login = user.path("login").asText("");
        long id = user.path("id").asLong();
        if (publishers.isEmpty()) {
            return new Admin(false, "could not read " + Publishers.FILE + " on " + Catalog.REPO, login, id);
        }
        return publishers.get().isAdmin(id)
                ? new Admin(true, "", login, id)
                : new Admin(false, login + " is not an admin in " + Publishers.FILE, login, id);
    }
}
