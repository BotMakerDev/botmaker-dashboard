package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.cli.catalog.Publishers;
import com.botmaker.cli.catalog.Rank;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

/**
 * An admin's three edits to the catalog (2026-10-23): remove one approved release of an entry (2026-10-24, which
 * replaced listing another tag), remove an entry, change an author's rank. Each is <b>one pull request changing one file</b>, which the catalog's {@code validate} checks and
 * {@code automerge.yml} merges: the check passes an admin's change to anyone's entry, and a {@code publishers.json}
 * change only from an admin. Nothing here merges, and nothing here validates.
 *
 * <p><b>Where the branch is.</b> On the catalog itself for an account that can push there, otherwise on the
 * account's fork of it, made when there is none — Studio's {@code CatalogListing} shape. The branch is
 * {@code dashboard/<verb>-<slug>-<utc stamp>}, new each time, so a second edit while the first is open is a
 * second pull request rather than a 422.
 *
 * <p><b>The file is sent back with the blob sha it was read at</b>, from {@code main}, so GitHub refuses the write
 * when the file moved in between rather than overwriting what somebody else just merged.
 *
 * <p>Each call runs its requests one after another on the common pool and answers once: a fork is made in the
 * background by GitHub and has to be waited for, which a chain of futures says less plainly.
 */
public final class CatalogEdits {

    /**
     * A pull request an edit opened.
     *
     * @param number the pull request number on the catalog
     * @param url    its page on github.com
     * @param branch the branch it was raised from
     */
    public record Proposal(int number, String url, String branch) {
    }

    private static final String MAIN = GitHubConfig.CATALOG_BRANCH;

    private CatalogEdits() {
    }

    /**
     * Opens the pull request that stops approving {@code row}'s release {@code tag}, the other releases kept in
     * their order (2026-10-24: the check's {@code Remove} change). Its last release goes with the whole entry
     * ({@link #remove}), never alone: an entry lists at least one.
     */
    public static CompletableFuture<Proposal> removeRelease(GitHubClient client, GitHubAuth auth, Admin admin,
                                                            CatalogRow row, String tag, String why) {
        return CompletableFuture.supplyAsync(() -> {
            requireAdmin(admin);
            if (row.releases().stream().noneMatch(r -> r.tag().equals(tag))) {
                throw new IllegalArgumentException(row.name() + " lists no release " + tag + ".");
            }
            if (row.releases().size() == 1) {
                throw new IllegalArgumentException(tag + " is the only release " + row.name()
                        + " lists; remove it from the catalog instead.");
            }
            JsonNode file = entryOnMain(client, auth, row);
            String text = withoutRelease(Contents.decode(file), tag);
            return open(client, auth, admin, branch("release", key(row) + "-" + tag, Instant.now()),
                    row.entryPath(), file.path("sha").asText(""), Optional.of(text),
                    "remove " + key(row) + " " + tag,
                    body("Stops approving `" + key(row) + "` `" + tag + "`; its other releases stay listed.", why));
        });
    }

    /** Opens the pull request that deletes {@code row}'s entry file: it leaves {@code catalog.json} once merged. */
    public static CompletableFuture<Proposal> remove(GitHubClient client, GitHubAuth auth, Admin admin,
                                                     CatalogRow row, String why) {
        return CompletableFuture.supplyAsync(() -> {
            requireAdmin(admin);
            JsonNode file = entryOnMain(client, auth, row);
            return open(client, auth, admin, branch("remove", key(row), Instant.now()), row.entryPath(),
                    file.path("sha").asText(""), Optional.empty(), "remove " + key(row),
                    body("Removes `" + row.entryPath() + "` from the catalog.", why));
        });
    }

    /**
     * Opens the pull request that gives {@code login} {@code rank} in {@code publishers.json}, read again from
     * {@code main} first. An unknown id is asked of GitHub ({@code GET /users/<login>}); the file is matched by id.
     */
    public static CompletableFuture<Proposal> setRank(GitHubClient client, GitHubAuth auth, Admin admin,
                                                      String login, Optional<Long> id, Rank rank, String why) {
        return CompletableFuture.supplyAsync(() -> {
            requireAdmin(admin);
            PublishersFile file = PublishersFile.fetch(client, auth).join().orElseThrow(() ->
                    new IllegalStateException("Could not read " + Publishers.FILE + " on " + Catalog.REPO + "."));
            long account = id.filter(n -> n > 0).orElseGet(() -> accountId(client, auth, login));
            String text = moved(file.publishers(), account, login, rank);
            return open(client, auth, admin, branch("rank", login, Instant.now()), Publishers.FILE, file.sha(),
                    Optional.of(text), "rank " + login + " " + rank.id(),
                    body("Ranks @" + login + " (account " + account + ") " + rank.displayName() + ".", why));
        });
    }

