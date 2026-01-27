// MainFile: forge/src/main/java/net/z2six/featheredfriend/FeatheredFriend.java
package net.z2six.featheredfriend;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.z2six.featheredfriend.chat.ChatDisabler;
import net.z2six.featheredfriend.client.FFClientSyncEvents;
import net.z2six.featheredfriend.client.FFForgeClient;
import net.z2six.featheredfriend.client.FFKeyBindings;
import net.z2six.featheredfriend.client.particle.FFClientParticles;
import net.z2six.featheredfriend.client.raven.RavenClientEvents;
import net.z2six.featheredfriend.command.FeatheredFriendCommands;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import net.z2six.featheredfriend.config.FFClientConfig;
import net.z2six.featheredfriend.events.FFPlayerEvents;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.network.FFPayloads;
import net.z2six.featheredfriend.registry.*;
import net.z2six.featheredfriend.registry.FFForgeMenus;
import net.z2six.featheredfriend.server.FFServerSyncEvents;
import net.z2six.featheredfriend.world.RavenCourierRuntime;
import net.z2six.featheredfriend.world.RavenSpawnEvents;
import net.z2six.featheredfriend.world.TamedRavenScrollWatcher;
import org.slf4j.Logger;

@Mod(Constants.MOD_ID)
public class FeatheredFriend {

    private static final Logger LOG = LogUtils.getLogger();

    public FeatheredFriend() {
        LOG.debug("[FeatheredFriend] Initializing Forge side");

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

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
            FFForgeItems.register(modEventBus);
            FFCreativeTabsNeoForge.register(modEventBus);
            FFForgeMenus.register(modEventBus);

            FFForgeParticles.register(modEventBus);

            LOG.debug("[FeatheredFriend] Registered registries (items/tabs/menus/particles)");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register registries", t);
        }

        try {
            FFForgeEntities.register(modEventBus);
            LOG.debug("[FeatheredFriend] Registered entity registries");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register entities", t);
        }

        // ---------------------------------------------------------------------
        // Networking
        // ---------------------------------------------------------------------
        try {
            // Register SimpleChannel messages during FMLCommonSetupEvent
            FFNetwork.register(modEventBus);
            LOG.debug("[FeatheredFriend] Registered FFNetwork common-setup hook");
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

        LOG.debug("[FeatheredFriend] Forge initialization complete");
    }
}
