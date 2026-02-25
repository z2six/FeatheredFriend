package net.z2six.featheredfriend.item;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.platform.Services;
import net.z2six.featheredfriend.registry.FFItems;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-authoritative, player-bound Enderpack storage.
 *
 * Enderpack items are treated as access keys; contents live on the player.
 */
public final class EnderpackSharedStorage {

    public static final int SLOT_COUNT = EnderpackStorage.SLOT_COUNT;

    private static final String ROOT_KEY = "EnderpackSharedData";

    private EnderpackSharedStorage() {
    }

    public static @NotNull List<ItemStack> load(@NotNull ServerPlayer player,
                                                @NotNull HolderLookup.Provider registries) {
        NonNullList<ItemStack> out = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

        try {
            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null || !root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return out;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
                return out;
            }

            CompoundTag sharedTag = modTag.getCompound(ROOT_KEY);
            if (sharedTag == null || sharedTag.isEmpty()) {
                return out;
            }

            ContainerHelper.loadAllItems(sharedTag, out, registries);
        } catch (Throwable ignored) {
        }

        return out;
    }

    public static void save(@NotNull ServerPlayer player,
                            @NotNull List<ItemStack> contents,
                            @NotNull HolderLookup.Provider registries) {
        try {
            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null) {
                return;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);

            NonNullList<ItemStack> toSave = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
            for (int i = 0; i < SLOT_COUNT && i < contents.size(); i++) {
                ItemStack s = contents.get(i);
                toSave.set(i, (s == null || s.isEmpty()) ? ItemStack.EMPTY : s.copy());
            }

            if (!hasAnyItems(toSave)) {
                modTag.remove(ROOT_KEY);
            } else {
                CompoundTag sharedTag = new CompoundTag();
                ContainerHelper.saveAllItems(sharedTag, toSave, registries);
                sharedTag.putInt("Size", SLOT_COUNT);
                modTag.put(ROOT_KEY, sharedTag);
            }

            root.put(Constants.MOD_ID, modTag);
        } catch (Throwable ignored) {
        }
    }

    public static boolean hasAnyItems(@NotNull ServerPlayer player,
                                      @NotNull HolderLookup.Provider registries) {
        return hasAnyItems(load(player, registries));
    }

    /**
     * Atomically snapshots shared contents for an in-flight raven deposit and clears shared storage.
     */
    public static @NotNull List<ItemStack> beginDepositSnapshot(@NotNull ServerPlayer player,
                                                                 @NotNull HolderLookup.Provider registries) {
        List<ItemStack> current = load(player, registries);
        List<ItemStack> snapshot = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack s = i < current.size() ? current.get(i) : ItemStack.EMPTY;
            snapshot.add(s == null || s.isEmpty() ? ItemStack.EMPTY : s.copy());
        }
        save(player, NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY), registries);
        return snapshot;
    }

    /**
     * Merges leftovers from an in-flight deposit back into shared storage.
     *
     * @return stacks that could not fit after merge (caller should drop/refund them)
     */
    public static @NotNull List<ItemStack> mergeIntoSharedAndGetOverflow(@NotNull ServerPlayer player,
                                                                          @NotNull List<ItemStack> leftovers,
                                                                          @NotNull HolderLookup.Provider registries) {
        List<ItemStack> shared = load(player, registries);
        List<ItemStack> work = new ArrayList<>(leftovers.size());
        for (ItemStack s : leftovers) {
            work.add(s == null || s.isEmpty() ? ItemStack.EMPTY : s.copy());
        }

        moveStacksIntoSlots(work, shared, SLOT_COUNT);
        save(player, shared, registries);

        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack s : work) {
            if (s != null && !s.isEmpty()) {
                overflow.add(s.copy());
            }
        }
        return overflow;
    }

    /**
     * One-way migration helper for old item-bound Enderpack contents.
     * Imports from the given stack only if shared storage is currently empty.
     */
    public static void migrateLegacyDataFromStackIfSharedEmpty(@NotNull ServerPlayer player,
                                                                @NotNull ItemStack stack,
                                                                @NotNull HolderLookup.Provider registries) {
        try {
            if (!FFItems.isEnderpack(stack) || hasAnyItems(player, registries)) {
                return;
            }

            List<ItemStack> legacy = EnderpackStorage.load(stack, registries);
            if (!hasAnyItems(legacy)) {
                return;
            }

            save(player, legacy, registries);
            EnderpackStorage.save(stack, NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY), registries);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasAnyItems(@NotNull List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (s != null && !s.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void moveStacksIntoSlots(@NotNull List<ItemStack> sourceStacks,
                                            @NotNull List<ItemStack> targetSlots,
                                            int slotCount) {
        for (int i = 0; i < sourceStacks.size(); i++) {
            ItemStack remaining = sourceStacks.get(i);
            if (remaining == null || remaining.isEmpty()) {
                sourceStacks.set(i, ItemStack.EMPTY);
                continue;
            }

            ItemStack work = remaining.copy();

            // Merge into compatible stacks first.
            for (int slot = 0; slot < slotCount && !work.isEmpty(); slot++) {
                ItemStack target = targetSlots.get(slot);
                if (target == null || target.isEmpty()) {
                    continue;
                }
                if (!ItemStack.isSameItemSameComponents(target, work)) {
                    continue;
                }
                int room = target.getMaxStackSize() - target.getCount();
                if (room <= 0) {
                    continue;
                }
                int toMove = Math.min(room, work.getCount());
                if (toMove <= 0) {
                    continue;
                }
                target.grow(toMove);
                work.shrink(toMove);
                targetSlots.set(slot, target);
            }

            // Then fill empty slots.
            for (int slot = 0; slot < slotCount && !work.isEmpty(); slot++) {
                ItemStack target = targetSlots.get(slot);
                if (target != null && !target.isEmpty()) {
                    continue;
                }
                int toMove = Math.min(work.getCount(), work.getMaxStackSize());
                if (toMove <= 0) {
                    continue;
                }
                ItemStack placed = work.copy();
                placed.setCount(toMove);
                targetSlots.set(slot, placed);
                work.shrink(toMove);
            }

            sourceStacks.set(i, work.isEmpty() ? ItemStack.EMPTY : work);
        }
    }
}
