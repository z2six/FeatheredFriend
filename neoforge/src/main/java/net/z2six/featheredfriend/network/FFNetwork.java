// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
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
import net.z2six.featheredfriend.client.gui.SigilPreviewScreen;
import net.z2six.featheredfriend.network.payload.SigilPreviewPayload;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**

 // neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java

 FFNetwork

 Handles NeoForge networking for FeatheredFriend.

 KnownPlayersPayload (S2C): list of known player names.

 SigilPreviewPayload (S2C): opens sigil preview screen with slices + shapeSetIndex + seed.
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
            var registrar = event.registrar("1");

            // Existing: KnownPlayersPayload
            registrar.playToClient(
                    KnownPlayersPayload.TYPE,
                    KnownPlayersPayload.STREAM_CODEC,
                    FFNetwork::handleKnownPlayersOnClient
            );

            // New: SigilPreviewPayload
            registrar.playToClient(
                    SigilPreviewPayload.TYPE,
                    SigilPreviewPayload.STREAM_CODEC,
                    FFNetwork::handleSigilPreviewOnClient
            );

            LOG.info("[FFNetwork] Registered KnownPlayersPayload + SigilPreviewPayload handlers");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to register payload handlers", t);
        }


    }

// -------------------------------------------------------------------------
// KnownPlayers S2C
// -------------------------------------------------------------------------

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

// -------------------------------------------------------------------------
// SigilPreview S2C
// -------------------------------------------------------------------------

    public static void sendSigilPreview(@NotNull ServerPlayer player,
                                        int slices,
                                        int shapeSetIndex,
                                        @NotNull String seed) {
        try {
            PacketDistributor.sendToPlayer(player, new SigilPreviewPayload(slices, shapeSetIndex, seed));
            LOG.debug("[FFNetwork] Sent SigilPreviewPayload to {} (slices={} shapeSetIndex={} seed='{}')",
                    player.getGameProfile().getName(), slices, shapeSetIndex, seed);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to send SigilPreviewPayload to {}", player.getGameProfile().getName(), t);
        }
    }

    private static void handleSigilPreviewOnClient(@NotNull SigilPreviewPayload payload,
                                                   @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) {
                    LOG.warn("[FFNetwork] Minecraft instance is null in SigilPreview handler");
                    return;
                }
                SigilPreviewScreen.open(payload.slices(), payload.shapeSetIndex(), payload.seed());
                LOG.debug("[FFNetwork] Opened SigilPreviewScreen for slices={} shapeSetIndex={} seed='{}'",
                        payload.slices(), payload.shapeSetIndex(), payload.seed());
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle SigilPreviewPayload on client", t);
            }
        });
    }

// -------------------------------------------------------------------------
// KnownPlayers payload type
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

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull KnownPlayersPayload payload) {
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