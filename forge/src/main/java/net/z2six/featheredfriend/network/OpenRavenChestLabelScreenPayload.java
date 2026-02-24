package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record OpenRavenChestLabelScreenPayload(@NotNull String dimensionId,
                                               long blockPos,
                                               @NotNull String currentLabel) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull OpenRavenChestLabelScreenPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeUtf(msg.dimensionId(), 128);
            buf.writeLong(msg.blockPos());
            buf.writeUtf(msg.currentLabel(), 128);
        } catch (Throwable t) {
            LOG.error("[OpenRavenChestLabelScreenPayload] encode failed", t);
        }
    }

    public static @NotNull OpenRavenChestLabelScreenPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new OpenRavenChestLabelScreenPayload(
                    buf.readUtf(128),
                    buf.readLong(),
                    buf.readUtf(128)
            );
        } catch (Throwable t) {
            LOG.error("[OpenRavenChestLabelScreenPayload] decode failed, returning safe default", t);
            return new OpenRavenChestLabelScreenPayload("minecraft:overworld", BlockPos.ZERO.asLong(), "");
        }
    }
}

