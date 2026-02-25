// common/src/main/java/net/z2six/featheredfriend/content/item/ScrollViewItem.java
package net.z2six.featheredfriend.item;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * common/src/main/java/net/z2six/featheredfriend/content/item/ScrollViewItem.java
 *
 * ScrollViewItem
 *
 * - Shared item implementation for scrolls that open a placeholder GUI when used.
 * - Both scroll_sealed and scroll_opened will use this item class.
 * - The GUI itself will detect which of the two items triggered it by checking
 *   the player's currently held item on the client side.
 */
public class ScrollViewItem extends Item {

    private static final Logger LOG = LogUtils.getLogger();

    public ScrollViewItem(@NotNull Properties properties) {
        super(properties);
        LOG.debug("[ScrollViewItem] Constructed with properties={}", properties);
    }

    @Override
    @NotNull
    public InteractionResult use(@NotNull Level level,
                                 @NotNull net.minecraft.world.entity.player.Player player,
                                 @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        try {
            // Client side: just play the animation.
            if (level.isClientSide) {
                LOG.debug("[ScrollViewItem] use() on client; returning SUCCESS (no server logic)");
                return InteractionResult.SUCCESS;
            }

            if (!(player instanceof ServerPlayer serverPlayer)) {
                LOG.warn("[ScrollViewItem] use() called on non-ServerPlayer on logical server; returning PASS");
                return InteractionResult.PASS;
            }

            LOG.debug("[ScrollViewItem] Opening scroll view GUI for player={} hand={} item={}",
                    serverPlayer.getGameProfile().getName(),
                    hand,
                    stack.getItem().toString());

            // Open the placeholder scroll view GUI via platform abstraction.
            Services.PLATFORM.openScrollViewScreen(serverPlayer);

            return InteractionResult.SUCCESS_SERVER;
        } catch (Throwable t) {
            LOG.error("[ScrollViewItem] use() failed; returning PASS to avoid crashes", t);
            return InteractionResult.PASS;
        }
    }
}
