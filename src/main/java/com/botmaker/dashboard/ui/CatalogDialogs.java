package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.github.Catalog;
import com.botmaker.dashboard.github.Vetting;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Catalog tab's four proposals — edit, unpublish, vet, revoke — as dialogs, and the two that report on one.
 *
 * <p>Out of the tab since 2026-09-29, when it was nine hundred lines. Each asks and, when the operator says
 * yes, answers the pull request it started; the tab runs it and keeps the buttons. Nothing here merges or
 * judges: a proposal is a pull request, and {@code RegistryGate} is what judges it there.
 */
final class CatalogDialogs {

    private final GitHubClient client;
    private final GitHubAuth auth;
    private final Supplier<Window> owner;
    private final Consumer<String> status;

    CatalogDialogs(GitHubClient client, GitHubAuth auth, Supplier<Window> owner, Consumer<String> status) {
        this.client = client;
        this.auth = auth;
        this.owner = owner;
        this.status = status;
    }

    /**
     * Proposes vetting a bot at one release.
     *
     * <p>The version box starts on the newest release, which is almost always the one just looked at, and is
     * editable because it need not be. Nothing about the release is checked here: the gallery's gate checks
     * that it downloads, on the pull request this opens.
     */
    Optional<CompletableFuture<Catalog.Proposal>> vet(Catalog.Entry entry) {
        TextField version = new TextField(entry.vetted() == null ? "" : entry.vetted().record().vettedVersion());
        version.setPromptText("release tag, e.g. v0.1.0");
        Vetting.latestRelease(client, auth, entry).thenAccept(tag -> Platform.runLater(() -> {
            if (!tag.isBlank() && version.getText().isBlank()) {
                version.setText(tag);
            }
        }));
        TextField why = new TextField();
        why.setPromptText("What you looked at (optional) — goes in the pull request body");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Vet " + entry.id());
        dialog.setHeaderText((entry.vetted() == null ? "" : "Vetted now at "
                + entry.vetted().record().vettedVersion() + ".\n")
                + "Opens a pull request writing vetted/ in " + entry.kind().repo() + ". Once merged, Studio shows "
                + entry.id() + " as Vetted and installs exactly this release.\n\nThe release you looked at:");
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(new VBox(8, version, why));
        ButtonType open = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        pane.getButtonTypes().setAll(open, ButtonType.CANCEL);
        pane.lookupButton(open).disableProperty().bind(version.textProperty().isEmpty());
        if (!confirmed(dialog)) {
            return Optional.empty();
        }
        String tag = version.getText().trim();
        if (entry.vetted() != null && tag.equals(entry.vetted().record().vettedVersion())) {
            status.accept(entry.id() + " is already vetted at " + tag + ", so no pull request was opened.");
            return Optional.empty();
        }
        return Optional.of(Vetting.vet(client, auth, entry, tag, why.getText()));
    }

    /** Proposes removing a bot's vetting; its listing stays. */
    Optional<CompletableFuture<Catalog.Proposal>> revoke(Catalog.Entry entry) {
        TextField why = new TextField();
        why.setPromptText("Why (optional) — goes in the pull request body");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Revoke vetting of " + entry.id());
        dialog.setHeaderText("Opens a pull request deleting " + entry.vetted().path() + ".\nOnce merged, "
                + entry.id() + " is Community again: still listed, installed at its newest release, and gone from "
                + "the index older Studios read.");
        dialog.getDialogPane().setContent(new VBox(8, why));
        ButtonType open = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().setAll(open, ButtonType.CANCEL);
        return confirmed(dialog)
                ? Optional.of(Vetting.revoke(client, auth, entry, why.getText()))
                : Optional.empty();
    }

