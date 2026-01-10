package net.z2six.featheredfriend.registry;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

/**
 * Adds FeatheredFriend items to vanilla creative tabs on Forge.
 */
public final class FFCreativeTabsNeoForge {

    private static final Logger LOG = Constants.LOG;

    private FFCreativeTabsNeoForge() {
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("FFCreativeTabsNeoForge: Registering BuildCreativeModeTabContentsEvent listener");
        modEventBus.addListener(FFCreativeTabsNeoForge::onBuildCreativeTabContents);
    }

    public static void onBuildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {

        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            LOG.debug("FFCreativeTabsNeoForge: Populating Ingredients tab with FeatheredFriend items");

            try { event.accept(FFItems.SCROLL_UNSEALED.get()); } catch (Throwable t) { LOG.error("Failed to add SCROLL_UNSEALED", t); }
            try { event.accept(FFItems.SCROLL_SEALED.get()); } catch (Throwable t) { LOG.error("Failed to add SCROLL_SEALED", t); }
            try { event.accept(FFItems.SCROLL_OPENED.get()); } catch (Throwable t) { LOG.error("Failed to add SCROLL_OPENED", t); }
            try { event.accept(FFItems.SEAL_STAMP.get()); } catch (Throwable t) { LOG.error("Failed to add SEAL_STAMP", t); }
        }
    }
}
