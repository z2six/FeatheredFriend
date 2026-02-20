// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/config/FFClientConfig.java
package net.z2six.featheredfriend.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.z2six.featheredfriend.client.font.ScrollUiFontMode;
import net.z2six.featheredfriend.client.ravenbadge.RavenStatusGuiAnchor;
import net.z2six.featheredfriend.client.ravenbadge.RavenStatusGuiVisualMode;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/config/FFClientConfig.java
 *
 * Client-only config.
 */
public final class FFClientConfig {

    private static final Logger LOG = LogUtils.getLogger();

    public static final ScrollUiFontMode DEFAULT_SCROLL_UI_FONT_MODE = ScrollUiFontMode.JACQUARD;
    public static final RavenStatusGuiVisualMode DEFAULT_RAVEN_STATUS_GUI_VISUAL_MODE = RavenStatusGuiVisualMode.BADGE_AND_TEXT;
    public static final boolean DEFAULT_USE_VANILLA_FONT_FOR_GOTHIC_TEXT = false;
    public static final int DEFAULT_RAVEN_STATUS_GUI_X = 12;
    public static final int DEFAULT_RAVEN_STATUS_GUI_Y = 12;
    public static final int RAVEN_STATUS_GUI_COORD_MAX = 20_000;
    public static final RavenStatusGuiAnchor DEFAULT_RAVEN_STATUS_GUI_ANCHOR = RavenStatusGuiAnchor.TOP_LEFT;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.ConfigValue<String> SCROLL_UI_FONT_MODE;
    public static final ModConfigSpec.ConfigValue<String> RAVEN_STATUS_GUI_VISUAL_MODE;
    public static final ModConfigSpec.BooleanValue USE_VANILLA_FONT_FOR_GOTHIC_TEXT;
    public static final ModConfigSpec.ConfigValue<String> FAVORITE_STAMP_KEY;
    public static final ModConfigSpec.ConfigValue<String> RAVEN_LOG_VIEW_SETTINGS_RAW;
    public static final ModConfigSpec.IntValue RAVEN_STATUS_GUI_X;
    public static final ModConfigSpec.IntValue RAVEN_STATUS_GUI_Y;
    public static final ModConfigSpec.ConfigValue<String> RAVEN_STATUS_GUI_ANCHOR;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("client");

        SCROLL_UI_FONT_MODE = builder
                .comment(
                        "Scroll UI font mode.",
                        "Valid values: VANILLA, JACQUARD, ALAGARD."
                )
                .define("scrollUiFontMode", DEFAULT_SCROLL_UI_FONT_MODE.name());

        USE_VANILLA_FONT_FOR_GOTHIC_TEXT = builder
                .comment(
                        "Legacy fallback for old clients/configs.",
                        "Deprecated: use scrollUiFontMode instead."
                )
                .define("useVanillaFontForGothicText", DEFAULT_USE_VANILLA_FONT_FOR_GOTHIC_TEXT);

        FAVORITE_STAMP_KEY = builder
                .comment(
                        "Favorite seal stamp key (client-only).",
                        "Format: owner|seed|slices|shapeSet (empty means no favorite)."
                )
                .define("favoriteStampKey", "");

        RAVEN_LOG_VIEW_SETTINGS_RAW = builder
                .comment(
                        "Raven Log client-side category visibility/color settings.",
                        "Format: categoryId,visible,colorRgb;... (managed automatically by UI)."
                )
                .define("ravenLogViewSettingsRaw", "");

        RAVEN_STATUS_GUI_X = builder
                .comment("Raven Status GUI X position (pixels from left).")
                .defineInRange("ravenStatusGuiX", DEFAULT_RAVEN_STATUS_GUI_X, 0, RAVEN_STATUS_GUI_COORD_MAX);

        RAVEN_STATUS_GUI_Y = builder
                .comment("Raven Status GUI Y position (pixels from top).")
                .defineInRange("ravenStatusGuiY", DEFAULT_RAVEN_STATUS_GUI_Y, 0, RAVEN_STATUS_GUI_COORD_MAX);

