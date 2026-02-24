// neoforge/src/main/java/net/z2six/featheredfriend/events/FFPlayerEvents.java
package net.z2six.featheredfriend.events;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.z2six.featheredfriend.data.FFKnownPlayersData;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import java.util.List;

/**
 * FFPlayerEvents
 *
 * Game-level player event handlers.
 *
 * Registration:
 *  - Call FFPlayerEvents.register() from the main mod constructor.
 *
 * On login:
 *  - Add/update player in persisted server SavedData (UUID->name)
 *  - Broadcast full known-player list (UUID+name) to all online clients
 */
public final class FFPlayerEvents {

    private static final Logger LOG = LogUtils.getLogger();

    private static volatile boolean REGISTERED = false;

    private FFPlayerEvents() {
    }

    public static void register() {
        if (REGISTERED) {
            LOG.debug("[FFPlayerEvents] register(): already registered, skipping");
            return;
        }

        try {
            MinecraftForge.EVENT_BUS.addListener(FFPlayerEvents::onPlayerLoggedIn);
            MinecraftForge.EVENT_BUS.addListener(FFPlayerEvents::onPlayerClone);
            REGISTERED = true;
            LOG.debug("[FFPlayerEvents] Registered PlayerLoggedInEvent listener on MinecraftForge.EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[FFPlayerEvents] register() failed safely", t);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        try {
            MinecraftServer server = serverPlayer.server;
            if (server == null) {
                LOG.error("[FFPlayerEvents] PlayerLoggedInEvent: server is null for {}",
                        safeName(serverPlayer));
                return;
            }

            // Persist (UUID -> latest name)
            FFKnownPlayersData data = FFKnownPlayersData.get(server);
            data.addOrUpdate(serverPlayer);

            // Build sorted UUID+name list
            List<FFKnownPlayersData.KnownPlayer> players = data.getSortedPlayers();

            // Broadcast to everyone online (including the one who just joined)
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                try {
                    FFNetwork.sendKnownPlayersTo(online, players);
                } catch (Throwable sendErr) {
                    LOG.warn("[FFPlayerEvents] Failed sending known players list to {}: {}",
                            safeName(online), sendErr.toString());
                }
            }

            LOG.debug("[FFPlayerEvents] Login '{}' -> known players now {} (broadcasted to {} online)",
                    safeName(serverPlayer),
                    players.size(),
                    server.getPlayerList().getPlayers().size());

        } catch (Throwable t) {
            LOG.error("[FFPlayerEvents] Failed to process PlayerLoggedInEvent", t);
        }
    }

    /**
     * Ensure our per-player persistent data survives player cloning (death/respawn).
     *
     * Without this, entries like {@code featheredfriend.TamedRaven} can be lost when the server creates
     * a new player entity after death, leading to "my tamed raven disappeared from the stored list".
     */
    private static void onPlayerClone(PlayerEvent.Clone event) {
        try {
            if (event == null) return;
            if (!(event.getEntity() instanceof ServerPlayer newPlayer)) return;
            if (!(event.getOriginal() instanceof ServerPlayer oldPlayer)) return;

            CompoundTag oldRoot = oldPlayer.getPersistentData();
            if (oldRoot == null) return;
            if (!oldRoot.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) return;

            CompoundTag oldMod = oldRoot.getCompound(Constants.MOD_ID);
            if (oldMod == null || oldMod.isEmpty()) return;

            CompoundTag newRoot = newPlayer.getPersistentData();
            if (newRoot == null) return;

            // Copy our entire mod compound to preserve tamed raven records and any future persistent flags.
            newRoot.put(Constants.MOD_ID, oldMod.copy());

            LOG.debug("[FFPlayerEvents] Player cloned: copied persistent '{}' tag for player={} (wasDeath={})",
                    Constants.MOD_ID, safeName(newPlayer), event.isWasDeath());
        } catch (Throwable t) {
            LOG.error("[FFPlayerEvents] onPlayerClone failed safely", t);
        }
    }

    private static String safeName(ServerPlayer p) {
        try {
            return p.getGameProfile().getName();
        } catch (Throwable ignored) {
            try {
                return p.getName().getString();
            } catch (Throwable ignored2) {
                return "<unknown>";
            }
        }
    }
}
