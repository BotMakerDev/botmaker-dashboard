package com.botmaker.dashboard.github;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fields the Queue's detail lists for a catalog file at a pull request's head. */
class QueueTest {

    @Test
    void anEntryListsItsFieldsInTheFilesOrder() {
        assertEquals(List.of(new Queue.Field("repo", "somedev/firestone"), new Queue.Field("tag", "v2.0.0"),
                        new Queue.Field("detached", "true")),
                Queue.fields("{\"repo\": \"somedev/firestone\", \"tag\": \"v2.0.0\", \"detached\": true}"));
    }

    @Test
    void theRanksListTheirLogins() {
        assertEquals(List.of(new Queue.Field("admin", "LiQiyeDev, BotMakerDev"), new Queue.Field("trusted", "")),
                Queue.fields("""
                        {"admin": [{"id": 298085676, "login": "LiQiyeDev"}, {"id": 330995542, "login": "BotMakerDev"}],
                         "trusted": []}
                        """));
    }

    @Test
    void textThatIsNotAnObjectHasNoFields() {
        assertTrue(Queue.fields("[1]").isEmpty());
        assertTrue(Queue.fields("{ not json").isEmpty());
        assertTrue(Queue.fields(null).isEmpty());
    }
}
