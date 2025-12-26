// FFNetwork.java
// neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java
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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Payload-based networking registration for NeoForge.
 *
 * IMPORTANT SERVER SAFETY NOTE:
 *  - This class MUST be safe to load on a dedicated server.
 *  - Therefore it must NOT import or reference net.minecraft.client.* or any client-only classes.
 *
 * Client-only packet handling is delegated to:
 *   net.z2six.featheredfriend.client.network.FFNetworkClientHandlers
 * via reflection, so the server never needs to load that class.
 *
 * FeatheredFriend (NeoForge entrypoint) must call:
 *   modEventBus.addListener(FFNetwork::register);
 */
public final class FFNetwork {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Client-only handler class name (do NOT reference directly from server-safe code).
     */
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

    /**
     * Legacy stub retained so old call sites don't break.
     */
    public static void registerSimpleMessages() {
        try {
            LOG.info("[FFNetwork] registerSimpleMessages() called; payload-based networking is used (no-op)");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] registerSimpleMessages() failed (no-op stub)", t);
        }
    }

    // ---------------------------------------------------------------------
    // Payload registration
    // ---------------------------------------------------------------------

    public static void register(final RegisterPayloadHandlersEvent event) {
        try {
            LOG.info("[FFNetwork] RegisterPayloadHandlersEvent received -> registering payloads");
            var registrar = event.registrar("1");

            // -----------------------------------------------------------------
            // S2C payloads (server -> client)
            // IMPORTANT: still register on dedicated server so the channel exists
            // in the handshake. The handler will never run on server.
            // -----------------------------------------------------------------

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

            // -----------------------------------------------------------------
            // C2S payloads (client -> server)
            // -----------------------------------------------------------------

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

            LOG.info("[FFNetwork] Registered payload channels: known_players, open_raven_name_screen, " +
                    "seal_stamp_carve_result, wax_seal, break_seal, raven_name_chosen, whistle_for_raven");

        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to register payload handlers", t);
        }
    }

    // ---------------------------------------------------------------------
    // Client-side proxies (server-safe)
    // ---------------------------------------------------------------------

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

    /**
     * Reflection dispatcher into client-only code.
     * Keeps this class safe to load on dedicated servers.
     */
    private static void dispatchToClientHandler(@NotNull String methodName,
                                                @NotNull Object payload,
                                                @NotNull IPayloadContext context) {
        try {
            Class<?> cls = Class.forName(CLIENT_HANDLER_CLASS);
            Method m = cls.getDeclaredMethod(methodName, payload.getClass(), IPayloadContext.class);
            m.setAccessible(true);
            m.invoke(null, payload, context);
        } catch (ClassNotFoundException e) {
            // Normal on dedicated server. On client, this would indicate a broken jar/layout.
            LOG.debug("[FFNetwork] Client handler class not present (expected on dedicated server): {}", CLIENT_HANDLER_CLASS);
        } catch (NoSuchMethodException e) {
            LOG.error("[FFNetwork] Client handler method not found: {}.{}({}, {})",
                    CLIENT_HANDLER_CLASS, methodName, payload.getClass().getName(), IPayloadContext.class.getName(), e);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to dispatch to client handler {}.{}(...)",
                    CLIENT_HANDLER_CLASS, methodName, t);
        }
    }

    // ---------------------------------------------------------------------
    // WhistleForRavenPacket handler (C2S)
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // RavenNameChosenPacket sender/handler (client -> server)
    // ---------------------------------------------------------------------

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

    private static void handleRavenNameChosenOnServer(@NotNull RavenNameChosenPacket payload,
                                                      @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleRavenNameChosenOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                // Delegate to packet's static handler
                RavenNameChosenPacket.handle(payload, serverPlayer);

            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenNameChosenPacket on server", t);
            }
        });
    }

    // ---------------------------------------------------------------------
    // KnownPlayers S2C sender
    // ---------------------------------------------------------------------

    public static void sendKnownPlayersTo(@NotNull ServerPlayer player,
                                          @NotNull Collection<String> names) {
        try {
            List<String> copy = new ArrayList<>(names);
            KnownPlayersPayload payload = new KnownPlayersPayload(copy);
            PacketDistributor.sendToPlayer(player, payload);
            LOG.debug("[FFNetwork] Sent {} known players to {}", copy.size(), player.getGameProfile().getName());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to send KnownPlayersPayload to {}", player.getGameProfile().getName(), t);
        }
    }

    // ---------------------------------------------------------------------
    // SealStampCarveResult C2S
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // WaxSealPacket sender/handler (client -> server)
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // BreakSealPacket sender/handler (client -> server)
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // S2C: Open naming screen sender
    // ---------------------------------------------------------------------

    public static void sendOpenRavenNamingScreen(@NotNull ServerPlayer player,
                                                 int ravenEntityId) {
        try {
            OpenRavenNameScreenPayload payload = new OpenRavenNameScreenPayload(ravenEntityId);
            PacketDistributor.sendToPlayer(player, payload);
            LOG.debug("[FFNetwork] Sent OpenRavenNameScreenPayload to {} for ravenEntityId={}",
                    player.getGameProfile().getName(), ravenEntityId);
        } catch (Throwable t) {
            // FIXED: was incorrectly chaining getGameProfile().getGameProfile()
            LOG.error("[FFNetwork] sendOpenRavenNamingScreen failed for player={}",
                    player.getGameProfile().getName(), t);
        }
    }

    // ---------------------------------------------------------------------
    // Whistle sending (client -> server)
    // ---------------------------------------------------------------------

    public static void sendWhistleForRaven() {
        try {
            WhistleForRavenPacket p = new WhistleForRavenPacket();
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent WhistleForRavenPacket to server");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendWhistleForRaven failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // WhistleForRavenPacket (C2S, no fields)
    // ---------------------------------------------------------------------

    public record WhistleForRavenPacket() implements CustomPacketPayload {

        public static final Type<WhistleForRavenPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "whistle_for_raven"));

        public static final StreamCodec<RegistryFriendlyByteBuf, WhistleForRavenPacket> STREAM_CODEC =
                StreamCodec.of(WhistleForRavenPacket::encode, WhistleForRavenPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull WhistleForRavenPacket payload) {
            try {
                // No fields – nothing to write.
            } catch (Throwable t) {
                LOG.error("[FFNetwork] WhistleForRavenPacket encode failed", t);
            }
        }

        private static @NotNull WhistleForRavenPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                // No fields – nothing to read.
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

    // ---------------------------------------------------------------------
    // KnownPlayersPayload (S2C)
    // ---------------------------------------------------------------------

    public record KnownPlayersPayload(List<String> names) implements CustomPacketPayload {

        public static final Type<KnownPlayersPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "known_players"));

        public static final StreamCodec<RegistryFriendlyByteBuf, KnownPlayersPayload> STREAM_CODEC =
                StreamCodec.of(KnownPlayersPayload::encode, KnownPlayersPayload::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull KnownPlayersPayload payload) {
            try {
                List<String> list = payload.names();
                buf.writeVarInt(list.size());
                for (String name : list) buf.writeUtf(name, 1024);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] KnownPlayersPayload encode failed", t);
            }
        }

        private static @NotNull KnownPlayersPayload decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                int size = buf.readVarInt();
                List<String> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) list.add(buf.readUtf(1024));
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

    // ---------------------------------------------------------------------
    // OpenRavenNameScreenPayload (S2C)
    // ---------------------------------------------------------------------

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
