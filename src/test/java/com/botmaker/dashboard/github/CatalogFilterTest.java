package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.Publishers;
import com.botmaker.cli.catalog.Rank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogFilterTest {

    private static final List<CatalogRow> ROWS = Catalog.read(CatalogTest.CATALOG).rows();
    private static final List<Authors.Author> AUTHORS = Authors.of(ROWS, new Publishers(List.of(), List.of()));

    private static List<String> names(CatalogFilter filter) {
        return filter.apply(ROWS).stream().map(CatalogRow::name).toList();
    }

    @Test
    void allShowsBothKindsAndNoAuthors() {
        assertEquals(3, CatalogFilter.ALL.apply(ROWS).size());
        assertTrue(CatalogFilter.ALL.applyAuthors(AUTHORS).isEmpty());
    }

    @Test
    void aKindChipHidesTheOtherKind() {
        assertEquals(List.of("firestone"), names(new CatalogFilter(CatalogFilter.View.BOTS, Set.of(), "")));
        assertEquals(List.of("BotMaker SDK", "zoom"),
                names(new CatalogFilter(CatalogFilter.View.PLUGINS, Set.of(), "")));
    }

    @Test
    void theAuthorsChipShowsAuthorsOnly() {
        CatalogFilter authors = new CatalogFilter(CatalogFilter.View.AUTHORS, Set.of(Rank.ADMIN), "");
        assertTrue(authors.apply(ROWS).isEmpty());
        assertEquals(List.of("BotMakerDev"), authors.applyAuthors(AUTHORS).stream().map(Authors.Author::login)
                .toList());
    }

    @Test
    void rankChipsAddUp() {
        assertEquals(List.of("BotMaker SDK"), names(new CatalogFilter(CatalogFilter.View.ALL, Set.of(Rank.ADMIN), "")));
        assertEquals(3, new CatalogFilter(CatalogFilter.View.ALL, Set.of(Rank.ADMIN, Rank.COMMUNITY), "")
                .apply(ROWS).size());
        assertTrue(new CatalogFilter(CatalogFilter.View.ALL, Set.of(Rank.TRUSTED), "").apply(ROWS).isEmpty());
    }

    @Test
    void theSearchMatchesNameOwnerAndTagsCaseInsensitively() {
        assertEquals(List.of("BotMaker SDK"), names(new CatalogFilter(CatalogFilter.View.ALL, Set.of(), "sdk")));
        assertEquals(List.of("zoom", "firestone"), names(new CatalogFilter(CatalogFilter.View.ALL, Set.of(), "SOMEDEV")));
        assertEquals(List.of("firestone"), names(new CatalogFilter(CatalogFilter.View.ALL, Set.of(), "idle")));
        assertEquals(List.of("firestone"), names(new CatalogFilter(CatalogFilter.View.ALL, Set.of(), "somedev idle")),
                "every word must match");
        assertTrue(names(new CatalogFilter(CatalogFilter.View.ALL, Set.of(), "devidle")).isEmpty(),
                "a word never spans two fields");
        assertEquals(List.of("somedev"), new CatalogFilter(CatalogFilter.View.AUTHORS, Set.of(), "some")
                .applyAuthors(AUTHORS).stream().map(Authors.Author::login).toList());
    }
}
