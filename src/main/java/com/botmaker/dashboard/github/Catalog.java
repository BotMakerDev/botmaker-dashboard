package com.botmaker.dashboard.github;

import com.botmaker.cli.registry.Registry;
import com.botmaker.cli.registry.RegistryEntry;
import com.botmaker.dashboard.umbrella.Links;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * What is <b>published</b>: every merged entry in the plugin registry.
 *
 * <p><b>This is the half {@link Queue} is not.</b> The queue is what is pending — open pull requests, which
 * is zero most of the time and says nothing about what a user of Studio can actually install. This is what
 * shipped: {@code plugins/<plugin-id>.json} on the registry's {@code main}.
 *
 * <p><b>Plugins only, since 2026-10-23.</b> Bots have no gallery repository any more: a bot is listed by the
 * GitHub topic {@code botmaker-bot} on its own repository, which Studio's Browse Bots searches and nobody
 * reviews, so there is nothing published about a bot for an operator to read or change here.
 *
 * <p><b>It reads github.com, never the checked-out submodule.</b> A merged entry exists on {@code main}, the
 * umbrella's recorded pointer is routinely behind it (the registry's CI commits a regenerated
 * {@code index.json} of its own), and a stale catalog would look exactly like a current one. So the API is
 * the source.
 *
 * <p><b>It reads the entry files and not the generated {@code index.json}.</b> The index is derived, by CI,
 * from those files — one file per entry is what makes two same-day submissions two files that cannot
 * conflict and what makes git itself refuse a second claim on an id. Reading the derived copy would show
 * the operator something a job produced rather than what the repository holds, and the two differ for
 * however long a regeneration takes.
 *
 * <p>Nothing here judges an entry. Whether a plugin is good is {@code RegistryGate}'s answer, delivered as a
 * check run on the pull request that added it — see {@link Checks}. This class lists what was admitted.
 */
public final class Catalog {

    /** {@code owner/name} of the registry — {@link Queue}'s, so the two tabs cannot name different repos. */
    public static final String REPO = Queue.REGISTRY;

    /**
     * One published entry.
     *
     * @param path     its path in the registry — the filename is the entry's identity
     * @param sha      the blob sha the listing reported, which an edit must send back
     * @param id       the plugin id
     * @param name     what a user reads, or the id again when the entry could not be parsed
     * @param summary  the entry's description
     * @param tags     its tags, as written
     * @param json     the file, verbatim, for the field view
     * @param readable whether {@link #json} parsed as a registry entry
     * @param repo     {@code owner/name} of the repository the entry names, blank when unreadable
     */
    public record Entry(String path, String sha, String id, String name, String summary,
                        List<String> tags, String json, boolean readable, String repo) {

        public Entry {
            tags = tags == null ? List.of() : List.copyOf(tags);
            repo = repo == null ? "" : repo;
        }

        /**
         * The pages worth opening for this entry: the repository it names (and JitPack, which a host resolves
         * a plugin from), then the entry file itself.
         *
         * <p>An entry whose repository cannot be read — an unparseable file, or a {@code repo} that is not
         * {@code owner/name} — offers only the entry file, rather than a link guessed from its id.
         */
        public List<Links.Link> links() {
            List<Links.Link> links = new ArrayList<>(repo.isEmpty() ? List.of() : Links.forRepository(repo, true));
            links.add(new Links.Link("Entry file", url()));
            return List.copyOf(links);
        }

        /** What the row is called: the identity, never the display name — the filename is the key. */
        public String label() {
            return id;
        }

        public String tagLine() {
            return String.join(", ", tags);
        }

        /** The file on github.com, which is the thing a reviewer wants when a row looks wrong. */
        public String url() {
            return "https://github.com/" + REPO + "/blob/main/" + path;
        }
    }

    /** The branch every published entry is read from. */
    static final String MAIN = "main";

    /**
     * A pull request this window opened against the registry.
     *
     * @param number the pull request number
     * @param url    its page on github.com
     * @param branch the branch it was raised from
     */
    public record Proposal(int number, String url, String branch) {
    }

    private Catalog() {
    }

