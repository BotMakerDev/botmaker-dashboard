package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.Rank;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What the Catalog tab's chips and search box select: a pure function over rows and authors, so the grid's
 * filtering is tested without a window.
 *
 * @param view  which cards show: bots and plugins, one kind, or the authors
 * @param ranks the ranks shown; empty shows every rank
 * @param query words that must each appear, case-insensitive, in a card's name, owner or tags (an author's login)
 */
public record CatalogFilter(View view, Set<Rank> ranks, String query) {

    /** The kind chips; exactly one is on. */
    public enum View {
        ALL("all", "All"),
        BOTS("bots", "Bots"),
        PLUGINS("plugins", "Plugins"),
        AUTHORS("authors", "Authors");

        private final String id;
        private final String displayName;

        View(String id, String displayName) {
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

    /** Everything: what the tab opens on. */
    public static final CatalogFilter ALL = new CatalogFilter(View.ALL, Set.of(), "");

    public CatalogFilter {
        view = view == null ? View.ALL : view;
        ranks = ranks == null ? Set.of() : Set.copyOf(ranks);
        query = query == null ? "" : query.strip();
    }

    /** The bot and plugin cards shown, in the rows' order; none under {@link View#AUTHORS}. */
    public List<CatalogRow> apply(List<CatalogRow> rows) {
        return rows.stream()
                .filter(row -> switch (view) {
                    case ALL -> true;
                    case BOTS -> row.kind() == CatalogRow.Kind.BOT;
                    case PLUGINS -> row.kind() == CatalogRow.Kind.PLUGIN;
                    case AUTHORS -> false;
                })
                .filter(row -> ranked(row.rank()))
                .filter(row -> matches(Stream.concat(Stream.of(row.name(), row.owner()), row.tags().stream())))
                .toList();
    }

    /** The author cards shown, in their order; only under {@link View#AUTHORS}. */
    public List<Authors.Author> applyAuthors(List<Authors.Author> authors) {
        if (view != View.AUTHORS) {
            return List.of();
        }
        return authors.stream()
                .filter(author -> ranked(author.rank()))
                .filter(author -> matches(Stream.of(author.login())))
                .toList();
    }

    private boolean ranked(Rank rank) {
        return ranks.isEmpty() || ranks.contains(rank);
    }

    private boolean matches(Stream<String> fields) {
        if (query.isEmpty()) {
            return true;
        }
        String haystack = String.join("\n", fields.toList()).toLowerCase(Locale.ROOT);
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+")).allMatch(haystack::contains);
    }
}
