// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/FeatheredFriend.java
package net.z2six.featheredfriend;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.z2six.featheredfriend.chat.ChatDisabler;
import net.z2six.featheredfriend.client.FFClientSyncEvents;
import net.z2six.featheredfriend.client.FFKeyBindings;
import net.z2six.featheredfriend.client.FFNeoForgeClient;
import net.z2six.featheredfriend.client.particle.FFClientParticles;
import net.z2six.featheredfriend.client.raven.RavenClientEvents;
import net.z2six.featheredfriend.command.FeatheredFriendCommands;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import net.z2six.featheredfriend.config.FFClientConfig;
import net.z2six.featheredfriend.events.FFPlayerEvents;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.network.FFPayloads;
import net.z2six.featheredfriend.registry.FFCreativeTabsNeoForge;
import net.z2six.featheredfriend.registry.FFNeoForgeEntities;
import net.z2six.featheredfriend.registry.FFNeoForgeItems;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import net.z2six.featheredfriend.registry.FFNeoForgeParticles;
import net.z2six.featheredfriend.server.FFServerSyncEvents;
import net.z2six.featheredfriend.server.FFConfigSyncEvents;
import net.z2six.featheredfriend.world.RavenCourierRuntime;
import net.z2six.featheredfriend.world.RavenSpawnEvents;
import net.z2six.featheredfriend.world.TamedRavenScrollWatcher;
import org.slf4j.Logger;

@Mod(Constants.MOD_ID)
public class FeatheredFriend {

    private static final Logger LOG = LogUtils.getLogger();

    public FeatheredFriend(IEventBus modEventBus) {
        LOG.debug("[FeatheredFriend] Initializing NeoForge side");

        // ---------------------------------------------------------------------
        // Common init
        // ---------------------------------------------------------------------
        try {
            CommonClass.init();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] CommonClass.init() failed", t);
        }

        // ---------------------------------------------------------------------
        // Configs
        // ---------------------------------------------------------------------
        try {
            // Server config (calendar + settings default)
            FFCalendarConfig.register();
            LOG.debug("[FeatheredFriend] Registered FFCalendarConfig (SERVER)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] FFCalendarConfig.register() failed", t);
        }

        try {
            // Client config (autoSummon preference). Safe to call on server; it will self-skip.
            FFClientConfig.register();
            LOG.debug("[FeatheredFriend] Registered FFClientConfig (CLIENT if applicable)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] FFClientConfig.register() failed", t);
        }

        // ---------------------------------------------------------------------
        // Registries
        // ---------------------------------------------------------------------
        try {
            FFNeoForgeItems.register(modEventBus);
            FFCreativeTabsNeoForge.register(modEventBus);
            FFNeoForgeMenus.register(modEventBus);

            FFNeoForgeParticles.register(modEventBus);

            LOG.debug("[FeatheredFriend] Registered NeoForge registries (items/tabs/menus/particles)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register NeoForge registries", t);
        }

        try {
            FFNeoForgeEntities.register(modEventBus);
            LOG.debug("[FeatheredFriend] Registered NeoForge entity registries");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register NeoForge entities", t);
        }

        // ---------------------------------------------------------------------
        // Networking
        // ---------------------------------------------------------------------
        try {
            // Your existing network hookup (keep it if you still use it for other payloads/messages)
            modEventBus.addListener(FFNetwork::register);
            LOG.debug("[FeatheredFriend] Hooked FFNetwork payload registration listener");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook FFNetwork payload registration listener", t);
        }

        try {
            // New payload system for server settings sync (chatDisabled, canEditChat, etc.)
            // This MUST be registered on the mod event bus.
            FFPayloads.register(modEventBus);
            LOG.debug("[FeatheredFriend] Registered FFPayloads (server settings sync payloads)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register FFPayloads", t);
        }

        try {
            // If you still rely on SimpleChannel / legacy messages in FFNetwork:
            FFNetwork.registerSimpleMessages();
            LOG.debug("[FeatheredFriend] FFNetwork.registerSimpleMessages() OK");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] FFNetwork.registerSimpleMessages() failed", t);
        }

        // ---------------------------------------------------------------------
        // Client-only registrations
        // ---------------------------------------------------------------------
        try {
            modEventBus.addListener(FFNeoForgeClient::onRegisterMenuScreens);
            LOG.debug("[FeatheredFriend] Hooked client menu screen registration listener");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook client menu screen registration", t);
        }

        try {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                modEventBus.addListener(RavenClientEvents::onRegisterRenderers);
                LOG.debug("[FeatheredFriend] Hooked Raven renderer registration listener (client only)");

                modEventBus.addListener(FFClientParticles::onRegisterParticleProviders);
                LOG.debug("[FeatheredFriend] Hooked particle provider registration listener (client only)");

                FFKeyBindings.register(modEventBus);
                LOG.debug("[FeatheredFriend] Registered FFKeyBindings (client only)");
            } else {
                LOG.debug("[FeatheredFriend] Skipping client-only listeners on non-client dist");
            }
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook client-only listeners", t);
        }

        // ---------------------------------------------------------------------
        // GAME bus registrations (explicit, no deprecated @EventBusSubscriber(bus=GAME))
        // ---------------------------------------------------------------------
        try {
            FFPlayerEvents.register();
            LOG.debug("[FeatheredFriend] Registered FFPlayerEvents (player login -> known-player persistence + broadcast)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register FFPlayerEvents", t);
        }

        try {
            RavenSpawnEvents.register();
            LOG.debug("[FeatheredFriend] Hooked RavenSpawnEvents (natural spawning)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook RavenSpawnEvents", t);
        }

        try {
            TamedRavenScrollWatcher.register();
            LOG.debug("[FeatheredFriend] Registered TamedRavenScrollWatcher");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register TamedRavenScrollWatcher", t);
        }

        try {
            RavenCourierRuntime.register();
            LOG.debug("[FeatheredFriend] Registered RavenCourierRuntime");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register RavenCourierRuntime", t);
        }

        try {
            ChatDisabler.register();
            LOG.debug("[FeatheredFriend] Registered ChatDisabler");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register ChatDisabler", t);
        }

        // ---------------------------------------------------------------------
        // Server/Client sync events for settings
        // ---------------------------------------------------------------------
        try {
            // Server: push settings on login (and you can reuse it anywhere else too)
            FFServerSyncEvents.registerGameBus();
            LOG.debug("[FeatheredFriend] Registered FFServerSyncEvents (server login sync)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register FFServerSyncEvents", t);
        }

        try {
            // Server: hot-reload + GUI changes -> re-sync to clients (chatDisabled, etc.)
            FFConfigSyncEvents.registerGameBus();
            LOG.debug("[FeatheredFriend] Registered FFConfigSyncEvents (hot-reload settings sync)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register FFConfigSyncEvents", t);
        }

        try {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                // Client: request settings on connect/logging-in
                FFClientSyncEvents.registerGameBus();
                LOG.debug("[FeatheredFriend] Registered FFClientSyncEvents (client requests settings on connect)");
            } else {
                LOG.debug("[FeatheredFriend] Skipping FFClientSyncEvents on non-client dist");
            }
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register FFClientSyncEvents", t);
        }

        // ---------------------------------------------------------------------
        // Commands
        // ---------------------------------------------------------------------
        try {
            FeatheredFriendCommands.register();
            LOG.debug("[FeatheredFriend] Registered FeatheredFriendCommands");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register FeatheredFriendCommands", t);
        }

        LOG.debug("[FeatheredFriend] NeoForge initialization complete");
    }
}