    /**
     * Every merged entry.
     *
     * <p>One request for the directory listing, then one per entry for its bytes — the same {@code n+1}
     * shape {@link Queue#open} has, and the same reason it is the right trade: a curated index is small, and
     * the alternative is the generated {@code index.json}, which answers a slightly different question (see
     * this class's own note).
     *
     * <p>Sorted by id so the list is stable between reloads. GitHub already returns a directory
     * alphabetically, but that is a property of the API rather than a promise, and a table that reorders
     * under the operator is worse than one that is merely wrong.
     *
     * <p><b>A directory that is not an array is an empty catalog, not a failure.</b> The registry was empty
     * for its whole first week and a repository can legitimately have no {@code plugins/} directory at all;
     * answering that with an exception would put "could not read GitHub" in front of an operator whose
     * registry is simply new.
     */
    public static CompletableFuture<List<Entry>> list(GitHubClient client, GitHubAuth auth) {
        return Contents.listing(client, auth, REPO, Registry.ENTRIES_DIRECTORY, MAIN).thenCompose(listing -> {
            if (listing == null || !listing.isArray() || listing.isEmpty()) {
                return CompletableFuture.completedFuture(List.<Entry>of());
            }
            List<CompletableFuture<Entry>> each = new ArrayList<>();
            for (JsonNode file : listing) {
                if ("file".equals(file.path("type").asText("")) && isEntryFile(file)) {
                    each.add(entry(client, auth, file));
                }
            }
            return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> each.stream().map(CompletableFuture::join)
                            .sorted(Comparator.comparing(Entry::id, String.CASE_INSENSITIVE_ORDER))
                            .toList());
        });
    }

    /**
     * Whether a listed file is an entry.
     *
     * <p>{@code index.json} is the one thing that is not one — except that it is not in the directory, since
     * CI writes it at the repository root. Excluded anyway: it is generated, so if it ever moves next to the
     * entries it must not read as one.
     */
    private static boolean isEntryFile(JsonNode file) {
        String name = file.path("name").asText("");
        return name.endsWith(".json") && !name.equals(Registry.INDEX);
    }

    /** One listed file, fetched and parsed. */
    private static CompletableFuture<Entry> entry(GitHubClient client, GitHubAuth auth, JsonNode file) {
        String path = file.path("path").asText("");
        String sha = file.path("sha").asText("");
        String idFromName = idFromFilename(file.path("name").asText(""));
        return Contents.read(client, auth, REPO, path, MAIN)
                .thenApply(contents -> read(path, sha, idFromName, Contents.decode(contents)));
    }

    /**
     * Builds one entry from its text.
     *
     * <p><b>Unparseable is a row, never a dropped one.</b> An entry this window cannot read is exactly the
     * entry an operator has to see — it is on {@code main}, so somebody merged it, and the gate either
     * passed it or never ran. It keeps its filename as its identity, is marked unreadable, and its raw text
     * still reaches the field view, where {@link EntryFields} renders the parse failure and then the file.
     */
    static Entry read(String path, String sha, String idFromName, String json) {
        if (json == null) {
            return new Entry(path, sha, idFromName, idFromName, "", List.of(), null, false, "");
        }
        try {
            RegistryEntry plugin = Registry.mapper().readValue(json, RegistryEntry.class);
            String id = plugin.id() == null || plugin.id().isBlank() ? idFromName : plugin.id();
            return new Entry(path, sha, id, blankTo(plugin.name(), id), plugin.description(),
                    plugin.tags(), json, true, Links.slug(plugin.repo()).orElse(""));
        } catch (Exception e) {
            return new Entry(path, sha, idFromName, idFromName, "", List.of(), json, false, "");
        }
    }

    /** {@code plugins/com.botmaker.sdk.json} claims {@code com.botmaker.sdk} — the layout doing a key's job. */
    static String idFromFilename(String filename) {
        return filename.endsWith(".json")
                ? filename.substring(0, filename.length() - ".json".length())
                : filename;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ---------------------------------------------------------------------------------------------------
    // The two writes. Both are pull requests; neither touches main.
    // ---------------------------------------------------------------------------------------------------

    /**
     * Proposes new text for an entry, as a pull request.
     *
     * <p><b>A pull request and never a push to {@code main}</b>, for the reason the layout gives: the entry
     * file is the source of truth and {@code index.json} is generated from it by CI, so an edit committed
     * straight to {@code main} leaves an index that disagrees with the entries until the next job runs. A
     * pull request also runs {@code RegistryGate} over the result — which is the whole point of the gate
     * being a library rather than a step in somebody's command, and the reason this window validates
     * nothing itself.
     *
     * <p>The blob {@code sha} the listing reported is sent back, so GitHub refuses the write if the file
     * moved since it was read. That is optimistic locking rather than a courtesy: two operators editing one
     * entry is exactly the case a registry with one file per entry was shaped to make visible.
     */
    public static CompletableFuture<Proposal> edit(GitHubClient client, GitHubAuth auth,
                                                   Entry entry, String json, String why) {
        String branch = branchFor("edit", entry);
        String message = "edit " + entry.id();
        return branch(client, auth, branch)
                .thenCompose(ignored -> Contents.put(client, auth, REPO, entry.path(),
                        Map.of("message", message,
                                "content", Base64.getEncoder()
                                        .encodeToString(json.getBytes(StandardCharsets.UTF_8)),
                                "sha", entry.sha(),
                                "branch", branch)))
                .thenCompose(ignored -> pull(client, auth, branch, message,
                        body("Edits `" + entry.path() + "`.", why)));
    }

    /**
     * Proposes removing an entry, as a pull request.
     *
     * <p>Nothing is deleted by this call. It opens a pull request whose merge removes the file; until
     * somebody merges it the entry is published exactly as before, which is the only safe shape for an
     * action whose effect is that a plugin disappears from every user's Manage Plugins.
     */
    public static CompletableFuture<Proposal> unpublish(GitHubClient client, GitHubAuth auth,
                                                        Entry entry, String why) {
        String branch = branchFor("unpublish", entry);
        String message = "unpublish " + entry.id();
        return branch(client, auth, branch)
                .thenCompose(ignored -> Contents.delete(client, auth, REPO, entry.path(),
                        Map.of("message", message, "sha", entry.sha(), "branch", branch)))
                .thenCompose(ignored -> pull(client, auth, branch, message,
                        body("Removes `" + entry.path() + "`, unpublishing `" + entry.id() + "`.", why)));
    }

    /** Branches {@code main} at whatever it is now. */
    static CompletableFuture<JsonNode> branch(GitHubClient client, GitHubAuth auth, String branch) {
        return client.get(GitHubConfig.API_BASE + "/repos/" + REPO + "/git/ref/heads/" + MAIN, token(auth))
                .thenCompose(ref -> {
                    String sha = ref == null ? "" : ref.path("object").path("sha").asText("");
                    if (sha.isBlank()) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "could not read " + REPO + "'s " + MAIN + " — nothing to branch from"));
                    }
                    return client.post(GitHubConfig.API_BASE + "/repos/" + REPO + "/git/refs",
                            Map.of("ref", "refs/heads/" + branch, "sha", sha), token(auth));
                });
    }

    static CompletableFuture<Proposal> pull(GitHubClient client, GitHubAuth auth,
                                            String branch, String title, String body) {
        return client.post(GitHubConfig.API_BASE + "/repos/" + REPO + "/pulls",
                        Map.of("title", title, "head", branch, "base", MAIN, "body", body), token(auth))
                .thenApply(pr -> new Proposal(pr.path("number").asInt(),
                        pr.path("html_url").asText(""), branch));
    }

    /**
     * A body that says what the pull request does and, when the operator wrote one, why.
     *
     * <p>It also says where it came from. A reviewer who finds a branch nobody recognises on a data
     * repository should be able to read what opened it, and the answer is a desktop app rather than CI.
     */
    static String body(String what, String why) {
        String reason = why == null || why.isBlank() ? "" : "\n\n" + why.strip();
        return what + reason + "\n\nOpened from the BotMaker Dashboard.";
    }

    /**
     * A branch name that cannot collide with the last one.
     *
     * <p>The timestamp is not decoration: a second edit while the first pull request is still open would
     * otherwise be refused with a 422 naming a reference that already exists, which reads as a bug in this
     * window rather than as what it is. The id is reduced to the characters a git ref may hold, so a plugin
     * id with a dot in it — every plugin id — still produces a legal ref.
     */
    static String branchFor(String verb, Entry entry) {
        String slug = entry.id().replaceAll("[^A-Za-z0-9._-]", "-");
        return "dashboard/" + verb + "-" + slug + "-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
                        .format(Instant.now());
    }

    private static String token(GitHubAuth auth) {
        return Contents.token(auth);
    }
}
