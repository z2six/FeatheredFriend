// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/config/FFClientConfig.java
package net.z2six.featheredfriend.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/config/FFClientConfig.java
 *
 * Client-only config.
 */
public final class FFClientConfig {

    private static final Logger LOG = LogUtils.getLogger();

    public static final boolean DEFAULT_USE_VANILLA_FONT_FOR_GOTHIC_TEXT = false;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue USE_VANILLA_FONT_FOR_GOTHIC_TEXT;
    public static final ModConfigSpec.ConfigValue<String> FAVORITE_STAMP_KEY;
    public static final ModConfigSpec.ConfigValue<String> RAVEN_LOG_VIEW_SETTINGS_RAW;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("client");

        USE_VANILLA_FONT_FOR_GOTHIC_TEXT = builder
                .comment(
                        "If true, use the default Minecraft font instead of the gothic12 font in scroll/stamp UIs.",
                        "If false, keep the gothic12 font."
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

    public static boolean isUseVanillaFontForGothicText() {
        try {
            return USE_VANILLA_FONT_FOR_GOTHIC_TEXT.get();
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] isUseVanillaFontForGothicText failed, returning default {}",
                    DEFAULT_USE_VANILLA_FONT_FOR_GOTHIC_TEXT, t);
            return DEFAULT_USE_VANILLA_FONT_FOR_GOTHIC_TEXT;
        }
    }

    public static void setUseVanillaFontForGothicText(boolean value) {
        try {
            USE_VANILLA_FONT_FOR_GOTHIC_TEXT.set(value);
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

    private FFClientConfig() {
        // no-op
    }
}
