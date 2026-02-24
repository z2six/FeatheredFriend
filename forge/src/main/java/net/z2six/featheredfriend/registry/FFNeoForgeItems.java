// neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeItems.java
package net.z2six.featheredfriend.registry;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

/**
 * FFNeoForgeItems
 *
 * NeoForge-specific item registration.
 *
 * Bridges the common FFItems.ITEM_MAP into NeoForge's DeferredRegister.Items.
 */
public class FFNeoForgeItems {

    private static final Logger LOG = Constants.LOG;

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Constants.MOD_ID);

    static {
        // Register all common items into the NeoForge DeferredRegister.
        FFItems.ITEM_MAP.forEach((id, itemSupplier) -> {
            try {
                LOG.debug("Registering NeoForge item '{}' via DeferredRegister", id);
                ITEMS.register(id, itemSupplier);
            } catch (Throwable t) {
                LOG.error("Failed to register NeoForge item '{}'", id, t);
            }
        });
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("Registering {} FeatheredFriend items with NeoForge", FFItems.ITEM_MAP.size());
        ITEMS.register(modEventBus);
    }

    private FFNeoForgeItems() {
        // no-op
    }
}
