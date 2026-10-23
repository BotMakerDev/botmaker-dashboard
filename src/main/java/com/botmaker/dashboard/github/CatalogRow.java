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
 * @param repo    {@code owner/name}
 * @param name    what a person reads: a plugin's pom {@code <name>}, a bot's repository name
 * @param forkOf  the repository a bot was forked from, blank when none
 * @param id      a plugin's id, blank for a bot
 * @param plugins the catalog plugins a bot's pom declares at {@code tag}; empty for a plugin
 */
public record CatalogRow(Kind kind, String repo, String name, String tag, Rank rank, String description,
                         List<String> tags, int stars, String forkOf, String id, List<Pin> plugins) {

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

    /** A catalog plugin a bot declares, at the version its pom pins. */
    public record Pin(String id, String version) {
    }

    public CatalogRow {
        name = name == null || name.isBlank() ? repoName(repo) : name;
        rank = rank == null ? Rank.COMMUNITY : rank;
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        forkOf = forkOf == null ? "" : forkOf;
        id = id == null ? "" : id;
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    /** The repository's owner login: who the row is by. */
    public String owner() {
        int slash = repo.indexOf('/');
        return slash < 0 ? repo : repo.substring(0, slash);
    }

    /**
     * The entry file this row was built from: {@code bots/<owner>__<repo>.json} or {@code plugins/<id>.json}, the
     * one path an admin's edit changes.
     */
    public String entryPath() {
        return switch (kind) {
            case BOT -> EntryPath.BOTS + owner() + "__" + repoName(repo) + ".json";
            case PLUGIN -> EntryPath.PLUGINS + id + ".json";
        };
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
