package com.botmaker.dashboard.ui;

import javafx.scene.Node;
import javafx.scene.control.Tab;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A tab whose content is built the first time it is selected.
 *
 * <p>Every tab used to be built at start, and each started reading at once: the Releases tab fetched tags from
 * every module, the Changelog tab fetched one, and the Queue and Catalog asked GitHub for their whole listing —
 * twice, since the admin probe reloads them too. A tab nobody opens now costs nothing (2026-09-29).
 *
 * <p>What the window tells a tab (a new checkout, a new admin verdict) goes through {@link #ifBuilt}; a tab
 * built later reads the current state from its builder instead.
 */
public final class LazyTab<T extends Node> {

    private final Tab tab;
    private final Supplier<T> build;
    private T content;

    public LazyTab(String title, Supplier<T> build) {
        this.tab = new Tab(title);
        this.build = build;
        tab.selectedProperty().addListener((o, was, is) -> {
            if (is) {
                get();
            }
        });
    }

    public Tab tab() {
        return tab;
    }

    /** The content, built now if it was not yet. FX thread only. */
    public T get() {
        if (content == null) {
            content = build.get();
            tab.setContent(content);
        }
        return content;
    }

    /** Runs {@code action} on the content if the tab has been opened; a tab not yet built reads the state later. */
    public void ifBuilt(Consumer<T> action) {
        if (content != null) {
            action.accept(content);
        }
    }
}
