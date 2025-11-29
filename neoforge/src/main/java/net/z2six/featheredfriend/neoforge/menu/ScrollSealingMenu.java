// neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/ScrollSealingMenu.java
package net.z2six.featheredfriend.neoforge.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

/**
 * ScrollSealingMenu
 *
 * Minimal backend for the Scroll Sealing GUI.
 * - Contains no custom slots or player inventory slots.
 * - Exists only so we can use an AbstractContainerScreen on the client.
 */
public class ScrollSealingMenu extends AbstractContainerMenu {

    private static final Logger LOG = LogUtils.getLogger();

    public ScrollSealingMenu(int containerId, Inventory playerInventory) {
        super(FFNeoForgeMenus.SCROLL_SEALING_MENU.get(), containerId);
        LOG.debug("[ScrollSealingMenu] Creating menu id={} for player={}",
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
