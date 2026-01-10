// neoforge/src/main/java/net/z2six/featheredfriend/registry/FFCreativeTabsNeoForge.java
package net.z2six.featheredfriend.registry;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

/**
 * Handles adding FeatheredFriend items to vanilla creative tabs on NeoForge.
 *
 * This is registered to the MOD event bus via FFCreativeTabsNeoForge.register(eventBus).
 */
public final class FFCreativeTabsNeoForge {

    private static final Logger LOG = Constants.LOG;

    private FFCreativeTabsNeoForge() {
        // no instances
    }

    /**
     * Hook up the listener to the mod event bus.
     */
    public static void register(IEventBus modEventBus) {
        LOG.debug("FFCreativeTabsNeoForge: Registering BuildCreativeModeTabContentsEvent listener");
        modEventBus.addListener(FFCreativeTabsNeoForge::onBuildCreativeTabContents);
    }

    /**
     * Listener for BuildCreativeModeTabContentsEvent.
     */
    public static void onBuildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {

        // Only touch the Ingredients tab for now.
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            LOG.debug("FFCreativeTabsNeoForge: Populating Ingredients tab with FeatheredFriend items");

            try {
                event.accept(FFItems.SCROLL_UNSEALED.get());
            } catch (Throwable t) {
                LOG.error("Failed to add SCROLL_UNSEALED to Ingredients tab", t);
            }

            try {
                event.accept(FFItems.SCROLL_SEALED.get());
            } catch (Throwable t) {
                LOG.error("Failed to add SCROLL_SEALED to Ingredients tab", t);
            }

            try {
                event.accept(FFItems.SCROLL_OPENED.get());
            } catch (Throwable t) {
                LOG.error("Failed to add SCROLL_OPENED to Ingredients tab", t);
            }

            try {
                event.accept(FFItems.SEAL_STAMP.get());
            } catch (Throwable t) {
                LOG.error("Failed to add SEAL_STAMP to Ingredients tab", t);
            }
        }
    }
}
