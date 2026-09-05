package com.botmaker.dashboard.ui;

import java.awt.Desktop;
import java.net.URI;

/**
 * Opens a URL in the operator's browser, best-effort.
 *
 * <p><b>Best-effort is the contract, not a shortcut.</b> A headless session, a Linux desktop with no
 * {@code xdg-open}, a restricted environment — none of them is a reason to interrupt what the operator was
 * doing, because everything this window offers to open is also readable from the window itself (a device
 * code is on screen, a tag is in the table). So a failure is one line on stderr and nothing else.
 */
public final class Browse {

    private Browse() {
    }

    public static void open(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            System.err.println("Could not open the browser: " + e.getMessage());
        }
    }
}
