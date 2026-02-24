package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record OpenEnderpackRequestPacket() {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull OpenEnderpackRequestPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            // no fields
        } catch (Throwable t) {
            LOG.error("[OpenEnderpackRequestPacket] encode failed", t);
        }
    }

    public static @NotNull OpenEnderpackRequestPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new OpenEnderpackRequestPacket();
        } catch (Throwable t) {
            LOG.error("[OpenEnderpackRequestPacket] decode failed, returning safe default", t);
            return new OpenEnderpackRequestPacket();
        }
    }
}

