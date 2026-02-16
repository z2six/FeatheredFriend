// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeEntities.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.registry.FFEntities;
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

    public static final DeferredHolder<EntityType<?>, EntityType<RavenEntity>> RAVEN =
            ENTITY_TYPES.register("raven", FFEntities.RAVEN);

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
            event.put(RAVEN.get(), FFEntities.createRavenAttributes().build());
            LOG.debug("[FFNeoForgeEntities] Registered Raven attributes");
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeEntities] Failed to register Raven attributes", t);
        }
    }

    private static AttributeSupplier.Builder createRavenAttributes() {
        return FFEntities.createRavenAttributes();
    }
}
