// neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java
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
 *
 * Handles NeoForge networking for FeatheredFriend.
 * Currently:
 *  - S2C payload: KnownPlayersPayload (list of all known player names).
 *
 * IMPORTANT:
 *  Payload handlers MUST be registered on BOTH physical sides (client + server),
 *  otherwise NeoForge will refuse to send the payload with
 *  "may not be sent to the client!" errors.
 */
@EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class FFNetwork {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNetwork() {
    }

    // -------------------------------------------------------------------------
    // Payload registration
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        try {
            // "1" is a simple protocol version string; bump if you ever break payload formats.
            var registrar = event.registrar("1");

            registrar.playToClient(
                    KnownPlayersPayload.TYPE,
                    KnownPlayersPayload.STREAM_CODEC,
                    FFNetwork::handleKnownPlayersOnClient
            );

            LOG.info("[FFNetwork] Registered KnownPlayersPayload S2C handler");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to register payload handlers", t);
        }
    }

    // -------------------------------------------------------------------------
    // S2C: KnownPlayersPayload
    // -------------------------------------------------------------------------

    public static void sendKnownPlayersTo(@NotNull ServerPlayer player, @NotNull Collection<String> names) {
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
        // NeoForge 1.21 style: use enqueueWork
        context.enqueueWork(() -> {
            try {
                KnownPlayersClientCache.update(payload.names());
                LOG.debug("[FFNetwork] Client cache updated with {} known players", payload.names().size());
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle KnownPlayersPayload on client", t);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Payload type
    // -------------------------------------------------------------------------

    public record KnownPlayersPayload(List<String> names) implements CustomPacketPayload {

        public static final Type<KnownPlayersPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "known_players"));

        /**
         * Simple manual StreamCodec implementation. Encodes:
         *  - varint size
         *  - for each: utf string (up to 1024 characters, arbitrarily generous)
         */
        public static final StreamCodec<RegistryFriendlyByteBuf, KnownPlayersPayload> STREAM_CODEC =
                StreamCodec.of(KnownPlayersPayload::encode, KnownPlayersPayload::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf, @NotNull KnownPlayersPayload payload) {
            List<String> list = payload.names();
            int size = list.size();
            buf.writeVarInt(size);
            for (String name : list) {
                buf.writeUtf(name, 1024);
            }
        }

        private static @NotNull KnownPlayersPayload decode(@NotNull RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt();
            List<String> list = new ArrayList<>(Math.max(0, size));
            for (int i = 0; i < size; i++) {
                String name = buf.readUtf(1024);
                list.add(name);
            }
            return new KnownPlayersPayload(list);
        }

        @Override
        public @NotNull Type<KnownPlayersPayload> type() {
            return TYPE;
        }
    }
}
