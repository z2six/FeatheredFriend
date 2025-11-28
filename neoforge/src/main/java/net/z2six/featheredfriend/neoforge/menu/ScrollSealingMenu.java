// neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/ScrollSealingMenu.java
package net.z2six.featheredfriend.neoforge.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

/**
 * ScrollSealingMenu
 *
 * Backend for the Scroll Sealing GUI.
 * For now:
 *  - Has a single Ender Pearl slot (no player inventory grid).
 *  - Any pearl left behind is safely dropped/returned when the menu closes.
 */
public class ScrollSealingMenu extends AbstractContainerMenu {

    private static final Logger LOG = LogUtils.getLogger();

    // Slot index of the Ender Pearl slot
    public static final int PEARL_SLOT_INDEX = 0;

    // Layout constants (relative to GUI background)
    public static final int PEARL_SLOT_X = 20;
    public static final int PEARL_SLOT_Y = 20;

    private final Container pearlContainer;

    public ScrollSealingMenu(int containerId, Inventory playerInventory) {
        super(FFNeoForgeMenus.SCROLL_SEALING_MENU.get(), containerId);

        LOG.debug("[ScrollSealingMenu] Creating menu id={} for player={}",
                containerId, playerInventory.player.getGameProfile().getName());

        this.pearlContainer = new SimpleContainer(1);

        // Ender Pearl-only slot
        this.addSlot(new Slot(pearlContainer, PEARL_SLOT_INDEX, PEARL_SLOT_X, PEARL_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                boolean allowed = stack.is(Items.ENDER_PEARL);
                if (!allowed && !stack.isEmpty()) {
                    LOG.debug("[ScrollSealingMenu] Rejecting non-pearl stack {} in pearl slot", stack);
                }
                return allowed;
            }
        });

        // NOTE:
        // We deliberately do NOT add the player's inventory/hotbar slots here.
        // The screen will only show the single pearl slot; the player can still
        // drag items from their hotbar into it.
    }

    @Override
    public boolean stillValid(Player player) {
        // No specific position to validate against (this is an item-based GUI),
        // so we just keep it always valid while open on the client.
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // With only a single custom slot and no player inventory slots,
        // shift-click transfer is intentionally disabled.
        try {
            LOG.debug("[ScrollSealingMenu] quickMoveStack called with index={}, but shift-click is disabled", index);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingMenu] quickMoveStack logging failed", t);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        try {
            // Safety: drop/return any items left in the pearl container, to prevent loss or dupes.
            this.clearContainer(player, this.pearlContainer);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingMenu] Error while clearing pearl container on close", t);
        }
    }
}
