// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeEntities.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.ravenlink.RavenLinkEffigyEntity;
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
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Constants.MOD_ID);

    public static final RegistryObject<EntityType<RavenEntity>> RAVEN =
            ENTITY_TYPES.register("raven", FFEntities.RAVEN);
    public static final RegistryObject<EntityType<RavenLinkEffigyEntity>> RAVEN_LINK_EFFIGY =
            ENTITY_TYPES.register("raven_link_effigy", FFEntities.RAVEN_LINK_EFFIGY);

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
        try {
            event.put(RAVEN_LINK_EFFIGY.get(), FFEntities.createRavenLinkEffigyAttributes().build());
            LOG.debug("[FFNeoForgeEntities] Registered Raven Link effigy attributes");
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeEntities] Failed to register Raven Link effigy attributes", t);
        }
    }

    private static AttributeSupplier.Builder createRavenAttributes() {
        return FFEntities.createRavenAttributes();
    }
}
