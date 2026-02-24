package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record RequestKnownPlayersPacket() {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull RequestKnownPlayersPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            // no fields
        } catch (Throwable t) {
            LOG.error("[RequestKnownPlayersPacket] encode failed", t);
        }
    }

    public static @NotNull RequestKnownPlayersPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new RequestKnownPlayersPacket();
        } catch (Throwable t) {
            LOG.error("[RequestKnownPlayersPacket] decode failed, returning safe default", t);
            return new RequestKnownPlayersPacket();
        }
    }
}

