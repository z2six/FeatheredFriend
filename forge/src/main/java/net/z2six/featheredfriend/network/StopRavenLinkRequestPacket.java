package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record StopRavenLinkRequestPacket() {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull StopRavenLinkRequestPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            // no fields
        } catch (Throwable t) {
            LOG.error("[StopRavenLinkRequestPacket] encode failed", t);
        }
    }

    public static @NotNull StopRavenLinkRequestPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new StopRavenLinkRequestPacket();
        } catch (Throwable t) {
            LOG.error("[StopRavenLinkRequestPacket] decode failed, returning safe default", t);
            return new StopRavenLinkRequestPacket();
        }
    }
}

