// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/FFClientSettingsCache.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/FFClientSettingsCache.java
 *
 * Client-side cache of server-owned FeatheredFriend settings (synced via payload).
 *
 * Currently:
 * - chatDisabled
 * - canEditChat (computed by server per-player; usually OP/owner)
 */
public final class FFClientSettingsCache {

    private static final Logger LOG = LogUtils.getLogger();

    private static volatile boolean hasServerSettings = false;
    private static volatile boolean chatDisabled = true;
    private static volatile boolean canEditChat = false;

    private static volatile long lastUpdateMillis = 0L;

    public static boolean hasServerSettings() {
        return hasServerSettings;
    }

    public static boolean isChatDisabled() {
        return chatDisabled;
    }

    public static boolean canEditChat() {
        return canEditChat;
    }

    public static long getLastUpdateMillis() {
        return lastUpdateMillis;
    }

    public static void applyFromServer(boolean newChatDisabled, boolean newCanEditChat) {
        try {
            hasServerSettings = true;
            chatDisabled = newChatDisabled;
            canEditChat = newCanEditChat;
            lastUpdateMillis = System.currentTimeMillis();

            LOG.info("[FFClientSettingsCache] Updated from server: chatDisabled={} canEditChat={}", chatDisabled, canEditChat);
        } catch (Throwable t) {
            LOG.error("[FFClientSettingsCache] applyFromServer failed safely", t);
        }
    }

    private FFClientSettingsCache() {
        // no-op
    }
}
