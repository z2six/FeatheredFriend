// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/server/FFServerSyncEvents.java
package net.z2six.featheredfriend.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/server/FFServerSyncEvents.java
 *
 * Registers GAME bus listeners to sync server settings to clients on join.
 */
public final class FFServerSyncEvents {

    private static final Logger LOG = LogUtils.getLogger();

    public static void registerGameBus() {
        try {
            NeoForge.EVENT_BUS.register(FFServerSyncEvents.class);
            LOG.debug("[FFServerSyncEvents] Registered on NeoForge EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[FFServerSyncEvents] registerGameBus failed safely", t);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer sp)) {
                return;
            }
            if (sp.serverLevel() == null) {
                return;
            }

            FFServerSettingsSync.sendToPlayer(sp.serverLevel(), sp);
        } catch (Throwable t) {
            LOG.error("[FFServerSyncEvents] onPlayerLoggedIn failed safely", t);
        }
    }

    private FFServerSyncEvents() {
        // no-op
    }
}
