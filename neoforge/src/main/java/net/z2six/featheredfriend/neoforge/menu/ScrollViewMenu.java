// neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/ScrollViewMenu.java
package net.z2six.featheredfriend.neoforge.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/ScrollViewMenu.java
 *
 * ScrollViewMenu
 *
 * Placeholder menu for viewing scrolls (sealed / opened).
 * - No slots.
 * - Always valid while the player is alive.
 */
public class ScrollViewMenu extends AbstractContainerMenu {

    private static final Logger LOG = LogUtils.getLogger();

    private final Inventory playerInventory;

    public ScrollViewMenu(int containerId, @NotNull Inventory playerInventory) {
        super(FFNeoForgeMenus.SCROLL_VIEW_MENU.get(), containerId);
        this.playerInventory = playerInventory;
        LOG.debug("[ScrollViewMenu] Constructed with containerId={} for player={}",
                containerId,
                playerInventory.player != null ? playerInventory.player.getGameProfile().getName() : "unknown");
    }

    public Inventory getPlayerInventory() {
        return playerInventory;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        // Placeholder: always valid while the player exists.
        boolean valid = !player.isRemoved();
        if (!valid) {
            LOG.debug("[ScrollViewMenu] stillValid: player is removed; returning false");
        }
        return valid;
    }

    @Override
    @NotNull
    public ItemStack quickMoveStack(@NotNull Player player, int index) {
        // No slots in this placeholder menu, so shift-click does nothing.
        try {
            LOG.debug("[ScrollViewMenu] quickMoveStack called (index={}) but menu has no slots; returning EMPTY", index);
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] quickMoveStack() logging failed", t);
        }
        return ItemStack.EMPTY;
    }
}
