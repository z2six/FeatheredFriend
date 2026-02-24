// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/config/FFServerConfig.java
package net.z2six.featheredfriend.config;

import com.mojang.logging.LogUtils;
import net.z2six.featheredfriend.Constants;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
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

    /**
     * How long a Raven Log entry is kept (in minutes).
     */
    public static final int DEFAULT_RAVEN_LOG_RETENTION_MINUTES = 60 * 24 * 7; // 7 days

    /**
     * Max bytes retained per player for Raven Logs.
     * If exceeded, that player's log is cleared entirely.
     */
    public static final int DEFAULT_RAVEN_LOG_MAX_BYTES_PER_PLAYER = 262_144; // 256 KiB

    /**
     * Real-time cooldown (seconds) between Enderpack -> Raven Chest deposit workflows per player.
     */
    public static final int DEFAULT_ENDERPACK_DEPOSIT_COOLDOWN_SECONDS = 600;

    /**
     * Real-time cooldown (seconds) between raven scroll deliveries per player.
     */
    public static final int DEFAULT_SCROLL_DELIVERY_COOLDOWN_SECONDS = 600;

    /**
     * Real-time interval (seconds) between automatic retries for courier jobs that failed due to timeout.
     * 0 disables auto-retry.
     */
    public static final int DEFAULT_COURIER_TIMEOUT_RETRY_SECONDS = 60;

    /**
     * Real-time cooldown (seconds) between "brushing" a raven with the vanilla Brush.
     * 0 disables cooldown.
     */
    public static final int DEFAULT_BRUSH_RAVEN_COOLDOWN_SECONDS = 30;

    /**
     * Raven Link duration in seconds.
     */
    public static final int DEFAULT_RAVEN_LINK_DURATION_SECONDS = 30;

    /**
     * If false, OPs cannot view/edit server-owned settings through the in-game settings screen.
     * This remains configurable only via server TOML.
     */
    public static final boolean DEFAULT_ALLOW_SERVER_SETTINGS_SCREEN_EDITING = true;

    // ---------------------------------------------------------------------
    // Feature toggles (server-authoritative; hot-reloadable)
    // ---------------------------------------------------------------------

    /**
     * Enable the Suspicious Feather item (Raven Link / revive).
     */
    public static final boolean DEFAULT_ENABLE_SUSPICIOUS_FEATHER = true;

    /**
     * Enable Suspicious Chest (Raven Chest) specific behavior.
     * Note: disabling does not delete existing blocks/items; it just disables FeatheredFriend logic.
     */
    public static final boolean DEFAULT_ENABLE_SUSPICIOUS_CHEST = true;

    /**
     * Enable Raven armor items and their stat modifiers.
     * Note: disabling does not delete existing armor items; stat modifiers are ignored.
     */
    public static final boolean DEFAULT_ENABLE_RAVEN_ARMOR = true;

    /**
     * Enable Mailbox block + mailbox delivery logic.
     * Note: disabling does not delete existing mailboxes; courier delivery to mailboxes is disabled.
     */
    public static final boolean DEFAULT_ENABLE_MAILBOX = true;

    // ---------------------------------------------------------------------
    // Spec + entries
    // ---------------------------------------------------------------------

    public static final ForgeConfigSpec SERVER_SPEC;

    // settings
    public static final ForgeConfigSpec.BooleanValue CHAT_DISABLED_DEFAULT;
    public static final ForgeConfigSpec.IntValue RAVEN_CHESTS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue RAVEN_LOG_RETENTION_MINUTES;
    public static final ForgeConfigSpec.IntValue RAVEN_LOG_MAX_BYTES_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue ENDERPACK_DEPOSIT_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue SCROLL_DELIVERY_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue COURIER_TIMEOUT_RETRY_SECONDS;
    public static final ForgeConfigSpec.IntValue BRUSH_RAVEN_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue RAVEN_LINK_DURATION_SECONDS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_SERVER_SETTINGS_SCREEN_EDITING;
    public static final ForgeConfigSpec.BooleanValue ENABLE_SUSPICIOUS_FEATHER;
    public static final ForgeConfigSpec.BooleanValue ENABLE_SUSPICIOUS_CHEST;
    public static final ForgeConfigSpec.BooleanValue ENABLE_RAVEN_ARMOR;
    public static final ForgeConfigSpec.BooleanValue ENABLE_MAILBOX;

    // spawning
    public static final ForgeConfigSpec.IntValue WILD_RAVENS_PER_PLAYER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

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

        RAVEN_LOG_RETENTION_MINUTES = builder
                .comment(
                        "How long Raven Log entries are retained, in minutes.",
                        "0 means keep no history."
                )
                .defineInRange("ravenLogRetentionMinutes", DEFAULT_RAVEN_LOG_RETENTION_MINUTES, 0, 60 * 24 * 90);

        RAVEN_LOG_MAX_BYTES_PER_PLAYER = builder
                .comment(
                        "Maximum retained Raven Log size per player (in bytes).",
                        "If exceeded, that player's Raven Log is cleared entirely.",
                        "0 means keep no history."
                )
                .defineInRange("ravenLogMaxBytesPerPlayer", DEFAULT_RAVEN_LOG_MAX_BYTES_PER_PLAYER, 0, 4 * 1024 * 1024);

        ENDERPACK_DEPOSIT_COOLDOWN_SECONDS = builder
                .comment(
                        "Real-time cooldown in seconds between Enderpack -> Raven Chest deposit workflows per player.",
                        "Hot-reloadable and server-authoritative.",
                        "0 disables cooldown."
                )
                .defineInRange("enderpackDepositCooldownSeconds", DEFAULT_ENDERPACK_DEPOSIT_COOLDOWN_SECONDS, 0, 86_400);

        SCROLL_DELIVERY_COOLDOWN_SECONDS = builder
                .comment(
                        "Real-time cooldown in seconds between raven scroll deliveries per player.",
                        "Hot-reloadable and server-authoritative.",
                        "0 disables cooldown."
                )
                .defineInRange("scrollDeliveryCooldownSeconds", DEFAULT_SCROLL_DELIVERY_COOLDOWN_SECONDS, 0, 86_400);

        COURIER_TIMEOUT_RETRY_SECONDS = builder
                .comment(
                        "Real-time interval in seconds between automatic retries for courier jobs that failed by timeout.",
                        "Hot-reloadable and server-authoritative.",
                        "0 disables automatic timeout retry."
                )
                .defineInRange("courierTimeoutRetrySeconds", DEFAULT_COURIER_TIMEOUT_RETRY_SECONDS, 0, 86_400);

        BRUSH_RAVEN_COOLDOWN_SECONDS = builder
                .comment(
                        "Real-time cooldown in seconds between brushing a raven (right-click with vanilla Brush).",
                        "Hot-reloadable and server-authoritative.",
                        "0 disables cooldown."
                )
                .defineInRange("brushRavenCooldownSeconds", DEFAULT_BRUSH_RAVEN_COOLDOWN_SECONDS, 0, 86_400);

        RAVEN_LINK_DURATION_SECONDS = builder
                .comment(
                        "How long Raven Link lasts (seconds).",
                        "Hot-reloadable and server-authoritative.",
                        "",
                        "Note: changing this value immediately affects ongoing Raven Links as well."
                )
                .defineInRange("ravenLinkDurationSeconds", DEFAULT_RAVEN_LINK_DURATION_SECONDS, 5, 600);

        ALLOW_SERVER_SETTINGS_SCREEN_EDITING = builder
                .comment(
                        "If false, no player (including OPs) can view or edit FeatheredFriend server settings",
                        "from the in-game settings screen. TOML still remains authoritative."
                )
                .define("allowServerSettingsScreenEditing", DEFAULT_ALLOW_SERVER_SETTINGS_SCREEN_EDITING);

        builder.push("features");

        ENABLE_SUSPICIOUS_FEATHER = builder
                .comment(
                        "If false, Suspicious Feather cannot start Raven Link / revive.",
                        "Existing items remain but will be inert."
                )
                .define("enableSuspiciousFeather", DEFAULT_ENABLE_SUSPICIOUS_FEATHER);

        ENABLE_SUSPICIOUS_CHEST = builder
                .comment(
                        "If false, Suspicious Chest (Raven Chest) special behavior is disabled.",
                        "Existing blocks/items remain; storage stays accessible."
                )
                .define("enableSuspiciousChest", DEFAULT_ENABLE_SUSPICIOUS_CHEST);

        ENABLE_RAVEN_ARMOR = builder
                .comment(
                        "If false, Raven armor items cannot be equipped and armor stat modifiers are ignored.",
                        "Existing armor items remain."
                )
                .define("enableRavenArmor", DEFAULT_ENABLE_RAVEN_ARMOR);

        ENABLE_MAILBOX = builder
                .comment(
                        "If false, Mailbox spotting + courier delivery to mailboxes is disabled.",
                        "Existing mailbox blocks/items remain; storage stays accessible."
                )
                .define("enableMailbox", DEFAULT_ENABLE_MAILBOX);

        builder.pop();

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
            // Forge's SERVER configs are per-world (saves/<world>/serverconfig), which is confusing for many server owners
            // and also doesn't match NeoForge's behavior in our 1.21.1 branch (single server-wide TOML).
            // We intentionally register as COMMON and keep a server-style filename, because:
            // - It lives in the normal /config folder
            // - It's still server-authoritative (we sync it to clients)
            // - It can be hot-reloaded and edited by server admins in one predictable location
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SERVER_SPEC, Constants.MOD_ID + "-server.toml");

            LOG.debug("[FFServerConfig] Registered server-authoritative config as COMMON ('{}-server.toml')", Constants.MOD_ID);
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

    public static void setWildRavensPerPlayer(int value) {
        try {
            int clamped = Math.max(0, Math.min(16, value));
            int before = getWildRavensPerPlayer();
            WILD_RAVENS_PER_PLAYER.set(clamped);
            if (before != clamped) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after wild raven cap update: {}",
                        saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setWildRavensPerPlayer failed", t);
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

    public static int getRavenLogRetentionMinutes() {
        try {
            int v = RAVEN_LOG_RETENTION_MINUTES.get();
            return Math.max(0, Math.min(60 * 24 * 90, v));
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getRavenLogRetentionMinutes failed, using default {}", DEFAULT_RAVEN_LOG_RETENTION_MINUTES, t);
            return DEFAULT_RAVEN_LOG_RETENTION_MINUTES;
        }
    }

    public static void setRavenLogRetentionMinutes(int value) {
        try {
            int clamped = Math.max(0, Math.min(60 * 24 * 90, value));
            int before = getRavenLogRetentionMinutes();
            RAVEN_LOG_RETENTION_MINUTES.set(clamped);
            if (before != clamped) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after raven log retention update: {}",
                        saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setRavenLogRetentionMinutes failed", t);
        }
    }

    public static int getRavenLogMaxBytesPerPlayer() {
        try {
            int v = RAVEN_LOG_MAX_BYTES_PER_PLAYER.get();
            return Math.max(0, Math.min(4 * 1024 * 1024, v));
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getRavenLogMaxBytesPerPlayer failed, using default {}", DEFAULT_RAVEN_LOG_MAX_BYTES_PER_PLAYER, t);
            return DEFAULT_RAVEN_LOG_MAX_BYTES_PER_PLAYER;
        }
    }

    public static void setRavenLogMaxBytesPerPlayer(int value) {
        try {
            int clamped = Math.max(0, Math.min(4 * 1024 * 1024, value));
            int before = getRavenLogMaxBytesPerPlayer();
            RAVEN_LOG_MAX_BYTES_PER_PLAYER.set(clamped);
            if (before != clamped) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after raven log size update: {}",
                        saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setRavenLogMaxBytesPerPlayer failed", t);
        }
    }

    public static int getEnderpackDepositCooldownSeconds() {
        try {
            int v = ENDERPACK_DEPOSIT_COOLDOWN_SECONDS.get();
            return Math.max(0, Math.min(86_400, v));
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getEnderpackDepositCooldownSeconds failed, using default {}", DEFAULT_ENDERPACK_DEPOSIT_COOLDOWN_SECONDS, t);
            return DEFAULT_ENDERPACK_DEPOSIT_COOLDOWN_SECONDS;
        }
    }

    public static void setEnderpackDepositCooldownSeconds(int value) {
        try {
            int clamped = Math.max(0, Math.min(86_400, value));
            int before = getEnderpackDepositCooldownSeconds();
            ENDERPACK_DEPOSIT_COOLDOWN_SECONDS.set(clamped);
            if (before != clamped) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after enderpack deposit cooldown update: {}",
                        saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setEnderpackDepositCooldownSeconds failed", t);
        }
    }

    public static int getScrollDeliveryCooldownSeconds() {
        try {
            int v = SCROLL_DELIVERY_COOLDOWN_SECONDS.get();
            return Math.max(0, Math.min(86_400, v));
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getScrollDeliveryCooldownSeconds failed, using default {}", DEFAULT_SCROLL_DELIVERY_COOLDOWN_SECONDS, t);
            return DEFAULT_SCROLL_DELIVERY_COOLDOWN_SECONDS;
        }
    }

    public static void setScrollDeliveryCooldownSeconds(int value) {
        try {
            int clamped = Math.max(0, Math.min(86_400, value));
            int before = getScrollDeliveryCooldownSeconds();
            SCROLL_DELIVERY_COOLDOWN_SECONDS.set(clamped);
            if (before != clamped) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after scroll delivery cooldown update: {}",
                        saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setScrollDeliveryCooldownSeconds failed", t);
        }
    }

    public static int getCourierTimeoutRetrySeconds() {
        try {
            int v = COURIER_TIMEOUT_RETRY_SECONDS.get();
            return Math.max(0, Math.min(86_400, v));
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getCourierTimeoutRetrySeconds failed, using default {}", DEFAULT_COURIER_TIMEOUT_RETRY_SECONDS, t);
            return DEFAULT_COURIER_TIMEOUT_RETRY_SECONDS;
        }
    }

    public static void setCourierTimeoutRetrySeconds(int value) {
        try {
            int clamped = Math.max(0, Math.min(86_400, value));
            int before = getCourierTimeoutRetrySeconds();
            COURIER_TIMEOUT_RETRY_SECONDS.set(clamped);
            if (before != clamped) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after courier timeout retry update: {}",
                        saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setCourierTimeoutRetrySeconds failed", t);
        }
    }

    public static int getBrushRavenCooldownSeconds() {
        try {
            int v = BRUSH_RAVEN_COOLDOWN_SECONDS.get();
            return Math.max(0, Math.min(86_400, v));
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getBrushRavenCooldownSeconds failed, using default {}", DEFAULT_BRUSH_RAVEN_COOLDOWN_SECONDS, t);
            return DEFAULT_BRUSH_RAVEN_COOLDOWN_SECONDS;
        }
    }

    public static void setBrushRavenCooldownSeconds(int value) {
        try {
            int clamped = Math.max(0, Math.min(86_400, value));
            int before = getBrushRavenCooldownSeconds();
            BRUSH_RAVEN_COOLDOWN_SECONDS.set(clamped);
            if (before != clamped) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after brush raven cooldown update: {}",
                        saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setBrushRavenCooldownSeconds failed", t);
        }
    }

    public static int getRavenLinkDurationSeconds() {
        try {
            int v = RAVEN_LINK_DURATION_SECONDS.get();
            return Math.max(5, Math.min(600, v));
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] getRavenLinkDurationSeconds failed, using default {}", DEFAULT_RAVEN_LINK_DURATION_SECONDS, t);
            return DEFAULT_RAVEN_LINK_DURATION_SECONDS;
        }
    }

    public static void setRavenLinkDurationSeconds(int value) {
        try {
            int clamped = Math.max(5, Math.min(600, value));
            int before = getRavenLinkDurationSeconds();
            RAVEN_LINK_DURATION_SECONDS.set(clamped);
            if (before != clamped) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after raven link duration update: {}",
                        saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setRavenLinkDurationSeconds failed", t);
        }
    }

    public static boolean isServerSettingsScreenEditingEnabled() {
        try {
            return ALLOW_SERVER_SETTINGS_SCREEN_EDITING.get();
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] isServerSettingsScreenEditingEnabled failed, using default {}",
                    DEFAULT_ALLOW_SERVER_SETTINGS_SCREEN_EDITING, t);
            return DEFAULT_ALLOW_SERVER_SETTINGS_SCREEN_EDITING;
        }
    }

    public static boolean isSuspiciousFeatherEnabled() {
        try {
            return ENABLE_SUSPICIOUS_FEATHER.get();
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] isSuspiciousFeatherEnabled failed, using default {}", DEFAULT_ENABLE_SUSPICIOUS_FEATHER, t);
            return DEFAULT_ENABLE_SUSPICIOUS_FEATHER;
        }
    }

    public static void setSuspiciousFeatherEnabled(boolean enabled) {
        try {
            boolean before = isSuspiciousFeatherEnabled();
            ENABLE_SUSPICIOUS_FEATHER.set(enabled);
            if (before != enabled) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after enableSuspiciousFeather update: {}", saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setSuspiciousFeatherEnabled failed", t);
        }
    }

    public static boolean isSuspiciousChestEnabled() {
        try {
            return ENABLE_SUSPICIOUS_CHEST.get();
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] isSuspiciousChestEnabled failed, using default {}", DEFAULT_ENABLE_SUSPICIOUS_CHEST, t);
            return DEFAULT_ENABLE_SUSPICIOUS_CHEST;
        }
    }

    public static void setSuspiciousChestEnabled(boolean enabled) {
        try {
            boolean before = isSuspiciousChestEnabled();
            ENABLE_SUSPICIOUS_CHEST.set(enabled);
            if (before != enabled) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after enableSuspiciousChest update: {}", saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setSuspiciousChestEnabled failed", t);
        }
    }

    public static boolean isRavenArmorEnabled() {
        try {
            return ENABLE_RAVEN_ARMOR.get();
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] isRavenArmorEnabled failed, using default {}", DEFAULT_ENABLE_RAVEN_ARMOR, t);
            return DEFAULT_ENABLE_RAVEN_ARMOR;
        }
    }

    public static void setRavenArmorEnabled(boolean enabled) {
        try {
            boolean before = isRavenArmorEnabled();
            ENABLE_RAVEN_ARMOR.set(enabled);
            if (before != enabled) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after enableRavenArmor update: {}", saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setRavenArmorEnabled failed", t);
        }
    }

    public static boolean isMailboxEnabled() {
        try {
            return ENABLE_MAILBOX.get();
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] isMailboxEnabled failed, using default {}", DEFAULT_ENABLE_MAILBOX, t);
            return DEFAULT_ENABLE_MAILBOX;
        }
    }

    public static void setMailboxEnabled(boolean enabled) {
        try {
            boolean before = isMailboxEnabled();
            ENABLE_MAILBOX.set(enabled);
            if (before != enabled) {
                markSettingsDirty();
            }
            try {
                SERVER_SPEC.save();
            } catch (Throwable saveErr) {
                LOG.warn("[FFServerConfig] Failed to save SERVER config to disk after enableMailbox update: {}", saveErr.toString());
            }
        } catch (Throwable t) {
            LOG.error("[FFServerConfig] setMailboxEnabled failed", t);
        }
    }

    private FFServerConfig() {
        // no-op
    }
}
