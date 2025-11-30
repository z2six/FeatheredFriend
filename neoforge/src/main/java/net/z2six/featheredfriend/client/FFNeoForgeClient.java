// neoforge/src/main/java/net/z2six/featheredfriend/client/FFNeoForgeClient.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
import net.z2six.featheredfriend.client.ClientCalendarEvents;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/FFNeoForgeClient.java
 *
 * Client-only bootstrap for FeatheredFriend (NeoForge 1.21.1).
 *
 * NOTE:
 * - NeoForge 1.21.1 has NO @EventBusSubscriber on client.
 * - All client event wiring is done manually from the main mod class.
 */
public final class FFNeoForgeClient {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNeoForgeClient() {
        // no-op
    }

    /**
     * Called from FeatheredFriend's constructor on the CLIENT side only.
     *
     * @param modEventBus the mod's IEventBus (client + server combined)
     */
    public static void init(IEventBus modEventBus) {
        LOG.info("[FeatheredFriend] Client init starting");

        try {
            // Register menu screens
            modEventBus.addListener(FFNeoForgeClient::onRegisterScreens);
            LOG.debug("[FeatheredFriend] Hooked RegisterMenuScreensEvent");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Error wiring RegisterMenuScreensEvent", t);
        }

        try {
            // Wire calendar popup listeners into the GLOBAL NeoForge bus
            NeoForge.EVENT_BUS.addListener(ClientCalendarEvents::onClientTick);
            NeoForge.EVENT_BUS.addListener(ClientCalendarEvents::onRenderGui);
            LOG.info("[FeatheredFriend] Client calendar events wired to NeoForge.EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Error wiring client calendar events", t);
        }
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        try {
            event.register(FFNeoForgeMenus.SCROLL_SEALING_MENU.get(), ScrollSealingScreen::new);
            LOG.debug("[FeatheredFriend] Registered ScrollSealingScreen");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed registering ScrollSealingScreen", t);
        }
    }
}
