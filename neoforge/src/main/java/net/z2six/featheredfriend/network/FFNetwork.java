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
import net.z2six.featheredfriend.client.gui.SigilPreviewScreen;
import net.z2six.featheredfriend.client.data.KnownPlayersClientCache;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class FFNetwork {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNetwork() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");

        // Known players payload (existing)
        registrar.playToClient(
                KnownPlayersPayload.TYPE,
                KnownPlayersPayload.STREAM_CODEC,
                FFNetwork::handleKnownPlayersOnClient
        );

        // NEW: Sigil preview payload
        registrar.playToClient(
                SigilPreviewPayload.TYPE,
                SigilPreviewPayload.STREAM_CODEC,
                FFNetwork::handleSigilPreview
        );

        LOG.info("[FFNetwork] Registered KnownPlayersPayload + SigilPreviewPayload");
    }

    // ------------------------------------
    // NEW: Handle sigil preview on client
    // ------------------------------------
    private static void handleSigilPreview(@NotNull SigilPreviewPayload payload,
                                           @NotNull IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    SigilPreviewScreen.open(payload.seed());
                }
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle SigilPreviewPayload", t);
            }
        });
    }

    // ------------------------------------
    // Existing known players handling
    // ------------------------------------
    private static void handleKnownPlayersOnClient(@NotNull KnownPlayersPayload payload,
                                                   @NotNull IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                KnownPlayersClientCache.update(payload.names());
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle KnownPlayersPayload", t);
            }
        });
    }

    // Existing S2C sender
    public static void sendKnownPlayersTo(@NotNull ServerPlayer player, @NotNull Collection<String> names) {
        PacketDistributor.sendToPlayer(player, new KnownPlayersPayload(new ArrayList<>(names)));
    }

    // Existing KnownPlayersPayload remains unchanged
    public record KnownPlayersPayload(List<String> names) implements CustomPacketPayload {
        public static final Type<KnownPlayersPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "known_players"));

        public static final StreamCodec<RegistryFriendlyByteBuf, KnownPlayersPayload> STREAM_CODEC =
                StreamCodec.of(KnownPlayersPayload::encode, KnownPlayersPayload::decode);

        private static void encode(RegistryFriendlyByteBuf buf, KnownPlayersPayload p) {
            buf.writeVarInt(p.names().size());
            for (String n : p.names()) buf.writeUtf(n, 1024);
        }

        private static KnownPlayersPayload decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt();
            List<String> names = new ArrayList<>(size);
            for (int i = 0; i < size; i++) names.add(buf.readUtf(1024));
            return new KnownPlayersPayload(names);
        }

        @Override
        public @NotNull Type<KnownPlayersPayload> type() { return TYPE; }
    }
}
