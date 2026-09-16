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
 * What this app remembers between runs: where the umbrella checkout is, and — since 2026-09-16 — which
 * palette the operator picked, {@code null} meaning "follow the desktop".
 *
 * <p>Both keys are written on every save and each setter keeps the other, so choosing a checkout can never
 * forget a theme and toggling the theme can never forget the checkout.
 *
 * <p>Under {@link CacheDirs}, and <b>not</b> beside the credentials: this is a preference, that is a secret,
 * and the file holding a token is written {@code 0600} and merged rather than overwritten. Keeping them
 * apart means a corrupt preference can never cost a sign-in, and a sign-out can never forget which checkout
 * the operator uses.
 *
 * <p>Every read degrades to "not configured". A missing file is the first run; an unreadable one is a
 * directory the user can pick again in two clicks. Neither is worth an error dialog on startup.
 */
public record DashboardConfig(Path umbrella, Theme theme) {

    private static final Path FILE = CacheDirs.cacheRoot().resolve("dashboard.json");
    private static final String UMBRELLA_KEY = "umbrella";
    private static final String THEME_KEY = "theme";

    public DashboardConfig withUmbrella(Path root) {
        return new DashboardConfig(root, theme);
    }

    public DashboardConfig withTheme(Theme chosen) {
        return new DashboardConfig(umbrella, chosen);
    }

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
        return load(FILE);
    }

    public static void save(DashboardConfig config) {
        save(config, FILE);
    }

    static DashboardConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                JsonNode node = new ObjectMapper().readTree(file.toFile());
                String path = node.path(UMBRELLA_KEY).asText("");
                Theme theme = Theme.fromId(node.path(THEME_KEY).asText(""));
                return new DashboardConfig(path.isBlank() ? null : Path.of(path), theme);
            }
        } catch (Exception e) {
            System.err.println("Failed to read the dashboard config: " + e.getMessage());
        }
        return new DashboardConfig(null, null);
    }

    static void save(DashboardConfig config, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Map<String, String> all = new LinkedHashMap<>();
            if (config.umbrella != null) all.put(UMBRELLA_KEY, config.umbrella.toString());
            if (config.theme != null) all.put(THEME_KEY, config.theme.id());
            new ObjectMapper().writeValue(file.toFile(), all);
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
