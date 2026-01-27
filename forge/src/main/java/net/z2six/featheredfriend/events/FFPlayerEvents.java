package net.z2six.featheredfriend.events;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.z2six.featheredfriend.data.FFKnownPlayersData;
import net.z2six.featheredfriend.network.FFNetwork;
import org.slf4j.Logger;

import java.util.List;

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
                LOG.error("[FFPlayerEvents] PlayerLoggedInEvent: server is null for {}", safeName(serverPlayer));
                return;
            }

            FFKnownPlayersData data = FFKnownPlayersData.get(server);
            data.addOrUpdate(serverPlayer);

            List<FFKnownPlayersData.KnownPlayer> players = data.getSortedPlayers();

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
