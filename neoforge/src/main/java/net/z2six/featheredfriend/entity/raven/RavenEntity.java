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
import net.z2six.featheredfriend.entity.raven.pathing.RavenAStarPathing;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenEntity.java
 *
 * Raven entity with phase-based AI focusing on:
 *  - Perching ~75% of the time (IDLE_GROUND on LEAVES, NO_AIR).
 *  - Roaming/flying ~25% of the time (ROAM_FLY free flight) before attempting a landing.
 *
 * Pathing refactor:
 *  - All prior "move directly to flyTarget" calls are routed via a coarse (2x2x2) A* waypoint planner.
 *  - Raven still uses smooth steering between waypoints (flyTowardTarget), plus local raycast avoidance as a safety layer.
 *  - If no path can be found, we fall back to the old behavior (simple flyTarget) so the AI never freezes.
 *
 * Surgical fix (requested):
 *  - Re-attempt path planning every X ticks (configurable) because entity-position sampling can occasionally make the start cell
 *    appear "inside a block" (or cause pathological expansions / maxExpanded).
 *  - Add a "safe start sampling" step that probes a few candidate start Vec3s (nudging upward / snapping to block centers)
 *    before calling A*.
 *  - Add explicit logging when we decide to retry / when we compute an adjusted start sample.
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

    // ---- Pathing (coarse A*) ----
    // Replan throttle and safety:
    private static final int PATH_REPLAN_MIN_INTERVAL_TICKS = 8; // don't spam A*
    private static final double PATH_GOAL_REPLAN_DIST_SQR = 4.0D * 4.0D; // if goal moved > 4 blocks, replan
    private static final double PATH_WAYPOINT_REACHED_DIST = 1.25D; // close enough to advance

    /**
     * Requested behavior: keep trying every X ticks (configurable).
     * This is NOT "cooldown because we failed"; this is a retry scheduler that ensures we re-attempt even if we
     * temporarily thought we were inside a block or A* hit maxExpanded due to a transient bad start sample.
     */
    // ---- Path retry control ----
    private static final int PATH_RETRY_INTERVAL_TICKS = 20; // 1 second
    private int pathRetryTicks = 0;

    /**
     * Start-sample probes:
     * We probe a few candidate start positions to avoid the case where the entity's reported position makes us anchor
     * a 2x2 clearance volume into a wall/ceiling.
     */
    private static final double[] PATH_START_PROBE_UP_OFFSETS = new double[]{
            0.0D, 0.125D, 0.25D, 0.5D, 0.75D, 1.0D, 1.25D, 1.5D, 2.0D
    };

    /**
     * If true, we emit a log whenever we alter the start sample (so you can SEE when the surgical fix is active).
     */
    private static final boolean PATH_START_SAMPLE_LOGS = true;

    /**
     * If true, we emit a log whenever we skip planning due to retry throttle, and when a retry window opens.
     */
    private static final boolean PATH_RETRY_LOGS = true;

    // If pathing repeatedly fails, we fall back to old direct setFlyTarget behavior.
    private int pathFailCooldownTicks = 0;

    @Nullable
    private Vec3 pathGoal = null;

    @Nullable
    private Vec3 pathPendingGoal = null;

    private int pathReplanCooldownTicks = 0;

    private List<Vec3> pathWaypoints = Collections.emptyList();
    private int pathWaypointIndex = 0;

    // Retry scheduler bookkeeping
    private int pathRetryCooldownTicks = 0;
    private long lastPathPlanAttemptTick = -1L;
    private int consecutivePathPlanFails = 0;
    private int consecutiveStartSampleAdjustments = 0;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final RavenControl control = new RavenControl(this);

    // GeckoLib animations
    private static final RawAnimation ANIM_NO_AIR = RawAnimation.begin().thenLoop("animation.raven.no_air");
    private static final RawAnimation ANIM_IN_AIR = RawAnimation.begin().thenLoop("animation.raven.in_air");

    // Home point (server-authoritative)
    private boolean homeInitialized = false;
    private BlockPos homePos = BlockPos.ZERO;

    // Simple flight target (server-side): NOW this is the CURRENT waypoint target (not the final goal).
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
    private boolean idleSettleArmed = false;
    private boolean idleSettlingActive = false;

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

    private void clearPlannedPath(String reason) {
        if ((!pathWaypoints.isEmpty() || pathGoal != null) && (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0)) {
            LOG.debug("[RavenEntity] clearPlannedPath(reason={}) goal={} waypoints={} idx={}",
                    reason, pathGoal, pathWaypoints.size(), pathWaypointIndex);
        }
        pathGoal = null;
        pathWaypoints = Collections.emptyList();
        pathWaypointIndex = 0;
        pathReplanCooldownTicks = 0;

        // Also reset retry bookkeeping.
        pathRetryCooldownTicks = 0;
        lastPathPlanAttemptTick = -1L;
        consecutivePathPlanFails = 0;
        consecutiveStartSampleAdjustments = 0;
    }

    private RavenAStarPathing.CellBounds currentHomeCellBounds() {
        int minY = homePos.getY() - HOME_Y_DELTA;
        int maxY = homePos.getY() + HOME_Y_DELTA;
        return RavenAStarPathing.boundsFrom(homePos, HOME_RADIUS_BLOCKS, minY, maxY);
    }

    /**
     * Surgical fix #1: "safe start sampling".
     *
     * The core issue you described matches this exact failure mode:
     * - Occasionally, this.position() (or its implied anchor) makes a 2x2x2 clearance volume overlap a wall/ceiling/floor.
     * - A* then either says "start blocked" OR explodes expansions inside a tight space until it hits maxExpanded.
     *
     * So: we probe a small set of candidate start Vec3s that are:
     *  - slightly nudged upward (common for "buzzing against ceiling" / feet-y semantics),
     *  - snapped to block centers in X/Z (prevents the 2x2 anchor from drifting into a neighboring solid),
     *  - and we pick the first one that appears to have a locally empty clearance volume.
     *
     * This does NOT "cheat" through blocks; it just changes the sampling point so we stop incorrectly anchoring inside a block.
     */
    private Vec3 computeSafePathingStart(Vec3 rawStart, RavenAStarPathing.Config cfg) {
        if (rawStart == null) {
            return this.position();
        }
        if (cfg == null) {
            cfg = new RavenAStarPathing.Config();
        }

        // Uses cfg.cellSize / cfg.clearanceHeight, so switching to 1x1 works automatically.
        final int clearanceXZ = Math.max(1, cfg.cellSize);
        final int clearanceH = Math.max(1, cfg.clearanceHeight);

        final double rawX = rawStart.x;
        final double rawZ = rawStart.z;

        final double snapX = Math.floor(rawX) + 0.5D;
        final double snapZ = Math.floor(rawZ) + 0.5D;

        final Vec3[] baseCandidates = new Vec3[]{
                new Vec3(rawX, rawStart.y, rawZ),
                new Vec3(snapX, rawStart.y, snapZ),
                new Vec3(Mth.lerp(0.35D, rawX, snapX), rawStart.y, Mth.lerp(0.35D, rawZ, snapZ))
        };

        for (Vec3 base : baseCandidates) {
            for (double up : PATH_START_PROBE_UP_OFFSETS) {
                Vec3 cand = new Vec3(base.x, base.y + up, base.z);

                if (looksLocallyPassableForClearance(cand, clearanceXZ, clearanceH, cfg.allowLeaves, cfg.allowReplaceables)) {
                    if (PATH_START_SAMPLE_LOGS) {
                        boolean changedXZ = (Math.abs(base.x - rawX) > 1.0E-6D) || (Math.abs(base.z - rawZ) > 1.0E-6D);
                        boolean changedY = (up != 0.0D);

                        if (changedXZ || changedY) {
                            consecutiveStartSampleAdjustments++;
                            if (this.tickCount % 20 == 0) {
                                LOG.info("[RavenEntity] Path start sample adjusted: rawStart={} -> chosenStart={} (up={} snapXZ={} clearance={}x{} allowLeaves={} allowReplaceables={} adjCount={})",
                                        rawStart,
                                        cand,
                                        String.format("%.3f", up),
                                        changedXZ,
                                        clearanceXZ,
                                        clearanceH,
                                        cfg.allowLeaves,
                                        cfg.allowReplaceables,
                                        consecutiveStartSampleAdjustments);
                            }
                        }
                    }
                    return cand;
                }
            }
        }

        Vec3 fallback = new Vec3(snapX, rawStart.y + 0.25D, snapZ);
        if (PATH_START_SAMPLE_LOGS && this.tickCount % 40 == 0) {
            LOG.warn("[RavenEntity] Path start sample probe found no locally-passable candidate; using fallback={} (rawStart={}, clearance={}x{})",
                    fallback, rawStart, clearanceXZ, clearanceH);
        }
        return fallback;
    }

    private Vec3 computeStablePathStart() {
        try {
            Vec3 pos = this.position();

            // Stable anchor: block-centered X/Z and block-centered Y
            // (NOT bbMinY, NOT collision-resolved minY).
            double sx = Math.floor(pos.x) + 0.5D;
            double sy = Math.floor(pos.y) + 0.5D;
            double sz = Math.floor(pos.z) + 0.5D;

            // If the anchor ends up inside something, nudge upward a few blocks (bounded).
            BlockPos bp = BlockPos.containing(sx, sy, sz);
            for (int i = 0; i < 4; i++) {
                if (this.level().isEmptyBlock(bp)) {
                    break;
                }
                sy += 1.0D;
                bp = bp.above();
            }

            return new Vec3(sx, sy, sz);
        } catch (Throwable t) {
            // Safety fallback: never crash AI on a path sample helper.
            if (this.tickCount % 40 == 0) {
                LOG.warn("[RavenEntity] computeStablePathStart failed: {}", t.toString());
            }
            return this.position();
        }
    }


    /**
     * Extremely cheap local passability probe:
     * checks a 2x2xH volume (in blocks) around the candidate anchor implied by:
     *  - anchorX = floor(x)
     *  - anchorZ = floor(z)
     *  - anchorY = floor(y)
     *
     * This matches the "coarse clearance" semantics you actually want for the Raven.
     */
    private boolean looksLocallyPassableForClearance(Vec3 start, int clearanceXZ, int clearanceH, boolean allowLeaves, boolean allowReplaceables) {
        try {
            if (start == null) return false;

            int ax = Mth.floor(start.x + 1.0E-4D);
            int ay = Mth.floor(start.y + 1.0E-4D);
            int az = Mth.floor(start.z + 1.0E-4D);

            // Quick scan the clearance volume.
            for (int ox = 0; ox < clearanceXZ; ox++) {
                for (int oy = 0; oy < clearanceH; oy++) {
                    for (int oz = 0; oz < clearanceXZ; oz++) {
                        BlockPos p = new BlockPos(ax + ox, ay + oy, az + oz);

                        if (this.level().isEmptyBlock(p)) {
                            continue;
                        }

                        BlockState st = this.level().getBlockState(p);
                        if (st == null) {
                            return false;
                        }

                        if (allowLeaves && st.is(BlockTags.LEAVES)) {
                            continue;
                        }

                        if (allowReplaceables && st.canBeReplaced()) {
                            continue;
                        }

                        // blocked
                        return false;
                    }
                }
            }

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Maps existing call sites:
     *   ensurePathTo(goal, timeoutTicks, seed, "reason")
     * into the core planner with a derived highPriority flag.
     *
     * This fixes your compile errors:
     *  - no more int->boolean mismatch
     *  - no duplicate ensurePathTo signatures
     */
    private boolean ensurePathTo(@Nullable Vec3 goal, int timeoutTicks, long seed, @Nullable String reason) {
        // Derive priority based on why we're planning (keeps all existing call sites unchanged).
        boolean highPriority = isHighPriorityReason(reason);

        // If timeout is very short, it is typically reactive / urgent.
        // (Example: collision replans, landing approach, retry-loop, follow adjustments)
        if (timeoutTicks > 0 && timeoutTicks <= 6 * 20) {
            highPriority = true;
        }

        return ensurePathTo(goal, highPriority, timeoutTicks, seed, reason);
    }

    /**
     * Optional compatibility overload if you ever want to call it explicitly with a boolean.
     * (Not required for your current code, but keeps the API clean.)
     */
    private boolean ensurePathTo(@Nullable Vec3 goal, boolean highPriority, long seed, @Nullable String reason) {
        // Default timeout if caller didn't provide one.
        // Keep it modest: long enough to move to a waypoint, short enough to replan.
        final int defaultTimeout = 6 * 20;
        return ensurePathTo(goal, highPriority, defaultTimeout, seed, reason);
    }

    /**
     * Core planner (SINGLE definition).
     *
     * IMPORTANT:
     * - You must only have ONE method with this signature in the class.
     * - This version supports your safe-start sampling + safe-goal selection.
     */
    private boolean ensurePathTo(@Nullable Vec3 goal, boolean highPriority, int timeoutTicks, long seed, @Nullable String reason) {
        if (this.level().isClientSide) {
            return false;
        }

        if (goal == null) {
            if (this.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] ensurePathTo(core): goal=null, skipping (reason={})", reason);
            }
            return false;
        }

        // Keep pending goal ALWAYS (so the retry loop can function even when pathGoal isn't set yet).
        // IMPORTANT: pendingGoal remains the ORIGINAL requested goal (e.g. owner position).
        this.pathPendingGoal = goal;

        try {
            ensureHomeInitialized();

            // If we are in fail cooldown and not high priority, don't spam A*.
            if (!highPriority && pathFailCooldownTicks > 0) {
                if (PATH_RETRY_LOGS && this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] ensurePathTo(core): blocked by failCooldown={} (reason={}) goal={}",
                            pathFailCooldownTicks, reason, goal);
                }
                return false;
            }

            // Throttle replanning unless high priority.
            if (!highPriority && pathReplanCooldownTicks > 0) {
                return false;
            }

            // Throttle retry window unless high priority (this is your "do not spam A*" guard).
            if (!highPriority && pathRetryCooldownTicks > 0) {
                return false;
            }

            // If we already have an active goal close to the requested one, avoid replanning.
            // NOTE: pathGoal may be an adjusted goal (near the requested one). That's fine.
            if (this.pathGoal != null) {
                double d2 = this.pathGoal.distanceToSqr(goal);
                if (d2 <= PATH_GOAL_REPLAN_DIST_SQR) {
                    return true;
                }
            }

            // Hard-min interval guard (protects CPU).
            if (!highPriority && lastPathPlanAttemptTick >= 0L) {
                long dt = (long) this.tickCount - lastPathPlanAttemptTick;
                if (dt >= 0 && dt < PATH_REPLAN_MIN_INTERVAL_TICKS) {
                    if (PATH_RETRY_LOGS && this.tickCount % 60 == 0) {
                        LOG.debug("[RavenEntity] ensurePathTo(core): min-interval gate dt={} < {} (reason={})",
                                dt, PATH_REPLAN_MIN_INTERVAL_TICKS, reason);
                    }
                    return false;
                }
            }

            lastPathPlanAttemptTick = this.tickCount;

            // Build config for your A*.
            RavenAStarPathing.Config cfg = new RavenAStarPathing.Config();

            // Your "coarse" footprint (2x2x2) default behavior:
            cfg.cellSize = 2;
            cfg.clearanceHeight = 2;
            cfg.gridStep = 1;

            cfg.allowLeaves = false;
            cfg.allowReplaceables = false;

            cfg.maxExpanded = 6500;
            cfg.maxOpen = 16000;

            // Keep your smoothing as-is for now.
            cfg.smoothPath = true;

            cfg.tieBreakSeed = seed;

            RavenAStarPathing.CellBounds bounds = currentHomeCellBounds();

            // ---- START: stabilized sampling ----
            Vec3 rawStart = computeStablePathStart();
            Vec3 safeStart = computeSafePathingStart(rawStart, cfg);

            if (PATH_START_SAMPLE_LOGS && this.tickCount % 40 == 0) {
                double dx = safeStart.x - rawStart.x;
                double dy = safeStart.y - rawStart.y;
                double dz = safeStart.z - rawStart.z;
                if ((dx * dx + dy * dy + dz * dz) > 0.0001D) {
                    LOG.info("[RavenEntity] ensurePathTo(core): start adjusted raw={} safe={} (reason={})",
                            rawStart, safeStart, reason);
                }
            }

            // ---- GOAL: safe goal selection ----
            Vec3 rawGoal = goal;
            Vec3 safeGoal = computeSafePathingGoal(rawGoal, cfg);
            safeGoal = clampTargetToHomeBounds(safeGoal);

            if (this.tickCount % 20 == 0) {
                double d2 = safeGoal.distanceToSqr(rawGoal);
                if (d2 > 1.0E-6D) {
                    LOG.info("[RavenEntity] ensurePathTo(core): goal adjusted rawGoal={} safeGoal={} d2={} (reason={})",
                            rawGoal, safeGoal, String.format("%.3f", d2), reason);
                }
            }

            // Actually call A* using safeStart + safeGoal
            List<Vec3> pts;
            try {
                pts = RavenAStarPathing.findPath(this.level(), safeStart, safeGoal, bounds, cfg);
            } catch (Throwable t) {
                LOG.error("[RavenEntity] ensurePathTo(core): RavenAStarPathing.findPath crashed safely (reason={}) start={} goal={} bounds={}",
                        reason, safeStart, safeGoal, bounds, t);
                pts = Collections.emptyList();
            }

            if (pts == null || pts.isEmpty()) {
                consecutivePathPlanFails++;

                // Apply cooldowns so we don't hammer A* every tick.
                pathFailCooldownTicks = Math.max(pathFailCooldownTicks, 12); // mild backoff
                pathReplanCooldownTicks = Math.max(pathReplanCooldownTicks, 6);
                pathRetryCooldownTicks = highPriority ? 5 : 20;

                if (this.tickCount % 20 == 0) {
                    LOG.info("[RavenEntity] ensurePathTo(core): A* FAIL (fails={} reason={}) start={} rawGoal={} safeGoal={} pos={} vel={} collH={} collV={} bounds={}",
                            consecutivePathPlanFails,
                            reason,
                            safeStart,
                            rawGoal,
                            safeGoal,
                            this.position(),
                            this.getDeltaMovement(),
                            this.horizontalCollision,
                            this.verticalCollision,
                            bounds);
                }

                // Keep pathPendingGoal as ORIGINAL requested goal so the retry loop can keep trying.
                return false;
            }

            // Success: accept waypoints
            consecutivePathPlanFails = 0;

            // IMPORTANT:
            // - pathGoal becomes the ACTUAL destination we are pathing to (safeGoal).
            // - pathPendingGoal remains the ORIGINAL requested goal (rawGoal), so future replans can re-derive a new safeGoal.
            this.pathGoal = safeGoal;
            this.pathWaypoints = pts;
            this.pathWaypointIndex = 0;

            // Cooldowns after success (still prevents spam)
            pathReplanCooldownTicks = highPriority ? 4 : 8;
            pathRetryCooldownTicks = highPriority ? 4 : 10;
            pathFailCooldownTicks = 0;

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] ensurePathTo(core): A* OK pts={} reason={} start={} rawGoal={} safeGoal={} pos={}",
                        pts.size(), reason, safeStart, rawGoal, safeGoal, this.position());
            }

            // Immediately set fly target to the first waypoint (or advance if already reached).
            // Use the caller-provided timeoutTicks (this was the thing your call sites were trying to express).
            advanceWaypointIfNeeded(Math.max(20, timeoutTicks), "ensurePathTo(core) accept: " + String.valueOf(reason));

            return true;

        } catch (Throwable t) {
            LOG.error("[RavenEntity] ensurePathTo(core) failed safely (reason={}) goal={} pos={}",
                    reason, goal, this.position(), t);
            return false;
        }
    }

    /**
     * Priority heuristics.
     *
     * You wanted retries / return-home / follow / landing approach to be allowed to punch through throttles.
     * This keeps behavior stable without rewriting all your call sites to pass booleans everywhere.
     */
    private boolean isHighPriorityReason(@Nullable String reason) {
        if (reason == null || reason.isEmpty()) {
            return false;
        }

        // Normalize once.
        String r = reason.toLowerCase();

        // Things that should not get stuck behind backoffs / cooldown gates:
        // - retry-loop (explicit)
        // - out-of-bounds return / follow cooldown return
        // - follow owner adjustments
        // - collision replans / landing approach
        if (r.contains("retry")) return true;
        if (r.contains("out-of-bounds")) return true;
        if (r.contains("return")) return true;
        if (r.contains("follow")) return true;
        if (r.contains("collision")) return true;
        if (r.contains("blocked")) return true;
        if (r.contains("landing")) return true;

        return false;
    }

    private void advanceWaypointIfNeeded(int timeoutTicks, String reason) {
        try {
            if (pathWaypoints == null || pathWaypoints.isEmpty()) {
                return;
            }

            Vec3 pos = this.position();

            // Advance over any waypoints already reached
            while (pathWaypointIndex < pathWaypoints.size()) {
                Vec3 wp = pathWaypoints.get(pathWaypointIndex);
                if (wp == null) {
                    pathWaypointIndex++;
                    continue;
                }

                double d = pos.distanceTo(wp);
                if (d <= PATH_WAYPOINT_REACHED_DIST) {
                    pathWaypointIndex++;
                    continue;
                }
                break;
            }

            if (pathWaypointIndex >= pathWaypoints.size()) {
                // Completed path
                clearFlyTarget();
                if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                    LOG.debug("[RavenEntity] Path completed (reason={}) goal={} pos={}", reason, pathGoal, pos);
                }
                return;
            }

            Vec3 next = pathWaypoints.get(pathWaypointIndex);
            if (next != null) {
                setFlyTarget(next, timeoutTicks);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] advanceWaypointIfNeeded failed (reason={})", reason, t);
        }
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
            target = clampTargetToHomeBounds(target);

            // Plan a path (coarse A*). If it fails, fall back to old direct target.
            long seed = this.getUUID().getLeastSignificantBits() ^ (long) this.tickCount;
            boolean ok = ensurePathTo(target, 12 * 20, seed, "commandMoveTo");

            if (!ok) {
                // fallback: old behavior
                setFlyTarget(target, 12 * 20);
            }

            return true;
        } catch (Throwable t) {
            LOG.error("[RavenEntity] commandMoveTo failed for {} @ {}", pos, speed, t);
            return false;
        }
    }

    public void commandStopMoving() {
        clearPlannedPath("commandStopMoving");
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

            // Tick down pathing cooldowns here (single source of truth).
            if (pathFailCooldownTicks > 0) {
                pathFailCooldownTicks--;
            }
            if (pathReplanCooldownTicks > 0) {
                pathReplanCooldownTicks--;
            }
            if (pathRetryCooldownTicks > 0) {
                pathRetryCooldownTicks--;
            }

            // --- PATH RETRY LOOP ---
            // Your original retry loop never ran because pathGoal is only set on SUCCESS.
            // We retry using pathPendingGoal (set on every ensurePathTo call) if there's no active path.
            {
                Vec3 retryGoal = (pathGoal != null) ? pathGoal : pathPendingGoal;

                boolean hasNoPath = (pathWaypoints == null || pathWaypoints.isEmpty());
                boolean wantsRetry = (retryGoal != null) && hasNoPath;

                if (wantsRetry) {
                    if (pathRetryTicks > 0) {
                        pathRetryTicks--;
                    } else {
                        pathRetryTicks = PATH_RETRY_INTERVAL_TICKS;

                        // Force the internal gates open so we DEFINITELY call RavenAStarPathing.findPath(...)
                        // on this scheduled retry tick. Otherwise ensurePathTo may early-return and you see "nothing happens".
                        pathRetryCooldownTicks = 0;
                        pathReplanCooldownTicks = 0;

                        long seed = this.getUUID().getLeastSignificantBits() ^ (long) this.tickCount ^ 0xA5A5A5A5L;

                        // High-signal log: shows you that the retry loop is alive AND what state it's in.
                        LOG.info("[RavenEntity] A* retry tick: retryGoal={} pos={} vel={} bbMinY={} collH={} collV={} pathGoal={} pendingGoal={} fails={}",
                                retryGoal,
                                this.position(),
                                this.getDeltaMovement(),
                                this.getBoundingBox().minY,
                                this.horizontalCollision,
                                this.verticalCollision,
                                pathGoal,
                                pathPendingGoal,
                                consecutivePathPlanFails);

                        boolean ok = ensurePathTo(retryGoal, 6 * 20, seed, "retry-loop");

                        if (ok) {
                            LOG.info("[RavenEntity] A* retry SUCCESS -> waypoints={} idx={} flyTarget={}",
                                    (pathWaypoints == null ? 0 : pathWaypoints.size()), pathWaypointIndex, flyTarget);
                        } else {
                            LOG.info("[RavenEntity] A* retry FAILED -> nextRetryIn={}t (pathRetryCooldownTicks={} failCd={}) retryGoal={}",
                                    PATH_RETRY_INTERVAL_TICKS, pathRetryCooldownTicks, pathFailCooldownTicks, retryGoal);
                        }
                    }
                }
            }

            if (isOutOfHomeBounds(this.position())) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] Out of bounds, commanding return to home bounds");
                }
                setAIState(RavenAIState.ROAM_FLY);
                resetLandingState("out-of-bounds");
                idleLockTicks = 0;
                idleLeafLossTicks = 0;

                // Force a return path immediately; keep roam window running (or start a short one) so we don't instantly land.
                Vec3 ret = homeCenterReturnTarget();
                long seed = this.getUUID().getMostSignificantBits() ^ (long) this.tickCount;
                boolean ok = ensurePathTo(ret, 10 * 20, seed, "out-of-bounds return");
                if (!ok) {
                    setFlyTarget(ret, 10 * 20);
                }

                if (roamTicksRemaining <= 0) {
                    beginRoamFlightWindow("out-of-bounds return");
                    // Keep the return target (beginRoamFlightWindow might plan a random roam target)
                    clearPlannedPath("override roam path with return");
                    boolean ok2 = ensurePathTo(ret, 10 * 20, seed ^ 0xBADC0FFEE0DDF00DL, "out-of-bounds return (after roam window)");
                    if (!ok2) {
                        setFlyTarget(ret, 10 * 20);
                    }
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

    private void tickIdleGround() {
        boolean leafUnder = isLeafUnderFeet();

        // --- ONE-TIME SETTLE STEP (ONLY ONCE PER IDLE ENTRY) ---
        if (idleSettleArmed && idleSettlingActive) {
            this.setNoGravity(false);
            this.clearFlyTarget();
            clearPlannedPath("idle settle step");
            resetLandingState("idle settle step (one-time)");
            if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
                this.setAnimMode(RavenAnimMode.NO_AIR);
            }

            Vec3 vel = this.getDeltaMovement();
            double vy = vel.y;
            if (vy > -0.05D) {
                vy = -0.05D;
            }

            this.setDeltaMovement(0.0D, vy, 0.0D);

            if (this.onGround()) {
                idleSettlingActive = false;
                idleSettleArmed = false;

                if (this.tickCount % 20 == 0) {
                    LOG.debug("[RavenEntity] Idle settle COMPLETE (onGround=true). pos={} vel={} leafUnder={}",
                            this.position(), this.getDeltaMovement(), leafUnder);
                }
            } else {
                if (this.tickCount % 60 == 0) {
                    LOG.debug("[RavenEntity] Idle settle in progress... pos={} bbMinY={} vel={} leafUnder={} onGround={}",
                            this.position(), this.getBoundingBox().minY, this.getDeltaMovement(), leafUnder, this.onGround());
                }
                return;
            }
        } else {
            if (!idleSettlingActive && idleSettleArmed) {
                idleSettleArmed = false;
            }
        }

        if (!leafUnder) {
            idleLeafLossTicks++;
            if (idleLeafLossTicks >= IDLE_LEAF_LOSS_GRACE_TICKS) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] IDLE_GROUND aborted: leaves lost under feet for {} ticks at pos={}, switching to ROAM_FLY",
                            idleLeafLossTicks, this.position());
                }

                this.setAIState(RavenAIState.ROAM_FLY);
                this.idleTicksRemaining = 0;

                this.idleLockTicks = 12;
                this.idleLeafLossTicks = 0;

                this.idleSettleArmed = false;
                this.idleSettlingActive = false;

                resetLandingState("idle leaf loss -> roam");
                beginRoamFlightWindow("idle leaf loss");
                return;
            }
        } else {
            idleLeafLossTicks = 0;
        }

        this.setNoGravity(false);
        this.clearFlyTarget();
        clearPlannedPath("idle");
        resetLandingState("enter/continue idle");
        if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
            this.setAnimMode(RavenAnimMode.NO_AIR);
        }

        if (idleLockTicks > 0) {
            idleLockTicks--;
        }

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
            setAIState(RavenAIState.ROAM_FLY);

            this.idleLockTicks = 12;
            this.idleLeafLossTicks = 0;

            this.idleSettleArmed = false;
            this.idleSettlingActive = false;

            resetLandingState("idle expired -> roam");
            beginRoamFlightWindow("idle expired");
        }
    }

    /**
     * ROAM_FLY (refactored to use coarse A* pathing):
     *  - During roam window: pick a random roam target and follow a waypoint path.
     *  - After roam window: attempt landing; the "fly to overhead" phase uses pathing too.
     *  - If pathing cannot find a path, we fall back to the previous direct-target behavior.
     */
    private void tickRoamFly() {
        RandomSource rnd = this.getRandom();

        // TAKEOFF LOCK (unchanged):
        if (idleLockTicks > 0) {
            idleLockTicks--;

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            Vec3 vel = this.getDeltaMovement();
            double vy = vel.y;
            if (vy < 0.18D) {
                vy = 0.18D;
            }
            if (vy > 0.32D) {
                vy = 0.32D;
            }

            this.setDeltaMovement(vel.x, vy, vel.z);

            // During takeoff lock, ensure we have *some* flight intent:
            if ((pathWaypoints == null || pathWaypoints.isEmpty()) && (flyTarget == null || flyTargetTimeoutTicks <= 0)) {
                Vec3 roamTarget = pickRoamFallbackTarget(rnd);
                if (roamTarget != null) {
                    long seed = this.getUUID().getLeastSignificantBits() ^ (long) this.tickCount ^ 0x13579BDFL;
                    boolean ok = ensurePathTo(roamTarget, 4 * 20, seed, "takeoff lock roam");
                    if (!ok) {
                        setFlyTarget(roamTarget, 4 * 20);
                    }
                } else {
                    clearPlannedPath("takeoff lock no roam target");
                    clearFlyTarget();
                }
            }

            // Continue moving toward current waypoint/target
            if (flyTarget != null) {
                if (flyTargetTimeoutTicks > 0) {
                    flyTargetTimeoutTicks--;
                }
                maybeAvoidOrRetargetDuringFlight(rnd);
                flyTowardTarget(FLY_SPEED_BASE);
            } else if (pathWaypoints != null && !pathWaypoints.isEmpty()) {
                advanceWaypointIfNeeded(4 * 20, "takeoff lock advance");
            }

            if (this.tickCount % 120 == 0) {
                LOG.debug("[RavenEntity] ROAM_FLY takeoff lock active (remaining={}) pos={} vel={} pathPts={} pathIdx={}",
                        idleLockTicks, this.position(), this.getDeltaMovement(),
                        (pathWaypoints == null ? 0 : pathWaypoints.size()), pathWaypointIndex);
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
                clearPlannedPath("landing timeout");
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
            clearPlannedPath("invalid landing leaf");
        }

        // If landing hasn't started yet, enforce the minimum flight window.
        if (landingPhase == LandingPhase.NONE && roamTicksRemaining > 0) {
            roamTicksRemaining--;

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            // Plan or maintain a roaming path.
            if ((pathWaypoints == null || pathWaypoints.isEmpty() || pathWaypointIndex >= pathWaypoints.size())
                    && (flyTarget == null || flyTargetTimeoutTicks <= 0)) {
                Vec3 roamTarget = pickRoamFallbackTarget(rnd);
                if (roamTarget != null) {
                    long seed = this.getUUID().getMostSignificantBits() ^ (long) this.tickCount ^ 0xCAFEBABEL;
                    boolean ok = ensurePathTo(roamTarget, 4 * 20, seed, "roam window");
                    if (!ok) {
                        setFlyTarget(roamTarget, 4 * 20);
                    }
                } else {
                    clearPlannedPath("roam window no target");
                    clearFlyTarget();
                    this.setDeltaMovement(Vec3.ZERO);
                }
            }

            // Drive motion
            if (flyTarget != null) {
                maybeAvoidOrRetargetDuringFlight(rnd);
                if (flyTargetTimeoutTicks > 0) {
                    flyTargetTimeoutTicks--;
                }
                flyTowardTarget(FLY_SPEED_BASE);
            }

            // Waypoint progression
            if (pathWaypoints != null && !pathWaypoints.isEmpty()) {
                advanceWaypointIfNeeded(4 * 20, "roam window");
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

            if ((pathWaypoints == null || pathWaypoints.isEmpty()) && (flyTarget == null || flyTargetTimeoutTicks <= 0)) {
                Vec3 roamTarget = pickRoamFallbackTarget(rnd);
                if (roamTarget != null) {
                    long seed = this.getUUID().getLeastSignificantBits() ^ (long) this.tickCount ^ 0xDEADBEEFL;
                    boolean ok = ensurePathTo(roamTarget, 4 * 20, seed, "landing allowed but no leaf -> roam");
                    if (!ok) {
                        setFlyTarget(roamTarget, 4 * 20);
                    }
                } else {
                    clearPlannedPath("no leaf roam no target");
                    clearFlyTarget();
                    this.setDeltaMovement(Vec3.ZERO);
                }
            }

            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                LOG.debug("[RavenEntity] Landing allowed but no valid leaf found -> roaming {} ticks then retry", extra);
            }
            return;
        }

        // Start landing: we will path to overhead (global routing) then do descent/drop locally.
        landingLeafPos = leaf;
        landingPhase = LandingPhase.FLY_TO_OVERHEAD;
        landingTicks = 0;

        Vec3 overhead = overheadTargetForLeaf(leaf);

        this.setNoGravity(true);
        if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
            this.setAnimMode(RavenAnimMode.IN_AIR);
        }

        // Plan path to overhead. If fails, fall back to direct.
        clearPlannedPath("starting landing -> plan overhead");
        long seed = this.getUUID().getMostSignificantBits() ^ (long) this.tickCount ^ leaf.asLong();
        boolean ok = ensurePathTo(overhead, 6 * 20, seed, "landing FLY_TO_OVERHEAD init");
        if (!ok) {
            setFlyTarget(overhead, 6 * 20);
        }

        if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
            LOG.debug("[RavenEntity] Landing start (post-roam): leaf={} overhead={} (airAbove={}, canopyNeighbors>={}) pathOk={}",
                    leaf, overhead, LAND_REQUIRED_AIR_ABOVE, CANOPY_NEIGHBOR_LEAVES_REQUIRED, ok);
        }
    }

    /**
     * Landing state machine (refactored):
     *  - FLY_TO_OVERHEAD uses coarse A* waypoint pathing to reach the overhead point without punching through canopy/structures.
     *  - DESCEND_SLOW and DROP remain local/physics-driven (no A*), unchanged in behavior.
     */
    private void tickLandingStateMachine(RandomSource rnd) {
        if (isLeafUnderFeet()) {
            enterIdleFromLanding("Landing: leaf under feet");
            return;
        }

        switch (landingPhase) {
            case NONE -> {
                resetLandingState("tickLandingStateMachine called with NONE");
                clearPlannedPath("landing NONE");
            }
            case FLY_TO_OVERHEAD -> {
                this.setNoGravity(true);
                if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                    this.setAnimMode(RavenAnimMode.IN_AIR);
                }

                if (landingLeafPos == null) {
                    resetLandingState("FLY_TO_OVERHEAD missing leaf");
                    clearPlannedPath("FLY_TO_OVERHEAD missing leaf");
                    return;
                }

                Vec3 overhead = overheadTargetForLeaf(landingLeafPos);

                long seed = this.getUUID().getLeastSignificantBits() ^ (long) this.tickCount ^ landingLeafPos.asLong();
                boolean ok = ensurePathTo(overhead, 6 * 20, seed, "FLY_TO_OVERHEAD");
                if (!ok && (flyTarget == null || flyTargetTimeoutTicks <= 0)) {
                    setFlyTarget(overhead, 6 * 20);
                }

                maybeAvoidOrRetargetDuringFlightApproachOnly(rnd);

                if (flyTarget != null) {
                    if (flyTargetTimeoutTicks > 0) {
                        flyTargetTimeoutTicks--;
                    }
                    flyTowardTarget(FLY_SPEED_BASE);
                }

                if (pathWaypoints != null && !pathWaypoints.isEmpty()) {
                    advanceWaypointIfNeeded(6 * 20, "FLY_TO_OVERHEAD");
                }

                Vec3 pos = this.position();
                double horiz = horizontalDistanceTo(pos, overhead);
                double vert = Math.abs(pos.y - overhead.y);

                if (horiz <= OVERHEAD_HORIZONTAL_EPS && vert <= OVERHEAD_VERTICAL_EPS) {
                    landingPhase = LandingPhase.DESCEND_SLOW;
                    clearFlyTarget();
                    clearPlannedPath("overhead reached -> descent");
                    if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                        LOG.debug("[RavenEntity] Overhead reached -> DESCEND_SLOW (leaf={}, pos={}, horiz={}, vert={})",
                                landingLeafPos, pos, horiz, vert);
                    }
                }
            }
            case DESCEND_SLOW -> {
                if (landingLeafPos == null) {
                    resetLandingState("DESCEND_SLOW missing leaf");
                    clearPlannedPath("DESCEND_SLOW missing leaf");
                    return;
                }

                this.setNoGravity(true);
                if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                    this.setAnimMode(RavenAnimMode.IN_AIR);
                }

                Vec3 pos = this.position();
                Vec3 leafCenter = leafCenterTop(landingLeafPos);

                Vec3 toCenter = new Vec3(leafCenter.x - pos.x, 0.0D, leafCenter.z - pos.z);
                double d2 = toCenter.length();
                Vec3 horizVel = Vec3.ZERO;
                if (d2 > 0.0001D) {
                    Vec3 dir = toCenter.scale(1.0D / d2);
                    double sp = Mth.clamp(DESCEND_CENTER_SPEED, 0.0D, DESCEND_CENTER_MAX);
                    horizVel = new Vec3(dir.x * sp, 0.0D, dir.z * sp);
                }

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

                if (horizVel.lengthSqr() > 0.0001D) {
                    float yaw = (float) (Mth.atan2(horizVel.z, horizVel.x) * (180.0D / Math.PI)) - 90.0F;
                    this.setYRot(yaw);
                    this.setYHeadRot(yaw);
                    this.yBodyRot = yaw;
                }

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
                    clearPlannedPath("DROP missing leaf");
                    return;
                }

                this.setNoGravity(false);
                if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
                    this.setAnimMode(RavenAnimMode.NO_AIR);
                }

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

                if (isLeafUnderFeet()) {
                    enterIdleFromLanding("DROP: leaf under feet");
                    return;
                }

                Vec3 pos = this.position();
                double leafTopY = landingLeafPos.getY() + 1.0D;
                if (pos.y < leafTopY - 1.25D) {
                    if (this.tickCount % 80 == 0) {
                        LOG.debug("[RavenEntity] DROP missed leaf (posY={}, leafTopY={}) -> resetting landing and reattempting",
                                pos.y, leafTopY);
                    }
                    resetLandingState("drop missed");
                    clearPlannedPath("drop missed");
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
            clearPlannedPath("follow lost owner");
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

        Vec3 desiredGoal = null;

        if (dist > FOLLOW_MAX_DIST) {
            desiredGoal = ownerPos.add(0.0D, 2.0D, 0.0D);
        } else if (dist < FOLLOW_MIN_DIST) {
            Vec3 away = myPos.subtract(ownerPos).normalize();
            if (away.lengthSqr() < 0.0001D) {
                away = new Vec3(1, 0, 0);
            }
            desiredGoal = ownerPos.add(away.scale(FOLLOW_MIN_DIST)).add(0.0D, 2.0D, 0.0D);
        } else {
            if (this.tickCount % 20 == 0) {
                clearPlannedPath("follow in-band hover");
                clearFlyTarget();
                this.setDeltaMovement(Vec3.ZERO);
            }
            return;
        }

        desiredGoal = clampTargetToHomeBounds(desiredGoal);

        long seed = owner.getUUID().getLeastSignificantBits() ^ this.getUUID().getMostSignificantBits() ^ (long) this.tickCount;
        boolean ok = ensurePathTo(desiredGoal, 5 * 20, seed, "follow owner");
        if (!ok && (flyTarget == null || flyTargetTimeoutTicks <= 0)) {
            setFlyTarget(desiredGoal, 5 * 20);
        }

        if (isOutOfHomeBounds(this.position())) {
            triggerFollowCooldownAndReturn();
            return;
        }

        if (flyTargetTimeoutTicks > 0) {
            flyTargetTimeoutTicks--;
        }

        maybeAvoidOrRetargetDuringFlight(this.getRandom());

        if (flyTarget != null) {
            flyTowardTarget(FLY_SPEED_BASE);
        }

        if (pathWaypoints != null && !pathWaypoints.isEmpty()) {
            advanceWaypointIfNeeded(5 * 20, "follow owner");
        }
    }

    private void triggerFollowCooldownAndReturn() {
        RandomSource rnd = this.getRandom();

        int cd = FOLLOW_COOLDOWN_MIN_TICKS + rnd.nextInt(Math.max(1, FOLLOW_COOLDOWN_MAX_TICKS - FOLLOW_COOLDOWN_MIN_TICKS + 1));
        setFollowCooldownTicks(cd);

        setAIState(RavenAIState.ROAM_FLY);

        clearPlannedPath("follow cooldown return");
        Vec3 ret = homeCenterReturnTarget();
        long seed = this.getUUID().getMostSignificantBits() ^ (long) this.tickCount ^ 0xA11CE5EDL;
        boolean ok = ensurePathTo(ret, 10 * 20, seed, "follow cooldown return");
        if (!ok) {
            setFlyTarget(ret, 10 * 20);
        }

        roamTicksRemaining = 0;

        resetLandingState("follow cooldown");
        this.idleLockTicks = 0;
        this.idleLeafLossTicks = 0;

        beginRoamFlightWindow("follow cooldown -> roam");

        clearPlannedPath("override roam window with return");
        boolean ok2 = ensurePathTo(ret, 10 * 20, seed ^ 0x55AA55AAL, "follow cooldown return (post-roam window)");
        if (!ok2) {
            setFlyTarget(ret, 10 * 20);
        }

        if (this.tickCount % 40 == 0) {
            LOG.debug("[RavenEntity] Follow bounds violated -> cooldown {} ticks and return to home", cd);
        }
    }

    private void beginRoamFlightWindow(String reason) {
        try {
            RandomSource rnd = this.getRandom();

            int span = Math.max(1, ROAM_MAX_TICKS - ROAM_MIN_TICKS + 1);
            int ticks = ROAM_MIN_TICKS + rnd.nextInt(span);
            roamTicksRemaining = Math.max(1, ticks);

            resetLandingState("beginRoamFlightWindow: " + reason);

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            clearPlannedPath("beginRoamFlightWindow: " + reason);

            Vec3 roamTarget = pickRoamFallbackTarget(rnd);
            if (roamTarget != null) {
                long seed = this.getUUID().getLeastSignificantBits() ^ (long) this.tickCount ^ reason.hashCode();
                boolean ok = ensurePathTo(roamTarget, 4 * 20, seed, "beginRoamFlightWindow");
                if (!ok) {
                    setFlyTarget(roamTarget, 4 * 20);
                }
            } else {
                clearFlyTarget();
            }

            if (idleLockTicks <= 0) {
                idleLockTicks = 12;
            }

            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                LOG.debug("[RavenEntity] beginRoamFlightWindow(reason={}) -> roamTicksRemaining={} takeoffLock={} pathPts={} flyTarget={}",
                        reason, roamTicksRemaining, idleLockTicks,
                        (pathWaypoints == null ? 0 : pathWaypoints.size()), flyTarget);
            }
        } catch (Throwable t) {
            LOG.error("[RavenEntity] beginRoamFlightWindow failed (reason={})", reason, t);
            roamTicksRemaining = Math.max(1, ROAM_MIN_TICKS);

            if (idleLockTicks <= 0) {
                idleLockTicks = 12;
            }
        }
    }

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
            clearPlannedPath("enter idle: " + reason);
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

            roamTicksRemaining = 0;

            this.setNoGravity(false);
            this.setAnimMode(RavenAnimMode.NO_AIR);

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

    /**
     * Local avoidance hook (refactored):
     *  - If we have a planned pathGoal, first try to REPLAN using A* (bounded and throttled).
     *  - If replan fails (or cooldown), fall back to the old "avoidance waypoint" behavior.
     *
     * Surgical fix #2:
     *  - If we are "stuck" and we have a goal, we also allow retries on the retry scheduler.
     *    This ensures that even if we temporarily thought we were in a wall and A* maxExpanded, we try again soon.
     */
    private void maybeAvoidOrRetargetDuringFlight(RandomSource rnd) {
        if (flyTarget == null) {
            return;
        }

        // -----------------------------
        // 1) SPAWN / TAKEOFF GRACE
        // -----------------------------
        // Newly spawned or just-took-off ravens always report collisions.
        // Ignore collision-based logic until they've had time to separate from blocks.
        if (this.tickCount < 10) {
            return;
        }

        Vec3 pos = this.position();
        Vec3 vel = this.getDeltaMovement();
        double speedSqr = vel.lengthSqr();

        // -----------------------------
        // 2) MUST BE ACTUALLY MOVING
        // -----------------------------
        // If we're not really moving yet, collision flags are meaningless.
        if (speedSqr < 0.0004D) { // ~0.02 blocks/tick
            return;
        }

        double dist = pos.distanceTo(flyTarget);

        // -----------------------------
        // 3) PROGRESS TRACKING
        // -----------------------------
        if (!Double.isNaN(lastDistToTarget)) {
            double progress = lastDistToTarget - dist;
            if (progress < STUCK_PROGRESS_EPS) {
                stuckTicks++;
            } else {
                stuckTicks = Math.max(0, stuckTicks - 2);
            }
        }
        lastDistToTarget = dist;

        // -----------------------------
        // 4) COLLISION SEMANTICS (FIXED)
        // -----------------------------
        boolean collisionNow = this.horizontalCollision || this.verticalCollision;

        // Collision only matters if it PERSISTS while we're trying to move
        boolean collisionPersistent = collisionNow && stuckTicks >= 3;

        boolean rayBlocked = isDirectPathBlocked(pos, flyTarget);

        boolean pathBlocked = rayBlocked || collisionPersistent;

        if (collisionPersistent && this.tickCount % 20 == 0) {
            LOG.info(
                    "[RavenEntity] Persistent collision: pos={} vel={} flyTarget={} dist={} stuckTicks={} collH={} collV={} rayBlocked={}",
                    pos, vel, flyTarget,
                    String.format("%.3f", dist),
                    stuckTicks,
                    this.horizontalCollision,
                    this.verticalCollision,
                    rayBlocked
            );
        }

        // -----------------------------
        // 5) REPLAN FIRST (PREFERRED)
        // -----------------------------
        if (pathGoal != null && pathBlocked && pathRetryCooldownTicks <= 0) {
            long seed = this.getUUID().getLeastSignificantBits()
                    ^ (long) this.tickCount
                    ^ 0xA57C1EADL;

            boolean ok = ensurePathTo(
                    pathGoal,
                    5 * 20,
                    seed,
                    collisionPersistent ? "collision-persistent" : "blocked"
            );

            if (ok) {
                advanceWaypointIfNeeded(5 * 20, "collision replan");
                stuckTicks = 0;

                if (this.tickCount % 20 == 0) {
                    LOG.info("[RavenEntity] Replan succeeded after collision: goal={} pos={}", pathGoal, pos);
                }
                return;
            }
        }

        // -----------------------------
        // 6) FALLBACK: LOCAL AVOIDANCE
        // -----------------------------
        if (pathBlocked && avoidanceCooldownTicks <= 0) {
            avoidanceCooldownTicks = AVOIDANCE_COOLDOWN_TICKS;

            Vec3 avoidance = computeAvoidanceWaypoint(pos, flyTarget, rnd);
            if (avoidance != null) {
                setFlyTarget(avoidance, 4 * 20);
                stuckTicks = 0;

                if (this.tickCount % 20 == 0) {
                    LOG.info("[RavenEntity] Avoidance waypoint issued: {}", avoidance);
                }
                return;
            }
        }

        // -----------------------------
        // 7) LAST RESORT: STOP (NO BUZZING)
        // -----------------------------
        if (stuckTicks >= STUCK_TICKS_THRESHOLD) {
            clearFlyTarget();
            this.setDeltaMovement(Vec3.ZERO);

            if (this.tickCount % 20 == 0) {
                LOG.warn("[RavenEntity] Stuck hard-stop to prevent buzzing: pos={} goal={}", pos, pathGoal);
            }
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

            if (pathGoal != null) {
                long seed = this.getUUID().getMostSignificantBits() ^ (long) this.tickCount ^ 0xABCDEF12345L;
                boolean ok = ensurePathTo(pathGoal, 4 * 20, seed, "landing approach replan");
                if (ok) {
                    advanceWaypointIfNeeded(4 * 20, "landing approach replan");
                    if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                        LOG.debug("[RavenEntity] Landing-approach avoidance -> replanned path to goal={} (blocked={}, stuckTicks={})",
                                pathGoal, pathBlocked, stuckTicks);
                    }
                    return;
                }
            }

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

                    if (!hasAirColumn(leaf.above(), LAND_REQUIRED_AIR_ABOVE)) {
                        continue;
                    }

                    if (!isThickCanopyLeaf(leaf)) {
                        continue;
                    }

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

    private void flyTowardTarget(double speed) {
        try {
            if (flyTarget == null) {
                return;
            }

            Vec3 pos = this.position();
            Vec3 to = flyTarget.subtract(pos);
            double dist = to.length();

            if (dist < ARRIVE_DIST) {
                return;
            }

            Vec3 dir = to.normalize();

            // Gentle slowdown near target (keeps it from overshooting and jittering)
            double scaledSpeed = speed;
            if (dist < 6.0D) {
                scaledSpeed = speed * 0.65D;
            }

            Vec3 desiredVel = dir.scale(scaledSpeed);

            boolean collH = this.horizontalCollision;
            boolean collV = this.verticalCollision;

            // DO NOT hard-stop on collision; slide/dampen instead.
            double vx = desiredVel.x;
            double vy = desiredVel.y;
            double vz = desiredVel.z;

            if (collH) {
                vx *= 0.35D;
                vz *= 0.35D;
            }
            if (collV) {
                vy *= 0.35D;
            }

            Vec3 newVel = new Vec3(vx, vy, vz);

            // If we got damped into almost-zero, nudge out so we don't “buzz-lock”.
            if (newVel.lengthSqr() < 0.0005D) {
                RandomSource rnd = this.getRandom();
                newVel = new Vec3(
                        (rnd.nextDouble() - 0.5D) * 0.08D,
                        0.06D,
                        (rnd.nextDouble() - 0.5D) * 0.08D
                );

                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] flyTowardTarget: collision-nudge applied (collH={}, collV={}) pos={} target={} vel={}",
                            collH, collV, pos, flyTarget, newVel);
                }
            }

            this.setDeltaMovement(newVel);

            float yaw = (float) (Math.atan2(newVel.z, newVel.x) * (180.0D / Math.PI)) - 90.0F;
            this.setYRot(yaw);
            this.setYHeadRot(yaw);
            this.yBodyRot = yaw;

        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                LOG.warn("[RavenEntity] flyTowardTarget failed: {}", t.toString());
            }
        }
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
    // More helpers..
    // -----------------

    private Vec3 computeSafePathingGoal(Vec3 rawGoal, RavenAStarPathing.Config cfg) {
        try {
            if (rawGoal == null) {
                return null;
            }
            if (cfg == null) {
                cfg = new RavenAStarPathing.Config();
            }

            // These match the A* node passability semantics.
            final int clearanceXZ = Math.max(1, cfg.cellSize);
            final int clearanceH = Math.max(1, cfg.clearanceHeight);

            // "Goal" should be an air cell with all around air — you described a 3x3x3 concept.
            // We implement that as:
            //  - core clearance volume must be passable (clearanceXZ x clearanceXZ x clearanceH)
            //  - plus a small safety buffer (3x3 in XZ, and +-0..1 in Y depending on clearanceH)
            //
            // This is deliberately strict for goal selection: it prevents "barely clips head into ceiling" cases.
            final int safetyR = 1;                 // 3x3 in XZ incl diagonals
            final int safetyUp = 1;                // one block above
            final int safetyDown = 0;              // don't force below to be empty (flight)

            // Snap candidate center to block centers in X/Z so we don't anchor clearance into neighbors.
            final double snapX = Math.floor(rawGoal.x) + 0.5D;
            final double snapZ = Math.floor(rawGoal.z) + 0.5D;

            // We probe in increasing "distance" from the requested goal.
            // Offsets are in BLOCKS (since your A* uses block-based clearance checks).
            final int maxR = 4; // radius in blocks around the target
            final int[] yOffsets = new int[]{0, 1, -1, 2, -2}; // keep it modest; FOLLOW uses y+2 already

            // Candidate bases:
            // 1) raw (as-is)
            // 2) snapped XZ
            // 3) lightly lerped toward snap (stabilizes)
            final Vec3[] baseCandidates = new Vec3[]{
                    rawGoal,
                    new Vec3(snapX, rawGoal.y, snapZ),
                    new Vec3(Mth.lerp(0.35D, rawGoal.x, snapX), rawGoal.y, Mth.lerp(0.35D, rawGoal.z, snapZ))
            };

            for (Vec3 base : baseCandidates) {
                // Spiral-like probe by manhattan shells: r=0..maxR
                for (int r = 0; r <= maxR; r++) {
                    // generate offsets on the "shell" r (manhattan)
                    for (int dx = -r; dx <= r; dx++) {
                        int dzAbs = r - Math.abs(dx);
                        // two dz options: +dzAbs and -dzAbs (if dzAbs == 0 they are the same)
                        int[] dzOptions = (dzAbs == 0) ? new int[]{0} : new int[]{dzAbs, -dzAbs};

                        for (int dz : dzOptions) {
                            for (int dy : yOffsets) {
                                Vec3 cand = new Vec3(base.x + dx, base.y + dy, base.z + dz);

                                // First: core clearance passability
                                if (!looksLocallyPassableForClearance(cand, clearanceXZ, clearanceH, cfg.allowLeaves, cfg.allowReplaceables)) {
                                    continue;
                                }

                                // Second: strict "goal bubble" safety buffer (3x3x(1+)) around the node center/anchor region
                                if (!looksLocallyPassableSafetyBuffer(cand, clearanceXZ, clearanceH, safetyR, safetyUp, safetyDown, cfg.allowLeaves, cfg.allowReplaceables)) {
                                    continue;
                                }

                                // If we got here, this is a truly "safe goal" near the desired one.
                                if (this.tickCount % 20 == 0) {
                                    boolean changed = cand.distanceToSqr(rawGoal) > 1.0E-6D;
                                    if (changed) {
                                        LOG.info("[RavenEntity] Goal adjusted near target: rawGoal={} -> chosenGoal={} (dx={}, dy={}, dz={}, clearance={}x{} safety=({} r, +{} -{}))",
                                                rawGoal, cand, dx, dy, dz, clearanceXZ, clearanceH, safetyR, safetyUp, safetyDown);
                                    } else {
                                        LOG.debug("[RavenEntity] Goal accepted without adjustment: goal={} (clearance={}x{} safety=({} r, +{} -{}))",
                                                cand, clearanceXZ, clearanceH, safetyR, safetyUp, safetyDown);
                                    }
                                }

                                return cand;
                            }
                        }
                    }
                }
            }

            // No nearby safe goal found; return original (caller will likely fail-path and retry later).
            if (this.tickCount % 40 == 0) {
                LOG.warn("[RavenEntity] No nearby safe goal found. Using rawGoal={} (clearance={}x{}, allowLeaves={}, allowReplaceables={})",
                        rawGoal, clearanceXZ, clearanceH, cfg.allowLeaves, cfg.allowReplaceables);
            }
            return rawGoal;

        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                LOG.warn("[RavenEntity] computeSafePathingGoal failed: {}", t.toString());
            }
            return rawGoal;
        }
    }

    private boolean looksLocallyPassableSafetyBuffer(
            Vec3 center,
            int clearanceXZ,
            int clearanceH,
            int rXZ,
            int yUp,
            int yDown,
            boolean allowLeaves,
            boolean allowReplaceables
    ) {
        try {
            if (center == null) {
                return false;
            }

            // We anchor similarly to your clearance check: floor(x/y/z) are the "anchor blocks".
            // For safety buffer we want a symmetric neighborhood around the *node's implied center region*.
            // We'll bias toward the middle of the clearance footprint.
            int ax = Mth.floor(center.x + 1.0E-4D);
            int ay = Mth.floor(center.y + 1.0E-4D);
            int az = Mth.floor(center.z + 1.0E-4D);

            int centerX = ax + Math.max(0, clearanceXZ / 2);
            int centerY = ay + Math.max(0, clearanceH / 2);
            int centerZ = az + Math.max(0, clearanceXZ / 2);

            int rx = Math.max(0, rXZ);
            int up = Math.max(0, yUp);
            int down = Math.max(0, yDown);

            for (int dx = -rx; dx <= rx; dx++) {
                for (int dz = -rx; dz <= rx; dz++) {
                    for (int dy = -down; dy <= up; dy++) {
                        BlockPos p = new BlockPos(centerX + dx, centerY + dy, centerZ + dz);

                        if (this.level().isEmptyBlock(p)) {
                            continue;
                        }

                        BlockState st = this.level().getBlockState(p);
                        if (st == null) {
                            return false;
                        }

                        if (allowLeaves && st.is(BlockTags.LEAVES)) {
                            continue;
                        }

                        if (allowReplaceables && st.canBeReplaced()) {
                            continue;
                        }

                        // Any solid-ish block inside the buffer => not safe.
                        return false;
                    }
                }
            }

            return true;
        } catch (Throwable t) {
            return false;
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
