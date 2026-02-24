package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record ConfirmRavenChestDepositPacket(int ravenEntityId,
                                             @NotNull String dimensionId,
                                             long blockPos,
                                             int actionId) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull ConfirmRavenChestDepositPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(msg.ravenEntityId());
            buf.writeUtf(msg.dimensionId(), 128);
            buf.writeLong(msg.blockPos());
            buf.writeVarInt(msg.actionId());
        } catch (Throwable t) {
            LOG.error("[ConfirmRavenChestDepositPacket] encode failed", t);
        }
    }

    public static @NotNull ConfirmRavenChestDepositPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            int id = buf.readVarInt();
            String dim = buf.readUtf(128);
            long pos = buf.readLong();
            int actionId = buf.readVarInt();
            return new ConfirmRavenChestDepositPacket(id, dim, pos, actionId);
        } catch (Throwable t) {
            LOG.error("[ConfirmRavenChestDepositPacket] decode failed, returning safe default", t);
            return new ConfirmRavenChestDepositPacket(-1, "minecraft:overworld", BlockPos.ZERO.asLong(), 0);
        }
    }
}

