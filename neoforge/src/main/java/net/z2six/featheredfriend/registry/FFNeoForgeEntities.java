// neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeEntities.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeEntities.java
 *
 * NeoForge entity registry for FeatheredFriend.
 *
 * Registers:
 *  - Raven EntityType
 *  - Raven attribute supplier (MOD bus event)
 */
public final class FFNeoForgeEntities {

    private static final Logger LOG = LogUtils.getLogger();

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Constants.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<RavenEntity>> RAVEN =
            ENTITY_TYPES.register("raven", () -> EntityType.Builder
                    .of(RavenEntity::new, MobCategory.CREATURE)
                    // Reasonable small-bird-ish hitbox; tweak later if needed
                    .sized(0.55F, 0.8F)
                    .clientTrackingRange(8)
                    .build(Constants.MOD_ID + ":raven"));

    private FFNeoForgeEntities() {
    }

    public static void register(IEventBus modEventBus) {
        LOG.info("[FFNeoForgeEntities] Registering entity types");
        ENTITY_TYPES.register(modEventBus);

        // Attributes are registered via MOD bus event listener (NOT EventBusSubscriber).
        try {
            modEventBus.addListener(FFNeoForgeEntities::onEntityAttributeCreation);
            LOG.info("[FFNeoForgeEntities] Hooked EntityAttributeCreationEvent listener");
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeEntities] Failed to hook EntityAttributeCreationEvent listener", t);
        }
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        LOG.info("[FFNeoForgeEntities] Creating attributes for registered entities");
        try {
            event.put(RAVEN.get(), createRavenAttributes().build());
            LOG.info("[FFNeoForgeEntities] Registered Raven attributes");
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeEntities] Failed to register Raven attributes", t);
        }
    }

    private static AttributeSupplier.Builder createRavenAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 16.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D);
    }
}
