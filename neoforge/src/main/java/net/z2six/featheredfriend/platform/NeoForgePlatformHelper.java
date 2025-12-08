// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/platform/NeoForgePlatformHelper.java
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
import net.z2six.featheredfriend.neoforge.menu.SealStampMenu;
import net.z2six.featheredfriend.platform.services.IPlatformHelper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

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
            player.openMenu(new SimpleMenuProvider(
                    (int containerId, Inventory inv, Player p) ->
                            new ScrollSealingMenu(containerId, inv),
                    Component.translatable("screen.featheredfriend.scroll_sealing")
            ));
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] Failed to open Scroll Sealing menu", t);
        }
    }

    @Override
    public void openSealStampScreen(@NotNull ServerPlayer player) {
        try {
            int slot = player.getMainHandItem().isEmpty() ? 37 : 36;

            player.openMenu(new SimpleMenuProvider(
                    (int containerId, Inventory inv, Player p) ->
                            new SealStampMenu(containerId, inv, slot),
                    Component.translatable("screen.featheredfriend.seal_stamp")
            ));
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] Failed to open Seal Stamp menu", t);
        }
    }

    @Override
    public CalendarDefinition getCalendarDefinition() {
        try {
            return FFCalendarConfig.getCalendarDefinition();
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] getCalendarDefinition() failed", t);
            return CalendarDefinition.defaultDefinition();
        }
    }
}
