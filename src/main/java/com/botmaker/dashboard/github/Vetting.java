package com.botmaker.dashboard.github;

import com.botmaker.cli.gallery.VettedRecord;
import com.botmaker.cli.registry.Registry;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Vetting a bot, and taking it back: {@code vetted/<owner>-<repo>.json} in the gallery.
 *
 * <p><b>A vetting pins one release.</b> The record names the tag the operator looked at, so the badge Studio
 * shows cannot follow the repository to a release nobody has seen; Studio installs that tag, and offers it as
 * the update. Revoking deletes the file and the bot is Community again on the next catalog build.
 *
 * <p><b>Both are pull requests, like every write in this window.</b> The gallery's gate runs over a
 * {@code vetted/} change from a maintainer and checks what this class does not: that the bot is listed, and
 * that the pinned release really downloads. So nothing is checked here — that would be the second gate this
 * module exists not to have. Its merge job never merges a {@code vetted/} change by itself, so the proposal
 * waits in the Queue tab, labelled for a maintainer, and is merged there.
 *
 * <p>The record is {@code botmaker-cli}'s {@link VettedRecord} written through {@link Registry#mapper()}, so the
 * file is the one the gate and {@code CatalogBuilder} read, key order included.
 */
public final class Vetting {

    private Vetting() {
    }

    /**
     * The record vetting {@code entry} at {@code version} would write.
     *
     * @param login the operator, who is who vouched
     * @param today the date, in UTC — a date and not an instant, because "vetted on" is all a reader wants
     */
    public static VettedRecord record(Catalog.Entry entry, String version, String login, LocalDate today) {
        String slug = entry.repo().isEmpty() ? entry.id() : entry.repo();
        int slash = slug.indexOf('/');
        String owner = slash < 0 ? "" : slug.substring(0, slash);
        String repo = slash < 0 ? slug : slug.substring(slash + 1);
        return new VettedRecord(VettedRecord.CURRENT_SCHEMA, owner, repo, version, today.toString(), login);
    }

    /** The file's text: pretty-printed through the CLI's mapper, newline-terminated. */
    public static String json(VettedRecord record) {
        try {
            return Registry.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(record) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("could not write the vetting record: " + e.getMessage(), e);
        }
    }

    /** The bot's newest release tag, blank when it has none — what Vet offers by default. */
    public static CompletableFuture<String> latestRelease(GitHubClient client, GitHubAuth auth,
                                                          Catalog.Entry entry) {
        if (entry.repo().isEmpty()) {
            return CompletableFuture.completedFuture("");
        }
        return client.get(GitHubConfig.API_BASE + "/repos/" + entry.repo() + "/releases/latest",
                        Contents.token(auth))
                .thenApply(node -> node == null ? "" : node.path("tag_name").asText(""));
    }

    /**
     * Proposes vetting {@code entry} at {@code version}, or moving an existing vetting to it.
     *
     * <p>An existing record's blob sha is sent back, so GitHub refuses the write if the file moved since the
     * catalog was read — the same optimistic lock {@link Catalog#edit} relies on.
     */
    public static CompletableFuture<Catalog.Proposal> vet(GitHubClient client, GitHubAuth auth,
                                                         Catalog.Entry entry, String version, String why) {
        return auth.login(client).thenCompose(login -> {
            VettedRecord record = record(entry, version, login, LocalDate.now(ZoneOffset.UTC));
            String branch = Catalog.branchFor("vet", entry);
            String message = "vet " + record.slug() + " at " + version;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", message);
            body.put("content", Base64.getEncoder().encodeToString(json(record).getBytes(StandardCharsets.UTF_8)));
            body.put("branch", branch);
            if (entry.vetted() != null) {
                body.put("sha", entry.vetted().sha());
            }
            String was = entry.vetted() == null ? ""
                    : " It was vetted at `" + entry.vetted().record().vettedVersion() + "`.";
            return Catalog.branch(client, auth, entry, branch)
                    .thenCompose(ignored -> Contents.put(client, auth, entry.kind().repo(), record.path(), body))
                    .thenCompose(ignored -> Catalog.pull(client, auth, entry, branch, message,
                            Catalog.body("Vets `" + record.slug() + "` at `" + version + "`." + was, why)));
        });
    }

    /** Proposes revoking {@code entry}'s vetting. Nothing changes until it is merged. */
    public static CompletableFuture<Catalog.Proposal> revoke(GitHubClient client, GitHubAuth auth,
                                                            Catalog.Entry entry, String why) {
        Catalog.Vetted vetted = entry.vetted();
        if (vetted == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(entry.id() + " is not vetted"));
        }
        String branch = Catalog.branchFor("revoke", entry);
        String message = "revoke the vetting of " + vetted.record().slug();
        return Catalog.branch(client, auth, entry, branch)
                .thenCompose(ignored -> Contents.delete(client, auth, entry.kind().repo(), vetted.path(),
                        Map.of("message", message, "sha", vetted.sha(), "branch", branch)))
                .thenCompose(ignored -> Catalog.pull(client, auth, entry, branch, message,
                        Catalog.body("Removes `" + vetted.path() + "`: " + vetted.record().slug()
                                + " is Community again once merged. Its listing is untouched.", why)));
    }
}
