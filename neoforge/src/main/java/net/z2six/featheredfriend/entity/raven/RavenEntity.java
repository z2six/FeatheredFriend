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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.z2six.featheredfriend.entity.raven.modules.LureFollowTame;

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

    // --------------------
    // Sound handling
    // --------------------
    private final RavenSoundEngine soundEngine = new RavenSoundEngine(this);

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

    // Small cache so we only resolve from the registry once per sound.
    private static SoundEvent cachedRavenCawingNormal;

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

    @Nullable
    private BlockPos idlePerchCorner = null; // NW corner of the 2x2 perch footprint we are committed to during IDLE_GROUND

    private int idleCommitTicks = 0;

    // -----------------
    // Teleport FX: SERVER-SIDE particle scheduling (world-space, not entity-attached)
    // -----------------

    // You said you set these to 1..1. Keep them. Note: if we spawn at BOTH start+end,
    // you'll see 2 particles minimum (1 at start, 1 at destination).
    private static final int TELEPORT_FX_BURST_MIN = 1;
    private static final int TELEPORT_FX_BURST_MAX = 1;

    // Spacing between bursts
    private static final int TELEPORT_FX_BURST_SPACING_MIN_TICKS = 1;
    private static final int TELEPORT_FX_BURST_SPACING_MAX_TICKS = 3;

    // Particles per burst
    private static final int TELEPORT_FX_PARTICLES_PER_BURST_MIN = 3;
    private static final int TELEPORT_FX_PARTICLES_PER_BURST_MAX = 3;

    // Keep within 1 block
    private static final double TELEPORT_FX_SPREAD_XZ = 0.55D;
    private static final double TELEPORT_FX_SPREAD_Y  = 0.85D;

    private static final double TELEPORT_FX_SPEED = 0.02D;

    // Teleport fade (0..255). Render uses this as alpha.
    private static final EntityDataAccessor<Integer> DATA_TELEPORT_FADE_ALPHA =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    // Teleport sequence phases (server-driven; client reads alpha only).
    private enum TeleportSeqPhase {
        NONE,
        FADING_OUT,
        TELEPORTING,
        FADING_IN
    }

    private static final class PerchValidity {
        final boolean ok;
        final String reason;
        final String details;

        private PerchValidity(boolean ok, String reason, String details) {
            this.ok = ok;
            this.reason = reason;
            this.details = details;
        }

        static PerchValidity ok() {
            return new PerchValidity(true, "OK", "");
        }

        static PerchValidity fail(String reason, String details) {
            return new PerchValidity(false, reason == null ? "FAIL" : reason, details == null ? "" : details);
        }
    }

    private TeleportSeqPhase teleportSeqPhase = TeleportSeqPhase.NONE;
    private int teleportSeqTicks = 0;

    // Fade timing (match your particle window)
    private static final int TELEPORT_FADE_TICKS_OUT = 6; // <= TELEPORT_FX_DURATION_TICKS feels good
    private static final int TELEPORT_FADE_TICKS_IN  = 6;

    // MainFile: neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenEntity.java
    private static final int TELEPORT_INVISIBLE_HOLD_BEFORE_TICKS = 6; // fully invisible BEFORE teleport
    private static final int TELEPORT_INVISIBLE_HOLD_AFTER_TICKS  = 8; // fully invisible AFTER teleport (covers client lerp)

    // Where we intend to teleport to (server only)
    @Nullable
    private Vec3 teleportSeqTarget = null;

    // Keep reason for logs
    @Nullable
    private String teleportSeqReason = null;

    // Scheduler state
    private int teleportFxBurstsRemaining = 0;
    private int teleportFxNextBurstInTicks = 0;
    private long teleportFxServerSeed = 0L;

    // Where to spawn (world-space)
    @Nullable
    private Vec3 teleportFxOriginA = null; // start pos (pre-teleport)
    @Nullable
    private Vec3 teleportFxOriginB = null; // end pos (destination)

    // Teleport recovery tuning
    private static final int TELEPORT_CHECK_INTERVAL_TICKS = 20; // every 1s
    private static final double TELEPORT_MIN_MOVED_DIST = 1.0D;  // less than 1 block => stuck
    private static final int TELEPORT_COOLDOWN_TICKS = 6 * 20;   // 6s
    private static final int TELEPORT_MAX_SEARCH_RADIUS = 6;     // blocks around current position
    private static final int TELEPORT_MAX_CANDIDATES = 48;       // cap attempts

    // Teleport FX: server tells client to play a short burst.
    // 0.5s ~= 10 ticks at 20tps. We'll do 10 ticks.
    private static final int TELEPORT_FX_DURATION_TICKS = 10;

    // Synched FX state (client needs to know when to play burst and with what seed)
    private static final EntityDataAccessor<Integer> DATA_TELEPORT_FX_TICKS =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_TELEPORT_FX_SEED =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.LONG);

    // Sampling state
    private Vec3 teleportSampleLastPos = null;
    private int teleportSampleTicker = 0;
    private int teleportCooldownTicks = 0;
    private int teleportStuckSamples = 0; // consecutive 1-second samples where moved < TELEPORT_MIN_MOVED_DIST

    // Perch footprint rules (2x2, step allowed)
    private static final int PERCH_FOOTPRINT_SIZE = 2; // 2x2
    private static final int PERCH_STEP_DOWN_MAX = 1;  // allow topY and topY-1 within the 2x2

    // How close we must be to the true center of the 2x2 to consider "perched"
    private static final double PERCH_CENTER_EPS = 0.55D;

    // Store landing target as the NW corner of the 2x2 footprint at the chosen topY
    // landingLeafPos used to be a single leaf; now it represents the perch-corner "anchor".
    @Nullable
    private BlockPos landingLeafPos = null;

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

        // Teleport FX sync (client renders short burst)
        builder.define(DATA_TELEPORT_FX_TICKS, 0);
        builder.define(DATA_TELEPORT_FX_SEED, 0L);

        // Transparency
        builder.define(DATA_TELEPORT_FADE_ALPHA, 255);
    }

    // Ender pop particle FX

    private void spawnEnderpopBurst(ServerLevel level, double x, double y, double z, long seed, String why) {
        try {
            if (level == null) return;

            // Deterministic-ish per teleport event
            RandomSource rnd = RandomSource.create(seed ^ (long) this.getId() * 0x9E3779B97F4A7C15L ^ (long) this.tickCount);

            int burstCountMin = Math.min(TELEPORT_FX_BURST_MIN, TELEPORT_FX_BURST_MAX);
            int burstCountMax = Math.max(TELEPORT_FX_BURST_MIN, TELEPORT_FX_BURST_MAX);

            int perMin = Math.min(TELEPORT_FX_PARTICLES_PER_BURST_MIN, TELEPORT_FX_PARTICLES_PER_BURST_MAX);
            int perMax = Math.max(TELEPORT_FX_PARTICLES_PER_BURST_MIN, TELEPORT_FX_PARTICLES_PER_BURST_MAX);

            // Even though this is "per burst", we spawn exactly one burst per scheduler tick.
            // So "count" here is particles-per-burst.
            int count;
            if (perMin == perMax) count = perMin;
            else count = perMin + rnd.nextInt(Math.max(1, perMax - perMin + 1));

            // Hard safety clamps (never 0, never insane)
            count = Mth.clamp(count, 1, 64);

            double dx = TELEPORT_FX_SPREAD_XZ;
            double dy = TELEPORT_FX_SPREAD_Y;
            double dz = TELEPORT_FX_SPREAD_XZ;

            double speed = TELEPORT_FX_SPEED;

            level.sendParticles(
                    net.z2six.featheredfriend.registry.FFNeoForgeParticles.ENDERPOP.get(),
                    x, y, z,
                    count,
                    dx, dy, dz,
                    speed
            );

            if (this.tickCount % 20 == 0) {
                LOG.debug("[RavenEntity] EnderpopBurst: id={} why={} count={} spread=({}, {}, {}) speed={} pos=({}, {}, {}) seed={}",
                        this.getId(),
                        why,
                        count,
                        String.format("%.2f", dx),
                        String.format("%.2f", dy),
                        String.format("%.2f", dz),
                        String.format("%.3f", speed),
                        String.format("%.2f", x),
                        String.format("%.2f", y),
                        String.format("%.2f", z),
                        seed
                );
            }
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] spawnEnderpopBurst failed safely: {}", t.toString());
        }
    }

    private void tickTeleportFxServer() {
        try {
            if (this.level().isClientSide) return;
            if (!(this.level() instanceof ServerLevel serverLevel)) return;

            if (teleportFxBurstsRemaining <= 0) return;

            if (teleportFxNextBurstInTicks > 0) {
                teleportFxNextBurstInTicks--;
                return;
            }

            // One scheduler fire => spawn at BOTH origins (start + end), if present.
            // This matches your requirement.
            if (teleportFxOriginA != null) {
                spawnEnderpopBurst(serverLevel, teleportFxOriginA.x, teleportFxOriginA.y, teleportFxOriginA.z,
                        teleportFxServerSeed ^ 0xA1A1A1A1A1A1A1A1L, "teleportFx A");
            }

            if (teleportFxOriginB != null) {
                spawnEnderpopBurst(serverLevel, teleportFxOriginB.x, teleportFxOriginB.y, teleportFxOriginB.z,
                        teleportFxServerSeed ^ 0xB2B2B2B2B2B2B2B2L, "teleportFx B");
            }

            teleportFxBurstsRemaining--;

            // If more bursts remaining, schedule next delay
            if (teleportFxBurstsRemaining > 0) {
                RandomSource rnd = RandomSource.create(teleportFxServerSeed ^ 0x55AA55AA55AA55AAL ^ (long) teleportFxBurstsRemaining);
                int min = Math.min(TELEPORT_FX_BURST_SPACING_MIN_TICKS, TELEPORT_FX_BURST_SPACING_MAX_TICKS);
                int max = Math.max(TELEPORT_FX_BURST_SPACING_MIN_TICKS, TELEPORT_FX_BURST_SPACING_MAX_TICKS);
                int gap = (min == max) ? min : (min + rnd.nextInt(Math.max(1, max - min + 1)));
                teleportFxNextBurstInTicks = Mth.clamp(gap, 0, 20);
            } else {
                // Done
                teleportFxNextBurstInTicks = 0;
                teleportFxOriginA = null;
                teleportFxOriginB = null;
            }
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] tickTeleportFxServer failed safely: {}", t.toString());
            // fail-closed: stop the scheduler if it misbehaves
            teleportFxBurstsRemaining = 0;
            teleportFxNextBurstInTicks = 0;
            teleportFxOriginA = null;
            teleportFxOriginB = null;
        }
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
            if (followOverrideActive
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
                            landingLeafPos,
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
                        landingLeafPos,
                        landingTicks,
                        idlePerchCorner,
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
                    landingLeafPos,
                    landingTicks,
                    idlePerchCorner,
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

            // Build config for your A*.
            RavenAStarPathing.Config cfg = new RavenAStarPathing.Config();

            // Coarse footprint (2x2x2)
            cfg.cellSize = 2;
            cfg.clearanceHeight = 2;
            cfg.gridStep = 1;

            cfg.allowLeaves = false;
            cfg.allowReplaceables = false;

            cfg.maxExpanded = 6500;
            cfg.maxOpen = 16000;

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
            // NEW: Roll per-spawn tame-cost (3..6 golden nuggets)
            // Only roll if not already set (e.g., NBT-loaded or manually assigned).
            // ------------------------------------------------------------
            initGoldenNuggetsRequiredToTameIfNeeded("finalizeSpawn:" + spawnType);

            if (this.tickCount % 20 == 0) {
                LOG.debug("[RavenEntity] finalizeSpawn: nuggetsRequiredToTame={} spawnType={} pos={}",
                        this.goldenNuggetsRequiredToTame,
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
            tag.putInt(NBT_FOLLOW_CD, this.entityData.get(DATA_FOLLOW_COOLDOWN_TICKS));

            tag.putBoolean(NBT_HOME_INIT, homeInitialized);
            tag.putInt(NBT_HOME_X, homePos.getX());
            tag.putInt(NBT_HOME_Y, homePos.getY());
            tag.putInt(NBT_HOME_Z, homePos.getZ());

            // ------------------------------------------------------------
            // NEW: Persist per-spawn tame-cost (3..6 golden nuggets)
            // ------------------------------------------------------------
            try {
                // Ensure it's initialized before saving (server side typically).
                if (this.goldenNuggetsRequiredToTame <= 0 && this.level() != null && !this.level().isClientSide) {
                    initGoldenNuggetsRequiredToTameIfNeeded("save");
                }

                int v = this.goldenNuggetsRequiredToTame;
                if (v < 3) v = 3;
                if (v > 6) v = 6;

                tag.putInt(NBT_TAME_NUGGETS_REQUIRED, v);

                if (this.tickCount % 200 == 0) {
                    LOG.debug("[RavenEntity] Saved tame-cost: {}={}", NBT_TAME_NUGGETS_REQUIRED, v);
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
                RavenVariant v = RavenVariant.fromId(id); // assuming you have fromId; if not, keep reading below
                this.setRavenVariant(v);
            }

            if (tag.contains(NBT_ANIM_MODE)) {
                int id = tag.getInt(NBT_ANIM_MODE);
                RavenAnimMode m = RavenAnimMode.fromId(id); // assuming you have fromId; if not, keep reading below
                this.setAnimMode(m);
            }

            if (tag.contains(NBT_AI_STATE)) {
                int id = tag.getInt(NBT_AI_STATE);
                RavenAIState s = RavenAIState.fromId(id);
                this.setAIState(s);
            }

            if (tag.contains(NBT_FOLLOW_CD)) {
                int cd = tag.getInt(NBT_FOLLOW_CD);
                this.setFollowCooldownTicks(cd);
            }

            if (tag.contains(NBT_HOME_INIT)) {
                this.homeInitialized = tag.getBoolean(NBT_HOME_INIT);
            }
            if (tag.contains(NBT_HOME_X) && tag.contains(NBT_HOME_Y) && tag.contains(NBT_HOME_Z)) {
                this.homePos = new BlockPos(tag.getInt(NBT_HOME_X), tag.getInt(NBT_HOME_Y), tag.getInt(NBT_HOME_Z));
            }

            // ------------------------------------------------------------
            // NEW: Load per-spawn tame-cost (3..6 golden nuggets)
            // ------------------------------------------------------------
            try {
                if (tag.contains(NBT_TAME_NUGGETS_REQUIRED, net.minecraft.nbt.Tag.TAG_INT)) {
                    int v = tag.getInt(NBT_TAME_NUGGETS_REQUIRED);
                    if (v < 3) v = 3;
                    if (v > 6) v = 6;

                    this.goldenNuggetsRequiredToTame = v;

                    if (this.tickCount % 200 == 0) {
                        LOG.debug("[RavenEntity] Loaded tame-cost: {}={}", NBT_TAME_NUGGETS_REQUIRED, v);
                    }
                } else {
                    // Older saves: initialize safely (server side).
                    initGoldenNuggetsRequiredToTameIfNeeded("load-missingTag");
                }
            } catch (Throwable t2) {
                if (this.tickCount % 200 == 0) {
                    LOG.warn("[RavenEntity] Failed reading tame-cost NBT safely: {}", t2.toString());
                }
                this.goldenNuggetsRequiredToTame = 4;
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
            tickTeleportSequenceServer();

            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                // While teleporting, we do NOTHING else except FX.
                tickTeleportFxServer();
                return;
            }

            // ------------------------------------------------------------------
            // GLOBAL COOLDOWNS (always tick, even during avoidance)
            // ------------------------------------------------------------------
            int cd = LureFollowTame.getFollowCooldownTicks();
            if (cd > 0) {
                LureFollowTame.setFollowCooldownTicks(cd - 1);
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
                        (teleportSeqPhase == TeleportSeqPhase.NONE) &&
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
                resetLandingState("out-of-bounds");
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
            Player owner = LureFollowTame.getOwnerPlayerServerSafe();
            boolean canFollow =
                    owner != null &&
                            this.isTame() &&
                            LureFollowTame.getFollowCooldownTicks() <= 0;

            if (playerAvoidanceOverrideTicks <= 0) {
                if (canFollow) {
                    if (isOutOfHomeBounds(owner.position())) {
                        LureFollowTame.triggerFollowCooldownAndReturn();
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
                case FOLLOW_OWNER -> LureFollowTame.tickFollowOwner();
                default -> tickIdleGround();
            }

            // ------------------------------------------------------------------
            // TELEPORT RECOVERY + FX (ALWAYS ALLOWED)
            // ------------------------------------------------------------------
            tickTeleportRecoverySampler();
            tickTeleportFxServer();

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
                RavenPlayerAvoidanceHelper.tryTriggerPlayerAvoidance(this);

                if (getAIState() != RavenAIState.IDLE_GROUND) {
                    if (this.tickCount % 20 == 0) {
                        LOG.info("[RavenEntity] tickIdleGround: player avoidance switched AI state -> {} (yielding idle tick)", getAIState());
                    }
                    return;
                }
            } catch (Throwable t) {
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] RavenPlayerAvoidanceHelper failed safely (idle): {}", t.toString());
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

            BlockPos corner = idlePerchCorner;
            if (corner != null) {
                perchValidNow = isValidPerchCornerAtTopY(corner);
            } else {
                // Fallback: probe under feet and locate a valid corner nearby.
                BlockPos feetBlock = BlockPos.containing(this.getX(), this.getBoundingBox().minY - 0.001D, this.getZ());
                BlockPos found = findValidPerchCornerNearXZ(feetBlock.getX(), feetBlock.getZ());
                if (found != null) {
                    idlePerchCorner = found;
                    perchValidNow = isValidPerchCornerAtTopY(found);
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
                            idlePerchCorner,
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
                                leaveReason, this.position(), idlePerchCorner);
                    }

                    // Start a roam flight window (or your preferred takeoff logic)
                    setAIState(RavenAIState.ROAM_FLY);
                    idleLockTicks = 12;          // takeoff grace
                    roamTicksRemaining = 0;      // allow landing selection soon if desired
                    resetLandingState("idle left: " + leaveReason);
                    clearPlannedPath("idle left: " + leaveReason);
                    clearFlyTarget();

                    beginRoamFlightWindow("idle left: perch invalid");
                    return;
                }
            } else {
                // Reset loss counter if we are valid again.
                if (idleLeafLossTicks > 0 && this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] IDLE: perch validity restored. pos={} idlePerchCorner={} lossTicksResetFrom={}",
                            this.position(), idlePerchCorner, idleLeafLossTicks);
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
                                leaveReason, this.position(), idlePerchCorner);
                    }

                    setAIState(RavenAIState.ROAM_FLY);
                    idleLockTicks = 12;
                    roamTicksRemaining = 0;
                    resetLandingState("idle left: " + leaveReason);
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
                        this.position(), idlePerchCorner, idleTicksRemaining, idleCommitTicks, idleLeafLossTicks);
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
                RavenPlayerAvoidanceHelper.tryTriggerPlayerAvoidance(this);
            } catch (Throwable t) {
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] RavenPlayerAvoidanceHelper failed safely: {}", t.toString());
                }
            }

            // --------------------------------------------------------------------
            // ✅ HARD OVERRIDE: while playerAvoidanceOverrideTicks > 0 we do NOT allow
            // landing / perch / roam planners to run at all. We only:
            //  - keep flight physics
            //  - keep landing cancelled
            //  - fly toward current target/path
            //  - allow teleport blink checks (stuck logic)
            // --------------------------------------------------------------------
            if (playerAvoidanceOverrideTicks > 0) {
                // Absolutely cancel landing every tick to stop "re-perch" fights.
                try {
                    resetLandingState("player avoidance override tick");
                } catch (Throwable ignored) {}

                landingPhase = LandingPhase.NONE;
                landingLeafPos = null;
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
                    tickRandomFlightTeleportBlink();
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
                    tickRandomFlightTeleportBlink();
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
            if (!this.isNoGravity() && isOnValidPerchNow()) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] ROAM_FLY -> IDLE shortcut (not flying): pos={} vel={} landingPhase={} leafPos={}",
                            this.position(), this.getDeltaMovement(), landingPhase, landingLeafPos);
                }
                enterIdleFromLanding("ROAM_FLY: centered on valid 2x2 perch (not flying)");
                return;
            }

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

            if (landingLeafPos != null && !isStillValidLandingLeaf(landingLeafPos)) {
                if (this.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] Landing leaf became invalid -> resetting landing leaf at {}", landingLeafPos);
                }
                resetLandingState("invalid leaf");
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
                    tickRandomFlightTeleportBlink();
                } catch (Throwable t) {
                    if (this.tickCount % 40 == 0) {
                        LOG.warn("[RavenEntity] tickRandomFlightTeleportBlink failed safely (roamWindow): {}", t.toString());
                    }
                }

                return;
            }

            if (landingPhase != LandingPhase.NONE) {
                // ❌ No random blink here: landing state machine owns movement & phases.
                tickLandingStateMachine(rnd);
                return;
            }

            BlockPos leaf = pickLandingLeafBlock(rnd);
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
                    tickRandomFlightTeleportBlink();
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
            landingLeafPos = leaf;
            landingPhase = LandingPhase.FLY_TO_OVERHEAD;
            landingTicks = 0;

            Vec3 overhead = overheadTargetForLeaf(leaf);

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
                        leaf, overhead, LAND_REQUIRED_AIR_ABOVE, CANOPY_NEIGHBOR_LEAVES_REQUIRED, ok);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] tickRoamFly failed", t);
        }
    }

    /**
     * Landing state machine (refactored):
     *  - FLY_TO_OVERHEAD uses coarse A* waypoint pathing to reach the overhead point without punching through canopy/structures.
     *  - DESCEND_SLOW and DROP remain local/physics-driven (no A*), unchanged in behavior.
     */
    private void tickLandingStateMachine(RandomSource rnd) {
        // FIX: Do NOT allow "I’m on a perch" shortcut while we’re still in flight pathing.
        // Otherwise, passing near the 2x2 center during FLY_TO_OVERHEAD can trigger enterIdleFromLanding(),
        // which can then snap (setPos) and look like a silent teleport.
        try {
            boolean allowPerchShortcutNow = false;

            // Only allow the shortcut when we are actually in DROP (noGravity=false) OR explicitly not flying.
            // This keeps behavior stable and prevents mid-air "instant idle" transitions.
            if (landingPhase == LandingPhase.DROP) {
                allowPerchShortcutNow = true;
            } else {
                // fallback safety: if not flying physics (noGravity=false) allow it
                // (covers weird edge cases where phase might be desynced)
                try {
                    allowPerchShortcutNow = !this.isNoGravity();
                } catch (Throwable t) {
                    allowPerchShortcutNow = false;
                }
            }

            if (allowPerchShortcutNow && isOnValidPerchNow()) {
                if (this.tickCount % 20 == 0) {
                    LOG.debug("[RavenEntity] Landing shortcut -> IDLE allowed (phase={} noGravity={} pos={} vel={} leafPos={})",
                            landingPhase, this.isNoGravity(), this.position(), this.getDeltaMovement(), landingLeafPos);
                }
                enterIdleFromLanding("Landing: perched (phase=" + landingPhase + ")");
                return;
            }
        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                LOG.warn("[RavenEntity] tickLandingStateMachine perch-shortcut gate failed safely: {}", t.toString());
            }
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

                // Only plan if we currently have no movement target.
                boolean hasFlyIntent = (flyTarget != null && flyTargetTimeoutTicks > 0);
                boolean hasPathIntent = (pathWaypoints != null && !pathWaypoints.isEmpty() && pathWaypointIndex < pathWaypoints.size());

                // Also, if we are very close, don't replan; we should just transition.
                Vec3 pos = this.position();
                double horizNow = horizontalDistanceTo(pos, overhead);
                double vertNow = Math.abs(pos.y - overhead.y);

                if (horizNow <= OVERHEAD_HORIZONTAL_EPS && vertNow <= OVERHEAD_VERTICAL_EPS) {
                    landingPhase = LandingPhase.DESCEND_SLOW;
                    clearFlyTarget();
                    clearPlannedPath("overhead reached -> descent");
                    if (this.tickCount % 20 == 0) {
                        LOG.info("[RavenEntity] Overhead reached -> DESCEND_SLOW (leaf={} pos={} horiz={} vert={})",
                                landingLeafPos, pos, String.format("%.3f", horizNow), String.format("%.3f", vertNow));
                    }
                    return;
                }

                // Only compute a new path if we don't already have one.
                if (!hasFlyIntent && !hasPathIntent) {
                    long seed = this.getUUID().getLeastSignificantBits() ^ (long) this.tickCount ^ landingLeafPos.asLong();
                    boolean ok = ensurePathTo(overhead, 6 * 20, seed, "FLY_TO_OVERHEAD");

                    if (!ok) {
                        setFlyTarget(overhead, 6 * 20);
                    }

                    if (this.tickCount % 20 == 0) {
                        LOG.info("[RavenEntity] FLY_TO_OVERHEAD acquire intent: pathOk={} overhead={} pos={} vel={}",
                                ok, overhead, pos, this.getDeltaMovement());
                    }
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

                // --- FIX: accept slightly-off landings and COMMIT the correct 2x2 corner ---
                // The logs you showed were repeating DROP forever because "relaxedPerch=false" and dXZ ~ 1.2–1.27,
                // so isOnValidPerchNowRelaxedForLanding() never returned true.
                //
                // During DROP, if we're grounded/stable we should:
                //  1) pick the BEST valid 2x2 corner (prefer landingLeafPos vicinity)
                //  2) accept up to ~1.6 blocks off-center (then enterIdleFromLanding will snap)
                try {
                    boolean groundedStable = this.onGround() || this.verticalCollision;

                    if (groundedStable) {
                        BlockPos feetBlock = BlockPos.containing(this.getX(), this.getBoundingBox().minY - 0.001D, this.getZ());

                        BlockPos bestCorner = null;
                        try {
                            bestCorner = findBestPerchCornerForLanding(landingLeafPos, feetBlock);
                        } catch (Throwable t) {
                            bestCorner = null;
                            if (this.tickCount % 40 == 0) {
                                LOG.warn("[RavenEntity] DROP: findBestPerchCornerForLanding failed safely: {}", t.toString());
                            }
                        }

                        if (bestCorner != null && isValidPerchCornerAtTopY(bestCorner)) {
                            Vec3 center = perchCenterTop(bestCorner);
                            Vec3 pos = this.position();

                            double dx = pos.x - center.x;
                            double dz = pos.z - center.z;
                            double dXZ = Math.sqrt(dx * dx + dz * dz);

                            // Key constant: your failing samples are ~1.20–1.27; accept them and snap in enterIdleFromLanding.
                            final double DROP_SNAP_EPS = 1.60D;

                            if (this.tickCount % 20 == 0) {
                                LOG.info("[RavenEntity] DROP: groundedStable={} bestCorner={} center={} pos={} dXZ={} eps={} landingLeafPos={} onGround={} vColl={} vel={}",
                                        groundedStable,
                                        bestCorner,
                                        center,
                                        pos,
                                        String.format("%.3f", dXZ),
                                        String.format("%.2f", DROP_SNAP_EPS),
                                        landingLeafPos,
                                        this.onGround(),
                                        this.verticalCollision,
                                        this.getDeltaMovement()
                                );
                            }

                            if (dXZ <= DROP_SNAP_EPS) {
                                // Commit the anchor so IDLE validity checks don't “pick a different corner” and instantly fail.
                                idlePerchCorner = bestCorner;

                                // Also align landingLeafPos to the chosen corner so enterIdleFromLanding uses the correct anchor.
                                // (This is safe because landingLeafPos is "perch-corner anchor" in your code.)
                                landingLeafPos = bestCorner;

                                enterIdleFromLanding("DROP: grounded stable + bestCorner within snap eps (dXZ=" + String.format("%.3f", dXZ) + ")");
                                return;
                            }
                        }
                    }
                } catch (Throwable t) {
                    if (this.tickCount % 40 == 0) {
                        LOG.warn("[RavenEntity] DROP: snap-accept logic failed safely: {}", t.toString());
                    }
                }

                // Fallback: keep your existing relaxed check (still useful when we are actually close / centered).
                if (isOnValidPerchNowRelaxedForLanding("DROP")) {
                    enterIdleFromLanding("DROP: on valid 2x2 perch (relaxed threshold, will snap)");
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

            // ---- NEW: hard-kill any leftover "intent" that can cause ensurePathTo while perched.
            // If these remain non-null, other code can interpret "intent exists" and re-path.
            boolean hadAnyIntent =
                    (pathGoal != null)
                            || (pathPendingGoal != null)
                            || (pathWaypoints != null && !pathWaypoints.isEmpty());

            pathGoal = null;
            pathPendingGoal = null;
            pathWaypoints = null;
            pathWaypointIndex = 0;
            pathRetryCooldownTicks = 0;

            if (hadAnyIntent && this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] enterIdleFromLanding: cleared leftover path/goal intent. reason={} pos={}",
                        reason, this.position());
            }

            BlockPos committedCorner = landingLeafPos;
            if (committedCorner == null) {
                BlockPos feetBlock = BlockPos.containing(this.getX(), this.getBoundingBox().minY - 0.001D, this.getZ());
                committedCorner = findValidPerchCornerNearXZ(feetBlock.getX(), feetBlock.getZ());
            }

            resetLandingState("enter idle: " + reason);

            idlePerchCorner = committedCorner;

            setAIState(RavenAIState.IDLE_GROUND);

            // ---- NEW: "commit" window so we don't immediately bail out of idle due to jitter / soft checks.
            // This solves the "lands, idles 1s, takes off" behavior when a soft rule fires right after landing.
            idleCommitTicks = 60; // 3 seconds @ 20 TPS (tune: 40=2s, 80=4s)

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

            // Prevent "silent snap" while airborne:
            // Only snap to perch center if we are effectively settled (not flying) and not moving vertically much.
            boolean canSnapNow = true;
            try {
                boolean notFlying = !this.isNoGravity();
                boolean lowVertical = Math.abs(this.getDeltaMovement().y) <= 0.10D;
                boolean touchingSomething = this.onGround() || this.verticalCollision;
                canSnapNow = notFlying && (lowVertical || touchingSomething);
            } catch (Throwable t) {
                canSnapNow = false;
            }

            if (canSnapNow && idlePerchCorner != null && isValidPerchCornerAtTopY(idlePerchCorner)) {
                Vec3 centerTop = perchCenterTop(idlePerchCorner);

                double snapX = centerTop.x;
                double snapZ = centerTop.z;
                double keepY = this.getY();

                this.setPos(snapX, keepY, snapZ);
                this.hurtMarked = true;

                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] enterIdleFromLanding: snapped to perch center. reason={} idlePerchCorner={} newPos={} vel={}",
                            reason, idlePerchCorner, this.position(), this.getDeltaMovement());
                }
            } else {
                if (this.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] enterIdleFromLanding: snap skipped (canSnapNow={}). reason={} idlePerchCorner={} pos={} vel={} noGravity={} onGround={} vColl={}",
                            canSnapNow, reason, idlePerchCorner, this.position(), this.getDeltaMovement(), this.isNoGravity(), this.onGround(), this.verticalCollision);
                }
            }

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] ENTER IDLE: reason={} pos={} idlePerchCorner={} idleTicksRemaining={} commitTicks={}",
                        reason, this.position(), idlePerchCorner, idleTicksRemaining, idleCommitTicks);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] enterIdleFromLanding failed (reason={})", reason, t);
        }
    }

    private boolean isStillValidLandingLeaf(BlockPos leaf) {
        try {
            // leaf is now "perchCornerTop"
            if (leaf == null) return false;

            // Re-validate the full footprint (includes air column + canopy/edge protection)
            return isValidPerchCornerAtTopY(leaf);
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] isStillValidLandingLeaf failed: {}", t.toString());
            }
            return false;
        }
    }

    private Vec3 overheadTargetForLeaf(BlockPos leaf) {
        // leaf is now perchCornerTop
        Vec3 centerTop = perchCenterTop(leaf);
        return new Vec3(centerTop.x, leaf.getY() + OVERHEAD_Y_OFFSET_FROM_LEAF, centerTop.z);
    }

    private Vec3 leafCenterTop(BlockPos leaf) {
        // leaf is now perchCornerTop
        return perchCenterTop(leaf);
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

            // We now pick a 2x2 perch corner (cornerTop = (cornerX, topY, cornerZ))
            BlockPos center = new BlockPos(cx, clampYToHomeBounds(homePos.getY()), cz);
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
                    BlockPos probe = new BlockPos(x, y, z);
                    BlockState state = this.level().getBlockState(probe);
                    if (state == null || !state.is(BlockTags.LEAVES)) {
                        continue;
                    }

                    // Convert this probe leaf into a candidate 2x2 perch corner (stepped allowed)
                    BlockPos perchCorner = findValidPerchCornerNearXZ(probe.getX(), probe.getZ());
                    if (perchCorner == null) {
                        continue;
                    }

                    // Must be within home bounds (use perch center as the test)
                    Vec3 perchCenter = perchCenterTop(perchCorner);
                    if (isOutOfHomeBounds(perchCenter)) {
                        continue;
                    }

                    // If valid corner check already enforced air column, this is redundant,
                    // but leaving it as an extra guard is fine.
                    if (!isValidPerchCornerAtTopY(perchCorner)) {
                        continue;
                    }

                    return perchCorner;
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
            // We do NOT just sample a single block below a single point anymore.
            // Instead, we sample the four corners of the entity's AABB footprint.
            // Requirement:
            //  - Each corner must have leaves support either directly below OR one block further down.
            // This allows "stepped 2x2" where one quadrant is 1 block lower.
            //
            // This dramatically reduces false negatives at edges / while settling.

            var bb = this.getBoundingBox();
            if (bb == null) {
                return false;
            }

            // Probe just below feet.
            final double yProbe = bb.minY - 0.02D;

            // Small inset so we don't hit neighbor blocks from precision jitter.
            final double inset = 0.05D;

            final double x0 = bb.minX + inset;
            final double x1 = bb.maxX - inset;
            final double z0 = bb.minZ + inset;
            final double z1 = bb.maxZ - inset;

            // If the entity is extremely tiny (or AABB is degenerate), fall back to center sample.
            if (x1 <= x0 || z1 <= z0) {
                BlockPos feetBlock = BlockPos.containing(this.getX(), yProbe, this.getZ());
                return isLeavesSupportAtOrOneBelow(feetBlock);
            }

            // Four corners.
            BlockPos p00 = BlockPos.containing(x0, yProbe, z0);
            BlockPos p01 = BlockPos.containing(x0, yProbe, z1);
            BlockPos p10 = BlockPos.containing(x1, yProbe, z0);
            BlockPos p11 = BlockPos.containing(x1, yProbe, z1);

            // All corners must be supported.
            boolean ok =
                    isLeavesSupportAtOrOneBelow(p00) &&
                            isLeavesSupportAtOrOneBelow(p01) &&
                            isLeavesSupportAtOrOneBelow(p10) &&
                            isLeavesSupportAtOrOneBelow(p11);

            // Debug occasionally so we can see if this is doing work.
            if (!ok && (this.tickCount % 40 == 0)) {
                LOG.debug("[RavenEntity] isLeafUnderFeet=false (footprint corners). pos={} bbMinY={} p00={} p01={} p10={} p11={}",
                        this.position(),
                        String.format("%.3f", bb.minY),
                        p00, p01, p10, p11
                );
            }

            return ok;

        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] isLeafUnderFeet failed: {}", t.toString());
            }
            return false;
        }
    }

    private boolean isLeavesSupportAtOrOneBelow(BlockPos probeAt) {
        try {
            if (probeAt == null) return false;

            // We check the block BELOW the probe, and also one further below.
            // This supports stepped 2x2 (one block lower).
            BlockPos b1 = probeAt.below();
            BlockState s1 = this.level().getBlockState(b1);
            if (s1 != null && s1.is(BlockTags.LEAVES)) return true;

            BlockPos b2 = b1.below();
            BlockState s2 = this.level().getBlockState(b2);
            return s2 != null && s2.is(BlockTags.LEAVES);

        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns the BlockPos of the NW corner of a valid 2x2 perch footprint at a specific topY
     * (corner.y = topY). The 2x2 may be "stepped": each of the 4 blocks must be LEAVES at either
     * topY or topY-1.
     *
     * We test corners around the given probe (x/z) because when you're standing on an edge,
     * the "below feet" block may be one of the four.
     */
    @Nullable
    private BlockPos findValidPerchCornerNearXZ(int x, int z) {
        try {
            // We check the four possible 2x2 corners that could include (x,z):
            // corners: (x,z), (x-1,z), (x,z-1), (x-1,z-1)
            int[][] corners = new int[][]{
                    {x, z},
                    {x - 1, z},
                    {x, z - 1},
                    {x - 1, z - 1}
            };

            for (int[] c : corners) {
                int cx = c[0];
                int cz = c[1];

                // Determine topY as the max leaf Y among the 4 positions (within a small vertical probe)
                Integer topY = computePerchTopY(cx, cz);
                if (topY == null) continue;

                BlockPos cornerTop = new BlockPos(cx, topY, cz);

                if (isValidPerchCornerAtTopY(cornerTop)) {
                    return cornerTop;
                }
            }

            return null;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] findValidPerchCornerNearXZ failed safely: {}", t.toString());
            }
            return null;
        }
    }

    /**
     * Computes a candidate topY for a 2x2 corner (cx, cz) by checking the 4 footprint blocks
     * at several Y samples near the local surface.
     *
     * We deliberately keep this cheap:
     *  - We use WORLD_SURFACE as a starting hint.
     *  - Then we scan downward a few blocks to find the highest leaves among the 4 columns.
     */
    @Nullable
    private Integer computePerchTopY(int cx, int cz) {
        try {
            // Use heightmap as a hint (fast)
            int hintY;
            try {
                hintY = this.level().getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
            } catch (Throwable t) {
                hintY = this.blockPosition().getY();
            }

            int maxY = Integer.MIN_VALUE;

            // Probe range: from hintY down a bit (trees / canopies)
            int scanTop = Mth.clamp(hintY + 2, this.level().getMinBuildHeight(), this.level().getMaxBuildHeight() - 1);
            int scanBottom = Mth.clamp(hintY - 8, this.level().getMinBuildHeight(), this.level().getMaxBuildHeight() - 1);

            for (int y = scanTop; y >= scanBottom; y--) {
                // Check if ANY of the 4 footprint positions has leaves at this y.
                // We want the highest y where at least one is leaves, as the candidate topY.
                if (isLeavesAt(cx, y, cz)
                        || isLeavesAt(cx + 1, y, cz)
                        || isLeavesAt(cx, y, cz + 1)
                        || isLeavesAt(cx + 1, y, cz + 1)) {
                    maxY = y;
                    break;
                }
            }

            if (maxY == Integer.MIN_VALUE) {
                return null;
            }
            return maxY;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] computePerchTopY failed safely: {}", t.toString());
            }
            return null;
        }
    }

    private boolean isLeavesAt(int x, int y, int z) {
        try {
            BlockState st = this.level().getBlockState(new BlockPos(x, y, z));
            return st != null && st.is(BlockTags.LEAVES);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Validates the 2x2 perch footprint at cornerTop=(cx, topY, cz).
     *
     * Rules:
     *  - For each of the 4 positions, there must be LEAVES at either topY or topY-1.
     *  - There must be at least one leaf at topY (so we don't "pick" a fully-downshifted shelf).
     *  - Air column above the *top layer* should be clear (we check all 4 top-layer positions),
     *    using your LAND_REQUIRED_AIR_ABOVE.
     *  - Canopy thickness: we require the footprint to be on/within canopy, using your existing
     *    neighbor-leaves concept but applied to the footprint (cheap aggregate).
     */
    private boolean isValidPerchCornerAtTopY(BlockPos cornerTop) {
        try {
            return validatePerchCornerAtTopY(cornerTop).ok;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isValidPerchCornerAtTopY wrapper failed safely: {}", t.toString());
            }
            return false;
        }
    }

    /**
     * Returns the true center-top point of the 2x2 footprint for a cornerTop (cx, topY, cz).
     * 2x2 spans [cx..cx+2) and [cz..cz+2), so center is (cx+1.0, cz+1.0).
     * Top surface is (topY + 1.0).
     */
    private Vec3 perchCenterTop(BlockPos cornerTop) {
        try {
            if (cornerTop == null) return this.position();
            double x = cornerTop.getX() + 1.0D;
            double z = cornerTop.getZ() + 1.0D;
            double y = cornerTop.getY() + 1.0D;
            return new Vec3(x, y, z);
        } catch (Throwable t) {
            return this.position();
        }
    }

    /**
     * Checks if our current position is close enough to the true 2x2 center of a valid perch footprint
     * under/near our feet. This replaces "leafUnderFeet" for entering/staying idle.
     */
    private boolean isOnValidPerchNow() {
        try {
            // Probe block under feet (use bb minY)
            BlockPos feetBlock = BlockPos.containing(this.getX(), this.getBoundingBox().minY - 0.001D, this.getZ());

            BlockPos corner = findValidPerchCornerNearXZ(feetBlock.getX(), feetBlock.getZ());
            if (corner == null) {
                return false;
            }

            Vec3 center = perchCenterTop(corner);
            Vec3 pos = this.position();

            double dx = pos.x - center.x;
            double dz = pos.z - center.z;
            double dXZ = Math.sqrt(dx * dx + dz * dz);

            // Must be near the center to count as "perched"
            return dXZ <= PERCH_CENTER_EPS;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isOnValidPerchNow failed safely: {}", t.toString());
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

    private void tickTeleportRecoverySampler() {
        try {
            if (this.level().isClientSide) return;

            if (teleportCooldownTicks > 0) {
                teleportCooldownTicks--;
            }

            RavenAIState st = getAIState();

            // New rule per request:
            // - Allow in ALL phases (landing/takeoff/descent/drop/follow/roam) as long as we're not idle.
            // - Still skip while a teleport sequence is already running.
            boolean excludedByPhase =
                    (teleportSeqPhase != TeleportSeqPhase.NONE)
                            || (st == RavenAIState.IDLE_GROUND);

            if (excludedByPhase || !isTeleportRecoveryEligible()) {
                // Reset sampling so we don't bank samples while excluded (idle / sequence).
                teleportSampleLastPos = this.position();
                teleportSampleTicker = 0;
                teleportStuckSamples = 0;

                if (excludedByPhase && this.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] TeleportRecovery: sampling skipped due to phase. phase={} ai={} landingPhase={} idleLockTicks={} pos={}",
                            teleportSeqPhase, st, landingPhase, idleLockTicks, this.position());
                }
                return;
            }

            teleportSampleTicker++;
            if (teleportSampleTicker < TELEPORT_CHECK_INTERVAL_TICKS) {
                return;
            }
            teleportSampleTicker = 0;

            Vec3 now = this.position();
            if (teleportSampleLastPos == null) {
                teleportSampleLastPos = now;
                teleportStuckSamples = 0;
                return;
            }

            double moved = now.distanceTo(teleportSampleLastPos);
            teleportSampleLastPos = now;

            // While cooling down, do not accumulate stuck samples.
            if (teleportCooldownTicks > 0) {
                teleportStuckSamples = 0;
                return;
            }

            boolean movedEnough = moved >= TELEPORT_MIN_MOVED_DIST;

            if (movedEnough) {
                if (teleportStuckSamples > 0 && this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] TeleportRecovery: progress resumed; reset stuckSamples. moved={} >= {} ai={} pos={} vel={} flyTarget={} pathGoal={} pendingGoal={} landingPhase={} idleLockTicks={}",
                            String.format("%.3f", moved),
                            TELEPORT_MIN_MOVED_DIST,
                            st,
                            now,
                            this.getDeltaMovement(),
                            flyTarget,
                            pathGoal,
                            pathPendingGoal,
                            landingPhase,
                            idleLockTicks);
                }
                teleportStuckSamples = 0;
                return;
            }

            teleportStuckSamples++;

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] TeleportRecovery: stuck sample {}/3 (moved={} < {}) ai={} pos={} vel={} flyTarget={} pathGoal={} pendingGoal={} landingPhase={} idleLockTicks={}",
                        teleportStuckSamples,
                        String.format("%.3f", moved),
                        TELEPORT_MIN_MOVED_DIST,
                        st,
                        now,
                        this.getDeltaMovement(),
                        flyTarget,
                        pathGoal,
                        pathPendingGoal,
                        landingPhase,
                        idleLockTicks);
            }

            if (teleportStuckSamples < 3) {
                return;
            }

            teleportStuckSamples = 0;

            boolean ok = attemptTeleportRecovery("stuck 3x (3s) moved<1");
            if (ok) {
                teleportCooldownTicks = TELEPORT_COOLDOWN_TICKS;
            } else {
                teleportCooldownTicks = Math.min(TELEPORT_COOLDOWN_TICKS, 2 * 20);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] tickTeleportRecoverySampler failed", t);

            teleportSampleLastPos = this.position();
            teleportSampleTicker = 0;
            teleportStuckSamples = 0;
        }
    }

    private void beginTeleportPhase(String reason) {
        try {
            // Freeze hard.
            this.setDeltaMovement(Vec3.ZERO);
            this.hurtMarked = true;

            // During fade/teleport we do not want damage.
            this.setInvulnerable(true);

            // IMPORTANT: we do NOT setInvisible(true) immediately anymore,
            // because we want a visible fade-out. Renderer will use alpha.

            // Gravity off, because flight entity and to avoid fall jitter.
            this.setNoGravity(true);

            // Disable physics/collision resolution.
            // In 1.21.1 there is no public setNoPhysics(boolean). Use the protected field.
            this.noPhysics = true;

            if (this.tickCount % 20 == 0) {
                LOG.debug("[RavenEntity] beginTeleportPhase: id={} reason={} pos={} invuln={} noPhys={} fadeAlpha={}",
                        this.getId(), reason, this.position(), this.isInvulnerable(), this.noPhysics, getTeleportFadeAlpha());
            }
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] beginTeleportPhase failed safely: {}", t.toString());
        }
    }


    private void endTeleportPhase(String reason) {
        try {
            // Restore normal physics.
            // In 1.21.1 there is no public setNoPhysics(boolean). Use the protected field.
            this.noPhysics = false;

            // Restore damage behavior.
            this.setInvulnerable(false);

            // We also ensure vanilla invis is off (we rely on alpha fade now).
            this.setInvisible(false);

            this.hurtMarked = true;

            if (this.tickCount % 20 == 0) {
                LOG.debug("[RavenEntity] endTeleportPhase: id={} reason={} pos={} invuln={} noPhys={} fadeAlpha={}",
                        this.getId(), reason, this.position(), this.isInvulnerable(), this.noPhysics, getTeleportFadeAlpha());
            }
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] endTeleportPhase failed safely: {}", t.toString());
        }
    }

    public int getTeleportFadeAlphaPublic() {
        try {
            return this.entityData.get(DATA_TELEPORT_FADE_ALPHA);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getTeleportFadeAlphaPublic failed safely: {}", t.toString());
            }
            return 255;
        }
    }

    /**
     * Client-side: how many vanilla lerp steps remain (position interpolation after server correction).
     * Server-side: always 0.
     */
    public int getClientLerpStepsPublic() {
        try {
            if (!this.level().isClientSide) {
                return 0;
            }

            // These fields exist in vanilla Entity in modern versions.
            // If mappings change, we fail safely and return 0.
            return this.lerpSteps;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getClientLerpStepsPublic failed safely: {}", t.toString());
            }
            return 0;
        }
    }

    /**
     * Client-side: squared distance from current position to the lerp target.
     * Useful to detect "big snaps" vs tiny corrections.
     * Server-side: always 0.
     */
    public double getClientLerpTargetDistSqrPublic() {
        try {
            if (!this.level().isClientSide) {
                return 0.0D;
            }

            // Vanilla stores lerp targets as doubles
            double dx = this.lerpX - this.getX();
            double dy = this.lerpY - this.getY();
            double dz = this.lerpZ - this.getZ();
            return dx * dx + dy * dy + dz * dz;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getClientLerpTargetDistSqrPublic failed safely: {}", t.toString());
            }
            return 0.0D;
        }
    }

    private int getTeleportFadeAlpha() {
        try {
            return this.entityData.get(DATA_TELEPORT_FADE_ALPHA);
        } catch (Throwable t) {
            return 255;
        }
    }

    private void setTeleportFadeAlpha(int alpha) {
        try {
            this.entityData.set(DATA_TELEPORT_FADE_ALPHA, Mth.clamp(alpha, 0, 255));
        } catch (Throwable t) {
            // no crash
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] setTeleportFadeAlpha failed: {}", t.toString());
            }
        }
    }

    private void startTeleportSequence(@Nullable Vec3 target, long fxSeed, String reason) {
        try {
            if (this.level().isClientSide) return;
            if (target == null) return;

            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                if (this.tickCount % 20 == 0) {
                    LOG.debug("[RavenEntity] startTeleportSequence ignored (already active). phase={} reason={}", teleportSeqPhase, reason);
                }
                return;
            }

            teleportSeqTarget = target;
            teleportSeqReason = reason;
            teleportSeqTicks = 0;
            teleportSeqPhase = TeleportSeqPhase.FADING_OUT;

            beginTeleportPhase(reason);

            setTeleportFadeAlpha(255);

            // FIX #1:
            // Only start FX at origin immediately. Do NOT schedule destination FX yet,
            // otherwise you see particles at the target location before the fade-out is visible.
            startTeleportFxServer(fxSeed, this.position(), null);

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] TeleportSequence START reason={} pos={} target={} fadeOutTicks={} fadeInTicks={}",
                        reason, this.position(), target, TELEPORT_FADE_TICKS_OUT, TELEPORT_FADE_TICKS_IN);
            }
        } catch (Throwable t) {
            LOG.error("[RavenEntity] startTeleportSequence failed (reason={})", reason, t);

            teleportSeqPhase = TeleportSeqPhase.NONE;
            teleportSeqTarget = null;
            teleportSeqReason = null;
            setTeleportFadeAlpha(255);
            endTeleportPhase("failsafe startTeleportSequence");
        }
    }

    private void tickTeleportSequenceServer() {
        try {
            if (this.level().isClientSide) return;
            if (!(this.level() instanceof ServerLevel serverLevel)) return;

            if (teleportSeqPhase == TeleportSeqPhase.NONE) return;

            this.setDeltaMovement(Vec3.ZERO);
            this.hurtMarked = true;

            teleportSeqTicks++;

            switch (teleportSeqPhase) {
                case FADING_OUT -> {
                    int t = teleportSeqTicks;
                    int total = Math.max(1, TELEPORT_FADE_TICKS_OUT);

                    float k = Mth.clamp((float) t / (float) total, 0.0F, 1.0F);
                    int alpha = (int) Mth.lerp(k, 255.0F, 0.0F);
                    setTeleportFadeAlpha(alpha);

                    // When fade-out finishes, move into TELEPORTING state.
                    if (t >= total) {
                        teleportSeqPhase = TeleportSeqPhase.TELEPORTING;
                        teleportSeqTicks = 0;
                        setTeleportFadeAlpha(0);
                    }
                }

                case TELEPORTING -> {
                    setTeleportFadeAlpha(0);

                    Vec3 target = teleportSeqTarget;
                    if (target == null) {
                        LOG.warn("[RavenEntity] TeleportSequence TELEPORTING but target=null. Aborting.");
                        teleportSeqPhase = TeleportSeqPhase.NONE;
                        setTeleportFadeAlpha(255);
                        endTeleportPhase("teleport target null");
                        return;
                    }

                    int holdBefore = Math.max(0, TELEPORT_INVISIBLE_HOLD_BEFORE_TICKS);
                    int holdAfter = Math.max(0, TELEPORT_INVISIBLE_HOLD_AFTER_TICKS);

                    int teleportTickIndex = holdBefore + 1;
                    int endOfAfterHoldTick = holdBefore + 1 + holdAfter;

                    boolean shouldTeleportNow = (teleportSeqTicks == teleportTickIndex);

                    if (shouldTeleportNow) {
                        boolean teleported = false;
                        Vec3 before = this.position();

                        try {
                            teleported = this.teleportTo(serverLevel, target.x, target.y, target.z, java.util.Set.of(), this.getYRot(), this.getXRot());
                        } catch (Throwable t) {
                            LOG.warn("[RavenEntity] TeleportSequence teleportTo failed safely: {}", t.toString());
                            teleported = false;
                        }

                        if (!teleported) {
                            this.setPos(target.x, target.y, target.z);
                        }

                        this.setDeltaMovement(Vec3.ZERO);
                        this.hurtMarked = true;

                        // FIX #1 (continued):
                        // Spawn destination FX ONLY after we've faded out and actually teleported.
                        // This guarantees you never see target FX before the fade starts.
                        try {
                            long fxSeed = this.entityData.get(DATA_TELEPORT_FX_SEED) ^ 0xD15C0FFEE0DDF00DL ^ (long) this.tickCount;
                            startTeleportFxServer(fxSeed, null, this.position());
                        } catch (Throwable t) {
                            if (this.tickCount % 20 == 0) {
                                LOG.warn("[RavenEntity] TeleportSequence destination FX failed safely: {}", t.toString());
                            }
                        }

                        if (this.tickCount % 20 == 0) {
                            LOG.info("[RavenEntity] TeleportSequence TELEPORTED reason={} teleported={} from={} newPos={} (holdBefore={} holdAfter={})",
                                    teleportSeqReason, teleported, before, this.position(), holdBefore, holdAfter);
                        }
                    }

                    if (teleportSeqTicks >= endOfAfterHoldTick) {
                        teleportSeqPhase = TeleportSeqPhase.FADING_IN;
                        teleportSeqTicks = 0;
                        setTeleportFadeAlpha(0); // start fade-in from fully invisible
                    }
                }

                case FADING_IN -> {
                    int t = teleportSeqTicks;
                    int total = Math.max(1, TELEPORT_FADE_TICKS_IN);

                    float k = Mth.clamp((float) t / (float) total, 0.0F, 1.0F);
                    int alpha = (int) Mth.lerp(k, 0.0F, 255.0F);
                    setTeleportFadeAlpha(alpha);

                    if (t >= total) {
                        setTeleportFadeAlpha(255);

                        TeleportSeqPhase old = teleportSeqPhase;
                        teleportSeqPhase = TeleportSeqPhase.NONE;
                        teleportSeqTicks = 0;

                        Vec3 oldTarget = teleportSeqTarget;
                        String oldReason = teleportSeqReason;

                        teleportSeqTarget = null;
                        teleportSeqReason = null;

                        endTeleportPhase("sequence done");

                        stuckTicks = 0;
                        lastDistToTarget = Double.NaN;
                        avoidanceCooldownTicks = 0;

                        reissueMovementIntentAfterTeleport(oldReason == null ? "teleport sequence" : oldReason);

                        if (this.tickCount % 20 == 0) {
                            LOG.info("[RavenEntity] TeleportSequence END phase={} reason={} target={}", old, oldReason, oldTarget);
                        }
                    }
                }

                default -> {
                }
            }
        } catch (Throwable t) {
            LOG.error("[RavenEntity] tickTeleportSequenceServer failed", t);

            teleportSeqPhase = TeleportSeqPhase.NONE;
            teleportSeqTarget = null;
            teleportSeqReason = null;
            teleportSeqTicks = 0;

            setTeleportFadeAlpha(255);
            endTeleportPhase("failsafe tickTeleportSequenceServer");
        }
    }

    public boolean attemptTeleportRecovery(String reason) {
        try {
            if (this.level().isClientSide) return false;
            if (!(this.level() instanceof ServerLevel serverLevel)) return false;

            if (!isTeleportRecoveryEligible()) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] TeleportRecovery skipped (not eligible). reason={} state={} pos={}",
                            reason, getAIState(), this.position());
                }
                return false;
            }

            // If already doing a teleport fade/sequence, don't stack.
            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                if (this.tickCount % 20 == 0) {
                    LOG.debug("[RavenEntity] TeleportRecovery ignored (sequence active). phase={} reason={}", teleportSeqPhase, reason);
                }
                return true; // treat as "handled"
            }

            BlockPos targetPos = findNearbyEmptyTeleportBlock();
            if (targetPos == null) {
                if (this.tickCount % 20 == 0) {
                    LOG.warn("[RavenEntity] TeleportRecovery: no empty 1x1x1 found near pos={} reason={}", this.position(), reason);
                }
                return false;
            }

            Vec3 end = new Vec3(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D);

            long fxSeed = this.getUUID().getLeastSignificantBits() ^ (long) this.tickCount ^ targetPos.asLong();

            // Start fade-out -> teleport -> fade-in, particles run during the window.
            startTeleportSequence(end, fxSeed, reason);

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] TeleportRecovery armed sequence reason={} targetBlock={} endPos={}",
                        reason, targetPos, end);
            }

            return true;

        } catch (Throwable t) {
            LOG.error("[RavenEntity] attemptTeleportRecovery failed (reason={})", reason, t);

            // failsafe restore
            teleportSeqPhase = TeleportSeqPhase.NONE;
            teleportSeqTarget = null;
            teleportSeqReason = null;
            teleportSeqTicks = 0;

            setTeleportFadeAlpha(255);
            endTeleportPhase("failsafe attemptTeleportRecovery");

            return false;
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




    /**
     * Checks a 3x3x3 volume centered on anchor.xz, spanning:
     *   x: [anchorX-1 .. anchorX+1]
     *   z: [anchorZ-1 .. anchorZ+1]
     *   y: [anchorY .. anchorY+2]
     *
     * All blocks must be empty AND fluid-free.
     */
    private boolean isEmptyTeleportPocket3x3x3At(BlockPos anchor) {
        try {
            if (anchor == null) return false;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos p = anchor.offset(dx, dy, dz);

                        if (!this.level().isEmptyBlock(p)) {
                            return false;
                        }
                        if (!this.level().getFluidState(p).isEmpty()) {
                            return false;
                        }
                    }
                }
            }

            return true;

        } catch (Throwable t) {
            if (this.tickCount % 120 == 0) {
                LOG.warn("[RavenEntity] isEmptyTeleportPocket3x3x3At failed safely: {}", t.toString());
            }
            return false;
        }
    }

    // ------------------
    // ------------------
    // ------------------

    @Nullable
    private BlockPos findNearbyEmptyTeleportBlock() {
        try {
            // Use current block as center
            BlockPos base = this.blockPosition();
            RandomSource rnd = this.getRandom();

            // We will sample random offsets; keep y within a small band so we don’t teleport into caves/sky.
            for (int i = 0; i < TELEPORT_MAX_CANDIDATES; i++) {
                int rx = rnd.nextInt(TELEPORT_MAX_SEARCH_RADIUS * 2 + 1) - TELEPORT_MAX_SEARCH_RADIUS;
                int rz = rnd.nextInt(TELEPORT_MAX_SEARCH_RADIUS * 2 + 1) - TELEPORT_MAX_SEARCH_RADIUS;
                int ry = rnd.nextInt(5) - 2; // [-2..+2]

                BlockPos p = base.offset(rx, ry, rz);

                // Must be empty at p
                if (!this.level().isEmptyBlock(p)) continue;

                // Must be empty above (headroom)
                if (!this.level().isEmptyBlock(p.above())) continue;

                // Must not be out of home bounds (optional but consistent with your design)
                Vec3 center = new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D);
                if (isOutOfHomeBounds(center)) continue;

                // Avoid teleporting into liquids (cheap check)
                if (!this.level().getFluidState(p).isEmpty()) continue;

                // Slight preference: don't place inside leaves
                BlockState below = this.level().getBlockState(p.below());
                if (below != null && below.is(BlockTags.LEAVES)) {
                    // fine, allowed; but we prefer non-leaf below unless in canopy behavior
                    // we won't reject it.
                }

                return p;
            }

            return null;
        } catch (Throwable t) {
            if (this.tickCount % 60 == 0) {
                LOG.warn("[RavenEntity] findNearbyEmptyTeleportBlock failed: {}", t.toString());
            }
            return null;
        }
    }

    private void startTeleportFxServer(long seed, @Nullable Vec3 startPos, @Nullable Vec3 endPos) {
        try {
            // Keep entity data updated so clients can still reference seed if needed.
            this.entityData.set(DATA_TELEPORT_FX_SEED, seed);
            this.entityData.set(DATA_TELEPORT_FX_TICKS, 0);

            this.teleportFxServerSeed = seed;

            // IMPORTANT:
            // We allow either origin to be null so we can schedule A first, then B later.
            if (startPos != null) {
                this.teleportFxOriginA = startPos.add(0.0D, 0.6D, 0.0D);
            }
            if (endPos != null) {
                this.teleportFxOriginB = endPos.add(0.0D, 0.6D, 0.0D);
            }

            int min = Math.min(TELEPORT_FX_BURST_MIN, TELEPORT_FX_BURST_MAX);
            int max = Math.max(TELEPORT_FX_BURST_MIN, TELEPORT_FX_BURST_MAX);

            int bursts;
            if (min == max) {
                bursts = min;
            } else {
                RandomSource rnd = RandomSource.create(seed ^ 0xC0FFEE1234ABCDEFL);
                bursts = min + rnd.nextInt(Math.max(1, max - min + 1));
            }

            bursts = Mth.clamp(bursts, 1, 12);

            // If FX is already running, just extend bursts rather than resetting visuals abruptly.
            if (this.teleportFxBurstsRemaining > 0) {
                this.teleportFxBurstsRemaining = Mth.clamp(this.teleportFxBurstsRemaining + bursts, 1, 12);
                this.teleportFxNextBurstInTicks = Math.min(this.teleportFxNextBurstInTicks, 1);
            } else {
                this.teleportFxBurstsRemaining = bursts;
                this.teleportFxNextBurstInTicks = 0; // first burst immediately
            }

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] TeleportFX scheduled: id={} bursts={} seed={} originA={} originB={}",
                        this.getId(), this.teleportFxBurstsRemaining, seed, this.teleportFxOriginA, this.teleportFxOriginB);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] startTeleportFxServer failed safely: {}", t.toString());
            teleportFxBurstsRemaining = 0;
            teleportFxNextBurstInTicks = 0;
            teleportFxOriginA = null;
            teleportFxOriginB = null;
        }
    }

    public int getTeleportFxTicks() {
        return this.entityData.get(DATA_TELEPORT_FX_TICKS);
    }

    public long getTeleportFxSeed() {
        return this.entityData.get(DATA_TELEPORT_FX_SEED);
    }

    private void reissueMovementIntentAfterTeleport(String reason) {
        try {
            // Capture & reset intent so it does not leak into later teleports.
            PostTeleportIntent intent = this.postTeleportIntent;
            this.postTeleportIntent = PostTeleportIntent.PERCH;

            if (intent == PostTeleportIntent.ROAM_FLIGHT) {
                // --------------------------------------------
                // DAMAGE BLINK (or anything else that set ROAM)
                // Requirement:
                //  - After teleport ends, force it into roaming flight.
                // --------------------------------------------
                clearFlyTarget();
                clearPlannedPath("post-teleport ROAM: " + reason);

                setAIState(RavenAIState.ROAM_FLY);

                // Enable flight
                this.setNoGravity(true);
                if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                    this.setAnimMode(RavenAnimMode.IN_AIR);
                }

                // Cancel landing so we don't immediately try to perch again.
                resetLandingState("post-teleport roam reset landing");
                landingLeafPos = null;

                // Start a roam window (this already picks a roam target + path/flyTarget).
                // This is the most stable way to get it moving and avoid instant re-perch loops.
                roamTicksRemaining = 0;
                beginRoamFlightWindow("post-teleport roam: " + reason);

                // Also clear “stuck/avoidance” bookkeeping
                avoidanceCooldownTicks = 0;
                stuckTicks = 0;
                lastDistToTarget = Double.NaN;

                if (this.tickCount % 20 == 0) {
                    LOG.info("[RavenEntity] PostTeleport: intent=ROAM_FLIGHT reason={} pos={} roamTicksRemaining={} flyTarget={} pathGoal={} pathPts={}",
                            reason, this.position(), roamTicksRemaining, flyTarget, pathGoal, (pathWaypoints == null ? 0 : pathWaypoints.size()));
                }

                return;
            }

            // --------------------------------------------
            // DEFAULT (existing behavior): PERCH MODE
            // --------------------------------------------
            // After teleport, we do NOT resume roam/follow goals.
            // Requirement previously: always go into perching mode: find spot, commit to landing, then idle.

            clearFlyTarget();
            clearPlannedPath("post-teleport: " + reason);

            // Force the AI into ROAM_FLY so landing state machine is active.
            setAIState(RavenAIState.ROAM_FLY);

            // Cancel roam window so we are allowed to start landing immediately.
            roamTicksRemaining = 0;

            resetLandingState("post-teleport reset landing");
            avoidanceCooldownTicks = 0;
            stuckTicks = 0;
            lastDistToTarget = Double.NaN;

            net.minecraft.util.RandomSource rnd = this.getRandom();

            net.minecraft.core.BlockPos perchCorner = pickLandingLeafBlock(rnd); // returns perchCornerTop
            if (perchCorner == null) {
                // Fallback: if we can't find a perch spot, do a short roam so we don't freeze.
                net.minecraft.world.phys.Vec3 roamTarget = pickRoamFallbackTarget(rnd);
                if (roamTarget != null) {
                    long seed = this.getUUID().getLeastSignificantBits() ^ (long) this.tickCount ^ 0x51CED00DL;
                    boolean ok = ensurePathTo(roamTarget, 4 * 20, seed, "post-teleport fallback roam");
                    if (!ok) {
                        setFlyTarget(roamTarget, 4 * 20);
                    }
                } else {
                    clearFlyTarget();
                    this.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                }

                if (this.tickCount % 20 == 0) {
                    LOG.warn("[RavenEntity] PostTeleport: no perch found -> fallback roam. reason={} pos={}", reason, this.position());
                }
                return;
            }

            // Commit to landing on that perch
            landingLeafPos = perchCorner;
            landingPhase = LandingPhase.FLY_TO_OVERHEAD;
            landingTicks = 0;

            net.minecraft.world.phys.Vec3 overhead = overheadTargetForLeaf(perchCorner);

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            clearPlannedPath("post-teleport landing -> plan overhead");
            long seed = this.getUUID().getMostSignificantBits() ^ (long) this.tickCount ^ perchCorner.asLong() ^ 0xBADC0DEL;
            boolean ok = ensurePathTo(overhead, 6 * 20, seed, "post-teleport FLY_TO_OVERHEAD");
            if (!ok) {
                setFlyTarget(overhead, 6 * 20);
            }

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] PostTeleport: committed landing. reason={} perchCorner={} overhead={} pathOk={}",
                        reason, perchCorner, overhead, ok);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] reissueMovementIntentAfterTeleport failed (reason={})", reason, t);
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

    /**
     * Public entrypoint for "player avoidance".
     * This is the ONLY method the helper needs.
     *
     * Requirements:
     *  - If any player is within range, force ROAM_FLY
     *  - Cancel landing/perch intent
     *  - Always try to fly away from the player, but keep within home bounds
     *  - Uses your internal pathing/flyTarget logic (private methods stay private)
     */
    public void requestPlayerAvoidanceFleeTarget(@org.jetbrains.annotations.Nullable Player player, double distToPlayer) {
        try {
            if (this.level().isClientSide) {
                return;
            }

            // -----------------------------
            // FOLLOW OVERRIDE: do not allow avoidance to hijack follow behavior
            // (stuck teleport remains allowed separately by your sampler)
            // -----------------------------
            if (followOverrideActive && getAIState() == RavenAIState.FOLLOW_OWNER) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] PlayerAvoidance ignored due to FOLLOW_OWNER override. player={} dist={} pos={}",
                            (player == null ? "null" : player.getName().getString()),
                            String.format("%.2f", distToPlayer),
                            this.position());
                }
                return;
            }

            // -----------------------------
            // LURE-FOLLOW OVERRIDE: do not allow avoidance while lured
            // (stuck teleport remains allowed separately by your sampler)
            // -----------------------------
            if (isLureFollowActive()) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] requestPlayerAvoidanceFleeTarget suppressed (lure-follow active). player={} dist={} pos={}",
                            (player == null ? "null" : player.getName().getString()),
                            String.format("%.2f", distToPlayer),
                            this.position());
                }
                return;
            }

            if (player == null) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] requestPlayerAvoidanceFleeTarget: player=null (skip)");
                }
                return;
            }
            if (!player.isAlive() || player.isSpectator()) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] requestPlayerAvoidanceFleeTarget: player not valid (alive={} spectator={}) name={}",
                            player.isAlive(), player.isSpectator(), player.getName().getString());
                }
                return;
            }

            // If teleport sequence is active, do NOT try to arm avoidance.
            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] requestPlayerAvoidanceFleeTarget: teleportSeqPhase={} (skip)", teleportSeqPhase);
                }
                return;
            }

            // -----------------------------
            // HARD RATE LIMIT
            // -----------------------------
            final int REPLAN_MIN_INTERVAL_TICKS = 20; // 1s
            if (playerAvoidanceRearmCooldownTicks > 0) {
                // Within cooldown: do not replan. Let existing intent run.
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] PlayerAvoidance: rearm cooldown {}t left (skip replan) dist={}",
                            playerAvoidanceRearmCooldownTicks, String.format("%.2f", distToPlayer));
                }
                return;
            }

            // Are we already fleeing?
            boolean alreadyOverriding = playerAvoidanceOverrideTicks > 0;

            boolean hasFlyIntent = (flyTarget != null && flyTargetTimeoutTicks > 0);
            boolean hasPathIntent = (pathWaypoints != null && !pathWaypoints.isEmpty() && pathWaypointIndex < pathWaypoints.size());
            boolean hasAnyIntent = hasFlyIntent || hasPathIntent;

            boolean collisionsNow = this.horizontalCollision || this.verticalCollision;
            boolean panicRefresh = (distToPlayer >= 0.0D && distToPlayer < 6.0D);

            boolean shouldReplanWhileOverriding =
                    !hasAnyIntent
                            || collisionsNow
                            || panicRefresh
                            || (stuckTicks >= STUCK_TICKS_THRESHOLD);

            // If already overriding and stable, do NOT replan.
            if (alreadyOverriding && !shouldReplanWhileOverriding) {
                int minKeep = 40; // keep 2s buffer so it doesn't drop mid-flee
                if (playerAvoidanceOverrideTicks < minKeep) {
                    playerAvoidanceOverrideTicks = minKeep;
                }

                playerAvoidanceRearmCooldownTicks = REPLAN_MIN_INTERVAL_TICKS;

                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] PlayerAvoidance: stable override -> no replan. dist={} overrideTicks={} flyIntent={} pathIntent={} pos={} flyTarget={}",
                            String.format("%.2f", distToPlayer),
                            playerAvoidanceOverrideTicks,
                            hasFlyIntent,
                            hasPathIntent,
                            this.position(),
                            flyTarget
                    );
                }
                return;
            }

            // -----------------------------
            // Compute a stable flee target
            // -----------------------------
            Vec3 fleeTarget = computePlayerAvoidanceFleeTarget(player);
            if (fleeTarget == null) {
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] PlayerAvoidance: fleeTarget=null (skip) player={} dist={}",
                            player.getName().getString(), String.format("%.2f", distToPlayer));
                }
                playerAvoidanceRearmCooldownTicks = REPLAN_MIN_INTERVAL_TICKS;
                return;
            }

            // Clamp to home bounds (existing helper)
            fleeTarget = clampTargetToHomeBounds(fleeTarget);

            // -----------------------------
            // Arm override (only when actually rearming)
            // -----------------------------
            final int OVERRIDE_TICKS = 8 * 20;
            playerAvoidanceOverrideTicks = OVERRIDE_TICKS;
            playerAvoidanceRearmCooldownTicks = REPLAN_MIN_INTERVAL_TICKS;

            // Cancel landing/perch intent; force flight
            resetLandingState("player avoidance arm");
            landingPhase = LandingPhase.NONE;
            landingLeafPos = null;
            landingTicks = 0;

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            // Clear idle locks so we don't fight ourselves
            roamTicksRemaining = 0;
            idleLockTicks = 0;
            idleLeafLossTicks = 0;

            // -----------------------------
            // CRITICAL FIX FOR "UP/DOWN BUZZING":
            // Close-range avoidance uses DIRECT fly target (no A* safe-goal probing).
            // -----------------------------
            final double CLOSE_RANGE_DIRECT_FLEE_DIST = 10.0D; // tune 8..14
            boolean closeRange = (distToPlayer >= 0.0D && distToPlayer <= CLOSE_RANGE_DIRECT_FLEE_DIST);

            // Always clear planned path when arming avoidance to avoid path-vs-fly fighting.
            clearPlannedPath("player avoidance arm");
            pathGoal = null;
            pathPendingGoal = null;
            pathWaypoints = null;
            pathWaypointIndex = 0;
            pathRetryCooldownTicks = 0;

            boolean pathOk = false;

            if (closeRange) {
                setFlyTarget(fleeTarget, 8 * 20);
                pathOk = false;

                if (this.tickCount % 20 == 0) {
                    LOG.info("[RavenEntity] PlayerAvoidance armed (CLOSE-RANGE DIRECT): player={} dist={} target={} overrideTicks={} rearmCd={} pos={} playerPos={}",
                            player.getName().getString(),
                            String.format("%.2f", distToPlayer),
                            fleeTarget,
                            playerAvoidanceOverrideTicks,
                            playerAvoidanceRearmCooldownTicks,
                            this.position(),
                            player.position());
                }
                return;
            }

            // Non-close range: use A* (preferred)
            long seed =
                    this.getUUID().getLeastSignificantBits()
                            ^ (long) this.tickCount
                            ^ player.getUUID().getMostSignificantBits()
                            ^ 0xC0FFEE1234ABL;

            String reason = "player avoidance: player=" + player.getName().getString() + " dist=" + String.format("%.2f", distToPlayer);

            try {
                pathOk = ensurePathTo(fleeTarget, true, 8 * 20, seed, reason);
            } catch (Throwable t) {
                pathOk = false;
                if (this.tickCount % 20 == 0) {
                    LOG.warn("[RavenEntity] PlayerAvoidance ensurePathTo failed safely: {}", t.toString());
                }
            }

            if (!pathOk) {
                setFlyTarget(fleeTarget, 8 * 20);
            } else {
                pathPendingGoal = fleeTarget;
            }

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] PlayerAvoidance armed: reason={} target={} pathOk={} overrideTicks={} rearmCd={} pos={} vel={} playerPos={}",
                        reason,
                        fleeTarget,
                        pathOk,
                        playerAvoidanceOverrideTicks,
                        playerAvoidanceRearmCooldownTicks,
                        this.position(),
                        this.getDeltaMovement(),
                        player.position());
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] requestPlayerAvoidanceFleeTarget failed safely", t);
            try {
                playerAvoidanceRearmCooldownTicks = Math.max(playerAvoidanceRearmCooldownTicks, 10);
            } catch (Throwable ignored) {
            }
        }
    }

    @org.jetbrains.annotations.Nullable
    private Vec3 computePlayerAvoidanceFleeTarget(Player player) {
        try {
            if (player == null) return null;

            final Vec3 ravenPos = this.position();
            final Vec3 playerPos = player.position();

            // -----------------------------
            // 1) Direction away from player in XZ
            // -----------------------------
            double dx = ravenPos.x - playerPos.x;
            double dz = ravenPos.z - playerPos.z;

            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-4D) {
                // Degenerate case: same XZ. Choose a random horizontal direction.
                RandomSource rnd = this.getRandom();
                double ang = rnd.nextDouble() * (Math.PI * 2.0D);
                dx = Math.cos(ang);
                dz = Math.sin(ang);
                len = 1.0D;
            }

            double nx = dx / len;
            double nz = dz / len;

            // -----------------------------
            // 2) Horizontal flee distance
            // -----------------------------
            final double FLEE_DIST = 24.0D; // tune 18..34
            double tx = ravenPos.x + nx * FLEE_DIST;
            double tz = ravenPos.z + nz * FLEE_DIST;

            // -----------------------------
            // 3) Y POLICY (ANTI-BOBBING)
            //
            // The vertical bobbing you described is almost always caused by:
            //  - producing targets with varying Y over and over (heightmap / empty searches up/down)
            //  - A* "safe goal" adjustments choosing different dy candidates over successive replans
            //
            // So for player avoidance we LOCK a stable Y band:
            //  - Primary: stay near current Y (slightly lifted so flight pathing stays in air)
            //  - If player is above us, bias down a bit (but still stable)
            //  - Absolutely avoid heightmap-based "snap Y" here
            // -----------------------------
            boolean playerAbove = playerPos.y > ravenPos.y + 1.25D;

            double baseY = ravenPos.y;

            // Gentle down-bias if player is above (still stable, not a search).
            double desiredDrop = playerAbove ? 3.0D : 0.75D;

            double tyRaw = baseY - desiredDrop;

            // Clamp Y to home bounds using your existing int clamp, then convert back to a stable flight Y.
            // We deliberately keep it stable (no scanning up/down).
            int tyInt = clampYToHomeBounds(Mth.floor(tyRaw));
            double ty = tyInt + 0.75D; // keep in-air, stable

            // -----------------------------
            // 4) Find an empty-ish target cell WITHOUT changing Y all the time
            //
            // We try:
            //  - exact (tx,tz) at stable ty
            //  - small horizontal spiral (same Y)
            //  - finally try ty+1 and ty-1 once each (still bounded, avoids oscillation)
            // -----------------------------
            BlockPos base = BlockPos.containing(tx, ty, tz);

            BlockPos chosen = null;

            // Helper lambda-style logic (manual, no new methods).
            // First attempt: base spot.
            if (this.level().isEmptyBlock(base) && this.level().getFluidState(base).isEmpty()) {
                chosen = base;
            }

            // Horizontal spiral at same Y (cheap, stable)
            if (chosen == null) {
                final int R = 4; // 4 blocks radius
                int y = base.getY();

                // simple square-ring scan
                for (int r = 1; r <= R && chosen == null; r++) {
                    // perimeter of square [-r..r] x [-r..r]
                    for (int ox = -r; ox <= r && chosen == null; ox++) {
                        int ozA = -r;
                        int ozB = r;

                        BlockPos p1 = new BlockPos(base.getX() + ox, y, base.getZ() + ozA);
                        if (this.level().isEmptyBlock(p1) && this.level().getFluidState(p1).isEmpty()) {
                            chosen = p1;
                            break;
                        }

                        if (ozB != ozA) {
                            BlockPos p2 = new BlockPos(base.getX() + ox, y, base.getZ() + ozB);
                            if (this.level().isEmptyBlock(p2) && this.level().getFluidState(p2).isEmpty()) {
                                chosen = p2;
                                break;
                            }
                        }
                    }

                    for (int oz = -r + 1; oz <= r - 1 && chosen == null; oz++) {
                        int oxA = -r;
                        int oxB = r;

                        BlockPos p1 = new BlockPos(base.getX() + oxA, y, base.getZ() + oz);
                        if (this.level().isEmptyBlock(p1) && this.level().getFluidState(p1).isEmpty()) {
                            chosen = p1;
                            break;
                        }

                        if (oxB != oxA) {
                            BlockPos p2 = new BlockPos(base.getX() + oxB, y, base.getZ() + oz);
                            if (this.level().isEmptyBlock(p2) && this.level().getFluidState(p2).isEmpty()) {
                                chosen = p2;
                                break;
                            }
                        }
                    }
                }
            }

            // One-shot vertical nudge (bounded; prevents "pick different Y every time")
            if (chosen == null) {
                BlockPos up = base.above(1);
                if (this.level().isEmptyBlock(up) && this.level().getFluidState(up).isEmpty()) {
                    chosen = up;
                }
            }
            if (chosen == null) {
                BlockPos down = base.below(1);
                if (this.level().isEmptyBlock(down) && this.level().getFluidState(down).isEmpty()) {
                    chosen = down;
                }
            }

            // Absolute fallback: keep stable Y and just use the target XZ (even if not empty),
            // because ensurePathTo() / collision logic can still route around.
            if (chosen == null) {
                chosen = base;
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] computePlayerAvoidanceFleeTarget: no empty spot found near base={} (using base as fallback) ravenPos={} playerPos={} playerAbove={}",
                            base, ravenPos, playerPos, playerAbove);
                }
            }

            Vec3 out = new Vec3(chosen.getX() + 0.5D, ty, chosen.getZ() + 0.5D);

            if (this.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] computePlayerAvoidanceFleeTarget: ravenPos={} playerPos={} playerAbove={} out={} baseY={} tyRaw={} tyStable={}",
                        ravenPos, playerPos, playerAbove, out,
                        String.format("%.2f", baseY),
                        String.format("%.2f", tyRaw),
                        String.format("%.2f", ty));
            }

            return out;

        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                LOG.warn("[RavenEntity] computePlayerAvoidanceFleeTarget failed safely: {}", t.toString());
            }
            return null;
        }
    }

    public void requestPanicTeleportAwayFromPlayer(@org.jetbrains.annotations.Nullable Player player, double distToPlayer) {
        try {
            if (this.level().isClientSide) return;
            if (!this.isAlive()) return;

            // -----------------------------
            // FOLLOW/LURE OVERRIDE: do not allow panic teleport to hijack follow behavior
            // (stuck teleport remains allowed separately by your sampler)
            // -----------------------------
            if ((LureFollowTame.followOverrideActive && getAIState() == RavenAIState.FOLLOW_OWNER) || LureFollowTame.isLureFollowActive()) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] requestPanicTeleportAwayFromPlayer suppressed (follow/lure active). player={} dist={} pos={} ai={} followOverride={} lureActive={}",
                            (player == null ? "null" : player.getName().getString()),
                            String.format("%.2f", distToPlayer),
                            this.position(),
                            getAIState(),
                            LureFollowTame.followOverrideActive,
                            LureFollowTame.isLureFollowActive());
                }
                return;
            }

            if (player == null) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] requestPanicTeleportAwayFromPlayer: player=null (skip)");
                }
                return;
            }
            if (!player.isAlive() || player.isSpectator()) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] requestPanicTeleportAwayFromPlayer: invalid player (alive={} spectator={}) name={}",
                            player.isAlive(), player.isSpectator(), player.getName().getString());
                }
                return;
            }

            // Never stack with an active teleport sequence.
            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] requestPanicTeleportAwayFromPlayer: teleportSeqPhase={} (skip)", teleportSeqPhase);
                }
                return;
            }

            // IMPORTANT FIX #1: do NOT use teleportCooldownTicks here.
            // teleportCooldownTicks is shared with stuck/blink logic, and it frequently blocks panic teleport,
            // causing the "I stand inside it for 1-2s before it finally teleports" delay.
            // Instead, use a dedicated interval based on lastPanicTeleportTick.
            final int PANIC_MIN_INTERVAL_TICKS = 30; // 1.5s; tune 10..40. This prevents spam but keeps it snappy.
            int dt = this.tickCount - this.lastPanicTeleportTick;
            if (dt >= 0 && dt < PANIC_MIN_INTERVAL_TICKS) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] PanicTeleport suppressed by interval: dt={} < {} dist={} player={} pos={}",
                            dt, PANIC_MIN_INTERVAL_TICKS, String.format("%.2f", distToPlayer), player.getName().getString(), this.position());
                }
                return;
            }

            final Vec3 ravenPos = this.position();
            final Vec3 playerPos = player.position();

            // IMPORTANT FIX #2: teleport BEHIND the player (rear 180° cone), not simply away from player.
            // "Behind" is relative to player's look direction (front 180° vs back 180°).
            Vec3 look = player.getLookAngle();
            double lx = look.x;
            double lz = look.z;
            double lLen = Math.sqrt(lx * lx + lz * lz);

            // If look vector is degenerate (rare), pick something stable-ish.
            if (lLen < 1.0E-4D) {
                RandomSource rnd = this.getRandom();
                double ang = rnd.nextDouble() * (Math.PI * 2.0D);
                lx = Math.cos(ang);
                lz = Math.sin(ang);
                lLen = 1.0D;
            }

            // Base "behind" direction = -look (XZ only)
            double bx = -lx / lLen;
            double bz = -lz / lLen;

            // Random angle within the 180° behind cone: rotate behind vector by [-90°, +90°]
            // This keeps it in the rear hemisphere.
            RandomSource rnd = this.getRandom();
            double yawOffset = (rnd.nextDouble() * Math.PI) - (Math.PI * 0.5D); // [-pi/2 .. +pi/2]

            double cos = Math.cos(yawOffset);
            double sin = Math.sin(yawOffset);

            // Rotate (bx,bz) around Y by yawOffset:
            // x' = x*cos - z*sin
            // z' = x*sin + z*cos
            double rx = bx * cos - bz * sin;
            double rz = bx * sin + bz * cos;

            double rLen = Math.sqrt(rx * rx + rz * rz);
            if (rLen < 1.0E-4D) {
                rx = bx;
                rz = bz;
                rLen = 1.0D;
            }
            rx /= rLen;
            rz /= rLen;

            final double TELEPORT_DIST = 30.0D;

            // Destination is computed relative to PLAYER (so it tends to "appear behind you"),
            // not relative to raven.
            double tx = playerPos.x + rx * TELEPORT_DIST;
            double tz = playerPos.z + rz * TELEPORT_DIST;

            // Y policy: keep it near raven's current Y band (stable, avoids bobbing),
            // then clamp into home bounds via existing helpers.
            int tyInt = clampYToHomeBounds(Mth.floor(ravenPos.y));
            double ty = tyInt + 0.75D;

            Vec3 raw = new Vec3(tx, ty, tz);
            Vec3 clamped = clampTargetToHomeBounds(raw);

            BlockPos center = BlockPos.containing(clamped.x, clamped.y, clamped.z);

            long seed =
                    this.getUUID().getLeastSignificantBits()
                            ^ (long) this.tickCount
                            ^ player.getUUID().getMostSignificantBits()
                            ^ center.asLong()
                            ^ 0x5AC1F1EDBEEFL;

            // Prefer a 3x3x3 pocket near the behind-player destination.
            BlockPos targetBlock = findEmptyTeleportBlock3x3x3Near(center, 10, 260, seed);

            // Fallback 1: any 3x3x3 pocket near current position.
            if (targetBlock == null) {
                try {
                    targetBlock = findNearbyEmptyTeleportBlock3x3x3(10, 90);
                } catch (Throwable ignored) {
                    targetBlock = null;
                }
            }

            // Fallback 2: last resort 1x1x1.
            if (targetBlock == null) {
                try {
                    targetBlock = findNearbyEmptyTeleportBlock();
                } catch (Throwable ignored) {
                    targetBlock = null;
                }
            }

            if (targetBlock == null) {
                if (this.tickCount % 20 == 0) {
                    LOG.warn("[RavenEntity] PanicTeleport: no valid teleport target found. player={} dist={} ravenPos={} raw={} clamped={}",
                            player.getName().getString(),
                            String.format("%.2f", distToPlayer),
                            ravenPos,
                            raw,
                            clamped);
                }
                // Still update last tick to prevent hammering heavy scans every single tick while you stand inside it.
                this.lastPanicTeleportTick = this.tickCount;
                return;
            }

            Vec3 end = new Vec3(targetBlock.getX() + 0.5D, targetBlock.getY(), targetBlock.getZ() + 0.5D);

            // Home bounds guard (should already be enforced in findEmptyTeleportBlock3x3x3Near).
            if (isOutOfHomeBounds(end)) {
                if (this.tickCount % 20 == 0) {
                    LOG.warn("[RavenEntity] PanicTeleport: selected end out of home bounds. end={} home={} radius={}",
                            end, getHomePosPublic(), getHomeRadiusBlocksPublic());
                }
                this.lastPanicTeleportTick = this.tickCount;
                return;
            }

            // IMPORTANT FIX #3: after panic teleport, force ROAM_FLIGHT (test to eliminate the post-teleport "perching bopping").
            // This makes it immediately resume stable flight behavior, and then your normal avoidance logic works cleanly.
            this.postTeleportIntent = PostTeleportIntent.ROAM_FLIGHT;

            // Cancel current movement intent so teleport doesn't fight A* / flyTarget.
            clearFlyTarget();
            clearPlannedPath("panic teleport");

            // Also clear avoidance override state so we don't get weird half-armed logic after teleport.
            try {
                playerAvoidanceOverrideTicks = 0;
                playerAvoidanceRearmCooldownTicks = 20; // small buffer
            } catch (Throwable ignored) {}

            long fxSeed =
                    this.getUUID().getLeastSignificantBits()
                            ^ (long) this.tickCount
                            ^ targetBlock.asLong()
                            ^ player.getUUID().getMostSignificantBits()
                            ^ 0xD15C0FFEE0DDF00DL;

            String reason = "panic teleport behind: player=" + player.getName().getString()
                    + " dist=" + String.format("%.2f", distToPlayer)
                    + " yawOffsetDeg=" + String.format("%.1f", (yawOffset * (180.0D / Math.PI)));

            // Mark interval now so we don't double-fire in the same close-contact moment.
            this.lastPanicTeleportTick = this.tickCount;

            startTeleportSequence(end, fxSeed, reason);

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] PanicTeleport STARTED: reason={} ravenPos={} playerPos={} behindDir=({}, {}) dist={} raw={} clamped={} targetBlock={} end={} fxSeed={}",
                        reason,
                        ravenPos,
                        playerPos,
                        String.format("%.3f", rx),
                        String.format("%.3f", rz),
                        String.format("%.1f", TELEPORT_DIST),
                        raw,
                        clamped,
                        targetBlock,
                        end,
                        fxSeed);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] requestPanicTeleportAwayFromPlayer failed safely", t);
            // Fail-safe: avoid hammering if something is broken.
            try {
                this.lastPanicTeleportTick = this.tickCount;
            } catch (Throwable ignored) {}
        }
    }

    @org.jetbrains.annotations.Nullable
    private BlockPos findEmptyTeleportBlock3x3x3Near(BlockPos center, int radiusBlocks, int maxCandidates, long seed) {
        try {
            if (center == null) return null;

            int r = Math.max(1, radiusBlocks);
            int candidates = Math.max(1, maxCandidates);

            RandomSource rnd = RandomSource.create(seed ^ 0xA11CE5ED1234L);

            int yBand = 4;

            for (int i = 0; i < candidates; i++) {
                int rx = rnd.nextInt(r * 2 + 1) - r;
                int rz = rnd.nextInt(r * 2 + 1) - r;
                int ry = rnd.nextInt(yBand * 2 + 1) - yBand;

                BlockPos p = center.offset(rx, ry, rz);

                if (!this.level().isEmptyBlock(p)) continue;
                if (!this.level().isEmptyBlock(p.above())) continue;
                if (!this.level().getFluidState(p).isEmpty()) continue;

                Vec3 pv = new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D);
                if (isOutOfHomeBounds(pv)) continue;

                boolean ok = true;
                for (int dx = -1; dx <= 1 && ok; dx++) {
                    for (int dz = -1; dz <= 1 && ok; dz++) {
                        for (int dy = 0; dy <= 2 && ok; dy++) {
                            BlockPos q = p.offset(dx, dy, dz);
                            if (!this.level().isEmptyBlock(q)) {
                                ok = false;
                                break;
                            }
                            if (!this.level().getFluidState(q).isEmpty()) {
                                ok = false;
                                break;
                            }
                        }
                    }
                }

                if (!ok) continue;

                return p;
            }

            if (this.tickCount % 60 == 0) {
                LOG.debug("[RavenEntity] findEmptyTeleportBlock3x3x3Near: no pocket found center={} r={} candidates={} seed={}",
                        center, r, candidates, seed);
            }

            return null;

        } catch (Throwable t) {
            if (this.tickCount % 60 == 0) {
                LOG.warn("[RavenEntity] findEmptyTeleportBlock3x3x3Near failed safely: {}", t.toString());
            }
            return null;
        }
    }

    // -----------------
    // More helpers..
    // -----------------

    public Vec3 getHomeCenterVec() {
        return new Vec3(
                homePos.getX() + 0.5D,
                homePos.getY() + 0.5D,
                homePos.getZ() + 0.5D
        );
    }

    public void forceRoamFlightFromThreat(Vec3 fleeTarget, net.minecraft.world.entity.player.Player threat) {
        try {
            // Cancel everything calm-related
            clearPlannedPath("player avoidance");
            clearFlyTarget();

            // Force roaming flight
            setAIState(RavenAIState.ROAM_FLY);
            roamTicksRemaining = 0;

            resetLandingState("player avoidance");
            idleCommitTicks = 0;
            idleLockTicks = 0;

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            long seed = this.getUUID().getLeastSignificantBits()
                    ^ (long) this.tickCount
                    ^ threat.getUUID().getMostSignificantBits()
                    ^ 0xF1EEBEEFL;

            boolean ok = ensurePathTo(fleeTarget, 6 * 20, seed, "player avoidance flee");

            if (!ok) {
                setFlyTarget(fleeTarget, 6 * 20);
            }

            // Prevent immediate re-trigger
            idleCommitTicks = 40;

        } catch (Throwable t) {
            LOG.error("[RavenEntity] forceRoamFlightFromThreat failed", t);
        }
    }

    public boolean requestDamageBlinkTeleport(@org.jetbrains.annotations.Nullable net.minecraft.world.damagesource.DamageSource source, float amount, String reasonTag) {
        try {
            if (this.level().isClientSide) return false;
            if (!this.isAlive()) return false;

            // If a teleport sequence is already active, we do NOT start another one.
            // But we still force the "after teleport -> roam flight" behavior.
            this.postTeleportIntent = PostTeleportIntent.ROAM_FLIGHT;

            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                if (this.tickCount % 20 == 0) {
                    LOG.debug("[RavenEntity] requestDamageBlinkTeleport: sequence already active; set postTeleportIntent=ROAM_FLIGHT reasonTag={} phase={} pos={}",
                            reasonTag, teleportSeqPhase, this.position());
                }
                return true; // handled/queued
            }

            // Anti-spam interval so repeated hits (fire tick, thorns, cactus) don't cause constant blinking.
            int dt = this.tickCount - lastDamageBlinkTick;
            if (dt < DAMAGE_BLINK_MIN_INTERVAL_TICKS) {
                if (this.tickCount % 20 == 0) {
                    LOG.debug("[RavenEntity] requestDamageBlinkTeleport: suppressed by interval dt={} < {} reasonTag={} pos={}",
                            dt, DAMAGE_BLINK_MIN_INTERVAL_TICKS, reasonTag, this.position());
                }
                return false;
            }
            lastDamageBlinkTick = this.tickCount;

            // We always want to "feel" like it dodged even when it still takes damage (25% case),
            // so we blink either way.

            // Prefer a 3x3x3 empty pocket for blink (clean visuals + no clipping),
            // fallback to your existing 1x1 findNearbyEmptyTeleportBlock().
            net.minecraft.core.BlockPos targetBlock = null;

            try {
                targetBlock = findNearbyEmptyTeleportBlock3x3x3(10, 80);
            } catch (Throwable ignored) {
                targetBlock = null;
            }

            if (targetBlock == null) {
                try {
                    targetBlock = findNearbyEmptyTeleportBlock();
                } catch (Throwable ignored) {
                    targetBlock = null;
                }
            }

            if (targetBlock == null) {
                if (this.tickCount % 20 == 0) {
                    LOG.warn("[RavenEntity] requestDamageBlinkTeleport: no valid teleport target found. reasonTag={} src={} amt={} pos={}",
                            reasonTag,
                            (source == null ? "null" : source.toString()),
                            amount,
                            this.position());
                }
                return false;
            }

            net.minecraft.world.phys.Vec3 end = new net.minecraft.world.phys.Vec3(
                    targetBlock.getX() + 0.5D,
                    targetBlock.getY(),
                    targetBlock.getZ() + 0.5D
            );

            // FX seed: stable-ish but varied.
            final long DAMAGE_BLINK_SALT = 0xD0D6E5EEDL; // valid hex
            long fxSeed =
                    this.getUUID().getLeastSignificantBits()
                            ^ (long) this.tickCount
                            ^ targetBlock.asLong()
                            ^ DAMAGE_BLINK_SALT
                            ^ (long) (Float.floatToIntBits(amount));

            // Start your existing fade/FX teleport sequence.
            startTeleportSequence(end, fxSeed, "damage blink: " + reasonTag);

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] requestDamageBlinkTeleport: STARTED reasonTag={} src={} amt={} fromPos={} toBlock={} end={} fxSeed={}",
                        reasonTag,
                        (source == null ? "null" : source.toString()),
                        amount,
                        this.position(),
                        targetBlock,
                        end,
                        fxSeed);
            }

            return true;

        } catch (Throwable t) {
            LOG.error("[RavenEntity] requestDamageBlinkTeleport failed reasonTag={}", reasonTag, t);
            return false;
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
                        requestDamageBlinkTeleport(source, amount, "invulnerable-hurt");
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

    private @Nullable BlockPos findBestPerchCornerForLanding(@Nullable BlockPos landingLeaf, BlockPos feetBlock) {
        try {
            BlockPos best = null;
            double bestD2 = Double.MAX_VALUE;

            // Candidate corners to try.
            // Priority:
            //  1) corners around the committed landingLeafPos (prevents “random other 2x2” selection)
            //  2) corners around feetBlock (fallback when landingLeaf is null or stale)
            BlockPos[] seeds;
            if (landingLeaf != null) {
                seeds = new BlockPos[] { landingLeaf, feetBlock };
            } else {
                seeds = new BlockPos[] { feetBlock };
            }

            for (BlockPos seed : seeds) {
                // A 2x2 perch corner could be at seed, seed-1x, seed-1z, seed-1x-1z depending on where we hit.
                // Try the 4 possible “NW corner” candidates around this seed.
                BlockPos[] corners = new BlockPos[] {
                        seed,
                        seed.west(),
                        seed.north(),
                        seed.west().north()
                };

                for (BlockPos c : corners) {
                    if (!isValidPerchCornerAtTopY(c)) {
                        continue;
                    }

                    Vec3 center = perchCenterTop(c);
                    double dx = this.getX() - center.x;
                    double dz = this.getZ() - center.z;
                    double d2 = dx * dx + dz * dz;

                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = c;
                    }
                }
            }

            return best;

        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] findBestPerchCornerForLanding failed safely: {}", t.toString());
            }
            return null;
        }
    }

    // neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenEntity.java
    private boolean isOnValidPerchNowRelaxedForLanding(String debugTag) {
        try {
            // Only makes sense when we are in "landing/resting physics".
            // If we are still flying (noGravity=true), do NOT use relaxed logic.
            if (this.isNoGravity()) {
                return false;
            }

            if (this.getBoundingBox() == null) {
                return false;
            }

            // We only want to do the "force commit" behavior during DROP.
            // Otherwise, preserve your stricter semantics.
            final boolean inDrop = (landingPhase == LandingPhase.DROP);

            // Probe block under feet (use bb minY)
            BlockPos feetBlock = BlockPos.containing(this.getX(), this.getBoundingBox().minY - 0.001D, this.getZ());

            // Step 1: pick the best perch corner.
            // Prefer corners around the committed landingLeafPos (prevents selecting a "different" nearby 2x2).
            BlockPos bestCorner = null;
            try {
                bestCorner = findBestPerchCornerForLanding(landingLeafPos, feetBlock);
            } catch (Throwable t) {
                // fail safe: fallback below
                bestCorner = null;
            }

            if (bestCorner == null) {
                bestCorner = findValidPerchCornerNearXZ(feetBlock.getX(), feetBlock.getZ());
            }

            if (bestCorner == null) {
                if (this.tickCount % 40 == 0) {
                    LOG.info("[RavenEntity] {}: relaxedPerch=false (no corner) pos={} feetBlock={} onGround={} vColl={} phase={} landingTicks={}",
                            debugTag, this.position(), feetBlock, this.onGround(), this.verticalCollision, landingPhase, landingTicks);
                }
                return false;
            }

            // Basic validity guard (cheap).
            if (!isValidPerchCornerAtTopY(bestCorner)) {
                if (this.tickCount % 40 == 0) {
                    LOG.info("[RavenEntity] {}: relaxedPerch=false (corner invalid) pos={} corner={} phase={} landingTicks={}",
                            debugTag, this.position(), bestCorner, landingPhase, landingTicks);
                }
                return false;
            }

            Vec3 center = perchCenterTop(bestCorner);
            Vec3 pos = this.position();

            double dx = pos.x - center.x;
            double dz = pos.z - center.z;
            double dXZ = Math.sqrt(dx * dx + dz * dz);

            // Normal relaxed threshold: only slightly relaxed vs strict perched checks.
            final double relaxedEps = Math.max(PERCH_CENTER_EPS, 0.35D);

            boolean withinRelaxed = dXZ <= relaxedEps;

            // ---------------------------------------------------------
            // NEW: "DROP COMMIT" SNAP
            // ---------------------------------------------------------
            // Problem shown in your logs:
            // - Raven is clearly perched physically (onGround + vColl, noGravity=false)
            // - But it is >1 block off from computed center, so it never commits to idle
            //
            // Solution:
            // - During DROP, after a short settle window, if we are on ground/colliding vertically,
            //   we snap to the nearest valid perch center and treat that as success.
            //
            // This avoids the endless "DROP -> fail -> takeoff -> new path" loop.
            boolean canForceCommit =
                    inDrop
                            && (this.onGround() || this.verticalCollision)
                            && landingTicks >= 20; // 1s settle time; tune 10..40 if needed

            if (!withinRelaxed && canForceCommit) {
                // Allow a fairly generous radius; if we're farther than this, we're probably not actually on that 2x2.
                // Your failures are ~1.2–1.27, so 1.8 gives headroom but stays sane.
                final double maxSnapRadius = 1.80D;

                // Also ensure we're not still moving fast; prevents snapping mid-flight edge cases.
                Vec3 vel = this.getDeltaMovement();
                double speedSqr = vel.lengthSqr();
                boolean movingSlowEnough = speedSqr <= 0.08D; // generous; mostly filters true flight

                if (dXZ <= maxSnapRadius && movingSlowEnough) {
                    // Snap X/Z only; keep Y (you already have settling logic in enterIdleFromLanding too).
                    double snapX = center.x;
                    double snapZ = center.z;
                    double keepY = this.getY();

                    this.setPos(snapX, keepY, snapZ);
                    this.hurtMarked = true;

                    // IMPORTANT:
                    // We do NOT set idle state here; caller does that when we return true.
                    // But we *do* want future checks to see a stable "committed" corner.
                    // Keep landingLeafPos consistent with what we're snapping to.
                    landingLeafPos = bestCorner;

                    if (this.tickCount % 20 == 0) {
                        LOG.info("[RavenEntity] {}: DROP-COMMIT SNAP applied dXZ={} -> 0.0 corner={} center={} posNow={} vel={} landingTicks={}",
                                debugTag,
                                String.format("%.3f", dXZ),
                                bestCorner,
                                center,
                                this.position(),
                                vel,
                                landingTicks);
                    }

                    return true;
                } else {
                    if (this.tickCount % 20 == 0) {
                        LOG.info("[RavenEntity] {}: DROP force-commit skipped dXZ={} maxSnap={} movingSlow={} vel={} corner={} center={} landingTicks={}",
                                debugTag,
                                String.format("%.3f", dXZ),
                                String.format("%.3f", maxSnapRadius),
                                movingSlowEnough,
                                vel,
                                bestCorner,
                                center,
                                landingTicks);
                    }
                }
            }

            // Original behavior + improved diagnostics
            boolean ok = withinRelaxed;

            if (!ok && (this.tickCount % 20 == 0)) {
                LOG.info("[RavenEntity] {}: relaxedPerch=false dXZ={} eps={} pos={} center={} corner={} onGround={} vColl={} vel={} phase={} landingTicks={}",
                        debugTag,
                        String.format("%.3f", dXZ),
                        String.format("%.3f", relaxedEps),
                        pos,
                        center,
                        bestCorner,
                        this.onGround(),
                        this.verticalCollision,
                        this.getDeltaMovement(),
                        landingPhase,
                        landingTicks
                );
            } else if (ok && (this.tickCount % 20 == 0)) {
                LOG.info("[RavenEntity] {}: relaxedPerch=true dXZ={} eps={} pos={} center={} corner={} (will enter idle + snap)",
                        debugTag,
                        String.format("%.3f", dXZ),
                        String.format("%.3f", relaxedEps),
                        pos,
                        center,
                        bestCorner
                );
            }

            return ok;

        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isOnValidPerchNowRelaxedForLanding failed safely: {}", t.toString());
            }
            return false;
        }
    }

    private void tickRandomFlightTeleportBlink() {
        try {
            if (this.level().isClientSide) return;
            if (!this.isAlive()) return;

            // Never stack with your existing teleport sequence.
            if (teleportSeqPhase != TeleportSeqPhase.NONE) return;

            RavenAIState st = getAIState();

            // Only in flight-ish modes. Tighten if you want ONLY ROAM_FLY.
            boolean inFlight =
                    (st == RavenAIState.ROAM_FLY)
                            || (st == RavenAIState.FOLLOW_OWNER);

            if (!inFlight) {
                // Leaving flight resets "session" state so next flight can roll a new budget.
                flightTeleportLastAI = st;
                flightTeleportBudget = 0;
                flightTeleportUsed = 0;
                flightTeleportCheckCooldownTicks = 0;
                flightTeleportHardCooldownTicks = 0;
                return;
            }

            // Initialize a new flight session budget when we transition into flight (or first time).
            if (flightTeleportLastAI == null || flightTeleportLastAI != st) {
                flightTeleportLastAI = st;

                // Roll per-flight budget: 0, 1, or 2 (weighted).
                // Often none, sometimes one, rarely two.
                int roll = this.getRandom().nextInt(100);
                if (roll < 55) {
                    flightTeleportBudget = 0;
                } else if (roll < 90) {
                    flightTeleportBudget = 1;
                } else {
                    flightTeleportBudget = 2;
                }

                flightTeleportUsed = 0;

                // Avoid teleporting instantly on takeoff; give it a moment.
                flightTeleportCheckCooldownTicks = 40 + this.getRandom().nextInt(60); // 2–5s
                flightTeleportHardCooldownTicks = 0;

                if (this.tickCount % 20 == 0) {
                    LOG.debug("[RavenEntity] FlightBlink session started ai={} budget={} pos={}",
                            st, flightTeleportBudget, this.position());
                }
            }

            // If budget is 0, we do nothing for this flight session.
            if (flightTeleportBudget <= 0) {
                return;
            }
            if (flightTeleportUsed >= flightTeleportBudget) {
                return;
            }

            if (flightTeleportHardCooldownTicks > 0) {
                flightTeleportHardCooldownTicks--;
            }
            if (flightTeleportCheckCooldownTicks > 0) {
                flightTeleportCheckCooldownTicks--;
                return;
            }

            // Throttle check frequency (so we don’t spam scanning).
            // We only check ~every 1–2 seconds.
            flightTeleportCheckCooldownTicks = 20 + this.getRandom().nextInt(25);

            // Also require we actually have some motion; avoids blinking while hovering nearly still.
            Vec3 vel = this.getDeltaMovement();
            if (vel.lengthSqr() < 0.004D) { // ~0.063 blocks/tick
                return;
            }

            // Probability per check (per ~1–2 seconds). Tune this.
            double p = 0.26D;
            if (this.getRandom().nextDouble() > p) {
                return;
            }

            // Don’t allow very frequent back-to-back blinks even if the roll hits twice.
            if (flightTeleportHardCooldownTicks > 0) {
                return;
            }

            BlockPos targetBlock = findNearbyEmptyTeleportBlock3x3x3(10, 60);
            if (targetBlock == null) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] FlightBlink: no valid 3x3x3 empty space found near pos={} ai={} used={}/{}",
                            this.position(), st, flightTeleportUsed, flightTeleportBudget);
                }
                return;
            }

            Vec3 end = new Vec3(targetBlock.getX() + 0.5D, targetBlock.getY(), targetBlock.getZ() + 0.5D);

            // Preserve “stay in home bounds” behavior (optional).
            if (isOutOfHomeBounds(end)) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] FlightBlink: candidate out of home bounds end={} basePos={}", end, this.position());
                }
                return;
            }

            // ✅ FIXED: valid hex literal (and stable "salt" for variety)
            final long BLINK_SALT = 0xB11E5EEDL; // "BILESEED" vibe; valid hex
            long fxSeed = this.getUUID().getLeastSignificantBits()
                    ^ (long) this.tickCount
                    ^ targetBlock.asLong()
                    ^ BLINK_SALT;

            if (this.tickCount % 20 == 0) {
                LOG.debug("[RavenEntity] FlightBlink seed computed: lsb={} tick={} block={} salt={} fxSeed={}",
                        this.getUUID().getLeastSignificantBits(),
                        this.tickCount,
                        targetBlock.asLong(),
                        BLINK_SALT,
                        fxSeed);
            }

            // Use your existing fade/FX teleport sequence.
            startTeleportSequence(end, fxSeed, "random flight blink");

            flightTeleportUsed++;

            // Hard cooldown after a blink so it feels like a “rare event” not rapid-fire.
            flightTeleportHardCooldownTicks = 60 + this.getRandom().nextInt(80); // 3–7s

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] FlightBlink TRIGGERED used={}/{} ai={} fromPos={} toBlock={} end={} vel={}",
                        flightTeleportUsed, flightTeleportBudget, st, this.position(), targetBlock, end, vel);
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] tickRandomFlightTeleportBlink failed", t);
        }
    }

    @Nullable
    private BlockPos findNearbyEmptyTeleportBlock3x3x3(int radiusBlocks, int maxCandidates) {
        try {
            int r = Math.max(1, radiusBlocks);
            int candidates = Math.max(1, maxCandidates);

            BlockPos base = this.blockPosition();
            RandomSource rnd = this.getRandom();

            // Keep Y in a reasonable band around current Y so it doesn't blink into weird vertical spots.
            // You can widen this if you want.
            int yBand = 4;

            for (int i = 0; i < candidates; i++) {
                int rx = rnd.nextInt(r * 2 + 1) - r;
                int rz = rnd.nextInt(r * 2 + 1) - r;
                int ry = rnd.nextInt(yBand * 2 + 1) - yBand;

                BlockPos p = base.offset(rx, ry, rz);

                // First: quick center + headroom check (fast reject)
                if (!this.level().isEmptyBlock(p)) continue;
                if (!this.level().isEmptyBlock(p.above())) continue;
                if (!this.level().getFluidState(p).isEmpty()) continue;

                // Now enforce: 3x3x3 volume must be fully empty & fluid-free.
                // We interpret the 3-high as y..y+2. If you prefer centered y-1..y+1, tell me.
                boolean ok = true;
                for (int dx = -1; dx <= 1 && ok; dx++) {
                    for (int dz = -1; dz <= 1 && ok; dz++) {
                        for (int dy = 0; dy <= 2 && ok; dy++) {
                            BlockPos q = p.offset(dx, dy, dz);

                            if (!this.level().isEmptyBlock(q)) {
                                ok = false;
                                break;
                            }
                            if (!this.level().getFluidState(q).isEmpty()) {
                                ok = false;
                                break;
                            }
                        }
                    }
                }

                if (!ok) continue;

                // Optional: don't blink inside leaves even if “empty” is weirdly true due to replaceables.
                // But since we require isEmptyBlock(), leaves won't pass anyway. Keeping it simple.

                return p;
            }

            return null;
        } catch (Throwable t) {
            if (this.tickCount % 60 == 0) {
                LOG.warn("[RavenEntity] findNearbyEmptyTeleportBlock3x3x3 failed safely: {}", t.toString());
            }
            return null;
        }
    }

    private boolean isPerchFootprintStillSupported(BlockPos cornerTop) {
        try {
            if (cornerTop == null) return false;

            int cx = cornerTop.getX();
            int cz = cornerTop.getZ();
            int topY = cornerTop.getY();

            boolean anyAtTop = false;

            for (int ox = 0; ox < PERCH_FOOTPRINT_SIZE; ox++) {
                for (int oz = 0; oz < PERCH_FOOTPRINT_SIZE; oz++) {
                    int x = cx + ox;
                    int z = cz + oz;

                    boolean atTop = isLeavesAt(x, topY, z);
                    boolean atStep = isLeavesAt(x, topY - 1, z);

                    if (!atTop && !atStep) {
                        return false;
                    }
                    if (atTop) anyAtTop = true;
                }
            }

            return anyAtTop;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isPerchFootprintStillSupported failed safely: {}", t.toString());
            }
            return false;
        }
    }

    private PerchValidity validatePerchCornerAtTopY(BlockPos cornerTop) {
        try {
            if (cornerTop == null) {
                return PerchValidity.fail("cornerTop=null", "");
            }

            int cx = cornerTop.getX();
            int cz = cornerTop.getZ();
            int topY = cornerTop.getY();

            boolean anyAtTop = false;

            // Footprint check: each cell must have leaves at topY or topY-1, and at least one must be at topY.
            for (int ox = 0; ox < PERCH_FOOTPRINT_SIZE; ox++) {
                for (int oz = 0; oz < PERCH_FOOTPRINT_SIZE; oz++) {
                    int x = cx + ox;
                    int z = cz + oz;

                    boolean atTop = isLeavesAt(x, topY, z);
                    boolean atStep = isLeavesAt(x, topY - 1, z);

                    if (!atTop && !atStep) {
                        return PerchValidity.fail(
                                "footprint_missing_leaves",
                                "cell=(" + x + "," + topY + "," + z + ") top=" + atTop + " step=" + atStep
                        );
                    }
                    if (atTop) anyAtTop = true;
                }
            }

            if (!anyAtTop) {
                return PerchValidity.fail(
                        "footprint_no_top_layer",
                        "topY=" + topY + " (all 4 cells only at topY-1)"
                );
            }

            // Air column check: 10 blocks above each of 4 top-layer columns.
            for (int ox = 0; ox < PERCH_FOOTPRINT_SIZE; ox++) {
                for (int oz = 0; oz < PERCH_FOOTPRINT_SIZE; oz++) {
                    BlockPos start = new BlockPos(cx + ox, topY + 1, cz + oz);
                    if (!hasAirColumn(start, LAND_REQUIRED_AIR_ABOVE)) {
                        // Find the first blocked Y for clearer diagnostics
                        int blockedAtY = Integer.MIN_VALUE;
                        BlockPos blockedPos = null;
                        for (int i = 0; i < LAND_REQUIRED_AIR_ABOVE; i++) {
                            BlockPos p = start.above(i);
                            if (!this.level().isEmptyBlock(p)) {
                                blockedAtY = p.getY();
                                blockedPos = p;
                                break;
                            }
                        }

                        String extra = "";
                        if (blockedPos != null) {
                            BlockState st = this.level().getBlockState(blockedPos);
                            extra = " blockedPos=" + blockedPos + " block=" + (st == null ? "null" : st.getBlock().toString());
                        }

                        return PerchValidity.fail(
                                "air_column_blocked",
                                "col=(" + (cx + ox) + "," + (cz + oz) + ") startY=" + (topY + 1)
                                        + " need=" + LAND_REQUIRED_AIR_ABOVE
                                        + " firstBlockedY=" + (blockedAtY == Integer.MIN_VALUE ? "?" : blockedAtY)
                                        + extra
                        );
                    }
                }
            }

            // Neighbor canopy check around topY plane
            int neighbors = 0;
            for (int ox = -1; ox <= 2; ox++) {
                for (int oz = -1; oz <= 2; oz++) {
                    if (ox >= 0 && ox <= 1 && oz >= 0 && oz <= 1) continue;

                    BlockState st = this.level().getBlockState(new BlockPos(cx + ox, topY, cz + oz));
                    if (st != null && st.is(BlockTags.LEAVES)) {
                        neighbors++;
                    }
                }
            }

            int requiredNeighbors = Math.max(2, CANOPY_NEIGHBOR_LEAVES_REQUIRED - 2);
            if (neighbors < requiredNeighbors) {
                return PerchValidity.fail(
                        "canopy_neighbors_low",
                        "neighbors=" + neighbors + " required=" + requiredNeighbors + " topY=" + topY + " corner=(" + cx + "," + cz + ")"
                );
            }

            return PerchValidity.ok();

        } catch (Throwable t) {
            return PerchValidity.fail("exception", t.toString());
        }
    }

    private boolean isTeleportRecoveryEligible() {
        try {
            if (this.level().isClientSide) return false;
            if (!this.isAlive()) return false;

            if (teleportSeqPhase != TeleportSeqPhase.NONE) return false;

            RavenAIState st = getAIState();

            // Per request: only exclude idle.
            if (st == RavenAIState.IDLE_GROUND) return false;

            // Keep these safety exclusions.
            if (this.isPassenger() || this.isVehicle()) return false;
            if (this.isNoAi()) return false;

            // IMPORTANT:
            // We still require some “intent” so we don't randomly teleport while just hovering.
            boolean hasIntent =
                    (flyTarget != null && flyTargetTimeoutTicks > 0)
                            || (pathWaypoints != null && !pathWaypoints.isEmpty() && pathWaypointIndex < pathWaypoints.size())
                            || (pathPendingGoal != null)
                            || (pathGoal != null)
                            || (st == RavenAIState.FOLLOW_OWNER);

            if (!hasIntent) return false;

            // -------------------------------------------------
            // When FOLLOW_OWNER + lure-follow is active AND
            // we are already "at the player", suppress stuck teleport.
            // This stops constant blinking while you stand still with a nugget.
            // -------------------------------------------------
            if (st == RavenAIState.FOLLOW_OWNER && LureFollowTame.isLureFollowActive()) {
                try {
                    Player lurePlayer = LureFollowTame.getLureFollowPlayerServerSafe();
                    if (lurePlayer != null) {
                        boolean closeEnough;
                        try {
                            // This method is already used in your follow code.
                            closeEnough = LureFollowTame.isCloseEnoughToFollowPlayer(lurePlayer);
                        } catch (Throwable t) {
                            // If anything goes wrong, err on the side of allowing recovery.
                            closeEnough = false;
                            if (this.tickCount % 80 == 0) {
                                LOG.warn("[RavenEntity] isTeleportRecoveryEligible: isCloseEnoughToFollowPlayer failed safely: {}", t.toString());
                            }
                        }

                        if (closeEnough) {
                            if (this.tickCount % 40 == 0) {
                                LOG.debug("[RavenEntity] isTeleportRecoveryEligible: suppressed (close to lure-follow player) pos={} aiState={} flyIntent={} pathIntent={} lurePlayer={}",
                                        this.position(),
                                        st,
                                        (flyTarget != null && flyTargetTimeoutTicks > 0),
                                        (pathWaypoints != null && !pathWaypoints.isEmpty() && pathWaypointIndex < pathWaypoints.size()),
                                        lurePlayer.getName().getString());
                            }
                            return false;
                        }
                    }
                } catch (Throwable t) {
                    if (this.tickCount % 80 == 0) {
                        LOG.warn("[RavenEntity] isTeleportRecoveryEligible: lure-follow close check failed safely: {}", t.toString());
                    }
                }
            }

            return true;

        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isTeleportRecoveryEligible failed: {}", t.toString());
            }
            return false;
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
