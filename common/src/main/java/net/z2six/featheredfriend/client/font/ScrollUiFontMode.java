package net.z2six.featheredfriend.client.font;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Client-side font mode for scroll/stamp/naming UIs.
 */
public enum ScrollUiFontMode {
    VANILLA("screen.featheredfriend.settings.scroll_ui_font.vanilla"),
    JACQUARD("screen.featheredfriend.settings.scroll_ui_font.jacquard"),
    ALAGARD("screen.featheredfriend.settings.scroll_ui_font.alagard");

    private final String translationKey;

    ScrollUiFontMode(@NotNull String translationKey) {
        this.translationKey = translationKey;
    }

    public @NotNull String translationKey() {
        return translationKey;
    }

    public @NotNull ScrollUiFontMode next() {
        ScrollUiFontMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static @NotNull ScrollUiFontMode fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return JACQUARD;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ScrollUiFontMode value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return JACQUARD;
    }
}

