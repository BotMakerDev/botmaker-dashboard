package com.botmaker.dashboard.umbrella;

import java.time.Duration;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code ./release.sh --all --dry-run} decided, read back out of its own output.
 *
 * <p><b>This class parses; it never decides.</b> Whether a module has something publishable since its tag is
 * {@code change_kind}/{@code is_release_irrelevant}'s answer, and re-deriving it here — "markdown does not
 * count", "a module with no tag always releases" — would be a second implementation that diverges on the
 * first rule added and is discovered as a bad tag, which cannot be edited. So the window asks and reads.
 *
 * <p>The decide pass prints one line per module it was asked about:
 *
 * <pre>
 * ==&gt; Deciding what to release:
 *     botmaker-cli: releasing v0.0.13
 *     botmaker-pilot: no changes since its latest tag — skipping
 *     botmaker-studio-api: only docs since v0.0.4 — skipping (the artifact would be identical; --force overrides)
 * </pre>
 *
 * <p>Everything before that heading is the plan the flags asked for, and everything after it is the gates —
 * neither is a verdict about drift, so only the block between is read. A module that never appears is one
 * the script does not release at all ({@code botmaker-gallery}, {@code botmaker-plugin-registry}, and this
 * module), which is how "is it releasable" is answered without a list here.
 *
 * <p><b>A non-zero exit is an ordinary outcome and the verdicts still stand.</b> The gates run <i>after</i>
 * the decide pass, so a run that dies on {@code check_api_pointers} has already printed everything this
 * class reads. {@link #raw()} keeps the whole output so the window can show why it stopped.
 */
public record ReleasePlan(Map<String, Verdict> verdicts, int exit, String raw) {

    /** ANSI SGR sequences — {@code info()} colours its headings, and a colour code is not part of a name. */
    private static final Pattern ANSI = Pattern.compile("\\[[0-9;]*m");

    private static final String HEADING = "Deciding what to release:";

    /** {@code     botmaker-cli: releasing v0.0.13} */
    private static final Pattern DECISION = Pattern.compile("^\\s+(botmaker-[a-z0-9-]+):\\s+(.*\\S)\\s*$");

    private static final Pattern RELEASING = Pattern.compile("^releasing v(\\S+)$");

    /**
     * One module's decision.
     *
     * @param version the version being cut, empty when the module was skipped
     * @param text    the script's own sentence, shown verbatim — the two skips have different causes
     *                ("no changes" vs "only docs"), and paraphrasing them here would lose the difference
     */
    public record Verdict(String module, Optional<String> version, String text) {
        public boolean releasing() {
            return version.isPresent();
        }
    }

    /** The plan for a module the script never named — data-only, or this one. */
    public Optional<Verdict> forModule(String module) {
        return Optional.ofNullable(verdicts.get(module));
    }

    /** Whether the run got as far as printing a decide pass at all. */
    public boolean decided() {
        return !verdicts.isEmpty();
    }

    /**
     * Runs {@code ./release.sh --all --dry-run} in the umbrella checkout and parses it. Never on the FX
     * thread: the pass shells to git in ten repositories and runs the SDK's pointer test through Maven.
     */
    public static ReleasePlan ask(Path umbrella) {
        Proc p = Proc.run(umbrella, Duration.ofMinutes(6), "./release.sh", "--all", "--dry-run");
        return parse(p.out(), p.exit());
    }

    public static ReleasePlan parse(String output, int exit) {
        Map<String, Verdict> verdicts = new LinkedHashMap<>();
        boolean inDecide = false;
        for (String line : ANSI.matcher(output).replaceAll("").split("\n", -1)) {
            if (!inDecide) {
                inDecide = line.contains(HEADING);
                continue;
            }
            Matcher m = DECISION.matcher(line);
            if (!m.matches()) {
                // The next heading, a blank line or a gate's first line: the decide block is over. Stopping
                // rather than skipping matters — the gates print module-prefixed lines of their own.
                if (!line.isBlank()) {
                    break;
                }
                continue;
            }
            String module = m.group(1);
            String text = m.group(2);
            Matcher rel = RELEASING.matcher(text);
            verdicts.put(module, new Verdict(module,
                    rel.matches() ? Optional.of(rel.group(1)) : Optional.empty(), text));
        }
        return new ReleasePlan(Map.copyOf(verdicts), exit, output);
    }
}
