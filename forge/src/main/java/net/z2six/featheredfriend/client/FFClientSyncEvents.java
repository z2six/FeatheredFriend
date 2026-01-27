// MainFile: forge/src/main/java/net/z2six/featheredfriend/client/FFClientSyncEvents.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.z2six.featheredfriend.config.FFClientConfig;
import net.z2six.featheredfriend.network.FFPayloads;
import org.slf4j.Logger;

/**
 * Client-side settings sync initiator.
 *
 * Sends:
 * - RequestServerSettingsPayload once connection is fully ready (server-owned settings)
 * - ClientAutoSummonPrefPayload once connection is ready (client per-player preference)
 */
public final class FFClientSyncEvents {

    private static final Logger LOG = LogUtils.getLogger();
    private static volatile boolean REGISTERED = false;
    private static volatile boolean REQUEST_PENDING = false;
    private static volatile boolean REQUEST_SENT_THIS_SESSION = false;

    private static volatile boolean CLIENT_PREF_SENT_THIS_SESSION = false;

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
            LOG.debug("[FFClientSyncEvents] Registered on MinecraftForge EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[FFClientSyncEvents] registerGameBus failed safely", t);
        }
    }

    @SubscribeEvent
    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            REQUEST_PENDING = true;
            REQUEST_SENT_THIS_SESSION = false;
            CLIENT_PREF_SENT_THIS_SESSION = false;

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
            CLIENT_PREF_SENT_THIS_SESSION = false;

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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            Player player = mc.player;
            if (player == null) return;
            if (mc.level == null) return;
            if (mc.getConnection() == null) return;

            // 1) Server-owned settings request
            if (REQUEST_PENDING && !REQUEST_SENT_THIS_SESSION) {
                FFPayloads.sendRequestServerSettingsToServer();

                REQUEST_SENT_THIS_SESSION = true;
                REQUEST_PENDING = false;

                LOG.debug("[FFClientSyncEvents] Sent RequestServerSettingsPayload (safe tick)");
            }

            // 2) Client preference sync (auto-summon)
            if (!CLIENT_PREF_SENT_THIS_SESSION) {
                boolean pref = true;
                try {
                    pref = FFClientConfig.isAutoSummonOnScroll();
                } catch (Throwable t) {
                    pref = FFClientConfig.DEFAULT_AUTO_SUMMON_ON_SCROLL;
                    LOG.warn("[FFClientSyncEvents] Failed reading FFClientConfig autoSummon; defaulting to {}. err={}",
                            pref, t.toString());
                }

                FFPayloads.sendClientAutoSummonPrefToServer(pref);
                CLIENT_PREF_SENT_THIS_SESSION = true;

                LOG.debug("[FFClientSyncEvents] Sent ClientAutoSummonPrefPayload (autoSummonOnScroll={})", pref);
            }

        } catch (Throwable t) {
            LOG.error("[FFClientSyncEvents] onClientTick failed safely; will retry next tick", t);
        }
    }
}
