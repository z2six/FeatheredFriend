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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

// Debug particles
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.phys.AABB;

// Modules
import net.z2six.featheredfriend.entity.raven.modules.LureFollowTame;
import net.z2six.featheredfriend.entity.raven.modules.Teleportation;
import net.z2six.featheredfriend.entity.raven.modules.Landing;
import net.z2six.featheredfriend.entity.raven.modules.PlayerAvoidance;
import net.z2six.featheredfriend.entity.raven.pathing.RavenAStarPathing;
import net.z2six.featheredfriend.entity.raven.RavenSoundEngine;

import java.util.Collections;
import java.util.List;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenEntity.java
 *
 * Raven entity with phase-based AI focusing on:
 *  - Perching ~75% of the time (IDLE_GROUND on LEAVES, NO_AIR).
 *  - Roaming/flying ~25% of the time (ROAM_FLY free flight) before attempting a landing.
 *  */

public class RavenEntity extends TamableAnimal implements GeoEntity {

    // Constructor
    public RavenEntity(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
    }

    // -------------
    // BEGIN VARS
    // -------------

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

    // --- Lure/follow data accessors (MUST live here, not in LureFollowTame) ---
    public static final EntityDataAccessor<Boolean> DATA_LURE_FOLLOW_ARMED =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Boolean> DATA_LURE_FOLLOW_ACTIVE =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.BOOLEAN);

    // Follow cooldown (for lure/owner follow logic).
    public static final EntityDataAccessor<Integer> DATA_FOLLOW_COOLDOWN_TICKS =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    private static final String NBT_VARIANT = "RavenVariant";
    private static final String NBT_ANIM_MODE = "RavenAnimMode";
    private static final String NBT_AI_STATE = "RavenAIState";

    private static final String NBT_HOME_INIT = "RavenHomeInit";
    private static final String NBT_HOME_X = "RavenHomeX";
    private static final String NBT_HOME_Y = "RavenHomeY";
    private static final String NBT_HOME_Z = "RavenHomeZ";

    // Home bounds
    private static final int HOME_RADIUS_BLOCKS = 50;
    private static final int HOME_Y_DELTA = 15;

    static {
        Teleportation.initEntityData();
    }

    // --------------------
    // Modules
    // --------------------
    private final RavenSoundEngine soundEngine = new RavenSoundEngine(this);
    private final LureFollowTame lureFollowTame = new LureFollowTame(this); // Not part of sound but hey why not put it here
    private final Teleportation teleportation = new Teleportation(this);
    private final Landing landing = new Landing(this);
    private final PlayerAvoidance playeravoidance = new PlayerAvoidance(this);

    // --------------------
    // Sound handling
    // --------------------

    // Lazy-resolved sound IDs for raven SFX
    private static final ResourceLocation SOUND_RAVEN_CAWING_NORMAL_ID =
            ResourceLocation.fromNamespaceAndPath("featheredfriend", "raven.cawing_normal");

    // You might use these later from other behaviors:
    private static final ResourceLocation SOUND_RAVEN_CAW_AGREE_ID =
            ResourceLocation.fromNamespaceAndPath("featheredfriend", "raven.caw_agree");
    private static final ResourceLocation SOUND_RAVEN_CAW_DMG_ID =
            ResourceLocation.fromNamespaceAndPath("featheredfriend", "raven.caw_dmg");
    private static final ResourceLocation SOUND_RAVEN_CAW_WHISTLE_ID =
            ResourceLocation.fromNamespaceAndPath("featheredfriend", "raven.caw_whistle");
    private static final ResourceLocation SOUND_RAVEN_AIR_WOOSH_ID =
            ResourceLocation.fromNamespaceAndPath("featheredfriend", "raven.air_woosh");

    // Cached SoundEvents (lazy-resolved from the IDs above)
    private static SoundEvent cachedRavenCawingNormal = null;
    private static SoundEvent cachedRavenCawAgree = null;
    private static SoundEvent cachedRavenCawDmg = null;
    private static SoundEvent cachedRavenCawWhistle = null;
    private static SoundEvent cachedRavenAirWoosh = null;

    // Ambient caw cooldown (server ticks). 0 => may caw this tick if conditions match.
    private int ambientCawCooldownTicks = 0;

    // For damage handler
    // Post-teleport intent:
    // Default stays PERCH because your existing teleport recovery behavior wanted perching.
    // Damage-teleport overrides to ROAM_FLIGHT for "combat blink".
    private enum PostTeleportIntent {
        PERCH,
        ROAM_FLIGHT
    }

    private int lastPanicTeleportTick = Integer.MIN_VALUE;

    // Player-avoidance override: while > 0, avoidance has priority and we MUST NOT start landing / normal roam planning.
    private int playerAvoidanceOverrideTicks = 0;
    // Cooldown so the helper doesn't re-arm every single tick and spam A*.
    private int playerAvoidanceRearmCooldownTicks = 0;

    private PostTeleportIntent postTeleportIntent = PostTeleportIntent.PERCH;

    // Anti-spam: prevent multiple damage blinks in the same instant (fire ticks, thorns spam, etc.)
    private int lastDamageBlinkTick = -999999;
    private static final int DAMAGE_BLINK_MIN_INTERVAL_TICKS = 10; // 0.5s @ 20 TPS

    // Idle (15-30s) NOTE: 20 ticks = 1 second
    private static final int IDLE_MIN_TICKS = 15 * 20;
    private static final int IDLE_MAX_TICKS = 30 * 20;

    // Roam flight window before landing is allowed (4-10s) NOTE: 20 ticks = 1 second
    private static final int ROAM_MIN_TICKS = 4 * 20;
    private static final int ROAM_MAX_TICKS = 10 * 20;

    // Stuck / avoidance (used in free-flight and overhead approach)
    private static final int STUCK_TICKS_THRESHOLD = 30;
    private static final double STUCK_PROGRESS_EPS = 0.06D;
    private static final int AVOIDANCE_COOLDOWN_TICKS = 8;

    // Movement tuning
    private static final double FLY_SPEED_BASE = 0.25D;
    private static final double FLY_SPEED_RETURN = 0.32D;
    private static final double ARRIVE_DIST = 1.2D;

    // IDLE stability lock + leaf-loss grace counter
    private static final int IDLE_LOCK_TICKS = 50; // ~2.5s
    private static final int IDLE_LEAF_LOSS_GRACE_TICKS = 20; // sustained loss before leaving idle

    // Settling physics while idle-locking above leaves (must not cancel Y)
    private static final double IDLE_SETTLE_NUDGE_DOWN = -0.10D;
    private static final double IDLE_SETTLE_MIN_FALL = -0.28D;
    private static final double IDLE_SETTLE_MAX_UP = 0.04D;

    // Random blink during flight
    private RavenAIState flightTeleportLastAI = null;
    private int flightTeleportBudget = 0;              // 0..2 per flight session
    private int flightTeleportUsed = 0;
    private int flightTeleportCheckCooldownTicks = 0;  // throttle checks
    private int flightTeleportHardCooldownTicks = 0;   // throttle actual teleports

    // Debug throttle
    private static final int DEBUG_LOG_INTERVAL_TICKS = 120;

    // -------------------- Debug, gizmos, etc --------------------
    // Debug: visualize A* path / waypoints in-world with labeled markers.
    private static final boolean DEBUG_DRAW_PATH_GIZMOS = true; // set to true when testing

    // How many waypoints around the current index to visualize (current + next N-1).
    private static final int DEBUG_PATH_GIZMO_MAX_WAYPOINTS = 20;

    // Only draw gizmos every N ticks to avoid excessive particles/entities.
    private static final int DEBUG_PATH_GIZMO_TICK_INTERVAL = 2;

    /**
     * Entity IDs of the active debug path markers so we can clean them up.
     */
    private final java.util.List<Integer> debugPathMarkerIds = new java.util.ArrayList<>();

    // Debug gizmo helpers
    private static final String DEBUG_PATH_GIZMO_TAG = "raven_path_gizmo";
    private static final double DEBUG_PATH_GIZMO_CLEAN_RADIUS = 96.0D; // blocks around raven

    // -------------------- Pathing (coarse A*) --------------------
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

    private int idleCommitTicks = 0;

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

    // IDLE stability lock + leaf-loss grace counter
    private int idleLockTicks = 0;
    private int idleLeafLossTicks = 0;

    // One-time settle-to-ground logic when entering IDLE_GROUND.
    private boolean idleSettleArmed = false;
    private boolean idleSettlingActive = false;

    // Landing phase + ticks remain here ONLY if you are not yet delegating the state machine fields to Landing.
    // (If you are delegating: remove these two fields + the enum from RavenEntity and put them into Landing.)
    private LandingPhase landingPhase = LandingPhase.NONE;
    private int landingTicks = 0;

    // Landing state machine (ROAM_FLY only)
    private enum LandingPhase {
        NONE,
        FLY_TO_OVERHEAD,
        DESCEND_SLOW,
        DROP
    }

    // -------------
    // END VARS
    // -------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);

        // Core raven state
        builder.define(DATA_VARIANT, RavenVariant.NORMAL.id());
        builder.define(DATA_ANIM_MODE, RavenAnimMode.AUTO.id());
        builder.define(DATA_AI_STATE, RavenAIState.IDLE_GROUND.id());

        // Follow cooldown shared with LureFollowTame via DATA_FOLLOW_COOLDOWN_TICKS
        builder.define(DATA_FOLLOW_COOLDOWN_TICKS, 0);

        // Teleport FX sync (client renders short burst)
        builder.define(Teleportation.DATA_TELEPORT_FX_TICKS, 0);
        builder.define(Teleportation.DATA_TELEPORT_FX_SEED, 0L);

        // Transparency
        builder.define(Teleportation.DATA_TELEPORT_FADE_ALPHA, 255);

        // Lure/follow flags
        builder.define(DATA_LURE_FOLLOW_ARMED, Boolean.FALSE);
        builder.define(DATA_LURE_FOLLOW_ACTIVE, Boolean.FALSE);
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
            // Current state from synced data
            RavenAIState prev = getAIState();

            // ----------------------------------------
            // FOLLOW OVERRIDE GUARD:
            //
            // While followOverrideActive is true, FOLLOW_OWNER should "own" the AI.
            // Older logic (idle/perch/normalisation) still tries to force IDLE_GROUND
            // every tick, which causes:
            //   FOLLOW_OWNER -> IDLE_GROUND -> FOLLOW_OWNER -> ...
            //
            // We block ONLY the downgrade FOLLOW_OWNER -> IDLE_GROUND here,
            // so teleports / ROAM_FLY etc. still work.
            // ----------------------------------------
            boolean followOverride = (this.lureFollowTame != null && this.lureFollowTame.isFollowOverrideActive());

            if (followOverride
                    && prev == RavenAIState.FOLLOW_OWNER
                    && state == RavenAIState.IDLE_GROUND) {

                if (this.tickCount % 40 == 0) {
                    LOG.debug(
                            "[RavenEntity] setAIState: ignoring downgrade FOLLOW_OWNER -> IDLE_GROUND while followOverrideActive. " +
                                    "pos={} flyTarget={} pathGoal={}",
                            this.position(),
                            flyTarget,
                            pathGoal
                    );
                }
                return; // do not change DATA_AI_STATE
            }

            // Arm settle logic ONLY when transitioning into IDLE_GROUND from a different state (server-side).
            try {
                if (!this.level().isClientSide) {
                    if (state == RavenAIState.IDLE_GROUND && prev != RavenAIState.IDLE_GROUND) {
                        idleSettleArmed = true;
                        idleSettlingActive = true;

                        if (this.tickCount % 40 == 0) {
                            LOG.debug(
                                    "[RavenEntity] Armed one-time idle settle (prev={}, next={}) pos={} bbMinY={} vel={}",
                                    prev,
                                    state,
                                    this.position(),
                                    this.getBoundingBox().minY,
                                    this.getDeltaMovement()
                            );
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.warn("[RavenEntity] setAIState arm-settle failed: {}", t.toString());
            }

            // If same-state spam happens, log occasionally so you can see it.
            if (prev == state) {
                if (this.tickCount % 80 == 0) {
                    LOG.info(
                            "[RavenEntity] setAIState(same): {} pos={} landingPhase={} landingLeafPos={} " +
                                    "idleTicksRemaining={} idleCommitTicks={} roamTicksRemaining={} " +
                                    "flyTarget={} flyTtl={} pathGoal={} pendingGoal={} pathPts={} pathIdx={}",
                            state,
                            this.position(),
                            landingPhase,
                            landing.landingLeafPos,
                            idleTicksRemaining,
                            idleCommitTicks,
                            roamTicksRemaining,
                            flyTarget,
                            flyTargetTimeoutTicks,
                            pathGoal,
                            pathPendingGoal,
                            (pathWaypoints == null ? 0 : pathWaypoints.size()),
                            pathWaypointIndex
                    );
                }
            } else {
                // Real transition: log every time.
                LOG.info(
                        "[RavenEntity] AI STATE CHANGE: {} -> {} pos={} vel={} noGravity={} onGround={} " +
                                "landingPhase={} landingLeafPos={} landingTicks={} " +
                                "idlePerchCorner={} idleTicksRemaining={} idleCommitTicks={} idleLeafLossTicks={} idleLockTicks={} " +
                                "roamTicksRemaining={} flyTarget={} flyTtl={} pathGoal={} pendingGoal={} pathPts={} pathIdx={}",
                        prev,
                        state,
                        this.position(),
                        this.getDeltaMovement(),
                        this.isNoGravity(),
                        this.onGround(),
                        landingPhase,
                        landing.landingLeafPos,
                        landingTicks,
                        landing.idlePerchCorner,
                        idleTicksRemaining,
                        idleCommitTicks,
                        idleLeafLossTicks,
                        idleLockTicks,
                        roamTicksRemaining,
                        flyTarget,
                        flyTargetTimeoutTicks,
                        pathGoal,
                        pathPendingGoal,
                        (pathWaypoints == null ? 0 : pathWaypoints.size()),
                        pathWaypointIndex
                );
            }

        } catch (Throwable t) {
            LOG.warn("[RavenEntity] setAIState logging failed safely: {}", t.toString());
        }

        // Final authoritative write
        this.entityData.set(DATA_AI_STATE, state.id());
    }

    private void debugAiHeartbeat(String where) {
        try {
            if (this.level().isClientSide) return;

            // once per second
            if (this.tickCount % 20 != 0) return;

            RavenAIState st = getAIState();

            LOG.info("[RavenEntity] HEARTBEAT({}): ai={} pos={} vel={} noGravity={} onGround={} hColl={} vColl={} landingPhase={} landingLeafPos={} landingTicks={} idlePerchCorner={} idleTicksRemaining={} idleCommitTicks={} idleLeafLossTicks={} idleLockTicks={} roamTicksRemaining={} flyTarget={} flyTtl={} pathGoal={} pendingGoal={} pathPts={} pathIdx={}",
                    where,
                    st,
                    this.position(),
                    this.getDeltaMovement(),
                    this.isNoGravity(),
                    this.onGround(),
                    this.horizontalCollision,
                    this.verticalCollision,
                    landingPhase,
                    landing.landingLeafPos,
                    landingTicks,
                    landing.idlePerchCorner,
                    idleTicksRemaining,
                    idleCommitTicks,
                    idleLeafLossTicks,
                    idleLockTicks,
                    roamTicksRemaining,
                    flyTarget,
                    flyTargetTimeoutTicks,
                    pathGoal,
                    pathPendingGoal,
                    (pathWaypoints == null ? 0 : pathWaypoints.size()),
                    pathWaypointIndex
            );
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] debugAiHeartbeat failed safely: {}", t.toString());
            }
        }
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
        try {
            int wpSize = 0;
            boolean hadWaypoints = false;
            try {
                if (pathWaypoints != null) {
                    hadWaypoints = !pathWaypoints.isEmpty();
                    wpSize = pathWaypoints.size();
                }
            } catch (Throwable ignored) {
                hadWaypoints = false;
                wpSize = 0;
            }

            if ((hadWaypoints || pathGoal != null || pathPendingGoal != null) && (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0)) {
                LOG.debug("[RavenEntity] clearPlannedPath(reason={}) goal={} pending={} waypoints={} idx={}",
                        reason, pathGoal, pathPendingGoal, wpSize, pathWaypointIndex);
            }

            pathGoal = null;
            pathPendingGoal = null; // IMPORTANT: clearing intent prevents retry-loop from replanning during landing/descent/idle

            // Your code expects "empty list" not "null".
            pathWaypoints = Collections.emptyList();
            pathWaypointIndex = 0;

            pathReplanCooldownTicks = 0;

            pathRetryCooldownTicks = 0;
            lastPathPlanAttemptTick = -1L;
            consecutivePathPlanFails = 0;
            consecutiveStartSampleAdjustments = 0;
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] clearPlannedPath failed safely: reason={} err={}", reason, t.toString());
            // Fail-safe hard clear
            try {
                pathGoal = null;
                pathPendingGoal = null;
                pathWaypoints = Collections.emptyList();
                pathWaypointIndex = 0;
                pathReplanCooldownTicks = 0;
                pathRetryCooldownTicks = 0;
                lastPathPlanAttemptTick = -1L;
            } catch (Throwable ignored) {
                // last-ditch: do nothing
            }
        }
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
     * Extremely cheap local passability probe for path start/goal sampling.
     *
     * NEW SEMANTICS (matching the "3x3 corridor only" rule):
     *  - Treat the candidate as valid ONLY if:
     *      * The center block (candidate) is empty/passable, AND
     *      * The full 3x3 area at the candidate Y is empty/passable, AND
     *      * The full 3x3 area at Y+1 is empty/passable.
     *
     *  This guarantees that we never pick a start anchor that is in an air block
     *  with a solid neighbor horizontally or directly overhead. The floor BELOW
     *  the candidate is allowed to be solid (we still want to be able to fly
     *  one block above the ground).
     *
     *  NOTE:
     *   - "Passable" respects allowLeaves / allowReplaceables flags, but for
     *     your Raven A* config we explicitly set those to false, so in practice
     *     ANY non-air block counts as solid here.
     */
    private boolean looksLocallyPassableForClearance(Vec3 start,
                                                     int clearanceXZ,
                                                     int clearanceH,
                                                     boolean allowLeaves,
                                                     boolean allowReplaceables) {
        try {
            if (start == null) return false;

            // Treat this Y as the "flight layer" (feet/base).
            int cx = Mth.floor(start.x + 1.0E-4D);
            int cy = Mth.floor(start.y + 1.0E-4D);
            int cz = Mth.floor(start.z + 1.0E-4D);

            // We require:
            //  - 3x3 area at (cy)
            //  - 3x3 area at (cy + 1)
            // Floor below (cy - 1) is allowed to be solid.
            for (int dy = 0; dy <= 1; dy++) {
                int y = cy + dy;

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos p = new BlockPos(cx + dx, y, cz + dz);

                        boolean empty;
                        try {
                            empty = this.level().isEmptyBlock(p);
                        } catch (Throwable t) {
                            // If we can't even query emptiness, be conservative: treat as blocked.
                            if (this.tickCount % 200 == 0) {
                                LOG.warn("[RavenEntity] looksLocallyPassableForClearance: isEmptyBlock failed at {} ({})",
                                        p, t.toString());
                            }
                            return false;
                        }

                        if (empty) {
                            continue;
                        }

                        BlockState st;
                        try {
                            st = this.level().getBlockState(p);
                        } catch (Throwable t) {
                            if (this.tickCount % 200 == 0) {
                                LOG.warn("[RavenEntity] looksLocallyPassableForClearance: getBlockState failed at {} ({})",
                                        p, t.toString());
                            }
                            return false;
                        }

                        if (st == null) {
                            return false;
                        }

                        // Respect flags if caller chooses to allow leaves/replaceables.
                        if (allowLeaves && st.is(BlockTags.LEAVES)) {
                            continue;
                        }
                        if (allowReplaceables && st.canBeReplaced()) {
                            continue;
                        }

                        // Any remaining non-air block in this 3x3x2 prism makes candidate invalid.
                        return false;
                    }
                }
            }

            // All samples OK -> locally clear.
            return true;
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] looksLocallyPassableForClearance failed: {}", t.toString());
            }
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
     *
     * NEW: configured for "1x1 cell + 3x3 safety corridor":
     *  - A* nodes occupy a 1x1x2 column (cellSize=1, clearanceHeight=2).
     *  - A safety buffer around each node (3x3 horizontally, 2 high) must be pure air.
     *    => the raven never flies in an air block that has a solid neighbor horizontally
     *       or directly overhead; only true 3x3 corridors / open space are valid.
     */
    private boolean ensurePathTo(@Nullable Vec3 goal,
                                 boolean highPriority,
                                 int timeoutTicks,
                                 long seed,
                                 @Nullable String reason) {
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

            // If we already have an active goal close to the requested one, avoid replanning,
            // BUT ONLY if we actually have a usable path (or an active flyTarget).
            if (this.pathGoal != null) {
                double d2 = this.pathGoal.distanceToSqr(goal);
                if (d2 <= PATH_GOAL_REPLAN_DIST_SQR) {
                    boolean hasUsablePath = !isPathExhausted();
                    boolean hasFly = (flyTarget != null && flyTargetTimeoutTicks > 0);

                    if (hasUsablePath || hasFly) {
                        return true;
                    } else {
                        if (this.tickCount % 40 == 0) {
                            LOG.debug("[RavenEntity] ensurePathTo(core): goal close but no usable path/flyTarget -> replanning (reason={}) goal={}",
                                    reason, goal);
                        }
                        // fall through and plan
                    }
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

            // ------------------------------------------------------------------
            // A* CONFIG — 1x1 footprint + 3x3 "no-touching" safety corridor
            // ------------------------------------------------------------------
            RavenAStarPathing.Config cfg = new RavenAStarPathing.Config();

            // 1x1 column, 2 blocks tall, grid step 1
            cfg.cellSize = 1;           // width/length in blocks
            cfg.clearanceHeight = 2;    // 2 blocks tall for the raven
            cfg.gridStep = 1;

            // We do NOT want to treat leaves or replaceables as "air" for flight.
            cfg.allowLeaves = false;
            cfg.allowReplaceables = false;

            cfg.maxExpanded = 6500;
            cfg.maxOpen = 16000;

            cfg.smoothPath = true;
            cfg.tieBreakSeed = seed;

            // HARD RULE: waypoints must never be adjacent (horizontally/overhead) to solids.
            // Enforce a safety buffer so each node has a clear 3x3x2 prism of air around it.
            cfg.useSafetyBuffer = true;
            cfg.safetyBufferXZ = 1;                // 3x3 horizontally
            cfg.safetyBufferYUp = 1;               // one block of headroom
            cfg.safetyBufferYDown = 0;             // floor beneath may be solid
            cfg.safetyBufferRespectsAllowFlags = false; // ANY non-air in buffer => node invalid

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
                double dg2 = safeGoal.distanceToSqr(rawGoal);
                if (dg2 > 1.0E-6D) {
                    LOG.info("[RavenEntity] ensurePathTo(core): goal adjusted rawGoal={} safeGoal={} d2={} (reason={})",
                            rawGoal, safeGoal, String.format("%.3f", dg2), reason);
                }
            }

            // Actually call A*
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

                // Backoffs
                pathFailCooldownTicks = Math.max(pathFailCooldownTicks, 12);
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

                return false;
            }

            // Success
            consecutivePathPlanFails = 0;

            this.pathGoal = safeGoal;
            this.pathWaypoints = pts;
            this.pathWaypointIndex = 0;

            pathReplanCooldownTicks = highPriority ? 4 : 8;
            pathRetryCooldownTicks = highPriority ? 4 : 10;
            pathFailCooldownTicks = 0;

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] ensurePathTo(core): A* OK pts={} reason={} start={} rawGoal={} safeGoal={} pos={}",
                        pts.size(), reason, safeStart, rawGoal, safeGoal, this.position());
            }

            // Prime movement immediately
            advanceWaypointIfNeeded(Math.max(20, timeoutTicks),
                    "ensurePathTo(core) accept: " + String.valueOf(reason));
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

            // Walk forward through any already-reached waypoints.
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

            // If we exhausted the waypoint list, DON'T immediately clear everything.
            // This is critical when A* returns pts=1 (start cell == goal cell):
            // we may still be far from the exact goal Vec3 center, so clearing causes replan thrash.
            if (pathWaypointIndex >= pathWaypoints.size()) {
                Vec3 goal = this.pathGoal;

                // If we still have a goal and we aren't actually "arrived" in world-space, keep flying to the goal directly.
                if (goal != null) {
                    double dGoal = pos.distanceTo(goal);

                    if (dGoal > ARRIVE_DIST) {
                        // Convert "path done" into a simple direct fly target to the goal center.
                        setFlyTarget(goal, Math.max(20, timeoutTicks));

                        // IMPORTANT: clear the waypoints so we don't keep completing/clearing the same list,
                        // but KEEP pathGoal/pathPendingGoal so intent remains stable.
                        this.pathWaypoints = Collections.emptyList();
                        this.pathWaypointIndex = 0;

                        if (this.tickCount % 20 == 0) {
                            LOG.info("[RavenEntity] advanceWaypointIfNeeded: path exhausted but not arrived -> flyDirectToGoal. reason={} goal={} dGoal={} pos={} flyTarget={}",
                                    reason,
                                    goal,
                                    String.format("%.3f", dGoal),
                                    pos,
                                    this.flyTarget);
                        }

                        return;
                    }
                }

                // Otherwise we truly arrived (or have no goal), so we can clear.
                clearFlyTarget();
                clearPlannedPath("path completed: " + reason);

                if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                    LOG.debug("[RavenEntity] Path completed -> cleared (reason={}) lastGoal={} pos={}",
                            reason, pathGoal, pos);
                }
                return;
            }

            // Normal case: continue toward next waypoint.
            Vec3 next = pathWaypoints.get(pathWaypointIndex);
            if (next != null) {
                setFlyTarget(next, timeoutTicks);

                if (this.tickCount % 60 == 0) {
                    LOG.debug("[RavenEntity] advanceWaypointIfNeeded: setFlyTarget to waypoint idx={}/{} reason={} wp={} pos={}",
                            pathWaypointIndex, pathWaypoints.size(), reason, next, pos);
                }
            } else {
                pathWaypointIndex++;
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] Null waypoint encountered -> skipping (idx now {}) reason={}", pathWaypointIndex, reason);
                }
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
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(
            net.minecraft.world.level.ServerLevelAccessor level,
            net.minecraft.world.DifficultyInstance difficulty,
            net.minecraft.world.entity.MobSpawnType spawnType,
            @org.jetbrains.annotations.Nullable net.minecraft.world.entity.SpawnGroupData spawnGroupData
    ) {
        net.minecraft.world.entity.SpawnGroupData out = null;

        try {
            out = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        } catch (Throwable t) {
            // Fail-safe: if vanilla spawn init ever changes or throws, we still want the raven to exist.
            LOG.warn("[RavenEntity] finalizeSpawn: super.finalizeSpawn failed safely: {}", t.toString());
            out = spawnGroupData;
        }

        try {
            // ------------------------------------------------------------
            // Roll per-spawn tame-cost (3..6 golden nuggets)
            // Only roll if not already set (e.g., NBT-loaded or manually assigned).
            // ------------------------------------------------------------
            initGoldenNuggetsRequiredToTameIfNeeded("finalizeSpawn:" + spawnType);

            if (this.tickCount % 20 == 0) {
                LOG.debug("[RavenEntity] finalizeSpawn: nuggetsRequiredToTame={} spawnType={} pos={}",
                        this.getGoldenNuggetsRequiredToTame(),
                        spawnType,
                        this.position());
            }
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] finalizeSpawn: tame-cost init failed safely: {}", t.toString());
        }

        return out;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        try {
            tag.putInt(NBT_VARIANT, this.entityData.get(DATA_VARIANT));
            tag.putInt(NBT_ANIM_MODE, this.entityData.get(DATA_ANIM_MODE));
            tag.putInt(NBT_AI_STATE, this.entityData.get(DATA_AI_STATE));
            // Use the shared key & data accessor from LureFollowTame
            tag.putInt(LureFollowTame.NBT_FOLLOW_CD, this.entityData.get(LureFollowTame.DATA_FOLLOW_COOLDOWN_TICKS));

            tag.putBoolean(NBT_HOME_INIT, homeInitialized);
            tag.putInt(NBT_HOME_X, homePos.getX());
            tag.putInt(NBT_HOME_Y, homePos.getY());
            tag.putInt(NBT_HOME_Z, homePos.getZ());

            // ------------------------------------------------------------
            // Persist per-spawn tame-cost (3..6 golden nuggets)
            // ------------------------------------------------------------
            try {
                // Ensure it's initialized before saving (server side typically).
                if (this.getGoldenNuggetsRequiredToTame() <= 0 && this.level() != null && !this.level().isClientSide) {
                    initGoldenNuggetsRequiredToTameIfNeeded("save");
                }

                int v = this.getGoldenNuggetsRequiredToTame();
                if (v < 3) v = 3;
                if (v > 6) v = 6;

                tag.putInt(LureFollowTame.NBT_TAME_NUGGETS_REQUIRED, v);

                if (this.tickCount % 200 == 0) {
                    LOG.debug("[RavenEntity] Saved tame-cost: {}={}",
                            LureFollowTame.NBT_TAME_NUGGETS_REQUIRED, v);
                }
            } catch (Throwable t2) {
                if (this.tickCount % 200 == 0) {
                    LOG.warn("[RavenEntity] Failed writing tame-cost NBT safely: {}", t2.toString());
                }
            }

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
                RavenVariant v = RavenVariant.fromId(id);
                this.setRavenVariant(v);
            }

            if (tag.contains(NBT_ANIM_MODE)) {
                int id = tag.getInt(NBT_ANIM_MODE);
                RavenAnimMode m = RavenAnimMode.fromId(id);
                this.setAnimMode(m);
            }

            if (tag.contains(NBT_AI_STATE)) {
                int id = tag.getInt(NBT_AI_STATE);
                RavenAIState s = RavenAIState.fromId(id);
                this.setAIState(s);
            }

            // Follow cooldown
            if (tag.contains(LureFollowTame.NBT_FOLLOW_CD)) {
                int cd = tag.getInt(LureFollowTame.NBT_FOLLOW_CD);
                this.setFollowCooldownTicks(cd);
            }

            if (tag.contains(NBT_HOME_INIT)) {
                this.homeInitialized = tag.getBoolean(NBT_HOME_INIT);
            }
            if (tag.contains(NBT_HOME_X) && tag.contains(NBT_HOME_Y) && tag.contains(NBT_HOME_Z)) {
                this.homePos = new BlockPos(tag.getInt(NBT_HOME_X), tag.getInt(NBT_HOME_Y), tag.getInt(NBT_HOME_Z));
            }

            // ------------------------------------------------------------
            // Load per-spawn tame-cost (3..6 golden nuggets)
            // ------------------------------------------------------------
            try {
                if (tag.contains(LureFollowTame.NBT_TAME_NUGGETS_REQUIRED, net.minecraft.nbt.Tag.TAG_INT)) {
                    int v = tag.getInt(LureFollowTame.NBT_TAME_NUGGETS_REQUIRED);
                    if (v < 3) v = 3;
                    if (v > 6) v = 6;

                    this.setGoldenNuggetsRequiredToTame(v);

                    if (this.tickCount % 200 == 0) {
                        LOG.debug("[RavenEntity] Loaded tame-cost: {}={}",
                                LureFollowTame.NBT_TAME_NUGGETS_REQUIRED, v);
                    }
                } else {
                    // Older saves: initialize safely (server side).
                    initGoldenNuggetsRequiredToTameIfNeeded("load-missingTag");
                }
            } catch (Throwable t2) {
                if (this.tickCount % 200 == 0) {
                    LOG.warn("[RavenEntity] Failed reading tame-cost NBT safely: {}", t2.toString());
                }
                this.setGoldenNuggetsRequiredToTame(4);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] Failed reading NBT", t);
        }
    }

    // -----------------
    // AI tick (main, roam, idle
    // -----------------

    @Override
    public void aiStep() {
        super.aiStep();

        if (this.level().isClientSide) {
            return;
        }

        try {
            ensureHomeInitialized();

            // ------------------------------------------------------------------
            // TELEPORT SEQUENCE HAS ABSOLUTE PRIORITY
            // ------------------------------------------------------------------
            teleportation.tickTeleportSequenceServer(this);

            if (teleportation.teleportSeqPhase != Teleportation.TeleportSeqPhase.NONE) {
                // While teleporting, we do NOTHING else except FX.
                teleportation.tickTeleportFxServer(this);
                return;
            }

            // ------------------------------------------------------------------
            // GLOBAL COOLDOWNS (always tick, even during avoidance)
            // ------------------------------------------------------------------
            int cd = getFollowCooldownTicks();
            if (cd > 0) {
                setFollowCooldownTicks(cd - 1);
            }

            if (avoidanceCooldownTicks > 0) {
                avoidanceCooldownTicks--;
            }

            if (pathFailCooldownTicks > 0) {
                pathFailCooldownTicks--;
            }
            if (pathReplanCooldownTicks > 0) {
                pathReplanCooldownTicks--;
            }
            if (pathRetryCooldownTicks > 0) {
                pathRetryCooldownTicks--;
            }

            // ------------------------------------------------------------------
            // PATH RETRY LOOP — HARD GATED BY PLAYER AVOIDANCE
            // ------------------------------------------------------------------
            {
                RavenAIState st = getAIState();

                boolean retryAllowed =
                        (teleportation.teleportSeqPhase == Teleportation.TeleportSeqPhase.NONE) &&
                                (playerAvoidanceOverrideTicks <= 0) && // 🔒 HARD GATE
                                (st != RavenAIState.IDLE_GROUND) &&
                                (landingPhase == LandingPhase.NONE) &&
                                (idleLockTicks <= 0);

                if (retryAllowed) {
                    Vec3 retryGoal = (pathGoal != null) ? pathGoal : pathPendingGoal;
                    boolean noUsablePath = isPathExhausted();

                    if (retryGoal != null && noUsablePath) {
                        if (pathRetryTicks > 0) {
                            pathRetryTicks--;
                        } else {
                            pathRetryTicks = PATH_RETRY_INTERVAL_TICKS;

                            pathRetryCooldownTicks = 0;
                            pathReplanCooldownTicks = 0;

                            long seed =
                                    this.getUUID().getLeastSignificantBits()
                                            ^ (long) this.tickCount
                                            ^ 0xA5A5A5A5L;

                            LOG.info(
                                    "[RavenEntity] A* retry tick: retryGoal={} pos={} vel={} collH={} collV={} fails={}",
                                    retryGoal,
                                    this.position(),
                                    this.getDeltaMovement(),
                                    this.horizontalCollision,
                                    this.verticalCollision,
                                    consecutivePathPlanFails
                            );

                            boolean ok = ensurePathTo(retryGoal, 6 * 20, seed, "retry-loop");

                            if (ok) {
                                LOG.info(
                                        "[RavenEntity] A* retry SUCCESS -> pts={} idx={} flyTarget={}",
                                        (pathWaypoints == null ? 0 : pathWaypoints.size()),
                                        pathWaypointIndex,
                                        flyTarget
                                );
                            } else {
                                LOG.info(
                                        "[RavenEntity] A* retry FAILED -> nextRetryIn={}t",
                                        PATH_RETRY_INTERVAL_TICKS
                                );
                            }
                        }
                    } else {
                        pathRetryTicks = Math.min(pathRetryTicks, PATH_RETRY_INTERVAL_TICKS);
                    }
                } else {
                    pathRetryTicks = Math.min(pathRetryTicks, PATH_RETRY_INTERVAL_TICKS);
                }
            }

            // ------------------------------------------------------------------
            // OUT-OF-HOME-BOUNDS — DISABLED DURING PLAYER AVOIDANCE
            // ------------------------------------------------------------------
            if (playerAvoidanceOverrideTicks <= 0 && isOutOfHomeBounds(this.position())) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] Out of bounds, commanding return to home bounds");
                }

                setAIState(RavenAIState.ROAM_FLY);
                landing.resetLandingState("out-of-bounds", this);
                idleLockTicks = 0;
                idleLeafLossTicks = 0;

                Vec3 ret = homeCenterReturnTarget();
                long seed = this.getUUID().getMostSignificantBits() ^ (long) this.tickCount;

                boolean ok = ensurePathTo(ret, 10 * 20, seed, "out-of-bounds return");
                if (!ok) {
                    setFlyTarget(ret, 10 * 20);
                }

                if (roamTicksRemaining <= 0) {
                    beginRoamFlightWindow("out-of-bounds return");
                    clearPlannedPath("override roam path with return");

                    boolean ok2 = ensurePathTo(
                            ret,
                            10 * 20,
                            seed ^ 0xBADC0FFEE0DDF00DL,
                            "out-of-bounds return (after roam window)"
                    );
                    if (!ok2) {
                        setFlyTarget(ret, 10 * 20);
                    }
                }
            }

            // ------------------------------------------------------------------
            // FOLLOW LOGIC — DISABLED DURING PLAYER AVOIDANCE
            // ------------------------------------------------------------------
            Player owner = (lureFollowTame != null) ? lureFollowTame.getOwnerPlayerServerSafe() : null;
            boolean canFollow =
                    owner != null &&
                            this.isTame() &&
                            getFollowCooldownTicks() <= 0;

            if (playerAvoidanceOverrideTicks <= 0) {
                if (canFollow) {
                    if (isOutOfHomeBounds(owner.position())) {
                        if (lureFollowTame != null) {
                            lureFollowTame.triggerFollowCooldownAndReturn();
                        }
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
            }

            // ------------------------------------------------------------------
            // AI STATE DISPATCH
            // ------------------------------------------------------------------
            switch (getAIState()) {
                case IDLE_GROUND -> tickIdleGround();
                case ROAM_FLY -> tickRoamFly();
                case FOLLOW_OWNER -> {
                    if (lureFollowTame != null) {
                        lureFollowTame.tickFollowOwner();
                    }
                }
                default -> tickIdleGround();
            }

            // ------------------------------------------------------------------
            // DEBUG: Path gizmos / waypoint visualization (server-only)
            // ------------------------------------------------------------------
            debugDrawPathGizmos("aiStep");

            // ------------------------------------------------------------------
            // TELEPORT RECOVERY + FX
            //  - Skip the "stuck" teleport sampler while we are successfully
            //    parked near our follow/lure target, so deliberate hovering
            //    in front of the player does NOT count as "stuck".
            // ------------------------------------------------------------------
            boolean skipStuckTeleport = false;
            if (getAIState() == RavenAIState.FOLLOW_OWNER && lureFollowTame != null) {
                try {
                    // Prefer the real owner when tamed, otherwise the lure player.
                    Player p = lureFollowTame.getOwnerPlayerServerSafe();
                    if (p == null) {
                        p = lureFollowTame.getLureFollowPlayerServerSafe();
                    }

                    if (p != null && lureFollowTame.isCloseEnoughToFollowPlayer(p)) {
                        skipStuckTeleport = true;
                    }
                } catch (Throwable t) {
                    if (this.tickCount % 80 == 0) {
                        LOG.warn("[RavenEntity] aiStep: stuck-teleport guard failed safely: {}", t.toString());
                    }
                }
            }

            if (!skipStuckTeleport) {
                teleportation.tickTeleportRecoverySampler(this);
            } else if (this.tickCount % 40 == 0) {
                LOG.debug(
                        "[RavenEntity] Skipping teleportRecoverySampler while parked near follow target. pos={} aiState={}",
                        this.position(),
                        getAIState()
                );
            }

            teleportation.tickTeleportFxServer(this);

        } catch (Throwable t) {
            LOG.error("[RavenEntity] aiStep failed", t);
        }
    }

    private void tickIdleGround() {
        try {
            debugAiHeartbeat("tickIdleGround");
            RandomSource rnd = this.getRandom();

            // ✅ PLAYER AVOIDANCE: must run in IDLE too.
            // IMPORTANT: if this triggers, it flips state to ROAM_FLY and sets noGravity=true etc.
            // We MUST return immediately or the rest of idle tick will overwrite those changes.
            try {
                PlayerAvoidance.tryTriggerPlayerAvoidance(this);

                if (getAIState() != RavenAIState.IDLE_GROUND) {
                    if (this.tickCount % 20 == 0) {
                        LOG.info("[RavenEntity] tickIdleGround: player avoidance switched AI state -> {} (yielding idle tick)", getAIState());
                    }
                    return;
                }
            } catch (Throwable t) {
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] PlayerAvoidance failed safely (idle): {}", t.toString());
                }
            }

            // Stay grounded / perched.
            this.setNoGravity(false);
            if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
                this.setAnimMode(RavenAnimMode.NO_AIR);
            }

            // Safety: do NOT allow flight targets/path to exist in idle.
            // If something reintroduced them (bug elsewhere), kill them and log.
            boolean hasUnexpectedIntent =
                    (flyTarget != null && flyTargetTimeoutTicks > 0)
                            || (pathGoal != null)
                            || (pathPendingGoal != null)
                            || (pathWaypoints != null && !pathWaypoints.isEmpty());

            if (hasUnexpectedIntent) {
                if (this.tickCount % 20 == 0) {
                    LOG.warn("[RavenEntity] IDLE: unexpected intent detected -> clearing. pos={} flyTarget={} goal={} pending={} pathPts={}",
                            this.position(),
                            flyTarget,
                            pathGoal,
                            pathPendingGoal,
                            (pathWaypoints == null ? 0 : pathWaypoints.size()));
                }
                clearFlyTarget();
                flyTargetTimeoutTicks = 0;
                clearPlannedPath("idle unexpected intent");
                pathGoal = null;
                pathPendingGoal = null;
                pathWaypoints = null;
                pathWaypointIndex = 0;
                pathRetryCooldownTicks = 0;
            }

            // Commit window (prevents "land then immediately take off" due to soft triggers).
            if (idleCommitTicks > 0) {
                idleCommitTicks--;
            }

            // Validate perch continuously, but only leave after sustained loss.
            // We use your 2x2 validity check, anchored by the current idlePerchCorner if possible.
            boolean perchValidNow = false;

            BlockPos corner = landing.idlePerchCorner;
            if (corner != null) {
                perchValidNow = landing.isValidPerchCornerAtTopY(corner, this);
            } else {
                // Fallback: probe under feet and locate a valid corner nearby.
                BlockPos feetBlock = BlockPos.containing(this.getX(), this.getBoundingBox().minY - 0.001D, this.getZ());
                BlockPos found = landing.findValidPerchCornerNearXZ(feetBlock.getX(), feetBlock.getZ(), this);
                if (found != null) {
                    landing.idlePerchCorner = found;
                    perchValidNow = landing.isValidPerchCornerAtTopY(found, this);
                } else {
                    perchValidNow = false;
                }
            }

            if (!perchValidNow) {
                idleLeafLossTicks++;

                if (this.tickCount % 20 == 0) {
                    LOG.info("[RavenEntity] IDLE: perch invalid sample {}/{} pos={} idlePerchCorner={} bbMinY={} onGround={} vColl={} commitTicks={} idleTicksRemaining={}",
                            idleLeafLossTicks,
                            IDLE_LEAF_LOSS_GRACE_TICKS,
                            this.position(),
                            landing.idlePerchCorner,
                            String.format("%.3f", this.getBoundingBox().minY),
                            this.onGround(),
                            this.verticalCollision,
                            idleCommitTicks,
                            idleTicksRemaining
                    );
                }

                // Only bail once it persists beyond your grace period.
                if (idleLeafLossTicks > IDLE_LEAF_LOSS_GRACE_TICKS) {
                    // Hard reason: perch truly lost.
                    String leaveReason = "idle perch invalid for " + idleLeafLossTicks + " ticks";
                    if (this.tickCount % 20 == 0) {
                        LOG.info("[RavenEntity] IDLE -> ROAM_FLY (reason={}) pos={} idlePerchCorner={}",
                                leaveReason, this.position(), landing.idlePerchCorner);
                    }

                    // Start a roam flight window (or your preferred takeoff logic)
                    setAIState(RavenAIState.ROAM_FLY);
                    idleLockTicks = 12;          // takeoff grace
                    roamTicksRemaining = 0;      // allow landing selection soon if desired
                    landing.resetLandingState("idle left: " + leaveReason, this);
                    clearPlannedPath("idle left: " + leaveReason);
                    clearFlyTarget();

                    beginRoamFlightWindow("idle left: perch invalid");
                    return;
                }
            } else {
                // Reset loss counter if we are valid again.
                if (idleLeafLossTicks > 0 && this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] IDLE: perch validity restored. pos={} idlePerchCorner={} lossTicksResetFrom={}",
                            this.position(), landing.idlePerchCorner, idleLeafLossTicks);
                }
                idleLeafLossTicks = 0;
            }

            // Natural idle countdown.
            if (idleTicksRemaining > 0) {
                idleTicksRemaining--;
            }

            // Gentle idle head-turning / looking around (your existing behavior).
            // Keep it extremely simple + stable.
            if (idleNextTurnTicks > 0) {
                idleNextTurnTicks--;
            } else {
                // Pick a new idle yaw target occasionally.
                idleTargetYaw = this.getYRot() + (rnd.nextFloat() - 0.5F) * 90.0F;
                idleNextTurnTicks = 15 + rnd.nextInt(60);
            }

            // Ease yaw toward target.
            float curYaw = this.getYRot();
            float newYaw = Mth.approachDegrees(curYaw, idleTargetYaw, 4.0F);
            this.setYRot(newYaw);
            this.setYHeadRot(newYaw);
            this.yBodyRot = newYaw;

            // If idle time expired, we leave — but NOT during commit window.
            if (idleTicksRemaining <= 0) {
                if (idleCommitTicks > 0) {
                    // Commit overrides "timer done" in the first few seconds after landing.
                    if (this.tickCount % 40 == 0) {
                        LOG.debug("[RavenEntity] IDLE: timer expired but commit active -> holding. commitTicks={} pos={}",
                                idleCommitTicks, this.position());
                    }
                } else {
                    String leaveReason = "idle timer expired";
                    if (this.tickCount % 20 == 0) {
                        LOG.info("[RavenEntity] IDLE -> ROAM_FLY (reason={}) pos={} idlePerchCorner={}",
                                leaveReason, this.position(), landing.idlePerchCorner);
                    }

                    setAIState(RavenAIState.ROAM_FLY);
                    idleLockTicks = 12;
                    roamTicksRemaining = 0;
                    landing.resetLandingState("idle left: " + leaveReason, this);
                    clearPlannedPath("idle left: " + leaveReason);
                    clearFlyTarget();

                    beginRoamFlightWindow("idle left: timer expired");
                    return;
                }
            }

            // Play random sound
            tickAmbientCawing();

            // Optional: super-light periodic idle heartbeat log (rare).
            if (this.tickCount % 200 == 0) {
                LOG.debug("[RavenEntity] IDLE tick: pos={} idlePerchCorner={} idleTicksRemaining={} commitTicks={} leafLossTicks={}",
                        this.position(), landing.idlePerchCorner, idleTicksRemaining, idleCommitTicks, idleLeafLossTicks);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] tickIdleGround failed", t);
        }
    }

    /**
     * ROAM_FLY (refactored to use coarse A* pathing):
     *  - During roam window: pick a random roam target and follow a waypoint path.
     *  - After roam window: attempt landing; the "fly to overhead" phase uses pathing too.
     *  - If pathing cannot find a path, we fall back to the previous direct-target behavior.
     */
    private void tickRoamFly() {
        try {
            debugAiHeartbeat("tickRoamFly");

            // --------------------------------------------------------------------
            // Tick down avoidance counters HERE so they actually change every tick.
            // (User reported only seeing these assigned inside requestPlayerAvoidanceFleeTarget.)
            // --------------------------------------------------------------------
            try {
                if (playerAvoidanceRearmCooldownTicks > 0) {
                    playerAvoidanceRearmCooldownTicks--;
                }
            } catch (Throwable ignored) {}

            try {
                if (playerAvoidanceOverrideTicks > 0) {
                    playerAvoidanceOverrideTicks--;
                }
            } catch (Throwable ignored) {}

            RandomSource rnd = this.getRandom();

            // 🔊 Ambient cawing while in ROAM_FLY.
            // This method internally checks AI state + cooldown,
            // so it's safe to call once per tick here.
            tickAmbientCawing();

            // ✅ Player avoidance tries to arm/refresh frequently.
            // This is safe even during override because requestPlayerAvoidanceFleeTarget internally throttles replans.
            try {
                PlayerAvoidance.tryTriggerPlayerAvoidance(this);
            } catch (Throwable t) {
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] PlayerAvoidance failed safely: {}", t.toString());
                }
            }

            // --------------------------------------------------------------------
            // ✅ HARD OVERRIDE: while playerAvoidanceOverrideTicks > 0 we do NOT allow
            // landing / perch / roam planners to run at all. We only:
            //  - keep flight physics
            //  - keep landing cancelled
            //  - fly toward current target/path
            //  - allow teleport blink checks (stuck logic)
            //
            // NEW: when we are "close enough" to the avoidance flyTarget, we treat that as
            //      ARRIVED -> clear override + start a rearm cooldown, so we don't bounce forever.
            // --------------------------------------------------------------------
            if (playerAvoidanceOverrideTicks > 0) {
                boolean stillOverriding = true;

                // Arrival check: if we're basically at the avoidance target, stop overriding.
                try {
                    if (flyTarget != null) {
                        Vec3 pos = this.position();
                        double d2 = pos.distanceToSqr(flyTarget);
                        final double ARRIVE_EPS = 0.60D; // ~0.6 blocks radius

                        if (d2 <= ARRIVE_EPS * ARRIVE_EPS) {
                            // We consider this "successfully avoided" the player.
                            clearFlyTarget();
                            flyTargetTimeoutTicks = 0;

                            // Drop override and arm a small rearm cooldown so we don't instantly re-trigger.
                            if (playerAvoidanceOverrideTicks > 0) {
                                playerAvoidanceOverrideTicks = 0;
                            }
                            if (playerAvoidanceRearmCooldownTicks < 40) { // ~2 seconds @ 20 TPS
                                playerAvoidanceRearmCooldownTicks = 40;
                            }

                            stillOverriding = false;

                            if (this.tickCount % 40 == 0) {
                                LOG.info("[RavenEntity] PlayerAvoidance: arrived near flee target -> clearing override. pos={} targetDist={}",
                                        pos, String.format("%.3f", Math.sqrt(d2)));
                            }
                        }
                    }
                } catch (Throwable t) {
                    if (this.tickCount % 40 == 0) {
                        LOG.warn("[RavenEntity] PlayerAvoidance arrival check failed safely: {}", t.toString());
                    }
                }

                // If arrival logic cleared the override, fall through into normal ROAM_FLY logic.
                if (stillOverriding && playerAvoidanceOverrideTicks > 0) {
                    // Absolutely cancel landing every tick to stop "re-perch" fights.
                    try {
                        landing.resetLandingState("player avoidance override tick", this);
                    } catch (Throwable ignored) {}

                    landingPhase = LandingPhase.NONE;
                    landing.landingLeafPos = null;
                    landingTicks = 0;

                    // Force flight posture
                    this.setNoGravity(true);
                    if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                        this.setAnimMode(RavenAnimMode.IN_AIR);
                    }

                    // Make sure we don't gate flight
                    roamTicksRemaining = 0;
                    idleLockTicks = 0;

                    // If we have a flyTarget, pursue it; otherwise follow waypoints; otherwise just hover (but do NOT plan roam/landing)
                    boolean didMove = false;

                    try {
                        if (flyTarget != null && flyTargetTimeoutTicks > 0) {
                            flyTargetTimeoutTicks--;
                            // IMPORTANT: do NOT call maybeAvoidOrRetargetDuringFlight() here.
                            // That method is allowed to pick landing/perch targets and will fight avoidance.
                            flyTowardTarget(FLY_SPEED_BASE);
                            didMove = true;
                        }
                    } catch (Throwable t) {
                        if (this.tickCount % 40 == 0) {
                            LOG.warn("[RavenEntity] playerAvoidance override: flyTowardTarget failed safely: {}", t.toString());
                        }
                    }

                    try {
                        if (!didMove && pathWaypoints != null && !pathWaypoints.isEmpty() && pathWaypointIndex < pathWaypoints.size()) {
                            // Keep advancing waypoints while avoiding any "normal roam" replanning.
                            advanceWaypointIfNeeded(4 * 20, "player avoidance override");
                            didMove = true;
                        }
                    } catch (Throwable t) {
                        if (this.tickCount % 40 == 0) {
                            LOG.warn("[RavenEntity] playerAvoidance override: advanceWaypointIfNeeded failed safely: {}", t.toString());
                        }
                    }

                    // Allow your random blink/stuck teleport logic while overriding.
                    try {
                        teleportation.tickRandomFlightTeleportBlink(this);
                    } catch (Throwable t) {
                        if (this.tickCount % 40 == 0) {
                            LOG.warn("[RavenEntity] tickRandomFlightTeleportBlink failed safely (avoidanceOverride): {}", t.toString());
                        }
                    }

                    if (this.tickCount % 40 == 0) {
                        LOG.info("[RavenEntity] PlayerAvoidance override active: ticksLeft={} pos={} vel={} flyTarget={} flyTtl={} pathGoal={} pts={} idx={}",
                                playerAvoidanceOverrideTicks,
                                this.position(),
                                this.getDeltaMovement(),
                                flyTarget,
                                flyTargetTimeoutTicks,
                                pathGoal,
                                (pathWaypoints == null ? 0 : pathWaypoints.size()),
                                pathWaypointIndex);
                    }

                    return;
                }
            }

            // NOTE:
            // "Random blink" should only fire while we're actively flying around (not during the landing state machine).
            // Therefore we will call tickRandomFlightTeleportBlink() ONLY in the flight branches:
            //  - takeoff lock branch
            //  - roam window branch
            //  - "no leaf found -> roam extra ticks" branch
            //
            // We intentionally do NOT call it while landingPhase != NONE, and we do NOT call it right after we
            // commit to a landing leaf (FLY_TO_OVERHEAD init), otherwise it will look like it "changes its mind".

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

                if (flyTarget != null) {
                    if (flyTargetTimeoutTicks > 0) {
                        flyTargetTimeoutTicks--;
                    }
                    maybeAvoidOrRetargetDuringFlight(rnd);
                    flyTowardTarget(FLY_SPEED_BASE);
                } else if (pathWaypoints != null && !pathWaypoints.isEmpty()) {
                    advanceWaypointIfNeeded(4 * 20, "takeoff lock advance");
                }

                // ✅ RANDOM BLINK: allowed during takeoff lock roaming (still flight mode).
                // Put it at the END so it can freeze velocity / noPhysics without our own code immediately overriding it.
                try {
                    teleportation.tickRandomFlightTeleportBlink(this);
                } catch (Throwable t) {
                    if (this.tickCount % 40 == 0) {
                        LOG.warn("[RavenEntity] tickRandomFlightTeleportBlink failed safely (takeoffLock): {}", t.toString());
                    }
                }

                if (this.tickCount % 120 == 0) {
                    LOG.debug("[RavenEntity] ROAM_FLY takeoff lock active (remaining={}) pos={} vel={} pathPts={} pathIdx={}",
                            idleLockTicks, this.position(), this.getDeltaMovement(),
                            (pathWaypoints == null ? 0 : pathWaypoints.size()), pathWaypointIndex);
                }
                return;
            }

            // FIX #2:
            // Do NOT instantly re-enter idle just because we are hovering above a perch center.
            // Only allow this shortcut when we are in "non-flying" physics (noGravity=false),
            // which is true during DROP / actual landing / resting.
            if (!this.isNoGravity() && landing.isOnValidPerchNow(this)) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] ROAM_FLY -> IDLE shortcut (not flying): pos={} vel={} landingPhase={} leafPos={}",
                            this.position(), this.getDeltaMovement(), landingPhase, landing.landingLeafPos);
                }
                landing.enterIdleFromLanding("ROAM_FLY: centered on valid 2x2 perch (not flying)", this);
                return;
            }

            if (landingPhase != LandingPhase.NONE) {
                landingTicks++;
                if (landingTicks > landing.LANDING_MAX_TOTAL_TICKS) {
                    if (this.tickCount % 80 == 0) {
                        LOG.debug("[RavenEntity] Landing timed out (phase={}, ticks={}) -> resetting landing and restarting landing (no roam interrupt)",
                                landingPhase, landingTicks);
                    }
                    landing.resetLandingState("landing timeout", this);
                    clearPlannedPath("landing timeout");
                }
            } else {
                landingTicks = 0;
            }

            if (landing.landingLeafPos != null && !landing.isStillValidLandingLeaf(landing.landingLeafPos, this)) {
                if (this.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] Landing leaf became invalid -> resetting landing leaf at {}", landing.landingLeafPos);
                }
                landing.resetLandingState("invalid leaf", this);
                clearPlannedPath("invalid landing leaf");
            }

            if (landingPhase == LandingPhase.NONE && roamTicksRemaining > 0) {
                roamTicksRemaining--;

                this.setNoGravity(true);
                if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                    this.setAnimMode(RavenAnimMode.IN_AIR);
                }

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

                if (flyTarget != null) {
                    maybeAvoidOrRetargetDuringFlight(rnd);
                    if (flyTargetTimeoutTicks > 0) {
                        flyTargetTimeoutTicks--;
                    }
                    flyTowardTarget(FLY_SPEED_BASE);
                }

                if (pathWaypoints != null && !pathWaypoints.isEmpty()) {
                    advanceWaypointIfNeeded(4 * 20, "roam window");
                }

                // ✅ RANDOM BLINK: allowed during the roam window (this is your "flight phase").
                try {
                    teleportation.tickRandomFlightTeleportBlink(this);
                } catch (Throwable t) {
                    if (this.tickCount % 40 == 0) {
                        LOG.warn("[RavenEntity] tickRandomFlightTeleportBlink failed safely (roamWindow): {}", t.toString());
                    }
                }

                return;
            }

            if (landingPhase != LandingPhase.NONE) {
                // ❌ No random blink here: landing state machine owns movement & phases.
                landing.tickLandingStateMachine(rnd, this);
                return;
            }

            BlockPos leaf = landing.pickLandingLeafBlock(rnd, this);
            if (leaf == null) {
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

                // ✅ RANDOM BLINK: allowed here too, because we're explicitly continuing flight (no leaf found).
                // This branch is still part of "roam flight phase".
                try {
                    teleportation.tickRandomFlightTeleportBlink(this);
                } catch (Throwable t) {
                    if (this.tickCount % 40 == 0) {
                        LOG.warn("[RavenEntity] tickRandomFlightTeleportBlink failed safely (noLeafRoam): {}", t.toString());
                    }
                }

                if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                    LOG.debug("[RavenEntity] Landing allowed but no valid leaf found -> roaming {} ticks then retry", extra);
                }
                return;
            }

            // From HERE on, we are committing to a landing sequence.
            // ❌ Do NOT random blink after this point.
            landing.landingLeafPos = leaf;
            landingPhase = LandingPhase.FLY_TO_OVERHEAD;
            landingTicks = 0;

            Vec3 overhead = landing.overheadTargetForLeaf(leaf, this);

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            clearPlannedPath("starting landing -> plan overhead");
            long seed = this.getUUID().getMostSignificantBits() ^ (long) this.tickCount ^ leaf.asLong();
            boolean ok = ensurePathTo(overhead, 6 * 20, seed, "landing FLY_TO_OVERHEAD init");
            if (!ok) {
                setFlyTarget(overhead, 6 * 20);
            }

            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                LOG.debug("[RavenEntity] Landing start (post-roam): leaf={} overhead={} (airAbove={}, canopyNeighbors>={}) pathOk={}",
                        leaf, overhead, landing.LAND_REQUIRED_AIR_ABOVE, landing.CANOPY_NEIGHBOR_LEAVES_REQUIRED, ok);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] tickRoamFly failed", t);
        }
    }

    public void beginRoamFlightWindow(String reason) {
        try {
            RandomSource rnd = this.getRandom();

            int span = Math.max(1, ROAM_MAX_TICKS - ROAM_MIN_TICKS + 1);
            int ticks = ROAM_MIN_TICKS + rnd.nextInt(span);
            roamTicksRemaining = Math.max(1, ticks);

            landing.resetLandingState("beginRoamFlightWindow: " + reason, this);

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

    private void flyTowardTarget(double speed) {
        try {
            // Prefer following the current A* waypoint if we have a path.
            // This is critical in corridors / under ceilings so we actually
            // follow the bend of the path instead of aiming at the final goal.
            Vec3 actualTarget = null;

            if (pathWaypoints != null && !pathWaypoints.isEmpty()
                    && pathWaypointIndex >= 0
                    && pathWaypointIndex < pathWaypoints.size()) {
                actualTarget = pathWaypoints.get(pathWaypointIndex);
            } else {
                actualTarget = flyTarget;
            }

            if (actualTarget == null) {
                return;
            }

            Vec3 pos = this.position();
            Vec3 to = actualTarget.subtract(pos);
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

            double vx = desiredVel.x;
            double vy = desiredVel.y;
            double vz = desiredVel.z;

            // On collision, damp components instead of hard-stopping.
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
                            collH, collV, pos, actualTarget, newVel);
                }
            }

            this.setDeltaMovement(newVel);

            float yaw = (float) (Math.atan2(newVel.z, newVel.x) * (180.0D / Math.PI)) - 90.0F;
            this.setYRot(yaw);
            this.setYHeadRot(yaw);
            this.yBodyRot = yaw;

            if (this.tickCount % 40 == 0) {
                LOG.debug(
                        "[RavenEntity] flyTowardTarget: heading toward waypointIdx={} of {} target={} dist={}",
                        (pathWaypoints == null ? -1 : pathWaypointIndex),
                        (pathWaypoints == null ? 0 : pathWaypoints.size()),
                        actualTarget,
                        String.format("%.3f", dist)
                );
            }

        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                LOG.warn("[RavenEntity] flyTowardTarget failed: {}", t.toString());
            }
        }
    }

    private boolean isPathExhausted() {
        try {
            return (pathWaypoints == null)
                    || pathWaypoints.isEmpty()
                    || (pathWaypointIndex < 0)
                    || (pathWaypointIndex >= pathWaypoints.size());
        } catch (Throwable t) {
            // Fail-safe: if we can’t reason about it, treat as exhausted so we can replan.
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isPathExhausted failed: {}", t.toString());
            }
            return true;
        }
    }

    public BlockPos getHomePosPublic() {
        try {
            return this.homePos;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getHomePosPublic failed safely: {}", t.toString());
            }
            return this.blockPosition();
        }
    }

    public int getHomeRadiusBlocksPublic() {
        try {
            return HOME_RADIUS_BLOCKS;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getHomeRadiusBlocksPublic failed safely: {}", t.toString());
            }
            return 50;
        }
    }

    @org.jetbrains.annotations.Nullable
    public Vec3 getHomeCenterVec() {
        return new Vec3(
                homePos.getX() + 0.5D,
                homePos.getY() + 0.5D,
                homePos.getZ() + 0.5D
        );
    }

    public void forceRoamFlightFromThreat(Vec3 fleeTarget, net.minecraft.world.entity.player.Player threat) {
        try {
            if (fleeTarget == null || threat == null) {
                return;
            }

            final Vec3 ravenPos = this.position();
            Vec3 playerPos;
            try {
                playerPos = threat.position();
            } catch (Throwable t) {
                playerPos = fleeTarget; // ultra-defensive fallback
            }

            // Cancel everything calm-related
            clearPlannedPath("player avoidance");
            clearFlyTarget();

            // Force roaming flight
            setAIState(RavenAIState.ROAM_FLY);
            roamTicksRemaining = 0;

            // Kill any landing / idle state right now.
            landing.resetLandingState("player avoidance", this);
            idleCommitTicks = 0;
            idleLockTicks = 0;

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            long baseSeed = this.getUUID().getLeastSignificantBits()
                    ^ (long) this.tickCount
                    ^ threat.getUUID().getMostSignificantBits()
                    ^ 0xF1EEBEEFL;

            boolean okPrimary = false;
            boolean okFallback = false;
            Vec3 usedGoal = fleeTarget;

            // ------------------------------------------------------------------
            // 1) Primary attempt: A* directly to the computed flee target
            // ------------------------------------------------------------------
            try {
                okPrimary = ensurePathTo(fleeTarget, 6 * 20, baseSeed, "player avoidance flee");
            } catch (Throwable t) {
                okPrimary = false;
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] forceRoamFlightFromThreat: primary A* threw (ignored): {}", t.toString());
                }
            }

            // ------------------------------------------------------------------
            // 2) Home-centered fallback: still away from player, but biased to a
            //    location near the raven's home that is more likely to be pathable.
            // ------------------------------------------------------------------
            if (!okPrimary) {
                Vec3 homeCenter = null;
                try {
                    homeCenter = getHomeCenterVec();
                } catch (Throwable ignored) {
                }

                if (homeCenter != null) {
                    Vec3 away = homeCenter.subtract(playerPos);
                    if (away.lengthSqr() < 1.0E-4D) {
                        away = ravenPos.subtract(playerPos);
                    }
                    if (away.lengthSqr() < 1.0E-4D) {
                        away = new Vec3(1.0D, 0.0D, 0.0D);
                    }
                    away = away.normalize();

                    double dist = 16.0D;
                    Vec3 fallbackGoal = homeCenter.add(away.scale(dist));
                    fallbackGoal = clampTargetToHomeBounds(fallbackGoal);

                    long fallbackSeed = baseSeed ^ 0xA5A5A5A5L;

                    try {
                        okFallback = ensurePathTo(fallbackGoal, 6 * 20, fallbackSeed,
                                "player avoidance flee (fallback)");
                    } catch (Throwable t) {
                        okFallback = false;
                        if (this.tickCount % 40 == 0) {
                            LOG.warn("[RavenEntity] forceRoamFlightFromThreat: fallback A* threw (ignored): {}", t.toString());
                        }
                    }

                    if (okFallback) {
                        usedGoal = fallbackGoal;

                        if (this.tickCount % 20 == 0) {
                            LOG.info(
                                    "[RavenEntity] forceRoamFlightFromThreat: primary A* failed; using fallback path. fromPos={} playerPos={} primaryGoal={} fallbackGoal={}",
                                    ravenPos,
                                    playerPos,
                                    fleeTarget,
                                    fallbackGoal
                            );
                        }
                    } else if (this.tickCount % 40 == 0) {
                        LOG.info(
                                "[RavenEntity] forceRoamFlightFromThreat: primary+fallback A* both failed. primaryGoal={} fallbackGoal={}",
                                fleeTarget,
                                fallbackGoal
                        );
                    }
                } else if (this.tickCount % 40 == 0) {
                    LOG.info(
                            "[RavenEntity] forceRoamFlightFromThreat: no homeCenter; primary A* failed. goal={}",
                            fleeTarget
                    );
                }
            }

            boolean ok = okPrimary || okFallback;

            // ------------------------------------------------------------------
            // NO ballistic fallback here. If A* cannot find anything, we simply
            // leave the raven in ROAM_FLY with no new intent. Panic teleport /
            // other safety nets can still handle extreme cases.
            // ------------------------------------------------------------------
            if (ok) {
                if (this.tickCount % 20 == 0) {
                    LOG.info(
                            "[RavenEntity] forceRoamFlightFromThreat: path armed to goal={} fromPos={} playerPos={}",
                            usedGoal,
                            ravenPos,
                            playerPos
                    );
                }
            } else if (this.tickCount % 40 == 0) {
                LOG.warn(
                        "[RavenEntity] forceRoamFlightFromThreat: no avoidance path found; leaving raven without new path. fromPos={} playerPos={} attemptedGoal={}",
                        ravenPos,
                        playerPos,
                        fleeTarget
                );
            }

            // Prevent immediate re-trigger of calm behaviors (landing etc.)
            idleCommitTicks = 40;

        } catch (Throwable t) {
            LOG.error("[RavenEntity] forceRoamFlightFromThreat failed", t);
        }
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        try {
            // Let vanilla short-circuits happen first if we are truly invulnerable to this.
            // (Note: even if invulnerable, we still might want to teleport; requirement says ALWAYS teleport on hit,
            // but if hurt() is never called, we can't. Here, hurt() *is* called, so we can do it.)
            if (this.isInvulnerableTo(source)) {
                // Still attempt the damage blink (server-side).
                try {
                    if (!this.level().isClientSide) {
                        // force post-teleport roam even if we don't take damage
                        teleportation.requestDamageBlinkTeleport(source, amount, "invulnerable-hurt", this);
                    }
                } catch (Throwable ignored) {
                }
                return false;
            }

            // Our combat blink logic (server authoritative).
            boolean dodge = false;
            try {
                dodge = RavenDamageDodgeHandler.handleHurt(this, source, amount);
            } catch (Throwable t) {
                if (this.tickCount % 80 == 0) {
                    LOG.warn("[RavenEntity] hurt: RavenDamageDodgeHandler failed safely: {}", t.toString());
                }
                dodge = false;
            }

            if (dodge) {
                // Dodged: no damage applied.
                if (this.tickCount % 20 == 0) {
                    LOG.info("[RavenEntity] hurt: DODGED damage. amount={} src={} pos={}", amount, (source == null ? "null" : source.toString()), this.position());
                }
                return false;
            }

            // Not dodged: apply damage as normal.
            boolean result = super.hurt(source, amount);

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] hurt: took damage. applied={} amount={} src={} hpNow={} pos={}",
                        result, amount, (source == null ? "null" : source.toString()), this.getHealth(), this.position());
            }

            return result;

        } catch (Throwable t) {
            // Fail-safe: never crash in hurt. Apply vanilla behavior if we can.
            if (this.tickCount % 40 == 0) {
                LOG.error("[RavenEntity] hurt failed; falling back to super.hurt. src={} amt={}",
                        (source == null ? "null" : source.toString()), amount, t);
            }
            try {
                return super.hurt(source, amount);
            } catch (Throwable t2) {
                // Absolute fail-safe: do not crash server.
                if (this.tickCount % 40 == 0) {
                    LOG.error("[RavenEntity] super.hurt also failed; suppressing to prevent crash. {}", t2.toString());
                }
                return false;
            }
        }
    }

    /**
     * Returns the "effective" AI state for debug/logging purposes.
     * While player avoidance override is active, we expose AVOID_PLAYER
     * so heartbeat/debug logs clearly show that mode without changing
     * the underlying FSM wiring.
     */
    private RavenAIState getAIStateForDebug() {
        try {
            if (playerAvoidanceOverrideTicks > 0) {
                return RavenAIState.AVOID_PLAYER;
            }
            return getAIState();
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] getAIStateForDebug failed safely: {}", t.toString());
            }
            // Fall back to the raw state to avoid NPEs in logs.
            return getAIState();
        }
    }

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
    // Debug
    // -----------------

    /**
     * Simple RGB lerp between two 0xRRGGBB colors.
     */
    private static int lerpRgb(int c1, int c2, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);

        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;

        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;

        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);

        return (r << 16) | (g << 8) | b;
    }

    /**
     * Debug helper: clear any previously spawned ArmorStand markers used for path visualization.
     * Also tries to clean up older leaked markers near this raven, even if their IDs were lost.
     */
    private void debugClearOldPathMarkers(ServerLevel serverLevel) {
        try {
            // 1) Clean up by recorded IDs (normal lifecycle)
            if (debugPathMarkerIds != null && !debugPathMarkerIds.isEmpty()) {
                for (Integer id : debugPathMarkerIds) {
                    if (id == null) {
                        continue;
                    }
                    try {
                        Entity e = serverLevel.getEntity(id);
                        if (e instanceof ArmorStand stand) {
                            stand.discard();
                        }
                    } catch (Throwable ignored) {
                        // Best-effort cleanup only.
                    }
                }
                debugPathMarkerIds.clear();
            }

            // 2) Extra safety: clean any *nearby* debug-looking armor stands,
            //    including ones from old versions where IDs/tags were lost.
            final double r = DEBUG_PATH_GIZMO_CLEAN_RADIUS;
            AABB box = this.getBoundingBox().inflate(r);

            List<ArmorStand> candidates = serverLevel.getEntitiesOfClass(
                    ArmorStand.class,
                    box,
                    stand -> {
                        // Tagged by the current version?
                        if (stand.getTags().contains(DEBUG_PATH_GIZMO_TAG)) {
                            return true;
                        }

                        // Heuristic for old leaked markers:
                        // - Invisible, invulnerable, no gravity
                        // - Custom name is purely digits (e.g. "1", "2", ...) or "Goal"
                        if (!stand.isInvisible()) return false;
                        if (!stand.isInvulnerable()) return false;
                        if (!stand.isNoGravity()) return false;

                        Component nameComp = stand.getCustomName();
                        if (nameComp == null) return false;

                        String name = nameComp.getString();
                        if (name == null || name.isEmpty()) return false;

                        if ("Goal".equalsIgnoreCase(name) || "End".equalsIgnoreCase(name)) {
                            return true;
                        }

                        boolean allDigits = true;
                        for (int i = 0; i < name.length(); i++) {
                            if (!Character.isDigit(name.charAt(i))) {
                                allDigits = false;
                                break;
                            }
                        }
                        return allDigits;
                    }
            );

            for (ArmorStand stand : candidates) {
                try {
                    stand.discard();
                } catch (Throwable ignored) {
                }
            }

        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] debugClearOldPathMarkers failed safely: {}", t.toString());
            }
        }
    }

    /**
     * Debug helper: spawn a small gizmo ArmorStand with a colored text label at the given position.
     *
     * - Uses private ArmorStand marker/small/baseplate flags via reflection so it has no hitbox
     *   and doesn't block building.
     * - Invisible + no gravity + invulnerable.
     * - Label text is colored to match.
     * - No particles, no big SFX — just a tiny visual marker.
     */
    private void debugSpawnWaypointMarker(ServerLevel level, Vec3 pos, String label, int rgb) {
        try {
            if (level == null || pos == null) {
                return;
            }

            ArmorStand stand = EntityType.ARMOR_STAND.create(level);
            if (stand == null) {
                return;
            }

            // Try to configure as a "pure marker" stand via reflection so we avoid hitboxes
            // even though setMarker/setSmall/setNoBasePlate are not publicly accessible.
            try {
                // setMarker(boolean)
                try {
                    java.lang.reflect.Method mSetMarker =
                            ArmorStand.class.getDeclaredMethod("setMarker", boolean.class);
                    mSetMarker.setAccessible(true);
                    mSetMarker.invoke(stand, true);
                } catch (Throwable ignored) {
                    // Optional; if it fails we still keep the stand, just not marker-mode.
                }

                // setSmall(boolean)
                try {
                    java.lang.reflect.Method mSetSmall =
                            ArmorStand.class.getDeclaredMethod("setSmall", boolean.class);
                    mSetSmall.setAccessible(true);
                    mSetSmall.invoke(stand, true);
                } catch (Throwable ignored) {
                }

                // setNoBasePlate(boolean)
                try {
                    java.lang.reflect.Method mSetNoBasePlate =
                            ArmorStand.class.getDeclaredMethod("setNoBasePlate", boolean.class);
                    mSetNoBasePlate.setAccessible(true);
                    mSetNoBasePlate.invoke(stand, true);
                } catch (Throwable ignored) {
                }

            } catch (Throwable tCfg) {
                if (this.tickCount % 200 == 0) {
                    LOG.warn("[RavenEntity] debugSpawnWaypointMarker: marker-flag reflection failed safely: {}", tCfg.toString());
                }
            }

            // Generic debug-stand flags
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.setInvulnerable(true);
            stand.setCustomNameVisible(true);

            // Tag it so our cleanup can always find it again.
            stand.addTag(DEBUG_PATH_GIZMO_TAG);

            // Position just above block center.
            stand.moveTo(pos.x, pos.y + 0.20D, pos.z, 0.0F, 0.0F);

            // Colored label
            stand.setCustomName(
                    Component.literal(label)
                            .withStyle(style -> style.withColor(TextColor.fromRgb(rgb)))
            );

            level.addFreshEntity(stand);

            if (debugPathMarkerIds != null) {
                debugPathMarkerIds.add(stand.getId());
            }

        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] debugSpawnWaypointMarker failed safely: {}", t.toString());
            }
        }
    }

    /**
     * Spawn a few small "dot" markers along the line from a -> b.
     */
    private void debugSpawnLineGizmos(ServerLevel level, Vec3 a, Vec3 b, int steps, int rgb) {
        try {
            if (level == null || a == null || b == null || steps <= 1) {
                return;
            }

            for (int i = 1; i < steps; i++) { // skip exact endpoints; they already have markers
                double t = (double) i / (double) steps;
                double x = Mth.lerp(t, a.x, b.x);
                double y = Mth.lerp(t, a.y, b.y);
                double z = Mth.lerp(t, a.z, b.z);
                Vec3 p = new Vec3(x, y, z);

                // Use a small dot character so it reads like a line.
                debugSpawnWaypointMarker(level, p, "·", rgb);
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] debugSpawnLineGizmos failed safely: {}", t.toString());
            }
        }
    }

    /**
     * Spawn a small ring of markers (circle gizmo) around a center point.
     */
    private void debugSpawnCircleGizmos(ServerLevel level, Vec3 center, double radius, int points, int rgb) {
        try {
            if (level == null || center == null || radius <= 0.0D || points <= 0) {
                return;
            }

            for (int i = 0; i < points; i++) {
                double angle = (2.0D * Math.PI * i) / (double) points;
                double x = center.x + Math.cos(angle) * radius;
                double z = center.z + Math.sin(angle) * radius;
                Vec3 p = new Vec3(x, center.y, z);

                // Use an empty label or a small circle symbol.
                debugSpawnWaypointMarker(level, p, "◦", rgb);
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] debugSpawnCircleGizmos failed safely: {}", t.toString());
            }
        }
    }

    /**
     * Debug helper: visualize the current path with simple gizmos:
     *
     * - Blue numbered markers "1", "2", "3", ... on current and next waypoints
     *   using a dark-blue -> light-blue gradient.
     * - Between each visible pair of waypoints, a few small dot markers to suggest a line.
     * - Green "Goal" marker + small ring around the final pathGoal.
     *
     * SERVER-SIDE ONLY, fully gated by DEBUG_DRAW_PATH_GIZMOS.
     */
    private void debugDrawPathGizmos(String callerTag) {
        try {
            if (!DEBUG_DRAW_PATH_GIZMOS) {
                return;
            }
            if (!(this.level() instanceof ServerLevel serverLevel)) {
                return;
            }

            // Throttle to reduce spam.
            if ((this.tickCount % DEBUG_PATH_GIZMO_TICK_INTERVAL) != 0) {
                return;
            }

            // Clear previous batch of markers so we don't leak entities.
            debugClearOldPathMarkers(serverLevel);

            Vec3 pos = this.position();
            Vec3 localFlyTarget = this.flyTarget;
            Vec3 localPathGoal = this.pathGoal;
            Vec3 localPendingGoal = this.pathPendingGoal;
            int idx = this.pathWaypointIndex;
            int total = (this.pathWaypoints == null ? 0 : this.pathWaypoints.size());

            // -------------------------------------
            // Visualize current + nearby waypoints
            // -------------------------------------
            if (this.pathWaypoints != null && !this.pathWaypoints.isEmpty() && idx >= 0 && idx < total) {
                int first = idx;
                int last = Math.min(total - 1, idx + (DEBUG_PATH_GIZMO_MAX_WAYPOINTS - 1));

                // Gradient: dark blue -> light blue across the visible slice.
                final int START_BLUE = 0x003366;
                final int END_BLUE   = 0x66CCFF;

                int labelNumber = 1;
                int visibleCount = (last - first + 1);

                Vec3 prevCenter = null;
                int prevColor = START_BLUE;

                for (int i = first; i <= last; i++) {
                    Vec3 wp = this.pathWaypoints.get(i);
                    if (wp == null) continue;

                    Vec3 center = new Vec3(
                            Math.floor(wp.x) + 0.5D,
                            wp.y,
                            Math.floor(wp.z) + 0.5D
                    );

                    // 0..1 across the *visible* waypoints.
                    float t;
                    if (visibleCount <= 1) {
                        t = 0.0F;
                    } else {
                        t = (float) (i - first) / (float) (visibleCount - 1);
                    }
                    int rgb = lerpRgb(START_BLUE, END_BLUE, t);

                    // Numbered marker at waypoint.
                    String label = Integer.toString(labelNumber++);
                    debugSpawnWaypointMarker(serverLevel, center, label, rgb);

                    // Draw a few dots between this waypoint and previous one to suggest a line.
                    if (prevCenter != null) {
                        // Use a small number of steps so we don't spawn too many entities.
                        int steps = 4;
                        debugSpawnLineGizmos(serverLevel, prevCenter, center, steps, rgb);
                    }

                    prevCenter = center;
                    prevColor = rgb;
                }
            }

            // -------------------------------------
            // Visualize pathGoal (final target) in GREEN
            // + a small ring around it.
            // -------------------------------------
            if (localPathGoal != null) {
                Vec3 g = new Vec3(
                        Math.floor(localPathGoal.x) + 0.5D,
                        localPathGoal.y,
                        Math.floor(localPathGoal.z) + 0.5D
                );

                int greenRgb = 0x00FF3C;

                // Central "Goal" marker.
                debugSpawnWaypointMarker(serverLevel, g, "Goal", greenRgb);

                // Small circle gizmo around the goal (more visual than just text).
                debugSpawnCircleGizmos(serverLevel, g, 0.75D, 8, greenRgb);
            }

            // -------------------------------------
            // Optional periodic log sample of the path
            // -------------------------------------
            if (this.tickCount % 40 == 0 && this.pathWaypoints != null && !this.pathWaypoints.isEmpty()) {
                Vec3 curWp = (idx >= 0 && idx < total) ? this.pathWaypoints.get(idx) : null;
                Vec3 nextWp = (idx + 1 >= 0 && idx + 1 < total) ? this.pathWaypoints.get(idx + 1) : null;
                Vec3 lastWp = this.pathWaypoints.get(total - 1);

                LOG.info(
                        "[RavenEntity] DEBUG path gizmos ({}): pos={} flyTarget={} pathGoal={} pendingGoal={} wpIdx={}/{} curWp={} nextWp={} lastWp={}",
                        callerTag,
                        pos,
                        localFlyTarget,
                        localPathGoal,
                        localPendingGoal,
                        idx,
                        total,
                        curWp,
                        nextWp,
                        lastWp
                );
            }

        } catch (Throwable t) {
            // Debug should NEVER crash the raven; just log + continue.
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] debugDrawPathGizmos failed safely: {}", t.toString());
            }
        }
    }

    // --------------------
    // Sound Engine
    // --------------------

    private static SoundEvent resolveRavenSound(ResourceLocation id) {
        try {
            if (id == null) return null;
            // BuiltInRegistries.SOUND_EVENT is populated from sounds.json; this gives us the SoundEvent by ID.
            return BuiltInRegistries.SOUND_EVENT.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SoundEvent getRavenCawingNormalSound() {
        if (cachedRavenCawingNormal == null) {
            cachedRavenCawingNormal = resolveRavenSound(SOUND_RAVEN_CAWING_NORMAL_ID);
        }
        return cachedRavenCawingNormal;
    }

    private static SoundEvent getRavenCawAgreeSoundInternal() {
        if (cachedRavenCawAgree == null) {
            cachedRavenCawAgree = resolveRavenSound(SOUND_RAVEN_CAW_AGREE_ID);
        }
        return cachedRavenCawAgree;
    }

    private static SoundEvent getRavenCawDmgSoundInternal() {
        if (cachedRavenCawDmg == null) {
            cachedRavenCawDmg = resolveRavenSound(SOUND_RAVEN_CAW_DMG_ID);
        }
        return cachedRavenCawDmg;
    }

    private static SoundEvent getRavenCawWhistleSoundInternal() {
        if (cachedRavenCawWhistle == null) {
            cachedRavenCawWhistle = resolveRavenSound(SOUND_RAVEN_CAW_WHISTLE_ID);
        }
        return cachedRavenCawWhistle;
    }

    private static SoundEvent getRavenAirWooshSoundInternal() {
        if (cachedRavenAirWoosh == null) {
            cachedRavenAirWoosh = resolveRavenSound(SOUND_RAVEN_AIR_WOOSH_ID);
        }
        return cachedRavenAirWoosh;
    }

    // ----------------------------------------------------------------------
    // Public static accessors for modules (LureFollowTame, damage logic, etc)
    // ----------------------------------------------------------------------

    public static SoundEvent getRavenCawingNormalSoundStatic() {
        return getRavenCawingNormalSound();
    }

    public static SoundEvent getRavenCawAgreeSoundStatic() {
        return getRavenCawAgreeSoundInternal();
    }

    public static SoundEvent getRavenCawDmgSoundStatic() {
        return getRavenCawDmgSoundInternal();
    }

    public static SoundEvent getRavenCawWhistleSoundStatic() {
        return getRavenCawWhistleSoundInternal();
    }

    public static SoundEvent getRavenAirWooshSoundStatic() {
        return getRavenAirWooshSoundInternal();
    }

    private void tickAmbientCawing() {
        try {
            if (this.level().isClientSide) {
                // Server-side only; client will hear broadcast/local playback.
                return;
            }
            if (!this.isAlive()) {
                return;
            }

            RavenAIState st = getAIState();
            boolean inRoamFly = (st == RavenAIState.ROAM_FLY);
            boolean inIdlePerch = (st == RavenAIState.IDLE_GROUND);

            // Only caw in these two calm-ish modes.
            if (!inRoamFly && !inIdlePerch) {
                // When we leave these modes, clamp cooldown so the next entry doesn't instantly spam.
                if (ambientCawCooldownTicks > 200) { // cap at 10s
                    ambientCawCooldownTicks = 200;
                }
                return;
            }

            if (ambientCawCooldownTicks > 0) {
                ambientCawCooldownTicks--;
                return;
            }

            // Cooldown expired: play an ambient caw and schedule the next one.
            // Target: ~1–3 times per minute per raven.
            //
            //  - Min delay: 400 ticks  = 20s
            //  - Max delay: 1200 ticks = 60s
            // We roll uniformly in [400, 1200], so average ~40s between caws ~ 1.5/minute.
            RandomSource rnd = this.getRandom();
            int minDelay = 400;   // 20 seconds
            int extra = 800;      // +0..40 seconds
            int rolled = minDelay;
            try {
                if (rnd != null) {
                    rolled = minDelay + rnd.nextInt(extra + 1);
                }
            } catch (Throwable ignored) {
                // Fallback: something reasonable
                rolled = 600; // 30s
            }
            ambientCawCooldownTicks = rolled;

            SoundEvent caw = getRavenCawingNormalSound();
            if (caw == null) {
                if (this.tickCount % 200 == 0) {
                    LOG.warn("[RavenEntity] tickAmbientCawing: raven.cawing_normal SoundEvent not resolved (id={})",
                            SOUND_RAVEN_CAWING_NORMAL_ID);
                }
                return;
            }

            // Slight pitch variation so it doesn't sound like an exact loop.
            float volume = 0.9F;
            float pitchMin = 0.95F;
            float pitchMax = 1.05F;

            if (soundEngine != null) {
                soundEngine.playWithRandomPitch(caw, SoundSource.NEUTRAL, volume, pitchMin, pitchMax);
            } else if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] tickAmbientCawing: soundEngine is null; cannot play ambient caw");
            }

            if (this.tickCount % 200 == 0) {
                LOG.debug("[RavenEntity] tickAmbientCawing: played ambient caw st={} nextDelay={}t pos={}",
                        st, ambientCawCooldownTicks, this.position());
            }

        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] tickAmbientCawing failed safely: {}", t.toString());
            }
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

    // ---------------------------------------------------------------------
    // Variant helpers (NORMAL / SCROLL)
    // ---------------------------------------------------------------------

    public RavenVariant getVariant() {
        try {
            int raw = this.entityData.get(DATA_VARIANT);
            return RavenVariant.fromId(raw);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getVariant failed safely: {}", t.toString());
            }
            return RavenVariant.NORMAL;
        }
    }

    public void setVariant(RavenVariant variant) {
        try {
            if (variant == null) {
                variant = RavenVariant.NORMAL;
            }
            this.entityData.set(DATA_VARIANT, variant.id());
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] setVariant failed safely: {}", t.toString());
            }
        }
    }

    // ---------------------------------------------------------------------
    // PlayerAvoidance module accessors
    // ---------------------------------------------------------------------

    // PlayerAvoidance module direct accessor
    public net.z2six.featheredfriend.entity.raven.modules.PlayerAvoidance getPlayerAvoidanceModule() {
        try {
            return this.playeravoidance;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                getSharedLogger().warn("[RavenEntity] getPlayerAvoidanceModule failed safely: {}", t.toString());
            }
            return null;
        }
    }

    /**
     * Exposes RavenEntity's internal logger instance without making the field public.
     * Keeps log category identical to RavenEntity.LOG usage.
     */
    public static org.slf4j.Logger getSharedLogger() {
        try {
            return LOG;
        } catch (Throwable t) {
            // Ultra-safe fallback: should never happen, but we must not crash.
            return com.mojang.logging.LogUtils.getLogger();
        }
    }

    /**
     * Public wrapper for the existing private clampYToHomeBounds(int).
     * Logic is identical; this only fixes access after moving methods into modules.
     */
    public int clampYToHomeBoundsPublic(int y) {
        try {
            return clampYToHomeBounds(y);
        } catch (Throwable t) {
            // If anything goes wrong, return input (safe, non-crashing).
            // This should never trigger in normal operation.
            if (this.tickCount % 80 == 0) {
                getSharedLogger().warn("[RavenEntity] clampYToHomeBoundsPublic failed safely: {}", t.toString());
            }
            return y;
        }
    }

    // --- Player avoidance override ticks ---
    public int getPlayerAvoidanceOverrideTicks() {
        try {
            return this.playerAvoidanceOverrideTicks;
        } catch (Throwable t) {
            return 0;
        }
    }

    public void setPlayerAvoidanceOverrideTicks(int ticks) {
        try {
            this.playerAvoidanceOverrideTicks = ticks;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                getSharedLogger().warn("[RavenEntity] setPlayerAvoidanceOverrideTicks failed safely: {}", t.toString());
            }
        }
    }

    public int getPlayerAvoidanceRearmCooldownTicks() {
        try {
            return this.playerAvoidanceRearmCooldownTicks;
        } catch (Throwable t) {
            return 0;
        }
    }

    // --- Fly target intent ---
    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.phys.Vec3 getFlyTarget() {
        try {
            return this.flyTarget;
        } catch (Throwable t) {
            return null;
        }
    }

    public int getFlyTargetTimeoutTicks() {
        try {
            return this.flyTargetTimeoutTicks;
        } catch (Throwable t) {
            return 0;
        }
    }

    // --- Pathing state used by avoidance re-arm guard/logging ---
    public java.util.List<net.minecraft.world.phys.Vec3> getPathWaypoints() {
        try {
            return this.pathWaypoints;
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    public int getPathWaypointIndex() {
        try {
            return this.pathWaypointIndex;
        } catch (Throwable t) {
            return 0;
        }
    }

    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.phys.Vec3 getPathGoal() {
        try {
            return this.pathGoal;
        } catch (Throwable t) {
            return null;
        }
    }

    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.phys.Vec3 getPathPendingGoal() {
        try {
            return this.pathPendingGoal;
        } catch (Throwable t) {
            return null;
        }
    }

    // --- Ticks that avoidance must reset to maintain behavior ---
    public void setAvoidanceCooldownTicks(int ticks) {
        try {
            this.avoidanceCooldownTicks = ticks;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                getSharedLogger().warn("[RavenEntity] setAvoidanceCooldownTicks failed safely: {}", t.toString());
            }
        }
    }

    public void setStuckTicks(int ticks) {
        try {
            this.stuckTicks = ticks;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                getSharedLogger().warn("[RavenEntity] setStuckTicks failed safely: {}", t.toString());
            }
        }
    }

    public void setRoamTicksRemaining(int ticks) {
        try {
            this.roamTicksRemaining = ticks;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                getSharedLogger().warn("[RavenEntity] setRoamTicksRemaining failed safely: {}", t.toString());
            }
        }
    }

    public int getIdleLockTicks() {
        try {
            return this.idleLockTicks;
        } catch (Throwable t) {
            return 0;
        }
    }

    public void setIdleLockTicks(int ticks) {
        try {
            this.idleLockTicks = ticks;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                getSharedLogger().warn("[RavenEntity] setIdleLockTicks failed safely: {}", t.toString());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Landing module accessors
    // ---------------------------------------------------------------------
    // These wrappers exist so Landing.java can reuse the existing private flight/pathing logic
    // without changing behavior or moving more code across files.

    public Landing getLanding() {
        return this.landing;
    }

    public boolean landingEnsurePathTo(@org.jetbrains.annotations.Nullable Vec3 goal, int ttlTicks, long seed, @org.jetbrains.annotations.Nullable String caller) {
        try {
            return this.ensurePathTo(goal, ttlTicks, seed, caller);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] landingEnsurePathTo failed safely: {}", t.toString());
            }
            return false;
        }
    }

    public void landingSetFlyTarget(@org.jetbrains.annotations.Nullable Vec3 goal, int ttlTicks) {
        try {
            this.setFlyTarget(goal, ttlTicks);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] landingSetFlyTarget failed safely: {}", t.toString());
            }
        }
    }

    public void landingMaybeAvoidOrRetargetDuringFlightApproachOnly(@org.jetbrains.annotations.Nullable RandomSource rnd) {
        try {
            this.maybeAvoidOrRetargetDuringFlightApproachOnly(rnd);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] landingMaybeAvoidOrRetargetDuringFlightApproachOnly failed safely: {}", t.toString());
            }
        }
    }

    public void landingFlyTowardTarget(double speed) {
        try {
            this.flyTowardTarget(speed);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] landingFlyTowardTarget failed safely: {}", t.toString());
            }
        }
    }

    public void landingAdvanceWaypointIfNeeded(int ttlTicks, @org.jetbrains.annotations.Nullable String caller) {
        try {
            this.advanceWaypointIfNeeded(ttlTicks, caller);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] landingAdvanceWaypointIfNeeded failed safely: {}", t.toString());
            }
        }
    }

    public void landingTickFlyTargetTimeout(@org.jetbrains.annotations.Nullable String why) {
        try {
            // If you have a target, tick down TTL.
            if (this.flyTarget != null) {
                if (this.flyTargetTimeoutTicks > 0) {
                    this.flyTargetTimeoutTicks--;
                }

                // If expired, clear (match your usual semantics).
                if (this.flyTargetTimeoutTicks <= 0) {
                    if (this.tickCount % 40 == 0) {
                        LOG.debug("[RavenEntity] landingTickFlyTargetTimeout: expired -> clearFlyTarget (why={}) target={}", why, this.flyTarget);
                    }
                    this.clearFlyTarget();
                    this.flyTargetTimeoutTicks = 0;
                }
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] landingTickFlyTargetTimeout failed safely: {}", t.toString());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Avoidance getters / accessors
    // ---------------------------------------------------------------------

    public boolean isPlayerAvoidanceOverrideActive() {
        try {
            return this.playerAvoidanceOverrideTicks > 0;
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] isPlayerAvoidanceOverrideActive failed safely: {}", t.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Teleportation module accessors
    // ---------------------------------------------------------------------

    public net.z2six.featheredfriend.entity.raven.modules.Teleportation getTeleportation() {
        try {
            return this.teleportation;
        } catch (Throwable t) {
            // Best-effort safety: avoid crashing logic that calls this.
            // Returning null is acceptable because callers already handle null pockets etc.
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getTeleportation failed safely: {}", t.toString());
            }
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Lure-follow / tame module accessors
    // ---------------------------------------------------------------------

    @SuppressWarnings("unused") // invoked reflectively
    private void resetLandingState(String reason) {
        try {
            if (landing != null) {
                landing.resetLandingState(reason, this);
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] resetLandingState(reason={}) failed safely: {}", reason, t.toString());
            }
        }
    }

    public void requestLureFollowPlayer(@org.jetbrains.annotations.Nullable Player player, double distToPlayer) {
        try {
            if (lureFollowTame != null) {
                lureFollowTame.requestLureFollowPlayer(player, distToPlayer);
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] requestLureFollowPlayer wrapper failed safely: {}", t.toString());
            }
        }
    }

    public boolean isLureFollowActive() {
        try {
            return lureFollowTame != null && lureFollowTame.isLureFollowActive();
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isLureFollowActive wrapper failed safely: {}", t.toString());
            }
            return false;
        }
    }

    private int getFollowCooldownTicks() {
        try {
            return (lureFollowTame != null) ? lureFollowTame.getFollowCooldownTicks() : 0;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getFollowCooldownTicks wrapper failed safely: {}", t.toString());
            }
            return 0;
        }
    }

    private void setFollowCooldownTicks(int ticks) {
        try {
            if (lureFollowTame != null) {
                lureFollowTame.setFollowCooldownTicks(ticks);
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] setFollowCooldownTicks wrapper failed safely: {}", t.toString());
            }
        }
    }

    public boolean isFollowOverrideActive() {
        try {
            return lureFollowTame != null && lureFollowTame.isFollowOverrideActive();
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isFollowOverrideActive wrapper failed safely: {}", t.toString());
            }
            return false;
        }
    }

    public int getGoldenNuggetsRequiredToTame() {
        try {
            return (lureFollowTame != null) ? lureFollowTame.getGoldenNuggetsRequiredToTame() : 0;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getGoldenNuggetsRequiredToTame wrapper failed safely: {}", t.toString());
            }
            return 0;
        }
    }

    public void setGoldenNuggetsRequiredToTame(int value) {
        try {
            if (lureFollowTame != null) {
                lureFollowTame.setGoldenNuggetsRequiredToTame(value);
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] setGoldenNuggetsRequiredToTame wrapper failed safely: {}", t.toString());
            }
        }
    }

    public void initGoldenNuggetsRequiredToTameIfNeeded(String context) {
        try {
            if (lureFollowTame != null) {
                lureFollowTame.initGoldenNuggetsRequiredToTameIfNeeded(context);
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] initGoldenNuggetsRequiredToTameIfNeeded wrapper failed safely: {}", t.toString());
            }
        }
    }

}
