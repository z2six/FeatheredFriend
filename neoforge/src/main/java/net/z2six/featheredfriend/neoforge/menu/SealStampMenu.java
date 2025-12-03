// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/SealStampMenu.java
package net.z2six.featheredfriend.neoforge.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/SealStampMenu.java
 *
 * SealStampMenu
 *
 * Minimal backend for the Seal Stamp carving GUI.
 * - Contains no custom slots or player inventory slots for now.
 * - Exists only so we can use an AbstractContainerScreen on the client.
 *
 * Step 2 will add actual widgets and data binding.
 */
public class SealStampMenu extends AbstractContainerMenu {

    private static final Logger LOG = LogUtils.getLogger();

    public SealStampMenu(int containerId, Inventory playerInventory) {
        super(FFNeoForgeMenus.SEAL_STAMP_MENU.get(), containerId);
        LOG.debug("[SealStampMenu] Creating menu id={} for player={}",
                containerId, playerInventory.player.getGameProfile().getName());
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
        // Nothing to clear; no internal inventory.
    }
}
