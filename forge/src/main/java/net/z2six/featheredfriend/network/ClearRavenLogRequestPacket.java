package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record ClearRavenLogRequestPacket() {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull ClearRavenLogRequestPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            // no fields
        } catch (Throwable t) {
            LOG.error("[ClearRavenLogRequestPacket] encode failed", t);
        }
    }

    public static @NotNull ClearRavenLogRequestPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new ClearRavenLogRequestPacket();
        } catch (Throwable t) {
            LOG.error("[ClearRavenLogRequestPacket] decode failed, returning safe default", t);
            return new ClearRavenLogRequestPacket();
        }
    }
}

