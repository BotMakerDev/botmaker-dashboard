package com.botmaker.dashboard.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The merge job's sentence is read, never rewritten: its own words, marker stripped. */
class QueueTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theMergeJobsCommentIsItsReasonVerbatim() throws Exception {
        String comments = """
                [{"body": "Looks good to me"},
                 {"body": "<!-- botmaker-listing -->\\nWaiting: the release v0.2.0 has no jar."}]""";

        assertEquals("Waiting: the release v0.2.0 has no jar.", Queue.listingReason(JSON.readTree(comments)));
    }

    @Test
    void theLastMarkedCommentWins() throws Exception {
        // The job edits its one comment in place; a second marked one means an older run left one behind.
        String comments = """
                [{"body": "<!-- botmaker-listing --> needs a maintainer"},
                 {"body": "<!-- botmaker-listing --> merged"}]""";

        assertEquals("merged", Queue.listingReason(JSON.readTree(comments)));
    }

    @Test
    void noMarkedCommentIsBlank() throws Exception {
        assertEquals("", Queue.listingReason(JSON.readTree("[{\"body\": \"hi\"}]")));
        assertEquals("", Queue.listingReason(JSON.readTree("{}")));
        assertEquals("", Queue.listingReason(null));
    }
}
