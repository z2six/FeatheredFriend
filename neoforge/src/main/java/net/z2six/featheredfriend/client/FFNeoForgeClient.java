// neoforge/src/main/java/net/z2six/featheredfriend/client/FFNeoForgeClient.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/FFNeoForgeClient.java
 *
 * FFNeoForgeClient
 *
 * Client-only helper for NeoForge.
 *
 * Currently:
 *  - Registers the ScrollSealingScreen for the scroll_sealing menu type.
 *
 * This is wired from the main mod class via:
 *
 *   modEventBus.addListener(FFNeoForgeClient::onRegisterMenuScreens);
 */
public final class FFNeoForgeClient {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNeoForgeClient() {
        // no instances
    }

    /**
     * Called on the client during mod setup to register container screens.
     *
     * This is guaranteed to only run on the physical client.
     */
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        try {
            // We can't call getId() on the Supplier; just log a simple message.
            LOG.info("[FFNeoForgeClient] Registering ScrollSealingScreen");

            event.register(FFNeoForgeMenus.SCROLL_SEALING_MENU.get(), ScrollSealingScreen::new);

        } catch (Throwable t) {
            LOG.error("[FFNeoForgeClient] Failed to register menu screens", t);
        }
    }
}
