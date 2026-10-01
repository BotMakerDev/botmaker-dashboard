package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which answer a finished release's lane shows: the polled one or the log's. */
class PastProgressTest {

    /** sdk v1.2.3 read Failed with a green log, from a poll Refresh had interrupted (2026-10-01). */
    @Test
    void aPollThatGotNoAnswerDoesNotHideTheLogsGreenOne() {
        assertFalse(PastProgress.outranks("no run on v1.2.3", "success"));
        assertFalse(PastProgress.outranks("unknown (interrupted)", "ok (resolves clean)"));
        assertFalse(PastProgress.outranks("", "success"));
    }

    @Test
    void anyRealAnswerIsNewerThanTheLog() {
        assertTrue(PastProgress.outranks("failure", "success"), "a re-run that failed is news");
        assertTrue(PastProgress.outranks("BROKEN", "ok (resolves clean)"));
        assertTrue(PastProgress.outranks("success", "running"));
        assertTrue(PastProgress.outranks("no run on v1.0.0", ""), "with nothing logged it is all there is");
    }
}
