package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.config.FFServerConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Forge 1.20.1 settings sync networking (server-authoritative).
 *
 * Kept separate from {@link FFNetwork} to avoid message id collisions.
 */
public final class FFPayloads {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String PROTOCOL_VERSION = "1";
    private static final ResourceLocation CHANNEL_ID = new ResourceLocation(Constants.MOD_ID, "settings");
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID,
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    private static volatile boolean REGISTERED = false;

    private FFPayloads() {
    }

    /**
     * Kept for call-site parity with the NeoForge branch. The bus argument is unused on Forge.
     */
    public static void register(@SuppressWarnings("unused") IEventBus modBus) {
        registerSimpleMessages();
    }

    public static void registerSimpleMessages() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        try {
            int id = 0;

            CHANNEL.messageBuilder(RequestServerSettingsPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RequestServerSettingsPacket::encode)
                    .decoder(RequestServerSettingsPacket::decode)
                    .consumerMainThread(FFPayloads::handleRequestServerSettingsOnServer)
                    .add();

            CHANNEL.messageBuilder(ServerSettingsPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(ServerSettingsPayload::encode)
                    .decoder(ServerSettingsPayload::decode)
                    .consumerMainThread(FFPayloads::handleServerSettingsOnClient)
                    .add();

            CHANNEL.messageBuilder(SetBooleanSettingPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(SetBooleanSettingPacket::encode)
                    .decoder(SetBooleanSettingPacket::decode)
                    .consumerMainThread(FFPayloads::handleSetBooleanSettingOnServer)
                    .add();

            CHANNEL.messageBuilder(SetIntSettingPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(SetIntSettingPacket::encode)
                    .decoder(SetIntSettingPacket::decode)
                    .consumerMainThread(FFPayloads::handleSetIntSettingOnServer)
                    .add();

            LOG.info("[FFPayloads] Registered {} settings messages on SimpleChannel {}", id, CHANNEL_ID);
        } catch (Throwable t) {
            LOG.error("[FFPayloads] registerSimpleMessages failed", t);
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
        private static volatile int wildRavensPerPlayer = 0;
        private static volatile int ravenLinkDurationSeconds = 0;
        private static volatile int maxRavenChestsPerPlayer = 0;
        private static volatile int ravenLogRetentionMinutes = 0;
        private static volatile int ravenLogMaxBytesPerPlayer = 0;
        private static volatile int enderpackDepositCooldownSeconds = 0;
        private static volatile int scrollDeliveryCooldownSeconds = 0;
        private static volatile int courierTimeoutRetrySeconds = 0;
        private static volatile boolean canEditChat = false;

        private ClientState() {
        }

        public static void clear() {
            hasSynced = false;
            chatDisabled = false;
            suspiciousFeatherEnabled = true;
            suspiciousChestEnabled = true;
            ravenArmorEnabled = true;
            mailboxEnabled = true;
            wildRavensPerPlayer = 0;
            ravenLinkDurationSeconds = 0;
            maxRavenChestsPerPlayer = 0;
            ravenLogRetentionMinutes = 0;
            ravenLogMaxBytesPerPlayer = 0;
            enderpackDepositCooldownSeconds = 0;
            scrollDeliveryCooldownSeconds = 0;
            courierTimeoutRetrySeconds = 0;
            canEditChat = false;
        }

        public static boolean hasSynced() { return hasSynced; }
        public static boolean isChatDisabled() { return chatDisabled; }
        public static boolean isSuspiciousFeatherEnabled() { return suspiciousFeatherEnabled; }
        public static boolean isSuspiciousChestEnabled() { return suspiciousChestEnabled; }
        public static boolean isRavenArmorEnabled() { return ravenArmorEnabled; }
        public static boolean isMailboxEnabled() { return mailboxEnabled; }
        public static boolean canEditChat() { return canEditChat; }
        public static int wildRavensPerPlayer() { return wildRavensPerPlayer; }
        public static int ravenLinkDurationSeconds() { return ravenLinkDurationSeconds; }
        public static int maxRavenChestsPerPlayer() { return maxRavenChestsPerPlayer; }
        public static int ravenLogRetentionMinutes() { return ravenLogRetentionMinutes; }
        public static int ravenLogMaxBytesPerPlayer() { return ravenLogMaxBytesPerPlayer; }
        public static int enderpackDepositCooldownSeconds() { return enderpackDepositCooldownSeconds; }
        public static int scrollDeliveryCooldownSeconds() { return scrollDeliveryCooldownSeconds; }
        public static int courierTimeoutRetrySeconds() { return courierTimeoutRetrySeconds; }

        private static void applyFromServer(@NotNull ServerSettingsPayload payload) {
            hasSynced = true;
            chatDisabled = payload.chatDisabled();
            suspiciousFeatherEnabled = payload.suspiciousFeatherEnabled();
            suspiciousChestEnabled = payload.suspiciousChestEnabled();
            ravenArmorEnabled = payload.ravenArmorEnabled();
            mailboxEnabled = payload.mailboxEnabled();
            wildRavensPerPlayer = payload.wildRavensPerPlayer();
            ravenLinkDurationSeconds = payload.ravenLinkDurationSeconds();
            maxRavenChestsPerPlayer = payload.maxRavenChestsPerPlayer();
            ravenLogRetentionMinutes = payload.ravenLogRetentionMinutes();
            ravenLogMaxBytesPerPlayer = payload.ravenLogMaxBytesPerPlayer();
            enderpackDepositCooldownSeconds = payload.enderpackDepositCooldownSeconds();
            scrollDeliveryCooldownSeconds = payload.scrollDeliveryCooldownSeconds();
            courierTimeoutRetrySeconds = payload.courierTimeoutRetrySeconds();
            canEditChat = payload.canEditChat();
        }
    }

    // ---------------------------------------------------------------------
    // Public API (used by server/client helpers and platform abstraction)
    // ---------------------------------------------------------------------

    public static void sendRequestServerSettingsToServer() {
        try {
            CHANNEL.sendToServer(new RequestServerSettingsPacket());
        } catch (Throwable t) {
            LOG.debug("[FFPayloads] sendRequestServerSettingsToServer failed safely: {}", t.toString());
        }
    }

    public static void sendSetChatDisabled(boolean value) {
        sendSetBoolean(SettingKeyBool.CHAT_DISABLED, value);
    }

    public static void sendSetEnableSuspiciousFeather(boolean value) {
        sendSetBoolean(SettingKeyBool.ENABLE_SUSPICIOUS_FEATHER, value);
    }

    public static void sendSetEnableSuspiciousChest(boolean value) {
        sendSetBoolean(SettingKeyBool.ENABLE_SUSPICIOUS_CHEST, value);
    }

    public static void sendSetEnableRavenArmor(boolean value) {
        sendSetBoolean(SettingKeyBool.ENABLE_RAVEN_ARMOR, value);
    }

    public static void sendSetEnableMailbox(boolean value) {
        sendSetBoolean(SettingKeyBool.ENABLE_MAILBOX, value);
    }

    public static void sendSetWildRavensPerPlayer(int value) {
        sendSetInt(SettingKeyInt.WILD_RAVENS_PER_PLAYER, value);
    }

    public static void sendSetRavenLinkDurationSeconds(int value) {
        sendSetInt(SettingKeyInt.RAVEN_LINK_DURATION_SECONDS, value);
    }

    public static void sendSetMaxRavenChestsPerPlayer(int value) {
        sendSetInt(SettingKeyInt.MAX_RAVEN_CHESTS_PER_PLAYER, value);
    }

    public static void sendSetRavenLogRetentionMinutes(int value) {
        sendSetInt(SettingKeyInt.RAVEN_LOG_RETENTION_MINUTES, value);
    }

    public static void sendSetRavenLogMaxBytesPerPlayer(int value) {
        sendSetInt(SettingKeyInt.RAVEN_LOG_MAX_BYTES_PER_PLAYER, value);
    }

    public static void sendSetEnderpackDepositCooldownSeconds(int value) {
        sendSetInt(SettingKeyInt.ENDERPACK_DEPOSIT_COOLDOWN_SECONDS, value);
    }

    public static void sendSetScrollDeliveryCooldownSeconds(int value) {
        sendSetInt(SettingKeyInt.SCROLL_DELIVERY_COOLDOWN_SECONDS, value);
    }

    public static void sendSetCourierTimeoutRetrySeconds(int value) {
        sendSetInt(SettingKeyInt.COURIER_TIMEOUT_RETRY_SECONDS, value);
    }

    public static void sendSettingsToPlayer(@NotNull ServerLevel level, @NotNull ServerPlayer player) {
        try {
            if (level == null || player == null) {
                return;
            }

            boolean canEdit = false;
            try {
                canEdit = FFServerConfig.isServerSettingsScreenEditingEnabled() && player.hasPermissions(2);
            } catch (Throwable ignored) {
            }

            ServerSettingsPayload payload = new ServerSettingsPayload(
                    FFServerConfig.isChatDisabled(),
                    FFServerConfig.isSuspiciousFeatherEnabled(),
                    FFServerConfig.isSuspiciousChestEnabled(),
                    FFServerConfig.isRavenArmorEnabled(),
                    FFServerConfig.isMailboxEnabled(),
                    FFServerConfig.getWildRavensPerPlayer(),
                    FFServerConfig.getRavenLinkDurationSeconds(),
                    FFServerConfig.getRavenChestsPerPlayer(),
                    FFServerConfig.getRavenLogRetentionMinutes(),
                    FFServerConfig.getRavenLogMaxBytesPerPlayer(),
                    FFServerConfig.getEnderpackDepositCooldownSeconds(),
                    FFServerConfig.getScrollDeliveryCooldownSeconds(),
                    FFServerConfig.getCourierTimeoutRetrySeconds(),
                    canEdit
            );

            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
        } catch (Throwable t) {
            LOG.warn("[FFPayloads] sendSettingsToPlayer failed safely: {}", t.toString());
        }
    }

    public static void broadcastSettings(@NotNull ServerLevel level) {
        try {
            if (level == null || level.getServer() == null) {
                return;
            }
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                if (player == null) {
                    continue;
                }
                sendSettingsToPlayer(level, player);
            }
        } catch (Throwable t) {
            LOG.warn("[FFPayloads] broadcastSettings failed safely: {}", t.toString());
        }
    }

    private static void sendSetBoolean(byte key, boolean value) {
        try {
            CHANNEL.sendToServer(new SetBooleanSettingPacket(key, value));
        } catch (Throwable t) {
            LOG.debug("[FFPayloads] sendSetBoolean failed safely: {}", t.toString());
        }
    }

    private static void sendSetInt(byte key, int value) {
        try {
            CHANNEL.sendToServer(new SetIntSettingPacket(key, value));
        } catch (Throwable t) {
            LOG.debug("[FFPayloads] sendSetInt failed safely: {}", t.toString());
        }
    }

    // ---------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------

    private static void handleRequestServerSettingsOnServer(@NotNull RequestServerSettingsPacket msg,
                                                           @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer sender = ctx.getSender();
                if (sender == null) {
                    return;
                }
                sendSettingsToPlayer(sender.serverLevel(), sender);
            } catch (Throwable t) {
                LOG.warn("[FFPayloads] handleRequestServerSettingsOnServer failed safely: {}", t.toString());
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleSetBooleanSettingOnServer(@NotNull SetBooleanSettingPacket msg,
                                                       @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer sender = ctx.getSender();
                if (sender == null) {
                    return;
                }
                if (!FFServerConfig.isServerSettingsScreenEditingEnabled() || !sender.hasPermissions(2)) {
                    sendSettingsToPlayer(sender.serverLevel(), sender);
                    return;
                }

                switch (msg.key()) {
                    case SettingKeyBool.CHAT_DISABLED -> FFServerConfig.setChatDisabled(msg.value());
                    case SettingKeyBool.ENABLE_SUSPICIOUS_FEATHER -> FFServerConfig.setSuspiciousFeatherEnabled(msg.value());
                    case SettingKeyBool.ENABLE_SUSPICIOUS_CHEST -> FFServerConfig.setSuspiciousChestEnabled(msg.value());
                    case SettingKeyBool.ENABLE_RAVEN_ARMOR -> FFServerConfig.setRavenArmorEnabled(msg.value());
                    case SettingKeyBool.ENABLE_MAILBOX -> FFServerConfig.setMailboxEnabled(msg.value());
                    default -> {
                        // ignore
                    }
                }

                broadcastSettings(sender.serverLevel());
            } catch (Throwable t) {
                LOG.warn("[FFPayloads] handleSetBooleanSettingOnServer failed safely: {}", t.toString());
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleSetIntSettingOnServer(@NotNull SetIntSettingPacket msg,
                                                    @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer sender = ctx.getSender();
                if (sender == null) {
                    return;
                }
                if (!FFServerConfig.isServerSettingsScreenEditingEnabled() || !sender.hasPermissions(2)) {
                    sendSettingsToPlayer(sender.serverLevel(), sender);
                    return;
                }

                switch (msg.key()) {
                    case SettingKeyInt.WILD_RAVENS_PER_PLAYER -> FFServerConfig.setWildRavensPerPlayer(msg.value());
                    case SettingKeyInt.RAVEN_LINK_DURATION_SECONDS -> FFServerConfig.setRavenLinkDurationSeconds(msg.value());
                    case SettingKeyInt.MAX_RAVEN_CHESTS_PER_PLAYER -> FFServerConfig.setRavenChestsPerPlayer(msg.value());
                    case SettingKeyInt.RAVEN_LOG_RETENTION_MINUTES -> FFServerConfig.setRavenLogRetentionMinutes(msg.value());
                    case SettingKeyInt.RAVEN_LOG_MAX_BYTES_PER_PLAYER -> FFServerConfig.setRavenLogMaxBytesPerPlayer(msg.value());
                    case SettingKeyInt.ENDERPACK_DEPOSIT_COOLDOWN_SECONDS -> FFServerConfig.setEnderpackDepositCooldownSeconds(msg.value());
                    case SettingKeyInt.SCROLL_DELIVERY_COOLDOWN_SECONDS -> FFServerConfig.setScrollDeliveryCooldownSeconds(msg.value());
                    case SettingKeyInt.COURIER_TIMEOUT_RETRY_SECONDS -> FFServerConfig.setCourierTimeoutRetrySeconds(msg.value());
                    default -> {
                        // ignore
                    }
                }

                broadcastSettings(sender.serverLevel());
            } catch (Throwable t) {
                LOG.warn("[FFPayloads] handleSetIntSettingOnServer failed safely: {}", t.toString());
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleServerSettingsOnClient(@NotNull ServerSettingsPayload msg,
                                                     @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                ClientState.applyFromServer(msg);
                notifySettingsScreenIfOpen();
            } catch (Throwable t) {
                LOG.error("[FFPayloads] handleServerSettingsOnClient failed", t);
            }
        }));
        ctx.setPacketHandled(true);
    }

    private static void notifySettingsScreenIfOpen() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.screen == null) {
                return;
            }
            Object screen = mc.screen;
            Method m = screen.getClass().getMethod("onServerSettingsUpdated");
            m.setAccessible(true);
            m.invoke(screen);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Message types
    // ---------------------------------------------------------------------

