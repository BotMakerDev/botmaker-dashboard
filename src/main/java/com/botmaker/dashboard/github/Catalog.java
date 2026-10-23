package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.Rank;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What is <b>listed</b>: every bot and plugin in {@code catalog.json} on the catalog's {@code main} (2026-10-23).
 *
 * <p><b>The file Studio reads, not the entry files.</b> {@code catalog.json} is what the catalog's {@code index.yml}
 * builds from the entries and {@code publishers.json}: the ranks, the names and the bots' plugins are there and
 * nowhere else, and a card should show exactly what a user of Studio is offered. It is read through the same raw
 * URL ({@link GitHubConfig#catalogRawUrls()}), so it costs no API request.
 *
 * <p><b>Read as data.</b> The dashboard depends on the cli, but parses the file into its own {@link CatalogRow}s
 * one row at a time: a row missing its {@code repo} or {@code tag} is dropped and the rest still show, and a file
 * that does not parse at all is an empty list with the reason, never an exception.
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     * Every row of a {@code catalog.json}, plugins then bots, each sorted by name. A row without {@code repo} or
     * {@code tag} is dropped; an unknown rank reads as {@link Rank#COMMUNITY}, as Studio reads it.
     */
    public static Read read(String catalogJson) {
        JsonNode root;
        try {
            root = catalogJson == null ? null : MAPPER.readTree(catalogJson);
        } catch (Exception e) {
            return new Read(List.of(), GitHubConfig.CATALOG_PATH + " is not JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return new Read(List.of(), GitHubConfig.CATALOG_PATH + " is not a catalog");
        }
        List<CatalogRow> rows = new ArrayList<>();
        rows.addAll(sorted(rows(root.path("plugins"), CatalogRow.Kind.PLUGIN)));
        rows.addAll(sorted(rows(root.path("bots"), CatalogRow.Kind.BOT)));
        return new Read(rows, "");
    }

    private static List<CatalogRow> rows(JsonNode array, CatalogRow.Kind kind) {
        List<CatalogRow> rows = new ArrayList<>();
        if (!array.isArray()) {
            return rows;
        }
        for (JsonNode node : array) {
            String repo = text(node, "repo");
            String tag = text(node, "tag");
            String id = text(node, "id");
            boolean plugin = kind == CatalogRow.Kind.PLUGIN;
            if (repo.isBlank() || !repo.contains("/") || tag.isBlank() || (plugin && id.isBlank())) {
                continue;
            }
            rows.add(new CatalogRow(kind, repo, plugin ? text(node, "name") : "", tag,
                    Rank.fromId(text(node, "rank")), text(node, "description"), strings(node.path("tags")),
                    node.path("stars").asInt(0), text(node, "forkOf"), id, pins(node.path("plugins"))));
        }
        return rows;
    }

    private static List<CatalogRow> sorted(List<CatalogRow> rows) {
        return rows.stream().sorted(Comparator.comparing(CatalogRow::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static List<CatalogRow.Pin> pins(JsonNode array) {
        List<CatalogRow.Pin> pins = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode pin : array) {
                String id = text(pin, "id");
                if (!id.isBlank()) {
                    pins.add(new CatalogRow.Pin(id, text(pin, "version")));
                }
            }
        }
        return pins;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    out.add(value.asText());
                }
            });
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() ? value.asText("").strip() : "";
    }
}
