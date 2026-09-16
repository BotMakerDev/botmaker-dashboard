package com.botmaker.dashboard.umbrella;

import com.botmaker.shared.config.CacheDirs;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the Releases tab last heard from JitPack and Actions about each tag, so the tab opens instantly.
 *
 * <p>Polling a release's verdicts is a {@code gh} call and an HTTP request per module; the history is dozens of
 * releases. So verdicts are kept under {@link CacheDirs} ({@code releases-cache.json}), keyed by
 * {@code module@tag}, each with the moment it was asked. <b>A cache is not a record</b> — the committed log is —
 * so nothing here is ever written back into a repository.
 *
 * <p><b>Settled verdicts are not asked again unless somebody asks.</b> A tag's CI runs that finished
 * {@code success} or {@code FAILED} will not change, and neither will a pom JitPack has published or a
 * clean-room resolve that answered. Everything else — {@code running}, {@code no run on …}, {@code missing},
 * {@code unknown} — goes stale after {@link #STALE}.
 */
public final class VerdictCache {

    /** How long an unsettled verdict is shown before it is polled again. */
    public static final Duration STALE = Duration.ofMinutes(10);

    private static final Path FILE = CacheDirs.cacheRoot().resolve("releases-cache.json");
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * One tag's verdicts. Either half may be blank — not asked yet.
     *
     * @param jitpack        {@code published (pom HEAD)}, {@code missing (pom HEAD)}, or the clean room's
     *                       {@code ok (resolves clean)} / {@code BROKEN}
     * @param jitpackError   the clean room's error text, when it had one
     * @param actions        {@code Actions.poll}'s verdict cell
     * @param actionsError   its failing runs and their excerpt
     */
    public record Entry(String jitpack, String jitpackError, long jitpackAt,
                        String actions, String actionsError, long actionsAt) {

        public static final Entry EMPTY = new Entry("", "", 0, "", "", 0);

        public Entry withJitpack(String verdict, String error, Instant at) {
            return new Entry(verdict, error, at.toEpochMilli(), actions, actionsError, actionsAt);
        }

        public Entry withActions(String verdict, String error, Instant at) {
            return new Entry(jitpack, jitpackError, jitpackAt, verdict, error, at.toEpochMilli());
        }

        public boolean jitpackStale(Instant now) {
            return !settledJitpack(jitpack) && older(jitpackAt, now);
        }

        public boolean actionsStale(Instant now) {
            return !settledActions(actions) && older(actionsAt, now);
        }

        public Instant jitpackTime() {
            return Instant.ofEpochMilli(jitpackAt);
        }

        public Instant actionsTime() {
            return Instant.ofEpochMilli(actionsAt);
        }
    }

    private final Path file;
    private final Map<String, Entry> entries;

    private VerdictCache(Path file, Map<String, Entry> entries) {
        this.file = file;
        this.entries = entries;
    }

    public static VerdictCache load() {
        return load(FILE);
    }

    /** Reads the file; a missing or unreadable one is an empty cache, because a cache is only ever a speed-up. */
    public static VerdictCache load(Path file) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                entries.putAll(JSON.readValue(file.toFile(), new TypeReference<Map<String, Entry>>() {
                }));
            } catch (IOException e) {
                // Start again rather than refuse to open the tab.
            }
        }
        return new VerdictCache(file, entries);
    }

    public static String key(String module, String tag) {
        return module + "@" + tag;
    }

    public synchronized Entry get(String module, String tag) {
        return entries.getOrDefault(key(module, tag), Entry.EMPTY);
    }

    public synchronized Optional<Entry> find(String module, String tag) {
        return Optional.ofNullable(entries.get(key(module, tag)));
    }

    public synchronized void put(String module, String tag, Entry entry) {
        entries.put(key(module, tag), entry);
    }

    /** Writes the file. Best effort: a cache that cannot be saved costs a re-poll next time, nothing more. */
    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
        } catch (IOException e) {
            // Nothing to do.
        }
    }

    static boolean settledJitpack(String verdict) {
        return verdict.startsWith("published") || verdict.startsWith("ok (resolves") || verdict.startsWith("BROKEN");
    }

    /**
     * {@code Actions.verdict}'s words: {@code success (n)} and {@code FAILED — <runs>} are final — a failed run
     * stays failed even while a sibling still runs. {@code running (…)}, {@code no run on …} and
     * {@code unknown (…)} are not.
     */
    static boolean settledActions(String verdict) {
        return verdict.startsWith("success") || verdict.startsWith("FAILED");
    }

    private static boolean older(long at, Instant now) {
        return Duration.between(Instant.ofEpochMilli(at), now).compareTo(STALE) > 0;
    }

    /** {@code 5m ago}, {@code 3h ago}, {@code 2d ago} — how old a shown verdict is. */
    public static String age(Instant at, Instant now) {
        if (at.toEpochMilli() == 0) {
            return "never";
        }
        Duration d = Duration.between(at, now);
        if (d.toMinutes() < 1) {
            return "just now";
        }
        if (d.toHours() < 1) {
            return d.toMinutes() + "m ago";
        }
        if (d.toDays() < 1) {
            return d.toHours() + "h ago";
        }
        return d.toDays() + "d ago";
    }
}
