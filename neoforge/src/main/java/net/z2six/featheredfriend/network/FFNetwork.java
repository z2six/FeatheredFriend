// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.data.FFKnownPlayersData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Payload-based networking for FeatheredFriend.
 *
 * NOTE:
 * - This file remains responsible for your legacy/gameplay payloads (known players, seal GUI, raven naming, whistle, etc.)
 * - Server settings sync is handled separately in FFPayloads.java to avoid ID collisions and keep concerns isolated.
 */
public final class FFNetwork {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String CLIENT_HANDLER_CLASS =
            "net.z2six.featheredfriend.client.network.FFNetworkClientHandlers";

    private FFNetwork() {
    }

    static {
        try {
            LOG.debug("[FFNetwork] Class loaded");
        } catch (Throwable ignored) {
        }
    }

    public static void registerSimpleMessages() {
        try {
            LOG.info("[FFNetwork] registerSimpleMessages() called; payload-based networking is used (no-op)");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] registerSimpleMessages() failed (no-op stub)", t);
        }
    }

    public static void register(final RegisterPayloadHandlersEvent event) {
        try {
            LOG.info("[FFNetwork] RegisterPayloadHandlersEvent received -> registering payloads");
            var registrar = event.registrar("1");

            registrar.playToClient(
                    KnownPlayersPayload.TYPE,
                    KnownPlayersPayload.STREAM_CODEC,
                    FFNetwork::handleKnownPlayersOnClientProxy
            );

            registrar.playToClient(
                    OpenRavenNameScreenPayload.TYPE,
                    OpenRavenNameScreenPayload.STREAM_CODEC,
                    FFNetwork::handleOpenRavenNameScreenOnClientProxy
            );

            registrar.playToServer(
                    RequestKnownPlayersPacket.TYPE,
                    RequestKnownPlayersPacket.STREAM_CODEC,
                    FFNetwork::handleRequestKnownPlayersOnServer
            );

            registrar.playToServer(
                    SealStampCarveResultPacket.TYPE,
                    SealStampCarveResultPacket.STREAM_CODEC,
                    FFNetwork::handleSealStampCarveResultOnServer
            );

            registrar.playToServer(
                    WaxSealPacket.TYPE,
                    WaxSealPacket.STREAM_CODEC,
                    FFNetwork::handleWaxSealOnServer
            );

            registrar.playToServer(
                    BreakSealPacket.TYPE,
                    BreakSealPacket.STREAM_CODEC,
                    FFNetwork::handleBreakSealOnServer
            );

            registrar.playToServer(
                    RavenNameChosenPacket.TYPE,
                    RavenNameChosenPacket.STREAM_CODEC,
                    FFNetwork::handleRavenNameChosenOnServer
            );

            registrar.playToServer(
                    WhistleForRavenPacket.TYPE,
                    WhistleForRavenPacket.STREAM_CODEC,
                    FFNetwork::handleWhistleForRavenOnServer
            );

            LOG.info("[FFNetwork] Registered payload channels: known_players, request_known_players, open_raven_name_screen, " +
                    "seal_stamp_carve_result, wax_seal, break_seal, raven_name_chosen, whistle_for_raven");

        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to register payload handlers", t);
        }
    }

    // ---------------------------
    // Client dispatch (dedicated-safe)
    // ---------------------------

    private static void handleKnownPlayersOnClientProxy(@NotNull KnownPlayersPayload payload,
                                                        @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                dispatchToClientHandler("handleKnownPlayersOnClient", payload, context);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] handleKnownPlayersOnClientProxy failed", t);
            }
        });
    }

    private static void handleOpenRavenNameScreenOnClientProxy(@NotNull OpenRavenNameScreenPayload payload,
                                                               @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                dispatchToClientHandler("handleOpenRavenNameScreenOnClient", payload, context);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] handleOpenRavenNameScreenOnClientProxy failed", t);
            }
        });
    }

    private static void dispatchToClientHandler(@NotNull String methodName,
                                                @NotNull Object payload,
                                                @NotNull IPayloadContext context) {
        try {
            Class<?> cls = Class.forName(CLIENT_HANDLER_CLASS);
            Method m = cls.getDeclaredMethod(methodName, payload.getClass(), IPayloadContext.class);
            m.setAccessible(true);
            m.invoke(null, payload, context);
        } catch (ClassNotFoundException e) {
            LOG.debug("[FFNetwork] Client handler class not present (expected on dedicated server): {}", CLIENT_HANDLER_CLASS);
        } catch (NoSuchMethodException e) {
            LOG.error("[FFNetwork] Client handler method not found: {}.{}({}, {})",
                    CLIENT_HANDLER_CLASS, methodName, payload.getClass().getName(), IPayloadContext.class.getName(), e);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to dispatch to client handler {}.{}(...)",
                    CLIENT_HANDLER_CLASS, methodName, t);
        }
    }

    // ---------------------------
    // Server handlers
    // ---------------------------

    private static void handleRequestKnownPlayersOnServer(@NotNull RequestKnownPlayersPacket payload,
                                                          @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleRequestKnownPlayersOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                var server = serverPlayer.server;
                if (server == null) {
                    LOG.error("[FFNetwork] handleRequestKnownPlayersOnServer: server is null for player={}",
                            serverPlayer.getGameProfile().getName());
                    return;
                }

                FFKnownPlayersData data = FFKnownPlayersData.get(server);
                List<FFKnownPlayersData.KnownPlayer> players = data.getSortedPlayers();

                sendKnownPlayersTo(serverPlayer, players);

                LOG.debug("[FFNetwork] Served request_known_players to '{}' (count={})",
                        serverPlayer.getGameProfile().getName(),
                        players.size());

            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RequestKnownPlayersPacket on server", t);
            }
        });
    }

    private static void handleWhistleForRavenOnServer(@NotNull WhistleForRavenPacket payload,
                                                      @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleWhistleForRavenOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                LOG.debug("[FFNetwork] handleWhistleForRavenOnServer: whistle request from '{}'",
                        serverPlayer.getGameProfile().getName());

                net.z2six.featheredfriend.world.TamedRavenScrollWatcher.handleWhistleSummonRequest(serverPlayer);

            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle WhistleForRavenPacket on server", t);
            }
        });
    }

    private static void handleSealStampCarveResultOnServer(@NotNull SealStampCarveResultPacket payload,
                                                           @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleSealStampCarveResultOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                SealStampCarveResultPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle SealStampCarveResultPacket on server", t);
            }
        });
    }

    private static void handleWaxSealOnServer(@NotNull WaxSealPacket payload,
                                              @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleWaxSealOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                WaxSealPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle WaxSealPacket on server", t);
            }
        });
    }

    private static void handleBreakSealOnServer(@NotNull BreakSealPacket payload,
                                                @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleBreakSealOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                BreakSealPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle BreakSealPacket on server", t);
            }
        });
    }

    private static void handleRavenNameChosenOnServer(@NotNull RavenNameChosenPacket payload,
                                                      @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleRavenNameChosenOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                RavenNameChosenPacket.handle(payload, serverPlayer);

            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenNameChosenPacket on server", t);
            }
        });
    }

    // ---------------------------
    // Send helpers
    // ---------------------------

    public static void sendRavenNameChosenToServer(int ravenEntityId,
                                                   @NotNull String name) {
        try {
            String safeName = name != null ? name : "";
            RavenNameChosenPacket p = new RavenNameChosenPacket(ravenEntityId, safeName);
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent RavenNameChosenPacket to server (ravenEntityId={}, nameLen={})",
                    ravenEntityId, safeName.length());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenNameChosenToServer failed", t);
        }
    }

    public static void sendWaxSealToServer(
            int stampSlot,
            String dateText,
            String recipientName,
            String recipientUUID,
            String recipientText,
            String messageText,
            String signatureText,
            long seed,
            int slices,
            int style,
            String senderName
    ) {
        try {
            WaxSealPacket p = new WaxSealPacket(
                    stampSlot,
                    dateText != null ? dateText : "",
                    recipientName != null ? recipientName : "",
                    recipientUUID != null ? recipientUUID : "",
                    recipientText != null ? recipientText : "",
                    messageText != null ? messageText : "",
                    signatureText != null ? signatureText : "",
                    seed,
                    slices,
                    style,
                    senderName != null ? senderName : ""
            );

            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent WaxSealPacket to server");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendWaxSealToServer failed", t);
        }
    }

    public static void sendBreakSealToServer(int slotHint,
                                             long seed,
                                             @NotNull String recipientUUID,
                                             @NotNull String dateText,
                                             @NotNull String senderName) {
        try {
            BreakSealPacket p = new BreakSealPacket(
                    slotHint,
                    seed,
                    recipientUUID != null ? recipientUUID : "",
                    dateText != null ? dateText : "",
                    senderName != null ? senderName : ""
            );
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent BreakSealPacket to server (slotHint={} seed={})", slotHint, seed);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendBreakSealToServer failed", t);
        }
    }

    public static void sendOpenRavenNamingScreen(@NotNull ServerPlayer player,
                                                 int ravenEntityId) {
        try {
            OpenRavenNameScreenPayload payload = new OpenRavenNameScreenPayload(ravenEntityId);
            PacketDistributor.sendToPlayer(player, payload);
            LOG.debug("[FFNetwork] Sent OpenRavenNameScreenPayload to {} for ravenEntityId={}",
                    player.getGameProfile().getName(), ravenEntityId);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenNamingScreen failed for player={}",
                    player.getGameProfile().getName(), t);
        }
    }

    public static void sendWhistleForRaven() {
        try {
            WhistleForRavenPacket p = new WhistleForRavenPacket();
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent WhistleForRavenPacket to server");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendWhistleForRaven failed safely", t);
        }
    }

    public static void sendRequestKnownPlayersToServer() {
        try {
            RequestKnownPlayersPacket p = new RequestKnownPlayersPacket();
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent RequestKnownPlayersPacket to server");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRequestKnownPlayersToServer failed safely", t);
        }
    }

    public static void sendKnownPlayersTo(@NotNull ServerPlayer player,
                                          @NotNull Collection<FFKnownPlayersData.KnownPlayer> players) {
        try {
            List<KnownPlayerInfo> copy = new ArrayList<>();
            for (FFKnownPlayersData.KnownPlayer kp : players) {
                if (kp == null || kp.uuid() == null || kp.name() == null || kp.name().isBlank()) continue;
                copy.add(new KnownPlayerInfo(kp.uuid(), kp.name()));
            }
            KnownPlayersPayload payload = new KnownPlayersPayload(copy);
            PacketDistributor.sendToPlayer(player, payload);
            LOG.debug("[FFNetwork] Sent {} known players to {}", copy.size(), player.getGameProfile().getName());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to send KnownPlayersPayload to {}", player.getGameProfile().getName(), t);
        }
    }

    // ---------------------------
    // Payloads
    // ---------------------------

    public record RequestKnownPlayersPacket() implements CustomPacketPayload {
        public static final Type<RequestKnownPlayersPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_known_players"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestKnownPlayersPacket> STREAM_CODEC =
                StreamCodec.of(RequestKnownPlayersPacket::encode, RequestKnownPlayersPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf, @NotNull RequestKnownPlayersPacket payload) {
            try {
                // no fields
            } catch (Throwable t) {
                LOG.error("[FFNetwork] RequestKnownPlayersPacket encode failed", t);
            }
        }

        private static @NotNull RequestKnownPlayersPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new RequestKnownPlayersPacket();
            } catch (Throwable t) {
                LOG.error("[FFNetwork] RequestKnownPlayersPacket decode failed", t);
                return new RequestKnownPlayersPacket();
            }
        }

        @Override
        public @NotNull Type<RequestKnownPlayersPacket> type() {
            return TYPE;
        }
    }

    public record WhistleForRavenPacket() implements CustomPacketPayload {

        public static final Type<WhistleForRavenPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "whistle_for_raven"));

        public static final StreamCodec<RegistryFriendlyByteBuf, WhistleForRavenPacket> STREAM_CODEC =
                StreamCodec.of(WhistleForRavenPacket::encode, WhistleForRavenPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull WhistleForRavenPacket payload) {
            try {
                // no fields
            } catch (Throwable t) {
                LOG.error("[FFNetwork] WhistleForRavenPacket encode failed", t);
            }
        }

        private static @NotNull WhistleForRavenPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new WhistleForRavenPacket();
            } catch (Throwable t) {
                LOG.error("[FFNetwork] WhistleForRavenPacket decode failed", t);
                return new WhistleForRavenPacket();
            }
        }

        @Override
        public @NotNull Type<WhistleForRavenPacket> type() {
            return TYPE;
        }
    }

    public record KnownPlayerInfo(@NotNull UUID uuid, @NotNull String name) {
    }

    public record KnownPlayersPayload(List<KnownPlayerInfo> players) implements CustomPacketPayload {

        public static final Type<KnownPlayersPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "known_players"));

        public static final StreamCodec<RegistryFriendlyByteBuf, KnownPlayersPayload> STREAM_CODEC =
                StreamCodec.of(KnownPlayersPayload::encode, KnownPlayersPayload::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull KnownPlayersPayload payload) {
            try {
                List<KnownPlayerInfo> list = payload.players();
                buf.writeVarInt(list.size());

                for (KnownPlayerInfo info : list) {
                    if (info == null || info.uuid() == null || info.name() == null) {
                        buf.writeUUID(new UUID(0L, 0L));
                        buf.writeUtf("", 1024);
                        continue;
                    }
                    buf.writeUUID(info.uuid());
                    buf.writeUtf(info.name(), 1024);
                }
            } catch (Throwable t) {
                LOG.error("[FFNetwork] KnownPlayersPayload encode failed", t);
            }
        }

        private static @NotNull KnownPlayersPayload decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                int size = buf.readVarInt();
                if (size < 0) size = 0;
                if (size > 10000) {
                    LOG.warn("[FFNetwork] KnownPlayersPayload decode: suspicious size={} (clamping to 10000)", size);
                    size = 10000;
                }

                List<KnownPlayerInfo> list = new ArrayList<>(size);

                for (int i = 0; i < size; i++) {
                    UUID uuid = buf.readUUID();
                    String name = buf.readUtf(1024);

                    if (uuid == null) continue;
                    if (name == null || name.isBlank()) continue;

                    if (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L) continue;
                    list.add(new KnownPlayerInfo(uuid, name));
                }

                return new KnownPlayersPayload(list);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] KnownPlayersPayload decode failed", t);
                return new KnownPlayersPayload(List.of());
            }
        }

        @Override
        public @NotNull Type<KnownPlayersPayload> type() {
            return TYPE;
        }
    }

    public record OpenRavenNameScreenPayload(int ravenEntityId) implements CustomPacketPayload {

        public static final Type<OpenRavenNameScreenPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_raven_name_screen"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRavenNameScreenPayload> STREAM_CODEC =
                StreamCodec.of(OpenRavenNameScreenPayload::encode, OpenRavenNameScreenPayload::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull OpenRavenNameScreenPayload payload) {
            try {
                buf.writeVarInt(payload.ravenEntityId());
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenNameScreenPayload encode failed", t);
            }
        }

        private static @NotNull OpenRavenNameScreenPayload decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                return new OpenRavenNameScreenPayload(id);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenNameScreenPayload decode failed", t);
                return new OpenRavenNameScreenPayload(-1);
            }
        }

        @Override
        public @NotNull Type<OpenRavenNameScreenPayload> type() {
            return TYPE;
        }
    }
}
