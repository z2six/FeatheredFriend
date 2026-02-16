package net.z2six.featheredfriend.item;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.z2six.featheredfriend.registry.FFItems;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Server-authoritative storage codec for Enderpack contents.
 */
public final class EnderpackStorage {

    public static final int SLOT_COUNT = 27;

    private static final String ROOT_KEY = "EnderpackData";

    private EnderpackStorage() {
    }

    public static boolean isEnderpack(@NotNull ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == FFItems.ENDERPACK.get();
    }

    public static @NotNull List<ItemStack> load(@NotNull ItemStack stack, @NotNull HolderLookup.Provider registries) {
        net.minecraft.core.NonNullList<ItemStack> out =
                net.minecraft.core.NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

        try {
            if (!isEnderpack(stack)) {
                return out;
            }

            CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            if (customData.isEmpty()) {
                return out;
            }

            CompoundTag root = customData.copyTag();
            if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
                return out;
            }

            CompoundTag packTag = root.getCompound(ROOT_KEY);
            if (packTag.isEmpty()) {
                return out;
            }

            ContainerHelper.loadAllItems(packTag, out, registries);
        } catch (Throwable ignored) {
        }

        return out;
    }

    public static void save(@NotNull ItemStack stack,
                            @NotNull List<ItemStack> contents,
                            @NotNull HolderLookup.Provider registries) {
        try {
            if (!isEnderpack(stack)) {
                return;
            }

            CustomData existing = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag root = existing.copyTag();
            if (root == null) {
                root = new CompoundTag();
            }

            net.minecraft.core.NonNullList<ItemStack> toSave =
                    net.minecraft.core.NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
            for (int i = 0; i < SLOT_COUNT && i < contents.size(); i++) {
                ItemStack s = contents.get(i);
                toSave.set(i, (s == null || s.isEmpty()) ? ItemStack.EMPTY : s.copy());
            }

            boolean hasAny = false;
            for (ItemStack s : toSave) {
                if (!s.isEmpty()) {
                    hasAny = true;
                    break;
                }
            }

            if (!hasAny) {
                root.remove(ROOT_KEY);
            } else {
                CompoundTag packTag = new CompoundTag();
                ContainerHelper.saveAllItems(packTag, toSave, registries);
                root.putInt("Size", SLOT_COUNT);
                root.put(ROOT_KEY, packTag);
            }

            if (root.isEmpty()) {
                stack.remove(DataComponents.CUSTOM_DATA);
            } else {
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
            }
        } catch (Throwable ignored) {
        }
    }
}
