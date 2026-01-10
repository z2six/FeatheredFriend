// forge/src/main/java/net/z2six/featheredfriend/registry/FFForgeMenus.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.forge.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.forge.menu.SealStampMenu;
import net.z2six.featheredfriend.forge.menu.ScrollViewMenu;
import org.slf4j.Logger;

/**
 * Forge-side menu registrations.
 */
public final class FFForgeMenus {

    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Constants.MOD_ID);

    public static final RegistryObject<MenuType<ScrollSealingMenu>> SCROLL_SEALING_MENU =
            MENUS.register("scroll_sealing", () -> {
                LOG.debug("[FFForgeMenus] Registering MenuType 'scroll_sealing'");
                return new MenuType<>(ScrollSealingMenu::new, FeatureFlags.DEFAULT_FLAGS);
            });

    public static final RegistryObject<MenuType<SealStampMenu>> SEAL_STAMP_MENU =
            MENUS.register("seal_stamp", () -> {
                LOG.debug("[FFForgeMenus] Registering MenuType 'seal_stamp'");
                return new MenuType<>(SealStampMenu::new, FeatureFlags.DEFAULT_FLAGS);
            });

    public static final RegistryObject<MenuType<ScrollViewMenu>> SCROLL_VIEW_MENU =
            MENUS.register("scroll_view", () -> {
                LOG.debug("[FFForgeMenus] Registering MenuType 'scroll_view'");
                return new MenuType<>(ScrollViewMenu::new, FeatureFlags.DEFAULT_FLAGS);
            });

    private FFForgeMenus() {
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("[FFForgeMenus] Registering menu deferred register on mod event bus");
        MENUS.register(modEventBus);
    }
}
