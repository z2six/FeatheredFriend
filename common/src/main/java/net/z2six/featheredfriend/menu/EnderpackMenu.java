package net.z2six.featheredfriend.menu;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.item.EnderpackSharedStorage;
import net.z2six.featheredfriend.item.EnderpackStorage;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Item-backed 3x9 container for Enderpack contents.
 */
public class EnderpackMenu extends AbstractContainerMenu {

    private static final int ROWS = 3;
    private static final int COLS = 9;
    private static final int SLOT_COUNT = ROWS * COLS;

    private final Container enderpackContainer = new SimpleContainer(SLOT_COUNT);
    private final Inventory playerInventory;

    private @Nullable ServerPlayer boundPlayer;
    private @Nullable HolderLookup.Provider registries;

    public EnderpackMenu(int containerId, @NotNull Inventory playerInventory) {
        super(Services.PLATFORM.getEnderpackMenuType(), containerId);
        this.playerInventory = playerInventory;

        // Enderpack slots (3x9)
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = col + row * COLS;
                int x = 8 + col * 18;
                int y = 18 + row * 18;
                this.addSlot(new Slot(this.enderpackContainer, index, x, y) {
                    @Override
                    public boolean mayPlace(@NotNull ItemStack stack) {
                        return !EnderpackStorage.isEnderpack(stack);
                    }
                });
            }
        }

        // Player inventory (3x9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9 + 9;
                int x = 8 + col * 18;
                int y = 84 + row * 18;
                this.addSlot(new Slot(playerInventory, index, x, y));
            }
        }

        // Hotbar (1x9)
        for (int col = 0; col < 9; col++) {
            int x = 8 + col * 18;
            int y = 142;
            this.addSlot(new Slot(playerInventory, col, x, y));
        }
    }

    public void bindServerStorage(@NotNull ServerPlayer player,
                                  @NotNull HolderLookup.Provider registries) {
        this.boundPlayer = player;
        this.registries = registries;
        loadFromSharedStorage();
        sanitizeForbiddenNestedEnderpacks();
    }

    private void loadFromSharedStorage() {
        try {
            if (this.boundPlayer == null || this.registries == null) {
                return;
            }
            List<ItemStack> loaded = EnderpackSharedStorage.load(this.boundPlayer, this.registries);
            for (int i = 0; i < SLOT_COUNT; i++) {
                ItemStack s = (i < loaded.size()) ? loaded.get(i) : ItemStack.EMPTY;
                this.enderpackContainer.setItem(i, s == null ? ItemStack.EMPTY : s.copy());
            }
        } catch (Throwable ignored) {
        }
    }

    private void sanitizeForbiddenNestedEnderpacks() {
        try {
            Player player = this.playerInventory.player;
            if (player == null || player.level().isClientSide()) {
                return;
            }

            for (int i = 0; i < SLOT_COUNT; i++) {
                ItemStack s = this.enderpackContainer.getItem(i);
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (!EnderpackStorage.isEnderpack(s)) {
                    continue;
                }

                ItemStack toRefund = s.copy();
                this.enderpackContainer.setItem(i, ItemStack.EMPTY);

                if (!player.getInventory().add(toRefund)) {
                    player.drop(toRefund, false);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveToSharedStorage(boolean sanitizeFirst) {
        try {
            if (this.boundPlayer == null || this.registries == null) {
                return;
            }

            if (sanitizeFirst) {
                sanitizeForbiddenNestedEnderpacks();
            }

            net.minecraft.core.NonNullList<ItemStack> toSave =
                    net.minecraft.core.NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
            for (int i = 0; i < SLOT_COUNT; i++) {
                ItemStack s = this.enderpackContainer.getItem(i);
                toSave.set(i, (s == null || s.isEmpty()) ? ItemStack.EMPTY : s.copy());
            }

            EnderpackSharedStorage.save(this.boundPlayer, toSave, this.registries);
        } catch (Throwable ignored) {
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
                if (EnderpackStorage.isEnderpack(source)) {
                    return ItemStack.EMPTY;
                }
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
        return true;
    }

    @Override
    public void slotsChanged(@NotNull Container container) {
        super.slotsChanged(container);
        if (!this.playerInventory.player.level().isClientSide()) {
            saveToSharedStorage(false);
        }
    }

    @Override
    public void removed(@NotNull Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) {
            saveToSharedStorage(true);
        }
    }

    public @NotNull Container getEnderpackContainer() {
        return this.enderpackContainer;
    }

    public @NotNull Inventory getPlayerInventory() {
        return this.playerInventory;
    }
}
