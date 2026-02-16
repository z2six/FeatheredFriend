// common/src/main/java/net/z2six/featheredfriend/registry/FFEntities.java
package net.z2six.featheredfriend.registry;

import com.google.common.base.Suppliers;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * FFEntities
 *
 * Common, loader-agnostic entity "registry".
 *
 * Loader-specific modules (NeoForge/Fabric) should iterate ENTITY_MAP to register
 * real EntityType entries using their registry APIs.
 */
public final class FFEntities {

    private static final Logger LOG = LogUtils.getLogger();

    public static final Map<String, Supplier<? extends EntityType<?>>> ENTITY_MAP = new LinkedHashMap<>();

    // Raven hitbox tuning (gameplay collision/selection).
    private static final float RAVEN_HITBOX_WIDTH = 0.70F;
    private static final float RAVEN_HITBOX_HEIGHT = 0.70F;

    public static final Supplier<EntityType<RavenEntity>> RAVEN = register(
            "raven",
            () -> EntityType.Builder
                    .of(RavenEntity::new, MobCategory.CREATURE)
                    .sized(RAVEN_HITBOX_WIDTH, RAVEN_HITBOX_HEIGHT)
                    .clientTrackingRange(8)
                    .build(Constants.MOD_ID + ":raven")
    );

    public static AttributeSupplier.Builder createRavenAttributes() {
        try {
            return Mob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, 1.0D)
                    .add(Attributes.MOVEMENT_SPEED, 0.28D);
        } catch (Throwable t) {
            LOG.error("[FFEntities] createRavenAttributes failed; falling back to minimal attributes", t);
            return Mob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, 1.0D);
        }
    }

    private static <T extends EntityType<?>> Supplier<T> register(String name, Supplier<T> factory) {
        LOG.debug("[FFEntities] Queuing common entity '{}' for registration", name);

        Supplier<T> memoized = Suppliers.memoize(() -> {
            try {
                return factory.get();
            } catch (Throwable t) {
                LOG.error("[FFEntities] Failed to create EntityType for '{}'", name, t);
                return factory.get();
            }
        });

        Supplier<? extends EntityType<?>> previous = ENTITY_MAP.put(name, memoized);
        if (previous != null) {
            LOG.warn("[FFEntities] Duplicate common entity key '{}' detected; overwriting previous supplier", name);
        }

        return memoized;
    }

    private FFEntities() {
    }
}
