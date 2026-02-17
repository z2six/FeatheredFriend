package net.z2six.featheredfriend.entity.ravenlink;

import net.minecraft.nbt.CompoundTag;
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

    public void setLinkedOwnerUuid(@Nullable UUID ownerUuid) {
        this.linkedOwnerUuid = ownerUuid;
    }

    public @Nullable UUID getLinkedOwnerUuid() {
        return linkedOwnerUuid;
    }

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
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        linkedOwnerUuid = tag.hasUUID(NBT_LINKED_OWNER) ? tag.getUUID(NBT_LINKED_OWNER) : null;
    }
}
