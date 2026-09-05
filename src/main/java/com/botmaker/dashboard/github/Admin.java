package com.botmaker.dashboard.github;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

/**
 * Whether the signed-in account may act, answered by GitHub rather than by this app.
 *
 * <p>The question "who is an admin here" has one honest answer and it is already recorded on github.com:
 * <b>can this account push to {@code botmaker-plugin-registry}</b>. {@code GET /repos/{owner}/{repo}}
 * returns a {@code permissions} object for the authenticated user, and {@code permissions.push} is that
 * bit. Nothing else is consulted, and nothing is stored.
 *
 * <p><b>Why not an allowlist.</b> A list of logins in this repository would be a second statement of the
 * same fact, in a place with no way to notice when the first one changes — someone removed from the
 * organisation would keep the buttons, and someone added would not get them until a release. It would also
 * be a claim this app cannot make good on: approving a pull request needs the push right at the moment of
 * the call, so an app that enabled the button on its own list would produce a 403 in a dialog instead of a
 * disabled button. <b>This reveals a power GitHub enforces regardless; it never grants one.</b>
 *
 * <p>So a refusal here is not a security boundary and must not be written as one. The API call still
 * decides. What the verdict buys is that the operator learns they cannot merge before they have written a
 * review, rather than after.
 *
 * <p>Every failure is read-only with a reason. Not signed in, offline, a repository that does not exist
 * yet, a token whose scope was narrowed — each leaves the window perfectly usable for everything it
 * reads, which is most of it.
 */
public record Admin(boolean canWrite, String reason) {

    /** The registry is the subject because it is the repository whose pull requests this app merges. */
    private static final String REPO = GitHubConfig.REGISTRY_OWNER + "/" + GitHubConfig.REGISTRY_REPO;

    /** One line for the badge: what the operator may do, and — when they may not — why. */
    public String summary() {
        return canWrite ? "write · " + REPO : "read-only · " + reason;
    }

    public static CompletableFuture<Admin> probe(GitHubClient client, GitHubAuth auth) {
        if (!auth.isConfigured()) {
            return CompletableFuture.completedFuture(new Admin(false, "GitHub sign-in is not configured"));
        }
        if (!auth.isAuthenticated()) {
            return CompletableFuture.completedFuture(new Admin(false, "not signed in"));
        }
        String url = GitHubConfig.API_BASE + "/repos/" + REPO;
        return client.get(url, auth.token()).thenApply(Admin::read);
    }

    /**
     * Reads {@code permissions.push} out of the repository object.
     *
     * <p>A null node is every failure the client folds together — a non-200, a network error, a repository
     * the token cannot see. They are one outcome here on purpose: each of them means *this app cannot show
     * that the account may write*, and telling them apart would produce four sentences saying the same
     * thing about the same disabled buttons.
     */
    static Admin read(JsonNode repo) {
        if (repo == null) {
            return new Admin(false, "could not read " + REPO + " (offline, or the token cannot see it)");
        }
        JsonNode permissions = repo.path("permissions");
        if (permissions.isMissingNode() || !permissions.hasNonNull("push")) {
            // The field is absent for an unauthenticated read — which is what a token GitHub rejected
            // silently degrades to — rather than present and false.
            return new Admin(false, "GitHub reported no permissions for this account on " + REPO);
        }
        boolean push = permissions.path("push").asBoolean(false);
        return new Admin(push, push ? "" : "no push access to " + REPO);
    }
}
