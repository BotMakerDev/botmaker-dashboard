package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.Publishers;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One open pull request on the catalog: who opened it, the one file it changes, what the check said, and whether
 * the merge job is holding it for the author's daily limit.
 *
 * <p><b>The shape is the one judgement made here.</b> The catalog takes exactly one path per pull request — a
 * bot's {@code bots/<owner>__<repo>.json}, a plugin's {@code plugins/<id>.json}, or {@code publishers.json} alone
 * (an admin's rank change) — and {@code automerge.yml} holds anything else. So a pull request touching more is
 * flagged loudly rather than left for somebody to notice in the Files tab. Whether the change is <i>good</i> is
 * the catalog's {@code validate} check, read in {@link Checks}; this record validates no entry.
 *
 * @param repo    {@code owner/name} of the repository the pull request is on
 * @param number  the pull request number
 * @param title   its title, as submitted
 * @param author  the login that opened it
 * @param headSha the head commit — what the check runs are attached to
 * @param url     its page on github.com
 * @param files   every path the pull request touches
 * @param checks  the {@code validate} verdict for {@link #headSha}
 * @param waiting whether it carries the {@code waiting} label: it passed, and merges when the author's daily limit
 *                frees
 */
public record Submission(String repo, int number, String title, String author, String headSha,
                         String url, List<String> files, Checks checks, boolean waiting) {

    /** The label {@code automerge.yml} puts on a pull request it holds for the daily limit. */
    public static final String WAITING = "waiting";

    public Submission {
        files = List.copyOf(files);
    }

    /** Reads a pull request object and the file list that goes with it. */
    public static Submission read(String repo, JsonNode pr, List<String> files, Checks checks) {
        boolean waiting = false;
        for (JsonNode label : pr.path("labels")) {
            waiting |= WAITING.equals(label.path("name").asText(""));
        }
        return new Submission(
                repo,
                pr.path("number").asInt(),
                pr.path("title").asText(""),
                pr.path("user").path("login").asText("(unknown)"),
                pr.path("head").path("sha").asText(""),
                pr.path("html_url").asText(""),
                files,
                checks,
                waiting);
    }

    /** The paths of a pull request's changed files, from {@code GET /pulls/{n}/files}. */
    public static List<String> filenames(JsonNode fileArray) {
        if (fileArray == null || !fileArray.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode f : fileArray) {
            out.add(f.path("filename").asText(""));
        }
        return List.copyOf(out);
    }

    /** Whether {@code path} is one the catalog takes in a pull request of its own. */
    static boolean isCatalogPath(String path) {
        return Publishers.FILE.equals(path) || EntryPath.parse(path).isPresent();
    }

    /** The catalog file this pull request changes, when it changes exactly one. */
    public Optional<String> entryFile() {
        List<String> entries = files.stream().filter(Submission::isCatalogPath).toList();
        return entries.size() == 1 ? Optional.of(entries.getFirst()) : Optional.empty();
    }

    /** Everything touched besides the one catalog file: {@code catalog.json} by hand, a workflow, a second entry. */
    public List<String> strays() {
        Optional<String> entry = entryFile();
        return files.stream().filter(f -> entry.isEmpty() || !f.equals(entry.get())).toList();
    }

    /** True when this is exactly one catalog file and nothing else — the shape the merge job takes. */
    public boolean wellShaped() {
        return entryFile().isPresent() && strays().isEmpty();
    }

    /** What the file names: a bot's {@code owner/repo}, a plugin's id; empty for {@code publishers.json}. */
    public Optional<String> claimedKey() {
        return entryFile().flatMap(EntryPath::parse).map(EntryPath::key);
    }

    /** One line for the row: what this pull request changes, or why its shape is wrong. */
    public String shape() {
        if (files.isEmpty()) {
            return "no files read";
        }
        Optional<String> entry = entryFile();
        if (entry.isEmpty()) {
            return "no single catalog file — touches " + String.join(", ", files);
        }
        String what = Publishers.FILE.equals(entry.get()) ? "ranks (" + Publishers.FILE + ")"
                : EntryPath.parse(entry.get()).map(p -> (p.kind() == EntryPath.Kind.BOT ? "bot " : "plugin ")
                        + p.key()).orElseThrow();
        List<String> strays = strays();
        return strays.isEmpty() ? what
                : what + " AND " + strays.size() + " file(s) it should not: " + String.join(", ", strays);
    }

    /** {@code botmaker-catalog#12}, which is how the operator will refer to it out loud. */
    public String label() {
        return repo.substring(repo.indexOf('/') + 1) + "#" + number;
    }
}
