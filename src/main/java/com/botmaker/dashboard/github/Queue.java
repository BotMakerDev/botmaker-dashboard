package com.botmaker.dashboard.github;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The submission queue: every open pull request on the two data repositories, and the four things an
 * operator does with one.
 *
 * <p>Two repositories and no more, named from {@link GitHubConfig} rather than typed here: a plugin is
 * submitted to {@code botmaker-plugin-registry} and a bot to {@code botmaker-gallery}, and those names
 * already have one owner in {@code botmaker-shared}.
 *
 * <p><b>Reads work signed out.</b> Both repositories are public, so the queue lists for anyone; the token
 * is passed when there is one, which lifts the anonymous rate limit and is required for the writes. The
 * writes are gated on {@link Admin} — but the gate is a courtesy, since GitHub enforces the same rule and
 * would answer 403. This class therefore never checks a permission itself; it only makes the call.
 *
 * <p>Everything here is a read of GitHub's own answer. There is no verdict computed in this app: the check
 * runs are the registry CI's, which runs {@code RegistryGate} out of {@code botmaker-cli}'s main artifact.
 */
public final class Queue {

    /** {@code owner/name} of the plugin registry — where a plugin submission lands. */
    public static final String REGISTRY = GitHubConfig.REGISTRY_OWNER + "/" + GitHubConfig.REGISTRY_REPO;

    /** {@code owner/name} of the gallery — where a published bot's entry lands. */
    public static final String GALLERY = GitHubConfig.INDEX_OWNER + "/" + GitHubConfig.INDEX_REPO;

    public static final List<String> REPOS = List.of(REGISTRY, GALLERY);

    private Queue() {
    }

    /**
     * Every open pull request on both repositories, with its files and its check-run verdict.
     *
     * <p>One round trip per pull request for the files and one for the checks, run concurrently and joined
     * at the end. That is 2n+2 requests for n submissions, which is the right trade while n is small — and
     * n is small by construction: this is a queue somebody empties, not a feed.
     */
    public static CompletableFuture<List<Submission>> open(GitHubClient client, GitHubAuth auth) {
        String token = token(auth);
        List<CompletableFuture<List<Submission>>> perRepo = REPOS.stream()
                .map(repo -> openIn(client, token, repo))
                .toList();
        return CompletableFuture.allOf(perRepo.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<Submission> all = new ArrayList<>();
                    perRepo.forEach(f -> all.addAll(f.join()));
                    return List.copyOf(all);
                });
    }

    private static CompletableFuture<List<Submission>> openIn(GitHubClient client, String token, String repo) {
        String url = GitHubConfig.API_BASE + "/repos/" + repo + "/pulls?state=open&per_page=50";
        return client.get(url, token).thenCompose(prs -> {
            if (prs == null || !prs.isArray() || prs.isEmpty()) {
                return CompletableFuture.completedFuture(List.<Submission>of());
            }
            List<CompletableFuture<Submission>> each = new ArrayList<>();
            for (JsonNode pr : prs) {
                each.add(detail(client, token, repo, pr));
            }
            return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> each.stream().map(CompletableFuture::join).toList());
        });
    }

    private static CompletableFuture<Submission> detail(GitHubClient client, String token,
                                                        String repo, JsonNode pr) {
        int number = pr.path("number").asInt();
        String sha = pr.path("head").path("sha").asText("");
        String base = GitHubConfig.API_BASE + "/repos/" + repo;

        CompletableFuture<JsonNode> files = client.get(base + "/pulls/" + number + "/files?per_page=100", token);
        CompletableFuture<JsonNode> checks = sha.isBlank()
                ? CompletableFuture.completedFuture(null)
                : client.get(base + "/commits/" + sha + "/check-runs", token);

        return files.thenCombine(checks, (fileArray, checkRuns) ->
                Submission.read(repo, pr, Submission.filenames(fileArray), Checks.of(checkRuns)));
    }

    /**
     * The text of the entry file a submission adds, read at the pull request's own head commit.
     *
     * <p>At the head rather than on {@code main} for the obvious reason — the file does not exist on
     * {@code main} yet, that being the whole point of the submission. {@link Catalog} asks for the same
     * bytes on {@code main}, which is the only difference between the two reads, so the request and the
     * base64 decode are {@link Contents}'.
     */
    public static CompletableFuture<String> entry(GitHubClient client, GitHubAuth auth, Submission submission) {
        String path = submission.entryFile().orElse(null);
        if (path == null) {
            return CompletableFuture.completedFuture(null);
        }
        return Contents.read(client, auth, submission.repo(), path, submission.headSha())
                .thenApply(Contents::decode);
    }

    /** Approve, as a review with no body — the ordinary "this is fine, merge it". */
    public static CompletableFuture<JsonNode> approve(GitHubClient client, GitHubAuth auth,
                                                      Submission submission) {
        return review(client, auth, submission, "APPROVE", "");
    }

    /**
     * Request changes, with the comment the submitter will read.
     *
     * <p>A body is required by GitHub for {@code REQUEST_CHANGES}, and it should be: a refusal with no
     * sentence attached is the worst outcome for somebody whose first contact with this project is a
     * submission.
     */
    public static CompletableFuture<JsonNode> requestChanges(GitHubClient client, GitHubAuth auth,
                                                             Submission submission, String comment) {
        return review(client, auth, submission, "REQUEST_CHANGES", comment);
    }

    private static CompletableFuture<JsonNode> review(GitHubClient client, GitHubAuth auth,
                                                      Submission submission, String event, String body) {
        String url = GitHubConfig.API_BASE + "/repos/" + submission.repo()
                + "/pulls/" + submission.number() + "/reviews";
        return client.post(url, Map.of("event", event, "body", body), token(auth));
    }

    /**
     * Merge, squashed.
     *
     * <p>Squash rather than a merge commit because an entry file's history is not interesting and a
     * submission is routinely several fixup commits deep after the gate has been round once. The commit
     * title names the entry, so {@code git log} on the registry reads as a list of what was admitted.
     */
    public static CompletableFuture<JsonNode> merge(GitHubClient client, GitHubAuth auth,
                                                    Submission submission) {
        String url = GitHubConfig.API_BASE + "/repos/" + submission.repo()
                + "/pulls/" + submission.number() + "/merge";
        String title = submission.claimedId()
                .map(id -> "add " + id + " (#" + submission.number() + ")")
                .orElse(submission.title() + " (#" + submission.number() + ")");
        return client.put(url, Map.of("merge_method", "squash", "commit_title", title), token(auth));
    }

    /** {@link Contents}', so "reads work signed out, writes do not" is one line and not two. */
    private static String token(GitHubAuth auth) {
        return Contents.token(auth);
    }
}
