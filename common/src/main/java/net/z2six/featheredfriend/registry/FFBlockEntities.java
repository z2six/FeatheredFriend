package net.z2six.featheredfriend.registry;

import com.google.common.base.Suppliers;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.z2six.featheredfriend.block.entity.RavenChestBlockEntity;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Common block-entity registry map (loader agnostic).
 */
public final class FFBlockEntities {

    private static final Logger LOG = LogUtils.getLogger();

    public static final Map<String, Supplier<BlockEntityType<?>>> BLOCK_ENTITY_MAP = new LinkedHashMap<>();

    private static Supplier<BlockEntityType<RavenChestBlockEntity>> ravenChestType =
            () -> {
                throw new IllegalStateException(
                        "Raven chest BlockEntityType was not bound by the current loader");
            };

    @SuppressWarnings("unchecked")
    public static final Supplier<BlockEntityType<RavenChestBlockEntity>> RAVEN_CHEST =
            (Supplier<BlockEntityType<RavenChestBlockEntity>>) (Supplier<?>) register(
                    "raven_chest",
                    () -> ravenChestType.get()
            );

    public static void bindRavenChestType(Supplier<BlockEntityType<RavenChestBlockEntity>> supplier) {
        ravenChestType = Objects.requireNonNull(supplier, "supplier");
    }

    private static Supplier<BlockEntityType<?>> register(String name, Supplier<BlockEntityType<?>> factory) {
        Supplier<BlockEntityType<?>> memoized = Suppliers.memoize(() -> {
            try {
                return factory.get();
            } catch (Throwable t) {
                LOG.error("[FFBlockEntities] Failed to create block entity type '{}'", name, t);
                throw t;
            }
        });

        Supplier<BlockEntityType<?>> previous = BLOCK_ENTITY_MAP.put(name, memoized);
        if (previous != null) {
            LOG.warn("[FFBlockEntities] Duplicate key '{}' detected; overwriting previous supplier", name);
        }

        return memoized;
    }

    private FFBlockEntities() {
    }
}
