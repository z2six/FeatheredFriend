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
import net.z2six.featheredfriend.calendar.CalendarDefinition;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.platform.services.IPlatformHelper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/platform/NeoForgePlatformHelper.java
 *
 * NeoForgePlatformHelper
 *
 * NeoForge-specific implementation of the platform abstraction.
 */
public class NeoForgePlatformHelper implements IPlatformHelper {

    private static final Logger LOG = LogUtils.getLogger();

    public NeoForgePlatformHelper() {
        LOG.debug("[NeoForgePlatformHelper] Constructed for platform '{}'", getPlatformName());
    }

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] isModLoaded('{}') failed", modId, t);
            return false;
        }
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        try {
            return !FMLLoader.isProduction();
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] isDevelopmentEnvironment() failed, assuming production", t);
            return false;
        }
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

    /**
     * Return the current calendar definition.
     *
     * This uses a SERVER config (ModConfig.Type.SERVER) defined in FFCalendarConfig.
     * On a dedicated server, that config is stored per-world. On clients, NeoForge
     * will automatically sync the SERVER config from the server, so getCalendarDefinition()
     * returns server-authoritative values on both logical sides.
     */
    @Override
    public CalendarDefinition getCalendarDefinition() {
        try {
            CalendarDefinition def = FFCalendarConfig.getCalendarDefinition();
            LOG.debug("[NeoForgePlatformHelper] getCalendarDefinition -> {}", def);
            return def;
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] getCalendarDefinition() failed, returning default", t);
            return CalendarDefinition.defaultDefinition();
        }
    }
}
