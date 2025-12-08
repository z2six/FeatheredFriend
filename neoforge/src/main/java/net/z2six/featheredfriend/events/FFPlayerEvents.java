package net.z2six.featheredfriend.events;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.data.FFKnownPlayersData;
import net.z2six.featheredfriend.network.FFNetwork;
import org.slf4j.Logger;

import java.util.List;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/events/FFPlayerEvents.java
 *
 * FFPlayerEvents
 *
 * Game-level player event handlers.
 * - On PlayerLoggedInEvent:
 *     * Update FFKnownPlayersData with this player.
 *     * Send the fresh known-player list to all currently online players.
 */
@EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class FFPlayerEvents {

    private static final Logger LOG = LogUtils.getLogger();

    private FFPlayerEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        try {
            MinecraftServer server = serverPlayer.server;
            FFKnownPlayersData data = FFKnownPlayersData.get(server);

            // Add/update this player in the SavedData
            data.addOrUpdate(serverPlayer);

            // Grab sorted list of all known names
            List<String> names = data.getSortedNames();

            // Broadcast to all currently connected players so everyone sees the fresh list
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                FFNetwork.sendKnownPlayersTo(online, names);
            }

            LOG.debug("[FFPlayerEvents] Updated known players due to login of {}", serverPlayer.getGameProfile().getName());
        } catch (Throwable t) {
            LOG.error("[FFPlayerEvents] Failed to process PlayerLoggedInEvent", t);
        }
    }
}
