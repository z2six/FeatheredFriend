package net.z2six.featheredfriend.log;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * High-level categories used by the Raven Log.
 */
public enum RavenLogCategory {
    SUMMON("summon"),
    COURIER("courier"),
    COMBAT("combat"),
    PERCH("perch"),
    ENDERPACK("enderpack"),
    CHEST("chest"),
    SYSTEM("system");

    private final String id;

    RavenLogCategory(@NotNull String id) {
        this.id = id;
    }

    public @NotNull String id() {
        return this.id;
    }

    public @NotNull String translationKey() {
        return "screen.featheredfriend.raven_log.category." + this.id;
    }

    public static @NotNull RavenLogCategory fromId(@NotNull String raw) {
        if (raw == null || raw.isBlank()) {
            return SYSTEM;
        }
        String id = raw.toLowerCase(Locale.ROOT).trim();
        for (RavenLogCategory c : values()) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return SYSTEM;
    }
}

