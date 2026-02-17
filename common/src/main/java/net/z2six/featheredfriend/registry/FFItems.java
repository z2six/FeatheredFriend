// common/src/main/java/net/z2six/featheredfriend/registry/FFItems.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import com.google.common.base.Suppliers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.entity.raven.RavenArmorVisual;
import net.z2six.featheredfriend.item.EnderpackItem;
import net.z2six.featheredfriend.item.RavenArmorItem;
import net.z2six.featheredfriend.item.RavenArmorStats;
import net.z2six.featheredfriend.item.RavensEyeItem;
import net.z2six.featheredfriend.item.ScrollViewItem;
import net.z2six.featheredfriend.item.TooltipBlockItem;
import net.z2six.featheredfriend.item.TooltipItem;
import net.z2six.featheredfriend.item.UnsealedScrollItem;
import net.z2six.featheredfriend.item.SealStampItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * FFItems
 *
 * Common, loader-agnostic item "registry".
 *
 * - Defines item factories in a deterministic map (id -> Supplier `Item`).
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

    // Opened scroll: non-stackable (already read/opened by the player).
    // Now uses ScrollViewItem to open the placeholder GUI on RMB.
    public static final Supplier<Item> SCROLL_OPENED = register(
            "scroll_opened",
            () -> new ScrollViewItem(new Item.Properties().stacksTo(1))
    );

    // Sealed scroll: non-stackable (stacksTo(1)).
    // Now uses ScrollViewItem to open the placeholder GUI on RMB.
    public static final Supplier<Item> SCROLL_SEALED = register(
            "scroll_sealed",
            () -> new ScrollViewItem(new Item.Properties().stacksTo(1))
    );

    // Seal stamp: custom item that opens the seal-etching GUI on RMB if not yet etched.
    public static final Supplier<Item> SEAL_STAMP = register(
            "seal_stamp",
            () -> new SealStampItem(new Item.Properties()
                    .stacksTo(1)
                    .durability(256))
    );

    public static final Supplier<Item> ENDERPACK = register(
            "enderpack",
            () -> new EnderpackItem(
                    new Item.Properties().stacksTo(1),
                    "tooltip.featheredfriend.enderpack"
            )
    );

    public static final Supplier<Item> RAVEN_FEATHER = register(
            "raven_feather",
            () -> new Item(new Item.Properties().stacksTo(64))
    );

    public static final Supplier<Item> RAVENS_EYE = register(
            "ravens_eye",
            () -> new RavensEyeItem(new Item.Properties().stacksTo(64))
    );

    public static final RavenArmorStats RAVEN_ARMOR_STATS_LEATHER = new RavenArmorStats(2, 35, 10, 15, 2);
    public static final RavenArmorStats RAVEN_ARMOR_STATS_COPPER = new RavenArmorStats(3, 22, 18, 35, 1);
    public static final RavenArmorStats RAVEN_ARMOR_STATS_IRON = new RavenArmorStats(4, 18, 16, 25, 1);
    public static final RavenArmorStats RAVEN_ARMOR_STATS_GOLD = new RavenArmorStats(2, 42, 22, 50, 3);
    public static final RavenArmorStats RAVEN_ARMOR_STATS_DIAMOND = new RavenArmorStats(5, 28, 14, 40, 2);
    public static final RavenArmorStats RAVEN_ARMOR_STATS_NETHERITE = new RavenArmorStats(6, 15, 28, 70, 2);

    public static final Supplier<Item> RAVEN_CHEST = register(
            "raven_chest",
            () -> new TooltipBlockItem(
                    FFBlocks.RAVEN_CHEST.get(),
                    new Item.Properties().stacksTo(1),
                    "tooltip.featheredfriend.raven_chest"
            )
    );

    public static final Supplier<Item> RAVEN_ARMOR_LEATHER = register(
            "raven_armor_leather",
            () -> new RavenArmorItem(
                    new Item.Properties().stacksTo(1),
                    "tooltip.featheredfriend.raven_armor_leather",
                    RAVEN_ARMOR_STATS_LEATHER
            )
    );

    public static final Supplier<Item> RAVEN_ARMOR_COPPER = register(
            "raven_armor_copper",
            () -> new RavenArmorItem(
                    new Item.Properties().stacksTo(1),
                    "tooltip.featheredfriend.raven_armor_copper",
                    RAVEN_ARMOR_STATS_COPPER
            )
    );

    public static final Supplier<Item> RAVEN_ARMOR_IRON = register(
            "raven_armor_iron",
            () -> new RavenArmorItem(
                    new Item.Properties().stacksTo(1),
                    "tooltip.featheredfriend.raven_armor_iron",
                    RAVEN_ARMOR_STATS_IRON
            )
    );

    public static final Supplier<Item> RAVEN_ARMOR_GOLD = register(
            "raven_armor_gold",
            () -> new RavenArmorItem(
                    new Item.Properties().stacksTo(1),
                    "tooltip.featheredfriend.raven_armor_gold",
                    RAVEN_ARMOR_STATS_GOLD
            )
    );

    public static final Supplier<Item> RAVEN_ARMOR_DIAMOND = register(
            "raven_armor_diamond",
            () -> new RavenArmorItem(
                    new Item.Properties().stacksTo(1),
                    "tooltip.featheredfriend.raven_armor_diamond",
                    RAVEN_ARMOR_STATS_DIAMOND
            )
    );

    public static final Supplier<Item> RAVEN_ARMOR_NETHERITE = register(
            "raven_armor_netherite",
            () -> new RavenArmorItem(
                    new Item.Properties().stacksTo(1),
                    "tooltip.featheredfriend.raven_armor_netherite",
                    RAVEN_ARMOR_STATS_NETHERITE
            )
    );

    public static boolean isRavenArmor(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item == RAVEN_ARMOR_LEATHER.get()
                || item == RAVEN_ARMOR_COPPER.get()
                || item == RAVEN_ARMOR_IRON.get()
                || item == RAVEN_ARMOR_GOLD.get()
                || item == RAVEN_ARMOR_DIAMOND.get()
                || item == RAVEN_ARMOR_NETHERITE.get();
    }

    public static boolean isEnderpack(@Nullable ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.getItem() == ENDERPACK.get();
    }

    public static @Nullable RavenArmorStats getRavenArmorStats(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item == RAVEN_ARMOR_LEATHER.get()) return RAVEN_ARMOR_STATS_LEATHER;
        if (item == RAVEN_ARMOR_COPPER.get()) return RAVEN_ARMOR_STATS_COPPER;
        if (item == RAVEN_ARMOR_IRON.get()) return RAVEN_ARMOR_STATS_IRON;
        if (item == RAVEN_ARMOR_GOLD.get()) return RAVEN_ARMOR_STATS_GOLD;
        if (item == RAVEN_ARMOR_DIAMOND.get()) return RAVEN_ARMOR_STATS_DIAMOND;
        if (item == RAVEN_ARMOR_NETHERITE.get()) return RAVEN_ARMOR_STATS_NETHERITE;
        return null;
    }

    public static @Nullable RavenArmorStats getRavenArmorStats(@Nullable RavenArmorVisual armorVisual) {
        if (armorVisual == null || armorVisual == RavenArmorVisual.NONE) {
            return null;
        }
        if (armorVisual == RavenArmorVisual.LEATHER) return RAVEN_ARMOR_STATS_LEATHER;
        if (armorVisual == RavenArmorVisual.COPPER) return RAVEN_ARMOR_STATS_COPPER;
        if (armorVisual == RavenArmorVisual.IRON) return RAVEN_ARMOR_STATS_IRON;
        if (armorVisual == RavenArmorVisual.GOLD) return RAVEN_ARMOR_STATS_GOLD;
        if (armorVisual == RavenArmorVisual.DIAMOND) return RAVEN_ARMOR_STATS_DIAMOND;
        if (armorVisual == RavenArmorVisual.NETHERITE) return RAVEN_ARMOR_STATS_NETHERITE;
        return null;
    }

    public static @NotNull RavenArmorVisual getRavenArmorVisual(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return RavenArmorVisual.NONE;
        }
        Item item = stack.getItem();
        if (item == RAVEN_ARMOR_LEATHER.get()) return RavenArmorVisual.LEATHER;
        if (item == RAVEN_ARMOR_COPPER.get()) return RavenArmorVisual.COPPER;
        if (item == RAVEN_ARMOR_IRON.get()) return RavenArmorVisual.IRON;
        if (item == RAVEN_ARMOR_GOLD.get()) return RavenArmorVisual.GOLD;
        if (item == RAVEN_ARMOR_DIAMOND.get()) return RavenArmorVisual.DIAMOND;
        if (item == RAVEN_ARMOR_NETHERITE.get()) return RavenArmorVisual.NETHERITE;
        return RavenArmorVisual.NONE;
    }

    public static @NotNull ItemStack createRavenArmorStack(@Nullable RavenArmorVisual armorVisual) {
        if (armorVisual == null || armorVisual == RavenArmorVisual.NONE) {
            return ItemStack.EMPTY;
        }
        if (armorVisual == RavenArmorVisual.LEATHER) return new ItemStack(RAVEN_ARMOR_LEATHER.get());
        if (armorVisual == RavenArmorVisual.COPPER) return new ItemStack(RAVEN_ARMOR_COPPER.get());
        if (armorVisual == RavenArmorVisual.IRON) return new ItemStack(RAVEN_ARMOR_IRON.get());
        if (armorVisual == RavenArmorVisual.GOLD) return new ItemStack(RAVEN_ARMOR_GOLD.get());
        if (armorVisual == RavenArmorVisual.DIAMOND) return new ItemStack(RAVEN_ARMOR_DIAMOND.get());
        if (armorVisual == RavenArmorVisual.NETHERITE) return new ItemStack(RAVEN_ARMOR_NETHERITE.get());
        return ItemStack.EMPTY;
    }

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