    private static final class SettingKeyBool {
        private static final byte CHAT_DISABLED = 1;
        private static final byte ENABLE_SUSPICIOUS_FEATHER = 2;
        private static final byte ENABLE_SUSPICIOUS_CHEST = 3;
        private static final byte ENABLE_RAVEN_ARMOR = 4;
        private static final byte ENABLE_MAILBOX = 5;
    }

    private static final class SettingKeyInt {
        private static final byte WILD_RAVENS_PER_PLAYER = 10;
        private static final byte RAVEN_LINK_DURATION_SECONDS = 11;
        private static final byte MAX_RAVEN_CHESTS_PER_PLAYER = 12;
        private static final byte RAVEN_LOG_RETENTION_MINUTES = 13;
        private static final byte RAVEN_LOG_MAX_BYTES_PER_PLAYER = 14;
        private static final byte ENDERPACK_DEPOSIT_COOLDOWN_SECONDS = 15;
        private static final byte SCROLL_DELIVERY_COOLDOWN_SECONDS = 16;
        private static final byte COURIER_TIMEOUT_RETRY_SECONDS = 17;
    }

    public record RequestServerSettingsPacket() {
        public static void encode(@NotNull RequestServerSettingsPacket msg, @NotNull FriendlyByteBuf buf) {
        }

