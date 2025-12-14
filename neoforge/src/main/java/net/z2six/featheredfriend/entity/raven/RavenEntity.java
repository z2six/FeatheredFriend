// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenEntity.java
package net.z2six.featheredfriend.entity.raven;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
 * Raven entity with phase-based AI focusing on:
 *  - Perching ~75% of the time (IDLE_GROUND on LEAVES, NO_AIR).
 *  - Roaming/flying ~25% of the time (ROAM_FLY free flight) before attempting a landing.
 *
 * Landing design (requested):
 *  1) Pick a LEAVES block that has air above it (REQUIRED: 10 blocks of air above).
 *     Also avoid canopy edges by requiring thick canopy neighbors.
 *  2) Fly to the "overhead" point: the 2nd block above that leaves block (centered).
 *  3) Once overhead is reached, slowly descend while still in IN_AIR animation.
 *  4) When ~1 block above the final target AND centered, switch to NO_AIR and "drop" with gravity.
 *
 * IMPORTANT:
 *  - Landing is a standalone phase: once started, it runs to completion (or times out / target becomes invalid),
 *    and does NOT get interrupted by the roam-timer.
 *  - The roam-timer (ROAM_MIN/MAX) is used as a "minimum flight window" before landing is allowed to start,
 *    which gives a stable perch/flight ratio.
 */
public class RavenEntity extends TamableAnimal implements GeoEntity {

    private static final Logger LOG = LogUtils.getLogger();

    // Variant
    private static final EntityDataAccessor<Integer> DATA_VARIANT =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    // Animation mode (manual override system)
    private static final EntityDataAccessor<Integer> DATA_ANIM_MODE =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    // AI state + follow cooldown
    private static final EntityDataAccessor<Integer> DATA_AI_STATE =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_FOLLOW_COOLDOWN_TICKS =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    private static final String NBT_VARIANT = "RavenVariant";
    private static final String NBT_ANIM_MODE = "RavenAnimMode";
    private static final String NBT_AI_STATE = "RavenAIState";
    private static final String NBT_FOLLOW_CD = "RavenFollowCooldown";

    private static final String NBT_HOME_INIT = "RavenHomeInit";
    private static final String NBT_HOME_X = "RavenHomeX";
    private static final String NBT_HOME_Y = "RavenHomeY";
    private static final String NBT_HOME_Z = "RavenHomeZ";

    // Home bounds
    private static final int HOME_RADIUS_BLOCKS = 30;
    private static final int HOME_Y_DELTA = 15;

    // Idle (15-30s) NOTE: 20 ticks = 1 second
    private static final int IDLE_MIN_TICKS = 15 * 20;
    private static final int IDLE_MAX_TICKS = 30 * 20;

    // Roam flight window before landing is allowed (4-10s) NOTE: 20 ticks = 1 second
    private static final int ROAM_MIN_TICKS = 4 * 20;
    private static final int ROAM_MAX_TICKS = 10 * 20;

    // Target selection search
    private static final int LAND_SEARCH_RADIUS = 18;
    private static final int LAND_SEARCH_ATTEMPTS = 60;
    private static final int LAND_SCAN_DOWN = 32;

    // REQUIRED: always require 10 blocks of air above the leaves block.
    private static final int LAND_REQUIRED_AIR_ABOVE = 10;

    // Canopy edge avoidance: require thick canopy neighbors around the leaf block.
    private static final int CANOPY_NEIGHBOR_LEAVES_REQUIRED = 5;

    // Stuck / avoidance (used in free-flight and overhead approach)
    private static final int STUCK_TICKS_THRESHOLD = 30;
    private static final double STUCK_PROGRESS_EPS = 0.06D;
    private static final int AVOIDANCE_COOLDOWN_TICKS = 8;

    // Follow: desired distance band
    private static final double FOLLOW_MIN_DIST = 3.0D;
    private static final double FOLLOW_MAX_DIST = 8.0D;

    // Follow cooldown after bounds violation
    private static final int FOLLOW_COOLDOWN_MIN_TICKS = 5 * 20;
    private static final int FOLLOW_COOLDOWN_MAX_TICKS = 10 * 20;

    // Movement tuning
    private static final double FLY_SPEED_BASE = 0.25D;
    private static final double FLY_SPEED_RETURN = 0.32D;
    private static final double ARRIVE_DIST = 1.2D;

    // Landing phases tuning
    private static final int LANDING_MAX_TOTAL_TICKS = 8 * 20;

    // Overhead target: centered 2 blocks above leaf, slightly padded upward for smoother approach
    private static final double OVERHEAD_Y_OFFSET_FROM_LEAF = 2.25D;

    // Slow descent
    private static final double DESCEND_SPEED_Y = -0.06D;
    private static final double DESCEND_SPEED_Y_MIN = -0.12D;
    private static final double DESCEND_SPEED_Y_MAX_UP = 0.04D;

    // Horizontal centering during descent
    private static final double DESCEND_CENTER_SPEED = 0.10D;
    private static final double DESCEND_CENTER_MAX = 0.12D;

    // Phase thresholds
    private static final double OVERHEAD_HORIZONTAL_EPS = 0.55D;
    private static final double OVERHEAD_VERTICAL_EPS = 0.75D;

    // Drop trigger: centered + about 1 block above leaf top surface
    private static final double DROP_START_ABOVE_LEAF_TOP_Y = 1.15D;
    private static final double DROP_CENTER_EPS = 0.32D;

    // Drop physics
    private static final double DROP_NUDGE_Y = -0.18D;

    // IDLE stability lock + leaf-loss grace counter
    private static final int IDLE_LOCK_TICKS = 50; // ~2.5s
    private static final int IDLE_LEAF_LOSS_GRACE_TICKS = 10; // sustained loss before leaving idle

    // Settling physics while idle-locking above leaves (must not cancel Y)
    private static final double IDLE_SETTLE_NUDGE_DOWN = -0.10D;
    private static final double IDLE_SETTLE_MIN_FALL = -0.28D;
    private static final double IDLE_SETTLE_MAX_UP = 0.04D;

    // Debug throttle
    private static final int DEBUG_LOG_INTERVAL_TICKS = 120;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final RavenControl control = new RavenControl(this);

    // GeckoLib animations
    private static final RawAnimation ANIM_NO_AIR = RawAnimation.begin().thenLoop("animation.raven.no_air");
    private static final RawAnimation ANIM_IN_AIR = RawAnimation.begin().thenLoop("animation.raven.in_air");

    // Home point (server-authoritative)
    private boolean homeInitialized = false;
    private BlockPos homePos = BlockPos.ZERO;

    // Simple flight target (server-side)
    @Nullable
    private Vec3 flyTarget = null;

    private int flyTargetTimeoutTicks = 0;

    // Idle timer + turning
    private int idleTicksRemaining = 0;
    private float idleTargetYaw = 0.0F;
    private int idleNextTurnTicks = 0;

    /**
     * Roam flight window remaining ticks:
     *  - While > 0 in ROAM_FLY and landingPhase==NONE, the raven will stay in flight and NOT start a landing.
     *  - When reaches 0, the raven is allowed to start landing (landingPhase transitions from NONE).
     */
    private int roamTicksRemaining = 0;

    // Stuck detection
    private double lastDistToTarget = Double.NaN;
    private int stuckTicks = 0;

    // Avoidance throttle
    private int avoidanceCooldownTicks = 0;

