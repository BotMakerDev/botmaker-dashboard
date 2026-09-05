package com.botmaker.dashboard;

import com.botmaker.shared.config.CacheDirs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one thing this app remembers between runs: where the umbrella checkout is.
 *
 * <p>Under {@link CacheDirs}, and <b>not</b> beside the credentials: this is a preference, that is a secret,
 * and the file holding a token is written {@code 0600} and merged rather than overwritten. Keeping them
 * apart means a corrupt preference can never cost a sign-in, and a sign-out can never forget which checkout
 * the operator uses.
 *
 * <p>Every read degrades to "not configured". A missing file is the first run; an unreadable one is a
 * directory the user can pick again in two clicks. Neither is worth an error dialog on startup.
 */
public record DashboardConfig(Path umbrella) {

    private static final Path FILE = CacheDirs.cacheRoot().resolve("dashboard.json");
    private static final String UMBRELLA_KEY = "umbrella";

    /**
     * The remembered umbrella root, empty when nothing has been chosen or the path no longer exists.
     *
     * <p>A separate name from the {@code umbrella()} component accessor on purpose: that one answers what
     * the file said, this one answers whether it is still true. A checkout can be moved or deleted between
     * two runs, and a stored path is not evidence that a directory exists.
     */
    public Optional<Path> remembered() {
        return Optional.ofNullable(umbrella).filter(Files::isDirectory);
    }

    public static DashboardConfig load() {
        try {
            if (Files.exists(FILE)) {
                JsonNode node = new ObjectMapper().readTree(FILE.toFile());
                String path = node.path(UMBRELLA_KEY).asText("");
                if (!path.isBlank()) return new DashboardConfig(Path.of(path));
            }
        } catch (Exception e) {
            System.err.println("Failed to read the dashboard config: " + e.getMessage());
        }
        return new DashboardConfig(null);
    }

    public static void save(DashboardConfig config) {
        try {
            Files.createDirectories(FILE.getParent());
            Map<String, String> all = new LinkedHashMap<>();
            if (config.umbrella != null) all.put(UMBRELLA_KEY, config.umbrella.toString());
            new ObjectMapper().writeValue(FILE.toFile(), all);
        } catch (Exception e) {
            System.err.println("Failed to store the dashboard config: " + e.getMessage());
        }
    }

    /**
     * Whether a directory really is the umbrella checkout.
     *
     * <p>Two files, chosen because they are the two this app actually reads: {@code release.sh} is what
     * every release view shells to, and {@code .gitmodules} is what makes the module list a fact rather
     * than a hard-coded array. A directory holding one and not the other is a checkout of something else.
     */
    public static boolean looksLikeUmbrella(Path dir) {
        return dir != null
                && Files.isRegularFile(dir.resolve("release.sh"))
                && Files.isRegularFile(dir.resolve(".gitmodules"));
    }
}
