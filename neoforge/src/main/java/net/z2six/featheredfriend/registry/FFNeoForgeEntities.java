// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeEntities.java
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
 *
 * Hitbox sizing:
 *  - Gameplay hitbox (collision/selection) is controlled by EntityType.Builder.sized(width, height).
 *  - Blockbench "hitbox" cubes do NOT affect Minecraft's collision box.
 */
public final class FFNeoForgeEntities {

    private static final Logger LOG = LogUtils.getLogger();

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Constants.MOD_ID);

    /**
     * Raven hitbox tuning (gameplay collision/selection).
     *
     * Width is X/Z diameter in blocks, height is Y in blocks.
     * 1.0F x 1.0F is huge for a bird. Start smaller and tune by feel in-game.
     */
    private static final float RAVEN_HITBOX_WIDTH = 0.70F;
    private static final float RAVEN_HITBOX_HEIGHT = 0.70F;

    public static final DeferredHolder<EntityType<?>, EntityType<RavenEntity>> RAVEN =
            ENTITY_TYPES.register("raven", () -> {
                try {
                    LOG.debug("[FFNeoForgeEntities] Building Raven EntityType with hitbox w={} h={}",
                            RAVEN_HITBOX_WIDTH, RAVEN_HITBOX_HEIGHT);

                    return EntityType.Builder
                            .of(RavenEntity::new, MobCategory.CREATURE)
                            .sized(RAVEN_HITBOX_WIDTH, RAVEN_HITBOX_HEIGHT)
                            .clientTrackingRange(8)
                            .build(Constants.MOD_ID + ":raven");
                } catch (Throwable t) {
                    LOG.error("[FFNeoForgeEntities] Failed building Raven EntityType", t);

                    // Fail safe: still return *something* to avoid hard-crash during registry bootstrap.
                    // If this ever triggers, you WANT the log + a broken entity rather than a dead game boot.
                    return EntityType.Builder
                            .of(RavenEntity::new, MobCategory.CREATURE)
                            .sized(RAVEN_HITBOX_WIDTH, RAVEN_HITBOX_HEIGHT)
                            .clientTrackingRange(8)
                            .build(Constants.MOD_ID + ":raven");
                }
            });

    private FFNeoForgeEntities() {
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("[FFNeoForgeEntities] Registering entity types");
        try {
            ENTITY_TYPES.register(modEventBus);
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeEntities] ENTITY_TYPES.register(modEventBus) failed", t);
        }

        // Attributes are registered via MOD bus event listener (NOT EventBusSubscriber).
        try {
            modEventBus.addListener(FFNeoForgeEntities::onEntityAttributeCreation);
            LOG.debug("[FFNeoForgeEntities] Hooked EntityAttributeCreationEvent listener");
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeEntities] Failed to hook EntityAttributeCreationEvent listener", t);
        }
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        LOG.debug("[FFNeoForgeEntities] Creating attributes for registered entities");
        try {
            event.put(RAVEN.get(), createRavenAttributes().build());
            LOG.debug("[FFNeoForgeEntities] Registered Raven attributes");
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeEntities] Failed to register Raven attributes", t);
        }
    }

    private static AttributeSupplier.Builder createRavenAttributes() {
        try {
            return Mob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, 16.0D)
                    .add(Attributes.MOVEMENT_SPEED, 0.28D);
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeEntities] createRavenAttributes failed; falling back to minimal attributes", t);
            return Mob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, 16.0D);
        }
    }
}
