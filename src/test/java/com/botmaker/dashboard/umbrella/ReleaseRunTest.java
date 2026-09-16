package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReleaseRun} degrading, which is the half a real checkout cannot demonstrate.
 *
 * <p>A preview against the maintainer's own umbrella is a manual test — it fetches eleven remotes and runs
 * Maven twice. What belongs here is the promise that a failure is a <b>value</b> rather than an exception:
 * this runs inside the window's JVM, so anything that escapes lands in a {@code CompletableFuture} and
 * reaches the operator as {@code Preview failed: null}.
 */
class ReleaseRunTest {

    private static ReleaseSpec everything() {
        return new ReleaseSpec(Optional.of("patch"), Map.of(), false, true);
    }

    @Test
    void aDirectoryThatIsNotACheckoutIsReportedRatherThanThrown(@TempDir Path notAnUmbrella) {
        List<String> streamed = new ArrayList<>();

        ReleaseRun run = ReleaseRun.go(notAnUmbrella, everything(), false, streamed::add);

        assertTrue(run.stopped(), "a run over an empty directory has to stop");
        assertFalse(run.decided(), "there is nothing here to decide about");
        assertTrue(run.error().isPresent(), "the reason is the value, never an exception");
        assertTrue(run.output().contains("error: "), run.output());
        assertTrue(streamed.stream().anyMatch(line -> line.startsWith("error: ")), streamed.toString());
    }

    @Test
    void everyLineReachesBothTheSinkAndTheWholeText(@TempDir Path notAnUmbrella) {
        // The pane is filled from the stream as the run goes and the whole text is kept for what comes
        // after. They must not be two different transcripts.
        List<String> streamed = new ArrayList<>();

        ReleaseRun run = ReleaseRun.go(notAnUmbrella, everything(), false, streamed::add);

        assertTrue(run.output().endsWith("\n") || run.output().isEmpty(), run.output());
        streamed.forEach(line -> assertTrue(run.output().contains(line), line));
    }

    @Test
    void aPreviewWritesNothingIntoTheCheckout(@TempDir Path umbrella) throws Exception {
        // The `Runner` is the whole of the difference between a preview and a release, so the property
        // worth asserting here is the one it exists for.
        Path sdk = umbrella.resolve(Module.SDK.directory());
        Files.createDirectories(sdk);
        Files.writeString(umbrella.resolve("release.sh"), "#!/usr/bin/env bash\n");

        ReleaseRun.go(umbrella, everything(), false, line -> { });

        assertFalse(Files.exists(sdk.resolve(".deps.env")));
        assertFalse(Files.exists(sdk.resolve("CHANGELOG.md")));
    }
}
