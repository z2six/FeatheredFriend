// MainFile: common/src/main/java/net/z2six/featheredfriend/network/SealStampCarveResultPacket.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.Constants;

import org.slf4j.Logger;

/**
 * // common/src/main/java/net/z2six/featheredfriend/network/SealStampCarveResultPacket.java
 *
 * C2S payload sent when the player finishes carving a seal stamp in the GUI.
 *
 * Writes into the stamp's CustomData payload:
 *
 *   CustomData: {
 *     SealStamp: {
 *       Owner:   "owner-string",
 *       Seed:    long,
 *       Slices:  int,
 *       ShapeSet:int
 *     }
 *   }
 *
 * Forge 1.20.1 note:
 * - We store the former DataComponents.CUSTOM_DATA payload under ItemStack tag sub-compound "CustomData".
 * - Uses FriendlyByteBuf for encode/decode.
 */
public record SealStampCarveResultPacket(
        int stampSlot,
        long seed,
        int slices,
        int style,
        String ownerName
) {

    private static final Logger LOG = LogUtils.getLogger();

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "seal_stamp_carve_result");

    // Where we store the former "minecraft:custom_data" payload in 1.20.1
    private static final String STACK_CUSTOM_DATA_KEY = "CustomData";

    // ---------------------------------------------------------------------
    // Codec
    // ---------------------------------------------------------------------

    public static void encode(SealStampCarveResultPacket msg, FriendlyByteBuf buf) {
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

    public static SealStampCarveResultPacket decode(FriendlyByteBuf buf) {
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

    // ---------------------------------------------------------------------
    // Server-side handler
    // ---------------------------------------------------------------------

    public static void handle(SealStampCarveResultPacket msg, ServerPlayer player) {
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

            // Read existing CustomData payload from stack tag sub-compound "CustomData"
            CompoundTag root = getCustomDataCopy(stack);
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

            // Write back into the custom payload
            setCustomData(stack, root);

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

    // ---------------------------------------------------------------------
    // CustomData compatibility (1.20.1)
    // ---------------------------------------------------------------------

    private static CompoundTag getCustomDataCopy(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            if (tag == null) return new CompoundTag();
            if (!tag.contains(STACK_CUSTOM_DATA_KEY, Tag.TAG_COMPOUND)) return new CompoundTag();
            CompoundTag cd = tag.getCompound(STACK_CUSTOM_DATA_KEY);
            return cd == null ? new CompoundTag() : cd.copy();
        } catch (Throwable t) {
            LOG.error("[SealStampCarveResultPacket] getCustomDataCopy failed", t);
            return new CompoundTag();
        }
    }

    private static void setCustomData(ItemStack stack, CompoundTag customDataRoot) {
        try {
            CompoundTag tag = stack.getOrCreateTag();
            tag.put(STACK_CUSTOM_DATA_KEY, customDataRoot);
        } catch (Throwable t) {
            LOG.error("[SealStampCarveResultPacket] setCustomData failed", t);
        }
    }
}
