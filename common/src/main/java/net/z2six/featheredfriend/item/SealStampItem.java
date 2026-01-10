// MainFile: common/src/main/java/net/z2six/featheredfriend/item/SealStampItem.java
package net.z2six.featheredfriend.item;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

/**
 * // common/src/main/java/net/z2six/featheredfriend/content/item/SealStampItem.java
 *
 * SealStampItem
 *
 * - Right-click (RMB) with this item in hand opens the Seal-carving GUI,
 *   but only if the stamp is not yet etched.
 * - "Etched" state is determined purely by data stored in custom per-stack data.
 *
 * Forge 1.20.1 note:
 * - DataComponents/CUSTOM_DATA do not exist. We store the same custom payload under
 *   the ItemStack's tag as a dedicated sub-compound named "CustomData".
 */
public class SealStampItem extends Item {

    private static final Logger LOG = LogUtils.getLogger();

    // NBT keys for seal data stored in the CUSTOM_DATA component
    private static final String NBT_SEAL_ROOT   = "SealStamp";
    private static final String NBT_OWNER       = "Owner";
    private static final String NBT_SEED        = "Seed";
    private static final String NBT_SLICES      = "Slices";
    private static final String NBT_SHAPESET    = "ShapeSet";

    // Where we store the former "minecraft:custom_data" payload in 1.20.1
    private static final String STACK_CUSTOM_DATA_KEY = "CustomData";

    public SealStampItem(@NotNull Properties properties) {
        super(properties);
        LOG.debug("[SealStampItem] Constructed");
    }

    // ---------------------------------------------------------------------
    // Use behaviour (RMB)
    // ---------------------------------------------------------------------

    @Override
    @NotNull
    public InteractionResultHolder<ItemStack> use(@NotNull Level level,
                                                  @NotNull net.minecraft.world.entity.player.Player player,
                                                  @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        try {
            // Client side: immediately return SUCCESS so the animation plays.
            if (level.isClientSide) {
                LOG.debug("[SealStampItem] use() on client; returning SUCCESS (no logic client-side)");
                return InteractionResultHolder.success(stack);
            }

            if (!(player instanceof ServerPlayer serverPlayer)) {
                LOG.warn("[SealStampItem] use() called on non-ServerPlayer on logical server; returning PASS");
                return InteractionResultHolder.pass(stack);
            }

            boolean etched = isEtched(stack);
            LOG.debug("[SealStampItem] use() on server for {}. Etched={}",
                    serverPlayer.getGameProfile().getName(), etched);

            if (etched) {
                // STEP 1: For now, just log. Later this will perform sealing behaviour.
                LOG.info("[SealStampItem] Stamp is already etched; future behaviour will seal scrolls, etc.");
                return InteractionResultHolder.success(stack);
            }

            // Not yet etched: open dedicated Seal Stamp placeholder GUI.
            LOG.info("[SealStampItem] Opening Seal Stamp carving GUI (placeholder) for player={}",
                    serverPlayer.getGameProfile().getName());

            Services.PLATFORM.openSealStampScreen(serverPlayer);

            return InteractionResultHolder.success(stack);
        } catch (Throwable t) {
            LOG.error("[SealStampItem] use() failed; returning PASS to avoid crashes", t);
            return InteractionResultHolder.pass(stack);
        }
    }

    // ---------------------------------------------------------------------
    // Etched state (uses custom data payload)
    // ---------------------------------------------------------------------

    /**
     * Returns true if this stamp already has an etched seal.
     *
     * Layout in custom data:
     *
     *   CustomData: {
     *     SealStamp: {
     *       Owner:   "uuid-string",
     *       Seed:    long,
     *       Slices:  int,
     *       ShapeSet:int
     *     }
     *   }
     */
    public static boolean isEtched(@NotNull ItemStack stack) {
        try {
            CompoundTag root = getCustomDataCopy(stack);
            if (root == null || root.isEmpty()) {
                LOG.debug("[SealStampItem] isEtched: no CustomData on stack");
                return false;
            }

            if (!root.contains(NBT_SEAL_ROOT)) {
                LOG.debug("[SealStampItem] isEtched: no '{}' compound found", NBT_SEAL_ROOT);
                return false;
            }

            CompoundTag sealTag = root.getCompound(NBT_SEAL_ROOT);
            if (sealTag.isEmpty()) {
                LOG.debug("[SealStampItem] isEtched: '{}' compound is empty", NBT_SEAL_ROOT);
                return false;
            }

            boolean hasOwner  = sealTag.contains(NBT_OWNER);
            boolean hasSeed   = sealTag.contains(NBT_SEED);
            boolean hasSlices = sealTag.contains(NBT_SLICES);
            boolean hasSet    = sealTag.contains(NBT_SHAPESET);

            boolean etched = hasOwner && hasSeed && hasSlices && hasSet;

            LOG.debug(
                    "[SealStampItem] isEtched: owner={} seed={} slices={} shapeset={} -> {}",
                    hasOwner, hasSeed, hasSlices, hasSet, etched
            );

            return etched;
        } catch (Throwable t) {
            LOG.error("[SealStampItem] isEtched: failed, treating as unetched", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Tooltip (very minimal for now; real owner display will be step 3)
    // ---------------------------------------------------------------------

    @Override
    public void appendHoverText(@NotNull ItemStack stack,
                                @Nullable Level level,
                                @NotNull List<Component> tooltip,
                                @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        try {
            if (isEtched(stack)) {
                // Placeholder text – later we'll resolve and show the actual owner.
                tooltip.add(Component.literal("§7Etched seal"));
            } else {
                tooltip.add(Component.literal("§7Uncarved"));
            }
        } catch (Throwable t) {
            LOG.error("[SealStampItem] appendHoverText failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers (1.20.1 compatibility)
    // ---------------------------------------------------------------------

    private static @NotNull CompoundTag getCustomDataCopy(@NotNull ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            if (tag == null) {
                return new CompoundTag();
            }
            if (!tag.contains(STACK_CUSTOM_DATA_KEY, CompoundTag.TAG_COMPOUND)) {
                return new CompoundTag();
            }
            CompoundTag cd = tag.getCompound(STACK_CUSTOM_DATA_KEY);
            return cd == null ? new CompoundTag() : cd.copy();
        } catch (Throwable t) {
            LOG.error("[SealStampItem] getCustomDataCopy failed", t);
            return new CompoundTag();
        }
    }
}
