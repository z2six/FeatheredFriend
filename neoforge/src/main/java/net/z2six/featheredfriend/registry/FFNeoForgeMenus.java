// neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeMenus.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.menu.ScrollViewMenu;
import net.z2six.featheredfriend.menu.SealStampMenu;
import net.z2six.featheredfriend.menu.EnderpackMenu;
import net.z2six.featheredfriend.registry.FFMenus;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * FFNeoForgeMenus
 *
 * NeoForge-side menu registrations.
 */
public final class FFNeoForgeMenus {

    private static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Constants.MOD_ID);

    public static final Supplier<MenuType<ScrollSealingMenu>> SCROLL_SEALING_MENU =
            MENUS.register(FFMenus.SCROLL_SEALING_ID,
                    () -> new MenuType<>(ScrollSealingMenu::new, FeatureFlags.DEFAULT_FLAGS));

    public static final Supplier<MenuType<SealStampMenu>> SEAL_STAMP_MENU =
            MENUS.register(FFMenus.SEAL_STAMP_ID,
                    () -> new MenuType<>(SealStampMenu::new, FeatureFlags.DEFAULT_FLAGS));

    public static final Supplier<MenuType<ScrollViewMenu>> SCROLL_VIEW_MENU =
            MENUS.register(FFMenus.SCROLL_VIEW_ID,
                    () -> new MenuType<>(ScrollViewMenu::new, FeatureFlags.DEFAULT_FLAGS));

    public static final Supplier<MenuType<EnderpackMenu>> ENDERPACK_MENU =
            MENUS.register(FFMenus.ENDERPACK_ID,
                    () -> new MenuType<>(EnderpackMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private FFNeoForgeMenus() {
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("[FFNeoForgeMenus] Registering menu deferred register on mod event bus");
        MENUS.register(modEventBus);
    }
}
