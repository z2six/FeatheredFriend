package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record RavenLinkInputPacket(boolean forward,
                                   boolean backward,
                                   boolean left,
                                   boolean right,
                                   boolean ascend,
                                   boolean descend,
                                   float yaw,
                                   float pitch) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull RavenLinkInputPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeBoolean(msg.forward());
            buf.writeBoolean(msg.backward());
            buf.writeBoolean(msg.left());
            buf.writeBoolean(msg.right());
            buf.writeBoolean(msg.ascend());
            buf.writeBoolean(msg.descend());
            buf.writeFloat(msg.yaw());
            buf.writeFloat(msg.pitch());
        } catch (Throwable t) {
            LOG.error("[RavenLinkInputPacket] encode failed", t);
        }
    }

    public static @NotNull RavenLinkInputPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new RavenLinkInputPacket(
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readFloat(),
                    buf.readFloat()
            );
        } catch (Throwable t) {
            LOG.error("[RavenLinkInputPacket] decode failed, returning safe default", t);
            return new RavenLinkInputPacket(false, false, false, false, false, false, 0.0F, 0.0F);
        }
    }
}

