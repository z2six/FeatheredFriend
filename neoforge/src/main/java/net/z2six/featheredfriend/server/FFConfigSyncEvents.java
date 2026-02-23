package net.z2six.featheredfriend.server;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.z2six.featheredfriend.config.FFServerConfig;
import net.z2six.featheredfriend.network.FFPayloads;
import net.z2six.featheredfriend.world.RavenLogService;
import net.z2six.featheredfriend.log.FFLogThrottle;
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
    private static boolean lastEnableSuspiciousFeather = true;
    private static boolean lastEnableSuspiciousChest = true;
    private static boolean lastEnableRavenArmor = true;
    private static boolean lastEnableMailbox = true;
    private static int lastWildRavensPerPlayer = 0;
    private static int lastRavenChestsPerPlayer = 0;
    private static int lastRavenLogRetentionMinutes = 0;
    private static int lastRavenLogMaxBytesPerPlayer = 0;
    private static int lastEnderpackDepositCooldownSeconds = 0;
    private static int lastScrollDeliveryCooldownSeconds = 0;
    private static int lastCourierTimeoutRetrySeconds = 0;
    private static int lastRavenLinkDurationSeconds = 0;
    private static boolean lastAllowServerSettingsScreenEditing = true;
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
            boolean enableSuspiciousFeatherNow = FFServerConfig.isSuspiciousFeatherEnabled();
            boolean enableSuspiciousChestNow = FFServerConfig.isSuspiciousChestEnabled();
            boolean enableRavenArmorNow = FFServerConfig.isRavenArmorEnabled();
            boolean enableMailboxNow = FFServerConfig.isMailboxEnabled();
            int wildRavensPerPlayerNow = FFServerConfig.getWildRavensPerPlayer();
            int ravenChestsPerPlayerNow = FFServerConfig.getRavenChestsPerPlayer();
            int ravenLogRetentionMinutesNow = FFServerConfig.getRavenLogRetentionMinutes();
            int ravenLogMaxBytesPerPlayerNow = FFServerConfig.getRavenLogMaxBytesPerPlayer();
            int enderpackDepositCooldownSecondsNow = FFServerConfig.getEnderpackDepositCooldownSeconds();
            int scrollDeliveryCooldownSecondsNow = FFServerConfig.getScrollDeliveryCooldownSeconds();
            int courierTimeoutRetrySecondsNow = FFServerConfig.getCourierTimeoutRetrySeconds();
            int ravenLinkDurationSecondsNow = FFServerConfig.getRavenLinkDurationSeconds();
            boolean allowServerSettingsScreenEditingNow = FFServerConfig.isServerSettingsScreenEditingEnabled();

            boolean changed = false;
            if (!lastInitialized) {
                lastInitialized = true;
                lastChatDisabled = chatDisabledNow;
                lastEnableSuspiciousFeather = enableSuspiciousFeatherNow;
                lastEnableSuspiciousChest = enableSuspiciousChestNow;
                lastEnableRavenArmor = enableRavenArmorNow;
                lastEnableMailbox = enableMailboxNow;
                lastWildRavensPerPlayer = wildRavensPerPlayerNow;
                lastRavenChestsPerPlayer = ravenChestsPerPlayerNow;
                lastRavenLogRetentionMinutes = ravenLogRetentionMinutesNow;
                lastRavenLogMaxBytesPerPlayer = ravenLogMaxBytesPerPlayerNow;
                lastEnderpackDepositCooldownSeconds = enderpackDepositCooldownSecondsNow;
                lastScrollDeliveryCooldownSeconds = scrollDeliveryCooldownSecondsNow;
                lastCourierTimeoutRetrySeconds = courierTimeoutRetrySecondsNow;
                lastRavenLinkDurationSeconds = ravenLinkDurationSecondsNow;
                lastAllowServerSettingsScreenEditing = allowServerSettingsScreenEditingNow;
            } else {
                if (lastChatDisabled != chatDisabledNow) {
                    lastChatDisabled = chatDisabledNow;
                    changed = true;
                }
                if (lastEnableSuspiciousFeather != enableSuspiciousFeatherNow) {
                    lastEnableSuspiciousFeather = enableSuspiciousFeatherNow;
                    changed = true;
                }
                if (lastEnableSuspiciousChest != enableSuspiciousChestNow) {
                    lastEnableSuspiciousChest = enableSuspiciousChestNow;
                    changed = true;
                }
                if (lastEnableRavenArmor != enableRavenArmorNow) {
                    lastEnableRavenArmor = enableRavenArmorNow;
                    changed = true;
                }
                if (lastEnableMailbox != enableMailboxNow) {
                    lastEnableMailbox = enableMailboxNow;
                    changed = true;
                }
                if (lastWildRavensPerPlayer != wildRavensPerPlayerNow) {
                    lastWildRavensPerPlayer = wildRavensPerPlayerNow;
                    changed = true;
                }
                if (lastRavenChestsPerPlayer != ravenChestsPerPlayerNow) {
                    lastRavenChestsPerPlayer = ravenChestsPerPlayerNow;
                    changed = true;
                }
                if (lastRavenLogRetentionMinutes != ravenLogRetentionMinutesNow) {
                    lastRavenLogRetentionMinutes = ravenLogRetentionMinutesNow;
                    changed = true;
                }
                if (lastRavenLogMaxBytesPerPlayer != ravenLogMaxBytesPerPlayerNow) {
                    lastRavenLogMaxBytesPerPlayer = ravenLogMaxBytesPerPlayerNow;
                    changed = true;
                }
                if (lastEnderpackDepositCooldownSeconds != enderpackDepositCooldownSecondsNow) {
                    lastEnderpackDepositCooldownSeconds = enderpackDepositCooldownSecondsNow;
                    changed = true;
                }
                if (lastScrollDeliveryCooldownSeconds != scrollDeliveryCooldownSecondsNow) {
                    lastScrollDeliveryCooldownSeconds = scrollDeliveryCooldownSecondsNow;
                    changed = true;
                }
                if (lastCourierTimeoutRetrySeconds != courierTimeoutRetrySecondsNow) {
                    lastCourierTimeoutRetrySeconds = courierTimeoutRetrySecondsNow;
                    changed = true;
                }
                if (lastRavenLinkDurationSeconds != ravenLinkDurationSecondsNow) {
                    lastRavenLinkDurationSeconds = ravenLinkDurationSecondsNow;
                    changed = true;
                }
                if (lastAllowServerSettingsScreenEditing != allowServerSettingsScreenEditingNow) {
                    lastAllowServerSettingsScreenEditing = allowServerSettingsScreenEditingNow;
                    changed = true;
                }
            }

            boolean requested = FFServerConfig.consumeSettingsDirty();

            ServerLevel overworld = server.overworld();
            if (overworld != null) {
                try {
                    RavenLogService.pruneAll(overworld);
                } catch (Throwable t) {
                    if (FFLogThrottle.shouldLog("FFConfigSyncEvents.RavenLog.pruneAll", 30_000L)) {
                        LOG.warn("[FFConfigSyncEvents] RavenLog prune failed safely", t);
                    } else {
                        LOG.debug("[FFConfigSyncEvents] RavenLog prune failed safely: {}", t.toString());
                    }
                }
            }

            if (!changed && !requested) {
                return;
            }

            if (overworld == null) {
                return;
            }

            FFPayloads.broadcastSettings(overworld);

            LOG.info("[FFConfigSyncEvents] Broadcast server settings due to {} (chatDisabled={} suspiciousFeather={} suspiciousChest={} ravenArmor={} mailbox={} wildRavensPerPlayer={} ravenChestsPerPlayer={} ravenLinkDurationSeconds={})",
                    changed ? "config-change" : "request",
                    chatDisabledNow,
                    enableSuspiciousFeatherNow,
                    enableSuspiciousChestNow,
                    enableRavenArmorNow,
                    enableMailboxNow,
                    wildRavensPerPlayerNow,
                    ravenChestsPerPlayerNow,
                    ravenLinkDurationSecondsNow);

            if (LOG.isDebugEnabled()) {
                LOG.debug("[FFConfigSyncEvents] Broadcast settings full snapshot (ravenLogRetentionMinutes={} ravenLogMaxBytesPerPlayer={} enderpackDepositCooldownSeconds={} scrollDeliveryCooldownSeconds={} courierTimeoutRetrySeconds={} allowServerSettingsScreenEditing={})",
                        ravenLogRetentionMinutesNow,
                        ravenLogMaxBytesPerPlayerNow,
                        enderpackDepositCooldownSecondsNow,
                        scrollDeliveryCooldownSecondsNow,
                        courierTimeoutRetrySecondsNow,
                        allowServerSettingsScreenEditingNow);
            }
        } catch (Throwable t) {
            LOG.error("[FFConfigSyncEvents] onServerTick failed safely", t);
        }
    }
}
