package com.botmaker.dashboard.umbrella;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Drafts a module's {@code ## [Unreleased]} notes with Claude, through {@code cswap}.
 *
 * <p><b>It fills the editor and saves nothing.</b> What comes back is text in a text area the maintainer
 * then reads, edits and commits — the same as if they had typed it. That is the whole safety story here and
 * it is why there is no verdict, no gate and no check: a wrong draft costs a keystroke, and a draft that
 * wrote a commit by itself would cost a review nobody asked for.
 *
 * <p><b>The account is a slot, never an address</b> — see {@link CswapAccounts}. The least-used one is tried
 * first and the rest in order after it, so a rate-limited account moves the work along instead of ending it.
 * Every account refusing is one sentence in the status line and an editor left exactly as it was.
 *
 * <p><b>The prompt carries facts and no instructions about the project.</b> The module's own changelog
 * preamble says what the module is, its newest stamped section is the house style, and the commits and the
 * diff stat are what happened. Nothing here tells Claude what the module <i>should</i> say, because the one
 * failure worth preventing is a changelog bullet describing something that did not happen.
 */
public final class ClaudeDraft {

    /** Long enough for a real draft over forty commits, short enough that a hung process is not forever. */
    static final Duration TIMEOUT = Duration.ofMinutes(3);

    /** What the model is told about one module. Every field is read out of the checkout. */
    public record Request(String module, String preamble, Optional<String> lastStamped, String commits,
                          String diffStat) {
    }

    /**
     * What a draft produced.
     *
     * @param text    the section body, empty when nothing was produced
     * @param account the slot it came from, for the status line — {@code ""} when none answered
     * @param message one sentence about what happened, shown either way
     */
    public record Result(String text, String account, String message) {

        public boolean drafted() {
            return !text.isBlank();
        }
    }

    private ClaudeDraft() {
    }

    /** Whether both programs are on {@code PATH}. When they are not, the button is hidden, not disabled. */
    public static boolean available() {
        return onPath("claude") && onPath("cswap");
    }

    /**
     * Tries each account, least-used first, and stops at the first that answers.
     *
     * <p>{@code progress} is called with one sentence per attempt, so a draft that takes two minutes says
     * which slot it is waiting on rather than looking hung. It is called off the FX thread; the caller
     * marshals.
     */
    public static Result draft(Path where, Request request, Consumer<String> progress) {
        List<CswapAccounts.Account> accounts = CswapAccounts.byLeastUsed(CswapAccounts.list(where));
        if (accounts.isEmpty()) {
            return new Result("", "", "No cswap account could be read — nothing was drafted.");
        }
        String prompt = prompt(request);
        List<String> refusals = new ArrayList<>();
        for (CswapAccounts.Account account : accounts) {
            progress.accept("Drafting with " + account.label() + " …");
            Proc answer = run(where, argv(account.slot()), prompt);
            String out = answer.out().strip();
            if (answer.ok() && !out.isEmpty() && !rateLimited(out)) {
                return new Result(out, account.label(), "Drafted with " + account.label()
                        + " — nothing is saved until you press Save.");
            }
            refusals.add(account.label() + ": " + firstLine(out.isEmpty()
                    ? "exit " + answer.exit() + ", no output" : out));
        }
        return new Result("", "", "No account could draft this — " + String.join("; ", refusals));
    }

    /**
     * {@code cswap run <slot> -- claude -p …}.
     *
     * <p>Pure, and tested, because it is the one part of this that a session cannot exercise cheaply. The
     * tools are turned off: the draft is a piece of writing about text already in the prompt, and a model
     * reading or editing this working copy is exactly what the maintainer did not ask for.
     */
    static List<String> argv(int slot) {
        return List.of("cswap", "run", String.valueOf(slot), "--",
                "claude", "-p",
                "--model", "sonnet",
                "--effort", "medium",
                "--output-format", "text",
                "--allowedTools", "");
    }

    /** The prompt: what the module is, how it writes, what happened, and what to produce. */
    static String prompt(Request request) {
        StringBuilder text = new StringBuilder();
        text.append("You are writing the release notes for one module of a Java project, ")
                .append(request.module()).append(".\n\n");
        if (!request.preamble().isBlank()) {
            text.append("What the module's own CHANGELOG.md says about itself:\n")
                    .append(request.preamble().strip()).append("\n\n");
        }
        request.lastStamped().ifPresent(last -> text
                .append("The last released section, as the house style to match:\n")
                .append(last.strip()).append("\n\n"));
        text.append("The commits since the module's newest tag, newest first:\n")
                .append(request.commits().isBlank() ? "(none)" : request.commits().strip()).append("\n\n");
        if (!request.diffStat().isBlank()) {
            text.append("git diff --stat over the same span:\n")
                    .append(request.diffStat().strip()).append("\n\n");
        }
        text.append("""
                Write the body of the ## [Unreleased] section for this module.

                Rules:
                - Keep-a-Changelog bullets under ### Added, ### Changed, ### Fixed, ### Removed. Use only the
                  headings you have something for.
                - Only what a consumer of this module notices: a published behaviour, an API, a file it reads
                  or writes, a failure it no longer has. Not refactors, not test counts, not internal moves.
                - State no fact the commits and the diff do not support. If something is unclear, leave it out.
                - Match the last section's voice and level of detail.
                - Output the section body only: no ## heading, no preamble, no code fence, no closing remark.
                """);
        return text.toString();
    }

    /**
     * Whether the output is a refusal to serve rather than a draft.
     *
     * <p>{@code claude} exits 0 on a usage limit and prints the sentence, so the exit code alone would take
     * that sentence for the notes and write it into the changelog.
     */
    static boolean rateLimited(String out) {
        String lower = out.toLowerCase();
        return lower.contains("usage limit") || lower.contains("rate limit")
                || lower.contains("upgrade to increase") || lower.contains("credit balance");
    }

    /** One process, the prompt on stdin, output captured, a timeout that is a result. */
    private static Proc run(Path where, List<String> argv, String prompt) {
        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(where.toFile())
                .redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            try (OutputStream in = process.getOutputStream()) {
                in.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            String out;
            try (InputStream stream = process.getInputStream()) {
                out = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Proc(Proc.TIMED_OUT, "gave up after " + TIMEOUT.toMinutes() + " minutes");
            }
            return new Proc(process.exitValue(), out);
        } catch (IOException e) {
            return new Proc(Proc.TIMED_OUT, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Proc(Proc.TIMED_OUT, "interrupted");
        }
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse(text);
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, program))) {
                return true;
            }
        }
        return false;
    }
}
