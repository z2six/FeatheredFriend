// MainFile: common/src/main/java/net/z2six/featheredfriend/network/SealStampCarveResultPacket.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * // common/src/main/java/net/z2six/featheredfriend/network/SealStampCarveResultPacket.java
 *
 * C2S payload sent when the player finishes carving a seal stamp in the GUI.
 *
 * Writes into the stamp's CustomData component:
 *
 *   CustomData: {
 *     SealStamp: {
 *       Owner:   "<owner-string>",
 *       Seed:    long,
 *       Slices:  int,
 *       ShapeSet:int
 *     }
 *   }
 *
 * Slot mapping:
 *  - 0–35: inventory slots (if used)
 *  - 36:   main hand
 *  - 37:   off hand
 */
public record SealStampCarveResultPacket(
        int stampSlot,
        long seed,
        int slices,
        int style,
        String ownerName
) implements CustomPacketPayload {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Payload type & codec
    // ---------------------------------------------------------------------

    public static final Type<SealStampCarveResultPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "seal_stamp_carve_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SealStampCarveResultPacket> STREAM_CODEC =
            StreamCodec.of(SealStampCarveResultPacket::encode, SealStampCarveResultPacket::decode);

    private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                               @NotNull SealStampCarveResultPacket msg) {
        try {
            buf.writeInt(msg.stampSlot);
            buf.writeLong(msg.seed);
            buf.writeInt(msg.slices);
            buf.writeInt(msg.style);
            buf.writeUtf(msg.ownerName);
        } catch (Throwable t) {
            LOG.error("[SealStampCarveResultPacket] encode failed", t);
        }
    }

    private static @NotNull SealStampCarveResultPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
        try {
            int slot = buf.readInt();
            long seed = buf.readLong();
            int slices = buf.readInt();
            int style = buf.readInt();
            String owner = buf.readUtf();
            return new SealStampCarveResultPacket(slot, seed, slices, style, owner);
        } catch (Throwable t) {
            LOG.error("[SealStampCarveResultPacket] decode failed, returning safe default", t);
            return new SealStampCarveResultPacket(-1, 0L, 0, 0, "");
        }
    }

    @Override
    public @NotNull Type<SealStampCarveResultPacket> type() {
        return TYPE;
    }

    // ---------------------------------------------------------------------
    // Server-side handler
    // ---------------------------------------------------------------------

    public static void handle(@NotNull SealStampCarveResultPacket msg, @NotNull ServerPlayer player) {
        try {
            if (msg.stampSlot() < 0) {
                LOG.error("[SealStampCarveResultPacket] Invalid stampSlot {}", msg.stampSlot());
                return;
            }

            ItemStack stack;

            // 36 = main hand, 37 = offhand, else try inventory
            if (msg.stampSlot() == 36) {
                stack = player.getMainHandItem();
            } else if (msg.stampSlot() == 37) {
                stack = player.getOffhandItem();
            } else if (msg.stampSlot() >= 0 && msg.stampSlot() < player.getInventory().items.size()) {
                stack = player.getInventory().items.get(msg.stampSlot());
            } else {
                stack = ItemStack.EMPTY;
            }

            if (stack.isEmpty()) {
                LOG.error("[SealStampCarveResultPacket] No stack at slot {} for player {}",
                        msg.stampSlot(), player.getGameProfile().getName());
                return;
            }

            // Read existing CustomData via DataComponents.CUSTOM_DATA
            CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag root = customData.copyTag();
            if (root == null) {
                root = new CompoundTag();
            }

            // SealStamp subtree
            CompoundTag sealRoot = new CompoundTag();
            sealRoot.putString("Owner", msg.ownerName());
            sealRoot.putLong("Seed", msg.seed());
            sealRoot.putInt("Slices", msg.slices());
            sealRoot.putInt("ShapeSet", msg.style());

            root.put("SealStamp", sealRoot);

            // Write back into the CUSTOM_DATA component
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));

            LOG.info(
                    "[SealStampCarveResultPacket] Wrote seal data to slot {} for player {} (seed={} slices={} style={} owner='{}')",
                    msg.stampSlot(),
                    player.getGameProfile().getName(),
                    msg.seed(),
                    msg.slices(),
                    msg.style(),
                    msg.ownerName()
            );

        } catch (Throwable t) {
            LOG.error("[SealStampCarveResultPacket] handle failed", t);
        }
    }
}
