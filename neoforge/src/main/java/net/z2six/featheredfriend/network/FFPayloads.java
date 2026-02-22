// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/network/FFPayloads.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.config.FFServerConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

/**
 * Settings sync payloads for FeatheredFriend.
 *
 * CRITICAL:
 * - Exactly ONE payload type per ResourceLocation ID.
 * - This file is the single source of truth for settings networking.
 *
 * What we sync (server-owned, must be consistent for all clients):
 * - chatDisabled (global)
 * - enableSuspiciousFeather (global feature toggle)
 * - enableSuspiciousChest (global feature toggle)
 * - enableRavenArmor (global feature toggle)
 * - enableMailbox (global feature toggle)
 * - maxRavenChestsPerPlayer (global)
 * - ravenLogRetentionMinutes (global)
 * - ravenLogMaxBytesPerPlayer (global)
 * - enderpackDepositCooldownSeconds (global)
 * - scrollDeliveryCooldownSeconds (global)
 * - courierTimeoutRetrySeconds (global)
 * - canEditChat (per-player "can edit server settings in UI" check, computed server-side)
 *
 * Client-only preferences are handled by FFClientConfig (not server-owned).
 */
public final class FFPayloads {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Bump if you change payload shapes. Must match client + server.
     */
    private static final String PROTOCOL_VERSION = "2";

