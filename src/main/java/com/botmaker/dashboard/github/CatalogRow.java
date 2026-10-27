package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.Rank;
import com.botmaker.dashboard.umbrella.Links;
import com.botmaker.shared.github.GitHubConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * One listed bot or plugin, as {@code catalog.json} on the catalog's {@code main} says: the card the Catalog tab
 * draws. Read as data by {@link Catalog#read}; the rank is the catalog's own, decided by the owner's account id.
 *
 * @param repo     {@code owner/name}
 * @param name     what a person reads: a plugin's pom {@code <name>}, a bot's repository name
 * @param forkOf   the repository a bot was forked from, blank when none
 * @param releases every release the catalog approved, newest first (schema 4, 2026-10-24); never empty
 */
public record CatalogRow(Kind kind, String repo, String name, Rank rank, String description, List<String> tags,
                         int stars, String forkOf, List<Release> releases) {

    /** Which half of the catalog a row is in. */
    public enum Kind {
        BOT("bot", "Bot"),
        PLUGIN("plugin", "Plugin");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** A catalog plugin a bot declares, named by its {@code owner/repo}, at the version its pom pins. */
    public record Pin(String repo, String version) {
    }

    /**
     * One approved release.
     *
     * @param commit  the commit its tag was pinned to; blank when the file names none
     * @param plugins the catalog plugins a bot's pom declares at {@code tag}; empty for a plugin
     */
    public record Release(String tag, String commit, List<Pin> plugins) {
        public Release {
            commit = commit == null ? "" : commit;
            plugins = plugins == null ? List.of() : List.copyOf(plugins);
        }

        /** The commit's first seven digits, as GitHub shows one. */
        public String shortCommit() {
            return commit.length() > 7 ? commit.substring(0, 7) : commit;
        }
    }

    public CatalogRow {
        name = name == null || name.isBlank() ? repoName(repo) : name;
        rank = rank == null ? Rank.COMMUNITY : rank;
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        forkOf = forkOf == null ? "" : forkOf;
        releases = releases == null ? List.of() : List.copyOf(releases);
        if (releases.isEmpty()) {
            throw new IllegalArgumentException(repo + " lists no release");
        }
    }

    /** The newest approved release's tag: what Studio offers first. */
    public String tag() {
        return releases.getFirst().tag();
    }

    /** The catalog plugins a bot's newest release declares; empty for a plugin. */
    public List<Pin> plugins() {
        return releases.getFirst().plugins();
    }

    /** The repository's owner login: who the row is by. */
    public String owner() {
        int slash = repo.indexOf('/');
        return slash < 0 ? repo : repo.substring(0, slash);
    }

    /**
     * The entry file this row was built from: {@code bots/<owner>__<repo>.json} or {@code plugins/<owner>__<repo>.json}
     * (a plugin's was named by its id until 2026-10-27), the one path an admin's edit changes.
     */
    public String entryPath() {
        return kind == Kind.BOT ? EntryPath.bot(repo) : EntryPath.plugin(repo);
    }

    /** The entry file on GitHub. */
    public String entryUrl() {
        return "https://github.com/" + GitHubConfig.CATALOG_OWNER + "/" + GitHubConfig.CATALOG_REPO + "/blob/"
                + GitHubConfig.CATALOG_BRANCH + "/" + entryPath();
    }

    /** The repository's pages (JitPack for a plugin, which a host resolves), then the entry file. */
    public List<Links.Link> links() {
        List<Links.Link> links = new ArrayList<>(Links.forRepository(repo, kind == Kind.PLUGIN));
        links.add(new Links.Link("Entry file", entryUrl()));
        return List.copyOf(links);
    }

    private static String repoName(String repo) {
        int slash = repo == null ? -1 : repo.indexOf('/');
        return repo == null ? "" : repo.substring(slash + 1);
    }
}
