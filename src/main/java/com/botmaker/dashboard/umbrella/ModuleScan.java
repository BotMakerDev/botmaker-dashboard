package com.botmaker.dashboard.umbrella;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads one umbrella checkout into a row per module.
 *
 * <p>The order of the work is the interesting part. Git is asked first, in every module, because those
 * answers are local, fast and needed twice — once for the module's own tag and again as the <i>upstream</i>
 * tag a sibling's {@code .deps.env} pin is judged against. {@code release.sh} is asked once, for the whole
 * constellation, because its decide pass covers every module in a single run and ten separate runs would be
 * ten times the work for the same answer.
 *
 * <p>Nothing here runs on the FX thread.
 */
public final class ModuleScan {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(20);

    /** A whole scan: the rows, and the {@code release.sh} run they quote. */
    public record Scan(List<ModuleRow> rows, ReleasePlan plan) {
    }

    private ModuleScan() {
    }

    public static Scan scan(Path umbrella) {
        List<String> modules = Umbrella.modules(read(umbrella.resolve(".gitmodules")).orElse(""));

        // Pass one: git, per module. The tags map is what makes a sibling's pin judgeable.
        Map<String, Optional<String>> tags = new LinkedHashMap<>();
        Map<String, Integer> ahead = new LinkedHashMap<>();
        Map<String, Boolean> dirty = new LinkedHashMap<>();
        for (String module : modules) {
            Path dir = umbrella.resolve(module);
            Optional<String> tag = latestTag(dir);
            tags.put(module, tag);
            ahead.put(module, tag.map(t -> commitsSince(dir, t)).orElse(0));
            dirty.put(module, isDirty(dir));
        }
        Map<String, String> latestTags = new LinkedHashMap<>();
        tags.forEach((module, tag) -> tag.ifPresent(t -> latestTags.put(module, t)));

        // Pass two: the script, once.
        ReleasePlan plan = ReleasePlan.ask(umbrella);

        List<ModuleRow> rows = new ArrayList<>();
        for (String module : modules) {
            Path dir = umbrella.resolve(module);
            List<DepsEnv.Pin> pins = read(dir.resolve(".deps.env"))
                    .map(text -> DepsEnv.parse(text, latestTags))
                    .orElse(List.of());
            ModuleRow.ChangelogState changelog = read(dir.resolve("CHANGELOG.md"))
                    .map(text -> Changelog.hasUnreleased(text)
                            ? ModuleRow.ChangelogState.UNRELEASED
                            : ModuleRow.ChangelogState.NONE)
                    .orElse(ModuleRow.ChangelogState.ABSENT);
            rows.add(new ModuleRow(module, tags.get(module), ahead.get(module), dirty.get(module),
                    plan.forModule(module), pins, changelog));
        }
        return new Scan(List.copyOf(rows), plan);
    }

    /**
     * The module's newest tag by version order.
     *
     * <p>Tags here are v-prefixed or bare (the SDK's older ones are), which is why the tag <i>name</i> is
     * taken from git rather than composed from a version: {@code v1.1.6} and {@code 1.1.6} are both real
     * refs in this constellation and only one of them exists in any given repository.
     */
    private static Optional<String> latestTag(Path dir) {
        Proc p = Proc.run(dir, GIT_TIMEOUT, "git", "tag", "--list", "--sort=-v:refname");
        if (!p.ok()) {
            return Optional.empty();
        }
        String first = p.firstLine();
        return first.isEmpty() ? Optional.empty() : Optional.of(first);
    }

    /** How many commits HEAD is past that tag. 0 when the tag is HEAD, or when git could not say. */
    private static int commitsSince(Path dir, String tag) {
        Proc p = Proc.run(dir, GIT_TIMEOUT, "git", "rev-list", "--count", tag + "..HEAD");
        if (!p.ok()) {
            return 0;
        }
        try {
            return Integer.parseInt(p.firstLine());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Whether the working tree has uncommitted changes.
     *
     * <p>{@code release.sh}'s {@code preflight} refuses a dirty module for a real release and only warns in
     * a dry run — so a dirty row is showing the maintainer the thing that would stop the release the moment
     * they drop {@code --dry-run}.
     */
    private static boolean isDirty(Path dir) {
        Proc p = Proc.run(dir, GIT_TIMEOUT, "git", "status", "--porcelain");
        return p.ok() && !p.out().isBlank();
    }

    private static Optional<String> read(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
