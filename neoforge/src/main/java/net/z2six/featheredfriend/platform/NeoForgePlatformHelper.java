// neoforge/src/main/java/net/z2six/featheredfriend/platform/NeoForgePlatformHelper.java
package net.z2six.featheredfriend.platform;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.platform.services.IPlatformHelper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * NeoForgePlatformHelper
 *
 * NeoForge-specific implementation of the platform abstraction.
 */
public class NeoForgePlatformHelper implements IPlatformHelper {

    private static final Logger LOG = LogUtils.getLogger();

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public void openScrollSealingScreen(@NotNull ServerPlayer player) {
        try {
            LOG.debug("[NeoForgePlatformHelper] Opening Scroll Sealing menu for {}", player.getGameProfile().getName());

            player.openMenu(new SimpleMenuProvider(
                    (int containerId, Inventory inventory, Player p) ->
                            new ScrollSealingMenu(containerId, inventory),
                    Component.translatable("screen.featheredfriend.scroll_sealing")
            ));
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] Failed to open Scroll Sealing menu", t);
        }
    }
}
