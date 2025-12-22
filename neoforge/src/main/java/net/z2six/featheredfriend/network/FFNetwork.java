// neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.data.KnownPlayersClientCache;
import net.z2six.featheredfriend.client.screen.RavenNamingScreen;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java
 *
 * Payload-based networking registration for NeoForge.
 *
 * IMPORTANT:
 *  - NeoForge 1.21.1: do NOT rely on EventBusSubscriber here.
 *  - FeatheredFriend (NeoForge entrypoint) must call:
 *      modEventBus.addListener(FFNetwork::register);
 */
public final class FFNetwork {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNetwork() {
    }

    public static void registerSimpleMessages() {
        try {
            LOG.info("[FFNetwork] registerSimpleMessages() called; using payload-based networking so this is a no-op");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] registerSimpleMessages() failed (no-op stub)", t);
        }
    }

    // ---------------------------------------------------------------------
    // Payload registration
    // ---------------------------------------------------------------------

    public static void register(final RegisterPayloadHandlersEvent event) {
        try {
            var registrar = event.registrar("1");

            // Existing: KnownPlayersPayload (S2C)
            registrar.playToClient(
                    KnownPlayersPayload.TYPE,
                    KnownPlayersPayload.STREAM_CODEC,
                    FFNetwork::handleKnownPlayersOnClient
            );

            // Existing: SealStampCarveResultPacket (C2S)
            registrar.playToServer(
                    SealStampCarveResultPacket.TYPE,
                    SealStampCarveResultPacket.STREAM_CODEC,
                    FFNetwork::handleSealStampCarveResultOnServer
            );

            // Existing: WaxSealPacket (C2S)
            registrar.playToServer(
                    WaxSealPacket.TYPE,
                    WaxSealPacket.STREAM_CODEC,
                    FFNetwork::handleWaxSealOnServer
            );

            // NEW: BreakSealPacket (C2S)
            registrar.playToServer(
                    BreakSealPacket.TYPE,
                    BreakSealPacket.STREAM_CODEC,
                    FFNetwork::handleBreakSealOnServer
            );

            // NEW: OpenRavenNameScreenPayload (S2C)
            registrar.playToClient(
                    OpenRavenNameScreenPayload.TYPE,
                    OpenRavenNameScreenPayload.STREAM_CODEC,
                    FFNetwork::handleOpenRavenNameScreenOnClient
            );

            // NEW: RavenNameChosenPacket (C2S)
            registrar.playToServer(
                    RavenNameChosenPacket.TYPE,
                    RavenNameChosenPacket.STREAM_CODEC,
                    FFNetwork::handleRavenNameChosenOnServer
            );

            LOG.info("[FFNetwork] Registered KnownPlayersPayload (S2C), SealStampCarveResultPacket (C2S), WaxSealPacket (C2S), BreakSealPacket (C2S), OpenRavenNameScreenPayload (S2C), RavenNameChosenPacket (C2S)");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to register payload handlers", t);
        }
    }

    // ---------------------------------------------------------------------
    // KnownPlayers S2C
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

    private static void handleKnownPlayersOnClient(@NotNull KnownPlayersPayload payload,
                                                   @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                KnownPlayersClientCache.update(payload.names());
                LOG.debug("[FFNetwork] Client cache updated with {} known players", payload.names().size());
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle KnownPlayersPayload on client", t);
            }
        });
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
    // WaxSealPacket sender (client → server)
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
    // BreakSealPacket sender (client → server)
    // ---------------------------------------------------------------------

    /**
     * Called by ScrollViewScreen the moment the user breaks the seal (wax click).
     */
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
    // NEW: Tamed Raven – Open naming screen (S2C)
    // ---------------------------------------------------------------------

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

    private static void handleOpenRavenNameScreenOnClient(@NotNull OpenRavenNameScreenPayload payload,
                                                          @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null || mc.player == null) return;
                if (mc.level == null) return;

                int id = payload.ravenEntityId();
                LOG.debug("[FFNetwork] handleOpenRavenNameScreenOnClient: opening GUI for ravenEntityId={}", id);
                mc.setScreen(new RavenNamingScreen(id));

            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle OpenRavenNameScreenPayload on client", t);
            }
        });
    }

    // ---------------------------------------------------------------------
    // NEW: Tamed Raven – Name chosen (client → server)
    // ---------------------------------------------------------------------

    public static void sendRavenNameChosenToServer(int ravenEntityId,
                                                   @NotNull String name) {
        try {
            RavenNameChosenPacket p = new RavenNameChosenPacket(
                    ravenEntityId,
                    name != null ? name : ""
            );
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent RavenNameChosenPacket to server (ravenEntityId={})", ravenEntityId);
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

                RavenNameChosenPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenNameChosenPacket on server", t);
            }
        });
    }

    // ---------------------------------------------------------------------
    // KnownPlayers payload type (existing)
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
    // NEW: OpenRavenNameScreenPayload (S2C)
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
