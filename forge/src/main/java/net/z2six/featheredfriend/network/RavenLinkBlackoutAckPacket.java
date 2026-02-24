package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record RavenLinkBlackoutAckPacket() {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull RavenLinkBlackoutAckPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            // no fields
        } catch (Throwable t) {
            LOG.error("[RavenLinkBlackoutAckPacket] encode failed", t);
        }
    }

    public static @NotNull RavenLinkBlackoutAckPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new RavenLinkBlackoutAckPacket();
        } catch (Throwable t) {
            LOG.error("[RavenLinkBlackoutAckPacket] decode failed, returning safe default", t);
            return new RavenLinkBlackoutAckPacket();
        }
    }
}

