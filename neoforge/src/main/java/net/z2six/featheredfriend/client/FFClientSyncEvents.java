// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/FFClientSyncEvents.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.z2six.featheredfriend.network.FFPayloads;
import org.slf4j.Logger;

/**
 * Client-side settings sync initiator.
 * Sends request only once connection is fully ready.
 */
public final class FFClientSyncEvents {

    private static final Logger LOG = LogUtils.getLogger();
    private static volatile boolean REGISTERED = false;
    private static volatile boolean REQUEST_PENDING = false;
    private static volatile boolean REQUEST_SENT_THIS_SESSION = false;

    private FFClientSyncEvents() {
        // no-op
    }

    public static void registerGameBus() {
        try {
            if (REGISTERED) {
                LOG.debug("[FFClientSyncEvents] already registered; skipping");
                return;
            }
            NeoForge.EVENT_BUS.register(FFClientSyncEvents.class);
            REGISTERED = true;
            LOG.debug("[FFClientSyncEvents] Registered on NeoForge EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[FFClientSyncEvents] registerGameBus failed safely", t);
        }
    }

    @SubscribeEvent
    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            REQUEST_PENDING = true;
            REQUEST_SENT_THIS_SESSION = false;

            try {
                FFPayloads.ClientState.clear();
            } catch (Throwable ignored) {
            }

            LOG.debug("[FFClientSyncEvents] LoggingIn: marked settings request pending");
        } catch (Throwable t) {
            LOG.error("[FFClientSyncEvents] onClientLoggingIn failed safely", t);
        }
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        try {
            REQUEST_PENDING = false;
            REQUEST_SENT_THIS_SESSION = false;

            try {
                FFPayloads.ClientState.clear();
            } catch (Throwable ignored) {
            }

            LOG.debug("[FFClientSyncEvents] LoggingOut: cleared pending flags + cache");
        } catch (Throwable t) {
            LOG.error("[FFClientSyncEvents] onClientLoggingOut failed safely", t);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        try {
            if (!REQUEST_PENDING || REQUEST_SENT_THIS_SESSION) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            Player player = mc.player;
            if (player == null) return;
            if (mc.level == null) return;
            if (mc.getConnection() == null) return;

            PacketDistributor.sendToServer(new FFPayloads.RequestServerSettingsPayload());

            REQUEST_SENT_THIS_SESSION = true;
            REQUEST_PENDING = false;

            LOG.debug("[FFClientSyncEvents] Sent RequestServerSettingsPayload (safe tick)");

        } catch (Throwable t) {
            LOG.error("[FFClientSyncEvents] onClientTick failed safely; will retry next tick", t);
        }
    }
}
