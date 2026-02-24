// common/src/main/java/net/z2six/featheredfriend/content/item/UnsealedScrollItem.java
package net.z2six.featheredfriend.item;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * UnsealedScrollItem
 *
 * - Stackable scroll item.
 * - Right-click (RMB) opens the Scroll Sealing GUI.
 */
public class UnsealedScrollItem extends Item {

    private static final Logger LOG = LogUtils.getLogger();

    public UnsealedScrollItem(@NotNull Properties properties) {
        super(properties);
        LOG.debug("[UnsealedScrollItem] Created instance with properties {}", properties);
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level,
                                                           @NotNull Player player,
                                                           @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        try {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                LOG.debug(
                        "[UnsealedScrollItem] Player {} used unsealed scroll in hand {} – opening sealing screen",
                        serverPlayer.getGameProfile().getName(),
                        hand
                );
                Services.PLATFORM.openScrollSealingScreen(serverPlayer);
            }
        } catch (Throwable t) {
            // Never crash: just log.
            LOG.error("[UnsealedScrollItem] Failed to open scroll sealing screen", t);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