    // ---------------------------------------------------------------------------------------------------
    // The texts, pure.
    // ---------------------------------------------------------------------------------------------------

    /**
     * The entry file without its release {@code tag}, every other release and field kept in its order. Refuses a
     * file on {@code main} that does not list the tag, or lists nothing else.
     */
    static String withoutRelease(String entryJson, String tag) {
        JsonNode node;
        try {
            node = entryJson == null ? null : CatalogJson.lenient().readTree(entryJson);
        } catch (Exception e) {
            throw new IllegalStateException("The entry file could not be read: " + e.getMessage(), e);
        }
        if (!(node instanceof ObjectNode entry) || !(entry.get("releases") instanceof ArrayNode releases)) {
            throw new IllegalStateException("The entry file holds no \"releases\" list.");
        }
        int at = -1;
        for (int i = 0; i < releases.size(); i++) {
            if (tag.equals(releases.get(i).path("tag").asText(null))) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            throw new IllegalStateException("The entry file on " + MAIN + " lists no release " + tag + ".");
        }
        if (releases.size() == 1) {
            throw new IllegalStateException("The entry file on " + MAIN + " lists only " + tag + ".");
        }
        releases.remove(at);
        try {
            return CatalogJson.strict().writeValueAsString(entry) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("The entry file could not be written: " + e.getMessage(), e);
        }
    }

    /**
     * {@code publishers.json} with account {@code id} moved to {@code rank}'s list under {@code login}; community
     * takes it off both. Refuses a change that changes nothing and one that leaves no admin, which the check would
     * refuse too.
     */
    static String moved(Publishers publishers, long id, String login, Rank rank) {
        if (publishers.rankOf(id) == rank && listedLogin(publishers, id).map(login::equals).orElse(true)) {
            throw new IllegalArgumentException(login + " is " + rank.displayName() + " already.");
        }
        List<Publishers.Publisher> admin = without(publishers.admin(), id);
        List<Publishers.Publisher> trusted = without(publishers.trusted(), id);
        Publishers.Publisher account = new Publishers.Publisher(id, login);
        switch (rank) {
            case ADMIN -> admin.add(account);
            case TRUSTED -> trusted.add(account);
            case COMMUNITY -> { }
        }
        if (admin.isEmpty()) {
            throw new IllegalArgumentException(Publishers.FILE + " keeps at least one admin.");
        }
        try {
            return CatalogJson.strict().writeValueAsString(new Publishers(admin, trusted)) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException(Publishers.FILE + " could not be written: " + e.getMessage(), e);
        }
    }

    /**
     * {@code dashboard/<verb>-<slug>-<yyyyMMdd-HHmmss>} in UTC, the key reduced to what a git ref may hold: a
     * plugin id's dots stay, a bot's {@code /} does not.
     */
    static String branch(String verb, String key, Instant now) {
        return "dashboard/" + verb + "-" + key.replaceAll("[^A-Za-z0-9._-]", "-") + "-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(now);
    }

    static String body(String what, String why) {
        String reason = why == null || why.isBlank() ? "" : "\n\n" + why.strip();
        return what + reason + "\n\nOpened from the BotMaker Dashboard.";
    }

    private static Optional<String> listedLogin(Publishers publishers, long id) {
        return Stream.concat(publishers.admin().stream(), publishers.trusted().stream())
                .filter(p -> p.id() == id).map(Publishers.Publisher::login).findFirst();
    }

    private static List<Publishers.Publisher> without(List<Publishers.Publisher> list, long id) {
        List<Publishers.Publisher> out = new ArrayList<>(list);
        out.removeIf(p -> p.id() == id);
        return out;
    }

    /** What the pull request names: the entry's {@code owner/repo}, a plugin's as a bot's. */
    private static String key(CatalogRow row) {
        return row.repo();
    }

    // ---------------------------------------------------------------------------------------------------
    // The requests.
    // ---------------------------------------------------------------------------------------------------

    private static void requireAdmin(Admin admin) {
        if (!admin.canWrite() || admin.login().isBlank()) {
            throw new IllegalStateException("Only a catalog admin edits the catalog: " + admin.reason());
        }
    }

