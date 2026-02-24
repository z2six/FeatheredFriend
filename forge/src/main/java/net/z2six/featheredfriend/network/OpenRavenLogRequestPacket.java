package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record OpenRavenLogRequestPacket() {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull OpenRavenLogRequestPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            // no fields
        } catch (Throwable t) {
            LOG.error("[OpenRavenLogRequestPacket] encode failed", t);
        }
    }

    public static @NotNull OpenRavenLogRequestPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new OpenRavenLogRequestPacket();
        } catch (Throwable t) {
            LOG.error("[OpenRavenLogRequestPacket] decode failed, returning safe default", t);
            return new OpenRavenLogRequestPacket();
        }
    }
}

