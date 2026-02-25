package net.z2six.featheredfriend.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;

/**
 * Starts Raven Link when possible.
 */
public class RavensEyeItem extends TooltipItem {

    public RavensEyeItem(@NotNull Properties properties, @NotNull String tooltipKey) {
        super(properties, tooltipKey);
    }

    @Override
    public @NotNull InteractionResult use(@NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        try {
            if (level.isClientSide()) {
                try {
                    Services.PLATFORM.sendRavenLinkEffigyPoseSnapshotToServer();
                } catch (Throwable ignored) {
                }
                return InteractionResult.SUCCESS;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                boolean started = Services.PLATFORM.tryStartRavenLink(serverPlayer);
                return started ? InteractionResult.SUCCESS_SERVER : InteractionResult.FAIL;
            }
        } catch (Throwable ignored) {
        }
        return InteractionResult.PASS;
    }
}
