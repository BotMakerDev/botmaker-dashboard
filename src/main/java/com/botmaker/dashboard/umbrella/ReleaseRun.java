package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.GateVerdict;
import com.botmaker.cli.release.Plan;
import com.botmaker.cli.release.Release;
import com.botmaker.cli.release.ReleaseRefusal;
import com.botmaker.cli.release.Runner;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One release, previewed or cut, through {@code com.botmaker.cli.release} — and the only place in this
 * module that can push a tag.
 *
 * <p><b>This is the rule the module hangs on, in its strict form rather than its weak one.</b> The weak
 * form was <i>shell to {@code release.sh} and read its output</i>, which kept the decisions in one place by
 * keeping them out of reach. The strict form is that the decisions have exactly one implementation and
 * every caller reaches it: the terminal through {@code botmaker release}, the workflow through the same
 * command, and this window through {@link Release#run}. Nothing here decides anything; what it supplies is
 * a {@link Runner} and a place to put the lines.
 *
 * <p><b>A preview and a release are the same call with a different {@code Runner}.</b> That is the property
 * worth having and it is not available to a window that shells: a dry run decides, gates and computes
 * exactly what a real run does and echoes the commands instead of running them, so the text on screen was
 * produced by the code that will do the work. A separate preview is free to drift, and the drift is
 * discovered as a tag, which cannot be edited.
 *
 * <p><b>A call, not the jar spawned</b>, because the library is already on this application's classpath —
 * {@code botmaker-dashboard} depends on {@code botmaker-cli}'s main artifact — and a spawned jar would put the
 * output back behind a pipe that has to be parsed. It must never be called on the FX thread, and a
 * {@link ReleaseRefusal} is a return value here rather than an exit code.
 *
 * <p><b>Where it runs depends on the Runner</b>, since 2026-09-16. A preview is called in the window's JVM. A
 * real release is called by {@link ReleaseJob}, in a process of its own, because the release cut in the
 * window's JVM that day died with the window after four tags. Both are this method.
 *
 * @param executed  whether a {@link Runner#real()} was used — false for a preview
 * @param output    every line the run produced, whole, in order
 * @param plan      the decide pass, when the run got that far
 * @param refusals  the gates that said no. Non-empty means nothing was tagged
 * @param error     a refusal that stopped the run before it could return an outcome
 * @param pushesOk  whether every branch push succeeded. False is reported, never fatal
 */
public record ReleaseRun(boolean executed, String output, Optional<Plan> plan, List<String> refusals,
                         Optional<String> error, boolean pushesOk) {

    /**
     * Runs it, streaming each line to {@code line} as it is produced and keeping the whole text.
     *
     * <p>Never on the FX thread: the decide pass shells to git in eleven repositories, the gates run Maven,
     * and a real run waits on JitPack between tags.
     *
     * @param line called from this thread, once per line. A UI caller hops to the FX thread itself — doing
     *             it here would make the sink's threading a property of this class rather than of its
     *             caller, and the CLI's sink has no thread to hop to.
     */
    public static ReleaseRun go(Path umbrella, ReleaseSpec spec, boolean execute, Consumer<String> line) {
        StringBuilder whole = new StringBuilder();
        Consumer<String> sink = text -> {
            whole.append(text).append('\n');
            line.accept(text);
        };
        Runner runner = new Runner(!execute, sink);
        try {
            // `why` is on, which is the one place this differs from `botmaker release`'s default. That flag
            // is off in the CLI so the port's output can be diffed against the script's byte for byte; no
            // diff is taken here, and the reason a module nobody named is in the release is exactly what an
            // operator about to press Execute needs on screen.
            Release.Outcome outcome = Release.run(runner, umbrella, spec.requested(),
                    spec.force(), !spec.noWaitJitpack(), true);
            return new ReleaseRun(execute, whole.toString(), Optional.of(outcome.plan()),
                    outcome.refusals().stream().map(GateVerdict::refusal).toList(),
                    Optional.empty(), outcome.pushesOk());
        } catch (ReleaseRefusal refused) {
            // The library's own way of saying no — a missing changelog extractor, an unreadable pom. It is
            // a sentence written for a person, so it is shown rather than wrapped.
            sink.accept("error: " + refused.getMessage());
            return new ReleaseRun(execute, whole.toString(), Optional.empty(), List.of(),
                    Optional.of(refused.getMessage()), true);
        } catch (RuntimeException e) {
            // Anything else is a bug here, and the window says so rather than closing over it. A release
            // half-done is the state this reports; what it must not do is look like a clean refusal.
            String message = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            sink.accept("error: " + message);
            return new ReleaseRun(execute, whole.toString(), Optional.empty(), List.of(),
                    Optional.of(message), true);
        }
    }

    /** Whether the run got as far as deciding. False means it stopped on something before the pass. */
    public boolean decided() {
        return plan.isPresent();
    }

    public boolean refused() {
        return !refusals.isEmpty();
    }

    /** Nothing was tagged: either a gate refused, or the run never got past its own refusal. */
    public boolean stopped() {
        return refused() || error.isPresent();
    }
}
