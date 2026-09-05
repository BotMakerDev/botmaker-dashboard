package com.botmaker.dashboard.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode runs(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void everyCompletedRunGreenIsAPass() {
        Checks checks = Checks.of(runs("""
                {"check_runs": [
                  {"name": "validate", "status": "completed", "conclusion": "success"},
                  {"name": "lint", "status": "completed", "conclusion": "skipped"}]}
                """));
        assertEquals(Checks.Verdict.PASSED, checks.verdict());
        assertTrue(checks.text().contains("2"));
    }

    @Test
    void aFailureBeatsARunStillGoing() {
        // Nothing the second run concludes can make the first one pass, so the answer is already known and
        // the operator should not be shown "still running" while a refusal is sitting there.
        Checks checks = Checks.of(runs("""
                {"check_runs": [
                  {"name": "validate", "status": "completed", "conclusion": "failure"},
                  {"name": "slow", "status": "in_progress"}]}
                """));
        assertEquals(Checks.Verdict.FAILED, checks.verdict());
        assertTrue(checks.text().contains("validate"), checks.text());
    }

    @Test
    void whichRunIsRedIsNamed() {
        Checks checks = Checks.of(runs("""
                {"check_runs": [{"name": "validate", "status": "completed", "conclusion": "timed_out"}]}
                """));
        assertEquals("validate (timed_out)", checks.text());
    }

    @Test
    void noCheckRunIsNeitherAPassNorAFailure() {
        // The one place this differs from the release log, deliberately: ReleaseLog.Health reads "no run on
        // <tag>" as broken because a tag is finished, while a pull request opened a minute ago simply has
        // not been answered yet.
        assertEquals(Checks.Verdict.NONE, Checks.of(runs("{\"check_runs\": []}")).verdict());
        assertEquals("no check run yet", Checks.of(runs("{\"check_runs\": []}")).text());
    }

    @Test
    void aReadFailureIsReportedRatherThanGuessed() {
        Checks checks = Checks.of(null);
        assertEquals(Checks.Verdict.NONE, checks.verdict());
        assertTrue(checks.text().contains("could not read"));
    }
}
