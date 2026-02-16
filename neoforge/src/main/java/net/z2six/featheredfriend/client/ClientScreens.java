// neoforge/src/main/java/net/z2six/featheredfriend/client/ClientScreens.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
import net.z2six.featheredfriend.client.gui.SealStampScreen;
import net.z2six.featheredfriend.client.gui.ScrollViewScreen;
import net.z2six.featheredfriend.client.gui.EnderpackScreen;
import net.z2six.featheredfriend.menu.EnderpackMenu;
import net.z2six.featheredfriend.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.menu.SealStampMenu;
import net.z2six.featheredfriend.menu.ScrollViewMenu;
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
 *  - We register:
 *      * scroll_sealing  -> ScrollSealingScreen
 *      * seal_stamp      -> SealStampScreen
 *      * scroll_view     -> ScrollViewScreen
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
        LOG.debug("[ClientScreens] onRegisterMenuScreens fired for modId='{}'", Constants.MOD_ID);

        // ---------------------------------------------------------------------
        // Scroll sealing GUI
        // ---------------------------------------------------------------------
        try {
            LOG.debug("[ClientScreens] Registering screen for menu type: {} (scroll_sealing)",
                    FFNeoForgeMenus.SCROLL_SEALING_MENU.get().toString());

            event.register(
                    FFNeoForgeMenus.SCROLL_SEALING_MENU.get(),
                    (ScrollSealingMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                            new ScrollSealingScreen(menu, inv, title)
            );

            LOG.debug("[ClientScreens] Successfully registered ScrollSealingScreen");
        } catch (Throwable t) {
            LOG.error("[ClientScreens] Failed to register ScrollSealingScreen", t);
        }

        // ---------------------------------------------------------------------
        // Seal Stamp carving GUI (placeholder)
        // ---------------------------------------------------------------------
        try {
            LOG.debug("[ClientScreens] Registering screen for menu type: {} (seal_stamp)",
                    FFNeoForgeMenus.SEAL_STAMP_MENU.get().toString());

            event.register(
                    FFNeoForgeMenus.SEAL_STAMP_MENU.get(),
                    (SealStampMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                            new SealStampScreen(menu, inv, title)
            );

            LOG.debug("[ClientScreens] Successfully registered SealStampScreen");
        } catch (Throwable t) {
            LOG.error("[ClientScreens] Failed to register SealStampScreen", t);
        }

        // ---------------------------------------------------------------------
        // Scroll view GUI (placeholder for sealed / opened scrolls)
        // ---------------------------------------------------------------------
        try {
            LOG.debug("[ClientScreens] Registering screen for menu type: {} (scroll_view)",
                    FFNeoForgeMenus.SCROLL_VIEW_MENU.get().toString());

            event.register(
                    FFNeoForgeMenus.SCROLL_VIEW_MENU.get(),
                    (ScrollViewMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                            new ScrollViewScreen(menu, inv, title)
            );

            LOG.debug("[ClientScreens] Successfully registered ScrollViewScreen");
        } catch (Throwable t) {
            LOG.error("[ClientScreens] Failed to register ScrollViewScreen", t);
        }

        // ---------------------------------------------------------------------
        // Enderpack GUI (vanilla chest screen over EnderpackMenu)
        // ---------------------------------------------------------------------
        try {
            LOG.debug("[ClientScreens] Registering screen for menu type: {} (enderpack)",
                    FFNeoForgeMenus.ENDERPACK_MENU.get().toString());

            event.register(
                    FFNeoForgeMenus.ENDERPACK_MENU.get(),
                    (EnderpackMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                            new EnderpackScreen(menu, inv, title)
            );

            LOG.debug("[ClientScreens] Successfully registered Enderpack chest screen");
        } catch (Throwable t) {
            LOG.error("[ClientScreens] Failed to register Enderpack screen", t);
        }
    }
}
