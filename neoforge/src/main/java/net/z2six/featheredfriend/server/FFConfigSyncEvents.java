package net.z2six.featheredfriend.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.z2six.featheredfriend.config.FFServerConfig;
import net.z2six.featheredfriend.network.FFPayloads;
import org.slf4j.Logger;

/**
 * GAME bus hook that re-syncs server settings to clients when server configs change.
 *
 * Why this exists:
 * - NeoForge server config values can hot-reload from `.toml`.
 * - Clients only learn settings via payloads, so we broadcast when we detect a change.
 * - We also broadcast when settings are changed in-game (GUI) via {@link FFServerConfig#setChatDisabled(boolean)}.
 */
public final class FFConfigSyncEvents {

    private static final Logger LOG = LogUtils.getLogger();

    private static volatile boolean REGISTERED = false;

    private static int tickCounter = 0;
    private static boolean lastChatDisabled = true;
    private static boolean lastInitialized = false;

    private FFConfigSyncEvents() {
        // no-op
    }

    public static void registerGameBus() {
        if (REGISTERED) {
            LOG.debug("[FFConfigSyncEvents] registerGameBus(): already registered, skipping");
            return;
        }
        try {
            NeoForge.EVENT_BUS.addListener(FFConfigSyncEvents::onServerTick);
            REGISTERED = true;
            LOG.debug("[FFConfigSyncEvents] Registered on NeoForge EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[FFConfigSyncEvents] registerGameBus failed safely", t);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        try {
            MinecraftServer server = event.getServer();
            if (server == null) return;

            tickCounter++;
            if ((tickCounter % 20) != 0) {
                return; // check once per second
            }

            boolean chatDisabledNow = FFServerConfig.isChatDisabled();

            boolean changed = false;
            if (!lastInitialized) {
                lastInitialized = true;
                lastChatDisabled = chatDisabledNow;
            } else if (lastChatDisabled != chatDisabledNow) {
                lastChatDisabled = chatDisabledNow;
                changed = true;
            }

            boolean requested = FFServerConfig.consumeChatSettingDirty();

            if (!changed && !requested) {
                return;
            }

            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                return;
            }

            FFPayloads.broadcastSettings(overworld);

            if (LOG.isDebugEnabled()) {
                LOG.debug("[FFConfigSyncEvents] Broadcast settings due to {} (chatDisabled={})",
                        changed ? "config-change" : "request", chatDisabledNow);
            }
        } catch (Throwable t) {
            LOG.error("[FFConfigSyncEvents] onServerTick failed safely", t);
        }
    }
}

