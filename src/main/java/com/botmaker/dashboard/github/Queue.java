package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The queue: every open pull request on the catalog, read-only (2026-10-23).
 *
 * <p><b>Nobody reviews a catalog pull request.</b> Its {@code validate} check decides, and {@code automerge.yml}
 * merges what passed — an entry, or an admin's rank change — or labels it {@code waiting} for the author's daily
 * limit. So the operator's Approve, Request changes and Merge are gone: this lists what is in flight and why it
 * has not merged yet, and the pull request on GitHub is where anything else is said.
 *
 * <p><b>Reads work signed out.</b> The catalog is public; the token is passed when there is one, which lifts the
 * anonymous rate limit.
 */
public final class Queue {

    /** {@code owner/name} of the catalog. */
    public static final String REPO = Catalog.REPO;

    /** The catalog's check: the one verdict the queue shows. */
    public static final String CHECK = "validate";

    private Queue() {
    }

    /**
     * Every open pull request, with its files and its {@code validate} verdict.
     *
     * <p>One round trip per pull request for the files and one for the checks, run concurrently and joined at the
     * end: 2n+1 requests for n open, which is small by construction — the machine merges what passes.
     */
    public static CompletableFuture<List<Submission>> open(GitHubClient client, GitHubAuth auth) {
        String token = Contents.token(auth);
        String url = GitHubConfig.API_BASE + "/repos/" + REPO + "/pulls?state=open&per_page=50";
        // getOrFail, not get: a refused listing is an error the tab shows, never "0 open" (2026-09-29).
        return client.getOrFail(url, token).thenCompose(prs -> {
            if (prs == null || !prs.isArray() || prs.isEmpty()) {
                return CompletableFuture.completedFuture(List.<Submission>of());
            }
            List<CompletableFuture<Submission>> each = new ArrayList<>();
            for (JsonNode pr : prs) {
                each.add(detail(client, token, pr));
            }
            return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> each.stream().map(CompletableFuture::join).toList());
        });
    }

    private static CompletableFuture<Submission> detail(GitHubClient client, String token, JsonNode pr) {
        int number = pr.path("number").asInt();
        String sha = pr.path("head").path("sha").asText("");
        String base = GitHubConfig.API_BASE + "/repos/" + REPO;

        CompletableFuture<JsonNode> files = client.get(base + "/pulls/" + number + "/files?per_page=100", token);
        CompletableFuture<JsonNode> checks = sha.isBlank()
                ? CompletableFuture.completedFuture(null)
                : client.get(base + "/commits/" + sha + "/check-runs?check_name=" + CHECK, token);

        return files.thenCombine(checks, (fileArray, checkRuns) ->
                Submission.read(REPO, pr, Submission.filenames(fileArray), Checks.of(checkRuns)));
    }

    /**
     * The text of the catalog file a pull request changes, read at its own head commit — the file as it would
     * merge; {@code null} for a removal or a pull request of no single catalog file.
     */
    public static CompletableFuture<String> entry(GitHubClient client, GitHubAuth auth, Submission submission) {
        String path = submission.entryFile().orElse(null);
        if (path == null) {
            return CompletableFuture.completedFuture(null);
        }
        return Contents.read(client, auth, submission.repo(), path, submission.headSha())
                .thenApply(Contents::decode);
    }

    /** One top-level field of a catalog file, as the Queue tab's detail lists it. */
    public record Field(String name, String value) {
    }

    /**
     * The top-level fields of a catalog file's text, in the file's order: a value as written, a list of accounts
     * ({@code publishers.json}) as their logins. Empty when the text is not a JSON object — the raw text beside it
     * still shows; what a field must hold is the check's, so nothing here is refused.
     */
    public static List<Field> fields(String text) {
        JsonNode root;
        try {
            root = text == null ? null : CatalogJson.strict().readTree(text);
        } catch (Exception e) {
            return List.of();
        }
        if (root == null || !root.isObject()) {
            return List.of();
        }
        List<Field> fields = new ArrayList<>();
        root.properties().forEach(field -> fields.add(new Field(field.getKey(), value(field.getValue()))));
        return List.copyOf(fields);
    }

    private static String value(JsonNode node) {
        if (!node.isArray()) {
            return node.isValueNode() ? node.asText() : node.toString();
        }
        List<String> items = new ArrayList<>();
        node.forEach(item -> items.add(item.has("login") ? item.path("login").asText()
                : item.isValueNode() ? item.asText() : item.toString()));
        return String.join(", ", items);
    }
}
