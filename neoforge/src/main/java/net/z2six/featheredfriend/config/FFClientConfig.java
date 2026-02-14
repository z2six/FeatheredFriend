// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/config/FFClientConfig.java
package net.z2six.featheredfriend.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/config/FFClientConfig.java
 *
 * Client-only config.
 *
 * Stores:
 * - autoSummonOnScroll: if true, holding a sealed scroll auto-summons the raven.
 *   if false, player must whistle manually.
 */
public final class FFClientConfig {

    private static final Logger LOG = LogUtils.getLogger();

    public static final boolean DEFAULT_AUTO_SUMMON_ON_SCROLL = true;
    public static final boolean DEFAULT_USE_VANILLA_FONT_FOR_GOTHIC_TEXT = false;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue AUTO_SUMMON_ON_SCROLL;
    public static final ModConfigSpec.BooleanValue USE_VANILLA_FONT_FOR_GOTHIC_TEXT;
    public static final ModConfigSpec.ConfigValue<String> FAVORITE_STAMP_KEY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("client");

        AUTO_SUMMON_ON_SCROLL = builder
                .comment(
                        "If true, holding a sealed scroll will automatically summon your raven (client preference).",
                        "If false, you must whistle manually."
                )
                .define("autoSummonOnScroll", DEFAULT_AUTO_SUMMON_ON_SCROLL);

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

    public static boolean isAutoSummonOnScroll() {
        try {
            return AUTO_SUMMON_ON_SCROLL.get();
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] isAutoSummonOnScroll failed, returning default {}", DEFAULT_AUTO_SUMMON_ON_SCROLL, t);
            return DEFAULT_AUTO_SUMMON_ON_SCROLL;
        }
    }

    public static void setAutoSummonOnScroll(boolean value) {
        try {
            AUTO_SUMMON_ON_SCROLL.set(value);
            LOG.debug("[FFClientConfig] autoSummonOnScroll set to {}", value);
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] setAutoSummonOnScroll failed safely: {}", t.toString());
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

    private FFClientConfig() {
        // no-op
    }
}
