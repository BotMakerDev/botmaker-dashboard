package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.Publishers;
import com.botmaker.cli.catalog.Rank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthorsTest {

    private static final List<CatalogRow> ROWS = Catalog.read(CatalogTest.CATALOG).rows();

    @Test
    void everyOwnerIsAnAuthorWithTheirBotsAndPlugins() {
        List<Authors.Author> authors = Authors.of(ROWS, new Publishers(List.of(), List.of()));
        assertEquals(List.of("BotMakerDev", "somedev"), authors.stream().map(Authors.Author::login).toList(),
                "Official first, then by login");
        Authors.Author somedev = authors.getLast();
        assertEquals(1, somedev.bots().size());
        assertEquals(1, somedev.plugins().size());
        assertEquals(Rank.COMMUNITY, somedev.rank());
        assertEquals(Optional.empty(), somedev.id(), "catalog.json carries no account id");
        assertEquals(Rank.ADMIN, authors.getFirst().rank(), "unlisted: the rank the catalog gave its rows");
    }

    @Test
    void publishersJsonGivesTheIdAndTheRankAndAddsTheUnlisted() {
        Publishers publishers = new Publishers(
                List.of(new Publishers.Publisher(330995542, "BotMakerDev"),
                        new Publishers.Publisher(298085676, "LiQiyeDev")),
                List.of(new Publishers.Publisher(77, "SOMEDEV")));
        List<Authors.Author> authors = Authors.of(ROWS, publishers);
        assertEquals(List.of("BotMakerDev", "LiQiyeDev", "SOMEDEV"),
                authors.stream().map(Authors.Author::login).toList(), "an admin with nothing listed still appears");
        Authors.Author somedev = authors.getLast();
        assertEquals(Rank.TRUSTED, somedev.rank(), "the file's rank, ahead of the catalog's next build");
        assertEquals(Optional.of(77L), somedev.id());
        assertEquals(2, somedev.bots().size() + somedev.plugins().size(), "logins compared case-insensitively");
        assertEquals(0, authors.get(1).bots().size());
    }
}
