package net.z2six.featheredfriend.config;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;

public final class FFClientConfig {

    private static final Logger LOG = LogUtils.getLogger();

    public static final boolean DEFAULT_AUTO_SUMMON_ON_SCROLL = true;

    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec.BooleanValue AUTO_SUMMON_ON_SCROLL;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

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

            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
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

    /**
     * Best-effort immediate save. Works on Forge builds where ModConfig.save() exists.
     * Uses reflection to avoid hard API coupling across minor Forge revisions.
     */
    public static void save() {
        try {
            ModList.get().getModContainerById(Constants.MOD_ID).ifPresent(container -> {
                try {
                    Object configs = null;

                    // Try getConfigs(): Map<ModConfig.Type, ModConfig>
                    try {
                        Method m = container.getClass().getMethod("getConfigs");
                        configs = m.invoke(container);
                    } catch (Throwable ignored) {
                    }

                    if (configs instanceof Map<?, ?> map) {
                        Object clientCfg = map.get(ModConfig.Type.CLIENT);
                        if (clientCfg != null) {
                            tryInvokeSave(clientCfg);
                            return;
                        }
                    }

                    // Fallback: try getConfig() (some container impls)
                    try {
                        Method m2 = container.getClass().getMethod("getConfig");
                        Object cfg = m2.invoke(container);
                        if (cfg != null) {
                            tryInvokeSave(cfg);
                        }
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable t) {
                    LOG.error("[FFClientConfig] save(): failed locating mod config instance", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFClientConfig] save() failed safely", t);
        }
    }

    private static void tryInvokeSave(Object modConfigLike) {
        try {
            Method save = modConfigLike.getClass().getMethod("save");
            save.invoke(modConfigLike);
            LOG.debug("[FFClientConfig] Saved client config to disk");
        } catch (Throwable t) {
            LOG.debug("[FFClientConfig] save(): ModConfig.save not available on this Forge build ({})", t.toString());
        }
    }

    private FFClientConfig() {
    }
}
