package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record StopRavenLinkPayload() {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull StopRavenLinkPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            // no fields
        } catch (Throwable t) {
            LOG.error("[StopRavenLinkPayload] encode failed", t);
        }
    }

    public static @NotNull StopRavenLinkPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new StopRavenLinkPayload();
        } catch (Throwable t) {
            LOG.error("[StopRavenLinkPayload] decode failed, returning safe default", t);
            return new StopRavenLinkPayload();
        }
    }
}

