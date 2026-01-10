package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;

/**
 * Forge entity registry for FeatheredFriend.
 */
public final class FFForgeEntities {

    private static final Logger LOG = LogUtils.getLogger();

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Constants.MOD_ID);

    private static final float RAVEN_HITBOX_WIDTH = 0.70F;
    private static final float RAVEN_HITBOX_HEIGHT = 0.70F;

    public static final RegistryObject<EntityType<RavenEntity>> RAVEN =
            ENTITY_TYPES.register("raven", () -> {
                LOG.info("[FFForgeEntities] Building Raven EntityType with hitbox w={} h={}",
                        RAVEN_HITBOX_WIDTH, RAVEN_HITBOX_HEIGHT);

                return EntityType.Builder
                        .of(RavenEntity::new, MobCategory.CREATURE)
                        .sized(RAVEN_HITBOX_WIDTH, RAVEN_HITBOX_HEIGHT)
                        .clientTrackingRange(8)
                        .build(Constants.MOD_ID + ":raven");
            });

    private FFForgeEntities() {
    }

    public static void register(IEventBus modEventBus) {
        LOG.info("[FFForgeEntities] Registering entity types");
        ENTITY_TYPES.register(modEventBus);

        try {
            modEventBus.addListener(FFForgeEntities::onEntityAttributeCreation);
            LOG.info("[FFForgeEntities] Hooked EntityAttributeCreationEvent listener");
        } catch (Throwable t) {
            LOG.error("[FFForgeEntities] Failed to hook EntityAttributeCreationEvent listener", t);
        }
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        LOG.info("[FFForgeEntities] Creating attributes for registered entities");
        try {
            event.put(RAVEN.get(), createRavenAttributes().build());
            LOG.info("[FFForgeEntities] Registered Raven attributes");
        } catch (Throwable t) {
            LOG.error("[FFForgeEntities] Failed to register Raven attributes", t);
        }
    }

    private static AttributeSupplier.Builder createRavenAttributes() {
        try {
            return Mob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, 16.0D)
                    .add(Attributes.MOVEMENT_SPEED, 0.28D);
        } catch (Throwable t) {
            LOG.error("[FFForgeEntities] createRavenAttributes failed; falling back to minimal attributes", t);
            return Mob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, 16.0D);
        }
    }
}
