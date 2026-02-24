// neoforge/src/main/java/net/z2six/featheredfriend/client/ClientScreens.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
import net.z2six.featheredfriend.client.gui.SealStampScreen;
import net.z2six.featheredfriend.client.gui.ScrollViewScreen;
import net.z2six.featheredfriend.client.gui.EnderpackScreen;
import net.z2six.featheredfriend.client.gui.MailboxScreen;
import net.z2six.featheredfriend.client.gui.RavenChestScreen;
import net.z2six.featheredfriend.menu.EnderpackMenu;
import net.z2six.featheredfriend.menu.MailboxMenu;
import net.z2six.featheredfriend.menu.RavenChestMenu;
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
 *  - registerModBus(...) must be called from FeatheredFriend on Dist.CLIENT.
 *  - We register:
 *      * scroll_sealing  -> ScrollSealingScreen
 *      * seal_stamp      -> SealStampScreen
 *      * scroll_view     -> ScrollViewScreen
 */
public final class ClientScreens {

    private static final Logger LOG = LogUtils.getLogger();
    private static volatile boolean loggedNullScrollSealingMenuOnce = false;

    private ClientScreens() {
        // no-op
    }

    public static void registerModBus(IEventBus modEventBus) {
        try {
            modEventBus.addListener(ClientScreens::onClientSetup);
            LOG.debug("[ClientScreens] Registered on MOD event bus");
        } catch (Throwable t) {
            LOG.error("[ClientScreens] Failed to register MOD event bus listener", t);
        }
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        LOG.debug("[ClientScreens] onClientSetup fired for modId='{}'", Constants.MOD_ID);

        event.enqueueWork(() -> {
            // -----------------------------------------------------------------
            // Scroll sealing GUI
            // -----------------------------------------------------------------
            try {
                MenuScreens.register(
                        FFNeoForgeMenus.SCROLL_SEALING_MENU.get(),
                        (ScrollSealingMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) -> {
                            try {
                                if (menu == null) {
                                    if (!loggedNullScrollSealingMenuOnce) {
                                        loggedNullScrollSealingMenuOnce = true;
                                        LOG.error("[ClientScreens] ScrollSealingMenu was null in screen constructor; using fallback menu to avoid a hard crash.");
                                    }
                                    menu = new ScrollSealingMenu(0, inv);
                                }
                                return new ScrollSealingScreen(menu, inv, title);
                            } catch (Throwable t) {
                                LOG.error("[ClientScreens] Failed to construct ScrollSealingScreen", t);
                                return new ScrollSealingScreen(new ScrollSealingMenu(0, inv), inv, title);
                            }
                        }
                );
                LOG.debug("[ClientScreens] Registered ScrollSealingScreen");
            } catch (Throwable t) {
                LOG.error("[ClientScreens] Failed to register ScrollSealingScreen", t);
            }

            // -----------------------------------------------------------------
            // Seal Stamp carving GUI
            // -----------------------------------------------------------------
            try {
                MenuScreens.register(
                        FFNeoForgeMenus.SEAL_STAMP_MENU.get(),
                        (SealStampMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                                new SealStampScreen(menu, inv, title)
                );
                LOG.debug("[ClientScreens] Registered SealStampScreen");
            } catch (Throwable t) {
                LOG.error("[ClientScreens] Failed to register SealStampScreen", t);
            }

            // -----------------------------------------------------------------
            // Scroll view GUI (sealed / opened scrolls)
            // -----------------------------------------------------------------
            try {
                MenuScreens.register(
                        FFNeoForgeMenus.SCROLL_VIEW_MENU.get(),
                        (ScrollViewMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                                new ScrollViewScreen(menu, inv, title)
                );
                LOG.debug("[ClientScreens] Registered ScrollViewScreen");
            } catch (Throwable t) {
                LOG.error("[ClientScreens] Failed to register ScrollViewScreen", t);
            }

            // -----------------------------------------------------------------
            // Enderpack GUI
            // -----------------------------------------------------------------
            try {
                MenuScreens.register(
                        FFNeoForgeMenus.ENDERPACK_MENU.get(),
                        (EnderpackMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                                new EnderpackScreen(menu, inv, title)
                );
                LOG.debug("[ClientScreens] Registered EnderpackScreen");
            } catch (Throwable t) {
                LOG.error("[ClientScreens] Failed to register EnderpackScreen", t);
            }

            // -----------------------------------------------------------------
            // Mailbox GUI (9-slot storage)
            // -----------------------------------------------------------------
            try {
                MenuScreens.register(
                        FFNeoForgeMenus.MAILBOX_MENU.get(),
                        (MailboxMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                                new MailboxScreen(menu, inv, title)
                );
                LOG.debug("[ClientScreens] Registered MailboxScreen");
            } catch (Throwable t) {
                LOG.error("[ClientScreens] Failed to register MailboxScreen", t);
            }

            // -----------------------------------------------------------------
            // Raven Chest GUI (Suspicious Chest)
            // -----------------------------------------------------------------
            try {
                MenuScreens.register(
                        FFNeoForgeMenus.RAVEN_CHEST_MENU.get(),
                        (RavenChestMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                                new RavenChestScreen(menu, inv, title)
                );
                LOG.debug("[ClientScreens] Registered RavenChestScreen");
            } catch (Throwable t) {
                LOG.error("[ClientScreens] Failed to register RavenChestScreen", t);
            }
        });
    }
}
