// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/config/FFServerConfig.java
package net.z2six.featheredfriend.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

/**
 * NeoForge-side SERVER config for FeatheredFriend.
 *
 * Sections:
 * - [settings]
 *   * chatDisabledDefault (server-authoritative global chat toggle)
 *
 * - [spawning]
 *   * wildRavensPerPlayer (server-authoritative spawn limit)
 *
 * NOTE:
 * - Historically chatDisabled was stored as world-owned SavedData and this config only provided the default.
 * - This made editing the server `.toml` appear to "not work" on existing worlds.
 * - `chatDisabledDefault` is now the authoritative server value (hot-reloadable) and is what is synced to clients.
 */
public final class FFServerConfig {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Server-authoritative global chat toggle.
     * Chat is enabled by default.
     */
    public static final boolean DEFAULT_CHAT_DISABLED_DEFAULT = false;

    /**
     * Default maximum wild ravens per online player.
     */
    public static final int DEFAULT_WILD_RAVENS_PER_PLAYER = 1;

    /**
     * Default max placed Raven Chests per player.
     */
    public static final int DEFAULT_RAVEN_CHESTS_PER_PLAYER = 3;

    // ---------------------------------------------------------------------
    // Spec + entries
    // ---------------------------------------------------------------------

    public static final ModConfigSpec SERVER_SPEC;

    // settings
    public static final ModConfigSpec.BooleanValue CHAT_DISABLED_DEFAULT;
    public static final ModConfigSpec.IntValue RAVEN_CHESTS_PER_PLAYER;

    // spawning
    public static final ModConfigSpec.IntValue WILD_RAVENS_PER_PLAYER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // -----------------------
        // settings
        // -----------------------
        builder.push("settings");

        CHAT_DISABLED_DEFAULT = builder
                .comment(
                        "Server-authoritative global player chat toggle for FeatheredFriend.",
                        "If true, FeatheredFriend will block player chat (commands unaffected).",
                        "",
                        "This value is hot-reloadable and is synced to clients.",
                        "",
                        "Note: older versions stored chatDisabled in world SavedData; this config now drives the behavior directly."
                )
                .define("chatDisabledDefault", DEFAULT_CHAT_DISABLED_DEFAULT);

        RAVEN_CHESTS_PER_PLAYER = builder
                .comment(
                        "Maximum number of Raven Chests a single player may have placed.",
                        "Hot-reloadable and server-authoritative.",
                        "",
                        "If reduced below currently placed counts, existing chests are not removed,",
                        "but additional placements are blocked until the player is back within limit."
                )
                .defineInRange("ravenChestsPerPlayer", DEFAULT_RAVEN_CHESTS_PER_PLAYER, 0, 64);

        builder.pop();

        // -----------------------
        // spawning
        // -----------------------
        builder.push("spawning");

        WILD_RAVENS_PER_PLAYER = builder
                .comment(
                        "How many WILD (untamed) ravens FeatheredFriend may keep spawned per online (non-spectator) player.",
                        "This is only used by FeatheredFriend's own natural spawn handler (not by vanilla biome spawns).",
                        "",
                        "Examples:",
                        "- 0: disable wild raven spawning (and aggressively cull existing wild ravens near players)",
                        "- 1: at most 1 wild raven per online player (default)",
                        "- 2: allow up to 2 wild ravens per online player"
                )
                .defineInRange("wildRavensPerPlayer", DEFAULT_WILD_RAVENS_PER_PLAYER, 0, 16);

        builder.pop();

        SERVER_SPEC = builder.build();

        LOG.debug("[FFServerConfig] Built SERVER config spec (settings + spawning)");
    }

    // ---------------------------------------------------------------------
    // Registration (NeoForge idiom)
    // ---------------------------------------------------------------------

    public static void register() {
        try {
            ModLoadingContext.get()
                    .getActiveContainer()
                    .registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);

            LOG.debug("[FFServerConfig] Registered SERVER config with active ModContainer");
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] Failed to register SERVER config", t);
        }
    }

    // ---------------------------------------------------------------------
    // Safe accessors (server-side use; client reads synced values)
    // ---------------------------------------------------------------------

    public static boolean getChatDisabledDefault() {
        try {
            return CHAT_DISABLED_DEFAULT.get();
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getChatDisabledDefault failed, using default {}", DEFAULT_CHAT_DISABLED_DEFAULT, t);
            return DEFAULT_CHAT_DISABLED_DEFAULT;
        }
    }

    /**
     * Server-authoritative runtime value: whether global chat is disabled.
     */
    public static boolean isChatDisabled() {
        return getChatDisabledDefault();
    }

    /**
     * Server-side setter used by the Settings GUI / networking.
     * Updates the in-memory config and requests a settings re-sync.
     */
    public static void setChatDisabled(boolean disabled) {
        try {
            boolean before = isChatDisabled();
            CHAT_DISABLED_DEFAULT.set(disabled);
            if (before != disabled) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after chat toggle: {}", saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setChatDisabled failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Hot-reload -> re-sync plumbing
    // ---------------------------------------------------------------------

    private static volatile boolean SETTINGS_DIRTY = false;

    public static void markSettingsDirty() {
        SETTINGS_DIRTY = true;
    }

    /**
     * @return true if a broadcast is needed (and clears the flag).
     */
    public static boolean consumeSettingsDirty() {
        if (!SETTINGS_DIRTY) return false;
        SETTINGS_DIRTY = false;
        return true;
    }

    public static int getWildRavensPerPlayer() {
        try {
            int v = WILD_RAVENS_PER_PLAYER.get();
            if (v < 0) v = 0;
            if (v > 16) v = 16;
            return v;
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getWildRavensPerPlayer failed, using default {}", DEFAULT_WILD_RAVENS_PER_PLAYER, t);
            return DEFAULT_WILD_RAVENS_PER_PLAYER;
        }
    }

    public static int getRavenChestsPerPlayer() {
        try {
            int v = RAVEN_CHESTS_PER_PLAYER.get();
            if (v < 0) v = 0;
            if (v > 64) v = 64;
            return v;
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getRavenChestsPerPlayer failed, using default {}", DEFAULT_RAVEN_CHESTS_PER_PLAYER, t);
            return DEFAULT_RAVEN_CHESTS_PER_PLAYER;
        }
    }

    public static void setRavenChestsPerPlayer(int value) {
        try {
            int clamped = Math.max(0, Math.min(64, value));
            int before = getRavenChestsPerPlayer();
            RAVEN_CHESTS_PER_PLAYER.set(clamped);
            if (before != clamped) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after raven chest cap update: {}",
                        saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setRavenChestsPerPlayer failed", t);
        }
    }

    private FFServerConfig() {
        // no-op
    }
}
