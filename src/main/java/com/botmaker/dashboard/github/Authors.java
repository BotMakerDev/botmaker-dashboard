package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.Publishers;
import com.botmaker.cli.catalog.Rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The people behind the catalog: every row's owner and every account {@code publishers.json} ranks, so an admin
 * can rank an author who has listed nothing yet.
 *
 * <p>Owners are compared case-insensitively, as GitHub compares logins. {@code catalog.json} carries no account
 * id, so an author's id is {@code publishers.json}'s, or unknown until a rank is saved
 * ({@link CatalogEdits#setRank} asks GitHub then).
 */
public final class Authors {

    /**
     * One author card.
     *
     * @param id      the account id {@code publishers.json} gives, empty when it does not list the author
     * @param rank    {@code publishers.json}'s when it lists the author; otherwise the highest rank of their rows,
     *                which the catalog decided by account id (a login renamed since the file was written)
     * @param bots    their listed bots
     * @param plugins their listed plugins
     */
    public record Author(String login, Optional<Long> id, Rank rank, List<CatalogRow> bots,
                         List<CatalogRow> plugins) {
        public Author {
            bots = List.copyOf(bots);
            plugins = List.copyOf(plugins);
        }
    }

    private Authors() {
    }

    /** One author per login, Official first, then by login. */
    public static List<Author> of(List<CatalogRow> rows, Publishers publishers) {
        Map<String, Builder> byLogin = new LinkedHashMap<>();
        for (Publishers.Publisher p : publishers.admin()) {
            byLogin.computeIfAbsent(key(p.login()), k -> new Builder(p.login())).listed(p.id(), Rank.ADMIN);
        }
        for (Publishers.Publisher p : publishers.trusted()) {
            byLogin.computeIfAbsent(key(p.login()), k -> new Builder(p.login())).listed(p.id(), Rank.TRUSTED);
        }
        for (CatalogRow row : rows) {
            byLogin.computeIfAbsent(key(row.owner()), k -> new Builder(row.owner())).add(row);
        }
        return byLogin.values().stream().map(Builder::build)
                .sorted(Comparator.comparing(Author::rank)
                        .thenComparing(Author::login, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String key(String login) {
        return login.toLowerCase(Locale.ROOT);
    }

    private static final class Builder {
        private final String login;
        private Long id;
        private Rank listed;
        private Rank fromRows;
        private final List<CatalogRow> bots = new ArrayList<>();
        private final List<CatalogRow> plugins = new ArrayList<>();

        Builder(String login) {
            this.login = login;
        }

        /** Admin wins over trusted, as the catalog ranks an account listed twice. */
        void listed(long id, Rank rank) {
            this.id = id;
            if (listed == null || rank.compareTo(listed) < 0) {
                listed = rank;
            }
        }

        void add(CatalogRow row) {
            (row.kind() == CatalogRow.Kind.BOT ? bots : plugins).add(row);
            if (fromRows == null || row.rank().compareTo(fromRows) < 0) {
                fromRows = row.rank();
            }
        }

        Author build() {
            Rank rank = listed != null ? listed : fromRows != null ? fromRows : Rank.COMMUNITY;
            return new Author(login, Optional.ofNullable(id), rank, bots, plugins);
        }
    }
}
