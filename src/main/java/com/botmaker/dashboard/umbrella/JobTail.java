package com.botmaker.dashboard.umbrella;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A running job's output, read from where the last read stopped.
 *
 * <p>The Release tab reads its job once a second. Until 2026-09-29 each read was the whole file, twice (once
 * for the output pane, once more inside {@link ReleaseLauncher.Job#progress}), and every line was parsed again
 * each time: a release's output grows to hundreds of kilobytes, so the watcher's cost grew with the run. This
 * keeps the offset and the parsed lines, reads only what was appended, and leaves a trailing line the child
 * has not finished writing for the next read.
 *
 * <p>Not thread-safe: one watcher owns one tail.
 */
public final class JobTail {

    private final Path file;
    private long offset;
    private final List<ProgressLine> lines = new ArrayList<>();

    public JobTail(Path file) {
        this.file = file;
    }

    /** Every complete line so far. A file that cannot be read yet (not created) answers what was read before. */
    public List<ProgressLine> read() {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long length = in.length();
            if (length < offset) {
                // Truncated or replaced: start again rather than read from the middle of another file.
                offset = 0;
                lines.clear();
            }
            if (length > offset) {
                byte[] added = new byte[Math.toIntExact(length - offset)];
                in.seek(offset);
                in.readFully(added);
                // Only up to the last newline, counted in bytes: a newline byte never sits inside a UTF-8
                // character, so the unfinished rest (a line, or half a character) is simply read next time.
                int end = lastNewline(added);
                if (end >= 0) {
                    offset += end + 1;
                    lines.addAll(ProgressLine.parseAll(new String(added, 0, end, StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException e) {
            // Not there yet, or gone: what was read stays the answer.
        }
        return List.copyOf(lines);
    }

    private static int lastNewline(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
