package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Version;
import com.botmaker.dashboard.umbrella.ChangelogDrafts;
import com.botmaker.dashboard.umbrella.ClaudeDraft;
import com.botmaker.dashboard.umbrella.ReleaseLauncher;
import com.botmaker.dashboard.umbrella.ReleaseRun;
import com.botmaker.dashboard.umbrella.ReleaseSpec;
import com.botmaker.dashboard.umbrella.VersionTargets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What the Release tab calls outside itself — the library and the process launcher — so a test can hand in a
 * preview that does not shell to git in eleven repositories.
 */
interface ReleaseBackend {

    ReleaseRun preview(Path umbrella, ReleaseSpec spec, Consumer<String> line);

    /**
     * Writes and commits an {@code [Unreleased]} section for each of {@code modules} that has none, and
     * answers what happened to each — see {@link ChangelogDrafts}. Nothing by default, which is what a
     * test backend wants: a preview over a fixture is not a preview that commits into it.
     */
    default List<ChangelogDrafts.Result> autoDraft(Path umbrella, List<String> modules, Consumer<String> line) {
        return List.of();
    }

    Optional<Version> latest(Path umbrella, String module);

    ReleaseLauncher.Launched launch(Path umbrella, ReleaseSpec spec) throws IOException;

    Optional<ReleaseLauncher.Job> latestJob(Path umbrella);

    /** Asks a running release to stop before its next step — its stop file, which the process reads. */
    default void stop(ReleaseLauncher.Job job) throws IOException {
        job.requestStop();
    }

    ReleaseBackend REAL = new ReleaseBackend() {
        @Override
        public ReleaseRun preview(Path umbrella, ReleaseSpec spec, Consumer<String> line) {
            return ReleaseRun.go(umbrella, spec, false, line);
        }

        @Override
        public List<ChangelogDrafts.Result> autoDraft(Path umbrella, List<String> modules, Consumer<String> line) {
            List<String> needing = ChangelogDrafts.needing(umbrella, modules);
            if (needing.isEmpty()) {
                return List.of();
            }
            // Without Claude the copies still land and the drafted ones are reported as left, which is what
            // turns into the refusal the tab shows.
            ChangelogDrafts.Drafter drafter = ClaudeDraft.available()
                    ? ClaudeDraft::draft
                    : (where, request, progress) -> new ClaudeDraft.Result("", "", "Claude is not on this machine");
            return ChangelogDrafts.draftAll(umbrella, needing, drafter, line);
        }

        @Override
        public Optional<Version> latest(Path umbrella, String module) {
            return VersionTargets.latest(umbrella, module);
        }

        @Override
        public ReleaseLauncher.Launched launch(Path umbrella, ReleaseSpec spec) throws IOException {
            return ReleaseLauncher.launch(umbrella, spec);
        }

        @Override
        public Optional<ReleaseLauncher.Job> latestJob(Path umbrella) {
            return ReleaseLauncher.latest(umbrella);
        }
    };
}
