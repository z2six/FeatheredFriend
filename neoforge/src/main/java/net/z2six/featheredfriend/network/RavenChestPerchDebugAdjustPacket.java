package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * C2S debug packet for live raven perch transform tweaks.
 */
public record RavenChestPerchDebugAdjustPacket(
        int ravenEntityId,
        double offsetX,
        double offsetY,
        double offsetZ,
        float yaw,
        float pitch
) implements CustomPacketPayload {

    private static final Logger LOG = LogUtils.getLogger();

    public static final Type<RavenChestPerchDebugAdjustPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "raven_chest_perch_debug_adjust"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RavenChestPerchDebugAdjustPacket> STREAM_CODEC =
            StreamCodec.of(RavenChestPerchDebugAdjustPacket::encode, RavenChestPerchDebugAdjustPacket::decode);

    private static void encode(@NotNull RegistryFriendlyByteBuf buf, @NotNull RavenChestPerchDebugAdjustPacket payload) {
        try {
            buf.writeVarInt(payload.ravenEntityId());
            buf.writeDouble(payload.offsetX());
            buf.writeDouble(payload.offsetY());
            buf.writeDouble(payload.offsetZ());
            buf.writeFloat(payload.yaw());
            buf.writeFloat(payload.pitch());
        } catch (Throwable t) {
            LOG.error("[RavenChestPerchDebugAdjustPacket] encode failed", t);
        }
    }

    private static @NotNull RavenChestPerchDebugAdjustPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
        try {
            return new RavenChestPerchDebugAdjustPacket(
                    buf.readVarInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat()
            );
        } catch (Throwable t) {
            LOG.error("[RavenChestPerchDebugAdjustPacket] decode failed", t);
            return new RavenChestPerchDebugAdjustPacket(-1, 0.5D, 1.6D, 0.5D, 0.0F, 0.0F);
        }
    }

    @Override
    public @NotNull Type<RavenChestPerchDebugAdjustPacket> type() {
        return TYPE;
    }
}
