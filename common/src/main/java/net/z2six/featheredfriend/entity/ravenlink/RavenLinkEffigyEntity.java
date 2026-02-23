package net.z2six.featheredfriend.entity.ravenlink;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Server-side placeholder body while Raven Link is active.
 * This entity is intentionally static and non-interactive except for being hittable.
 */
public class RavenLinkEffigyEntity extends PathfinderMob {

    private static final String NBT_LINKED_OWNER = "LinkedOwner";
    private static final String NBT_POSE_SNAPSHOT = "PoseSnapshot";

    private static final EntityDataAccessor<Boolean> DATA_HAS_POSE_SNAPSHOT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Float> DATA_HEAD_XROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEAD_YROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEAD_ZROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_BODY_XROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_BODY_YROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_BODY_ZROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_RIGHT_ARM_XROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RIGHT_ARM_YROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RIGHT_ARM_ZROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_LEFT_ARM_XROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_LEFT_ARM_YROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_LEFT_ARM_ZROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_RIGHT_LEG_XROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RIGHT_LEG_YROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RIGHT_LEG_ZROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_LEFT_LEG_XROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_LEFT_LEG_YROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_LEFT_LEG_ZROT =
            SynchedEntityData.defineId(RavenLinkEffigyEntity.class, EntityDataSerializers.FLOAT);

    private @Nullable UUID linkedOwnerUuid;

