package net.z2six.featheredfriend.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Compatibility helper for per-item custom data.
 *
 * In 1.21+ this used DataComponents.CUSTOM_DATA. On Forge 1.20.1 we store the same
 * payload under the ItemStack's NBT as a top-level "CustomData" compound.
 */
public final class StackCustomDataUtil {

    private static final String KEY = "CustomData";

    private StackCustomDataUtil() {
    }

    public static @NotNull CompoundTag getCopy(@NotNull ItemStack stack) {
        try {
            CompoundTag root = stack.getTag();
            if (root == null || !root.contains(KEY, Tag.TAG_COMPOUND)) {
                return new CompoundTag();
            }
            CompoundTag cd = root.getCompound(KEY);
            return (cd == null) ? new CompoundTag() : cd.copy();
        } catch (Throwable ignored) {
            return new CompoundTag();
        }
    }

    public static boolean hasAny(@NotNull ItemStack stack) {
        try {
            CompoundTag root = stack.getTag();
            return root != null && root.contains(KEY, Tag.TAG_COMPOUND) && !root.getCompound(KEY).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void set(@NotNull ItemStack stack, @Nullable CompoundTag customData) {
        try {
            if (customData == null || customData.isEmpty()) {
                remove(stack);
                return;
            }
            stack.getOrCreateTag().put(KEY, customData.copy());
        } catch (Throwable ignored) {
        }
    }

    public static void remove(@NotNull ItemStack stack) {
        try {
            CompoundTag root = stack.getTag();
            if (root != null) {
                root.remove(KEY);
                if (root.isEmpty()) {
                    stack.setTag(null);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}

