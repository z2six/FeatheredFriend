// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenEntity.java
package net.z2six.featheredfriend.entity.raven;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenEntity.java
 *
 * Raven entity.
 *
 * Current scope:
 *  - Exists and renders via GeckoLib.
 *  - Has a synced variant (NORMAL vs SCROLL).
 *  - Minimal tameable-capable behavior (goals + owner follow).
 *
 * Future scope (NOT implemented here):
 *  - Give scroll -> switch to SCROLL variant.
 *  - Flying AI, perching, message delivery, etc.
 */
public class RavenEntity extends TamableAnimal implements GeoEntity {

    private static final Logger LOG = LogUtils.getLogger();

    private static final EntityDataAccessor<Integer> DATA_VARIANT =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    private static final String NBT_VARIANT = "RavenVariant";

    // GeckoLib cache
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    // If your animation json uses different names, entity will render static (safe).
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");

    public RavenEntity(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_VARIANT, RavenVariant.NORMAL.id());
    }

    public RavenVariant getRavenVariant() {
        return RavenVariant.fromId(this.entityData.get(DATA_VARIANT));
    }

    public void setRavenVariant(@Nullable RavenVariant variant) {
        if (variant == null) {
            variant = RavenVariant.NORMAL;
        }
        this.entityData.set(DATA_VARIANT, variant.id());
    }

    @Override
    protected void registerGoals() {
        // Minimal “it does something” behavior set.
        this.goalSelector.addGoal(0, new FloatGoal(this));

        // 1.21.x mappings: (TamableAnimal, speed, startDist, stopDist)
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this, 1.1D, 4.0F, 2.0F));

        this.goalSelector.addGoal(6, new RandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    /**
     * Required by Animal in 1.21.x.
     * We are not implementing taming/feeding yet, so return false for now.
     */
    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    /**
     * Required by AgeableMob/TamableAnimal in 1.21.x.
     * Breeding is not part of the current scope, so we safely return null.
     */
    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob otherParent) {
        if (this.tickCount % 200 == 0) {
            LOG.debug("[RavenEntity] getBreedOffspring called but breeding is not implemented yet (returning null).");
        }
        return null;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        try {
            tag.putInt(NBT_VARIANT, this.entityData.get(DATA_VARIANT));
        } catch (Throwable t) {
            LOG.error("[RavenEntity] Failed writing NBT", t);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        try {
            if (tag.contains(NBT_VARIANT)) {
                int id = tag.getInt(NBT_VARIANT);
                this.entityData.set(DATA_VARIANT, id);
            }
        } catch (Throwable t) {
            LOG.error("[RavenEntity] Failed reading NBT", t);
        }
    }

    // ---------- GeckoLib ----------

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, this::idleController));
    }

    private <E extends RavenEntity> PlayState idleController(final AnimationState<E> state) {
        try {
            // GeckoLib4 idiomatic: setAndContinue
            return state.setAndContinue(ANIM_IDLE);
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] Failed to apply idle animation (check animation names in json): {}", t.toString());
            }
            return PlayState.CONTINUE;
        }
    }
}
