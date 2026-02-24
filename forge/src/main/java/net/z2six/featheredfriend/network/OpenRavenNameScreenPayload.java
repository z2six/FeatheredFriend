package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record OpenRavenNameScreenPayload(int ravenEntityId) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull OpenRavenNameScreenPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(msg.ravenEntityId());
        } catch (Throwable t) {
            LOG.error("[OpenRavenNameScreenPayload] encode failed", t);
        }
    }

    public static @NotNull OpenRavenNameScreenPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new OpenRavenNameScreenPayload(buf.readVarInt());
        } catch (Throwable t) {
            LOG.error("[OpenRavenNameScreenPayload] decode failed, returning safe default", t);
            return new OpenRavenNameScreenPayload(-1);
        }
    }
}

