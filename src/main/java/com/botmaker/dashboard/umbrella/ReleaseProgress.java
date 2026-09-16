package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a release running in a child process has done so far — one immutable value, built from the two
 * things that process leaves on disk.
 *
 * <p><b>Two sources, because each is late about something the other is not.</b> The release log is the
 * record: it says for certain which modules are tagged, and after the verify pass what JitPack and Actions
 * answered. But it is rewritten only when a module <i>finishes</i>, so a module halfway through its JitPack
 * wait reads {@code pending} there for ten minutes. The child's output is the narration: it says a module
 * has started, which command it has reached and that it is waiting on JitPack — and it says nothing that is
 * certain. So the log wins wherever it has an answer, and the output fills in only a row the log still calls
 * {@code pending}.
 *
 * <p><b>Nothing here decides anything about the release.</b> Every word it reads is the library's: the stage
 * cells are {@code com.botmaker.cli.release.ReleaseLog.Stage}'s, the verdicts are
 * {@link ReleaseLog.Health}'s reading of {@code CleanRoom} and {@code Actions}, and the narration lines are
 * {@code Release}'s own. What this class adds is the one thing a window needs and a log does not: <i>where is
 * it now</i>. That is presentation, and it is tested as a pure function over text for the same reason the rest
 * of {@code umbrella/} is.
 *
 * @param phase   where the whole run is
 * @param lanes   one per module being released, in tag order — empty until the log exists
 * @param started the child's first line
 * @param elapsed from the first line to the last, or to now while the child is alive
 */
public record ReleaseProgress(Phase phase, List<Lane> lanes, Instant started, Duration elapsed) {

    /** Where the run is, as a whole. */
    public enum Phase {
        /** The decide pass and the gates: nothing tagged, and no log yet. */
        DECIDING("Deciding and gating", true),
        TAGGING("Releasing", true),
        /** Every module has an answer; the clean-room resolves and the Actions polls are running. */
        VERIFYING("Verifying JitPack and Actions", true),
        RECORDING("Recording pointers and pushing", true),
        DONE("Released", false),
        /** Done, with a branch left unpushed — every tag is out. */
        UNPUSHED("Released — a branch was not pushed", false),
        /** A gate said no. Nothing was tagged. */
        REFUSED("Refused — nothing was tagged", false),
        /** Something threw; the log says which module and where. */
        STOPPED("Stopped", false),
        /** The process is gone and never said it had finished — killed, or the machine went down. */
        DIED("The release process ended without finishing", false);

        private final String label;
        private final boolean running;

        Phase(String label, boolean running) {
            this.label = label;
            this.running = running;
        }

        public String label() {
            return label;
        }

        public boolean running() {
            return running;
        }
    }

    /** The four things one module goes through, and the stepper's nodes. */
    public enum Step {
        COMMIT("Commit"), TAG("Tag"), JITPACK("JitPack"), ACTIONS("Actions");

        private final String label;