    /**
     * Proposes new text for an entry.
     *
     * <p><b>A text area over the JSON, not a form built from a field list.</b> A form can only show the
     * keys it was written to know about, so it would silently drop one an entry carries and this window has
     * never heard of — the same reason {@code EntryFields} reads the file rather than a schema. The dialog
     * says whether the text parses, and does not refuse it: whether an entry is <i>good</i> is
     * {@code RegistryGate}'s answer on the pull request, and a syntax opinion formed here is the first step
     * towards a second gate.
     */
    Optional<CompletableFuture<Catalog.Proposal>> edit(Catalog.Entry entry) {
        TextArea json = new TextArea(entry.json() == null ? "" : entry.json());
        json.getStyleClass().add("output-text");
        json.setPrefRowCount(20);
        json.setPrefColumnCount(90);

        Label parses = new Label();
        parses.getStyleClass().add("status-line");
        json.textProperty().addListener((obs, was, now) -> sayWhetherItParses(parses, now));
        sayWhetherItParses(parses, json.getText());

        TextField why = new TextField();
        why.setPromptText("Why (optional) — goes in the pull request body");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit " + entry.id());
        dialog.setHeaderText("Opens a pull request against " + entry.kind().repo()
                + ". Nothing on main changes until somebody merges it.");
        VBox body = new VBox(8, json, parses, why);
        VBox.setVgrow(json, Priority.ALWAYS);
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(body);
        pane.getButtonTypes().setAll(new ButtonType("Open pull request", ButtonType.OK.getButtonData()),
                ButtonType.CANCEL);
        if (!confirmed(dialog)) {
            return Optional.empty();
        }
        String text = json.getText();
        if (text.equals(entry.json())) {
            // Not a gate — arithmetic. A pull request that changes nothing is one somebody has to close.
            status.accept("Nothing changed, so no pull request was opened.");
            return Optional.empty();
        }
        return Optional.of(Catalog.edit(client, auth, entry, text, why.getText()));
    }

    private static void sayWhetherItParses(Label label, String text) {
        try {
            new ObjectMapper().readTree(text);
            label.setText("Parses as JSON.");
            label.getStyleClass().removeAll("cell--broken");
        } catch (Exception e) {
            label.setText("Not valid JSON — the gate will refuse this: " + e.getMessage());
            if (!label.getStyleClass().contains("cell--broken")) {
                label.getStyleClass().add("cell--broken");
            }
        }
    }

    /**
     * Proposes removing an entry.
     *
     * <p><b>Typing the id, not clicking Yes.</b> Merging this makes a plugin disappear from every user's
     * Manage Plugins and a bot from the gallery Studio reads — and the id is the one thing that cannot be
     * recovered by re-submitting, because the file name is the claim. A confirmation somebody can dismiss
     * by reflex is not one.
     */
    Optional<CompletableFuture<Catalog.Proposal>> unpublish(Catalog.Entry entry) {
        TextField typed = new TextField();
        typed.setPromptText(entry.id());
        TextField why = new TextField();
        why.setPromptText("Why (optional) — goes in the pull request body");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Unpublish " + entry.id());
        dialog.setHeaderText("This opens a pull request that deletes " + entry.path() + " from "
                + entry.kind().repo() + ".\nNothing is removed until somebody merges it. Merging it removes "
                + entry.id() + " for everyone on the next index build.\n\nType the id to confirm:");
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(new VBox(8, typed, why));
        ButtonType open = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        pane.getButtonTypes().setAll(open, ButtonType.CANCEL);
        pane.lookupButton(open).setDisable(true);
        typed.textProperty().addListener((obs, was, now) ->
                pane.lookupButton(open).setDisable(!entry.id().equals(now.trim())));
        return confirmed(dialog)
                ? Optional.of(Catalog.unpublish(client, auth, entry, why.getText()))
                : Optional.empty();
    }

    /** Offers the pull request rather than opening a browser unasked. */
    void opened(Catalog.Proposal proposal) {
        ButtonType openIt = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText("Opened #" + proposal.number());
        alert.setContentText("Branch " + proposal.branch()
                + ".\nNothing is published or unpublished until it is merged.");
        alert.getButtonTypes().setAll(openIt, ButtonType.CLOSE);
        Themed.dialog(alert, owner.get());
        alert.showAndWait()
                .filter(b -> b == openIt)
                .ifPresent(b -> Browse.open(proposal.url(), status));
    }

    /**
     * Shows GitHub's own sentence.
     *
     * <p>A 403 because the token's scope was narrowed, a 409 because {@code main} moved under the blob sha,
     * and a 422 because the branch already exists are three different problems, and paraphrasing them into
     * "could not open a pull request" costs the operator the only line that says which.
     */
    void failed(String message) {
        TextArea text = new TextArea(message);
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("error-text");
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText("GitHub refused it");
        alert.getDialogPane().setContent(text);
        alert.getButtonTypes().setAll(ButtonType.OK);
        Themed.dialog(alert, owner.get());
        alert.showAndWait();
    }

    private boolean confirmed(Dialog<ButtonType> dialog) {
        Themed.dialog(dialog, owner.get());
        Optional<ButtonType> chose = dialog.showAndWait();
        return chose.isPresent() && chose.get().getButtonData() == ButtonType.OK.getButtonData();
    }
}
