// MainFile: forge/src/main/java/net/z2six/featheredfriend/neoforge/menu/SealStampMenu.java
package net.z2six.featheredfriend.forge.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.registry.FFForgeMenus;
import org.slf4j.Logger;

/**
 * forge/src/main/java/net/z2six/featheredfriend/neoforge/menu/SealStampMenu.java
 *
 * Updated for Step 2:
 * - Stores EXACT item slot used to open the menu.
 * - Slot = 0–35 inventory, 36 mainhand, 37 offhand.
 * - Server will write NBT directly to that slot after carving completes.
 */
public class SealStampMenu extends AbstractContainerMenu {

    private static final Logger LOG = LogUtils.getLogger();

    private final int stampSlotIndex;

    public SealStampMenu(int containerId, Inventory playerInventory, int stampSlotIndex) {
        super(FFForgeMenus.SEAL_STAMP_MENU.get(), containerId);
        this.stampSlotIndex = stampSlotIndex;

        LOG.debug("[SealStampMenu] Creating menu id={} for player={} stampSlot={}",
                containerId, playerInventory.player.getGameProfile().getName(), stampSlotIndex);
    }

    /**
     * Legacy ctor (no slot) – keeps old call sites compiling if any remain.
     * Uses -1 as "unknown slot".
     */
    public SealStampMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, -1);
    }

    /**
     * Original accessor name.
     */
    public int getStampSlotIndex() {
        return stampSlotIndex;
    }

    /**
     * New accessor used by SealStampScreen.
     */
    public int getStampSlot() {
        return stampSlotIndex;
    }

    @Override
    public boolean stillValid(Player player) {
        // Item-based GUI; always valid while open.
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // No slots -> nothing to quick-move.
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Nothing additional to clear.
    }
}
