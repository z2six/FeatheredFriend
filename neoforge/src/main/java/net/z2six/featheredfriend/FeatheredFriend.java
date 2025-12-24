// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/FeatheredFriend.java
package net.z2six.featheredfriend;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.z2six.featheredfriend.client.FFNeoForgeClient;
import net.z2six.featheredfriend.client.particle.FFClientParticles;
import net.z2six.featheredfriend.client.raven.RavenClientEvents;
import net.z2six.featheredfriend.command.FeatheredFriendCommands;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.registry.FFCreativeTabsNeoForge;
import net.z2six.featheredfriend.registry.FFNeoForgeEntities;
import net.z2six.featheredfriend.registry.FFNeoForgeItems;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import net.z2six.featheredfriend.registry.FFNeoForgeParticles;
import net.z2six.featheredfriend.world.RavenSpawnEvents;
import net.z2six.featheredfriend.world.TamedRavenScrollWatcher;
import net.z2six.featheredfriend.world.RavenCourierRuntime;
import org.slf4j.Logger;

/**
 * NeoForge entrypoint for FeatheredFriend.
 *
 * Responsibilities:
 *  - Invoke common init code.
 *  - Register NeoForge-specific registries (items, menus, creative tabs, particles).
 *  - Register server-side calendar config.
 *  - Hook client-only registration (menu screens, entity renderers, particle providers) via the mod event bus.
 *  - Register payload handlers via mod event bus listener (NeoForge 1.21.1 safe).
 *  - Register entity types + attributes (Raven).
 *  - Register Raven natural spawning logic.
 *  - Register admin/debug commands (via NeoForge EVENT_BUS).
 *  - Register TamedRavenScrollWatcher to detect sealed scroll usage.
 */
@Mod(Constants.MOD_ID)
public class FeatheredFriend {

    private static final Logger LOG = LogUtils.getLogger();

    public FeatheredFriend(IEventBus modEventBus) {
        LOG.info("[FeatheredFriend] Initializing NeoForge side");

        // Common initialization (shared between platforms)
        try {
            CommonClass.init();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] CommonClass.init() failed", t);
        }

        // Server-side calendar config (NeoForge-specific)
        try {
            FFCalendarConfig.register();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] FFCalendarConfig.register() failed", t);
        }

        // NeoForge registries
        try {
            FFNeoForgeItems.register(modEventBus);
            FFCreativeTabsNeoForge.register(modEventBus);
            FFNeoForgeMenus.register(modEventBus);

            // Particles (TYPE registry)
            FFNeoForgeParticles.register(modEventBus);

            LOG.info("[FeatheredFriend] Registered NeoForge registries (items/tabs/menus/particles)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register NeoForge registries", t);
        }

        // Entities
        try {
            FFNeoForgeEntities.register(modEventBus);
            LOG.info("[FeatheredFriend] Registered NeoForge entity registries");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register NeoForge entities", t);
        }

        // Networking: payload handler registration (MOD bus event)
        try {
            modEventBus.addListener(FFNetwork::register);
            LOG.info("[FeatheredFriend] Hooked FFNetwork payload registration listener");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook FFNetwork payload registration listener", t);
        }

        // Legacy no-op call (kept for compatibility with your current structure)
        try {
            FFNetwork.registerSimpleMessages();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] FFNetwork.registerSimpleMessages() failed", t);
        }

        // Client-only: menu screens (called only on physical client)
        try {
            modEventBus.addListener(FFNeoForgeClient::onRegisterMenuScreens);
            LOG.info("[FeatheredFriend] Hooked client menu screen registration listener");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook client menu screen registration", t);
        }

        // Client-only: entity renderers + particle providers
        try {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                modEventBus.addListener(RavenClientEvents::onRegisterRenderers);
                LOG.info("[FeatheredFriend] Hooked Raven renderer registration listener (client only)");

                modEventBus.addListener(FFClientParticles::onRegisterParticleProviders);
                LOG.info("[FeatheredFriend] Hooked particle provider registration listener (client only)");
            } else {
                LOG.info("[FeatheredFriend] Skipping client-only listeners on non-client dist");
            }
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook client-only listeners", t);
        }

        // Server-only-ish: Raven natural spawning (listener is server-only at runtime, safe to register always)
        try {
            RavenSpawnEvents.register();
            LOG.info("[FeatheredFriend] Hooked RavenSpawnEvents (natural spawning)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook RavenSpawnEvents", t);
        }

        // ---------------------------------------------------------------------
        // TamedRavenScrollWatcher: reacts when a player holds a sealed scroll.
        // ---------------------------------------------------------------------
        try {
            TamedRavenScrollWatcher.register();
            LOG.info("[FeatheredFriend] Registered TamedRavenScrollWatcher");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register TamedRavenScrollWatcher", t);
        }

        // ---------------------------------------------------------------------
        // RavenCourierRuntime
        // ---------------------------------------------------------------------
        try {
            RavenCourierRuntime.register();
            LOG.info("[FeatheredFriend] Registered RavenCourierRuntime");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register RavenCourierRuntime", t);
        }

        // ---------------------------------------------------------------------
        // Commands: register admin/debug commands via helper (global NeoForge bus).
        // ---------------------------------------------------------------------
        try {
            FeatheredFriendCommands.register();
            LOG.info("[FeatheredFriend] Registered FeatheredFriendCommands");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register FeatheredFriendCommands", t);
        }

        LOG.info("[FeatheredFriend] NeoForge initialization complete");
    }
}
