// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/ClientScreens.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
import net.z2six.featheredfriend.client.gui.SealStampScreen;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.neoforge.menu.SealStampMenu;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/ClientScreens.java
 *
 * Registers all container-based GUIs (menu → screen) on the NeoForge client side.
 *
 * IMPORTANT:
 *  - This must live in the NeoForge source set.
 *  - The @EventBusSubscriber annotation must use the correct mod id (Constants.MOD_ID).
 *  - We register BOTH:
 *      * scroll_sealing  -> ScrollSealingScreen
 *      * seal_stamp      -> SealStampScreen
 */
@EventBusSubscriber(
        value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD,
        modid = Constants.MOD_ID
)
public final class ClientScreens {

    private static final Logger LOG = LogUtils.getLogger();

    private ClientScreens() {
        // no-op
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        LOG.info("[ClientScreens] onRegisterMenuScreens fired for modId='{}'", Constants.MOD_ID);

        // ---------------------------------------------------------------------
        // Scroll sealing GUI
        // ---------------------------------------------------------------------
        try {
            LOG.info("[ClientScreens] Registering screen for menu type: {} (scroll_sealing)",
                    FFNeoForgeMenus.SCROLL_SEALING_MENU.get().toString());

            event.register(
                    FFNeoForgeMenus.SCROLL_SEALING_MENU.get(),
                    (ScrollSealingMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                            new ScrollSealingScreen(menu, inv, title)
            );

            LOG.info("[ClientScreens] Successfully registered ScrollSealingScreen");
        } catch (Throwable t) {
            LOG.error("[ClientScreens] Failed to register ScrollSealingScreen", t);
        }

        // ---------------------------------------------------------------------
        // Seal Stamp carving GUI (placeholder)
        // ---------------------------------------------------------------------
        try {
            LOG.info("[ClientScreens] Registering screen for menu type: {} (seal_stamp)",
                    FFNeoForgeMenus.SEAL_STAMP_MENU.get().toString());

            event.register(
                    FFNeoForgeMenus.SEAL_STAMP_MENU.get(),
                    (SealStampMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                            new SealStampScreen(menu, inv, title)
            );

            LOG.info("[ClientScreens] Successfully registered SealStampScreen");
        } catch (Throwable t) {
            LOG.error("[ClientScreens] Failed to register SealStampScreen", t);
        }
    }
}
