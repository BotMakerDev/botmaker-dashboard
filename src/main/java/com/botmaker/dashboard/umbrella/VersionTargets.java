package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Level;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.ReleaseRefusal;
import com.botmaker.cli.release.Tags;
import com.botmaker.cli.release.Version;
import com.botmaker.cli.release.VersionSpec;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What a chosen level or version would actually cut — {@code 1.1.6 → 1.2.0}.
 *
 * <p><b>This is the one place the dashboard computes a release number, and it does not compute it.</b> It
 * calls {@code com.botmaker.cli.release}, which is {@code release.sh}'s {@code latest_version} and
 * {@code resolve_version} ported into {@code botmaker-cli}'s library artifact. The rule in {@code CLAUDE.md}
 * is unchanged and is what forced the dependency: a level means nothing until it is applied to *that
 * module's* own latest tag, that arithmetic has exactly one owner, and a second copy of it here would be
 * discovered as a bad tag, which cannot be edited.
 *
 * <p>So the arrow is honest in the strong sense — it is the same code the release will run, not a
 * plausible-looking prediction of it.
 *
 * <p><b>A module the release library does not know is not an error.</b> The decide pass names only what it
 * releases, but this window also lists what a checkout holds, and {@code botmaker-gallery} or this
 * repository have no flag and no tag arithmetic. They get {@link #UNKNOWN_MODULE} rather than a refusal.
 */
public final class VersionTargets {

    /** Shown while the module's tags have not been read yet — one git call per module, off the FX thread. */
    public static final String READING = "…";

    /** A directory the release library has no module for: not something a release ever cuts. */
    public static final String UNKNOWN_MODULE = "not released";

    private VersionTargets() {
    }

    /**
     * The module's newest released version, or empty when it has never been tagged.
     *
     * <p>Shells to git (a best-effort {@code fetch --tags} first, exactly as the script does), so it belongs
     * on a background thread and its answer is cached by the caller: a level change must not re-fetch.
     */
    public static Optional<Version> latest(Path umbrella, String moduleDirectory) {
        return Module.byDirectory(moduleDirectory)
                .flatMap(module -> Tags.latest(umbrella, module));
    }

    /** Whether this directory is a module the release library can resolve a version for at all. */
    public static boolean releasable(String moduleDirectory) {
        return Module.byDirectory(moduleDirectory).isPresent();
    }

    /**
     * The arrow for a level: what that module goes from and to.
     *
     * @param latest the module's newest tag, or empty for a module that has never been released — which is
     *               the script's {@code cur="0.0.0"} and is why a first release is {@code 0.0.1}.
     */
    public static String forLevel(String moduleDirectory, Level level, Optional<Version> latest) {
        return arrow(moduleDirectory, new VersionSpec.Bump(level), latest);
    }

    /**
     * The arrow for a typed exact version, or the reason it is not one.
     *
     * <p>The refusal is the release library's, shortened for a table cell — the operator sees *that* it is
     * not a version here and the script's own sentence if they run it anyway. Refusing the keystroke is
     * deliberately not done: {@code 1.2} is an ordinary state on the way to {@code 1.2.0}.
     */
    public static String forExact(String moduleDirectory, String typed, Optional<Version> latest) {
        Optional<Module> module = Module.byDirectory(moduleDirectory);
        if (module.isEmpty()) {
            return UNKNOWN_MODULE;
        }
        try {
            return arrow(moduleDirectory, VersionSpec.parse(module.get(), typed.strip()), latest);
        } catch (ReleaseRefusal refused) {
            return "not a version";
        }
    }

    private static String arrow(String moduleDirectory, VersionSpec spec, Optional<Version> latest) {
        if (!releasable(moduleDirectory)) {
            return UNKNOWN_MODULE;
        }
        String from = latest.map(Version::toString).orElse("no tag");
        return from + " → " + spec.against(latest);
    }
}