        Step(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One node of the stepper. */
    public enum NodeState {
        PENDING, RUNNING, OK, FAILED, SKIPPED;

        /** {@code step--running} and so on — the stylesheet's name, kept beside the state it names. */
        public String styleClass() {
            return "step--" + name().toLowerCase();
        }

        /** Finished, one way or the other that does not need anybody. */
        public boolean settled() {
            return this == OK || this == SKIPPED;
        }
    }

    /** Which lanes the board shows: every one, or those whose step still needs looking at. */
    public enum Filter {
        ALL, TAG, JITPACK, ACTIONS
    }

    /**
     * One module's lane.
     *
     * @param module  the directory, which is also the repository name
     * @param tag     {@code v0.1.0}
     * @param stage   the log's stage cell, unchanged
     * @param steps   every step's node, in {@link Step} order
     * @param errors  the log's error blocks for this module, as {@code kind: text}
     * @param elapsed how long this module has taken, when it has started
     */
    public record Lane(String module, String tag, String stage, Map<Step, NodeState> steps,
                       List<String> errors, Optional<Duration> elapsed) {

        public Lane {
            steps = Map.copyOf(steps);
            errors = List.copyOf(errors);
        }

        public NodeState state(Step step) {
            return steps.get(step);
        }

        public boolean failed() {
            return steps.containsValue(NodeState.FAILED);
        }

        public boolean running() {
            return steps.containsValue(NodeState.RUNNING);
        }

        /** Whether this lane belongs under a filter: every lane for {@link Filter#ALL}, else an unsettled step. */
        public boolean shownUnder(Filter filter) {
            return switch (filter) {
                case ALL -> true;
                case TAG -> !state(Step.COMMIT).settled() || !state(Step.TAG).settled();
                case JITPACK -> !state(Step.JITPACK).settled();
                case ACTIONS -> !state(Step.ACTIONS).settled();
            };
        }
    }

    /** The four tiles' numbers, counted from the lanes and nothing else. */
    public record Tiles(int total, int tagged, boolean tagFailed,
                        int jitpackTotal, int jitpackOk, int jitpackWaiting, int jitpackFailed,
                        int actionsTotal, int actionsOk, int actionsPending, int actionsFailed,
                        Duration elapsed) {
    }

    /**
     * One line the child wrote, with the moment it wrote it.
     *
     * <p>The child stamps every line itself ({@link #format}) because the window may not be there to: it can
     * be closed and reopened mid-release, and a lane's elapsed time read off the moment the window happened
     * to read the file would be the window's history, not the release's.
     *
     * @param at   when it was written; empty for a line the child did not stamp — a JVM warning on stderr
     */
    public record Line(Optional<Instant> at, String text) {

        public static String format(Instant at, String text) {
            return at + " " + text;
        }

        /** A stamped line, or the whole thing as text when the first word is not an instant. */
        public static Line parse(String raw) {
            int space = raw.indexOf(' ');
            if (space > 0) {
                try {
                    return new Line(Optional.of(Instant.parse(raw.substring(0, space))), raw.substring(space + 1));
                } catch (DateTimeParseException e) {
                    // Not stamped: fall through.
                }
            }
            return new Line(Optional.empty(), raw);
        }

        public static List<Line> parseAll(String text) {
            List<Line> lines = new ArrayList<>();
            for (String raw : text.split("\n", -1)) {
                if (!raw.isEmpty()) {
                    lines.add(parse(raw));
                }
            }
            return List.copyOf(lines);
        }
    }

    /**
     * The last line {@link ReleaseJob} writes, and so the only way to tell "finished" from "killed".
     *
     * <p>Written by the child rather than inferred from the log, because a refusal and a decide-pass error
     * leave no log at all, and a run that died after its last tag leaves a log that looks finished.
     */
    public enum Ending {
        DONE("done"), UNPUSHED("done, a branch was not pushed"), REFUSED("refused"), STOPPED("stopped");

        public static final String PREFIX = "release-job: ";

        private final String word;

        Ending(String word) {
            this.word = word;
        }

        public String line(String detail) {
            return PREFIX + word + (detail.isBlank() ? "" : " — " + detail);
        }

        static Optional<Ending> of(String text) {
            if (!text.startsWith(PREFIX)) {
                return Optional.empty();
            }
            String rest = text.substring(PREFIX.length());
            // Longest first: "done, a branch…" also starts with "done".
            for (Ending ending : List.of(UNPUSHED, DONE, REFUSED, STOPPED)) {
                if (rest.equals(ending.word) || rest.startsWith(ending.word + " — ")) {
                    return Optional.of(ending);
                }
            }
            return Optional.empty();
        }

        Phase phase() {
            return switch (this) {
                case DONE -> Phase.DONE;
                case UNPUSHED -> Phase.UNPUSHED;
                case REFUSED -> Phase.REFUSED;
                case STOPPED -> Phase.STOPPED;
            };
        }
    }

    // ---- the library's narration, as it reads today ---------------------------------------------------

    /** {@code Release.release}: the first line of one module's segment. */
    private static final Pattern RELEASING = Pattern.compile("^Releasing (\\S+) (v\\S+)$");
    /** {@code ReleaseLog.write}: which file the chain is keeping. */
    private static final Pattern LOG = Pattern.compile("^Release log: releases/(\\S+\\.md)$");
    private static final String RECORDING = "Recording submodule pointers in the umbrella";
    private static final String CHAIN_ERROR = "error: ";

    /** The {@code at} steps before the commit — a failure there means nothing was committed. */
    private static final String PUSH_STEP = "commit, tag and push";
    private static final String JITPACK_STEP = "jitpack wait";

    /** The log file the child's chain is keeping, once it has said so. */
    public static Optional<String> logName(List<Line> lines) {
        for (Line line : lines) {
            Matcher match = LOG.matcher(line.text());
            if (match.matches()) {
                return Optional.of(match.group(1));
            }
        }
        return Optional.empty();
    }

    /**
     * The model.
     *
     * @param lines the child's output so far
     * @param log   the release log it names, once it exists
     * @param alive whether the child process is still running
     * @param now   the clock, handed in so a test can hold it still
     */
    public static ReleaseProgress of(List<Line> lines, Optional<ReleaseLog> log, boolean alive, Instant now) {
        Instant started = lines.stream().flatMap(l -> l.at().stream()).findFirst().orElse(now);
        Optional<Instant> last = lines.stream().flatMap(l -> l.at().stream()).reduce((a, b) -> b);

        Optional<Ending> ending = Optional.empty();
        Instant endedAt = null;
        boolean recording = false;
        for (Line line : lines) {
            Optional<Ending> end = Ending.of(line.text());
            if (end.isPresent()) {
                ending = end;
                endedAt = line.at().orElse(null);
            }
            recording |= line.text().equals(RECORDING);
        }

        Instant clockEnd = ending.isPresent() || !alive ? last.orElse(now) : now;
        if (endedAt != null) {
            clockEnd = endedAt;
        }

        List<Lane> lanes = new ArrayList<>();
        for (ReleaseLog.Row row : log.map(ReleaseLog::rows).orElse(List.of())) {
            lanes.add(lane(row, log.get(), segment(lines, row.module()), ending.isEmpty() && alive, now));
        }

        Phase phase;
        if (ending.isPresent()) {
            phase = ending.get().phase();
        } else if (!alive) {
            phase = Phase.DIED;
        } else if (lanes.isEmpty()) {
            phase = Phase.DECIDING;
        } else if (recording) {
            phase = Phase.RECORDING;
        } else if (log.get().rows().stream().noneMatch(r -> r.stage().equals("pending"))) {
            phase = Phase.VERIFYING;
        } else {
            phase = Phase.TAGGING;
        }
        return new ReleaseProgress(phase, List.copyOf(lanes), started, Duration.between(started, clockEnd));
    }

    public Tiles tiles() {
        int tagged = 0;
        boolean tagFailed = false;
        int jTotal = 0, jOk = 0, jWaiting = 0, jFailed = 0;
        int aTotal = 0, aOk = 0, aPending = 0, aFailed = 0;
        for (Lane lane : lanes) {
            tagged += lane.state(Step.TAG) == NodeState.OK ? 1 : 0;
            tagFailed |= lane.state(Step.COMMIT) == NodeState.FAILED || lane.state(Step.TAG) == NodeState.FAILED;
            NodeState jitpack = lane.state(Step.JITPACK);
            if (jitpack != NodeState.SKIPPED) {
                jTotal++;
                switch (jitpack) {
                    case OK -> jOk++;
                    case FAILED -> jFailed++;
                    default -> jWaiting++;
                }
            }
            NodeState actions = lane.state(Step.ACTIONS);
            if (actions != NodeState.SKIPPED) {
                aTotal++;
                switch (actions) {
                    case OK -> aOk++;
                    case FAILED -> aFailed++;
                    default -> aPending++;
                }
            }
        }
        return new Tiles(lanes.size(), tagged, tagFailed, jTotal, jOk, jWaiting, jFailed,
                aTotal, aOk, aPending, aFailed, elapsed);
    }

    public List<Lane> lanes(Filter filter) {
        return lanes.stream().filter(lane -> lane.shownUnder(filter)).toList();
    }

    public boolean failed() {
        return lanes.stream().anyMatch(Lane::failed) || phase == Phase.STOPPED || phase == Phase.DIED;
    }

    /** {@code 12:41}, or {@code 1:02:05} past the hour. */
    public static String clock(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long h = seconds / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        return h > 0 ? "%d:%02d:%02d".formatted(h, m, s) : "%d:%02d".formatted(m, s);
    }

    // ---- one lane -------------------------------------------------------------------------------------

    /**
     * One module's lines: from its {@code Releasing} line to the next module's, or to the end of the chain.
     *
     * @param closedAt when the segment ended, when it has
     */
    private record Segment(List<Line> lines, Optional<Instant> startedAt, boolean closed,
                           Optional<Instant> closedAt) {
        static final Segment NONE = new Segment(List.of(), Optional.empty(), false, Optional.empty());

        boolean has(String fragment) {
            return lines.stream().anyMatch(l -> l.text().contains(fragment));
        }

        /** Whether a line containing {@code fragment} is followed by anything — so the command returned. */
        boolean passed(String fragment) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).text().contains(fragment)) {
                    return i + 1 < lines.size() || closed;
                }
            }
            return false;
        }
    }

    private static Segment segment(List<Line> lines, String module) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher match = RELEASING.matcher(lines.get(i).text());
            if (match.matches() && match.group(1).equals(module)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return Segment.NONE;
        }
        for (int end = start + 1; end < lines.size(); end++) {
            String text = lines.get(end).text();
            if (RELEASING.matcher(text).matches() || text.equals(RECORDING)
                    || text.startsWith(CHAIN_ERROR) || text.startsWith(Ending.PREFIX)) {
                return new Segment(lines.subList(start + 1, end), lines.get(start).at(), true,
                        lines.get(end).at());
            }
        }
        return new Segment(lines.subList(start + 1, lines.size()), lines.get(start).at(), false, Optional.empty());
    }

    private static Lane lane(ReleaseLog.Row row, ReleaseLog log, Segment segment, boolean live, Instant now) {
        Map<Step, NodeState> steps = new EnumMap<>(Step.class);
        boolean onJitpack = Module.byDirectory(row.module())
                .map(com.botmaker.cli.release.ReleaseLog::onJitpack)
                .orElse(true);
        List<String> errors = log.problemsFor(row.module()).stream()
                .map(p -> p.kind() + ": " + p.text())
                .toList();
        String commitLine = " commit -am ";
        String pushTag = " push origin " + row.tag();

        switch (row.stage()) {
            case "pending" -> {
                if (segment == Segment.NONE) {
                    steps.put(Step.COMMIT, NodeState.PENDING);
                    steps.put(Step.TAG, NodeState.PENDING);
                    steps.put(Step.JITPACK, onJitpack ? NodeState.PENDING : NodeState.SKIPPED);
                } else {
                    NodeState commit = segment.has(commitLine) ? NodeState.OK
                            : segment.has(" tag " + row.tag()) || segment.has(pushTag) ? NodeState.SKIPPED
                            : NodeState.RUNNING;
                    NodeState tag = segment.passed(pushTag) ? NodeState.OK
                            : commit.settled() ? NodeState.RUNNING : NodeState.PENDING;
                    NodeState jitpack = !onJitpack ? NodeState.SKIPPED
                            : segment.has("JitPack build of " + row.module()) ? NodeState.OK
                            : segment.has("not built on JitPack after") ? NodeState.FAILED
                            : segment.has("waiting for JitPack to build " + row.module()) ? NodeState.RUNNING
                            : NodeState.PENDING;
                    steps.put(Step.COMMIT, commit);
                    steps.put(Step.TAG, tag);
                    steps.put(Step.JITPACK, jitpack);
                }
                steps.put(Step.ACTIONS, NodeState.PENDING);
            }
            case "not reached" -> {
                for (Step step : Step.values()) {
                    steps.put(step, NodeState.SKIPPED);
                }
            }
            case "FAILED" -> {
                String failure = log.problems().stream()
                        .filter(p -> p.module().equals(row.module()) && p.kind().equals("release"))
                        .map(ReleaseLog.Problem::text)
                        .findFirst().orElse("");
                NodeState committed = segment.has(commitLine) ? NodeState.OK : NodeState.SKIPPED;
                steps.put(Step.JITPACK, NodeState.SKIPPED);
                steps.put(Step.ACTIONS, NodeState.SKIPPED);
                if (failure.startsWith(JITPACK_STEP + ":")) {
                    steps.put(Step.COMMIT, committed);
                    steps.put(Step.TAG, NodeState.OK);
                    steps.put(Step.JITPACK, NodeState.FAILED);
                } else if (failure.startsWith(PUSH_STEP + ":")) {
                    steps.put(Step.COMMIT, committed);
                    steps.put(Step.TAG, NodeState.FAILED);
                } else {
                    steps.put(Step.COMMIT, NodeState.FAILED);
                    steps.put(Step.TAG, NodeState.SKIPPED);
                }
            }
            // "tagged", "built on jitpack", "jitpack timeout" — and "" for a log older than the column,
            // every row of which was a whole release.
            default -> {
                steps.put(Step.COMMIT, segment != Segment.NONE && !segment.has(commitLine)
                        ? NodeState.SKIPPED : NodeState.OK);
                steps.put(Step.TAG, NodeState.OK);
                steps.put(Step.JITPACK, !onJitpack ? NodeState.SKIPPED
                        : verdict(row.jitpackHealth(), row.stage().equals("built on jitpack")));
                steps.put(Step.ACTIONS, verdict(row.actionsHealth(), false));
            }
        }

        Optional<Duration> elapsed = segment.startedAt().map(from -> Duration.between(from,
                segment.closedAt().orElseGet(() -> live ? now
                        : segment.lines().stream().flatMap(l -> l.at().stream()).reduce((a, b) -> b).orElse(from))));
        return new Lane(row.module(), row.tag(), row.stage(), steps, errors, elapsed);
    }

    /** A polled verdict as a node. {@code builtWhileWaiting} stands in until the verify pass has answered. */
    private static NodeState verdict(ReleaseLog.Health health, boolean builtWhileWaiting) {
        return switch (health) {
            case OK -> NodeState.OK;
            case BROKEN -> NodeState.FAILED;
            case NA -> NodeState.SKIPPED;
            case PENDING -> builtWhileWaiting ? NodeState.OK : NodeState.PENDING;
        };
    }
}
