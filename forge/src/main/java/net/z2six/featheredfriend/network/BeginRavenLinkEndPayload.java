package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record BeginRavenLinkEndPayload() {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull BeginRavenLinkEndPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            // no fields
        } catch (Throwable t) {
            LOG.error("[BeginRavenLinkEndPayload] encode failed", t);
        }
    }

    public static @NotNull BeginRavenLinkEndPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new BeginRavenLinkEndPayload();
        } catch (Throwable t) {
            LOG.error("[BeginRavenLinkEndPayload] decode failed, returning safe default", t);
            return new BeginRavenLinkEndPayload();
        }
    }
}

