// neoforge/src/main/java/net/z2six/featheredfriend/client/FFNeoForgeClient.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

/**
 * FFNeoForgeClient
 *
 * Client-only NeoForge event handlers.
 */
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FFNeoForgeClient {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNeoForgeClient() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        try {
            LOG.debug("[FFNeoForgeClient] Registering ScrollSealingScreen");
            event.register(FFNeoForgeMenus.SCROLL_SEALING_MENU.get(), ScrollSealingScreen::new);
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeClient] Failed to register menu screens", t);
        }
    }
}
