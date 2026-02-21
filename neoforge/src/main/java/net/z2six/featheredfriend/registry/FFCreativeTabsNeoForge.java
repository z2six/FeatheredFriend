// neoforge/src/main/java/net/z2six/featheredfriend/registry/FFCreativeTabsNeoForge.java
package net.z2six.featheredfriend.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

/**
 * Registers the FeatheredFriend creative tab on NeoForge.
 */
public final class FFCreativeTabsNeoForge {

    private static final Logger LOG = Constants.LOG;

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Constants.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FEATHERED_FRIEND_TAB =
            TABS.register("featheredfriend", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.featheredfriend"))
                    .icon(() -> new ItemStack(FFItems.SCROLL_SEALED.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(FFItems.SCROLL_UNSEALED.get());
                        output.accept(FFItems.SCROLL_SEALED.get());
                        output.accept(FFItems.SCROLL_OPENED.get());
                        output.accept(FFItems.SEAL_STAMP.get());
                        output.accept(FFItems.ENDERPACK.get());
                        output.accept(FFItems.RAVEN_FEATHER.get());
                        output.accept(FFItems.RAVENS_EYE.get());

                        output.accept(FFItems.RAVEN_CHEST.get());
                        output.accept(FFItems.MAILBOX.get());
                        output.accept(FFItems.RAVEN_ARMOR_LEATHER.get());
                        output.accept(FFItems.RAVEN_ARMOR_COPPER.get());
                        output.accept(FFItems.RAVEN_ARMOR_IRON.get());
                        output.accept(FFItems.RAVEN_ARMOR_GOLD.get());
                        output.accept(FFItems.RAVEN_ARMOR_DIAMOND.get());
                        output.accept(FFItems.RAVEN_ARMOR_NETHERITE.get());
                    })
                    .build()
            );

    private FFCreativeTabsNeoForge() {
        // no instances
    }

    /**
     * Hook up tab registration to the mod event bus.
     */
    public static void register(IEventBus modEventBus) {
        LOG.debug("FFCreativeTabsNeoForge: Registering FeatheredFriend creative tab");
        TABS.register(modEventBus);
    }
}
