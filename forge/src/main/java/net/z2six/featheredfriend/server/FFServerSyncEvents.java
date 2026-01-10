// forge/src/main/java/net/z2six/featheredfriend/server/FFServerSyncEvents.java
package net.z2six.featheredfriend.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

/**
 * Registers FORGE bus listeners to sync server settings to clients on join.
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FFServerSyncEvents {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Kept for compatibility if your mod init calls it.
     * On Forge, the @EventBusSubscriber above is enough.
     */
    public static void registerGameBus() {
        LOG.debug("[FFServerSyncEvents] registerGameBus() called (Forge uses @EventBusSubscriber; nothing to do)");
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
