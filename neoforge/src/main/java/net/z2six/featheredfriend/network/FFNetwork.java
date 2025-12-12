// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.data.KnownPlayersClientCache;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java
 *
 * FFNetwork
 */
@EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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

    @SubscribeEvent
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

            // WaxSealPacket registration (C2S)
            registrar.playToServer(
                    WaxSealPacket.TYPE,
                    WaxSealPacket.STREAM_CODEC,
                    FFNetwork::handleWaxSealOnServer
            );

            LOG.info("[FFNetwork] Registered KnownPlayersPayload (S2C), SealStampCarveResultPacket (C2S), WaxSealPacket (C2S)");
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

    // ---------------------------------------------------------------------
    // WaxSealPacket handler
    // ---------------------------------------------------------------------

    private static void handleWaxSealOnServer(@NotNull WaxSealPacket payload,
                                              @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleWaxSealOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                // New WaxSealPacket.handle is void – just invoke it.
                WaxSealPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle WaxSealPacket on server", t);
            }
        });
    }

    // ---------------------------------------------------------------------
    // KnownPlayers payload type
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
}
