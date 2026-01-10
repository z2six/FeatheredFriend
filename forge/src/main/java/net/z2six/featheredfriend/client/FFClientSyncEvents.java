package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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
            MinecraftForge.EVENT_BUS.register(FFClientSyncEvents.class);
            REGISTERED = true;
            LOG.info("[FFClientSyncEvents] Registered on MinecraftForge EVENT_BUS");
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

            LOG.info("[FFClientSyncEvents] LoggingIn: marked settings request pending");
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

            LOG.info("[FFClientSyncEvents] LoggingOut: cleared pending flags + cache");
        } catch (Throwable t) {
            LOG.error("[FFClientSyncEvents] onClientLoggingOut failed safely", t);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

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

            // Forge 1.20.1: send via SimpleChannel
            FFPayloads.sendRequestServerSettingsToServer();

            REQUEST_SENT_THIS_SESSION = true;
            REQUEST_PENDING = false;

            LOG.info("[FFClientSyncEvents] Sent RequestServerSettingsPayload (safe tick)");

        } catch (Throwable t) {
            LOG.error("[FFClientSyncEvents] onClientTick failed safely; will retry next tick", t);
        }
    }
}
