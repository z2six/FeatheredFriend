package net.z2six.featheredfriend.client.ravenbadge;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Anchor used by the raven status HUD position offsets.
 */
public enum RavenStatusGuiAnchor {
    TOP_LEFT("screen.featheredfriend.settings.raven_status_gui_anchor.top_left"),
    TOP_CENTER("screen.featheredfriend.settings.raven_status_gui_anchor.top_center"),
    TOP_RIGHT("screen.featheredfriend.settings.raven_status_gui_anchor.top_right"),
    CENTER("screen.featheredfriend.settings.raven_status_gui_anchor.center"),
    BOTTOM_LEFT("screen.featheredfriend.settings.raven_status_gui_anchor.bottom_left"),
    BOTTOM_CENTER("screen.featheredfriend.settings.raven_status_gui_anchor.bottom_center"),
    BOTTOM_RIGHT("screen.featheredfriend.settings.raven_status_gui_anchor.bottom_right");

    private final String translationKey;

    RavenStatusGuiAnchor(@NotNull String translationKey) {
        this.translationKey = translationKey;
    }

    public @NotNull String translationKey() {
        return translationKey;
    }

    public @NotNull RavenStatusGuiAnchor next() {
        RavenStatusGuiAnchor[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static @NotNull RavenStatusGuiAnchor fromName(@NotNull String raw) {
        if (raw == null || raw.isBlank()) {
            return TOP_LEFT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (RavenStatusGuiAnchor anchor : values()) {
            if (anchor.name().equals(normalized)) {
                return anchor;
            }
        }
        return TOP_LEFT;
    }
}