    public RavenLinkEffigyEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D);
    }

    @Override
    protected void registerGoals() {
        // Intentionally empty: this is a static effigy.
    }

    @Override
    protected void defineSynchedData(@NotNull SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_HAS_POSE_SNAPSHOT, false);
        builder.define(DATA_HEAD_XROT, 0.0F);
        builder.define(DATA_HEAD_YROT, 0.0F);
        builder.define(DATA_HEAD_ZROT, 0.0F);
        builder.define(DATA_BODY_XROT, 0.0F);
        builder.define(DATA_BODY_YROT, 0.0F);
        builder.define(DATA_BODY_ZROT, 0.0F);
        builder.define(DATA_RIGHT_ARM_XROT, 0.0F);
        builder.define(DATA_RIGHT_ARM_YROT, 0.0F);
        builder.define(DATA_RIGHT_ARM_ZROT, 0.0F);
        builder.define(DATA_LEFT_ARM_XROT, 0.0F);
        builder.define(DATA_LEFT_ARM_YROT, 0.0F);
        builder.define(DATA_LEFT_ARM_ZROT, 0.0F);
        builder.define(DATA_RIGHT_LEG_XROT, 0.0F);
        builder.define(DATA_RIGHT_LEG_YROT, 0.0F);
        builder.define(DATA_RIGHT_LEG_ZROT, 0.0F);
        builder.define(DATA_LEFT_LEG_XROT, 0.0F);
        builder.define(DATA_LEFT_LEG_YROT, 0.0F);
        builder.define(DATA_LEFT_LEG_ZROT, 0.0F);
    }

    public void setLinkedOwnerUuid(@Nullable UUID ownerUuid) {
        this.linkedOwnerUuid = ownerUuid;
    }

    public @Nullable UUID getLinkedOwnerUuid() {
        return linkedOwnerUuid;
    }

    public boolean hasPoseSnapshot() {
        try {
            return this.entityData.get(DATA_HAS_POSE_SNAPSHOT);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void setPoseSnapshot(float headXRot, float headYRot, float headZRot,
                                float bodyXRot, float bodyYRot, float bodyZRot,
                                float rightArmXRot, float rightArmYRot, float rightArmZRot,
                                float leftArmXRot, float leftArmYRot, float leftArmZRot,
                                float rightLegXRot, float rightLegYRot, float rightLegZRot,
                                float leftLegXRot, float leftLegYRot, float leftLegZRot) {
        try {
            this.entityData.set(DATA_HAS_POSE_SNAPSHOT, true);
            this.entityData.set(DATA_HEAD_XROT, headXRot);
            this.entityData.set(DATA_HEAD_YROT, headYRot);
            this.entityData.set(DATA_HEAD_ZROT, headZRot);
            this.entityData.set(DATA_BODY_XROT, bodyXRot);
            this.entityData.set(DATA_BODY_YROT, bodyYRot);
            this.entityData.set(DATA_BODY_ZROT, bodyZRot);
            this.entityData.set(DATA_RIGHT_ARM_XROT, rightArmXRot);
            this.entityData.set(DATA_RIGHT_ARM_YROT, rightArmYRot);
            this.entityData.set(DATA_RIGHT_ARM_ZROT, rightArmZRot);
            this.entityData.set(DATA_LEFT_ARM_XROT, leftArmXRot);
            this.entityData.set(DATA_LEFT_ARM_YROT, leftArmYRot);
            this.entityData.set(DATA_LEFT_ARM_ZROT, leftArmZRot);
            this.entityData.set(DATA_RIGHT_LEG_XROT, rightLegXRot);
            this.entityData.set(DATA_RIGHT_LEG_YROT, rightLegYRot);
            this.entityData.set(DATA_RIGHT_LEG_ZROT, rightLegZRot);
            this.entityData.set(DATA_LEFT_LEG_XROT, leftLegXRot);
            this.entityData.set(DATA_LEFT_LEG_YROT, leftLegYRot);
            this.entityData.set(DATA_LEFT_LEG_ZROT, leftLegZRot);
        } catch (Throwable ignored) {
        }
    }

    public float snapshotHeadXRot() { return this.entityData.get(DATA_HEAD_XROT); }
    public float snapshotHeadYRot() { return this.entityData.get(DATA_HEAD_YROT); }
    public float snapshotHeadZRot() { return this.entityData.get(DATA_HEAD_ZROT); }
    public float snapshotBodyXRot() { return this.entityData.get(DATA_BODY_XROT); }
    public float snapshotBodyYRot() { return this.entityData.get(DATA_BODY_YROT); }
    public float snapshotBodyZRot() { return this.entityData.get(DATA_BODY_ZROT); }
    public float snapshotRightArmXRot() { return this.entityData.get(DATA_RIGHT_ARM_XROT); }
    public float snapshotRightArmYRot() { return this.entityData.get(DATA_RIGHT_ARM_YROT); }
    public float snapshotRightArmZRot() { return this.entityData.get(DATA_RIGHT_ARM_ZROT); }
    public float snapshotLeftArmXRot() { return this.entityData.get(DATA_LEFT_ARM_XROT); }
    public float snapshotLeftArmYRot() { return this.entityData.get(DATA_LEFT_ARM_YROT); }
    public float snapshotLeftArmZRot() { return this.entityData.get(DATA_LEFT_ARM_ZROT); }
    public float snapshotRightLegXRot() { return this.entityData.get(DATA_RIGHT_LEG_XROT); }
    public float snapshotRightLegYRot() { return this.entityData.get(DATA_RIGHT_LEG_YROT); }
    public float snapshotRightLegZRot() { return this.entityData.get(DATA_RIGHT_LEG_ZROT); }
    public float snapshotLeftLegXRot() { return this.entityData.get(DATA_LEFT_LEG_XROT); }
    public float snapshotLeftLegYRot() { return this.entityData.get(DATA_LEFT_LEG_YROT); }
    public float snapshotLeftLegZRot() { return this.entityData.get(DATA_LEFT_LEG_ZROT); }

    public void copyVisualsFromOwner(@NotNull ServerPlayer owner) {
        setLinkedOwnerUuid(owner.getUUID());
        setCustomName(owner.getDisplayName().copy());
        setCustomNameVisible(false);
        setNoAi(true);
        setNoGravity(true);
        setSilent(true);
        setPersistenceRequired();

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            setItemSlot(slot, owner.getItemBySlot(slot).copy());
            setDropChance(slot, 0.0F);
        }
    }

    @Override
    public void tick() {
        super.tick();
        setNoAi(true);
        setNoGravity(true);
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0.0F;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(@NotNull net.minecraft.world.entity.Entity entity) {
        // no-op
    }

    @Override
    public void push(@NotNull net.minecraft.world.entity.Entity entity) {
        // no-op
    }

    @Override
    public void knockback(double strength, double x, double z) {
        // no-op
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, @NotNull DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (linkedOwnerUuid != null) {
            tag.putUUID(NBT_LINKED_OWNER, linkedOwnerUuid);
        }
        if (hasPoseSnapshot()) {
            CompoundTag snap = new CompoundTag();
            snap.putFloat("HeadXRot", snapshotHeadXRot());
            snap.putFloat("HeadYRot", snapshotHeadYRot());
            snap.putFloat("HeadZRot", snapshotHeadZRot());
            snap.putFloat("BodyXRot", snapshotBodyXRot());
            snap.putFloat("BodyYRot", snapshotBodyYRot());
            snap.putFloat("BodyZRot", snapshotBodyZRot());
            snap.putFloat("RightArmXRot", snapshotRightArmXRot());
            snap.putFloat("RightArmYRot", snapshotRightArmYRot());
            snap.putFloat("RightArmZRot", snapshotRightArmZRot());
            snap.putFloat("LeftArmXRot", snapshotLeftArmXRot());
            snap.putFloat("LeftArmYRot", snapshotLeftArmYRot());
            snap.putFloat("LeftArmZRot", snapshotLeftArmZRot());
            snap.putFloat("RightLegXRot", snapshotRightLegXRot());
            snap.putFloat("RightLegYRot", snapshotRightLegYRot());
            snap.putFloat("RightLegZRot", snapshotRightLegZRot());
            snap.putFloat("LeftLegXRot", snapshotLeftLegXRot());
            snap.putFloat("LeftLegYRot", snapshotLeftLegYRot());
            snap.putFloat("LeftLegZRot", snapshotLeftLegZRot());
            tag.put(NBT_POSE_SNAPSHOT, snap);
        }
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        linkedOwnerUuid = tag.hasUUID(NBT_LINKED_OWNER) ? tag.getUUID(NBT_LINKED_OWNER) : null;
        try {
            if (tag.contains(NBT_POSE_SNAPSHOT)) {
                CompoundTag snap = tag.getCompound(NBT_POSE_SNAPSHOT);
                setPoseSnapshot(
                        snap.getFloat("HeadXRot"), snap.getFloat("HeadYRot"), snap.getFloat("HeadZRot"),
                        snap.getFloat("BodyXRot"), snap.getFloat("BodyYRot"), snap.getFloat("BodyZRot"),
                        snap.getFloat("RightArmXRot"), snap.getFloat("RightArmYRot"), snap.getFloat("RightArmZRot"),
                        snap.getFloat("LeftArmXRot"), snap.getFloat("LeftArmYRot"), snap.getFloat("LeftArmZRot"),
                        snap.getFloat("RightLegXRot"), snap.getFloat("RightLegYRot"), snap.getFloat("RightLegZRot"),
                        snap.getFloat("LeftLegXRot"), snap.getFloat("LeftLegYRot"), snap.getFloat("LeftLegZRot")
                );
            }
        } catch (Throwable ignored) {
        }
    }
}
