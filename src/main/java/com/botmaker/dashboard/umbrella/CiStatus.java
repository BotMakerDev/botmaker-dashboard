package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.CiGate;
import com.botmaker.cli.release.GateVerdict;
import com.botmaker.cli.release.Module;

import java.util.Optional;

/**
 * What a module's CI says about {@code main}, as the Modules tab shows it.
 *
 * <p><b>It is {@link CiGate}'s answer, not a second reading of {@code gh}.</b> The gate is what refuses a
 * release of a red module, and a badge that disagreed with it would be worse than no badge: the operator
 * would see green here and a refusal there, with nothing to say which asked the right question. So this
 * calls the gate and renders its verdict — including the sentence, which is the gate's own words.
 *
 * <p>{@code force} is always {@code false} here. Forcing is a property of a release being cut, not of what
 * CI currently says, and a badge that went quiet because somebody ticked {@code --force} on another tab
 * would be reporting the operator's intention rather than the build.
 */
public record CiStatus(String module, Health health, String text, String detail) {

    /** The three things a badge can say. The style class is the cell colour in both themes. */
    public enum Health {
        GREEN("cell--ok"),
        RED("cell--broken"),
        UNKNOWN("cell--dim");

        private final String styleClass;

        Health(String styleClass) {
            this.styleClass = styleClass;
        }

        public String styleClass() {
            return styleClass;
        }
    }

    /** Runs the gate for one submodule directory. Blocking — {@code gh} is a process. */
    public static CiStatus check(String directory) {
        Optional<Module> module = Module.byDirectory(directory);
        if (module.isEmpty()) {
            // The gallery, the plugin registry and this window's own repository: the release never cuts
            // them, so there is no ci.yml run on main it would have asked about.
            return new CiStatus(directory, Health.UNKNOWN, "not released", "");
        }
        return of(directory, CiGate.check(module.get(), false));
    }

    /**
     * The gate's verdict as a badge — pure, so every arm is testable with no {@code gh} anywhere.
     *
     * <p>{@code FORCED} cannot occur while {@link #check} passes {@code force = false}; it is mapped to red
     * anyway rather than left to fall through, because a forced verdict is a red run either way.
     */
    public static CiStatus of(String directory, GateVerdict verdict) {
        return switch (verdict.status()) {
            case OK -> new CiStatus(directory, Health.GREEN, sentence(verdict.line()), verdict.line().strip());
            case SKIPPED -> new CiStatus(directory, Health.UNKNOWN, sentence(verdict.line()),
                    verdict.line().strip());
            case FORCED -> new CiStatus(directory, Health.RED, sentence(verdict.line()),
                    verdict.line().strip());
            case REFUSED -> new CiStatus(directory, Health.RED, firstLine(verdict.refusal()),
                    verdict.refusal().strip());
        };
    }

    /**
     * The gate's line without the module name it starts with.
     *
     * <p>The same shape {@link ModuleRow#planLabel} uses on the decide pass's sentences, and for the same
     * reason: the row already has a module column, so repeating the name costs the width the sentence needs.
     */
    private static String sentence(String line) {
        String text = line.strip();
        int colon = text.indexOf(": ");
        return colon < 0 ? text : text.substring(colon + 2);
    }

    private static String firstLine(String refusal) {
        return sentence(refusal.lines().findFirst().orElse(refusal));
    }
}
