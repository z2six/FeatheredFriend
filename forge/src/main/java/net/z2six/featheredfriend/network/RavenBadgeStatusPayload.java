package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record RavenBadgeStatusPayload(int baseStateId, int eventTypeId) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull RavenBadgeStatusPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(msg.baseStateId());
            buf.writeVarInt(msg.eventTypeId());
        } catch (Throwable t) {
            LOG.error("[RavenBadgeStatusPayload] encode failed", t);
        }
    }

    public static @NotNull RavenBadgeStatusPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new RavenBadgeStatusPayload(buf.readVarInt(), buf.readVarInt());
        } catch (Throwable t) {
            LOG.error("[RavenBadgeStatusPayload] decode failed, returning safe default", t);
            return new RavenBadgeStatusPayload(0, 0);
        }
    }
}

