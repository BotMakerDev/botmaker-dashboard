package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Plan;

import java.util.List;
import java.util.Optional;

/**
 * One module, as the Modules tab shows it: where its tag is, whether HEAD has moved past it, what
 * {@code release.sh} would decide about that movement, where its pins sit, and whether its changelog is
 * ready.
 *
 * <p>Every field is either a fact of the checkout (git, a file's contents) or the release library's own
 * verdict quoted back. Nothing here is computed from a rule this module owns — see {@link ModuleScan}.
 */
public record ModuleRow(
        String name,
        Optional<String> latestTag,
        int ahead,
        boolean dirty,
        Optional<Plan.Decision> plan,
        List<DepsEnv.Pin> pins,
        ChangelogState changelog) {

    /** Whether this module's {@code CHANGELOG.md} is ready for a release to stamp a version onto. */
    public enum ChangelogState {
        /** A {@code ## [Unreleased]} section exists — the shape {@code check_changelog} wants. */
        UNRELEASED,
        /** A changelog with no unreleased section. The gate refuses a release of this module. */
        NONE,
        /** No {@code CHANGELOG.md} at all — {@code botmaker-pilot}, which the gate exempts. */
        ABSENT;

        public String label() {
            return switch (this) {
                case UNRELEASED -> "[Unreleased]";
                case NONE -> "no [Unreleased]";
                case ABSENT -> "—";
            };
        }
    }

    /** {@code v1.0.37 +3} — the tag and how far HEAD has moved past it. */
    public String tagLabel() {
        String tag = latestTag.orElse("never released");
        if (ahead > 0) {
            return tag + "  +" + ahead;
        }
        return tag;
    }

    /**
     * What the release would do, in the decide pass's own words.
     *
     * <p>Composed rather than re-derived: the two skip reasons have different causes ("no changes" and "only
     * docs") and {@link com.botmaker.cli.release.ReleaseDecision} is where both sentences are written. What
     * this does is drop the {@code    botmaker-cli: } prefix the pass prints in front of them, because the
     * module already has a column of its own here.
     *
     * <p>A module the pass never named is not "up to date" — it is a module the release does not cut at all,
     * and saying so is the honest answer for {@code botmaker-gallery}, {@code botmaker-plugin-registry} and
     * this window's own repository.
     */
    public String planLabel() {
        return plan
                .map(decision -> decision.releasing()
                        ? "releasing v" + decision.version()
                        : decision.verdict().skipReason())
                .orElse("not released by the release");
    }

    public boolean releasing() {
        return plan.map(Plan.Decision::releasing).orElse(false);
    }

    /** True when any pin names something other than its upstream's newest tag. */
    public boolean anyStalePin() {
        return pins.stream().anyMatch(DepsEnv.Pin::stale);
    }

    public String pinsLabel() {
        if (pins.isEmpty()) {
            return "—";
        }
        return String.join("   ", pins.stream().map(DepsEnv.Pin::toString).toList());
    }
}
