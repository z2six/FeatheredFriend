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

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue AUTO_SUMMON_ON_SCROLL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("client");

        AUTO_SUMMON_ON_SCROLL = builder
                .comment(
                        "If true, holding a sealed scroll will automatically summon your raven (client preference).",
                        "If false, you must whistle manually."
                )
                .define("autoSummonOnScroll", DEFAULT_AUTO_SUMMON_ON_SCROLL);

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

    public static void save() {
        try {
            CLIENT_SPEC.save();
            LOG.debug("[FFClientConfig] Saved client config to disk");
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] save() failed safely", t);
        }
    }

    private FFClientConfig() {
        // no-op
    }
}
