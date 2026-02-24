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
 * Mailbox block menu (9 slots) backed by the mailbox block entity.
 */
public class MailboxMenu extends AbstractContainerMenu {

    public static final int SLOT_COUNT = 9;

    private static final int MAILBOX_ROWS = 1;
    private static final int MAILBOX_COLS = 9;

    private final Container mailboxContainer;
    private final Inventory playerInventory;

    public MailboxMenu(int containerId, @NotNull Inventory playerInventory) {
        super(Services.PLATFORM.getMailboxMenuType(), containerId);
        this.mailboxContainer = new SimpleContainer(SLOT_COUNT);
        this.playerInventory = playerInventory;
        initSlots();
    }

    public MailboxMenu(int containerId, @NotNull Inventory playerInventory, @NotNull Container mailboxContainer) {
        super(Services.PLATFORM.getMailboxMenuType(), containerId);
        this.mailboxContainer = mailboxContainer;
        this.playerInventory = playerInventory;
        initSlots();
    }

    private void initSlots() {
        // Mailbox slots (1x9)
        for (int col = 0; col < MAILBOX_COLS; col++) {
            int index = col;
            int x = 16 + col * 18;
            int y = 16;
            this.addSlot(new Slot(this.mailboxContainer, index, x, y));
        }

        // Player inventory (3x9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9 + 9;
                int x = 16 + col * 18;
                int y = 46 + row * 18;
                this.addSlot(new Slot(playerInventory, index, x, y));
            }
        }

        // Hotbar (1x9)
        for (int col = 0; col < 9; col++) {
            int x = 16 + col * 18;
            int y = 104;
            this.addSlot(new Slot(playerInventory, col, x, y));
        }

        // Let the container know it's being opened (if supported).
        if (!playerInventory.player.level().isClientSide && playerInventory.player instanceof ServerPlayer serverPlayer) {
            try {
                this.mailboxContainer.startOpen(serverPlayer);
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

            if (index < SLOT_COUNT) {
                if (!this.moveItemStackTo(source, SLOT_COUNT, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(source, 0, SLOT_COUNT, false)) {
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
            return this.mailboxContainer.stillValid(player);
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    public void removed(@NotNull Player player) {
        super.removed(player);

        if (!player.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            try {
                this.mailboxContainer.stopOpen(serverPlayer);
            } catch (Throwable ignored) {
            }
        }
    }

    public @NotNull Container getMailboxContainer() {
        return this.mailboxContainer;
    }

    public @NotNull Inventory getPlayerInventory() {
        return this.playerInventory;
    }
}
