package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record RavenLinkStatePayload(int ravenEntityId,
                                    double x,
                                    double y,
                                    double z,
                                    float yaw,
                                    float pitch,
                                    int chunksSentThisTick,
                                    int chunksPending,
                                    int chunksLoaded,
                                    int streamRadius) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull RavenLinkStatePayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(msg.ravenEntityId());
            buf.writeDouble(msg.x());
            buf.writeDouble(msg.y());
            buf.writeDouble(msg.z());
            buf.writeFloat(msg.yaw());
            buf.writeFloat(msg.pitch());
            buf.writeVarInt(msg.chunksSentThisTick());
            buf.writeVarInt(msg.chunksPending());
            buf.writeVarInt(msg.chunksLoaded());
            buf.writeVarInt(msg.streamRadius());
        } catch (Throwable t) {
            LOG.error("[RavenLinkStatePayload] encode failed", t);
        }
    }

    public static @NotNull RavenLinkStatePayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new RavenLinkStatePayload(
                    buf.readVarInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt()
            );
        } catch (Throwable t) {
            LOG.error("[RavenLinkStatePayload] decode failed, returning safe default", t);
            return new RavenLinkStatePayload(-1, 0.0, 0.0, 0.0, 0.0F, 0.0F, 0, 0, 0, 0);
        }
    }
}

