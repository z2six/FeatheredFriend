package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
import net.z2six.featheredfriend.client.gui.ScrollViewScreen;
import net.z2six.featheredfriend.client.gui.SealStampScreen;
import net.z2six.featheredfriend.forge.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.forge.menu.ScrollViewMenu;
import net.z2six.featheredfriend.forge.menu.SealStampMenu;
import net.z2six.featheredfriend.registry.FFForgeMenus;
import org.slf4j.Logger;

/**
 * Registers all container-based GUIs (menu → screen) on the Forge client side.
 */
@Mod.EventBusSubscriber(
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        modid = Constants.MOD_ID
)
public final class ClientScreens {

    private static final Logger LOG = LogUtils.getLogger();

    private ClientScreens() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOG.info("[ClientScreens] Client setup: registering menu screens");

        event.enqueueWork(() -> {
            try {
                MenuScreens.register(
                        FFForgeMenus.SCROLL_SEALING_MENU.get(),
                        (ScrollSealingMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                                new ScrollSealingScreen(menu, inv, title)
                );
                LOG.info("[ClientScreens] Registered ScrollSealingScreen");
            } catch (Throwable t) {
                LOG.error("[ClientScreens] Failed to register ScrollSealingScreen", t);
            }

            try {
                MenuScreens.register(
                        FFForgeMenus.SEAL_STAMP_MENU.get(),
                        (SealStampMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                                new SealStampScreen(menu, inv, title)
                );
                LOG.info("[ClientScreens] Registered SealStampScreen");
            } catch (Throwable t) {
                LOG.error("[ClientScreens] Failed to register SealStampScreen", t);
            }

            try {
                MenuScreens.register(
                        FFForgeMenus.SCROLL_VIEW_MENU.get(),
                        (ScrollViewMenu menu, net.minecraft.world.entity.player.Inventory inv, net.minecraft.network.chat.Component title) ->
                                new ScrollViewScreen(menu, inv, title)
                );
                LOG.info("[ClientScreens] Registered ScrollViewScreen");
            } catch (Throwable t) {
                LOG.error("[ClientScreens] Failed to register ScrollViewScreen", t);
            }
        });
    }
}
