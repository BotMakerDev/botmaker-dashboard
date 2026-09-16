package com.botmaker.dashboard;

/**
 * The window's two palettes, as a closed set.
 *
 * <p>The {@link #id()} is what {@code dashboard.json} stores and {@link #styleClass()} is what the scene
 * roots carry; both are fixed so that a stored preference keeps its meaning across releases. The parse is
 * total — an id nothing here recognises is "no preference", and the window then follows the desktop.
 *
 * <p>No JavaFX here: the desktop's own scheme is read in {@code ui/Themed}, which is the only place that
 * may ask the toolkit, and this type stays testable without one.
 */
public enum Theme {

    DARK("dark"),
    LIGHT("light");

    private final String id;

    Theme(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String styleClass() {
        return "theme-" + id;
    }

    public Theme other() {
        return this == DARK ? LIGHT : DARK;
    }

    /** Total: {@code null}, blank or unknown answers {@code null}, meaning "follow the desktop". */
    public static Theme fromId(String id) {
        for (Theme theme : values()) {
            if (theme.id.equals(id)) {
                return theme;
            }
        }
        return null;
    }
}
