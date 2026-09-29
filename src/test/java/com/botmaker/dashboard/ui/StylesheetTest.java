package com.botmaker.dashboard.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** What JavaFX CSS cannot parse, kept out of the dashboard's stylesheet. */
class StylesheetTest {

    /**
     * {@code inherit} is not a JavaFX CSS value. On a font property it reached the {@code -fx-font} converter as a
     * bare string and threw {@code ClassCastException} on every layout of a combo-box popup (2026-09-29), the rule
     * dropped each time.
     */
    @Test
    void noPropertyIsSetToInherit() throws IOException {
        String css;
        try (InputStream in = StylesheetTest.class.getResourceAsStream("/css/dashboard.css")) {
            assertNotNull(in, "dashboard.css is on the classpath");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String code = css.replaceAll("(?s)/\\*.*?\\*/", "");
        assertFalse(Pattern.compile(":\\s*inherit\\s*;").matcher(code).find(), "a property set to inherit");
    }
}
