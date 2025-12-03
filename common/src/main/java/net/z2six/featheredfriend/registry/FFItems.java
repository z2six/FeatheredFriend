// MainFile: common/src/main/java/net/z2six/featheredfriend/registry/FFItems.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import com.google.common.base.Suppliers;
import net.minecraft.world.item.Item;
import net.z2six.featheredfriend.content.item.UnsealedScrollItem;
import net.z2six.featheredfriend.content.item.SealStampItem;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * FFItems
 *
 * Common, loader-agnostic item "registry".
 *
 * - Defines item factories in a deterministic map (id -> Supplier<Item>).
 * - Loader-specific modules (NeoForge/Fabric) are responsible for turning these
 *   into real registry entries using their own DeferredRegister / Registry APIs.
 *
 * IMPORTANT: This class must not import loader-specific classes (NeoForge, Fabric).
 */
public final class FFItems {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Deterministic map of item id → memoized item instance supplier.
     * The NeoForge side (FFNeoForgeItems) iterates this map to create
     * actual registered Items.
     */
    public static final Map<String, Supplier<Item>> ITEM_MAP = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Common item definitions
    // -------------------------------------------------------------------------

    // Unsealed scroll: stackable, opens the scroll sealing GUI on use.
    public static final Supplier<Item> SCROLL_UNSEALED = register(
            "scroll_unsealed",
            () -> new UnsealedScrollItem(new Item.Properties().stacksTo(64))
    );

    // Sealed scroll: non-stackable (stacksTo(1)).
    public static final Supplier<Item> SCROLL_SEALED = register(
            "scroll_sealed",
            () -> new Item(new Item.Properties().stacksTo(1))
    );

    // Seal stamp: custom item that opens the seal-etching GUI on RMB if not yet etched.
    public static final Supplier<Item> SEAL_STAMP = register(
            "seal_stamp",
            () -> new SealStampItem(new Item.Properties()
                    .stacksTo(1)
                    .durability(256))
    );

    // -------------------------------------------------------------------------
    // Internal helper
    // -------------------------------------------------------------------------

    private static Supplier<Item> register(String name, Supplier<Item> factory) {

        LOG.debug("[FFItems] Queuing common item '{}' for registration", name);

        // Memoize the factory so the Item instance is created once per runtime.
        Supplier<Item> memoized = Suppliers.memoize(() -> {
            try {
                return factory.get();
            } catch (Throwable t) {
                LOG.error("[FFItems] Failed to create Item instance for '{}', falling back to plain Item", name, t);
                return new Item(new Item.Properties());
            }
        });

        Supplier<Item> previous = ITEM_MAP.put(name, memoized);
        if (previous != null) {
            LOG.warn("[FFItems] Duplicate common item key '{}' detected; overwriting previous supplier", name);
        }

        return memoized;
    }

    private FFItems() {
        // no-op
    }
}