    private FFPayloads() {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Registration hook
    // ---------------------------------------------------------------------

    public static void register(IEventBus modBus) {
        try {
            modBus.addListener(FFPayloads::onRegisterPayloadHandlers);
            LOG.debug("[FFPayloads] Hooked RegisterPayloadHandlersEvent listener");
        } catch (Throwable t) {
            LOG.error("[FFPayloads] register() failed safely", t);
        }
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        try {
            PayloadRegistrar registrar = event.registrar(Constants.MOD_ID).versioned(PROTOCOL_VERSION);

            registrar.playToServer(
                    RequestServerSettingsPayload.TYPE,
                    RequestServerSettingsPayload.STREAM_CODEC,
                    FFPayloads::handleRequestServerSettings
            );

            registrar.playToClient(
                    ServerSettingsPayload.TYPE,
                    ServerSettingsPayload.STREAM_CODEC,
                    FFPayloads::handleServerSettingsSync
            );

            registrar.playToServer(
                    SetChatDisabledPayload.TYPE,
                    SetChatDisabledPayload.STREAM_CODEC,
                    FFPayloads::handleSetChatDisabled
            );

            registrar.playToServer(
                    SetEnableSuspiciousFeatherPayload.TYPE,
                    SetEnableSuspiciousFeatherPayload.STREAM_CODEC,
                    FFPayloads::handleSetEnableSuspiciousFeather
            );

            registrar.playToServer(
                    SetEnableSuspiciousChestPayload.TYPE,
                    SetEnableSuspiciousChestPayload.STREAM_CODEC,
                    FFPayloads::handleSetEnableSuspiciousChest
            );

            registrar.playToServer(
                    SetEnableRavenArmorPayload.TYPE,
                    SetEnableRavenArmorPayload.STREAM_CODEC,
                    FFPayloads::handleSetEnableRavenArmor
            );

            registrar.playToServer(
                    SetEnableMailboxPayload.TYPE,
                    SetEnableMailboxPayload.STREAM_CODEC,
                    FFPayloads::handleSetEnableMailbox
            );

            registrar.playToServer(
                    SetMaxRavenChestsPerPlayerPayload.TYPE,
                    SetMaxRavenChestsPerPlayerPayload.STREAM_CODEC,
                    FFPayloads::handleSetMaxRavenChestsPerPlayer
            );

            registrar.playToServer(
                    SetRavenLogRetentionMinutesPayload.TYPE,
                    SetRavenLogRetentionMinutesPayload.STREAM_CODEC,
                    FFPayloads::handleSetRavenLogRetentionMinutes
            );

            registrar.playToServer(
                    SetRavenLogMaxBytesPerPlayerPayload.TYPE,
                    SetRavenLogMaxBytesPerPlayerPayload.STREAM_CODEC,
                    FFPayloads::handleSetRavenLogMaxBytesPerPlayer
            );

            registrar.playToServer(
                    SetEnderpackDepositCooldownSecondsPayload.TYPE,
                    SetEnderpackDepositCooldownSecondsPayload.STREAM_CODEC,
                    FFPayloads::handleSetEnderpackDepositCooldownSeconds
            );

            registrar.playToServer(
                    SetScrollDeliveryCooldownSecondsPayload.TYPE,
                    SetScrollDeliveryCooldownSecondsPayload.STREAM_CODEC,
                    FFPayloads::handleSetScrollDeliveryCooldownSeconds
            );

            registrar.playToServer(
                    SetCourierTimeoutRetrySecondsPayload.TYPE,
                    SetCourierTimeoutRetrySecondsPayload.STREAM_CODEC,
                    FFPayloads::handleSetCourierTimeoutRetrySeconds
            );

            LOG.debug("[FFPayloads] Registered settings payloads OK (protocol={})", PROTOCOL_VERSION);
        } catch (Throwable t) {
            LOG.error("[FFPayloads] onRegisterPayloadHandlers failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Client-side cache
    // ---------------------------------------------------------------------

    public static final class ClientState {
        private static volatile boolean hasSynced = false;
        private static volatile boolean chatDisabled = false;
        private static volatile boolean suspiciousFeatherEnabled = true;
        private static volatile boolean suspiciousChestEnabled = true;
        private static volatile boolean ravenArmorEnabled = true;
        private static volatile boolean mailboxEnabled = true;
        private static volatile int maxRavenChestsPerPlayer = 0;
        private static volatile int ravenLogRetentionMinutes = 0;
        private static volatile int ravenLogMaxBytesPerPlayer = 0;
        private static volatile int enderpackDepositCooldownSeconds = 0;
        private static volatile int scrollDeliveryCooldownSeconds = 0;
        private static volatile int courierTimeoutRetrySeconds = 0;
        private static volatile boolean canEditChat = false;

        private ClientState() {
            // no-op
        }

        public static boolean hasSynced() {
            return hasSynced;
        }

        public static boolean isChatDisabled() {
            return chatDisabled;
        }

        public static boolean isSuspiciousFeatherEnabled() {
            return suspiciousFeatherEnabled;
        }

        public static boolean isSuspiciousChestEnabled() {
            return suspiciousChestEnabled;
        }

        public static boolean isRavenArmorEnabled() {
            return ravenArmorEnabled;
        }

        public static boolean isMailboxEnabled() {
            return mailboxEnabled;
        }

        public static boolean canEditChat() {
            return canEditChat;
        }

        public static int maxRavenChestsPerPlayer() {
            return maxRavenChestsPerPlayer;
        }

        public static int ravenLogRetentionMinutes() {
            return ravenLogRetentionMinutes;
        }

        public static int ravenLogMaxBytesPerPlayer() {
            return ravenLogMaxBytesPerPlayer;
        }

        public static int enderpackDepositCooldownSeconds() {
            return enderpackDepositCooldownSeconds;
        }

        public static int scrollDeliveryCooldownSeconds() {
            return scrollDeliveryCooldownSeconds;
        }

        public static int courierTimeoutRetrySeconds() {
            return courierTimeoutRetrySeconds;
        }

        private static void applyFromServer(boolean newChatDisabled,
                                            boolean newSuspiciousFeatherEnabled,
                                            boolean newSuspiciousChestEnabled,
                                            boolean newRavenArmorEnabled,
                                            boolean newMailboxEnabled,
                                            int newMaxRavenChestsPerPlayer,
                                            int newRavenLogRetentionMinutes,
                                            int newRavenLogMaxBytesPerPlayer,
                                            int newEnderpackDepositCooldownSeconds,
                                            int newScrollDeliveryCooldownSeconds,
                                            int newCourierTimeoutRetrySeconds,
                                            boolean newCanEditChat) {
            chatDisabled = newChatDisabled;
            suspiciousFeatherEnabled = newSuspiciousFeatherEnabled;
            suspiciousChestEnabled = newSuspiciousChestEnabled;
            ravenArmorEnabled = newRavenArmorEnabled;
            mailboxEnabled = newMailboxEnabled;
            maxRavenChestsPerPlayer = Math.max(0, newMaxRavenChestsPerPlayer);
            ravenLogRetentionMinutes = Math.max(0, newRavenLogRetentionMinutes);
            ravenLogMaxBytesPerPlayer = Math.max(0, newRavenLogMaxBytesPerPlayer);
            enderpackDepositCooldownSeconds = Math.max(0, newEnderpackDepositCooldownSeconds);
            scrollDeliveryCooldownSeconds = Math.max(0, newScrollDeliveryCooldownSeconds);
            courierTimeoutRetrySeconds = Math.max(0, newCourierTimeoutRetrySeconds);
            canEditChat = newCanEditChat;
            hasSynced = true;

            LOG.debug("[FFPayloads.ClientState] Applied server settings: chatDisabled={} enableSuspiciousFeather={} enableSuspiciousChest={} enableRavenArmor={} enableMailbox={} maxRavenChestsPerPlayer={} ravenLogRetentionMinutes={} ravenLogMaxBytesPerPlayer={} enderpackDepositCooldownSeconds={} scrollDeliveryCooldownSeconds={} courierTimeoutRetrySeconds={} canEditChat={}",
                    newChatDisabled, newSuspiciousFeatherEnabled, newSuspiciousChestEnabled, newRavenArmorEnabled, newMailboxEnabled, maxRavenChestsPerPlayer, ravenLogRetentionMinutes, ravenLogMaxBytesPerPlayer, enderpackDepositCooldownSeconds, scrollDeliveryCooldownSeconds, courierTimeoutRetrySeconds, newCanEditChat);
        }

        public static void clear() {
            hasSynced = false;
            chatDisabled = false;
            suspiciousFeatherEnabled = true;
            suspiciousChestEnabled = true;
            ravenArmorEnabled = true;
            mailboxEnabled = true;
            maxRavenChestsPerPlayer = 0;
            ravenLogRetentionMinutes = 0;
            ravenLogMaxBytesPerPlayer = 0;
            enderpackDepositCooldownSeconds = 0;
            scrollDeliveryCooldownSeconds = 0;
            courierTimeoutRetrySeconds = 0;
            canEditChat = false;
            LOG.debug("[FFPayloads.ClientState] Cleared client cache");
        }
    }

    // ---------------------------------------------------------------------
    // Payload definitions (ONLY ONCE PER ID!)
    // ---------------------------------------------------------------------

    /**
     * Client -> Server: request current server settings.
     */
    public record RequestServerSettingsPayload() implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_server_settings_v1");

        public static final Type<RequestServerSettingsPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestServerSettingsPayload> STREAM_CODEC =
                StreamCodec.unit(new RequestServerSettingsPayload());

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server -> Client: settings snapshot.
     */
    public record ServerSettingsPayload(boolean chatDisabled,
                                        boolean enableSuspiciousFeather,
                                        boolean enableSuspiciousChest,
                                        boolean enableRavenArmor,
                                        boolean enableMailbox,
                                        int maxRavenChestsPerPlayer,
                                        int ravenLogRetentionMinutes,
                                        int ravenLogMaxBytesPerPlayer,
                                        int enderpackDepositCooldownSeconds,
                                        int scrollDeliveryCooldownSeconds,
                                        int courierTimeoutRetrySeconds,
                                        boolean canEditChat)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "server_settings_v1");

        public static final Type<ServerSettingsPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerSettingsPayload> STREAM_CODEC =
                StreamCodec.of(ServerSettingsPayload::encode, ServerSettingsPayload::decode);

        private static void encode(RegistryFriendlyByteBuf buf, ServerSettingsPayload payload) {
            buf.writeBoolean(payload.chatDisabled());
            buf.writeBoolean(payload.enableSuspiciousFeather());
            buf.writeBoolean(payload.enableSuspiciousChest());
            buf.writeBoolean(payload.enableRavenArmor());
            buf.writeBoolean(payload.enableMailbox());
            buf.writeVarInt(payload.maxRavenChestsPerPlayer());
            buf.writeVarInt(payload.ravenLogRetentionMinutes());
            buf.writeVarInt(payload.ravenLogMaxBytesPerPlayer());
            buf.writeVarInt(payload.enderpackDepositCooldownSeconds());
            buf.writeVarInt(payload.scrollDeliveryCooldownSeconds());
            buf.writeVarInt(payload.courierTimeoutRetrySeconds());
            buf.writeBoolean(payload.canEditChat());
        }

        private static ServerSettingsPayload decode(RegistryFriendlyByteBuf buf) {
            return new ServerSettingsPayload(
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean()
            );
        }

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set chat disabled flag. Requires permission on server.
     */
    public record SetChatDisabledPayload(boolean chatDisabled)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_chat_disabled_v1");

        public static final Type<SetChatDisabledPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetChatDisabledPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, SetChatDisabledPayload::chatDisabled,
                        SetChatDisabledPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set Suspicious Feather feature toggle. Requires permission on server.
     */
    public record SetEnableSuspiciousFeatherPayload(boolean value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_enable_suspicious_feather_v1");

        public static final Type<SetEnableSuspiciousFeatherPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetEnableSuspiciousFeatherPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, SetEnableSuspiciousFeatherPayload::value,
                        SetEnableSuspiciousFeatherPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set Suspicious Chest feature toggle. Requires permission on server.
     */
    public record SetEnableSuspiciousChestPayload(boolean value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_enable_suspicious_chest_v1");

        public static final Type<SetEnableSuspiciousChestPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetEnableSuspiciousChestPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, SetEnableSuspiciousChestPayload::value,
                        SetEnableSuspiciousChestPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set Raven armor feature toggle. Requires permission on server.
     */
    public record SetEnableRavenArmorPayload(boolean value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_enable_raven_armor_v1");

        public static final Type<SetEnableRavenArmorPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetEnableRavenArmorPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, SetEnableRavenArmorPayload::value,
                        SetEnableRavenArmorPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set Mailbox feature toggle. Requires permission on server.
     */
    public record SetEnableMailboxPayload(boolean value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_enable_mailbox_v1");

        public static final Type<SetEnableMailboxPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetEnableMailboxPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, SetEnableMailboxPayload::value,
                        SetEnableMailboxPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set raven chest cap. Requires permission on server.
     */
    public record SetMaxRavenChestsPerPlayerPayload(int value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_max_raven_chests_per_player_v1");

        public static final Type<SetMaxRavenChestsPerPlayerPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetMaxRavenChestsPerPlayerPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SetMaxRavenChestsPerPlayerPayload::value,
                        SetMaxRavenChestsPerPlayerPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set Raven Log retention (minutes). Requires permission.
     */
    public record SetRavenLogRetentionMinutesPayload(int value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_raven_log_retention_minutes_v1");

        public static final Type<SetRavenLogRetentionMinutesPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetRavenLogRetentionMinutesPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SetRavenLogRetentionMinutesPayload::value,
                        SetRavenLogRetentionMinutesPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set Raven Log max bytes per player. Requires permission.
     */
    public record SetRavenLogMaxBytesPerPlayerPayload(int value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_raven_log_max_bytes_per_player_v1");

        public static final Type<SetRavenLogMaxBytesPerPlayerPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetRavenLogMaxBytesPerPlayerPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SetRavenLogMaxBytesPerPlayerPayload::value,
                        SetRavenLogMaxBytesPerPlayerPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set Enderpack deposit cooldown (seconds). Requires permission.
     */
    public record SetEnderpackDepositCooldownSecondsPayload(int value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_enderpack_deposit_cooldown_seconds_v1");

        public static final Type<SetEnderpackDepositCooldownSecondsPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetEnderpackDepositCooldownSecondsPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SetEnderpackDepositCooldownSecondsPayload::value,
                        SetEnderpackDepositCooldownSecondsPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set scroll delivery cooldown (seconds). Requires permission.
     */
    public record SetScrollDeliveryCooldownSecondsPayload(int value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_scroll_delivery_cooldown_seconds_v1");

        public static final Type<SetScrollDeliveryCooldownSecondsPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetScrollDeliveryCooldownSecondsPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SetScrollDeliveryCooldownSecondsPayload::value,
                        SetScrollDeliveryCooldownSecondsPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> Server: set courier timeout retry interval (seconds). Requires permission.
     */
    public record SetCourierTimeoutRetrySecondsPayload(int value)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_courier_timeout_retry_seconds_v1");

        public static final Type<SetCourierTimeoutRetrySecondsPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, SetCourierTimeoutRetrySecondsPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SetCourierTimeoutRetrySecondsPayload::value,
                        SetCourierTimeoutRetrySecondsPayload::new
                );

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------------
    // Server-side send helpers
    // ---------------------------------------------------------------------

    public static void sendSettingsToPlayer(ServerLevel level, ServerPlayer player) {
        try {
            if (level == null || player == null) {
                LOG.warn("[FFPayloads] sendSettingsToPlayer: null args; skipping");
                return;
            }

            boolean chatDisabledValue = FFServerConfig.isChatDisabled();
            boolean enableSuspiciousFeatherValue = FFServerConfig.isSuspiciousFeatherEnabled();
            boolean enableSuspiciousChestValue = FFServerConfig.isSuspiciousChestEnabled();
            boolean enableRavenArmorValue = FFServerConfig.isRavenArmorEnabled();
            boolean enableMailboxValue = FFServerConfig.isMailboxEnabled();
            int maxRavenChestsPerPlayerValue = FFServerConfig.getRavenChestsPerPlayer();
            int ravenLogRetentionMinutesValue = FFServerConfig.getRavenLogRetentionMinutes();
            int ravenLogMaxBytesPerPlayerValue = FFServerConfig.getRavenLogMaxBytesPerPlayer();
            int enderpackDepositCooldownSecondsValue = FFServerConfig.getEnderpackDepositCooldownSeconds();
            int scrollDeliveryCooldownSecondsValue = FFServerConfig.getScrollDeliveryCooldownSeconds();
            int courierTimeoutRetrySecondsValue = FFServerConfig.getCourierTimeoutRetrySeconds();
            boolean canEditChatValue = canPlayerEditServerSettings(player);

            ServerSettingsPayload msg = new ServerSettingsPayload(
                    chatDisabledValue,
                    enableSuspiciousFeatherValue,
                    enableSuspiciousChestValue,
                    enableRavenArmorValue,
                    enableMailboxValue,
                    maxRavenChestsPerPlayerValue,
                    ravenLogRetentionMinutesValue,
                    ravenLogMaxBytesPerPlayerValue,
                    enderpackDepositCooldownSecondsValue,
                    scrollDeliveryCooldownSecondsValue,
                    courierTimeoutRetrySecondsValue,
                    canEditChatValue
            );
            PacketDistributor.sendToPlayer(player, msg);

            LOG.debug("[FFPayloads] Sent settings to {}: chatDisabled={} enableSuspiciousFeather={} enableSuspiciousChest={} enableRavenArmor={} enableMailbox={} maxRavenChestsPerPlayer={} ravenLogRetentionMinutes={} ravenLogMaxBytesPerPlayer={} enderpackDepositCooldownSeconds={} scrollDeliveryCooldownSeconds={} courierTimeoutRetrySeconds={} canEditChat={}",
                    player.getGameProfile().getName(),
                    chatDisabledValue,
                    enableSuspiciousFeatherValue,
                    enableSuspiciousChestValue,
                    enableRavenArmorValue,
                    enableMailboxValue,
                    maxRavenChestsPerPlayerValue,
                    ravenLogRetentionMinutesValue,
                    ravenLogMaxBytesPerPlayerValue,
                    enderpackDepositCooldownSecondsValue,
                    scrollDeliveryCooldownSecondsValue,
                    courierTimeoutRetrySecondsValue,
                    canEditChatValue);

        } catch (Throwable t) {
            LOG.error("[FFPayloads] sendSettingsToPlayer failed safely", t);
        }
    }

    public static void broadcastSettings(ServerLevel level) {
        try {
            if (level == null || level.getServer() == null) {
                LOG.warn("[FFPayloads] broadcastSettings: null level/server; skipping");
                return;
            }

            List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
            for (ServerPlayer sp : players) {
                try {
                    sendSettingsToPlayer(level, sp);
                } catch (Throwable t) {
                    LOG.warn("[FFPayloads] broadcastSettings: failed sending to {}: {}",
                            sp.getGameProfile().getName(), t.toString());
                }
            }
        } catch (Throwable t) {
            LOG.error("[FFPayloads] broadcastSettings failed safely", t);
        }
    }

    private static boolean canPlayerEditServerSettings(@Nullable ServerPlayer player) {
        try {
            if (player == null) {
                return false;
            }
            if (!FFServerConfig.isServerSettingsScreenEditingEnabled()) {
                return false;
            }
            return player.hasPermissions(4);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------

    private static void handleRequestServerSettings(RequestServerSettingsPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] RequestServerSettings from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] RequestServerSettings: serverLevel null; ignoring");
                        return;
                    }

                    sendSettingsToPlayer(level, sp);

                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleRequestServerSettings work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleRequestServerSettings failed safely", t);
        }
    }

    private static void handleServerSettingsSync(ServerSettingsPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    ClientState.applyFromServer(
                            payload.chatDisabled(),
                            payload.enableSuspiciousFeather(),
                            payload.enableSuspiciousChest(),
                            payload.enableRavenArmor(),
                            payload.enableMailbox(),
                            payload.maxRavenChestsPerPlayer(),
                            payload.ravenLogRetentionMinutes(),
                            payload.ravenLogMaxBytesPerPlayer(),
                            payload.enderpackDepositCooldownSeconds(),
                            payload.scrollDeliveryCooldownSeconds(),
                            payload.courierTimeoutRetrySeconds(),
                            payload.canEditChat()
                    );
                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleServerSettingsSync work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleServerSettingsSync failed safely", t);
        }
    }

    private static void handleSetChatDisabled(SetChatDisabledPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetChatDisabled from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetChatDisabled: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);

                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetChatDisabled without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    FFServerConfig.setChatDisabled(payload.chatDisabled());

                    LOG.debug("[FFPayloads] {} set chatDisabled -> {}",
                            sp.getGameProfile().getName(), payload.chatDisabled());

                    broadcastSettings(level);

                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetChatDisabled work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetChatDisabled failed safely", t);
        }
    }

    private static void handleSetEnableSuspiciousFeather(SetEnableSuspiciousFeatherPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetEnableSuspiciousFeather from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetEnableSuspiciousFeather: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);

                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetEnableSuspiciousFeather without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    FFServerConfig.setSuspiciousFeatherEnabled(payload.value());

                    LOG.debug("[FFPayloads] {} set enableSuspiciousFeather -> {}",
                            sp.getGameProfile().getName(), payload.value());

                    broadcastSettings(level);

                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetEnableSuspiciousFeather work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetEnableSuspiciousFeather failed safely", t);
        }
    }

    private static void handleSetEnableSuspiciousChest(SetEnableSuspiciousChestPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetEnableSuspiciousChest from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetEnableSuspiciousChest: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);

                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetEnableSuspiciousChest without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    FFServerConfig.setSuspiciousChestEnabled(payload.value());

                    LOG.debug("[FFPayloads] {} set enableSuspiciousChest -> {}",
                            sp.getGameProfile().getName(), payload.value());

                    broadcastSettings(level);

                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetEnableSuspiciousChest work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetEnableSuspiciousChest failed safely", t);
        }
    }

    private static void handleSetEnableRavenArmor(SetEnableRavenArmorPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetEnableRavenArmor from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetEnableRavenArmor: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);

                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetEnableRavenArmor without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    FFServerConfig.setRavenArmorEnabled(payload.value());

                    LOG.debug("[FFPayloads] {} set enableRavenArmor -> {}",
                            sp.getGameProfile().getName(), payload.value());

                    broadcastSettings(level);

                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetEnableRavenArmor work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetEnableRavenArmor failed safely", t);
        }
    }

