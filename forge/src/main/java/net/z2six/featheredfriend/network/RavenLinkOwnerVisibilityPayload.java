package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record RavenLinkOwnerVisibilityPayload(int ownerEntityId, boolean hidden) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull RavenLinkOwnerVisibilityPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(msg.ownerEntityId());
            buf.writeBoolean(msg.hidden());
        } catch (Throwable t) {
            LOG.error("[RavenLinkOwnerVisibilityPayload] encode failed", t);
        }
    }

    public static @NotNull RavenLinkOwnerVisibilityPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new RavenLinkOwnerVisibilityPayload(buf.readVarInt(), buf.readBoolean());
        } catch (Throwable t) {
            LOG.error("[RavenLinkOwnerVisibilityPayload] decode failed, returning safe default", t);
            return new RavenLinkOwnerVisibilityPayload(-1, false);
        }
    }
}