    // Landing state machine (ROAM_FLY only)
    private enum LandingPhase {
        NONE,
        FLY_TO_OVERHEAD,
        DESCEND_SLOW,
        DROP
    }

    // IDLE stability lock + leaf-loss grace counter
    private int idleLockTicks = 0;
    private int idleLeafLossTicks = 0;

    // One-time settle-to-ground logic when entering IDLE_GROUND.
    // Goal: when we *start perching* (standing still + random yaw), enable gravity and let the entity
    // physically settle onto the leaves collision, then mark complete the moment we touch ground.
    // This must run ONLY ONCE per IDLE entry.
    private boolean idleSettleArmed = false;     // armed when we enter IDLE_GROUND from any other state
    private boolean idleSettlingActive = false;  // true while waiting for onGround() to become true

    private LandingPhase landingPhase = LandingPhase.NONE;
    private int landingTicks = 0;

    @Nullable
    private BlockPos landingLeafPos = null;

    public RavenEntity(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
    }

    public RavenControl getControl() {
        return control;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_VARIANT, RavenVariant.NORMAL.id());
        builder.define(DATA_ANIM_MODE, RavenAnimMode.AUTO.id());
        builder.define(DATA_AI_STATE, RavenAIState.IDLE_GROUND.id());
        builder.define(DATA_FOLLOW_COOLDOWN_TICKS, 0);
    }

    // -----------------
    // Variant API
    // -----------------

    public RavenVariant getRavenVariant() {
        return RavenVariant.fromId(this.entityData.get(DATA_VARIANT));
    }

    public void setRavenVariant(@Nullable RavenVariant variant) {
        if (variant == null) {
            variant = RavenVariant.NORMAL;
        }
        this.entityData.set(DATA_VARIANT, variant.id());
    }

    // -----------------
    // Animation mode API
    // -----------------

    public RavenAnimMode getAnimMode() {
        return RavenAnimMode.fromId(this.entityData.get(DATA_ANIM_MODE));
    }

    public void setAnimMode(@Nullable RavenAnimMode mode) {
        if (mode == null) {
            mode = RavenAnimMode.AUTO;
        }
        this.entityData.set(DATA_ANIM_MODE, mode.id());
    }

    // -----------------
    // AI state API
    // -----------------

    public RavenAIState getAIState() {
        return RavenAIState.fromId(this.entityData.get(DATA_AI_STATE));
    }

    public void setAIState(@Nullable RavenAIState state) {
        if (state == null) {
            state = RavenAIState.IDLE_GROUND;
        }

        try {
            // Arm settle logic ONLY when transitioning into IDLE_GROUND from a different state (server-side).
            if (!this.level().isClientSide) {
                RavenAIState prev = RavenAIState.fromId(this.entityData.get(DATA_AI_STATE));
                if (state == RavenAIState.IDLE_GROUND && prev != RavenAIState.IDLE_GROUND) {
                    idleSettleArmed = true;
                    idleSettlingActive = true;

                    if (this.tickCount % 40 == 0) {
                        LOG.debug("[RavenEntity] Armed one-time idle settle (prev={}, next={}) pos={} bbMinY={} vel={}",
                                prev, state, this.position(), this.getBoundingBox().minY, this.getDeltaMovement());
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] setAIState arm-settle failed: {}", t.toString());
            // Never crash AI because of arming logic
        }

        this.entityData.set(DATA_AI_STATE, state.id());
    }

    private int getFollowCooldownTicks() {
        return this.entityData.get(DATA_FOLLOW_COOLDOWN_TICKS);
    }

    private void setFollowCooldownTicks(int ticks) {
        this.entityData.set(DATA_FOLLOW_COOLDOWN_TICKS, Math.max(0, ticks));
    }

    // -----------------
    // Home initialization + bounds helpers
    // -----------------

    private void ensureHomeInitialized() {
        if (homeInitialized) {
            return;
        }
        homeInitialized = true;
        homePos = this.blockPosition();
        if (this.tickCount % 20 == 0) {
            LOG.info("[RavenEntity] Home initialized at {}", homePos);
        }
    }

    private int clampYToHomeBounds(int y) {
        int minY = homePos.getY() - HOME_Y_DELTA;
        int maxY = homePos.getY() + HOME_Y_DELTA;
        return Mth.clamp(y, minY, maxY);
    }

    private Vec3 clampTargetToHomeBounds(Vec3 target) {
        double clampedY = clampYToHomeBounds((int) Math.round(target.y));

        double hx = homePos.getX() + 0.5D;
        double hz = homePos.getZ() + 0.5D;

        double dx = target.x - hx;
        double dz = target.z - hz;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist <= HOME_RADIUS_BLOCKS) {
            return new Vec3(target.x, clampedY, target.z);
        }

        double scale = HOME_RADIUS_BLOCKS / Math.max(0.0001D, dist);
        double nx = hx + dx * scale;
        double nz = hz + dz * scale;
        return new Vec3(nx, clampedY, nz);
    }

    private boolean isOutOfHomeBounds(Vec3 pos) {
        double hx = homePos.getX() + 0.5D;
        double hz = homePos.getZ() + 0.5D;

        double dx = pos.x - hx;
        double dz = pos.z - hz;

        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > HOME_RADIUS_BLOCKS + 0.75D) {
            return true;
        }

        int y = (int) Math.floor(pos.y);
        int minY = homePos.getY() - HOME_Y_DELTA;
        int maxY = homePos.getY() + HOME_Y_DELTA;
        return y < minY || y > maxY;
    }

    private Vec3 homeCenterReturnTarget() {
        double hx = homePos.getX() + 0.5D;
        double hz = homePos.getZ() + 0.5D;
        double y = clampYToHomeBounds(homePos.getY());
        return new Vec3(hx, y, hz);
    }

    // -----------------
    // Movement command API
    // -----------------

    private void setFlyTarget(Vec3 target, int timeoutTicks) {
        Vec3 clamped = clampTargetToHomeBounds(target);
        this.flyTarget = clamped;
        this.flyTargetTimeoutTicks = Math.max(20, timeoutTicks);
        this.lastDistToTarget = Double.NaN;
        this.stuckTicks = 0;
    }

    private void clearFlyTarget() {
        this.flyTarget = null;
        this.flyTargetTimeoutTicks = 0;
        this.lastDistToTarget = Double.NaN;
        this.stuckTicks = 0;
    }

    public boolean commandMoveTo(BlockPos pos, double speed) {
        if (pos == null) {
            LOG.warn("[RavenEntity] commandMoveTo called with null pos");
            return false;
        }
        if (speed <= 0.0D) {
            LOG.warn("[RavenEntity] commandMoveTo called with non-positive speed: {}", speed);
            return false;
        }

        try {
            ensureHomeInitialized();
            Vec3 target = new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            setFlyTarget(target, 12 * 20);
            return true;
        } catch (Throwable t) {
            LOG.error("[RavenEntity] commandMoveTo failed for {} @ {}", pos, speed, t);
            return false;
        }
    }

    public void commandStopMoving() {
        clearFlyTarget();
        this.setDeltaMovement(Vec3.ZERO);
    }

    // -----------------
    // Goals
    // -----------------

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 10.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    // -----------------
    // Vanilla required overrides
    // -----------------

    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob otherParent) {
        if (this.tickCount % 200 == 0) {
            LOG.debug("[RavenEntity] getBreedOffspring called but breeding is not implemented (returning null).");
        }
        return null;
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected void checkInsideBlocks() {
        try {
            if (this.isNoGravity()) {
                return;
            }
            super.checkInsideBlocks();
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] checkInsideBlocks failed: {}", t.toString());
            }
        }
    }

    // -----------------
    // NBT persistence
    // -----------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        try {
            tag.putInt(NBT_VARIANT, this.entityData.get(DATA_VARIANT));
            tag.putInt(NBT_ANIM_MODE, this.entityData.get(DATA_ANIM_MODE));
            tag.putInt(NBT_AI_STATE, this.entityData.get(DATA_AI_STATE));
            tag.putInt(NBT_FOLLOW_CD, this.entityData.get(DATA_FOLLOW_COOLDOWN_TICKS));

            tag.putBoolean(NBT_HOME_INIT, homeInitialized);
            tag.putInt(NBT_HOME_X, homePos.getX());
            tag.putInt(NBT_HOME_Y, homePos.getY());
            tag.putInt(NBT_HOME_Z, homePos.getZ());
        } catch (Throwable t) {
            LOG.error("[RavenEntity] Failed writing NBT", t);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        try {
            if (tag.contains(NBT_VARIANT)) {
                this.entityData.set(DATA_VARIANT, tag.getInt(NBT_VARIANT));
            }
            if (tag.contains(NBT_ANIM_MODE)) {
                this.entityData.set(DATA_ANIM_MODE, tag.getInt(NBT_ANIM_MODE));
            }
            if (tag.contains(NBT_AI_STATE)) {
                this.entityData.set(DATA_AI_STATE, tag.getInt(NBT_AI_STATE));
            }
            if (tag.contains(NBT_FOLLOW_CD)) {
                this.entityData.set(DATA_FOLLOW_COOLDOWN_TICKS, tag.getInt(NBT_FOLLOW_CD));
            }

            if (tag.contains(NBT_HOME_INIT)) {
                this.homeInitialized = tag.getBoolean(NBT_HOME_INIT);
            }
            if (tag.contains(NBT_HOME_X) && tag.contains(NBT_HOME_Y) && tag.contains(NBT_HOME_Z)) {
                this.homePos = new BlockPos(tag.getInt(NBT_HOME_X), tag.getInt(NBT_HOME_Y), tag.getInt(NBT_HOME_Z));
            }
        } catch (Throwable t) {
            LOG.error("[RavenEntity] Failed reading NBT", t);
        }
    }

    // -----------------
    // AI tick
    // -----------------

    @Override
    public void aiStep() {
        super.aiStep();

        if (this.level().isClientSide) {
            return;
        }

        try {
            ensureHomeInitialized();

            int cd = getFollowCooldownTicks();
            if (cd > 0) {
                setFollowCooldownTicks(cd - 1);
            }

            if (avoidanceCooldownTicks > 0) {
                avoidanceCooldownTicks--;
            }

            if (isOutOfHomeBounds(this.position())) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] Out of bounds, commanding return to home bounds");
                }
                setAIState(RavenAIState.ROAM_FLY);
                resetLandingState("out-of-bounds");
                idleLockTicks = 0;
                idleLeafLossTicks = 0;

                // Force a return target immediately; keep roam window running (or start a short one) so we don't instantly land.
                setFlyTarget(homeCenterReturnTarget(), 10 * 20);
                if (roamTicksRemaining <= 0) {
                    beginRoamFlightWindow("out-of-bounds return");
                    // Keep the return target (beginRoamFlightWindow might set a random roam target)
                    setFlyTarget(homeCenterReturnTarget(), 10 * 20);
                }
            }

            Player owner = getOwnerPlayerServerSafe();
            boolean canFollow = owner != null && this.isTame() && getFollowCooldownTicks() <= 0;

            if (canFollow) {
                if (isOutOfHomeBounds(owner.position())) {
                    triggerFollowCooldownAndReturn();
                } else {
                    setAIState(RavenAIState.FOLLOW_OWNER);
                }
            } else {
                RavenAIState st = getAIState();
                if (st == RavenAIState.FOLLOW_OWNER) {
                    setAIState(RavenAIState.IDLE_GROUND);
                    idleTicksRemaining = 0;
                }
            }

            switch (getAIState()) {
                case IDLE_GROUND -> tickIdleGround();
                case ROAM_FLY -> tickRoamFly();
                case FOLLOW_OWNER -> tickFollowOwner();
                default -> tickIdleGround();
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] aiStep failed", t);
        }
    }

    /**
     * IDLE_GROUND:
     *  - If leaves are under feet: full standstill, random yaw turns, NO_AIR.
     *  - If not yet (e.g. barely above canopy): gravity must pull down; do NOT cancel Y velocity.
     *
     * Surgical fix requested:
     *  - When we ENTER IDLE_GROUND (perching begins), run a ONE-TIME settle step:
     *      1) enable gravity
     *      2) keep X/Z pinned (standing still)
     *      3) keep checking onGround()
     *      4) the instant onGround() becomes true, the settle step is COMPLETE and never runs again
     *         for this IDLE cycle. Idle continues normally afterward.
     */
    private void tickIdleGround() {
        boolean leafUnder = isLeafUnderFeet();

        // --- ONE-TIME SETTLE STEP (ONLY ONCE PER IDLE ENTRY) ---
        // If we just entered IDLE_GROUND, we may still be hovering slightly above leaves due to collision,
        // previous noGravity state, or movement leftovers. We want to *actually* settle onto the block.
        if (idleSettleArmed && idleSettlingActive) {
            // Ensure idle visuals/physics
            this.setNoGravity(false);
            this.clearFlyTarget();
            resetLandingState("idle settle step (one-time)");
            if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
                this.setAnimMode(RavenAnimMode.NO_AIR);
            }

            // Pin horizontal movement to "standing still", but do NOT cancel Y (we want gravity to work).
            Vec3 vel = this.getDeltaMovement();
            double vy = vel.y;

            // If we're not moving downward, nudge into a small fall so the settle completes reliably.
            // (This avoids the "float 0.5 block above leaves forever" behavior.)
            if (vy > -0.05D) {
                vy = -0.05D;
            }

            this.setDeltaMovement(0.0D, vy, 0.0D);

            // Completion condition: the moment we touch ground, we're done.
            // After this, idle continues normally and this block will never run again
            // until we leave IDLE and re-enter.
            if (this.onGround()) {
                idleSettlingActive = false;
                idleSettleArmed = false;

                if (this.tickCount % 20 == 0) {
                    LOG.debug("[RavenEntity] Idle settle COMPLETE (onGround=true). pos={} vel={} leafUnder={}",
                            this.position(), this.getDeltaMovement(), leafUnder);
                }
            } else {
                // Still settling; do NOT allow the "leaf loss -> roam" logic to fire while we haven't touched ground yet.
                // That would prematurely abort the perch attempt.
                if (this.tickCount % 60 == 0) {
                    LOG.debug("[RavenEntity] Idle settle in progress... pos={} bbMinY={} vel={} leafUnder={} onGround={}",
                            this.position(), this.getBoundingBox().minY, this.getDeltaMovement(), leafUnder, this.onGround());
                }

                // IMPORTANT: while settling, we stop here. We still want yaw turning? You asked:
                // "standing still mode as event (when we start not moving and randomly rotating), then enable gravity..."
                // The yaw turning is handled below in the normal idle section; but if we return here, we skip it.
                //
                // If you want yaw turning DURING the settle fall, remove this return.
                // For the strictest "settle first, then idle", keep it.
                return;
            }
        } else {
            // Once settle step is complete (or wasn't armed), make sure the arming doesn't persist accidentally.
            // (defensive; should already be false after completion)
            if (!idleSettlingActive && idleSettleArmed) {
                idleSettleArmed = false;
            }
        }

        // --- EXISTING IDLE LEAF-LOSS LOGIC (unchanged, but now won't run during settling) ---
        if (!leafUnder) {
            idleLeafLossTicks++;
            if (idleLeafLossTicks >= IDLE_LEAF_LOSS_GRACE_TICKS) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] IDLE_GROUND aborted: leaves lost under feet for {} ticks at pos={}, switching to ROAM_FLY",
                            idleLeafLossTicks, this.position());
                }

                this.setAIState(RavenAIState.ROAM_FLY);
                this.idleTicksRemaining = 0;

                // Prevent immediate re-idle
                this.idleLockTicks = 12; // ~0.6s
                this.idleLeafLossTicks = 0;

                // Leaving IDLE: ensure settle is not armed anymore until we re-enter IDLE later.
                this.idleSettleArmed = false;
                this.idleSettlingActive = false;

                resetLandingState("idle leaf loss -> roam");
                beginRoamFlightWindow("idle leaf loss");
                return;
            }
        } else {
            idleLeafLossTicks = 0;
        }

        // Idle: NO_AIR + gravity enabled
        this.setNoGravity(false);
        this.clearFlyTarget();
        resetLandingState("enter/continue idle");
        if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
            this.setAnimMode(RavenAnimMode.NO_AIR);
        }

        // During IDLE, idleLockTicks is the "settle" lock (from landing). Keep decrementing it here.
        if (idleLockTicks > 0) {
            idleLockTicks--;
        }

        // Physics:
        //  - If not actually on leaves yet, settle: clamp X/Z to 0 but keep Y falling.
        //  - If on leaves, full standstill.
        if (!leafUnder) {
            Vec3 vel = this.getDeltaMovement();

            double vy = vel.y;
            if (vy > IDLE_SETTLE_MAX_UP) {
                vy = IDLE_SETTLE_MAX_UP;
            }
            if (vy > IDLE_SETTLE_NUDGE_DOWN) {
                vy = IDLE_SETTLE_NUDGE_DOWN;
            }
            if (vy < IDLE_SETTLE_MIN_FALL) {
                vy = IDLE_SETTLE_MIN_FALL;
            }

            this.setDeltaMovement(0.0D, vy, 0.0D);
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }

        RandomSource rnd = this.getRandom();

        if (idleTicksRemaining <= 0) {
            idleTicksRemaining = IDLE_MIN_TICKS + rnd.nextInt(Math.max(1, IDLE_MAX_TICKS - IDLE_MIN_TICKS + 1));
            idleTargetYaw = this.getYRot();
            idleNextTurnTicks = 10 + rnd.nextInt(50);

            if (this.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] Entering IDLE_GROUND for {} ticks at pos={}", idleTicksRemaining, this.position());
            }
        }

        if (idleNextTurnTicks-- <= 0) {
            idleNextTurnTicks = 20 + rnd.nextInt(60);
            idleTargetYaw = rnd.nextFloat() * 360.0F;
        }

        float current = this.getYRot();
        float diff = Mth.wrapDegrees(idleTargetYaw - current);
        float step = Mth.clamp(diff, -4.0F, 4.0F);

        this.setYRot(current + step);
        this.setYHeadRot(this.getYRot());
        this.yBodyRot = this.getYRot();

        idleTicksRemaining--;

        if (idleTicksRemaining <= 0) {
            // Leaving idle -> start a roam flight window BEFORE landing is allowed.
            setAIState(RavenAIState.ROAM_FLY);

            // Prevent immediate re-idle
            this.idleLockTicks = 12; // ~0.6s takeoff lock
            this.idleLeafLossTicks = 0;

            // Leaving IDLE: ensure settle is not armed anymore until we re-enter IDLE later.
            this.idleSettleArmed = false;
            this.idleSettlingActive = false;

            resetLandingState("idle expired -> roam");
            beginRoamFlightWindow("idle expired");
        }
    }

    /**
     * ROAM_FLY:
     *  - First, fly around freely for roamTicksRemaining ticks (minimum flight time).
     *  - When roamTicksRemaining hits 0 and landingPhase==NONE, start the landing state machine.
     *  - Once landing starts, it runs to completion (or timeout) and is NOT interrupted by roamTicksRemaining.
     */
    private void tickRoamFly() {
        RandomSource rnd = this.getRandom();

        // TAKEOFF LOCK (surgical fix):
        // When we just switched from IDLE -> ROAM_FLY, we're still physically sitting on leaves.
        // If we immediately allow the "leaf under feet => idle" rule, we will never actually take off.
        // We reuse idleLockTicks as a short takeoff lock while entering ROAM_FLY.
        if (idleLockTicks > 0) {
            idleLockTicks--;

            // Force flight mode visuals/physics during takeoff lock
            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            // Give a small upward impulse so we actually separate from the leaf collision surface.
            // This prevents the “micro-twitch” where it tries to flap but remains stuck.
            Vec3 vel = this.getDeltaMovement();
            double vy = vel.y;
            if (vy < 0.18D) {
                vy = 0.18D;
            }
            if (vy > 0.32D) {
                vy = 0.32D;
            }

            // Keep existing horizontal motion (or zero) so it doesn't instantly drift off canopy.
            this.setDeltaMovement(vel.x, vy, vel.z);

            // Ensure we have some roam target so it actually starts moving after lifting off
            if (flyTarget == null || flyTargetTimeoutTicks <= 0) {
                Vec3 roamTarget = pickRoamFallbackTarget(rnd);
                if (roamTarget != null) {
                    setFlyTarget(roamTarget, 4 * 20);
                } else {
                    clearFlyTarget();
                }
            }

            // Light steering during takeoff lock
            if (flyTarget != null) {
                if (flyTargetTimeoutTicks > 0) {
                    flyTargetTimeoutTicks--;
                }
                flyTowardTarget(FLY_SPEED_BASE);
            }

            // Do NOT do any landing/idle logic while takeoff lock is active.
            if (this.tickCount % 120 == 0) {
                LOG.debug("[RavenEntity] ROAM_FLY takeoff lock active (remaining={}) pos={} vel={}",
                        idleLockTicks, this.position(), this.getDeltaMovement());
            }
            return;
        }

        // If we actually have leaves under feet AND we are not in takeoff lock, go idle.
        if (isLeafUnderFeet()) {
            enterIdleFromLanding("ROAM_FLY: leaf under feet (no takeoff lock)");
            return;
        }

        // Landing timeout safety
        if (landingPhase != LandingPhase.NONE) {
            landingTicks++;
            if (landingTicks > LANDING_MAX_TOTAL_TICKS) {
                if (this.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] Landing timed out (phase={}, ticks={}) -> resetting landing and restarting landing (no roam interrupt)",
                            landingPhase, landingTicks);
                }
                resetLandingState("landing timeout");
            }
        } else {
            landingTicks = 0;
        }

        // Validate landing leaf target if we have one.
        if (landingLeafPos != null && !isStillValidLandingLeaf(landingLeafPos)) {
            if (this.tickCount % 80 == 0) {
                LOG.debug("[RavenEntity] Landing leaf became invalid -> resetting landing leaf at {}", landingLeafPos);
            }
            resetLandingState("invalid leaf");
        }

        // If landing hasn't started yet, enforce the minimum flight window.
        if (landingPhase == LandingPhase.NONE && roamTicksRemaining > 0) {
            roamTicksRemaining--;

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            // Pure free-flight roaming (no landing attempts)
            if (flyTarget == null || flyTargetTimeoutTicks <= 0) {
                Vec3 roamTarget = pickRoamFallbackTarget(rnd);
                if (roamTarget != null) {
                    setFlyTarget(roamTarget, 4 * 20);
                } else {
                    clearFlyTarget();
                    this.setDeltaMovement(Vec3.ZERO);
                }
            }

            if (flyTarget != null) {
                maybeAvoidOrRetargetDuringFlight(rnd);
                if (flyTargetTimeoutTicks > 0) {
                    flyTargetTimeoutTicks--;
                }
                flyTowardTarget(FLY_SPEED_BASE);
            }

            return;
        }

        // If landing is active, run landing state machine and IGNORE roamTicksRemaining.
        if (landingPhase != LandingPhase.NONE) {
            tickLandingStateMachine(rnd);
            return;
        }

        // landingPhase==NONE and roamTicksRemaining<=0 => landing is now allowed to start.
        BlockPos leaf = pickLandingLeafBlock(rnd);
        if (leaf == null) {
            // No valid leaf found: roam briefly and retry.
            int extra = 20 + rnd.nextInt(60);
            roamTicksRemaining = extra;

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            if (flyTarget == null || flyTargetTimeoutTicks <= 0) {
                Vec3 roamTarget = pickRoamFallbackTarget(rnd);
                if (roamTarget != null) {
                    setFlyTarget(roamTarget, 4 * 20);
                } else {
                    clearFlyTarget();
                    this.setDeltaMovement(Vec3.ZERO);
                }
            }

            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                LOG.debug("[RavenEntity] Landing allowed but no valid leaf found -> roaming {} ticks then retry", extra);
            }
            return;
        }

        landingLeafPos = leaf;
        landingPhase = LandingPhase.FLY_TO_OVERHEAD;
        landingTicks = 0;

        Vec3 overhead = overheadTargetForLeaf(leaf);
        setFlyTarget(overhead, 6 * 20);

        this.setNoGravity(true);
        if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
            this.setAnimMode(RavenAnimMode.IN_AIR);
        }

        if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
            LOG.debug("[RavenEntity] Landing start (post-roam): leaf={} overhead={} (airAbove={}, canopyNeighbors>={})",
                    leaf, overhead, LAND_REQUIRED_AIR_ABOVE, CANOPY_NEIGHBOR_LEAVES_REQUIRED);
        }
    }

    private void tickLandingStateMachine(RandomSource rnd) {
        // If we somehow got onto leaves mid-landing, idle.
        if (isLeafUnderFeet()) {
            enterIdleFromLanding("Landing: leaf under feet");
            return;
        }

        switch (landingPhase) {
            case NONE -> {
                // Should not happen here; caller ensures landingPhase != NONE.
                resetLandingState("tickLandingStateMachine called with NONE");
            }
            case FLY_TO_OVERHEAD -> {
                this.setNoGravity(true);
                if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                    this.setAnimMode(RavenAnimMode.IN_AIR);
                }

                if (landingLeafPos == null) {
                    resetLandingState("FLY_TO_OVERHEAD missing leaf");
                    return;
                }

                Vec3 overhead = overheadTargetForLeaf(landingLeafPos);

                // We keep the overhead as the active fly target.
                setFlyTarget(overhead, 6 * 20);

                // Allow avoidance while approaching overhead (but never random retarget to a different leaf).
                maybeAvoidOrRetargetDuringFlightApproachOnly(rnd);

                if (flyTargetTimeoutTicks > 0) {
                    flyTargetTimeoutTicks--;
                }
                flyTowardTarget(FLY_SPEED_BASE);

                // Arrival check
                Vec3 pos = this.position();
                double horiz = horizontalDistanceTo(pos, overhead);
                double vert = Math.abs(pos.y - overhead.y);

                if (horiz <= OVERHEAD_HORIZONTAL_EPS && vert <= OVERHEAD_VERTICAL_EPS) {
                    landingPhase = LandingPhase.DESCEND_SLOW;
                    clearFlyTarget(); // descent is controlled; do not use fly steering.
                    if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                        LOG.debug("[RavenEntity] Overhead reached -> DESCEND_SLOW (leaf={}, pos={}, horiz={}, vert={})",
                                landingLeafPos, pos, horiz, vert);
                    }
                }
            }
            case DESCEND_SLOW -> {
                if (landingLeafPos == null) {
                    resetLandingState("DESCEND_SLOW missing leaf");
                    return;
                }

                // Still flying animation during slow descent
                this.setNoGravity(true);
                if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                    this.setAnimMode(RavenAnimMode.IN_AIR);
                }

                Vec3 pos = this.position();
                Vec3 leafCenter = leafCenterTop(landingLeafPos);

                // Gentle centering in X/Z while descending
                Vec3 toCenter = new Vec3(leafCenter.x - pos.x, 0.0D, leafCenter.z - pos.z);
                double d2 = toCenter.length();
                Vec3 horizVel = Vec3.ZERO;
                if (d2 > 0.0001D) {
                    Vec3 dir = toCenter.scale(1.0D / d2);
                    double sp = Mth.clamp(DESCEND_CENTER_SPEED, 0.0D, DESCEND_CENTER_MAX);
                    horizVel = new Vec3(dir.x * sp, 0.0D, dir.z * sp);
                }

                // Controlled descent Y velocity
                double vy = this.getDeltaMovement().y;
                if (vy > DESCEND_SPEED_Y_MAX_UP) {
                    vy = DESCEND_SPEED_Y_MAX_UP;
                }
                if (vy > DESCEND_SPEED_Y) {
                    vy = DESCEND_SPEED_Y;
                }
                if (vy < DESCEND_SPEED_Y_MIN) {
                    vy = DESCEND_SPEED_Y_MIN;
                }

                this.setDeltaMovement(horizVel.x, vy, horizVel.z);

                // Face horizontal movement direction (optional polish)
                if (horizVel.lengthSqr() > 0.0001D) {
                    float yaw = (float) (Mth.atan2(horizVel.z, horizVel.x) * (180.0D / Math.PI)) - 90.0F;
                    this.setYRot(yaw);
                    this.setYHeadRot(yaw);
                    this.yBodyRot = yaw;
                }

                // Transition to DROP when centered + ~1 block above leaf top
                double centerDist = horizontalDistanceTo(pos, leafCenter);
                double leafTopY = landingLeafPos.getY() + 1.0D;
                double aboveTop = pos.y - leafTopY;

                if (centerDist <= DROP_CENTER_EPS && aboveTop <= (DROP_START_ABOVE_LEAF_TOP_Y + 0.25D)) {
                    landingPhase = LandingPhase.DROP;
                    if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                        LOG.debug("[RavenEntity] DESCEND_SLOW -> DROP (leaf={}, pos={}, centerDist={}, aboveTop={})",
                                landingLeafPos, pos, centerDist, aboveTop);
                    }
                }
            }
            case DROP -> {
                if (landingLeafPos == null) {
                    resetLandingState("DROP missing leaf");
                    return;
                }

                // Switch to NO_AIR and drop with gravity so collision settles on leaves.
                this.setNoGravity(false);
                if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
                    this.setAnimMode(RavenAnimMode.NO_AIR);
                }

                // Clamp horizontal to avoid drifting off during final drop; allow downward movement.
                Vec3 vel = this.getDeltaMovement();
                double vy = vel.y;
                if (vy > IDLE_SETTLE_MAX_UP) {
                    vy = IDLE_SETTLE_MAX_UP;
                }
                if (vy > DROP_NUDGE_Y) {
                    vy = DROP_NUDGE_Y;
                }
                if (vy < IDLE_SETTLE_MIN_FALL) {
                    vy = IDLE_SETTLE_MIN_FALL;
                }

                this.setDeltaMovement(0.0D, vy, 0.0D);

                // Once leaf is under feet, enter idle properly.
                if (isLeafUnderFeet()) {
                    enterIdleFromLanding("DROP: leaf under feet");
                    return;
                }

                // If we fell too far without landing on leaves, bail and immediately re-attempt landing (no roam interrupt).
                Vec3 pos = this.position();
                double leafTopY = landingLeafPos.getY() + 1.0D;
                if (pos.y < leafTopY - 1.25D) {
                    if (this.tickCount % 80 == 0) {
                        LOG.debug("[RavenEntity] DROP missed leaf (posY={}, leafTopY={}) -> resetting landing and reattempting",
                                pos.y, leafTopY);
                    }
                    resetLandingState("drop missed");
                }
            }
        }
    }

    private void tickFollowOwner() {
        this.setNoGravity(true);
        if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
            this.setAnimMode(RavenAnimMode.IN_AIR);
        }

        resetLandingState("follow");
        this.idleLockTicks = 0;
        this.idleLeafLossTicks = 0;

        Player owner = getOwnerPlayerServerSafe();
        if (owner == null || !this.isTame()) {
            setAIState(RavenAIState.IDLE_GROUND);
            idleTicksRemaining = 0;
            clearFlyTarget();
            roamTicksRemaining = 0;
            return;
        }

        if (isOutOfHomeBounds(owner.position())) {
            triggerFollowCooldownAndReturn();
            return;
        }

        Vec3 ownerPos = owner.position();
        Vec3 myPos = this.position();
        double dist = myPos.distanceTo(ownerPos);

        if (dist > FOLLOW_MAX_DIST) {
            Vec3 desired = ownerPos.add(0.0D, 2.0D, 0.0D);
            desired = clampTargetToHomeBounds(desired);
            setFlyTarget(desired, 5 * 20);
        } else if (dist < FOLLOW_MIN_DIST) {
            Vec3 away = myPos.subtract(ownerPos).normalize();
            if (away.lengthSqr() < 0.0001D) {
                away = new Vec3(1, 0, 0);
            }
            Vec3 desired = ownerPos.add(away.scale(FOLLOW_MIN_DIST)).add(0.0D, 2.0D, 0.0D);
            desired = clampTargetToHomeBounds(desired);
            setFlyTarget(desired, 3 * 20);
        } else {
            if (this.tickCount % 20 == 0) {
                clearFlyTarget();
                this.setDeltaMovement(Vec3.ZERO);
            }
        }

        if (flyTarget != null) {
            flyTarget = clampTargetToHomeBounds(flyTarget);
        }

        if (isOutOfHomeBounds(this.position())) {
            triggerFollowCooldownAndReturn();
            return;
        }

        if (flyTargetTimeoutTicks > 0) {
            flyTargetTimeoutTicks--;
        }

        maybeAvoidOrRetargetDuringFlight(this.getRandom());
        flyTowardTarget(FLY_SPEED_BASE);
    }

    private void triggerFollowCooldownAndReturn() {
        RandomSource rnd = this.getRandom();

        int cd = FOLLOW_COOLDOWN_MIN_TICKS + rnd.nextInt(Math.max(1, FOLLOW_COOLDOWN_MAX_TICKS - FOLLOW_COOLDOWN_MIN_TICKS + 1));
        setFollowCooldownTicks(cd);

        setAIState(RavenAIState.ROAM_FLY);
        setFlyTarget(homeCenterReturnTarget(), 10 * 20);
        roamTicksRemaining = 0;

        resetLandingState("follow cooldown");
        this.idleLockTicks = 0;
        this.idleLeafLossTicks = 0;

        // Start a roam flight window so follow dropouts don't instantly land again.
        beginRoamFlightWindow("follow cooldown -> roam");

        // Keep the return target (beginRoamFlightWindow might set a random roam target)
        setFlyTarget(homeCenterReturnTarget(), 10 * 20);

        if (this.tickCount % 40 == 0) {
            LOG.debug("[RavenEntity] Follow bounds violated -> cooldown {} ticks and return to home", cd);
        }
    }

    // -----------------
    // Perch/flight balance helper
    // -----------------

    private void beginRoamFlightWindow(String reason) {
        try {
            RandomSource rnd = this.getRandom();

            int span = Math.max(1, ROAM_MAX_TICKS - ROAM_MIN_TICKS + 1);
            int ticks = ROAM_MIN_TICKS + rnd.nextInt(span);
            roamTicksRemaining = Math.max(1, ticks);

            // Landing should not be active during roam window.
            resetLandingState("beginRoamFlightWindow: " + reason);

            // Ensure flight physics/anim
            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            // Provide an initial roam target so it visibly flies around.
            Vec3 roamTarget = pickRoamFallbackTarget(rnd);
            if (roamTarget != null) {
                setFlyTarget(roamTarget, 4 * 20);
            } else {
                clearFlyTarget();
            }

            // SURGICAL FIX:
            // Make sure we never immediately snap back to idle in the very first ROAM_FLY tick
            // just because we're still sitting on leaves. We reuse idleLockTicks here.
            if (idleLockTicks <= 0) {
                idleLockTicks = 12; // ~0.6s takeoff lock
            }

            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                LOG.debug("[RavenEntity] beginRoamFlightWindow(reason={}) -> roamTicksRemaining={} takeoffLock={} flyTarget={}",
                        reason, roamTicksRemaining, idleLockTicks, flyTarget);
            }
        } catch (Throwable t) {
            LOG.error("[RavenEntity] beginRoamFlightWindow failed (reason={})", reason, t);
            roamTicksRemaining = Math.max(1, ROAM_MIN_TICKS);

            // Fail-safe takeoff lock so it doesn't get stuck perching forever due to an exception
            if (idleLockTicks <= 0) {
                idleLockTicks = 12;
            }
        }
    }

    // -----------------
    // Landing helpers
    // -----------------

    private void resetLandingState(String reason) {
        if (landingPhase != LandingPhase.NONE || landingLeafPos != null || landingTicks != 0) {
            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                LOG.debug("[RavenEntity] resetLandingState(reason={}) phase={} leaf={} ticks={}",
                        reason, landingPhase, landingLeafPos, landingTicks);
            }
        }
        landingPhase = LandingPhase.NONE;
        landingTicks = 0;
        landingLeafPos = null;
    }

    private void enterIdleFromLanding(String reason) {
        try {
            clearFlyTarget();
            flyTargetTimeoutTicks = 0;
            avoidanceCooldownTicks = 0;
            stuckTicks = 0;
            lastDistToTarget = Double.NaN;

            resetLandingState("enter idle: " + reason);

            setAIState(RavenAIState.IDLE_GROUND);
            idleLockTicks = IDLE_LOCK_TICKS;
            idleLeafLossTicks = 0;

            if (idleTicksRemaining <= 0) {
                RandomSource rnd = this.getRandom();
                idleTicksRemaining = IDLE_MIN_TICKS + rnd.nextInt(Math.max(1, IDLE_MAX_TICKS - IDLE_MIN_TICKS + 1));
                idleTargetYaw = this.getYRot();
                idleNextTurnTicks = 10 + rnd.nextInt(50);
            }

            // IMPORTANT: reset roam window so next time we leave idle we fly a bit.
            roamTicksRemaining = 0;

            this.setNoGravity(false);
            this.setAnimMode(RavenAnimMode.NO_AIR);

            // Allow a small downward settle so collision plants it on top of leaves.
            Vec3 vel = this.getDeltaMovement();
            double vy = vel.y;

            if (vy > IDLE_SETTLE_MAX_UP) {
                vy = IDLE_SETTLE_MAX_UP;
            }
            if (vy > IDLE_SETTLE_NUDGE_DOWN) {
                vy = IDLE_SETTLE_NUDGE_DOWN;
            }
            if (vy < IDLE_SETTLE_MIN_FALL) {
                vy = IDLE_SETTLE_MIN_FALL;
            }

            this.setDeltaMovement(0.0D, vy, 0.0D);

            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                LOG.debug("[RavenEntity] enterIdleFromLanding(reason={}) pos={} vel={}", reason, this.position(), this.getDeltaMovement());
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] enterIdleFromLanding failed (reason={})", reason, t);
        }
    }

    private boolean isStillValidLandingLeaf(BlockPos leaf) {
        try {
            BlockState st = this.level().getBlockState(leaf);
            if (st == null || !st.is(BlockTags.LEAVES)) {
                return false;
            }
            return hasAirColumn(leaf.above(), LAND_REQUIRED_AIR_ABOVE) && isThickCanopyLeaf(leaf);
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] isStillValidLandingLeaf failed: {}", t.toString());
            }
            return false;
        }
    }

    private Vec3 overheadTargetForLeaf(BlockPos leaf) {
        double x = leaf.getX() + 0.5D;
        double z = leaf.getZ() + 0.5D;
        double y = leaf.getY() + OVERHEAD_Y_OFFSET_FROM_LEAF;
        return new Vec3(x, y, z);
    }

    private Vec3 leafCenterTop(BlockPos leaf) {
        return new Vec3(leaf.getX() + 0.5D, leaf.getY() + 1.0D, leaf.getZ() + 0.5D);
    }

    private double horizontalDistanceTo(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Nullable
    private Vec3 pickRoamFallbackTarget(RandomSource rnd) {
        try {
            double hx = homePos.getX() + 0.5D;
            double hz = homePos.getZ() + 0.5D;

            double angle = rnd.nextDouble() * (Math.PI * 2.0D);
            double radius = 4.0D + rnd.nextDouble() * (HOME_RADIUS_BLOCKS - 4.0D);

            double x = hx + Math.cos(angle) * radius;
            double z = hz + Math.sin(angle) * radius;
            double y = clampYToHomeBounds(homePos.getY() + rnd.nextInt(9) - 4) + 0.25D;

            Vec3 target = new Vec3(x, y, z);
            return clampTargetToHomeBounds(target);
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] pickRoamFallbackTarget failed: {}", t.toString());
            }
            return null;
        }
    }

    // -----------------
    // Smarter in-air: avoidance + stuck detection
    // -----------------

    private void maybeAvoidOrRetargetDuringFlight(RandomSource rnd) {
        if (flyTarget == null) {
            return;
        }

        Vec3 pos = this.position();
        double dist = pos.distanceTo(flyTarget);

        if (!Double.isNaN(lastDistToTarget)) {
            double progress = lastDistToTarget - dist;
            if (progress < STUCK_PROGRESS_EPS) {
                stuckTicks++;
            } else {
                stuckTicks = Math.max(0, stuckTicks - 2);
            }
        }
        lastDistToTarget = dist;

        boolean pathBlocked = isDirectPathBlocked(pos, flyTarget);

        if ((pathBlocked || stuckTicks >= STUCK_TICKS_THRESHOLD) && avoidanceCooldownTicks <= 0) {
            avoidanceCooldownTicks = AVOIDANCE_COOLDOWN_TICKS;

            Vec3 avoidance = computeAvoidanceWaypoint(pos, flyTarget, rnd);
            if (avoidance != null) {
                setFlyTarget(avoidance, 4 * 20);
                if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                    LOG.debug("[RavenEntity] Avoidance reroute (blocked={}, stuckTicks={}) -> {}", pathBlocked, stuckTicks, avoidance);
                }
                return;
            }

            clearFlyTarget();
            this.setDeltaMovement(Vec3.ZERO);
        }
    }

    private void maybeAvoidOrRetargetDuringFlightApproachOnly(RandomSource rnd) {
        if (flyTarget == null) {
            return;
        }

        Vec3 pos = this.position();
        double dist = pos.distanceTo(flyTarget);

        if (!Double.isNaN(lastDistToTarget)) {
            double progress = lastDistToTarget - dist;
            if (progress < STUCK_PROGRESS_EPS) {
                stuckTicks++;
            } else {
                stuckTicks = Math.max(0, stuckTicks - 2);
            }
        }
        lastDistToTarget = dist;

        boolean pathBlocked = isDirectPathBlocked(pos, flyTarget);

        if ((pathBlocked || stuckTicks >= STUCK_TICKS_THRESHOLD) && avoidanceCooldownTicks <= 0) {
            avoidanceCooldownTicks = AVOIDANCE_COOLDOWN_TICKS;

            Vec3 avoidance = computeAvoidanceWaypoint(pos, flyTarget, rnd);
            if (avoidance != null) {
                setFlyTarget(avoidance, 3 * 20);
                if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                    LOG.debug("[RavenEntity] Landing-approach avoidance (blocked={}, stuckTicks={}) -> {}", pathBlocked, stuckTicks, avoidance);
                }
                return;
            }

            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                LOG.debug("[RavenEntity] Landing-approach avoidance failed (blocked={}, stuckTicks={}), continuing without retarget",
                        pathBlocked, stuckTicks);
            }
        }
    }

    private boolean isDirectPathBlocked(Vec3 from, Vec3 to) {
        try {
            BlockHitResult hit = this.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() == HitResult.Type.MISS) {
                return false;
            }
            double hitDistSqr = hit.getLocation().distanceToSqr(from);
            double targetDistSqr = to.distanceToSqr(from);
            return hitDistSqr < targetDistSqr - 0.25D;
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] isDirectPathBlocked failed: {}", t.toString());
            }
            return false;
        }
    }

    @Nullable
    private Vec3 computeAvoidanceWaypoint(Vec3 pos, Vec3 target, RandomSource rnd) {
        try {
            Vec3 to = target.subtract(pos);
            double len = to.length();
            if (len < 0.0001D) {
                return null;
            }

            Vec3 dir = to.scale(1.0D / len);

            Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
            if (side.lengthSqr() < 0.0001D) {
                side = new Vec3(1.0D, 0.0D, 0.0D);
            }
            side = side.normalize();

            double sideSign = rnd.nextBoolean() ? 1.0D : -1.0D;
            double sideMag = 3.0D + rnd.nextDouble() * 6.0D;
            double upMag = 2.5D + rnd.nextDouble() * 4.5D;
            double forwardMag = 4.0D + rnd.nextDouble() * 6.0D;

            Vec3 waypoint = pos
                    .add(dir.scale(forwardMag))
                    .add(side.scale(sideMag * sideSign))
                    .add(0.0D, upMag, 0.0D);

            waypoint = clampTargetToHomeBounds(waypoint);
            return waypoint;
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] computeAvoidanceWaypoint failed: {}", t.toString());
            }
            return null;
        }
    }

    // -----------------
    // Leaf-only landing target selection
    // -----------------

    @Nullable
    private BlockPos pickLandingLeafBlock(RandomSource rnd) {
        try {
            double hx = homePos.getX() + 0.5D;
            double hz = homePos.getZ() + 0.5D;

            double angle = rnd.nextDouble() * (Math.PI * 2.0D);
            double radius = 4.0D + rnd.nextDouble() * (HOME_RADIUS_BLOCKS - 4.0D);

            int cx = Mth.floor(hx + Math.cos(angle) * radius);
            int cz = Mth.floor(hz + Math.sin(angle) * radius);
            int cy = clampYToHomeBounds(homePos.getY());

            BlockPos center = new BlockPos(cx, cy, cz);
            return pickLandingLeafBlockNear(center, rnd);
        } catch (Throwable t) {
            LOG.error("[RavenEntity] pickLandingLeafBlock failed", t);
            return null;
        }
    }

    @Nullable
    private BlockPos pickLandingLeafBlockNear(BlockPos center, RandomSource rnd) {
        try {
            for (int i = 0; i < LAND_SEARCH_ATTEMPTS; i++) {
                int dx = rnd.nextInt(LAND_SEARCH_RADIUS * 2 + 1) - LAND_SEARCH_RADIUS;
                int dz = rnd.nextInt(LAND_SEARCH_RADIUS * 2 + 1) - LAND_SEARCH_RADIUS;

                int x = center.getX() + dx;
                int z = center.getZ() + dz;

                int topY;
                try {
                    topY = this.level().getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                } catch (Throwable t) {
                    continue;
                }

                int scanMinY = Math.max(this.level().getMinBuildHeight(), topY - LAND_SCAN_DOWN);
                for (int y = topY; y >= scanMinY; y--) {
                    BlockPos leaf = new BlockPos(x, y, z);
                    BlockState state = this.level().getBlockState(leaf);
                    if (state == null || !state.is(BlockTags.LEAVES)) {
                        continue;
                    }

                    // Enforce "10 blocks of air above leaf" ALWAYS
                    if (!hasAirColumn(leaf.above(), LAND_REQUIRED_AIR_ABOVE)) {
                        continue;
                    }

                    // Enforce "not edge of canopy"
                    if (!isThickCanopyLeaf(leaf)) {
                        continue;
                    }

                    // Ensure overhead point is within home bounds; otherwise we'd clamp X/Z away and lose centering.
                    Vec3 overhead = overheadTargetForLeaf(leaf);
                    if (isOutOfHomeBounds(overhead)) {
                        continue;
                    }

                    return leaf;
                }
            }
            return null;
        } catch (Throwable t) {
            LOG.error("[RavenEntity] pickLandingLeafBlockNear failed", t);
            return null;
        }
    }

    private boolean isThickCanopyLeaf(BlockPos leaf) {
        try {
            int leavesNeighbors = 0;

            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (ox == 0 && oz == 0) {
                        continue;
                    }
                    BlockPos p = leaf.offset(ox, 0, oz);
                    BlockState st = this.level().getBlockState(p);
                    if (st != null && st.is(BlockTags.LEAVES)) {
                        leavesNeighbors++;
                    }
                }
            }

            BlockState below = this.level().getBlockState(leaf.below());
            boolean hasSupport = below != null && below.is(BlockTags.LEAVES);

            return leavesNeighbors >= CANOPY_NEIGHBOR_LEAVES_REQUIRED && hasSupport;
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] isThickCanopyLeaf failed: {}", t.toString());
            }
            return false;
        }
    }

    private boolean hasAirColumn(BlockPos start, int count) {
        try {
            BlockPos p = start;
            for (int i = 0; i < count; i++) {
                if (!this.level().isEmptyBlock(p)) {
                    return false;
                }
                p = p.above();
            }
            return true;
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] hasAirColumn failed: {}", t.toString());
            }
            return false;
        }
    }

    private boolean isLeafUnderFeet() {
        try {
            BlockPos feetBlock = BlockPos.containing(this.getX(), this.getBoundingBox().minY - 0.001D, this.getZ());
            BlockPos below = feetBlock.below();
            BlockState belowState = this.level().getBlockState(below);
            return belowState != null && belowState.is(BlockTags.LEAVES);
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] isLeafUnderFeet failed: {}", t.toString());
            }
            return false;
        }
    }

    // -----------------
    // Flight steering
    // -----------------

    private void flyTowardTarget(double speed) {
        if (flyTarget == null) {
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }

        Vec3 pos = this.position();
        Vec3 to = flyTarget.subtract(pos);
        double dist = to.length();

        if (dist <= ARRIVE_DIST) {
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }

        Vec3 dir = to.scale(1.0D / Math.max(0.0001D, dist));

        double scaledSpeed = speed;
        if (dist < 6.0D) {
            scaledSpeed = speed * 0.65D;
        }

        Vec3 vel = dir.scale(scaledSpeed);
        this.setDeltaMovement(vel);

        float yaw = (float) (Mth.atan2(vel.z, vel.x) * (180.0D / Math.PI)) - 90.0F;
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.yBodyRot = yaw;
    }

    @Nullable
    private Player getOwnerPlayerServerSafe() {
        try {
            if (!(this.level() instanceof ServerLevel serverLevel)) {
                return null;
            }
            if (!this.isTame()) {
                return null;
            }
            if (this.getOwnerUUID() == null) {
                return null;
            }
            return serverLevel.getPlayerByUUID(this.getOwnerUUID());
        } catch (Throwable t) {
            LOG.error("[RavenEntity] getOwnerPlayerServerSafe failed", t);
            return null;
        }
    }

    // -----------------
    // GeckoLib
    // -----------------

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, this::mainAnimController));
    }

    private <E extends RavenEntity> PlayState mainAnimController(final AnimationState<E> state) {
        try {
            RavenAnimMode mode = this.getAnimMode();
            if (mode == RavenAnimMode.NO_AIR) {
                return state.setAndContinue(ANIM_NO_AIR);
            }
            if (mode == RavenAnimMode.IN_AIR) {
                return state.setAndContinue(ANIM_IN_AIR);
            }

            RavenAIState ai = this.getAIState();
            if (ai == RavenAIState.IDLE_GROUND) {
                return state.setAndContinue(ANIM_NO_AIR);
            }
            return state.setAndContinue(ANIM_IN_AIR);

        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] Animation controller failed: {}", t.toString());
            }
            return PlayState.CONTINUE;
        }
    }
}