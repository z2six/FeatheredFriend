package net.z2six.featheredfriend.registry;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

/**
 * Forge-specific item registration.
 *
 * Bridges the common FFItems.ITEM_MAP into Forge's DeferredRegister.
 */
public final class FFForgeItems {

    private static final Logger LOG = Constants.LOG;

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Constants.MOD_ID);

    static {
        FFItems.ITEM_MAP.forEach((id, itemSupplier) -> {
            try {
                LOG.debug("Registering Forge item '{}' via DeferredRegister", id);
                ITEMS.register(id, itemSupplier);
            } catch (Throwable t) {
                LOG.error("Failed to register Forge item '{}'", id, t);
            }
        });
    }

    public static void register(IEventBus modEventBus) {
        LOG.info("Registering {} FeatheredFriend items with Forge", FFItems.ITEM_MAP.size());
        ITEMS.register(modEventBus);
    }

    private FFForgeItems() {
    }
}
