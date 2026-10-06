package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltWithTest {

    @Test
    void theCliPinIsReadOutOfTheBakedFileAsATag() {
        // A tag's pom pins the bare release; git describe, which it is compared with, spells the tag.
        assertEquals(Optional.of("v0.0.14"), BuiltWith.cliTag("# baked by -Pdist\ncli.version=0.0.14\n"));
        assertEquals(Optional.of("0.2.1-SNAPSHOT"), BuiltWith.cliTag("cli.version=0.2.1-SNAPSHOT\n"));
        assertTrue(BuiltWith.cliTag("shared.version=0.1.3\n").isEmpty());
        // Unfiltered: the resource copied without its property substituted names nothing.
        assertTrue(BuiltWith.cliTag("cli.version=${botmaker.cli.version}\n").isEmpty());
    }

    @Test
    void aDevelopmentBuildCarriesNoFileAndSaysNothing() {
        // The resource is baked by the dist profile only; the test classpath is a development one.
        assertTrue(BuiltWith.cliTag().isEmpty());
        assertTrue(BuiltWith.notice(Optional.empty(), Optional.of("v0.0.14-3-gabc1234")).isEmpty());
    }

    @Test
    void theNoticeNamesBothSidesAndOnlyWhenTheyDiffer() {
        assertTrue(BuiltWith.notice(Optional.of("v0.0.14"), Optional.of("v0.0.14")).isEmpty());
        assertTrue(BuiltWith.notice(Optional.of("v0.0.14"), Optional.empty()).isEmpty());
        assertEquals(Optional.of("built with cli v0.0.14, checkout at v0.0.14-3-gabc1234"
                        + " — preview may follow older rules"),
                BuiltWith.notice(Optional.of("v0.0.14"), Optional.of("v0.0.14-3-gabc1234")));
    }

    @Test
    void aCheckoutPastItsTagOnlyByTheReleasesOwnCommitsIsAtThatTag(@TempDir Path umbrella) throws Exception {
        Path cli = Files.createDirectories(umbrella.resolve("botmaker-cli"));
        git(cli, "init", "-q");
        git(cli, "commit", "-q", "--allow-empty", "-m", "release: cli v0.2.1");
        git(cli, "tag", "v0.2.1");
        git(cli, "commit", "-q", "--allow-empty", "-m", "back to snapshot: cli 0.2.2-SNAPSHOT");

        assertEquals(Optional.of("v0.2.1"), BuiltWith.checkoutCli(umbrella));

        git(cli, "commit", "-q", "--allow-empty", "-m", "a real change");
        assertTrue(BuiltWith.checkoutCli(umbrella).orElseThrow().startsWith("v0.2.1-2-g"));
    }

    private static void git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false"));
        command.addAll(List.of(args));
        assertTrue(Proc.run(dir, Duration.ofSeconds(10), command).ok(), String.join(" ", command));
    }

    @Test
    void aCheckoutWithoutTheCliSubmoduleIsUnknownRatherThanAnError() {
        assertTrue(BuiltWith.checkoutCli(Path.of("/nonexistent/umbrella")).isEmpty());
    }
}
