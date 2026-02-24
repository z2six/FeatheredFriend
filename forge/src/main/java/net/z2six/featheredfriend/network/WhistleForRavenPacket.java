package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record WhistleForRavenPacket() {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull WhistleForRavenPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            // no fields
        } catch (Throwable t) {
            LOG.error("[WhistleForRavenPacket] encode failed", t);
        }
    }

    public static @NotNull WhistleForRavenPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new WhistleForRavenPacket();
        } catch (Throwable t) {
            LOG.error("[WhistleForRavenPacket] decode failed, returning safe default", t);
            return new WhistleForRavenPacket();
        }
    }
}

