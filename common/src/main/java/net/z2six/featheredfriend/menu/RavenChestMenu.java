package net.z2six.featheredfriend.menu;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;

/**
 * Custom container menu for the Raven Chest (Suspicious Chest) with a custom GUI layout.
 */
public class RavenChestMenu extends AbstractContainerMenu {

    public static final int CHEST_SLOT_COUNT = 27;

    private final Container chestContainer;
    private final Inventory playerInventory;

    public RavenChestMenu(int containerId, @NotNull Inventory playerInventory) {
        super(Services.PLATFORM.getRavenChestMenuType(), containerId);
        this.chestContainer = new SimpleContainer(CHEST_SLOT_COUNT);
        this.playerInventory = playerInventory;
        initSlots();
    }

    public RavenChestMenu(int containerId, @NotNull Inventory playerInventory, @NotNull Container chestContainer) {
        super(Services.PLATFORM.getRavenChestMenuType(), containerId);
        this.chestContainer = chestContainer;
        this.playerInventory = playerInventory;
        initSlots();
    }

    public @NotNull Container getChestContainer() {
        return this.chestContainer;
    }

    private void initSlots() {
        // Chest inventory (3x9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                int x = 16 + col * 18;
                int y = 16 + row * 18;
                this.addSlot(new Slot(this.chestContainer, index, x, y));
            }
        }

        // Player inventory (3x9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9 + 9;
                int x = 16 + col * 18;
                int y = 82 + row * 18;
                this.addSlot(new Slot(this.playerInventory, index, x, y));
            }
        }

        // Hotbar (1x9)
        for (int col = 0; col < 9; col++) {
            int x = 16 + col * 18;
            int y = 140;
            this.addSlot(new Slot(this.playerInventory, col, x, y));
        }

        if (!playerInventory.player.level().isClientSide && playerInventory.player instanceof ServerPlayer serverPlayer) {
            try {
                this.chestContainer.startOpen(serverPlayer);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        try {
            if (index < 0 || index >= this.slots.size()) {
                return ItemStack.EMPTY;
            }

            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) {
                return ItemStack.EMPTY;
            }

            ItemStack source = slot.getItem();
            ItemStack original = source.copy();

            if (index < CHEST_SLOT_COUNT) {
                if (!this.moveItemStackTo(source, CHEST_SLOT_COUNT, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(source, 0, CHEST_SLOT_COUNT, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (source.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            slot.onTake(player, source);
            return original;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        try {
            return this.chestContainer.stillValid(player);
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    public void removed(@NotNull Player player) {
        super.removed(player);

        if (!player.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            try {
                this.chestContainer.stopOpen(serverPlayer);
            } catch (Throwable ignored) {
            }
        }
    }
}
