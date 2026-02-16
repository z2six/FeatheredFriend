package net.z2six.featheredfriend.item;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Mutable reference to where an Enderpack stack is physically stored.
 */
public interface EnderpackStackRef {

    @NotNull ItemStack getCurrentStack();

    void setCurrentStack(@NotNull ItemStack stack);
}
