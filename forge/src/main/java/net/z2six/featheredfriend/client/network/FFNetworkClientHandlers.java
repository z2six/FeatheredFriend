package net.z2six.featheredfriend.client.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;
import net.z2six.featheredfriend.client.knownplayers.KnownPlayersClientCache;
import net.z2six.featheredfriend.client.screen.RavenNamingScreen;
import net.z2six.featheredfriend.network.FFNetwork;
import org.slf4j.Logger;

public final class FFNetworkClientHandlers {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNetworkClientHandlers() {
    }

    public static void handleKnownPlayersOnClient(FFNetwork.KnownPlayersPayload payload,
                                                  NetworkEvent.Context context) {
        try {
            KnownPlayersClientCache cache = KnownPlayersClientCache.getInstance();
            cache.replaceAllFromServer(payload.players());
            LOG.debug("[FFNetworkClientHandlers] Updated client known-players cache: {} entries", payload.players().size());
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleKnownPlayersOnClient failed", t);
        }
    }

    public static void handleOpenRavenNameScreenOnClient(FFNetwork.OpenRavenNameScreenPayload payload,
                                                         NetworkEvent.Context context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (mc.player == null) return;
            if (mc.level == null) return;

            int id = payload.ravenEntityId();
            LOG.debug("[FFNetworkClientHandlers] Opening RavenNamingScreen for ravenEntityId={}", id);
            mc.setScreen(new RavenNamingScreen(id));
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleOpenRavenNameScreenOnClient failed", t);
        }
    }
}
