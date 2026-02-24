// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/server/FFServerSettingsSync.java
package net.z2six.featheredfriend.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.z2six.featheredfriend.network.FFPayloads;
import org.slf4j.Logger;

/**
 * Server-side utilities for syncing FeatheredFriend settings to clients.
 *
 * Delegates to FFPayloads (single source of truth).
 */
public final class FFServerSettingsSync {

    private static final Logger LOG = LogUtils.getLogger();

    private FFServerSettingsSync() {
        // no-op
    }

    public static void sendToPlayer(ServerLevel level, ServerPlayer player) {
        try {
            FFPayloads.sendSettingsToPlayer(level, player);
        } catch (Throwable t) {
            LOG.error("[FFServerSettingsSync] sendToPlayer failed safely", t);
        }
    }

    public static void sendToAll(ServerLevel level) {
        try {
            FFPayloads.broadcastSettings(level);
        } catch (Throwable t) {
            LOG.error("[FFServerSettingsSync] sendToAll failed safely", t);
        }
    }
}
