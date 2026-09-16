package com.botmaker.dashboard.ui.widgets;

import javafx.animation.PauseTransition;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;

import java.util.function.Supplier;

/**
 * Puts a block of text on the clipboard.
 *
 * <p><b>It exists because the two things worth copying here are not selectable.</b> A gate's refusal is a
 * column of {@code Label}s and a lane's error is inside a read-only {@code TextArea} that scrolls; both are
 * exactly what an operator wants to paste into a terminal, an issue or a message, and neither could be got
 * out of the window except by retyping it.
 *
 * <p>The text is a {@link Supplier} rather than a value, so a button built once keeps copying whatever the
 * widget around it is showing now — a lane redrawn by the next poll copies the new error, not the one it
 * carried when the button was made.
 */
public final class CopyButton extends Button {

    private static final Duration SAID = Duration.seconds(1.5);

    private final String label;

    public CopyButton(String label, Supplier<String> text) {
        super(label);
        this.label = label;
        getStyleClass().add("copy-button");
        setFocusTraversable(false);
        setTooltip(new Tooltip("Copy this to the clipboard"));
        setOnAction(e -> copy(text.get()));
    }

    private void copy(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        // The clipboard is invisible, so the button says what happened and then goes back to its name.
        setText("Copied");
        PauseTransition back = new PauseTransition(SAID);
        back.setOnFinished(done -> setText(label));
        back.play();
    }
}
