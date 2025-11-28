// neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeMenus.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
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
            MENUS.register("scroll_sealing",
                    () -> {
                        LOG.debug("[FFNeoForgeMenus] Registering MenuType 'scroll_sealing'");
                        return new MenuType<>(ScrollSealingMenu::new, FeatureFlags.DEFAULT_FLAGS);
                    });

    private FFNeoForgeMenus() {
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("[FFNeoForgeMenus] Registering menu deferred register on mod event bus");
        MENUS.register(modEventBus);
    }
}
