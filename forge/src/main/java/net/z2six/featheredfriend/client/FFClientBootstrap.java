// forge/src/main/java/net/z2six/featheredfriend/client/FFClientBootstrap.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.z2six.featheredfriend.config.FFClientConfig;
import net.z2six.featheredfriend.client.particle.FFClientParticles;
import net.z2six.featheredfriend.client.raven.RavenClientEvents;
import net.z2six.featheredfriend.client.FFKeyBindings;
import org.slf4j.Logger;

/**
 * Client-only bootstrap. This is invoked reflectively from the mod entrypoint,
 * so dedicated servers never link/load any classes referenced from here.
 */
public final class FFClientBootstrap {

    private static final Logger LOG = LogUtils.getLogger();

    private FFClientBootstrap() {}

    public static void init(IEventBus modEventBus) {
        LOG.info("[FFClientBootstrap] Client bootstrap init");

        try {
            // Client config (safe here; client-only classpath)
            FFClientConfig.register();
            LOG.info("[FFClientBootstrap] Registered FFClientConfig");
        } catch (Throwable t) {
            LOG.error("[FFClientBootstrap] FFClientConfig.register() failed safely", t);
        }

        try {
            modEventBus.addListener(RavenClientEvents::onRegisterRenderers);
            LOG.info("[FFClientBootstrap] Hooked Raven renderer registration listener");
        } catch (Throwable t) {
            LOG.error("[FFClientBootstrap] Failed to hook Raven renderer listener", t);
        }

        try {
            modEventBus.addListener(FFClientParticles::onRegisterParticleProviders);
            LOG.info("[FFClientBootstrap] Hooked particle provider registration listener");
        } catch (Throwable t) {
            LOG.error("[FFClientBootstrap] Failed to hook particle provider listener", t);
        }

        try {
            FFKeyBindings.register(modEventBus);
            LOG.info("[FFClientBootstrap] Registered FFKeyBindings");
        } catch (Throwable t) {
            LOG.error("[FFClientBootstrap] Failed to register FFKeyBindings", t);
        }

        try {
            modEventBus.addListener(FFClientBootstrap::onClientSetup);
            LOG.info("[FFClientBootstrap] Hooked client setup listener");
        } catch (Throwable t) {
            LOG.error("[FFClientBootstrap] Failed to hook client setup listener", t);
        }

        try {
            // Client-side chat blocking (client-only)
            ChatDisablerClient.register();
        } catch (Throwable t) {
            LOG.error("[FFClientBootstrap] Failed to register ChatDisablerClient", t);
        }

        try {
            // Your existing client sync events (client-only). If it registers on GAME bus internally, that’s fine.
            FFClientSyncEvents.registerGameBus();
            LOG.info("[FFClientBootstrap] Registered FFClientSyncEvents (client requests settings on connect)");
        } catch (Throwable t) {
            LOG.error("[FFClientBootstrap] Failed to register FFClientSyncEvents", t);
        }
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        try {
            LOG.debug("[FFClientBootstrap] onClientSetup invoked");

            // If you want to keep FFForgeClient, call it here safely:
            try {
                FFForgeClient.onClientSetup(event);
            } catch (Throwable ignored) {
            }

        } catch (Throwable t) {
            LOG.error("[FFClientBootstrap] onClientSetup failed safely", t);
        }
    }
}
