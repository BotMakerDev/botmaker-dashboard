package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Requested;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the operator asked for: which modules, at what level, and the two run flags.
 *
 * <p><b>It composes a request; it decides nothing.</b> Which modules a release actually cuts, what version
 * each gets, what an unset flag defaults to, what forces what and what the gates say are all
 * {@code com.botmaker.cli.release}'s answers, reached through {@link ReleaseRun}. This record's whole job is
 * to spell what was ticked in that library's vocabulary.
 *
 * <p><b>{@code --dry-run} used to be appended here and cannot be, any more.</b> Until 2026-09-16 this class
 * put it on every command line with no way to leave it off, because the window shelled to
 * {@code release.sh} and the terminal was the only place a tag could be pushed. A preview is now a
 * {@code Runner} rather than a flag, so the choice belongs to whoever builds the runner — see
 * {@link ReleaseRun#go} — and the safety that was enforced here has moved to the Release tab's Execute
 * gate, where it can say what it is refusing and why.
 *
 * <p>{@link #commandLine()} still exists and still matters: it is the line to type if the window cannot
 * finish, and it names {@code botmaker release}, which is the same library this call reaches.
 *
 * @param all           the level for {@code --all}, when every module is being asked at once. Empty means no
 *                      {@code --all}; a blank string is the bare flag, which means a patch.
 * @param modules       module to version-or-level, in the order the operator's list has them. A blank value
 *                      is the bare flag. An explicit entry beats {@code --all} — {@link Requested}'s rule,
 *                      not one applied here.
 * @param force         {@code --force}
 * @param noWaitJitpack {@code --no-wait-jitpack}
 */
public record ReleaseSpec(Optional<String> all, Map<Module, String> modules,
                          boolean force, boolean noWaitJitpack) {

    /**
     * What {@code resolve_version} accepts: {@code x.y.z} or a bump level.
     *
     * <p>Checking it here is the one thing that looks like re-deciding and is not. It is the <i>grammar of an
     * argument</i>, the same category as knowing that the flag is spelled {@code --plugin-toolkit} — the
     * library states it in its own refusal (<i>"want x.y.z or patch|minor|major"</i>). What must never be
     * copied is what a level <i>resolves to</i>, which is a bump off that module's own latest tag.
     */
    private static final Pattern LEVEL_OR_VERSION =
            Pattern.compile("patch|minor|major|[0-9]+\\.[0-9]+\\.[0-9]+");

    public ReleaseSpec {
        modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
    }

    /** Whether a typed version or level is one the library will accept. Blank is the bare flag. */
    public static boolean wellFormed(String spec) {
        return spec.isBlank() || LEVEL_OR_VERSION.matcher(spec.strip()).matches();
    }

    /** Nothing selected: the library would refuse this with "nothing to release", so no run is started. */
    public boolean empty() {
        return all.isEmpty() && modules.isEmpty();
    }

    /**
     * The request {@code Plan.decide} takes.
     *
     * <p>The {@code --all} expansion and the rule that an explicit module beats it are
     * {@link Requested}'s — the same code {@code botmaker release} and the release workflow reach, so this
     * window cannot answer that question differently from the terminal.
     */
    public Map<Module, String> requested() {
        Map<Module, String> explicit = new LinkedHashMap<>();
        modules.forEach((module, spec) -> explicit.put(module, spec.isBlank() ? "patch" : spec.strip()));
        return Requested.of(all, explicit);
    }

    /**
     * The whole command as {@code botmaker release} takes it.
     *
     * <p>{@code --execute} is the caller's argument rather than a field, because a spec is what was asked
     * for and executing is a decision taken later, at a button with a confirmation behind it. Spelling both
     * lines from one method is also what keeps them honest: the preview line and the release line differ by
     * exactly that word.
     */
    public List<String> command(boolean execute) {
        List<String> out = new ArrayList<>();
        out.add("botmaker");
        out.add("release");
        all.ifPresent(level -> {
            out.add("--all");
            if (!level.isBlank()) {
                out.add(level.strip());
            }
        });
        modules.forEach((module, spec) -> {
            out.add(module.flag());
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
        if (execute) {
            out.add("--execute");
        }
        return List.copyOf(out);
    }

    /**
     * The flags back into a spec — {@link #command}'s inverse, for {@link ReleaseJob}, which receives them on
     * its command line.
     *
     * <p>The grammar is {@code botmaker release}'s and only that: a flag, then an optional word that is not
     * itself a flag. {@code --execute} is accepted and ignored, because the child is told to execute by
     * being started at all. Anything else is refused rather than dropped — a flag the child did not
     * understand is a release different from the one that was previewed.
     *
     * @param flags everything after {@code botmaker release}
     * @throws IllegalArgumentException naming the first argument that is not a flag of the release
     */
    public static ReleaseSpec parse(List<String> flags) {
        Optional<String> all = Optional.empty();
        Map<Module, String> modules = new LinkedHashMap<>();
        boolean force = false;
        boolean noWait = false;
        for (int i = 0; i < flags.size(); i++) {
            String flag = flags.get(i);
            String value = i + 1 < flags.size() && !flags.get(i + 1).startsWith("--") ? flags.get(i + 1) : null;
            switch (flag) {
                case "--force" -> force = true;
                case "--no-wait-jitpack" -> noWait = true;
                case "--execute" -> {
                    // The child executes by being started; the word is harmless and so accepted.
                }
                case "--all" -> {
                    all = Optional.of(value == null ? "" : value);
                    i += value == null ? 0 : 1;
                }
                default -> {
                    Module module = Module.byFlag(flag)
                            .orElseThrow(() -> new IllegalArgumentException("unknown arg: " + flag));
                    modules.put(module, value == null ? "" : value);
                    i += value == null ? 0 : 1;
                }
            }
        }
        return new ReleaseSpec(all, modules, force, noWait);
    }

    /** The preview line — the command this window runs when Preview is pressed. */
    public String commandLine() {
        return String.join(" ", command(false));
    }

    /** The line that cuts it, for an operator who would rather watch it in a terminal. */
    public String executeCommandLine() {
        return String.join(" ", command(true));
    }
}
