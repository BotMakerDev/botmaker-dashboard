package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Plan;
import com.botmaker.cli.release.ReleaseRefusal;
import com.botmaker.cli.release.Requested;

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
 * tag a sibling's {@code .deps.env} pin is judged against. The decide pass runs once, for the whole
 * constellation, because it covers every module in a single call and ten separate calls would be ten times
 * the work for the same answer.
 *
 * <p><b>The decide pass is {@link Plan#decide}, not {@code ./release.sh --all --dry-run}.</b> It was the
 * script until 2026-09-16, with {@code ReleasePlan} reading module verdicts back out of its stdout — which
 * kept the rule <i>never reimplement a decision the release owns</i> by keeping the decision out of reach,
 * behind a pipe and a regular expression. Calling the library keeps the same rule in its strict form: one
 * implementation, and every caller reaches it. What goes with the script is a parser that could
 * mis-read a line the script reworded, and a subprocess per scan.
 *
 * <p>Nothing here runs on the FX thread.
 */
public final class ModuleScan {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(20);

    /**
     * A whole scan: the rows, the decide pass they quote, and its output as text.
     *
     * @param plan   empty when the pass refused before deciding — an unreadable checkout, a missing
     *               {@code origin}. An empty plan and a plan that decided to release nothing are different
     *               things and the tab says which
     * @param output what the pass would have printed, which is what the Modules tab shows on demand
     * @param error  the refusal's own sentence, when there was one
     */
    public record Scan(List<ModuleRow> rows, Optional<Plan> plan, String output, Optional<String> error) {

        public boolean decided() {
            return plan.isPresent();
        }
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

        // Pass two: the decide pass, once, for every module at the level a bare flag means.
        Optional<Plan> plan;
        String output;
        Optional<String> error;
        try {
            Plan decided = Plan.decide(umbrella, Requested.of(Optional.of(""), Map.of()), false);
            plan = Optional.of(decided);
            output = String.join("\n", concat("Release plan:", decided.planLines(),
                    "Deciding what to release:", decided.decisionLines()));
            error = Optional.empty();
        } catch (ReleaseRefusal refused) {
            plan = Optional.empty();
            output = "error: " + refused.getMessage();
            error = Optional.of(refused.getMessage());
        }

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
                    decisionFor(plan, module), pins, changelog));
        }
        return new Scan(List.copyOf(rows), plan, output, error);
    }

    /**
     * What the pass said about one submodule directory, or empty.
     *
     * <p>Empty covers two cases the Modules tab keeps apart from each other only by what else it shows:
     * the pass refused before deciding anything, and the pass ran and never named this directory — which is
     * how {@code botmaker-gallery}, {@code botmaker-plugin-registry} and this repository are told apart from
     * the eleven, with no list kept here.
     */
    private static Optional<Plan.Decision> decisionFor(Optional<Plan> plan, String directory) {
        return plan.flatMap(decided -> Module.byDirectory(directory)
                .flatMap(module -> decided.decisions().stream()
                        .filter(decision -> decision.module() == module)
                        .findFirst()));
    }

    private static List<String> concat(String head, List<String> first, String mid, List<String> second) {
        List<String> out = new ArrayList<>();
        out.add(head);
        out.addAll(first);
        out.add(mid);
        out.addAll(second);
        return out;
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