        RAVEN_STATUS_GUI_ANCHOR = builder
                .comment(
                        "Raven Status GUI anchor point.",
                        "Valid values: TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT."
                )
                .define("ravenStatusGuiAnchor", DEFAULT_RAVEN_STATUS_GUI_ANCHOR.name());

        RAVEN_STATUS_GUI_VISUAL_MODE = builder
                .comment(
                        "Raven Status GUI visual mode.",
                        "Valid values: BADGE_AND_TEXT, RAVEN_ONLY."
                )
                .define("ravenStatusGuiVisualMode", DEFAULT_RAVEN_STATUS_GUI_VISUAL_MODE.name());

        builder.pop();

        CLIENT_SPEC = builder.build();
        LOG.debug("[FFClientConfig] Built CLIENT config spec");
    }

    public static void register() {
        try {
            if (!FMLEnvironment.dist.isClient()) {
                LOG.debug("[FFClientConfig] register() called on non-client; skipping safely");
                return;
            }

            ModLoadingContext.get()
                    .getActiveContainer()
                    .registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);

            LOG.debug("[FFClientConfig] Registered CLIENT config");
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] Failed to register CLIENT config", t);
        }
    }

    public static @NotNull ScrollUiFontMode getScrollUiFontMode() {
        try {
            ScrollUiFontMode mode = ScrollUiFontMode.fromName(SCROLL_UI_FONT_MODE.get());
            if (mode == DEFAULT_SCROLL_UI_FONT_MODE && USE_VANILLA_FONT_FOR_GOTHIC_TEXT.get()) {
                // Backward-compat migration path from old boolean-only config.
                return ScrollUiFontMode.VANILLA;
            }
            return mode;
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] getScrollUiFontMode failed safely", t);
            return DEFAULT_SCROLL_UI_FONT_MODE;
        }
    }

    public static void setScrollUiFontMode(@NotNull ScrollUiFontMode mode) {
        try {
            ScrollUiFontMode safe = (mode == null) ? DEFAULT_SCROLL_UI_FONT_MODE : mode;
            SCROLL_UI_FONT_MODE.set(safe.name());
            USE_VANILLA_FONT_FOR_GOTHIC_TEXT.set(safe == ScrollUiFontMode.VANILLA);
            LOG.debug("[FFClientConfig] scrollUiFontMode set to {}", safe.name());
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] setScrollUiFontMode failed safely: {}", t.toString());
        }
    }

    public static boolean isUseVanillaFontForGothicText() {
        try {
            return getScrollUiFontMode() == ScrollUiFontMode.VANILLA;
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] isUseVanillaFontForGothicText failed, returning default {}",
                    DEFAULT_USE_VANILLA_FONT_FOR_GOTHIC_TEXT, t);
            return DEFAULT_USE_VANILLA_FONT_FOR_GOTHIC_TEXT;
        }
    }

    public static void setUseVanillaFontForGothicText(boolean value) {
        try {
            setScrollUiFontMode(value ? ScrollUiFontMode.VANILLA : ScrollUiFontMode.JACQUARD);
            LOG.debug("[FFClientConfig] useVanillaFontForGothicText set to {}", value);
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] setUseVanillaFontForGothicText failed safely: {}", t.toString());
        }
    }

    public static void save() {
        try {
            CLIENT_SPEC.save();
            LOG.debug("[FFClientConfig] Saved client config to disk");
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] save() failed safely", t);
        }
    }

    public static String getFavoriteStampKey() {
        try {
            return FAVORITE_STAMP_KEY.get();
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] getFavoriteStampKey failed safely", t);
            return "";
        }
    }

    public static void setFavoriteStampKey(String value) {
        try {
            FAVORITE_STAMP_KEY.set(value == null ? "" : value);
            LOG.debug("[FFClientConfig] favoriteStampKey set to {}", value);
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] setFavoriteStampKey failed safely: {}", t.toString());
        }
    }

    public static @NotNull String getRavenLogViewSettingsRaw() {
        try {
            String raw = RAVEN_LOG_VIEW_SETTINGS_RAW.get();
            return raw == null ? "" : raw;
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] getRavenLogViewSettingsRaw failed safely", t);
            return "";
        }
    }

    public static void setRavenLogViewSettingsRaw(@NotNull String value) {
        try {
            RAVEN_LOG_VIEW_SETTINGS_RAW.set(value == null ? "" : value);
            LOG.debug("[FFClientConfig] ravenLogViewSettingsRaw updated");
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] setRavenLogViewSettingsRaw failed safely: {}", t.toString());
        }
    }

    public static int getRavenStatusGuiX() {
        try {
            return RAVEN_STATUS_GUI_X.get();
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] getRavenStatusGuiX failed safely", t);
            return DEFAULT_RAVEN_STATUS_GUI_X;
        }
    }

    public static int getRavenStatusGuiY() {
        try {
            return RAVEN_STATUS_GUI_Y.get();
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] getRavenStatusGuiY failed safely", t);
            return DEFAULT_RAVEN_STATUS_GUI_Y;
        }
    }

    public static void setRavenStatusGuiX(int value) {
        try {
            int clamped = Math.max(0, Math.min(RAVEN_STATUS_GUI_COORD_MAX, value));
            RAVEN_STATUS_GUI_X.set(clamped);
            LOG.debug("[FFClientConfig] ravenStatusGuiX set to {}", clamped);
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] setRavenStatusGuiX failed safely: {}", t.toString());
        }
    }

    public static void setRavenStatusGuiY(int value) {
        try {
            int clamped = Math.max(0, Math.min(RAVEN_STATUS_GUI_COORD_MAX, value));
            RAVEN_STATUS_GUI_Y.set(clamped);
            LOG.debug("[FFClientConfig] ravenStatusGuiY set to {}", clamped);
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] setRavenStatusGuiY failed safely: {}", t.toString());
        }
    }

    public static @NotNull RavenStatusGuiAnchor getRavenStatusGuiAnchor() {
        try {
            String raw = RAVEN_STATUS_GUI_ANCHOR.get();
            return RavenStatusGuiAnchor.fromName(raw);
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] getRavenStatusGuiAnchor failed safely", t);
            return DEFAULT_RAVEN_STATUS_GUI_ANCHOR;
        }
    }

    public static void setRavenStatusGuiAnchor(@NotNull RavenStatusGuiAnchor anchor) {
        try {
            RavenStatusGuiAnchor safe = anchor == null ? DEFAULT_RAVEN_STATUS_GUI_ANCHOR : anchor;
            RAVEN_STATUS_GUI_ANCHOR.set(safe.name());
            LOG.debug("[FFClientConfig] ravenStatusGuiAnchor set to {}", safe.name());
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] setRavenStatusGuiAnchor failed safely: {}", t.toString());
        }
    }

    public static @NotNull RavenStatusGuiVisualMode getRavenStatusGuiVisualMode() {
        try {
            String raw = RAVEN_STATUS_GUI_VISUAL_MODE.get();
            return RavenStatusGuiVisualMode.fromName(raw);
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] getRavenStatusGuiVisualMode failed safely", t);
            return DEFAULT_RAVEN_STATUS_GUI_VISUAL_MODE;
        }
    }

    public static void setRavenStatusGuiVisualMode(@NotNull RavenStatusGuiVisualMode mode) {
        try {
            RavenStatusGuiVisualMode safe = mode == null ? DEFAULT_RAVEN_STATUS_GUI_VISUAL_MODE : mode;
            RAVEN_STATUS_GUI_VISUAL_MODE.set(safe.name());
            LOG.debug("[FFClientConfig] ravenStatusGuiVisualMode set to {}", safe.name());
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] setRavenStatusGuiVisualMode failed safely: {}", t.toString());
        }
    }

    private FFClientConfig() {
        // no-op
    }
}
