package net.z2six.featheredfriend.client.ravenbadge;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Client visual mode for raven status HUD.
 */
public enum RavenStatusGuiVisualMode {
    BADGE_AND_TEXT("screen.featheredfriend.settings.raven_status_gui_visual_mode.badge_and_text"),
    RAVEN_ONLY("screen.featheredfriend.settings.raven_status_gui_visual_mode.raven_only");

    private final String translationKey;

    RavenStatusGuiVisualMode(@NotNull String translationKey) {
        this.translationKey = translationKey;
    }

    public @NotNull String translationKey() {
        return translationKey;
    }

    public @NotNull RavenStatusGuiVisualMode next() {
        RavenStatusGuiVisualMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static @NotNull RavenStatusGuiVisualMode fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return BADGE_AND_TEXT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (RavenStatusGuiVisualMode value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return BADGE_AND_TEXT;
    }
}

