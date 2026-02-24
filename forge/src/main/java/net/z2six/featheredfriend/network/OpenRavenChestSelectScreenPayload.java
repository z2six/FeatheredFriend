package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.z2six.featheredfriend.network.RavenChestSelectAction;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public record OpenRavenChestSelectScreenPayload(int ravenEntityId,
                                                @NotNull List<RavenChestChoiceInfo> choices,
                                                int actionId) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull OpenRavenChestSelectScreenPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(msg.ravenEntityId());
            buf.writeVarInt(msg.actionId());
            List<RavenChestChoiceInfo> list = msg.choices() == null ? List.of() : msg.choices();
            buf.writeVarInt(list.size());
            for (RavenChestChoiceInfo c : list) {
                if (c == null) {
                    buf.writeUtf("minecraft:overworld", 128);
                    buf.writeLong(BlockPos.ZERO.asLong());
                    buf.writeUtf("", 128);
                    buf.writeBoolean(false);
                    continue;
                }
                buf.writeUtf(c.dimensionId(), 128);
                buf.writeLong(c.blockPos());
                buf.writeUtf(c.label(), 128);
                buf.writeBoolean(c.available());
            }
        } catch (Throwable t) {
            LOG.error("[OpenRavenChestSelectScreenPayload] encode failed", t);
        }
    }

    public static @NotNull OpenRavenChestSelectScreenPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            int ravenId = buf.readVarInt();
            int actionId = buf.readVarInt();
            int size = Math.max(0, Math.min(1024, buf.readVarInt()));
            List<RavenChestChoiceInfo> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String dim = buf.readUtf(128);
                long pos = buf.readLong();
                String label = buf.readUtf(128);
                boolean available = buf.readBoolean();
                list.add(new RavenChestChoiceInfo(dim, pos, label, available));
            }
            return new OpenRavenChestSelectScreenPayload(ravenId, list, actionId);
        } catch (Throwable t) {
            LOG.error("[OpenRavenChestSelectScreenPayload] decode failed, returning safe default", t);
            return new OpenRavenChestSelectScreenPayload(
                    -1,
                    List.of(),
                    RavenChestSelectAction.ENDERPACK_DEPOSIT.id()
            );
        }
    }
}

