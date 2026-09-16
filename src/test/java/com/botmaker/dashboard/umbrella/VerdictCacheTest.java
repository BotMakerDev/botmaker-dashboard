package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which cached verdicts are asked again, and the file surviving a restart. */
class VerdictCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Test
    void anUnsettledVerdictGoesStaleAfterTenMinutes() {
        VerdictCache.Entry running = VerdictCache.Entry.EMPTY
                .withActions("running (1 of 2)", "", NOW.minusSeconds(9 * 60))
                .withJitpack("missing (pom HEAD)", "", NOW.minusSeconds(11 * 60));

        assertFalse(running.actionsStale(NOW));
        assertTrue(running.jitpackStale(NOW));
        assertTrue(running.withActions("no run on v0.1.0", "", NOW.minusSeconds(3600)).actionsStale(NOW));
    }

    @Test
    void aSettledVerdictIsNeverStale() {
        Instant weekAgo = NOW.minusSeconds(7 * 24 * 3600);
        VerdictCache.Entry settled = VerdictCache.Entry.EMPTY
                .withActions("FAILED — CI", "CI: failure — url", weekAgo)
                .withJitpack("published (pom HEAD)", "", weekAgo);

        assertFalse(settled.actionsStale(NOW));
        assertFalse(settled.jitpackStale(NOW));
        assertFalse(settled.withActions("success (2)", "", weekAgo).actionsStale(NOW));
        assertFalse(settled.withJitpack("BROKEN", "Could not find artifact", weekAgo).jitpackStale(NOW));
        assertFalse(settled.withJitpack("ok (resolves clean)", "", weekAgo).jitpackStale(NOW));
        assertTrue(settled.withJitpack("unknown (HttpTimeoutException)", "", weekAgo).jitpackStale(NOW));
    }

    @Test
    void theFileRoundTripsAndABrokenFileIsAnEmptyCache(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub/releases-cache.json");
        VerdictCache cache = VerdictCache.load(file);
        assertEquals(VerdictCache.Entry.EMPTY, cache.get("botmaker-sdk", "v1.2.0"));

        VerdictCache.Entry entry = VerdictCache.Entry.EMPTY.withActions("FAILED — CI", "CI: failure — url\n[ERROR] x", NOW);
        cache.put("botmaker-sdk", "v1.2.0", entry);
        cache.save();

        assertEquals(entry, VerdictCache.load(file).get("botmaker-sdk", "v1.2.0"));

        Files.writeString(file, "{ not json");
        assertTrue(VerdictCache.load(file).find("botmaker-sdk", "v1.2.0").isEmpty());
    }

    @Test
    void anAgeReadsInTheLargestWholeUnit() {
        assertEquals("never", VerdictCache.age(Instant.EPOCH, NOW));
        assertEquals("just now", VerdictCache.age(NOW.minusSeconds(20), NOW));
        assertEquals("5m ago", VerdictCache.age(NOW.minusSeconds(300), NOW));
        assertEquals("2h ago", VerdictCache.age(NOW.minusSeconds(7300), NOW));
        assertEquals("3d ago", VerdictCache.age(NOW.minusSeconds(3 * 86400 + 60), NOW));
    }
}
