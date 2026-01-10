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
import net.z2six.featheredfriend.world.FeatheredFriendSettingsData;
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
 * - canEditChat (per-player permission check, computed server-side)
 *
 * Client-only preference "autoSummonOnScroll" is handled by FFClientConfig (not server-owned).
 */
public final class FFPayloads {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Bump if you change payload shapes. Must match client + server.
     */
    private static final String PROTOCOL_VERSION = "1";

    private FFPayloads() {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Registration hook
    // ---------------------------------------------------------------------

    public static void register(IEventBus modBus) {
        try {
            modBus.addListener(FFPayloads::onRegisterPayloadHandlers);
            LOG.info("[FFPayloads] Hooked RegisterPayloadHandlersEvent listener");
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

            LOG.info("[FFPayloads] Registered settings payloads OK (protocol={})", PROTOCOL_VERSION);
        } catch (Throwable t) {
            LOG.error("[FFPayloads] onRegisterPayloadHandlers failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Client-side cache
    // ---------------------------------------------------------------------

    public static final class ClientState {
        private static volatile boolean hasSynced = false;
        private static volatile boolean chatDisabled = true;
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

        public static boolean canEditChat() {
            return canEditChat;
        }

        private static void applyFromServer(boolean newChatDisabled, boolean newCanEditChat) {
            chatDisabled = newChatDisabled;
            canEditChat = newCanEditChat;
            hasSynced = true;

            LOG.info("[FFPayloads.ClientState] Applied server settings: chatDisabled={} canEditChat={}",
                    newChatDisabled, newCanEditChat);
        }

        public static void clear() {
            hasSynced = false;
            chatDisabled = true;
            canEditChat = false;
            LOG.info("[FFPayloads.ClientState] Cleared client cache");
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
    public record ServerSettingsPayload(boolean chatDisabled, boolean canEditChat)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "server_settings_v1");

        public static final Type<ServerSettingsPayload> TYPE = new Type<>(ID);

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerSettingsPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ServerSettingsPayload::chatDisabled,
                        ByteBufCodecs.BOOL, ServerSettingsPayload::canEditChat,
                        ServerSettingsPayload::new
                );

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

    // ---------------------------------------------------------------------
    // Server-side send helpers
    // ---------------------------------------------------------------------

    public static void sendSettingsToPlayer(ServerLevel level, ServerPlayer player) {
        try {
            if (level == null || player == null) {
                LOG.warn("[FFPayloads] sendSettingsToPlayer: null args; skipping");
                return;
            }

            FeatheredFriendSettingsData data = FeatheredFriendSettingsData.get(level);

            boolean chatDisabledValue = data.isChatDisabled();
            boolean canEditChatValue;
            try {
                canEditChatValue = player.hasPermissions(4);
            } catch (Throwable ignored) {
                canEditChatValue = false;
            }

            ServerSettingsPayload msg = new ServerSettingsPayload(chatDisabledValue, canEditChatValue);
            PacketDistributor.sendToPlayer(player, msg);

            LOG.info("[FFPayloads] Sent settings to {}: chatDisabled={} canEditChat={}",
                    player.getGameProfile().getName(), chatDisabledValue, canEditChatValue);

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
                    ClientState.applyFromServer(payload.chatDisabled(), payload.canEditChat());
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

                    boolean allowed;
                    try {
                        allowed = sp.hasPermissions(4);
                    } catch (Throwable ignored) {
                        allowed = false;
                    }

                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetChatDisabled without permission; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    FeatheredFriendSettingsData data = FeatheredFriendSettingsData.get(level);
                    data.setChatDisabled(payload.chatDisabled());

                    LOG.info("[FFPayloads] {} set chatDisabled -> {}",
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
}
