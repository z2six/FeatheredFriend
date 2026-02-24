package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record SetRavenChestLabelPacket(@NotNull String dimensionId,
                                       long blockPos,
                                       @NotNull String label) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull SetRavenChestLabelPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeUtf(msg.dimensionId(), 128);
            buf.writeLong(msg.blockPos());
            buf.writeUtf(msg.label(), 128);
        } catch (Throwable t) {
            LOG.error("[SetRavenChestLabelPacket] encode failed", t);
        }
    }

    public static @NotNull SetRavenChestLabelPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new SetRavenChestLabelPacket(
                    buf.readUtf(128),
                    buf.readLong(),
                    buf.readUtf(128)
            );
        } catch (Throwable t) {
            LOG.error("[SetRavenChestLabelPacket] decode failed, returning safe default", t);
            return new SetRavenChestLabelPacket("minecraft:overworld", BlockPos.ZERO.asLong(), "");
        }
    }
}