    private static JsonNode entryOnMain(GitHubClient client, GitHubAuth auth, CatalogRow row) {
        JsonNode file = Contents.read(client, auth, Catalog.REPO, row.entryPath(), MAIN).join();
        if (file == null || Contents.decode(file) == null) {
            throw new IllegalStateException(row.entryPath() + " is not on " + Catalog.REPO + "'s " + MAIN
                    + " (removed already, or GitHub did not answer).");
        }
        return file;
    }

    private static long accountId(GitHubClient client, GitHubAuth auth, String login) {
        JsonNode user = client.get(GitHubConfig.API_BASE + "/users/"
                + URLEncoder.encode(login, StandardCharsets.UTF_8), Contents.token(auth)).join();
        long id = user == null ? 0 : user.path("id").asLong(0);
        if (id <= 0) {
            throw new IllegalStateException("GitHub knows no account " + login + ".");
        }
        return id;
    }

    /** Branches, writes the one file, and opens the pull request. */
    private static Proposal open(GitHubClient client, GitHubAuth auth, Admin admin, String branch, String path,
                                 String sha, Optional<String> content, String title, String body) {
        String token = Contents.token(auth);
        JsonNode catalog = client.get(api(Catalog.REPO), token).join();
        if (catalog == null) {
            throw new IllegalStateException("Could not read " + Catalog.REPO + ".");
        }
        boolean push = catalog.path("permissions").path("push").asBoolean(false);
        String head = push ? Catalog.REPO : fork(client, token, admin.login());
        branchFromMain(client, token, head, branch);

        Map<String, Object> write = new LinkedHashMap<>();
        write.put("message", title);
        content.ifPresent(text -> write.put("content",
                Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))));
        write.put("sha", sha);
        write.put("branch", branch);
        (content.isPresent() ? Contents.put(client, auth, head, path, write)
                : Contents.delete(client, auth, head, path, write)).join();

        JsonNode pr = client.post(api(Catalog.REPO) + "/pulls", Map.of(
                "title", title,
                "head", head.substring(0, head.indexOf('/')) + ":" + branch,
                "base", MAIN,
                "body", body), token).join();
        return new Proposal(pr.path("number").asInt(), pr.path("html_url").asText(""), branch);
    }

    /**
     * Creates {@code branch} on {@code head} at the catalog's {@code main}. A fork shares its network's objects, so
     * the ref may name a commit only the catalog has; should GitHub refuse that, the fork's {@code main} is synced
     * first and the branch starts there.
     */
    private static void branchFromMain(GitHubClient client, String token, String head, String branch) {
        String sha = mainSha(client, token, Catalog.REPO);
        try {
            client.post(api(head) + "/git/refs", Map.of("ref", "refs/heads/" + branch, "sha", sha), token).join();
        } catch (CompletionException refused) {
            if (head.equals(Catalog.REPO)) {
                throw refused;
            }
            client.post(api(head) + "/merge-upstream", Map.of("branch", MAIN), token).join();
            client.post(api(head) + "/git/refs", Map.of("ref", "refs/heads/" + branch,
                    "sha", mainSha(client, token, head)), token).join();
        }
    }

    private static String mainSha(GitHubClient client, String token, String repo) {
        JsonNode ref = client.get(api(repo) + "/git/ref/heads/" + MAIN, token).join();
        String sha = ref == null ? "" : ref.path("object").path("sha").asText("");
        if (sha.isBlank()) {
            throw new IllegalStateException("Could not read " + repo + "'s " + MAIN + ".");
        }
        return sha;
    }

    /**
     * The account's fork of the catalog, {@code owner/name}, made when there is none. GitHub makes a fork in the
     * background, so its {@code main} is asked for a few times before this gives up.
     */
    private static String fork(GitHubClient client, String token, String login) {
        JsonNode made = client.post(api(Catalog.REPO) + "/forks", Map.of(), token).join();
        String fullName = made == null ? "" : made.path("full_name").asText("");
        String slug = fullName.isBlank() ? login + "/" + GitHubConfig.CATALOG_REPO : fullName;
        for (int attempt = 0; attempt < 10; attempt++) {
            if (client.get(api(slug) + "/git/ref/heads/" + MAIN, token).join() != null) {
                return slug;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("Your fork of the catalog is not ready yet; retry in a minute.");
    }

    private static String api(String repo) {
        return GitHubConfig.API_BASE + "/repos/" + repo;
    }
}
