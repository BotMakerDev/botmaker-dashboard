package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Plan;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * The confirmation in front of every release this window cuts: what will be tagged, why it cannot be undone,
 * and a word to type.
 *
 * <p>The Release tab and the Catalog tab's template release each built this dialog until 2026-09-29, with the
 * same list, the same word and the same button. One copy, so a change to what a release asks before it runs is
 * made once. The warning stays the caller's: the template release says what it does not move (Vet… does).
 *
 * <p>It lists the plan rather than the flags, because a release cuts what the <i>decide pass</i> decided — a
 * forced module is in that list and not in the command line.
 */
final class ReleaseConfirm {

    /** The word the operator types. A dialog one click from done is a dialog people dismiss. */
    static final String WORD = "release";

    private static final ButtonType CUT = new ButtonType("Cut the release", ButtonBar.ButtonData.OK_DONE);

    private ReleaseConfirm() {
    }

    /** One line per tag the plan would cut, indented for the list. Empty when the plan releases nothing. */
    static List<String> tags(Plan plan) {
        return plan.releasing().entrySet().stream()
                .map(cut -> "    " + cut.getKey().directory() + "  " + cut.getValue().tag())
                .toList();
    }

    /**
     * Shows the dialog and answers whether the operator typed the word and pressed the button.
     *
     * @param header  the command line being run, as the dialog's header
     * @param tags    {@link #tags}; the caller has already refused an empty list
     * @param warning what this release does, in the caller's words; the typing instruction is added here
     */
    static boolean ask(Window owner, String header, List<String> tags, String warning) {
        TextArea list = new TextArea(String.join("\n", tags));
        list.setEditable(false);
        list.getStyleClass().add("output-text");
        list.setPrefRowCount(Math.min(12, tags.size() + 1));

        Label words = new Label(warning + "\n\nType " + WORD + " to enable the button.");
        words.setWrapText(true);

        TextField typed = new TextField();
        typed.setPromptText(WORD);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Cut this release");
        dialog.setHeaderText(header);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().setAll(ButtonType.CANCEL, CUT);
        VBox body = new VBox(10, list, words, typed);
        body.setPadding(new Insets(4));
        pane.setContent(body);
        pane.lookupButton(CUT).setDisable(true);
        typed.textProperty().addListener((o, was, is) -> pane.lookupButton(CUT).setDisable(!WORD.equals(is.strip())));
        Themed.dialog(dialog, owner);
        return dialog.showAndWait().filter(CUT::equals).isPresent();
    }
}
