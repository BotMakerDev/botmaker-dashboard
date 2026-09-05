package com.botmaker.dashboard.umbrella;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The flags a preview would be run with — a command line, and nothing more.
 *
 * <p><b>It composes arguments; it decides nothing.</b> Which modules a release actually cuts, what version
 * each gets, what an unset flag defaults to, what forces what and what the gates say are all
 * {@code release.sh}'s answers, read back by {@link ReleasePlan}. This record's whole job is to spell what
 * the operator asked for in the script's own vocabulary.
 *
 * <p><b>{@code --dry-run} is appended by {@link #command()} and there is no way to leave it off.</b> Part B
 * of the plan visualises and does not execute: the terminal stays the only place a tag is pushed until Part C
 * owns the decision in typed code. Enforcing that here rather than in the button's handler means a second
 * caller cannot forget it.
 *
 * @param all           the level for {@code --all}, when every module is being asked at once. Empty means no
 *                      {@code --all}; a blank string means the bare flag, which the script reads as a patch.
 * @param modules       module name to version-or-level, in the order the operator's list has them. A blank
 *                      value is the bare flag. An explicit entry beats {@code --all} — the script's rule, not
 *                      one applied here, so both are passed and it decides.
 * @param force         {@code --force}
 * @param noWaitJitpack {@code --no-wait-jitpack}
 */
public record ReleaseSpec(Optional<String> all, Map<String, String> modules,
                          boolean force, boolean noWaitJitpack) {

    private static final String PREFIX = "botmaker-";

    /**
     * What {@code resolve_version} accepts: {@code x.y.z} or a bump level.
     *
     * <p>Checking it here is the one thing that looks like re-deciding and is not. It is the <i>grammar of an
     * argument</i>, the same category as knowing that the flag is spelled {@code --plugin-toolkit} — the
     * script states it in its own refusal (<i>"want x.y.z or patch|minor|major"</i>). What must never be
     * copied is what a level <i>resolves to</i>, which is a bump off that module's own latest tag.
     */
    private static final Pattern LEVEL_OR_VERSION =
            Pattern.compile("patch|minor|major|[0-9]+\\.[0-9]+\\.[0-9]+");

    public ReleaseSpec {
        modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
    }

    public static ReleaseSpec of(Optional<String> all, Map<String, String> modules,
                                 boolean force, boolean noWaitJitpack) {
        return new ReleaseSpec(all, modules, force, noWaitJitpack);
    }

    /**
     * The module flag for a module directory name — {@code botmaker-plugin-toolkit} to
     * {@code --plugin-toolkit}.
     *
     * <p>Derived rather than tabulated, and that is deliberate: a table here would be a third list this
     * module keeps (see {@code CLAUDE.md}), stale on the day an eleventh module is added. Every one of the
     * script's ten module flags is its directory name without the {@code botmaker-} prefix.
     */
    public static String flagFor(String module) {
        if (!module.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a module directory name: " + module);
        }
        return "--" + module.substring(PREFIX.length());
    }

    /** Whether a typed version or level is one the script will accept. Blank is the bare flag. */
    public static boolean wellFormed(String spec) {
        return spec.isBlank() || LEVEL_OR_VERSION.matcher(spec.strip()).matches();
    }

    /** Nothing selected: the script would refuse this with "nothing to release", so no preview is run. */
    public boolean empty() {
        return all.isEmpty() && modules.isEmpty();
    }

    /** The whole command, {@code ./release.sh} first and {@code --dry-run} last. */
    public List<String> command() {
        List<String> out = new ArrayList<>();
        out.add("./release.sh");
        all.ifPresent(level -> {
            out.add("--all");
            if (!level.isBlank()) {
                out.add(level.strip());
            }
        });
        modules.forEach((module, spec) -> {
            out.add(flagFor(module));
            if (!spec.isBlank()) {
                out.add(spec.strip());
            }
        });
        if (force) {
            out.add("--force");
        }
        if (noWaitJitpack) {
            out.add("--no-wait-jitpack");
        }
        out.add("--dry-run");
        return List.copyOf(out);
    }

    /**
     * The same thing as one line, for the operator to read and copy.
     *
     * <p>This is the tab's real deliverable rather than a convenience. The window cannot cut a release, so
     * what it hands back is the exact line to paste into a terminal — and the preview above it is that line's
     * own output, which is the only honest way to show what it would do.
     */
    public String commandLine() {
        return String.join(" ", command());
    }

    /** Runs it. Never on the FX thread — the decide pass shells to git ten times and runs Maven. */
    public ReleasePlan preview(Path umbrella) {
        return ReleasePlan.ask(umbrella, command());
    }
}
