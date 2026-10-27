package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.cli.catalog.DerivedFacts;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * What is <b>listed</b>: every bot and plugin in {@code catalog.json} on the catalog's {@code main} (2026-10-23).
 *
 * <p><b>The file Studio reads, not the entry files.</b> {@code catalog.json} is what the catalog's {@code index.yml}
 * builds from the entries and {@code publishers.json}: the ranks, the names and the bots' plugins are there and
 * nowhere else, and a card should show exactly what a user of Studio is offered. It is read through the same raw
 * URL ({@link GitHubConfig#catalogRawUrls()}), so it costs no API request.
 *
 * <p><b>Read as Studio reads it</b> (schema 4, 2026-10-24): each row through the cli's own records
 * ({@link com.botmaker.cli.catalog.Catalog.Plugin}, {@link com.botmaker.cli.catalog.Catalog.Bot}), one row at a
 * time, so the two windows cannot disagree on the shape. A row that does not read, has no {@code repo}, no release,
 * or a plugin with no {@code id} is dropped and the rest still show; the single {@code tag} of schema 3 is not read.
 * A file that does not parse at all is an empty list with the reason, never an exception.
 *
 * <p>Nothing here judges an entry: what let it in was the catalog's {@code validate} check on the pull request
 * that added it.
 */
public final class Catalog {

    /** {@code owner/name} of the catalog — {@link Queue}'s, so the two tabs cannot name different repos. */
    public static final String REPO = GitHubConfig.CATALOG_OWNER + "/" + GitHubConfig.CATALOG_REPO;

    /**
     * The rows read, and when there are none because the file could not be read or parsed, why.
     *
     * @param problem blank when the file read; otherwise one sentence for the status line
     */
    public record Read(List<CatalogRow> rows, String problem) {
        public Read {
            rows = rows == null ? List.of() : List.copyOf(rows);
            problem = problem == null ? "" : problem;
        }
    }

    private Catalog() {
    }

    /** {@code catalog.json} on the catalog's {@code main}, off the caller's thread. */
    public static CompletableFuture<Read> fetch(GitHubClient client) {
        return client.getFirstString(GitHubConfig.catalogRawUrls()).thenApply(text -> text == null
                ? new Read(List.of(), "could not read " + GitHubConfig.CATALOG_PATH + " from " + REPO
                        + " (offline, or GitHub did not answer)")
                : read(text));
    }

    /**
     * Every row of a {@code catalog.json}, plugins then bots, each sorted by name. A row that cannot be shown is
     * dropped (see the class); an unknown rank reads as {@link com.botmaker.cli.catalog.Rank#COMMUNITY}, as Studio
     * reads it.
     */
    public static Read read(String catalogJson) {
        JsonNode root;
        try {
            root = catalogJson == null ? null : CatalogJson.lenient().readTree(catalogJson);
        } catch (Exception e) {
            return new Read(List.of(), GitHubConfig.CATALOG_PATH + " is not JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return new Read(List.of(), GitHubConfig.CATALOG_PATH + " is not a catalog");
        }
        List<CatalogRow> rows = new ArrayList<>();
        rows.addAll(sorted(rows(root.path("plugins"), Catalog::plugin)));
        rows.addAll(sorted(rows(root.path("bots"), Catalog::bot)));
        return new Read(rows, "");
    }

    private interface RowReader {
        Optional<CatalogRow> read(JsonNode node) throws Exception;
    }

    private static List<CatalogRow> rows(JsonNode array, RowReader reader) {
        List<CatalogRow> rows = new ArrayList<>();
        if (!array.isArray()) {
            return rows;
        }
        for (JsonNode node : array) {
            try {
                reader.read(node).ifPresent(rows::add);
            } catch (Exception e) {
                // A row of the wrong shape: the others still show.
            }
        }
        return rows;
    }

    private static Optional<CatalogRow> plugin(JsonNode node) throws Exception {
        var plugin = CatalogJson.lenient().treeToValue(node, com.botmaker.cli.catalog.Catalog.Plugin.class);
        if (plugin == null || !isRepo(plugin.repo())) {
            return Optional.empty();
        }
        List<CatalogRow.Release> releases = plugin.releases().stream().filter(r -> r != null && !blank(r.tag()))
                .map(r -> new CatalogRow.Release(r.tag(), r.commit(), List.of())).toList();
        return releases.isEmpty() ? Optional.empty() : Optional.of(new CatalogRow(CatalogRow.Kind.PLUGIN,
                plugin.repo().strip(), plugin.name(), plugin.rank(), plugin.description(), plugin.tags(),
                plugin.stars(), "", releases));
    }

    private static Optional<CatalogRow> bot(JsonNode node) throws Exception {
        var bot = CatalogJson.lenient().treeToValue(node, com.botmaker.cli.catalog.Catalog.Bot.class);
        if (bot == null || !isRepo(bot.repo())) {
            return Optional.empty();
        }
        List<CatalogRow.Release> releases = bot.releases().stream().filter(r -> r != null && !blank(r.tag()))
                .map(r -> new CatalogRow.Release(r.tag(), r.commit(), pins(r.plugins()))).toList();
        return releases.isEmpty() ? Optional.empty() : Optional.of(new CatalogRow(CatalogRow.Kind.BOT,
                bot.repo().strip(), "", bot.rank(), bot.description(), bot.tags(), bot.stars(), bot.forkOf(),
                releases));
    }

    private static List<CatalogRow.Pin> pins(List<DerivedFacts.Pin> pins) {
        return pins.stream().filter(p -> p != null && !blank(p.repo()))
                .map(p -> new CatalogRow.Pin(p.repo(), p.version() == null ? "" : p.version())).toList();
    }

    private static List<CatalogRow> sorted(List<CatalogRow> rows) {
        return rows.stream().sorted(Comparator.comparing(CatalogRow::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static boolean isRepo(String repo) {
        return !blank(repo) && repo.contains("/");
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
