package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record StartRavenLinkPayload(int ravenEntityId,
                                    int durationTicks,
                                    double anchorX,
                                    double anchorY,
                                    double anchorZ,
                                    float anchorYaw,
                                    float anchorPitch) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull StartRavenLinkPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(msg.ravenEntityId());
            buf.writeVarInt(msg.durationTicks());
            buf.writeDouble(msg.anchorX());
            buf.writeDouble(msg.anchorY());
            buf.writeDouble(msg.anchorZ());
            buf.writeFloat(msg.anchorYaw());
            buf.writeFloat(msg.anchorPitch());
        } catch (Throwable t) {
            LOG.error("[StartRavenLinkPayload] encode failed", t);
        }
    }

    public static @NotNull StartRavenLinkPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new StartRavenLinkPayload(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat()
            );
        } catch (Throwable t) {
            LOG.error("[StartRavenLinkPayload] decode failed, returning safe default", t);
            return new StartRavenLinkPayload(-1, 0, 0.0, 0.0, 0.0, 0.0F, 0.0F);
        }
    }
}

