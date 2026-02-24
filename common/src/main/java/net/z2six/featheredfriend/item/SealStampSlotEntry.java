package net.z2six.featheredfriend.item;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a selectable seal-stamp source for UI overlays.
 *
 * Slot mapping:
 * - 0..35: player inventory
 * - 37: offhand
 * - 10000+: virtual external slots (e.g. Curios)
 */
public record SealStampSlotEntry(int slotIndex, @NotNull ItemStack stack) {

    public static final int VIRTUAL_SLOT_BASE = 10_000;

    public static boolean isVirtualSlot(int slotIndex) {
        return slotIndex >= VIRTUAL_SLOT_BASE;
    }
}
