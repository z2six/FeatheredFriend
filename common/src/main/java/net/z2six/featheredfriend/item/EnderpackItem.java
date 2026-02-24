package net.z2six.featheredfriend.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Portable storage item. Server controls all actual open/save behavior.
 */
public class EnderpackItem extends Item {

    private final String tooltipKey;

    public EnderpackItem(@NotNull Properties properties, @NotNull String tooltipKey) {
        super(properties);
        this.tooltipKey = tooltipKey;
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level,
                                                            @NotNull Player player,
                                                            @NotNull InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        try {
            if (level.isClientSide()) {
                return InteractionResultHolder.success(held);
            }
            if (player instanceof ServerPlayer serverPlayer) {
                Services.PLATFORM.openEnderpackScreen(serverPlayer, hand);
                return InteractionResultHolder.success(held);
            }
        } catch (Throwable ignored) {
        }
        return InteractionResultHolder.pass(held);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack,
                                @Nullable Level level,
                                @NotNull List<Component> tooltip,
                                @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable(this.tooltipKey).withStyle(ChatFormatting.GRAY));
    }
}