    private static void handleSetEnableMailbox(SetEnableMailboxPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetEnableMailbox from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetEnableMailbox: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);

                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetEnableMailbox without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    FFServerConfig.setMailboxEnabled(payload.value());

                    LOG.debug("[FFPayloads] {} set enableMailbox -> {}",
                            sp.getGameProfile().getName(), payload.value());

                    broadcastSettings(level);

                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetEnableMailbox work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetEnableMailbox failed safely", t);
        }
    }

    private static void handleSetMaxRavenChestsPerPlayer(SetMaxRavenChestsPerPlayerPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetMaxRavenChestsPerPlayer from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetMaxRavenChestsPerPlayer: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);

                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetMaxRavenChestsPerPlayer without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    int clamped = Math.max(0, Math.min(64, payload.value()));
                    FFServerConfig.setRavenChestsPerPlayer(clamped);

                    LOG.debug("[FFPayloads] {} set maxRavenChestsPerPlayer -> {}",
                            sp.getGameProfile().getName(), clamped);

                    broadcastSettings(level);
                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetMaxRavenChestsPerPlayer work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetMaxRavenChestsPerPlayer failed safely", t);
        }
    }

    private static void handleSetRavenLogRetentionMinutes(SetRavenLogRetentionMinutesPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetRavenLogRetentionMinutes from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetRavenLogRetentionMinutes: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);
                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetRavenLogRetentionMinutes without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    int clamped = Math.max(0, Math.min(60 * 24 * 90, payload.value()));
                    FFServerConfig.setRavenLogRetentionMinutes(clamped);

                    LOG.debug("[FFPayloads] {} set ravenLogRetentionMinutes -> {}",
                            sp.getGameProfile().getName(), clamped);

                    broadcastSettings(level);
                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetRavenLogRetentionMinutes work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetRavenLogRetentionMinutes failed safely", t);
        }
    }

    private static void handleSetRavenLogMaxBytesPerPlayer(SetRavenLogMaxBytesPerPlayerPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetRavenLogMaxBytesPerPlayer from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetRavenLogMaxBytesPerPlayer: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);
                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetRavenLogMaxBytesPerPlayer without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    int clamped = Math.max(0, Math.min(4 * 1024 * 1024, payload.value()));
                    FFServerConfig.setRavenLogMaxBytesPerPlayer(clamped);

                    LOG.debug("[FFPayloads] {} set ravenLogMaxBytesPerPlayer -> {}",
                            sp.getGameProfile().getName(), clamped);

                    broadcastSettings(level);
                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetRavenLogMaxBytesPerPlayer work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetRavenLogMaxBytesPerPlayer failed safely", t);
        }
    }

    private static void handleSetEnderpackDepositCooldownSeconds(SetEnderpackDepositCooldownSecondsPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetEnderpackDepositCooldownSeconds from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetEnderpackDepositCooldownSeconds: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);
                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetEnderpackDepositCooldownSeconds without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    int clamped = Math.max(0, Math.min(86_400, payload.value()));
                    FFServerConfig.setEnderpackDepositCooldownSeconds(clamped);

                    LOG.debug("[FFPayloads] {} set enderpackDepositCooldownSeconds -> {}",
                            sp.getGameProfile().getName(), clamped);

                    broadcastSettings(level);
                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetEnderpackDepositCooldownSeconds work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetEnderpackDepositCooldownSeconds failed safely", t);
        }
    }

    private static void handleSetScrollDeliveryCooldownSeconds(SetScrollDeliveryCooldownSecondsPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetScrollDeliveryCooldownSeconds from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetScrollDeliveryCooldownSeconds: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);
                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetScrollDeliveryCooldownSeconds without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    int clamped = Math.max(0, Math.min(86_400, payload.value()));
                    FFServerConfig.setScrollDeliveryCooldownSeconds(clamped);

                    LOG.debug("[FFPayloads] {} set scrollDeliveryCooldownSeconds -> {}",
                            sp.getGameProfile().getName(), clamped);

                    broadcastSettings(level);
                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetScrollDeliveryCooldownSeconds work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetScrollDeliveryCooldownSeconds failed safely", t);
        }
    }

    private static void handleSetCourierTimeoutRetrySeconds(SetCourierTimeoutRetrySecondsPayload payload, IPayloadContext context) {
        try {
            context.enqueueWork(() -> {
                try {
                    if (!(context.player() instanceof ServerPlayer sp)) {
                        LOG.warn("[FFPayloads] SetCourierTimeoutRetrySeconds from non-ServerPlayer; ignoring");
                        return;
                    }

                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetCourierTimeoutRetrySeconds: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed = canPlayerEditServerSettings(sp);
                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetCourierTimeoutRetrySeconds without permission/settings-screen access; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    int clamped = Math.max(0, Math.min(86_400, payload.value()));
                    FFServerConfig.setCourierTimeoutRetrySeconds(clamped);

                    LOG.debug("[FFPayloads] {} set courierTimeoutRetrySeconds -> {}",
                            sp.getGameProfile().getName(), clamped);

                    broadcastSettings(level);
                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetCourierTimeoutRetrySeconds work failed safely", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[FFPayloads] handleSetCourierTimeoutRetrySeconds failed safely", t);
        }
    }
}