        public static @NotNull RequestServerSettingsPacket decode(@NotNull FriendlyByteBuf buf) {
            return new RequestServerSettingsPacket();
        }
    }

    public record SetBooleanSettingPacket(byte key, boolean value) {
        public static void encode(@NotNull SetBooleanSettingPacket msg, @NotNull FriendlyByteBuf buf) {
            buf.writeByte(msg.key);
            buf.writeBoolean(msg.value);
        }

        public static @NotNull SetBooleanSettingPacket decode(@NotNull FriendlyByteBuf buf) {
            byte key = buf.readByte();
            boolean value = buf.readBoolean();
            return new SetBooleanSettingPacket(key, value);
        }
    }

    public record SetIntSettingPacket(byte key, int value) {
        public static void encode(@NotNull SetIntSettingPacket msg, @NotNull FriendlyByteBuf buf) {
            buf.writeByte(msg.key);
            buf.writeVarInt(msg.value);
        }

        public static @NotNull SetIntSettingPacket decode(@NotNull FriendlyByteBuf buf) {
            byte key = buf.readByte();
            int value = buf.readVarInt();
            return new SetIntSettingPacket(key, value);
        }
    }

    public record ServerSettingsPayload(
            boolean chatDisabled,
            boolean suspiciousFeatherEnabled,
            boolean suspiciousChestEnabled,
            boolean ravenArmorEnabled,
            boolean mailboxEnabled,
            int wildRavensPerPlayer,
            int ravenLinkDurationSeconds,
            int maxRavenChestsPerPlayer,
            int ravenLogRetentionMinutes,
            int ravenLogMaxBytesPerPlayer,
            int enderpackDepositCooldownSeconds,
            int scrollDeliveryCooldownSeconds,
            int courierTimeoutRetrySeconds,
            boolean canEditChat
    ) {
        public static void encode(@NotNull ServerSettingsPayload msg, @NotNull FriendlyByteBuf buf) {
            buf.writeBoolean(msg.chatDisabled);
            buf.writeBoolean(msg.suspiciousFeatherEnabled);
            buf.writeBoolean(msg.suspiciousChestEnabled);
            buf.writeBoolean(msg.ravenArmorEnabled);
            buf.writeBoolean(msg.mailboxEnabled);
            buf.writeVarInt(msg.wildRavensPerPlayer);
            buf.writeVarInt(msg.ravenLinkDurationSeconds);
            buf.writeVarInt(msg.maxRavenChestsPerPlayer);
            buf.writeVarInt(msg.ravenLogRetentionMinutes);
            buf.writeVarInt(msg.ravenLogMaxBytesPerPlayer);
            buf.writeVarInt(msg.enderpackDepositCooldownSeconds);
            buf.writeVarInt(msg.scrollDeliveryCooldownSeconds);
            buf.writeVarInt(msg.courierTimeoutRetrySeconds);
            buf.writeBoolean(msg.canEditChat);
        }

        public static @NotNull ServerSettingsPayload decode(@NotNull FriendlyByteBuf buf) {
            boolean chatDisabled = buf.readBoolean();
            boolean suspiciousFeatherEnabled = buf.readBoolean();
            boolean suspiciousChestEnabled = buf.readBoolean();
            boolean ravenArmorEnabled = buf.readBoolean();
            boolean mailboxEnabled = buf.readBoolean();
            int wildRavensPerPlayer = buf.readVarInt();
            int ravenLinkDurationSeconds = buf.readVarInt();
            int maxRavenChestsPerPlayer = buf.readVarInt();
            int ravenLogRetentionMinutes = buf.readVarInt();
            int ravenLogMaxBytesPerPlayer = buf.readVarInt();
            int enderpackDepositCooldownSeconds = buf.readVarInt();
            int scrollDeliveryCooldownSeconds = buf.readVarInt();
            int courierTimeoutRetrySeconds = buf.readVarInt();
            boolean canEditChat = buf.readBoolean();
            return new ServerSettingsPayload(
                    chatDisabled,
                    suspiciousFeatherEnabled,
                    suspiciousChestEnabled,
                    ravenArmorEnabled,
                    mailboxEnabled,
                    wildRavensPerPlayer,
                    ravenLinkDurationSeconds,
                    maxRavenChestsPerPlayer,
                    ravenLogRetentionMinutes,
                    ravenLogMaxBytesPerPlayer,
                    enderpackDepositCooldownSeconds,
                    scrollDeliveryCooldownSeconds,
                    courierTimeoutRetrySeconds,
                    canEditChat
            );
        }
    }
}
