package com.botmaker.dashboard.github;

import com.botmaker.cli.gallery.GalleryEntry;
import com.botmaker.cli.gallery.Tier;
import com.botmaker.cli.gallery.VettedRecord;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * What is <b>published</b>: every merged entry in the plugin registry and the gallery.
 *
 * <p><b>This is the half {@link Queue} is not.</b> The queue is what is pending — open pull requests, which
 * is zero most of the time and says nothing about what a user of Studio can actually install. This is what
 * shipped: {@code plugins/<plugin-id>.json} and {@code bots/<owner>-<repo>.json} on each repository's
 * {@code main}.
 *
 * <p><b>It reads github.com, never the checked-out submodule.</b> The umbrella has both data repositories as
 * submodules and the tabs beside this one read the checkout — but they read it because releases only exist
 * in a working copy. A merged entry exists on {@code main}, the umbrella's recorded pointer is routinely
 * behind it (both repositories' CI commits a regenerated {@code index.json} of its own), and a stale catalog
 * would look exactly like a current one. So the API is the source.
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

    /** Which repository an entry came from, and therefore what it is. */
    public enum Kind {
        PLUGIN("Plugin", Queue.REGISTRY, Registry.ENTRIES_DIRECTORY),
        BOT("Bot", Queue.GALLERY, GalleryEntry.ENTRIES_DIRECTORY);

        private final String label;
        private final String repo;
        private final String directory;

        Kind(String label, String repo, String directory) {
            this.label = label;
            this.repo = repo;
            this.directory = directory;
        }

        public String label() {
            return label;
        }

        /** {@code owner/name} — {@link Queue}'s, so the two tabs cannot end up naming different repos. */
        public String repo() {
            return repo;
        }

        /** {@code plugins} or {@code bots} — each owner's own constant, never a literal here. */
        public String directory() {
            return directory;
        }
    }

    /**
     * One published entry.
     *
     * @param kind     which repository it lives in
     * @param path     its path in that repository — the filename is the entry's identity
     * @param sha      the blob sha the listing reported, which an edit must send back
     * @param id       the plugin id, or {@code owner/repo} for a bot
     * @param name     what a user reads, or the id again when the entry could not be parsed
     * @param summary  the entry's description
     * @param tags     its tags, as written
     * @param template whether it is a starting template — a gallery-only idea, always false for a plugin
     * @param json     the file, verbatim, for the field view
     * @param readable whether {@link #json} parsed as the entry shape it claims to be
     * @param repo     {@code owner/name} of the repository the entry names, blank when unreadable
     * @param vetted   the bot's {@code vetted/} record, or {@code null} — always null for a plugin
     */
    public record Entry(Kind kind, String path, String sha, String id, String name, String summary,
                        List<String> tags, boolean template, String json, boolean readable, String repo,
                        Vetted vetted) {

        public Entry {
            tags = tags == null ? List.of() : List.copyOf(tags);
            repo = repo == null ? "" : repo;
        }

        public Entry withVetted(Vetted record) {
            return new Entry(kind, path, sha, id, name, summary, tags, template, json, readable, repo, record);
        }

        /**
         * The gallery tier, as {@code CatalogBuilder} would decide it: Vetted exactly when a record exists.
         * Blank for a plugin, which has no tiers — the registry's {@code verifiedVersion} is a different idea.
         */
        public String tierLabel() {
            if (kind != Kind.BOT) {
                return "";
            }
            return vetted == null ? Tier.COMMUNITY.displayName()
                    : Tier.VETTED.displayName() + " " + vetted.record().vettedVersion();
        }

        /**
         * The pages worth opening for this entry: the repository it names, then the entry file itself.
         *
         * <p>An entry whose repository cannot be read — an unparseable file, or a {@code repo} that is not
         * {@code owner/name} — offers only the entry file, rather than a link guessed from its id.
         */
        public List<Links.Link> links() {
            // JitPack for a plugin, which a host resolves as a Maven artifact; never for a bot, which
            // nobody resolves — that page would answer nothing.
            List<Links.Link> links = new ArrayList<>(repo.isEmpty()
                    ? List.of()
                    : Links.forRepository(repo, kind == Kind.PLUGIN));
            links.add(new Links.Link("Entry file", url()));
            return List.copyOf(links);
        }

        /** What the row is called: the identity, never the display name — the filename is the key. */
        public String label() {
            return id;
        }

        /** {@code Plugin}, {@code Bot} or {@code Template} — the one place the reserved tag is read out. */
        public String kindLabel() {
            return template ? "Template" : kind.label();
        }

        public String tagLine() {
            return String.join(", ", tags);
        }

        /** The file on github.com, which is the thing a reviewer wants when a row looks wrong. */
        public String url() {
            return "https://github.com/" + kind.repo() + "/blob/main/" + path;
        }
    }

    /**
     * A bot's vetting record as it stands on {@code main}.
     *
     * @param path   {@code vetted/<owner>-<repo>.json}
     * @param sha    the blob sha, which a revocation must send back
     * @param record the parsed file — {@code botmaker-cli}'s own record, the one the gate and the catalog
     *               builder read
     */
    public record Vetted(String path, String sha, VettedRecord record) {
    }

    /** The branch every published entry is read from. Both data repositories publish from {@code main}. */
    static final String MAIN = "main";

    /**
     * A pull request this window opened against a data repository.
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
     * Every merged entry in both repositories.
     *
     * <p>One request per repository for the directory listing, then one per entry for its bytes — the same
     * {@code 2n+2} shape {@link Queue#open} has, and the same reason it is the right trade: a curated index
     * is small, and the alternative is the generated {@code index.json}, which answers a slightly different
     * question (see this class's own note).
     *
     * <p>Sorted by kind then id so the list is stable between reloads. GitHub already returns a directory
     * alphabetically, but that is a property of the API rather than a promise, and a table that reorders
     * under the operator is worse than one that is merely wrong.
     */
    public static CompletableFuture<List<Entry>> list(GitHubClient client, GitHubAuth auth) {
        List<CompletableFuture<List<Entry>>> perKind = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            perKind.add(listIn(client, auth, kind));
        }
        return CompletableFuture.allOf(perKind.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<Entry> all = new ArrayList<>();
                    perKind.forEach(f -> all.addAll(f.join()));
                    all.sort(Comparator.comparing((Entry e) -> e.kind().ordinal())
                            .thenComparing(Entry::id, String.CASE_INSENSITIVE_ORDER));
                    return List.copyOf(all);
                });
    }

    /**
     * One repository's entries.
     *
     * <p><b>A directory that is not an array is an empty catalog, not a failure.</b> The registry was empty
     * for its whole first week and a repository can legitimately have no {@code plugins/} directory at all;
     * answering that with an exception would put "could not read GitHub" in front of an operator whose
     * registry is simply new.
     */
    private static CompletableFuture<List<Entry>> listIn(GitHubClient client, GitHubAuth auth, Kind kind) {
        return Contents.read(client, auth, kind.repo(), kind.directory(), MAIN).thenCompose(listing -> {
            if (listing == null || !listing.isArray() || listing.isEmpty()) {
                return CompletableFuture.completedFuture(List.<Entry>of());
            }
            List<CompletableFuture<Entry>> each = new ArrayList<>();
            for (JsonNode file : listing) {
                if (!"file".equals(file.path("type").asText("")) || !isEntryFile(file, kind)) {
                    continue;
                }
                each.add(entry(client, auth, kind, file));
            }
            CompletableFuture<List<Entry>> entries = CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> each.stream().map(CompletableFuture::join).toList());
            return kind == Kind.BOT
                    ? entries.thenCombine(vettings(client, auth), Catalog::attach)
                    : entries;
        });
    }

    /**
     * Every {@code vetted/} record on the gallery's {@code main}. No directory is an empty list: a gallery
     * nobody has vetted anything in yet is an ordinary gallery.
     */
    private static CompletableFuture<List<Vetted>> vettings(GitHubClient client, GitHubAuth auth) {
        String repo = Kind.BOT.repo();
        return Contents.read(client, auth, repo, VettedRecord.DIRECTORY, MAIN).thenCompose(listing -> {
            if (listing == null || !listing.isArray() || listing.isEmpty()) {
                return CompletableFuture.completedFuture(List.<Vetted>of());
            }
            List<CompletableFuture<Optional<Vetted>>> each = new ArrayList<>();
            for (JsonNode file : listing) {
                String name = file.path("name").asText("");
                if (!"file".equals(file.path("type").asText("")) || !name.endsWith(".json")) {
                    continue;
                }
                String path = file.path("path").asText("");
                String sha = file.path("sha").asText("");
                each.add(Contents.read(client, auth, repo, path, MAIN)
                        .thenApply(contents -> readVetted(path, sha, Contents.decode(contents))));
            }
            return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> each.stream().map(CompletableFuture::join)
                            .flatMap(Optional::stream).toList());
        });
    }

    /**
     * Whether a listed file is an entry.
     *
     * <p>{@code index.json} is the one thing in both directories that is not one — except that it is not in
     * either of them, since CI writes it at the repository root. Excluded anyway: it is generated, so if it
     * ever moves next to the entries it must not read as one.
     */
    private static boolean isEntryFile(JsonNode file, Kind kind) {
        String name = file.path("name").asText("");
        return name.endsWith(".json") && !name.equals(Registry.INDEX);
    }

    /** One listed file, fetched and parsed. */
    private static CompletableFuture<Entry> entry(GitHubClient client, GitHubAuth auth,
                                                  Kind kind, JsonNode file) {
        String path = file.path("path").asText("");
        String sha = file.path("sha").asText("");
        String idFromName = idFromFilename(file.path("name").asText(""));
        return Contents.read(client, auth, kind.repo(), path, MAIN)
                .thenApply(contents -> read(kind, path, sha, idFromName, Contents.decode(contents)));
    }

    /**
     * Builds one entry from its text.
     *
     * <p><b>Unparseable is a row, never a dropped one.</b> An entry this window cannot read is exactly the
     * entry an operator has to see — it is on {@code main}, so somebody merged it, and the gate either
     * passed it or never ran. It keeps its filename as its identity, is marked unreadable, and its raw text
     * still reaches the field view, where {@link EntryFields} renders the parse failure and then the file.
     */
    static Entry read(Kind kind, String path, String sha, String idFromName, String json) {
        if (json == null) {
            return new Entry(kind, path, sha, idFromName, idFromName, "", List.of(), false, null, false, "",
                    null);
        }
        try {
            if (kind == Kind.PLUGIN) {
                RegistryEntry plugin = Registry.mapper().readValue(json, RegistryEntry.class);
                String id = plugin.id() == null || plugin.id().isBlank() ? idFromName : plugin.id();
                return new Entry(kind, path, sha, id, blankTo(plugin.name(), id), plugin.description(),
                        plugin.tags(), false, json, true, Links.slug(plugin.repo()).orElse(""), null);
            }
            GalleryEntry bot = Registry.mapper().readValue(json, GalleryEntry.class);
            String id = bot.slug().equals("/") ? idFromName : bot.slug();
            return new Entry(kind, path, sha, id, blankTo(bot.name(), id), bot.description(),
                    bot.tags(), bot.isTemplate(), json, true, Links.slug(bot.slug()).orElse(""), null);
        } catch (Exception e) {
            return new Entry(kind, path, sha, idFromName, idFromName, "", List.of(), false, json, false, "",
                    null);
        }
    }

    /**
     * Reads one {@code vetted/} file, or empty when it will not parse.
     *
     * <p>Unlike an entry, an unreadable vetting is not a row: it names no bot of its own, and the gallery's
     * {@code CatalogBuilder} fails the index build on it, which is where that problem is reported.
     */
    static Optional<Vetted> readVetted(String path, String sha, String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            VettedRecord record = Registry.mapper().readValue(json, VettedRecord.class);
            return record.owner().isEmpty() || record.repo().isEmpty()
                    ? Optional.empty()
                    : Optional.of(new Vetted(path, sha, record));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Each bot with its vetting attached, matched on {@code owner/repo} without case — GitHub's own rule, and
     * {@code GalleryCatalog}'s, which is what decides the tier Studio shows.
     */
    static List<Entry> attach(List<Entry> bots, List<Vetted> vettings) {
        Map<String, Vetted> bySlug = new java.util.HashMap<>();
        for (Vetted v : vettings) {
            bySlug.put(v.record().slug().toLowerCase(java.util.Locale.ROOT), v);
        }
        return bots.stream()
                .map(e -> e.kind() == Kind.BOT && e.readable()
                        ? e.withVetted(bySlug.get(e.id().toLowerCase(java.util.Locale.ROOT)))
                        : e)
                .toList();
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
        return branch(client, auth, entry, branch)
                .thenCompose(ignored -> Contents.put(client, auth, entry.kind().repo(), entry.path(),
                        Map.of("message", message,
                                "content", Base64.getEncoder()
                                        .encodeToString(json.getBytes(StandardCharsets.UTF_8)),
                                "sha", entry.sha(),
                                "branch", branch)))
                .thenCompose(ignored -> pull(client, auth, entry, branch, message,
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
        return branch(client, auth, entry, branch)
                .thenCompose(ignored -> Contents.delete(client, auth, entry.kind().repo(), entry.path(),
                        Map.of("message", message, "sha", entry.sha(), "branch", branch)))
                .thenCompose(ignored -> pull(client, auth, entry, branch, message,
                        body("Removes `" + entry.path() + "`, unpublishing `" + entry.id() + "`.", why)));
    }

    /** Branches {@code main} at whatever it is now. {@link Vetting}'s writes go through it too. */
    static CompletableFuture<JsonNode> branch(GitHubClient client, GitHubAuth auth,
                                                      Entry entry, String branch) {
        String repo = entry.kind().repo();
        return client.get(GitHubConfig.API_BASE + "/repos/" + repo + "/git/ref/heads/" + MAIN, token(auth))
                .thenCompose(ref -> {
                    String sha = ref == null ? "" : ref.path("object").path("sha").asText("");
                    if (sha.isBlank()) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "could not read " + repo + "'s " + MAIN + " — nothing to branch from"));
                    }
                    return client.post(GitHubConfig.API_BASE + "/repos/" + repo + "/git/refs",
                            Map.of("ref", "refs/heads/" + branch, "sha", sha), token(auth));
                });
    }

    static CompletableFuture<Proposal> pull(GitHubClient client, GitHubAuth auth, Entry entry,
                                                    String branch, String title, String body) {
        return client.post(GitHubConfig.API_BASE + "/repos/" + entry.kind().repo() + "/pulls",
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
