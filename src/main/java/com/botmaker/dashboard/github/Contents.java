package com.botmaker.dashboard.github;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * The contents API, which both readers of an entry file go through.
 *
 * <p>Two of them exist and they ask about the same bytes at different moments: {@link Queue} reads the entry
 * a pull request <i>adds</i>, at that pull request's own head, because the file does not exist on
 * {@code main} yet; {@link Catalog} reads the entry that is <i>merged</i>, on {@code main}. One ref apart,
 * one decode, so the decode lives here rather than in both.
 *
 * <p><b>The contents API rather than a raw URL</b>, for the reason it always was: a token still applies, so
 * one code path serves a public read, a rate-limited anonymous read and a private repository, and the
 * response carries the blob {@code sha} — which is what an edit has to send back to prove it is changing
 * the file it read.
 */
final class Contents {

    private Contents() {
    }

    /** {@code GET /repos/{repo}/contents/{path}} at a ref — a file object, or a directory's array. */
    static CompletableFuture<JsonNode> read(GitHubClient client, GitHubAuth auth,
                                            String repo, String path, String ref) {
        String url = GitHubConfig.API_BASE + "/repos/" + repo + "/contents/" + encodePath(path)
                + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);
        return client.get(url, token(auth));
    }

    /**
     * The decoded text of a file object, or {@code null} when it could not be read.
     *
     * <p>The contents API answers base64 with hard-wrapped lines, so the whitespace comes out before the
     * decode. Anything else — a missing {@code content} key, a body that is not base64 — is a read failure
     * rather than an exception, because every caller renders "could not be read" and none of them can act
     * on the distinction.
     */
    static String decode(JsonNode contents) {
        if (contents == null || !contents.hasNonNull("content")) {
            return null;
        }
        try {
            String encoded = contents.path("content").asText("").replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Percent-encodes each segment, leaving the separators alone.
     *
     * <p>{@code URLEncoder} on the whole path would turn every {@code /} into {@code %2F} and ask GitHub for
     * one file with slashes in its name. Entry filenames are ids and slugs — {@code com.botmaker.sdk.json},
     * {@code LiQiyeDev-botmaker-gamebot.json} — so today nothing needs escaping at all; this is here so that
     * the day one does, it is not a 404 nobody can explain.
     */
    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    /** The signed-in token, or {@code null}. Reads work without one; the writes do not. */
    static String token(GitHubAuth auth) {
        return auth.isAuthenticated() ? auth.token() : null;
    }
}
