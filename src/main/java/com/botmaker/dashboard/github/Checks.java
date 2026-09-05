package com.botmaker.dashboard.github;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The gate's verdict on a submission, read from GitHub's check runs and never computed here.
 *
 * <p>The check that refuses a pull request is {@code RegistryGate}, run by the registry's own
 * {@code .github/workflows/validate.yml} out of {@code botmaker-cli}'s main artifact — the same code the
 * submitter ran as {@code botmaker plugin validate}. This app reads the conclusion of that run. It does not
 * resolve the plugin, load it, or repeat one check of it: a second validator would admit submissions the
 * gate then refuses, or refuse ones it admits, and the operator would have no way to tell which was right.
 *
 * <p><b>{@link #NONE} is a state of its own, and this is where it differs from the release log.</b>
 * {@code ReleaseLog.Health} reads {@code no run on <tag>} as <i>broken</i>, because a tag is finished: if no
 * workflow fired by the time the release polled, none ever will. A pull request is not finished — a
 * submission opened a minute ago has no check run yet and nothing is wrong. So "nobody has answered" is
 * neither a pass nor a failure here, and the operator is told which of the two it is rather than being shown
 * a colour that guesses.
 */
public record Checks(Verdict verdict, String text) {

    /** How a submission's checks stand. Ordered worst-last is not implied; see {@link #of}. */
    public enum Verdict {
        /** Every completed run concluded success, neutral or skipped. */
        PASSED,
        /** At least one run failed, timed out, was cancelled, or asks for a human action. */
        FAILED,
        /** At least one run is queued or in progress, and none has failed. */
        RUNNING,
        /** No check run exists for this head commit — which on a young pull request is ordinary. */
        NONE
    }

    /** Nothing asked yet, for a row whose checks have not been fetched. */
    public static Checks unknown() {
        return new Checks(Verdict.NONE, "not checked yet");
    }

    /**
     * Reduces {@code GET /repos/{owner}/{repo}/commits/{sha}/check-runs} to one verdict and one line.
     *
     * <p>Worst-of, with failure beating incompleteness: a run that has already failed is the answer even
     * while a second is still going, because nothing the second concludes can make the first pass. The text
     * names the runs that are not green, since "1 of 3 failed" is not actionable without knowing which.
     *
     * <p>A null node is every read failure the client folds together (offline, rate-limited, a repository
     * the token cannot see). It reports {@link Verdict#NONE} with the reason rather than an error, so the
     * queue still lists and the operator still sees what was submitted.
     */
    public static Checks of(JsonNode checkRuns) {
        if (checkRuns == null) {
            return new Checks(Verdict.NONE, "could not read the check runs");
        }
        JsonNode runs = checkRuns.path("check_runs");
        if (!runs.isArray() || runs.isEmpty()) {
            return new Checks(Verdict.NONE, "no check run yet");
        }

        List<String> failed = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        int total = 0;
        for (JsonNode run : runs) {
            total++;
            String name = run.path("name").asText("check");
            String status = run.path("status").asText("");
            String conclusion = run.path("conclusion").asText("");
            if (!"completed".equals(status)) {
                pending.add(name);
                continue;
            }
            switch (conclusion) {
                case "success", "neutral", "skipped" -> { }
                default -> failed.add(name + " (" + conclusion + ")");
            }
        }

        if (!failed.isEmpty()) {
            return new Checks(Verdict.FAILED, String.join(", ", failed));
        }
        if (!pending.isEmpty()) {
            return new Checks(Verdict.RUNNING, String.join(", ", pending) + " — still running");
        }
        return new Checks(Verdict.PASSED, total == 1 ? "passed" : "passed (" + total + " checks)");
    }
}
