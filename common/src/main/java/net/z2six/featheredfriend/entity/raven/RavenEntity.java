// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenEntity.java
package net.z2six.featheredfriend.entity.raven;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.RavenChestBlock;
import net.z2six.featheredfriend.registry.FFItems;
import net.z2six.featheredfriend.item.RavenArmorStats;
import net.z2six.featheredfriend.log.RavenLogCategory;
import net.z2six.featheredfriend.world.TamedRavenPlayerData;
import net.z2six.featheredfriend.world.RavenLogService;
import net.z2six.featheredfriend.entity.raven.modules.*;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.NotNull;

// Modules
import net.z2six.featheredfriend.platform.Services;
import net.z2six.featheredfriend.entity.raven.modules.TamedRavenDeathHandler;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
    private static final EntityDataAccessor<Integer> DATA_ARMOR_VISUAL =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    // Animation mode (manual override system)
    private static final EntityDataAccessor<Integer> DATA_ANIM_MODE =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    // AI state + follow cooldown
    private static final EntityDataAccessor<Integer> DATA_AI_STATE =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_RAVEN_CHEST_PERCH_YAW =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_RAVEN_CHEST_PERCH_PITCH =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.FLOAT);

    // --- Lure/follow data accessors (MUST live here, not in LureFollowTame) ---
    public static final EntityDataAccessor<Boolean> DATA_LURE_FOLLOW_ARMED =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Boolean> DATA_LURE_FOLLOW_ACTIVE =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.BOOLEAN);

    // Follow cooldown (for lure/owner follow logic).
    public static final EntityDataAccessor<Integer> DATA_FOLLOW_COOLDOWN_TICKS =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    private static final String NBT_VARIANT = "RavenVariant";
    private static final String NBT_ARMOR_VISUAL = "RavenArmorVisual";
    private static final String NBT_ANIM_MODE = "RavenAnimMode";
    private static final String NBT_AI_STATE = "RavenAIState";
    private static final String NBT_RAVEN_CHEST_PERCH_ASSIGNED = "RavenChestPerchAssigned";
    private static final String NBT_RAVEN_CHEST_PERCH_DIMENSION = "RavenChestPerchDimension";
    private static final String NBT_RAVEN_CHEST_PERCH_BLOCK_POS = "RavenChestPerchBlockPos";

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
    private final LureFollowTame lureFollowTame = new LureFollowTame(this); // Not part of sound but hey why not put it here
    private final Teleportation teleportation = new Teleportation(this);
    private final Landing landing = new Landing(this);
    private final PlayerAvoidance playeravoidance = new PlayerAvoidance(this);
    private final TamedRaven tamedRaven = new TamedRaven(this); // Tamed raven helper (extra taming/owner utilities)

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
    private static final ResourceLocation SOUND_RAVEN_TELEPORT_ID =
            ResourceLocation.fromNamespaceAndPath("featheredfriend", "raven.teleport");

    // Cached SoundEvents (lazy-resolved from the IDs above)
    private static SoundEvent cachedRavenCawingNormal = null;
    private static SoundEvent cachedRavenCawAgree = null;
    private static SoundEvent cachedRavenCawDmg = null;
    private static SoundEvent cachedRavenCawWhistle = null;
    private static SoundEvent cachedRavenAirWoosh = null;
    private static SoundEvent cachedRavenTeleport = null;

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
    // Cooldown so the helper doesn't re-arm every single tick and spam retargeting.
    private int playerAvoidanceRearmCooldownTicks = 0;

    private PostTeleportIntent postTeleportIntent = PostTeleportIntent.PERCH;

    // Anti-spam: prevent multiple damage blinks in the same instant (fire ticks, thorns spam, etc.)
    private int lastDamageBlinkTick = -999999;
    private static final int DAMAGE_BLINK_MIN_INTERVAL_TICKS = 10; // 0.5s @ 20 TPS
    private static final int DEFAULT_RAVEN_MAX_HITS = 1;
    private static final int DEFAULT_RAVEN_DODGE_CHANCE_PERCENT = 25;
    private static final int DEFAULT_RAVEN_DETECTION_RADIUS_BLOCKS = 10;
    private static final int DEFAULT_RAVEN_PAYLOAD_SAFETY_PERCENT = 0;
    private static final int DEFAULT_RAVEN_HEALTH_REGEN_PER_MINUTE = 0;
    private static final double DEFAULT_RAVEN_MAX_HEALTH = (double) DEFAULT_RAVEN_MAX_HITS;
    private static final float DAMAGE_PER_LANDED_HIT = 1.0F;
    private static final double RAVEN_CHEST_PERCH_OFFSET_X = 0.5D;
    private static final double RAVEN_CHEST_PERCH_OFFSET_Y = 1.6D;
    private static final double RAVEN_CHEST_PERCH_OFFSET_Z = 0.5D;

    // Idle (30-60s) NOTE: 20 ticks = 1 second
    private static final int IDLE_MIN_TICKS = 30 * 20;
    private static final int IDLE_MAX_TICKS = 60 * 20;

    // Roam flight window before landing is allowed (8-16s) NOTE: 20 ticks = 1 second
    private static final int ROAM_MIN_TICKS = 8 * 20;
    private static final int ROAM_MAX_TICKS = 16 * 20;
    private static final int ROAM_REARM_INTERVAL_TICKS = 80;

    private static final int PERCH_RETURN_FAILS = 4;
    private static final int PERCH_RETURN_MIN_TICKS = 60 * 20;

    private static final double LURE_PLAYER_RADIUS = 64.0D;
    private static final int LURE_CHECK_INTERVAL_TICKS = 10;

    // Stuck / avoidance (used in free-flight and overhead approach)
    private static final int STUCK_TICKS_THRESHOLD = 30;
    private static final double STUCK_PROGRESS_EPS = 0.06D;
    private static final int AVOIDANCE_COOLDOWN_TICKS = 10;

    // Movement tuning
    private static final double FLY_SPEED_BASE = 0.25D;
    private static final double FLY_SPEED_RETURN = 0.32D;
    private static final double ARRIVE_DIST = 1.2D;

    // Delivery ravens (scroll + courier) use straight-line flight + teleport corrections.
    private static final String TAG_COURIER_RAVEN = "ff_courier_raven";
    private static final int DELIVERY_REARM_INTERVAL_TICKS = 20;
    private static final int DELIVERY_FLY_TARGET_TTL_TICKS = 20;
    private static final double DELIVERY_TELEPORT_DISTANCE_SQR = 10.0D * 10.0D;
    private static final double DELIVERY_ARRIVE_DIST = 1.25D;

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
    private int lureCheckCooldownTicks = 0;

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

    // Simple flight target (server-side): current direct-flight target.
    @Nullable
    private Vec3 flyTarget = null;

    private int flyTargetTimeoutTicks = 0;
    private int deliveryRearmTicks = 0;
    private int ravenChestPerchThreatCheckCooldownTicks = 0;
    private final Map<UUID, String> ravenChestPerchSeenHostiles = new HashMap<>();
    private boolean ravenFeatherDeathDropDone = false;

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
    private int roamRearmTicks = 0;

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

    @Nullable
    private BlockPos lastPerchCorner = null;
    private long lastPerchGameTime = 0L;
    private int perchSearchFailCount = 0;

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
        builder.define(DATA_ARMOR_VISUAL, RavenArmorVisual.NONE.id());
        builder.define(DATA_ANIM_MODE, RavenAnimMode.AUTO.id());
        builder.define(DATA_AI_STATE, RavenAIState.IDLE_GROUND.id());
        builder.define(DATA_RAVEN_CHEST_PERCH_YAW, 0.0F);
        builder.define(DATA_RAVEN_CHEST_PERCH_PITCH, 0.0F);

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

    public RavenArmorVisual getRavenArmorVisual() {
        return RavenArmorVisual.fromId(this.entityData.get(DATA_ARMOR_VISUAL));
    }

    public void setRavenArmorVisual(@Nullable RavenArmorVisual visual) {
        if (visual == null) {
            visual = RavenArmorVisual.NONE;
        }
        this.entityData.set(DATA_ARMOR_VISUAL, visual.id());
        if (this.level() != null && !this.level().isClientSide) {
            enforceDefaultRavenHealthProfile();
        }
    }

    private @NotNull RavenArmorStats getEffectiveArmorStats() {
        try {
            RavenArmorStats equipped = FFItems.getRavenArmorStats(getRavenArmorVisual());
            if (equipped != null) {
                return equipped;
            }
        } catch (Throwable ignored) {
        }
        return new RavenArmorStats(
                DEFAULT_RAVEN_MAX_HITS,
                DEFAULT_RAVEN_DODGE_CHANCE_PERCENT,
                DEFAULT_RAVEN_DETECTION_RADIUS_BLOCKS,
                DEFAULT_RAVEN_PAYLOAD_SAFETY_PERCENT,
                DEFAULT_RAVEN_HEALTH_REGEN_PER_MINUTE
        );
    }

    public int getEffectiveMaxHits() {
        return Math.max(1, getEffectiveArmorStats().maxHits());
    }

    public float getEffectiveDodgeChanceFraction() {
        int percent = Mth.clamp(getEffectiveArmorStats().dodgeChancePercent(), 0, 100);
        return percent / 100.0F;
    }

    public double getEffectiveThreatDetectionRadiusBlocks() {
        return Math.max(0.0D, getEffectiveArmorStats().detectionRadius());
    }

    public float getEffectivePayloadDropOnLandedHitChance() {
        int safetyPercent = Mth.clamp(getEffectiveArmorStats().payloadSafetyPercent(), 0, 100);
        return 1.0F - (safetyPercent / 100.0F);
    }

    public int getEffectiveHealthRegenPerMinute() {
        return Math.max(0, getEffectiveArmorStats().healthRegenPerMinute());
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

    public void setRavenChestPerchLockRotation(float yaw, float pitch) {
        this.entityData.set(DATA_RAVEN_CHEST_PERCH_YAW, yaw);
        this.entityData.set(DATA_RAVEN_CHEST_PERCH_PITCH, pitch);
    }

    public float getRavenChestPerchLockYaw() {
        return this.entityData.get(DATA_RAVEN_CHEST_PERCH_YAW);
    }

    public float getRavenChestPerchLockPitch() {
        return this.entityData.get(DATA_RAVEN_CHEST_PERCH_PITCH);
    }

    public void setAIState(@Nullable RavenAIState state) {
        if (state == null) {
            state = RavenAIState.IDLE_GROUND;
        }

        try {
            // Current state from synced data
            RavenAIState prev = getAIState();

            // ----------------------------------------
// FOLLOW PROTECTION GUARD:
//
// When follow is "protected" (either the follow override is active OR
// the scroll-summon follow-lock is active), FOLLOW_OWNER should not be
// overridden by other AI planners in the same tick.
//
// Without this, you can get:
//   FOLLOW_OWNER -> ROAM_FLY -> FOLLOW_OWNER -> ROAM_FLY ...
// which matches the user's logs (and can freeze movement).
//
// Safety:
// - We only enforce this if the owner is actually valid/present.
// - If the owner is gone/offline, we allow transitions out of FOLLOW_OWNER.
// ----------------------------------------
            boolean followOverrideActive = false;
            boolean scrollSummonFollowLock = false;

            try {
                followOverrideActive = (this.lureFollowTame != null && this.lureFollowTame.isFollowOverrideActive());
            } catch (Throwable t) {
                if (this.tickCount % 80 == 0) {
                    //LOG.warn("[RavenEntity] setAIState: isFollowOverrideActive failed safely: {}", t.toString());
                }
            }

            try {
                // This already exists in your class (you call it from aiStep).
                scrollSummonFollowLock = isScrollSummonFollowLockActive();
            } catch (Throwable t) {
                if (this.tickCount % 80 == 0) {
                    //LOG.warn("[RavenEntity] setAIState: isScrollSummonFollowLockActive failed safely: {}", t.toString());
                }
            }

            boolean protectFollow = followOverrideActive || scrollSummonFollowLock;

// Only protect FOLLOW_OWNER if the owner is actually present/valid.
// (Prevents "stuck FOLLOW_OWNER forever" if owner disappears.)
            boolean ownerValid = false;
            try {
                if (protectFollow && this.lureFollowTame != null) {
                    Player ownerNow = this.lureFollowTame.getOwnerPlayerServerSafe();
                    ownerValid = (ownerNow != null && ownerNow.isAlive() && ownerNow.level() == this.level());
                }
            } catch (Throwable t) {
                if (this.tickCount % 80 == 0) {
                    //LOG.warn("[RavenEntity] setAIState: owner validity check failed safely: {}", t.toString());
                }
            }

            if (protectFollow
                    && ownerValid
                    && prev == RavenAIState.FOLLOW_OWNER
                    && state != RavenAIState.FOLLOW_OWNER) {

                // Rate-limited log so we can confirm what's trying to steal the state.
                if (this.tickCount % 20 == 0) {
                    /* LOG.debug(
                            "[RavenEntity] setAIState: BLOCKED transition {} -> {} while follow protected (override={}, scrollLock={}) " +
                                    "pos={} flyTarget={} followCd={}",
                            prev,
                            state,
                            followOverrideActive,
                            scrollSummonFollowLock,
                            this.position(),
                            flyTarget,
                            getFollowCooldownTicks()
                    );
                     */
                }

                // Optional: super-occasional stack trace to identify the caller.
                // This is intentionally rare to avoid log spam.
                if (LOG.isDebugEnabled() && (this.tickCount % 200 == 0)) {
                    try {
                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                        StringBuilder sb = new StringBuilder();
                        int shown = 0;
                        for (int i = 2; i < st.length && shown < 10; i++) {
                            // Skip noisy JVM internals
                            String cn = st[i].getClassName();
                            if (cn.startsWith("java.") || cn.startsWith("sun.") || cn.startsWith("jdk.")) {
                                continue;
                            }
                            sb.append("\n  at ").append(st[i]);
                            shown++;
                        }
                        /* LOG.debug("[RavenEntity] setAIState: caller trace for blocked transition:{}",
                                sb.toString());
                         */
                    } catch (Throwable ignored) {
                    }
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
                            /* LOG.debug(
                                    "[RavenEntity] Armed one-time idle settle (prev={}, next={}) pos={} bbMinY={} vel={}",
                                    prev,
                                    state,
                                    this.position(),
                                    this.getBoundingBox().minY,
                                    this.getDeltaMovement()
                            );
                             */
                        }
                    }
                }
            } catch (Throwable t) {
                //LOG.warn("[RavenEntity] setAIState arm-settle failed: {}", t.toString());
            }

            // If same-state spam happens, log occasionally so you can see it.
            if (prev == state) {
                if (this.tickCount % 80 == 0) {
                    /* LOG.debug(
                            "[RavenEntity] setAIState(same): {} pos={} landingPhase={} landingLeafPos={} " +
                                    "idleTicksRemaining={} idleCommitTicks={} roamTicksRemaining={} " +
                                    "flyTarget={} flyTtl={}",
                            state,
                            this.position(),
                            landingPhase,
                            landing.landingLeafPos,
                            idleTicksRemaining,
                            idleCommitTicks,
                            roamTicksRemaining,
                            flyTarget,
                            flyTargetTimeoutTicks
                    );
                     */
                }
            } else {
                // Real transition: log every time.
                /* LOG.debug(
                        "[RavenEntity] AI STATE CHANGE: {} -> {} pos={} vel={} noGravity={} onGround={} " +
                                "landingPhase={} landingLeafPos={} landingTicks={} " +
                                "idlePerchCorner={} idleTicksRemaining={} idleCommitTicks={} idleLeafLossTicks={} idleLockTicks={} " +
                                "roamTicksRemaining={} flyTarget={} flyTtl={}",
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
                        flyTargetTimeoutTicks
                );
                 */
            }

        } catch (Throwable t) {
            //LOG.warn("[RavenEntity] setAIState logging failed safely: {}", t.toString());
        }

        // Final authoritative write
        this.entityData.set(DATA_AI_STATE, state.id());
    }

    private void debugAiHeartbeat(String where) {
        try {
            if (this.level().isClientSide) return;

            // once per second
            if (this.tickCount % 20 != 0) return;

            RavenAIState st = getAIStateForDebug();

            /* LOG.debug("[RavenEntity] HEARTBEAT({}): ai={} pos={} vel={} noGravity={} onGround={} hColl={} vColl={} landingPhase={} landingLeafPos={} landingTicks={} idlePerchCorner={} idleTicksRemaining={} idleCommitTicks={} idleLeafLossTicks={} idleLockTicks={} roamTicksRemaining={} flyTarget={} flyTtl={}",
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
                    flyTargetTimeoutTicks
            );
            */
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                // LOG.warn("[RavenEntity] debugAiHeartbeat failed safely: {}", t.toString());
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
            // LOG.debug("[RavenEntity] Home initialized at {}", homePos);
        }
    }

    private int clampYToHomeBounds(int y) {
        return y;
    }

    private Vec3 clampTargetToHomeBounds(Vec3 target) {
        return target;
    }

    private boolean isOutOfHomeBounds(Vec3 pos) {
        return false;
    }

    private Vec3 homeCenterReturnTarget() {
        return this.position();
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

    private void setFlyTargetNoClamp(Vec3 target, int timeoutTicks) {
        this.flyTarget = target;
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

    private void reissueMovementIntentAfterTeleport(String reason) {
        try {
            if (postTeleportIntent == PostTeleportIntent.PERCH) {
                if (landing.landingLeafPos != null && getAIState() == RavenAIState.ROAM_FLY) {
                    landing.enterIdleFromLanding("teleport perch: " + reason, this);
                }
                return;
            }

            if (postTeleportIntent == PostTeleportIntent.ROAM_FLIGHT) {
                landing.landingLeafPos = null;
                setAIState(RavenAIState.ROAM_FLY);
            }
        } catch (Throwable t) {
            //LOG.warn("[RavenEntity] reissueMovementIntentAfterTeleport failed: {}", t.toString());
        } finally {
            postTeleportIntent = PostTeleportIntent.PERCH;
        }
    }

    public boolean commandMoveTo(BlockPos pos, double speed) {
        if (pos == null) {
            //LOG.warn("[RavenEntity] commandMoveTo called with null pos");
            return false;
        }
        if (speed <= 0.0D) {
            //LOG.warn("[RavenEntity] commandMoveTo called with non-positive speed: {}", speed);
            return false;
        }

        try {
            ensureHomeInitialized();

            Vec3 target = new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            setFlyTarget(target, 12 * 20);
            return true;
        } catch (Throwable t) {
            //LOG.error("[RavenEntity] commandMoveTo failed for {} @ {}", pos, speed, t);
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
            //LOG.debug("[RavenEntity] getBreedOffspring called but breeding is not implemented (returning null).");
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
                //LOG.warn("[RavenEntity] checkInsideBlocks failed: {}", t.toString());
            }
        }
    }

    private void enforceDefaultRavenHealthProfile() {
        try {
            double targetMaxHealth = Math.max(1.0D, (double) getEffectiveMaxHits());
            net.minecraft.world.entity.ai.attributes.AttributeInstance maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth != null && maxHealth.getBaseValue() != targetMaxHealth) {
                maxHealth.setBaseValue(targetMaxHealth);
            }

            float targetMaxHealthF = (float) targetMaxHealth;
            if (this.getHealth() > targetMaxHealthF) {
                this.setHealth(targetMaxHealthF);
            }
        } catch (Throwable ignored) {
        }
    }

    private void tickPassiveHealthRegen() {
        try {
            if (this.level().isClientSide || !this.isAlive()) {
                return;
            }

            int regenPerMinute = getEffectiveHealthRegenPerMinute();
            if (regenPerMinute <= 0) {
                return;
            }

            float maxHealth = this.getMaxHealth();
            float current = this.getHealth();
            if (current >= maxHealth) {
                return;
            }

            float healPerTick = regenPerMinute / 1200.0F;
            if (healPerTick <= 0.0F) {
                return;
            }

            this.setHealth(Math.min(maxHealth, current + healPerTick));
        } catch (Throwable ignored) {
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
            //LOG.warn("[RavenEntity] finalizeSpawn: super.finalizeSpawn failed safely: {}", t.toString());
            out = spawnGroupData;
        }

        try {
            enforceDefaultRavenHealthProfile();
        } catch (Throwable ignored) {
        }

        try {
            // ------------------------------------------------------------
            // Roll per-spawn tame-cost (3..6 golden nuggets)
            // Only roll if not already set (e.g., NBT-loaded or manually assigned).
            // ------------------------------------------------------------
            initGoldenNuggetsRequiredToTameIfNeeded("finalizeSpawn:" + spawnType);

            if (this.tickCount % 20 == 0) {
                /* LOG.debug("[RavenEntity] finalizeSpawn: nuggetsRequiredToTame={} spawnType={} pos={}",
                        this.getGoldenNuggetsRequiredToTame(),
                        spawnType,
                        this.position());
                 */
            }
        } catch (Throwable t) {
            //LOG.warn("[RavenEntity] finalizeSpawn: tame-cost init failed safely: {}", t.toString());
        }

        return out;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        try {
            tag.putInt(NBT_VARIANT, this.entityData.get(DATA_VARIANT));
            tag.putInt(NBT_ARMOR_VISUAL, this.entityData.get(DATA_ARMOR_VISUAL));
            tag.putInt(NBT_ANIM_MODE, this.entityData.get(DATA_ANIM_MODE));
            tag.putInt(NBT_AI_STATE, this.entityData.get(DATA_AI_STATE));
            // Use the shared key & data accessor from LureFollowTame
            tag.putInt(LureFollowTame.NBT_FOLLOW_CD, this.entityData.get(LureFollowTame.DATA_FOLLOW_COOLDOWN_TICKS));

            tag.putBoolean(NBT_HOME_INIT, homeInitialized);
            tag.putInt(NBT_HOME_X, homePos.getX());
            tag.putInt(NBT_HOME_Y, homePos.getY());
            tag.putInt(NBT_HOME_Z, homePos.getZ());

            // ------------------------------------------------------------
            // Persist per-spawn taming requirement
            // ------------------------------------------------------------
            try {
                // Ensure it's initialized before saving (server side typically).
                if (this.level() != null && !this.level().isClientSide) {
                    initGoldenNuggetsRequiredToTameIfNeeded("save");
                }

                int v = Math.max(0, this.getGoldenNuggetsRequiredToTame());
                if (v < 3) v = 3;
                if (v > 6) v = 6;

                tag.putInt(LureFollowTame.NBT_TAME_NUGGETS_REQUIRED, v);
                tag.putString(LureFollowTame.NBT_TAME_NUGGET_SEQUENCE, lureFollowTame.getTameNuggetSequence());
                tag.putInt(LureFollowTame.NBT_TAME_NUGGET_SEQUENCE_INDEX, lureFollowTame.getTameNuggetSequenceIndex());

                if (this.tickCount % 200 == 0) {
                    /* LOG.debug("[RavenEntity] Saved tame-cost: {}={}",
                            LureFollowTame.NBT_TAME_NUGGETS_REQUIRED, v);
                     */
                }
            } catch (Throwable t2) {
                if (this.tickCount % 200 == 0) {
                    //LOG.warn("[RavenEntity] Failed writing tame-cost NBT safely: {}", t2.toString());
                }
            }

        } catch (Throwable t) {
            //LOG.error("[RavenEntity] Failed writing NBT", t);
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

            if (tag.contains(NBT_ARMOR_VISUAL)) {
                int armorId = tag.getInt(NBT_ARMOR_VISUAL);
                this.setRavenArmorVisual(RavenArmorVisual.fromId(armorId));
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
            // Load per-spawn taming requirement
            // ------------------------------------------------------------
            try {
                String loadedSequence = null;
                int loadedIndex = 0;
                if (tag.contains(LureFollowTame.NBT_TAME_NUGGET_SEQUENCE, net.minecraft.nbt.Tag.TAG_STRING)) {
                    loadedSequence = tag.getString(LureFollowTame.NBT_TAME_NUGGET_SEQUENCE);
                }
                if (tag.contains(LureFollowTame.NBT_TAME_NUGGET_SEQUENCE_INDEX, net.minecraft.nbt.Tag.TAG_INT)) {
                    loadedIndex = tag.getInt(LureFollowTame.NBT_TAME_NUGGET_SEQUENCE_INDEX);
                }

                if (tag.contains(LureFollowTame.NBT_TAME_NUGGETS_REQUIRED, net.minecraft.nbt.Tag.TAG_INT)) {
                    int v = tag.getInt(LureFollowTame.NBT_TAME_NUGGETS_REQUIRED);
                    if (v < 3) v = 3;
                    if (v > 6) v = 6;

                    this.setGoldenNuggetsRequiredToTame(v);

                    if (this.tickCount % 200 == 0) {
                        /* LOG.debug("[RavenEntity] Loaded tame-cost: {}={}",
                                LureFollowTame.NBT_TAME_NUGGETS_REQUIRED, v);
                         */
                    }

                    // Back-compat: if no explicit sequence is stored, treat old ravens as "all gold".
                    if (loadedSequence == null || loadedSequence.isBlank()) {
                        loadedSequence = "G".repeat(v);
                        loadedIndex = 0;
                    }
                } else {
                    // Older saves: initialize safely (server side).
                    initGoldenNuggetsRequiredToTameIfNeeded("load-missingTag");
                }

                if (loadedSequence != null && !loadedSequence.isBlank()) {
                    lureFollowTame.setTameNuggetSequence(loadedSequence, loadedIndex);
                }
            } catch (Throwable t2) {
                if (this.tickCount % 200 == 0) {
                    //LOG.warn("[RavenEntity] Failed reading tame-cost NBT safely: {}", t2.toString());
                }
                this.setGoldenNuggetsRequiredToTame(4);
            }

            enforceDefaultRavenHealthProfile();

        } catch (Throwable t) {
            //LOG.error("[RavenEntity] Failed reading NBT", t);
        }
    }

    // -----------------
    // AI tick (main, roam, idle
    // -----------------

    @Override
    public void aiStep() {
        super.aiStep();

        if (!this.level().isClientSide) {
            try {
                enforceAssignedRavenChestPerch();
            } catch (Throwable ignored) {
            }
        }

        // Hard-locked Raven Chest perch state: no movement logic, no avoidance, no stuck teleport recovery.
        if (this.getAIState() == RavenAIState.RAVEN_CHEST_PERCH) {
            try {
                this.setDeltaMovement(Vec3.ZERO);
                this.hurtMarked = true;
                this.setNoGravity(true);
                this.setNoAi(false);
                float lockYaw = this.getRavenChestPerchLockYaw();
                float lockPitch = this.getRavenChestPerchLockPitch();
                this.setYRot(lockYaw);
                this.setYHeadRot(lockYaw);
                this.yBodyRot = lockYaw;
                this.yRotO = lockYaw;
                this.yHeadRotO = lockYaw;
                this.yBodyRotO = lockYaw;
                this.setXRot(lockPitch);
                this.xRotO = lockPitch;
                if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
                    this.setAnimMode(RavenAnimMode.NO_AIR);
                }
                if (!this.level().isClientSide) {
                    try {
                        net.z2six.featheredfriend.entity.raven.modules.TamedRaven tamed = this.getTamedRavenModule();
                        if (tamed != null) {
                            tamed.tickServer();
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        tickPassiveHealthRegen();
                    } catch (Throwable ignored) {
                    }
                    try {
                        tickRavenChestPerchThreatLogging();
                    } catch (Throwable ignored) {
                    }
                    if (teleportation.teleportSeqPhase != Teleportation.TeleportSeqPhase.NONE) {
                        teleportation.teleportSeqPhase = Teleportation.TeleportSeqPhase.NONE;
                        teleportation.setTeleportFadeAlpha(255, this);
                    }
                    teleportation.tickTeleportFxServer(this);
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        if (!this.ravenChestPerchSeenHostiles.isEmpty() || this.ravenChestPerchThreatCheckCooldownTicks > 0) {
            this.ravenChestPerchSeenHostiles.clear();
            this.ravenChestPerchThreatCheckCooldownTicks = 0;
        }

        if (this.level().isClientSide) {
            return;
        }

        // Hard no-AI mode: keep the raven fully pinned and skip all custom behavior systems.
        if (this.isNoAi()) {
            try {
                this.setDeltaMovement(Vec3.ZERO);
                this.hurtMarked = true;
                this.setNoGravity(true);
                if (this.getAIState() != RavenAIState.IDLE_GROUND) {
                    this.setAIState(RavenAIState.IDLE_GROUND);
                }
                if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
                    this.setAnimMode(RavenAnimMode.NO_AIR);
                }
                teleportation.tickTeleportFxServer(this);
            } catch (Throwable ignored) {
            }
            return;
        }

        try {
            ensureHomeInitialized();

            // ------------------------------------------------------------------
            // TAMED RAVEN DESPAWN FADE (server only)
            // ------------------------------------------------------------------
            try {
                net.z2six.featheredfriend.entity.raven.modules.TamedRaven tamed = this.getTamedRavenModule();
                if (tamed != null) {
                    tamed.tickServer();
                }
            } catch (Throwable t) {
                if (this.tickCount % 80 == 0) {
                    //LOG.error("[RavenEntity] aiStep: TamedRaven.tickServer failed safely", t);
                }
            }
            try {
                tickPassiveHealthRegen();
            } catch (Throwable ignored) {
            }

            // ------------------------------------------------------------------
            // TELEPORT SEQUENCE HAS ABSOLUTE PRIORITY
            // ------------------------------------------------------------------
            teleportation.tickTeleportSequenceServer(this);

            if (teleportation.teleportSeqPhase != Teleportation.TeleportSeqPhase.NONE) {
                // While teleporting, we do NOTHING else except FX.
                teleportation.tickTeleportFxServer(this);
                return;
            }

            if (tickDeliveryRaven()) {
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

            // ------------------------------------------------------------------
            // LURE-FOLLOW ARM (pre-tame) - ensures nugget holders can trigger follow.
            // ------------------------------------------------------------------
            tryArmLureFollowFromNearbyPlayers();

            // ------------------------------------------------------------------
            // FOLLOW LOGIC – DISABLED DURING PLAYER AVOIDANCE
            //  - Special case: scroll-summoned ravens with an online owner are
            //    HARD-LOCKED into FOLLOW_OWNER.
            //    In that case we ignore the normal follow cooldown, so they
            //    never drop back into ROAM_FLY just because follow logic
            //    temporarily set a cooldown.
            // ------------------------------------------------------------------
            Player owner = (lureFollowTame != null) ? lureFollowTame.getOwnerPlayerServerSafe() : null;
            boolean lureActive = false;
            try {
                lureActive = lureFollowTame != null && lureFollowTame.isLureFollowActive();
            } catch (Throwable ignored) {
                lureActive = false;
            }

            // True when:
            //  - this raven is scroll-summoned, AND
            //  - its owner is online in this level.
            boolean scrollSummonFollowLock = isScrollSummonFollowLockActive();

            boolean canFollow =
                    scrollSummonFollowLock
                            || (owner != null && this.isTame() && getFollowCooldownTicks() <= 0)
                            || lureActive;

            if (playerAvoidanceOverrideTicks <= 0) {
                if (canFollow) {
                    // NEW: Do NOT gate follow by home bounds anymore.
                    // Home bounds are still respected for idle/roam/landing,
                    // but FOLLOW_OWNER is allowed to go anywhere with the player.
                    //
                    // Additionally: if this is a scroll-summoned raven with an
                    // online owner, we *force* FOLLOW_OWNER every tick
                    // (ignoring follow cooldown), so it never drifts back into
                    // ROAM_FLY while manually summoned.
                    setAIState(RavenAIState.FOLLOW_OWNER);
                } else {
                    RavenAIState st = getAIStateForDebug();
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
                        //LOG.warn("[RavenEntity] aiStep: stuck-teleport guard failed safely: {}", t.toString());
                    }
                }
            }

            if (!skipStuckTeleport) {
                teleportation.tickTeleportRecoverySampler(this);
            } else if (this.tickCount % 40 == 0) {
                /* LOG.debug(
                        "[RavenEntity] Skipping teleportRecoverySampler while parked near follow target. pos={} aiState={}",
                        this.position(),
                        getAIState()
                );
                 */
            }

            teleportation.tickTeleportFxServer(this);

        } catch (Throwable t) {
            //LOG.error("[RavenEntity] aiStep failed", t);
        }
    }

    private void tryArmLureFollowFromNearbyPlayers() {
        try {
            if (this.level() == null || this.level().isClientSide) {
                return;
            }
            if (!this.isAlive()) {
                return;
            }
            if (this.isTame()) {
                return;
            }
            if (isDeliveryRaven()) {
                return;
            }

            if (lureCheckCooldownTicks > 0) {
                lureCheckCooldownTicks--;
                return;
            }
            lureCheckCooldownTicks = LURE_CHECK_INTERVAL_TICKS;

            Player candidate = findNearestPlayerHoldingLure(LURE_PLAYER_RADIUS);
            if (candidate == null) {
                return;
            }

            double dist = Math.sqrt(Math.max(0.0D, this.position().distanceToSqr(candidate.position())));
            requestLureFollowPlayer(candidate, dist);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] tryArmLureFollowFromNearbyPlayers failed safely: {}", t.toString());
            }
        }
    }

    @Nullable
    private Player findNearestPlayerHoldingLure(double radius) {
        try {
            if (this.level() == null) {
                return null;
            }
            Vec3 pos = this.position();
            double r = Math.max(0.1D, radius);
            double bestD2 = Double.MAX_VALUE;
            Player best = null;

            for (Player p : this.level().players()) {
                if (p == null || !p.isAlive() || p.isSpectator()) {
                    continue;
                }
                if (!isHoldingLureNugget(p)) {
                    continue;
                }
                double d2 = p.position().distanceToSqr(pos);
                if (d2 <= (r * r) && d2 < bestD2) {
                    bestD2 = d2;
                    best = p;
                }
            }

            return best;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] findNearestPlayerHoldingLure failed safely: {}", t.toString());
            }
            return null;
        }
    }

    private boolean isHoldingLureNugget(Player player) {
        try {
            if (player == null) {
                return false;
            }
            ItemStack main = player.getMainHandItem();
            if (main != null && !main.isEmpty()
                    && (main.is(Items.GOLD_NUGGET) || main.is(Items.IRON_NUGGET))) {
                return true;
            }
            ItemStack off = player.getOffhandItem();
            return off != null && !off.isEmpty()
                    && (off.is(Items.GOLD_NUGGET) || off.is(Items.IRON_NUGGET));
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean tickDeliveryRaven() {
        try {
            if (!isDeliveryRaven()) {
                return false;
            }
            if (!(this.level() instanceof ServerLevel serverLevel)) {
                return false;
            }

            ServerPlayer target = resolveDeliveryTarget(serverLevel);
            if (target == null) {
                clearFlyTarget();
                flyTargetTimeoutTicks = 0;
                this.setDeltaMovement(this.getDeltaMovement().scale(0.6D));
                return true;
            }

            Vec3 targetPos = target.position();
            Vec3 followGoal = computeDeliveryFollowGoal(target);
            if (followGoal == null) {
                followGoal = targetPos;
            }
            double distSqr = this.position().distanceToSqr(targetPos);
            if (distSqr > DELIVERY_TELEPORT_DISTANCE_SQR) {
                Vec3 teleportPos = findDeliveryTeleportPos(serverLevel, target);
                if (teleportPos != null) {
                    if (teleportation.teleportSeqPhase == Teleportation.TeleportSeqPhase.NONE) {
                        long fxSeed = this.getUUID().getLeastSignificantBits()
                                ^ (long) this.tickCount
                                ^ BlockPos.containing(teleportPos).asLong()
                                ^ 0xC0FFEE5EEDL;
                        teleportation.startTeleportSequence(teleportPos, fxSeed, "delivery teleport", this);
                        playRavenArriveSound();
                    }
                    return true;
                }
            }

            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            if (deliveryRearmTicks > 0) {
                deliveryRearmTicks--;
            }

            if (flyTarget == null || flyTargetTimeoutTicks <= 0 || deliveryRearmTicks <= 0) {
                setFlyTargetNoClamp(followGoal, DELIVERY_FLY_TARGET_TTL_TICKS);
                deliveryRearmTicks = DELIVERY_REARM_INTERVAL_TICKS;
            }

            if (flyTargetTimeoutTicks > 0) {
                flyTargetTimeoutTicks--;
            }

            if (this.position().distanceTo(followGoal) <= DELIVERY_ARRIVE_DIST) {
                clearFlyTarget();
                flyTargetTimeoutTicks = 0;
                this.setDeltaMovement(this.getDeltaMovement().scale(0.6D));
                return true;
            }

            flyTowardTarget(FLY_SPEED_BASE);
            return true;

        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isDeliveryRaven() {
        try {
            if (Services.PLATFORM.isScrollSummonedRaven(this)) {
                return true;
            }
            return this.getTags().contains(TAG_COURIER_RAVEN);
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    private ServerPlayer resolveDeliveryTarget(ServerLevel level) {
        try {
            UUID ownerId = this.getOwnerUUID();
            if (ownerId == null) {
                return null;
            }
            return level.getServer().getPlayerList().getPlayer(ownerId);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private Vec3 findDeliveryTeleportPos(ServerLevel level, ServerPlayer target) {
        try {
            Vec3 followGoal = computeDeliveryFollowGoal(target);
            if (followGoal == null) {
                return null;
            }

            BlockPos base = BlockPos.containing(followGoal);
            int minY = level.getMinBuildHeight() + 1;
            int maxY = level.getMaxBuildHeight() - 2;
            int baseY = Mth.clamp(base.getY(), minY, maxY);

            int[][] offsets = new int[][]{
                    {0, 0, 0},
                    {1, 0, 0},
                    {-1, 0, 0},
                    {0, 0, 1},
                    {0, 0, -1},
                    {2, 0, 0},
                    {-2, 0, 0},
                    {0, 0, 2},
                    {0, 0, -2},
                    {1, 0, 1},
                    {-1, 0, -1},
                    {1, 0, -1},
                    {-1, 0, 1},
                    {0, 1, 0},
                    {0, 2, 0}
            };

            for (int[] o : offsets) {
                BlockPos pos = new BlockPos(base.getX() + o[0], Mth.clamp(baseY + o[1], minY, maxY), base.getZ() + o[2]);
                if (!level.getWorldBorder().isWithinBounds(pos)) {
                    continue;
                }
                if (level.isEmptyBlock(pos) && level.isEmptyBlock(pos.above())) {
                    return new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private Vec3 computeDeliveryFollowGoal(ServerPlayer target) {
        try {
            if (target == null) {
                return null;
            }
            Vec3 playerPos = target.position();
            Vec3 goal = null;

            if (lureFollowTame != null) {
                Vec3 front = lureFollowTame.computeFollowFrontPosition(target);
                if (front != null) {
                    goal = lureFollowTame.computeSafeFollowGoalXZLockedY(front);
                }
            }

            if (goal == null) {
                goal = playerPos.add(0.0D, 1.5D, 0.0D);
            }

            double dist = goal.distanceTo(playerPos);
            if (dist < 1.75D) {
                Vec3 look = target.getLookAngle();
                double lx = look.x;
                double lz = look.z;
                double len = Math.sqrt(lx * lx + lz * lz);
                if (len < 1.0E-4D) {
                    lx = 1.0D;
                    lz = 0.0D;
                    len = 1.0D;
                } else {
                    lx /= len;
                    lz /= len;
                }
                Vec3 adjusted = new Vec3(
                        playerPos.x + lx * 2.5D,
                        goal.y,
                        playerPos.z + lz * 2.5D
                );
                if (lureFollowTame != null) {
                    Vec3 safe = lureFollowTame.computeSafeFollowGoalXZLockedY(adjusted);
                    goal = (safe != null) ? safe : adjusted;
                } else {
                    goal = adjusted;
                }
            }

            return goal;
        } catch (Throwable t) {
            return null;
        }
    }

    public void recordPerchCorner(@Nullable BlockPos cornerTop) {
        try {
            if (cornerTop == null) {
                return;
            }
            if (this.level() == null) {
                return;
            }
            this.lastPerchCorner = cornerTop;
            this.lastPerchGameTime = this.level().getGameTime();
            this.perchSearchFailCount = 0;
        } catch (Throwable ignored) {
        }
    }

    private boolean shouldReturnToLastPerch(long nowGameTime) {
        try {
            if (lastPerchCorner == null) {
                return false;
            }
            if ((nowGameTime - lastPerchGameTime) < PERCH_RETURN_MIN_TICKS) {
                return false;
            }
            return perchSearchFailCount >= PERCH_RETURN_FAILS;
        } catch (Throwable t) {
            return false;
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
                        //LOG.debug("[RavenEntity] tickIdleGround: player avoidance switched AI state -> {} (yielding idle tick)", getAIState());
                    }
                    return;
                }
            } catch (Throwable t) {
                if (this.tickCount % 40 == 0) {
                    //LOG.warn("[RavenEntity] PlayerAvoidance failed safely (idle): {}", t.toString());
                }
            }

            // Stay grounded / perched.
            this.setNoGravity(false);
            if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
                this.setAnimMode(RavenAnimMode.NO_AIR);
            }

            // Safety: do NOT allow flight targets to exist in idle.
            // If something reintroduced them (bug elsewhere), kill them and log.
            boolean hasUnexpectedIntent = (flyTarget != null && flyTargetTimeoutTicks > 0);

            if (hasUnexpectedIntent) {
                if (this.tickCount % 20 == 0) {
                    /* LOG.warn("[RavenEntity] IDLE: unexpected intent detected -> clearing. pos={} flyTarget={}",
                            this.position(),
                            flyTarget);
                     */
                }
                clearFlyTarget();
                flyTargetTimeoutTicks = 0;
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
                    /* LOG.debug("[RavenEntity] IDLE: perch invalid sample {}/{} pos={} idlePerchCorner={} bbMinY={} onGround={} vColl={} commitTicks={} idleTicksRemaining={}",
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
                     */
                }

                // Only bail once it persists beyond your grace period.
                if (idleLeafLossTicks > IDLE_LEAF_LOSS_GRACE_TICKS) {
                    // Hard reason: perch truly lost.
                    String leaveReason = "idle perch invalid for " + idleLeafLossTicks + " ticks";
                    if (this.tickCount % 20 == 0) {
                        /* LOG.debug("[RavenEntity] IDLE -> ROAM_FLY (reason={}) pos={} idlePerchCorner={}",
                                leaveReason, this.position(), landing.idlePerchCorner);
                         */
                    }

                    // Start a roam flight window (or your preferred takeoff logic)
                    setAIState(RavenAIState.ROAM_FLY);
                    idleLockTicks = 12;          // takeoff grace
                    roamTicksRemaining = 0;      // allow landing selection soon if desired
                    landing.resetLandingState("idle left: " + leaveReason, this);
                    clearFlyTarget();

                    beginRoamFlightWindow("idle left: perch invalid");
                    return;
                }
            } else {
                // Reset loss counter if we are valid again.
                if (idleLeafLossTicks > 0 && this.tickCount % 40 == 0) {
                    /* LOG.debug("[RavenEntity] IDLE: perch validity restored. pos={} idlePerchCorner={} lossTicksResetFrom={}",
                            this.position(), landing.idlePerchCorner, idleLeafLossTicks);
                     */
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
                        /* LOG.debug("[RavenEntity] IDLE: timer expired but commit active -> holding. commitTicks={} pos={}",
                                idleCommitTicks, this.position());
                         */
                    }
                } else {
                    String leaveReason = "idle timer expired";
                    if (this.tickCount % 20 == 0) {
                        /* LOG.debug("[RavenEntity] IDLE -> ROAM_FLY (reason={}) pos={} idlePerchCorner={}",
                                leaveReason, this.position(), landing.idlePerchCorner);
                         */
                    }

                    setAIState(RavenAIState.ROAM_FLY);
                    idleLockTicks = 12;
                    roamTicksRemaining = 0;
                    landing.resetLandingState("idle left: " + leaveReason, this);
                    clearFlyTarget();

                    beginRoamFlightWindow("idle left: timer expired");
                    return;
                }
            }

            // Play random sound
            tickAmbientCawing();

            // Optional: super-light periodic idle heartbeat log (rare).
            if (this.tickCount % 200 == 0) {
                /* LOG.debug("[RavenEntity] IDLE tick: pos={} idlePerchCorner={} idleTicksRemaining={} commitTicks={} leafLossTicks={}",
                        this.position(), landing.idlePerchCorner, idleTicksRemaining, idleCommitTicks, idleLeafLossTicks);
                 */
            }

        } catch (Throwable t) {
            //LOG.error("[RavenEntity] tickIdleGround failed", t);
        }
    }

    /**
     * ROAM_FLY (direct-flight roaming):
     *  - During roam window: pick a random roam target and fly directly.
     *  - After roam window: attempt landing; the "fly to overhead" phase is direct-flight.
     */
    private void tickRoamFly() {
        tickRoamFlySimple();
    }

    private void tickRoamFlySimple() {
        try {
            debugAiHeartbeat("tickRoamFlySimple");

            if (playerAvoidanceRearmCooldownTicks > 0) {
                playerAvoidanceRearmCooldownTicks--;
            }
            if (playerAvoidanceOverrideTicks > 0) {
                playerAvoidanceOverrideTicks--;
            }

            RandomSource rnd = this.getRandom();

            tickAmbientCawing();

            try {
                PlayerAvoidance.tryTriggerPlayerAvoidance(this);
            } catch (Throwable ignored) {
            }

            if (playerAvoidanceOverrideTicks > 0) {
                this.setNoGravity(true);
                if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                    this.setAnimMode(RavenAnimMode.IN_AIR);
                }
                if (flyTarget != null && flyTargetTimeoutTicks > 0) {
                    flyTargetTimeoutTicks--;
                    flyTowardTarget(FLY_SPEED_BASE);
                }
                try {
                    teleportation.tickRandomFlightTeleportBlink(this);
                } catch (Throwable ignored) {
                }
                return;
            }

            if (idleLockTicks > 0) {
                idleLockTicks--;
                this.setNoGravity(true);
                if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                    this.setAnimMode(RavenAnimMode.IN_AIR);
                }
                ensureRoamFlyTarget(rnd);
                flyTowardTarget(FLY_SPEED_BASE);
                try {
                    teleportation.tickRandomFlightTeleportBlink(this);
                } catch (Throwable ignored) {
                }
                return;
            }

            if (roamTicksRemaining > 0) {
                roamTicksRemaining--;
                this.setNoGravity(true);
                if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                    this.setAnimMode(RavenAnimMode.IN_AIR);
                }
                ensureRoamFlyTarget(rnd);
                flyTowardTarget(FLY_SPEED_BASE);
                try {
                    teleportation.tickRandomFlightTeleportBlink(this);
                } catch (Throwable ignored) {
                }
                return;
            }

            BlockPos leaf = landing.pickLandingLeafBlock(rnd, this);
            if (leaf != null) {
                perchSearchFailCount = 0;
                landing.landingLeafPos = leaf;
                Vec3 perchPos = landing.perchCenterTop(leaf, this);
                clearFlyTarget();
                postTeleportIntent = PostTeleportIntent.PERCH;

                long fxSeed = this.getUUID().getLeastSignificantBits()
                        ^ (long) this.tickCount
                        ^ BlockPos.containing(perchPos).asLong()
                        ^ 0xC01DCAFE4B1DD00DL;

                teleportation.startTeleportSequence(perchPos, fxSeed, "roam teleport perch", this);
                return;
            }

            perchSearchFailCount++;
            if (shouldReturnToLastPerch(this.level().getGameTime())) {
                BlockPos corner = lastPerchCorner;
                if (corner != null && landing.isValidPerchCornerAtTopY(corner, this)) {
                    landing.landingLeafPos = corner;
                    Vec3 perchPos = landing.perchCenterTop(corner, this);
                    clearFlyTarget();
                    postTeleportIntent = PostTeleportIntent.PERCH;

                    long fxSeed = this.getUUID().getLeastSignificantBits()
                            ^ (long) this.tickCount
                            ^ BlockPos.containing(perchPos).asLong()
                            ^ 0x9A0F5EED0BEEF00DL;

                    teleportation.startTeleportSequence(perchPos, fxSeed, "roam fallback to last perch", this);
                    perchSearchFailCount = 0;
                    return;
                } else {
                    lastPerchCorner = null;
                }
            }

            roamTicksRemaining = 20 + rnd.nextInt(60);
            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }
            ensureRoamFlyTarget(rnd);
            try {
                teleportation.tickRandomFlightTeleportBlink(this);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureRoamFlyTarget(RandomSource rnd) {
        if (roamRearmTicks > 0) {
            roamRearmTicks--;
        }

        if (flyTargetTimeoutTicks > 0) {
            flyTargetTimeoutTicks--;
        }

        if (flyTarget == null || flyTargetTimeoutTicks <= 0 || roamRearmTicks <= 0) {
            Vec3 roamTarget = pickRoamFallbackTarget(rnd);
            if (roamTarget != null) {
                setFlyTarget(roamTarget, ROAM_REARM_INTERVAL_TICKS);
                roamRearmTicks = ROAM_REARM_INTERVAL_TICKS;
            } else {
                clearFlyTarget();
            }
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

            Vec3 roamTarget = pickRoamFallbackTarget(rnd);
            if (roamTarget != null) {
                Vec3 start = findAirPosNear(roamTarget);
                if (start != null) {
                    postTeleportIntent = PostTeleportIntent.ROAM_FLIGHT;
                    long fxSeed = this.getUUID().getLeastSignificantBits()
                            ^ (long) this.tickCount
                            ^ BlockPos.containing(start).asLong()
                            ^ 0xA11F1ED00D5EEDL;
                    teleportation.startTeleportSequence(start, fxSeed, "begin roam: " + reason, this);
                }
                setFlyTarget(roamTarget, ROAM_REARM_INTERVAL_TICKS);
                roamRearmTicks = ROAM_REARM_INTERVAL_TICKS;
            } else {
                clearFlyTarget();
                roamRearmTicks = 0;
            }

            if (idleLockTicks <= 0) {
                idleLockTicks = 12;
            }

            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                /* LOG.debug("[RavenEntity] beginRoamFlightWindow(reason={}) -> roamTicksRemaining={} takeoffLock={} flyTarget={}",
                        reason, roamTicksRemaining, idleLockTicks, flyTarget);
                 */
            }
        } catch (Throwable t) {
            //LOG.error("[RavenEntity] beginRoamFlightWindow failed (reason={})", reason, t);
            roamTicksRemaining = Math.max(1, ROAM_MIN_TICKS);

            if (idleLockTicks <= 0) {
                idleLockTicks = 12;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Player interaction (RMB) – bridge into LureFollowTame for golden nugget taming
    // ---------------------------------------------------------------------------------------------
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        try {
            // Unthrottled debug so we KNOW if this ever fires
            /* LOG.debug("[RavenEntity] mobInteract ENTER: player={} hand={} pos={} side={}",
                    (player == null ? "null" : player.getName().getString()),
                    hand,
                    this.position(),
                    this.level() == null ? "null" : (this.level().isClientSide ? "CLIENT" : "SERVER"));
             */

            if (player == null) {
                return InteractionResult.PASS;
            }

            ItemStack stack = player.getItemInHand(hand);
            RavenArmorVisual heldArmorVisual = FFItems.getRavenArmorVisual(stack);

            // ----------------------------------------------------------------------
            // Armor equip visual hook:
            // - Owner can RMB their tamed raven with raven armor to set visual armor.
            // - Persist to player bound-raven data so future spawns use same texture.
            // ----------------------------------------------------------------------
            if (heldArmorVisual != RavenArmorVisual.NONE) {
                boolean isOwnerRaven = false;
                try {
                    isOwnerRaven = this.isTame() && this.isOwnedBy(player);
                } catch (Throwable ignored) {
                    isOwnerRaven = false;
                }

                if (!isOwnerRaven) {
                    return InteractionResult.PASS;
                }

                if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                    try {
                        RavenArmorVisual equippedBefore = this.getRavenArmorVisual();
                        if (equippedBefore == heldArmorVisual) {
                            return InteractionResult.sidedSuccess(false);
                        }

                        this.setRavenArmorVisual(heldArmorVisual);
                        TamedRavenPlayerData.setEquippedArmorVisual(serverPlayer, heldArmorVisual);

                        if (!player.getAbilities().instabuild) {
                            stack.shrink(1);
                        }

                        ItemStack previousArmor = FFItems.createRavenArmorStack(equippedBefore);
                        if (!previousArmor.isEmpty()) {
                            boolean added = player.getInventory().add(previousArmor);
                            if (!added) {
                                this.spawnAtLocation(previousArmor);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                return InteractionResult.sidedSuccess(this.level().isClientSide);
            }

            if ((stack == null || stack.isEmpty()) && player.isCrouching()) {
                boolean isOwnerRaven = false;
                try {
                    isOwnerRaven = this.isTame() && this.isOwnedBy(player);
                } catch (Throwable ignored) {
                    isOwnerRaven = false;
                }

                if (!isOwnerRaven) {
                    return InteractionResult.PASS;
                }

                if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                    try {
                        RavenArmorVisual equippedBefore = this.getRavenArmorVisual();
                        if (equippedBefore != RavenArmorVisual.NONE) {
                            this.setRavenArmorVisual(RavenArmorVisual.NONE);
                            TamedRavenPlayerData.setEquippedArmorVisual(serverPlayer, RavenArmorVisual.NONE);

                            ItemStack previousArmor = FFItems.createRavenArmorStack(equippedBefore);
                            if (!previousArmor.isEmpty()) {
                                boolean added = player.getInventory().add(previousArmor);
                                if (!added) {
                                    this.spawnAtLocation(previousArmor);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                return InteractionResult.sidedSuccess(this.level().isClientSide);
            }

            // ----------------------------------------------------------------------
            // FIRST: sealed scroll -> delegate to TamedRavenScrollWatcher
            // Only consumes the interaction when:
            //   - item is the Sealed Scroll
            //   - this raven is tamed and owned by the player
            //   - handler decides to accept it (CLIENT + SERVER)
            // ----------------------------------------------------------------------
            try {
                InteractionResult scrollResult =
                        Services.PLATFORM.handleSealedScrollInteract(this, player, hand);

                if (scrollResult.consumesAction()) {
                    /* LOG.debug(
                            "[RavenEntity] mobInteract: sealed-scroll handler consumed interaction: result={} side={}",
                            scrollResult,
                            this.level() == null
                                    ? "null"
                                    : (this.level().isClientSide ? "CLIENT" : "SERVER")
                    );
                     */
                    return scrollResult;
                }
            } catch (Throwable t) {
                //LOG.warn("[RavenEntity] mobInteract scroll handler failed safely: {}", t.toString());
            }

            // ----------------------------------------------------------------------
            // Lure/taming interact hook: feed golden nuggets while lure-following.
            // All real logic lives in LureFollowTame; this is just a thin bridge.
            // ----------------------------------------------------------------------
            try {
                if (this.lureFollowTame != null) {
                    InteractionResult lureResult = this.lureFollowTame.handleTamingInteract(player, hand);

                    /* LOG.debug(
                            "[RavenEntity] mobInteract: handleTamingInteract returned {} (item={}, side={})",
                            lureResult,
                            (stack == null ? "null" : stack.toString()),
                            this.level().isClientSide ? "CLIENT" : "SERVER"
                    );
                     */

                    // If the taming logic handled the interaction (consume / success),
                    // we’re done and let it own this RMB.
                    if (lureResult.consumesAction()) {
                        return lureResult;
                    }
                } else {
                    //LOG.debug("[RavenEntity] mobInteract: lureFollowTame == null, skipping taming hook");
                }
            } catch (Throwable t) {
                //LOG.warn("[RavenEntity] mobInteract lure/tame hook failed safely: {}", t.toString());
            }

            // ----------------------------------------------------------------------
            // If neither scroll logic nor LureFollowTame cared, fall back to vanilla.
            // ----------------------------------------------------------------------
            InteractionResult base = super.mobInteract(player, hand);
            /* LOG.debug("[RavenEntity] mobInteract: super.mobInteract result={} side={}",
                    base,
                    this.level() != null && this.level().isClientSide ? "CLIENT" : "SERVER");
             */
            return base;

        } catch (Throwable t) {
            //LOG.error("[RavenEntity] mobInteract failed safely", t);
            // Fail-safe: don’t break all interactions if something goes wrong.
            return InteractionResult.PASS;
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
            Vec3 base = this.position();
            double hx = base.x;
            double hz = base.z;

            double angle = rnd.nextDouble() * (Math.PI * 2.0D);
            double radius = 4.0D + rnd.nextDouble() * 36.0D;

            double x = hx + Math.cos(angle) * radius;
            double z = hz + Math.sin(angle) * radius;
            double y = base.y + rnd.nextInt(9) - 4 + 0.25D;

            Vec3 target = new Vec3(x, y, z);
            return target;
        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                //LOG.warn("[RavenEntity] pickRoamFallbackTarget failed: {}", t.toString());
            }
            return null;
        }
    }

    @Nullable
    private Vec3 findAirPosNear(@Nullable Vec3 base) {
        if (base == null || this.level() == null) {
            return null;
        }
        try {
            BlockPos basePos = BlockPos.containing(base);
            int minY = this.level().getMinBuildHeight() + 1;
            int maxY = this.level().getMaxBuildHeight() - 2;

            int baseY = Mth.clamp(basePos.getY(), minY, maxY);
            int baseX = basePos.getX();
            int baseZ = basePos.getZ();

            int[][] offsets = new int[][]{
                    {0, 0, 0},
                    {1, 0, 0},
                    {-1, 0, 0},
                    {0, 0, 1},
                    {0, 0, -1},
                    {1, 0, 1},
                    {-1, 0, -1},
                    {1, 0, -1},
                    {-1, 0, 1},
                    {0, 1, 0},
                    {0, 2, 0}
            };

            for (int[] o : offsets) {
                BlockPos pos = new BlockPos(baseX + o[0], Mth.clamp(baseY + o[1], minY, maxY), baseZ + o[2]);
                if (!this.level().getWorldBorder().isWithinBounds(pos)) {
                    continue;
                }
                if (this.level().isEmptyBlock(pos) && this.level().isEmptyBlock(pos.above())) {
                    return new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Local avoidance hook:
     *  - If we are blocked or stuck, issue a short avoidance waypoint and keep moving.
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
            /* LOG.debug(
                    "[RavenEntity] Persistent collision: pos={} vel={} flyTarget={} dist={} stuckTicks={} collH={} collV={} rayBlocked={}",
                    pos, vel, flyTarget,
                    String.format("%.3f", dist),
                    stuckTicks,
                    this.horizontalCollision,
                    this.verticalCollision,
                    rayBlocked
            );
             */
        }

        // -----------------------------
        // 5) LOCAL AVOIDANCE
        // -----------------------------
        if (pathBlocked && avoidanceCooldownTicks <= 0) {
            avoidanceCooldownTicks = AVOIDANCE_COOLDOWN_TICKS;

            Vec3 avoidance = computeAvoidanceWaypoint(pos, flyTarget, rnd);
            if (avoidance != null) {
                setFlyTarget(avoidance, 4 * 20);
                stuckTicks = 0;

                if (this.tickCount % 20 == 0) {
                    //LOG.debug("[RavenEntity] Avoidance waypoint issued: {}", avoidance);
                }
                return;
            }
        }

        // -----------------------------
        // 6) LAST RESORT: STOP (NO BUZZING)
        // -----------------------------
        if (stuckTicks >= STUCK_TICKS_THRESHOLD) {
            clearFlyTarget();
            this.setDeltaMovement(Vec3.ZERO);

            if (this.tickCount % 20 == 0) {
                //LOG.warn("[RavenEntity] Stuck hard-stop to prevent buzzing: pos={} target={}", pos, flyTarget);
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

            Vec3 avoidance = computeAvoidanceWaypoint(pos, flyTarget, rnd);
            if (avoidance != null) {
                setFlyTarget(avoidance, 3 * 20);
                if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                    // LOG.debug("[RavenEntity] Landing-approach avoidance (blocked={}, stuckTicks={}) -> {}", pathBlocked, stuckTicks, avoidance);
                }
                return;
            }

            if (this.tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
                /* LOG.debug("[RavenEntity] Landing-approach avoidance failed (blocked={}, stuckTicks={}), continuing without retarget",
                        pathBlocked, stuckTicks);
                 */
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
                //LOG.warn("[RavenEntity] isDirectPathBlocked failed: {}", t.toString());
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
                //LOG.warn("[RavenEntity] computeAvoidanceWaypoint failed: {}", t.toString());
            }
            return null;
        }
    }

    private void flyTowardTarget(double speed) {
        try {
            Vec3 actualTarget = flyTarget;

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
                    /* LOG.debug("[RavenEntity] flyTowardTarget: collision-nudge applied (collH={}, collV={}) pos={} target={} vel={}",
                            collH, collV, pos, actualTarget, newVel);
                     */
                }
            }

            this.setDeltaMovement(newVel);

            float yaw = (float) (Math.atan2(newVel.z, newVel.x) * (180.0D / Math.PI)) - 90.0F;
            this.setYRot(yaw);
            this.setYHeadRot(yaw);
            this.yBodyRot = yaw;

            if (this.tickCount % 40 == 0) {
                /* LOG.debug(
                        "[RavenEntity] flyTowardTarget: heading toward target={} dist={}",
                        actualTarget,
                        String.format("%.3f", dist)
                );
                 */
            }

        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                //LOG.warn("[RavenEntity] flyTowardTarget failed: {}", t.toString());
            }
        }
    }

    public BlockPos getHomePosPublic() {
        try {
            return this.blockPosition();
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] getHomePosPublic failed safely: {}", t.toString());
            }
            return this.blockPosition();
        }
    }

    public int getHomeRadiusBlocksPublic() {
        try {
            return 0;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] getHomeRadiusBlocksPublic failed safely: {}", t.toString());
            }
            return 50;
        }
    }

    @org.jetbrains.annotations.Nullable
    public Vec3 getHomeCenterVec() {
        return this.position();
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

            Vec3 usedGoal = clampTargetToHomeBounds(fleeTarget);
            setFlyTarget(usedGoal, 6 * 20);

            if (this.tickCount % 20 == 0) {
                /* LOG.debug(
                        "[RavenEntity] forceRoamFlightFromThreat: armed fly target={} fromPos={} playerPos={}",
                        usedGoal,
                        ravenPos,
                        playerPos
                );
                 */
            }

            // Prevent immediate re-trigger of calm behaviors (landing etc.)
            idleCommitTicks = 40;

        } catch (Throwable t) {
            //LOG.error("[RavenEntity] forceRoamFlightFromThreat failed", t);
        }
    }

    private record RavenChestPerchAssignment(@NotNull String dimensionId, long blockPos) {
    }

    private record DamageAttackerInfo(boolean player, @NotNull String label) {
    }

    private @Nullable RavenChestPerchAssignment readAssignedRavenChestPerch() {
        try {
            CompoundTag root = Services.PLATFORM.getEntityPersistentData(this);
            if (root == null) {
                return null;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return null;
            }
            if (!ffTag.getBoolean(NBT_RAVEN_CHEST_PERCH_ASSIGNED)) {
                return null;
            }
            if (!ffTag.contains(NBT_RAVEN_CHEST_PERCH_DIMENSION)
                    || !ffTag.contains(NBT_RAVEN_CHEST_PERCH_BLOCK_POS)) {
                return null;
            }

            String dim = ffTag.getString(NBT_RAVEN_CHEST_PERCH_DIMENSION);
            long pos = ffTag.getLong(NBT_RAVEN_CHEST_PERCH_BLOCK_POS);
            if (dim == null || dim.isBlank()) {
                return null;
            }
            return new RavenChestPerchAssignment(dim, pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isAssignedRavenChestPerch() {
        return readAssignedRavenChestPerch() != null;
    }

    private void clearAssignedRavenChestPerch() {
        try {
            CompoundTag root = Services.PLATFORM.getEntityPersistentData(this);
            if (root == null) {
                return;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return;
            }
            ffTag.remove(NBT_RAVEN_CHEST_PERCH_ASSIGNED);
            ffTag.remove(NBT_RAVEN_CHEST_PERCH_DIMENSION);
            ffTag.remove(NBT_RAVEN_CHEST_PERCH_BLOCK_POS);
            root.put(Constants.MOD_ID, ffTag);
        } catch (Throwable ignored) {
        }
    }

    private float defaultYawFromRavenChest(@NotNull BlockPos chestPos) {
        try {
            BlockState state = this.level().getBlockState(chestPos);
            if (state.hasProperty(RavenChestBlock.FACING)) {
                Direction facing = state.getValue(RavenChestBlock.FACING);
                return Mth.wrapDegrees(facing.toYRot());
            }
        } catch (Throwable ignored) {
        }
        return 0.0F;
    }

    private void enforceAssignedRavenChestPerch() {
        RavenChestPerchAssignment assignment = readAssignedRavenChestPerch();
        if (assignment == null) {
            return;
        }
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        String currentDim = serverLevel.dimension().location().toString();
        if (!assignment.dimensionId().equals(currentDim)) {
            return;
        }

        BlockPos chestPos = BlockPos.of(assignment.blockPos());
        if (!serverLevel.getBlockState(chestPos).is(net.z2six.featheredfriend.registry.FFBlocks.RAVEN_CHEST.get())) {
            clearAssignedRavenChestPerch();
            if (this.getAIState() == RavenAIState.RAVEN_CHEST_PERCH) {
                this.setAIState(RavenAIState.IDLE_GROUND);
            }
            this.setNoGravity(false);
            RavenLogService.logForRavenOwnerKey(
                    this,
                    RavenLogCategory.PERCH,
                    "log.featheredfriend.perch.assignment_cleared_invalid"
            );
            return;
        }

        float yaw = defaultYawFromRavenChest(chestPos);
        float pitch = 0.0F;
        double x = chestPos.getX() + RAVEN_CHEST_PERCH_OFFSET_X;
        double y = chestPos.getY() + RAVEN_CHEST_PERCH_OFFSET_Y;
        double z = chestPos.getZ() + RAVEN_CHEST_PERCH_OFFSET_Z;

        this.moveTo(x, y, z, yaw, pitch);
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.yBodyRot = yaw;
        this.setXRot(pitch);
        this.setRavenChestPerchLockRotation(yaw, pitch);
        this.setDeltaMovement(Vec3.ZERO);
        this.setNoGravity(true);
        this.setNoAi(false);
        if (this.getAIState() != RavenAIState.RAVEN_CHEST_PERCH) {
            this.setAIState(RavenAIState.RAVEN_CHEST_PERCH);
        }
        if (this.getAnimMode() != RavenAnimMode.NO_AIR) {
            this.setAnimMode(RavenAnimMode.NO_AIR);
        }
    }

    private @Nullable ServerPlayer resolveOwnerPlayerServerSafe() {
        try {
            if (!(this.level() instanceof ServerLevel serverLevel)) {
                return null;
            }
            UUID ownerUuid = this.getOwnerUUID();
            if (ownerUuid == null) {
                return null;
            }
            return serverLevel.getServer().getPlayerList().getPlayer(ownerUuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void tickRavenChestPerchThreatLogging() {
        if (this.level().isClientSide) {
            return;
        }
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.getAIState() != RavenAIState.RAVEN_CHEST_PERCH) {
            return;
        }

        if (this.ravenChestPerchThreatCheckCooldownTicks > 0) {
            this.ravenChestPerchThreatCheckCooldownTicks--;
            return;
        }
        this.ravenChestPerchThreatCheckCooldownTicks = 20;

        double detectionRadius = Math.max(0.0D, this.getEffectiveThreatDetectionRadiusBlocks());
        if (detectionRadius <= 0.0D) {
            if (!this.ravenChestPerchSeenHostiles.isEmpty()) {
                this.ravenChestPerchSeenHostiles.clear();
                RavenLogService.logForRavenOwnerKey(
                        this,
                        RavenLogCategory.PERCH,
                        "log.featheredfriend.perch.threat_state_cleared"
                );
            }
            return;
        }

        List<Mob> hostiles = serverLevel.getEntitiesOfClass(
                Mob.class,
                this.getBoundingBox().inflate(detectionRadius, detectionRadius, detectionRadius),
                mob -> mob != null
                        && mob.isAlive()
                        && !mob.isRemoved()
                        && mob instanceof Enemy
                        && mob.distanceToSqr(this) <= (detectionRadius * detectionRadius)
        );

        Map<UUID, String> current = new HashMap<>();
        if (hostiles != null) {
            for (Mob hostile : hostiles) {
                if (hostile == null) {
                    continue;
                }
                current.put(hostile.getUUID(), hostile.getType().getDescription().getString());
            }
        }

        if (!current.isEmpty()) {
            for (Map.Entry<UUID, String> e : current.entrySet()) {
                if (this.ravenChestPerchSeenHostiles.containsKey(e.getKey())) {
                    continue;
                }
                RavenLogService.logForRavenOwnerKey(
                        this,
                        RavenLogCategory.PERCH,
                        "log.featheredfriend.perch.threat_detected",
                        String.format("%.1f", detectionRadius),
                        e.getValue()
                );
            }
        }

        if (!this.ravenChestPerchSeenHostiles.isEmpty()) {
            for (Map.Entry<UUID, String> e : this.ravenChestPerchSeenHostiles.entrySet()) {
                if (current.containsKey(e.getKey())) {
                    continue;
                }
                RavenLogService.logForRavenOwnerKey(
                        this,
                        RavenLogCategory.PERCH,
                        "log.featheredfriend.perch.threat_ended_enemy",
                        e.getValue()
                );
            }
        }

        this.ravenChestPerchSeenHostiles.clear();
        this.ravenChestPerchSeenHostiles.putAll(current);
    }

    private @NotNull String getSafeRavenNameForDespawn() {
        try {
            if (this.getCustomName() != null) {
                String s = this.getCustomName().getString();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return Component.translatable("entity.featheredfriend.raven").getString();
    }

    private boolean handleAssignedRavenChestPerchHit(@Nullable DamageSource source, float amount) {
        try {
            if (!(this.level() instanceof ServerLevel serverLevel)) {
                return false;
            }
            if (!this.isAlive()) {
                return false;
            }

            boolean dodged = this.getRandom().nextFloat() < this.getEffectiveDodgeChanceFraction();
            if (!dodged) {
                this.setHealth(Math.max(0.0F, this.getHealth() - DAMAGE_PER_LANDED_HIT));
                spawnSuccessfulDamageFeatherBurst(serverLevel);
                try {
                    Services.PLATFORM.handleCourierRavenLandedHit(this);
                } catch (Throwable ignored) {
                }
                logPerchedHit(source, false);
            } else {
                logPerchedHit(source, true);
            }

            try {
                TamedRaven tamed = this.getTamedRavenModule();
                tamed.beginDespawnWithFx(
                        serverLevel,
                        resolveOwnerPlayerServerSafe(),
                        getSafeRavenNameForDespawn(),
                        false
                );
            } catch (Throwable t) {
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] handleAssignedRavenChestPerchHit: despawn FX failed safely: {}", t.toString());
                }
                this.discard();
            }

            RavenLogService.logForRavenOwnerKey(
                    this,
                    RavenLogCategory.PERCH,
                    "log.featheredfriend.perch.despawned_after_attack"
            );

            if (this.tickCount % 20 == 0) {
                LOG.debug("[RavenEntity] handleAssignedRavenChestPerchHit: dodged={} amount={} src={}",
                        dodged,
                        amount,
                        source == null ? "null" : source.toString());
            }
            return !dodged;
        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                LOG.warn("[RavenEntity] handleAssignedRavenChestPerchHit failed safely: {}", t.toString());
            }
            return false;
        }
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        try {
            if (!this.level().isClientSide && isSuffocationDamage(source)) {
                return handleSuffocationDespawn(source);
            }

            if (isAssignedRavenChestPerch()) {
                if (this.level().isClientSide) {
                    return false;
                }
                return handleAssignedRavenChestPerchHit(source, amount);
            }

            // Let vanilla short-circuits happen first if we are truly invulnerable to this.
            // (Note: even if invulnerable, we still might want to teleport; requirement says ALWAYS teleport on hit,
            // but if hurt() is never called, we can't. Here, hurt() *is* called, so we can do it.)
            if (this.isInvulnerableTo(source)) {
                if (isEntityAttackSource(source)) {
                    // For direct combat sources, still run custom dodge/hit handling.
                } else {
                    // Still attempt the damage blink (server-side).
                    try {
                        if (!this.level().isClientSide) {
                            // force post-teleport roam even if we don't take damage
                            teleportation.requestDamageBlinkTeleport(source, amount, "invulnerable-hurt", this);
                        }
                    } catch (Throwable ignored) {
                    }
                    RavenLogService.logForRavenOwnerKey(
                            this,
                            RavenLogCategory.COMBAT,
                            "log.featheredfriend.combat.hit_ignored_invulnerable"
                    );
                    return false;
                }
            }

            // Our combat blink logic (server authoritative).
            boolean dodge = false;
            try {
                boolean wouldDieIfLanded = this.getHealth() <= DAMAGE_PER_LANDED_HIT;
                dodge = net.z2six.featheredfriend.entity.raven.modules.DamageDodge.handleHurt(
                        this,
                        source,
                        amount,
                        wouldDieIfLanded,
                        this.getEffectiveDodgeChanceFraction()
                );
            } catch (Throwable t) {
                if (this.tickCount % 80 == 0) {
                    //LOG.warn("[RavenEntity] hurt: DamageDodge failed safely: {}", t.toString());
                }
                dodge = false;
            }

            if (dodge) {
                // Dodged: no damage applied.
                logCombatHit(source, true);
                if (this.tickCount % 20 == 0) {
                    /* LOG.debug("[RavenEntity] hurt: DODGED damage. amount={} src={} pos={}",
                            amount,
                            (source == null ? "null" : source.toString()),
                            this.position());
                     */
                }
                return false;
            }

            // Not dodged: each landed hit counts as one hit-point regardless of incoming amount.
            boolean result = super.hurt(source, DAMAGE_PER_LANDED_HIT);
            if (!result && !this.level().isClientSide) {
                float before = this.getHealth();
                float after = Math.max(0.0F, before - DAMAGE_PER_LANDED_HIT);
                if (after < before) {
                    this.setHealth(after);
                    if (after <= 0.0F) {
                        this.die(source);
                    }
                    result = true;
                }
            }

            if (result && !this.level().isClientSide) {
                if (this.level() instanceof ServerLevel serverLevel) {
                    spawnSuccessfulDamageFeatherBurst(serverLevel);
                }
                try {
                    Services.PLATFORM.handleCourierRavenLandedHit(this);
                } catch (Throwable ignored) {
                }
                logCombatHit(source, false);
            }

            if (this.tickCount % 20 == 0) {
                /* LOG.debug("[RavenEntity] hurt: took damage. applied={} amount={} src={} hpNow={} pos={}",
                        result,
                        amount,
                        (source == null ? "null" : source.toString()),
                        this.getHealth(),
                        this.position());
                 */
            }

            return result;

        } catch (Throwable t) {
            // Fail-safe: never crash in hurt. Apply vanilla behavior if we can.
            if (this.tickCount % 40 == 0) {
                /* LOG.error("[RavenEntity] hurt failed; falling back to super.hurt. src={} amt={}",
                        (source == null ? "null" : source.toString()), amount, t);
                 */
            }
            try {
                return super.hurt(source, amount);
            } catch (Throwable t2) {
                // Absolute fail-safe: do not crash server.
                if (this.tickCount % 40 == 0) {
                    //LOG.error("[RavenEntity] super.hurt also failed; suppressing to prevent crash. {}", t2.toString());
                }
                return false;
            }
        }
    }

    private boolean isEntityAttackSource(@Nullable DamageSource source) {
        try {
            Entity e = source == null ? null : source.getEntity();
            if (e instanceof LivingEntity) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Entity d = source == null ? null : source.getDirectEntity();
            return d instanceof LivingEntity;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isSuffocationDamage(@Nullable DamageSource source) {
        if (source == null) {
            return false;
        }
        try {
            String id = source.getMsgId();
            if (id != null) {
                String s = id.trim().toLowerCase(java.util.Locale.ROOT);
                if (s.equals("inwall") || s.equals("in_wall") || s.contains("suffocat")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean handleSuffocationDespawn(@Nullable DamageSource source) {
        try {
            if (!(this.level() instanceof ServerLevel serverLevel)) {
                return false;
            }
            if (!this.isAlive()) {
                return false;
            }

            RavenLogService.logForRavenOwnerKey(
                    this,
                    RavenLogCategory.COMBAT,
                    "log.featheredfriend.combat.suffocation_despawn"
            );

            try {
                TamedRaven tamed = this.getTamedRavenModule();
                tamed.beginDespawnWithFx(
                        serverLevel,
                        resolveOwnerPlayerServerSafe(),
                        getSafeRavenNameForDespawn(),
                        false
                );
            } catch (Throwable t) {
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] handleSuffocationDespawn: despawn FX failed safely: {}", t.toString());
                }
                this.discard();
            }
            return true;
        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                LOG.warn("[RavenEntity] handleSuffocationDespawn failed safely: {}", t.toString());
            }
            return false;
        }
    }

    private void spawnSuccessfulDamageFeatherBurst(@NotNull ServerLevel serverLevel) {
        try {
            Vec3 pos = this.position();
            double fxX = pos.x();
            double fxY = pos.y() + 0.6D;
            double fxZ = pos.z();

            int count = net.z2six.featheredfriend.entity.raven.modules.FeatherParticles.getFeathersPerBurst();
            serverLevel.sendParticles(
                    net.z2six.featheredfriend.registry.FFParticles.getFeather(),
                    fxX, fxY, fxZ,
                    count,
                    0.4D, 0.25D, 0.4D,
                    0.0D
            );
        } catch (Throwable ignored) {
        }
    }

    private void logPerchedHit(@Nullable DamageSource source, boolean dodged) {
        DamageAttackerInfo attacker = resolveDamageAttacker(source);
        if (dodged) {
            if (attacker.player()) {
                RavenLogService.logForRavenOwnerKey(
                        this,
                        RavenLogCategory.COMBAT,
                        "log.featheredfriend.combat.perched_hit_dodged_by_player"
                );
            } else {
                RavenLogService.logForRavenOwnerKey(
                        this,
                        RavenLogCategory.COMBAT,
                        "log.featheredfriend.combat.perched_hit_dodged_by_entity",
                        attacker.label()
                );
            }
            return;
        }

        if (attacker.player()) {
            RavenLogService.logForRavenOwnerKey(
                    this,
                    RavenLogCategory.COMBAT,
                    "log.featheredfriend.combat.perched_hit_landed_by_player"
            );
        } else {
            RavenLogService.logForRavenOwnerKey(
                    this,
                    RavenLogCategory.COMBAT,
                    "log.featheredfriend.combat.perched_hit_landed_by_entity",
                    attacker.label()
            );
        }
    }

    private void logCombatHit(@Nullable DamageSource source, boolean dodged) {
        DamageAttackerInfo attacker = resolveDamageAttacker(source);
        if (dodged) {
            if (attacker.player()) {
                RavenLogService.logForRavenOwnerKey(
                        this,
                        RavenLogCategory.COMBAT,
                        "log.featheredfriend.combat.hit_dodged_by_player"
                );
            } else {
                RavenLogService.logForRavenOwnerKey(
                        this,
                        RavenLogCategory.COMBAT,
                        "log.featheredfriend.combat.hit_dodged_by_entity",
                        attacker.label()
                );
            }
            return;
        }

        String healthNow = String.format("%.2f", this.getHealth());
        String healthMax = String.format("%.2f", this.getMaxHealth());
        if (attacker.player()) {
            RavenLogService.logForRavenOwnerKey(
                    this,
                    RavenLogCategory.COMBAT,
                    "log.featheredfriend.combat.hit_landed_health_by_player",
                    healthNow,
                    healthMax
            );
        } else {
            RavenLogService.logForRavenOwnerKey(
                    this,
                    RavenLogCategory.COMBAT,
                    "log.featheredfriend.combat.hit_landed_health_by_entity",
                    attacker.label(),
                    healthNow,
                    healthMax
            );
        }
    }

    private @NotNull DamageAttackerInfo resolveDamageAttacker(@Nullable DamageSource source) {
        try {
            Entity attacker = source == null ? null : source.getEntity();
            if (attacker instanceof Player) {
                return new DamageAttackerInfo(true, "player");
            }
            if (attacker != null) {
                String label = attacker.getType().getDescription().getString();
                if (label != null && !label.isBlank()) {
                    return new DamageAttackerInfo(false, label);
                }
            }

            Entity direct = source == null ? null : source.getDirectEntity();
            if (direct instanceof Player) {
                return new DamageAttackerInfo(true, "player");
            }
            if (direct != null) {
                String label = direct.getType().getDescription().getString();
                if (label != null && !label.isBlank()) {
                    return new DamageAttackerInfo(false, label);
                }
            }

            String fallback = source == null ? "unknown" : source.getMsgId();
            if (fallback == null || fallback.isBlank()) {
                fallback = "unknown";
            }
            return new DamageAttackerInfo(false, fallback);
        } catch (Throwable ignored) {
            return new DamageAttackerInfo(false, "unknown");
        }
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        try {
            super.die(source);
        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                /* LOG.error("[RavenEntity] die: super.die failed; suppressing to prevent crash. src={}",
                        (source == null ? "null" : source.toString()), t);
                 */
            }
        }

        try {
            if (!this.level().isClientSide && !this.ravenFeatherDeathDropDone) {
                this.ravenFeatherDeathDropDone = true;
                this.spawnAtLocation(new ItemStack(FFItems.RAVEN_FEATHER.get()));
            }
        } catch (Throwable ignored) {
        }

        // Fire TamedRaven death logic on the server side only.
        try {
            if (!this.level().isClientSide) {
                net.z2six.featheredfriend.entity.raven.modules.TamedRavenDeathHandler.onRavenDeath(this, source);
                String reason = source == null ? "unknown" : source.getMsgId();
                RavenLogService.logForRavenOwnerKey(
                        this,
                        RavenLogCategory.COMBAT,
                        "log.featheredfriend.combat.raven_died_cause",
                        reason
                );
            }
        } catch (Throwable t) {
            if (this.tickCount % 40 == 0) {
                //LOG.error("[RavenEntity] die: TamedRavenDeathHandler failed safely: {}", t.toString());
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
                //LOG.warn("[RavenEntity] getAIStateForDebug failed safely: {}", t.toString());
            }
            // Fall back to the raw state to avoid NPEs in logs.
            return getAIState();
        }
    }

    /**
     * Returns true if this raven should be hard-locked into FOLLOW_OWNER because:
     *  - it is a scroll-summoned raven (TAG_SCROLL_SUMMONED), AND
     *  - its owner is online in this ServerLevel.
     *
     * This is used to *ignore* the normal follow cooldown and prevent a
     * scroll-summoned raven from dropping back into ROAM_FLY while manually
     * summoned.
     */
    private boolean isScrollSummonFollowLockActive() {
        try {
            if (!(this.level() instanceof ServerLevel serverLevel)) {
                return false;
            }

            ServerPlayer owner = Services.PLATFORM.getScrollSummonOwnerIfHoldingScroll(serverLevel, this);
            if (owner == null) {
                return false;
            }

            // Optional debug: very low-frequency so it doesn't spam.
            if (this.tickCount % 80 == 0) {
                try {
                    String name = owner.getGameProfile().getName();
                    /* LOG.debug(
                            "[RavenEntity] Scroll-summon follow lock active for owner='{}' pos={} aiState={} followCd={}",
                            name,
                            this.position(),
                            getAIStateForDebug(),
                            getFollowCooldownTicks()
                    );
                     */
                } catch (Throwable ignored) {
                    // Ignore name / log formatting issues.
                }
            }

            return true;

        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                /* LOG.warn("[RavenEntity] isScrollSummonFollowLockActive failed safely for id={}: {}",
                        this.getId(), t.toString());
                 */
            }
            return false;
        }
    }

    // --------------------
    // Sound Engine
    // --------------------

    private void tickAmbientCawing() {
        try {
            if (this.level() == null) {
                return;
            }
            if (this.level().isClientSide) {
                // Server-side only; client will hear broadcast/local playback.
                return;
            }
            if (!this.isAlive()) {
                return;
            }

            RavenAIState st = getAIStateForDebug();
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

            // Cooldown expired: schedule the next one.
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

            if (SOUND_RAVEN_CAWING_NORMAL_ID == null) {
                if (this.tickCount % 200 == 0) {
                    //LOG.warn("[RavenEntity] tickAmbientCawing: SOUND_RAVEN_CAWING_NORMAL_ID is null");
                }
                return;
            }

            String soundId = SOUND_RAVEN_CAWING_NORMAL_ID.toString();
            float volume = 0.9F;
            float pitchMin = 0.95F;
            float pitchMax = 1.05F;

            RavenSoundEngine.playAtWithRandomPitch(
                    this.level(),
                    soundId,
                    SoundSource.NEUTRAL,
                    this.position(),
                    volume,
                    pitchMin,
                    pitchMax,
                    rnd
            );

            if (this.tickCount % 200 == 0) {
                /* LOG.debug("[RavenEntity] tickAmbientCawing: played ambient caw st={} nextDelay={}t pos={}",
                        st, ambientCawCooldownTicks, this.position());
                 */
            }

        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                //LOG.warn("[RavenEntity] tickAmbientCawing failed safely: {}", t.toString());
            }
        }
    }

    /**
     * Play the raven "arrive" sound (raven.caw_whistle) safely.
     * Call this exactly once when the raven arrives at / reaches its owner.
     */
    public void playRavenArriveSound() {
        try {
            if (this.level() == null) return;
            if (!this.isAlive()) return;

            if (SOUND_RAVEN_CAW_WHISTLE_ID == null) {
                if (this.tickCount % 200 == 0) {
                    //LOG.warn("[RavenEntity] playRavenArriveSound: SOUND_RAVEN_CAW_WHISTLE_ID is null");
                }
                return;
            }

            String soundId = SOUND_RAVEN_CAW_WHISTLE_ID.toString();

            float volume = 1.0F;
            float pitchMin = 0.98F;
            float pitchMax = 1.02F;

            RavenSoundEngine.playAtWithRandomPitch(
                    this.level(),
                    soundId,
                    SoundSource.NEUTRAL,
                    this.position(),
                    volume,
                    pitchMin,
                    pitchMax,
                    this.getRandom()
            );

            if (this.tickCount % 200 == 0) {
                //LOG.debug("[RavenEntity] playRavenArriveSound: played arrive sound at pos={}", this.position());
            }

        } catch (Throwable t) {
            if (this.tickCount % 200 == 0) {
                //LOG.warn("[RavenEntity] playRavenArriveSound failed safely: {}", t.toString());
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
                //LOG.warn("[RavenEntity] Animation controller failed: {}", t.toString());
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
                //LOG.warn("[RavenEntity] getVariant failed safely: {}", t.toString());
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
                //LOG.warn("[RavenEntity] setVariant failed safely: {}", t.toString());
            }
        }
    }

    // ----------------------------------------------------------------------
    // TamedRaven accessors
    // ----------------------------------------------------------------------
    public net.z2six.featheredfriend.entity.raven.modules.TamedRaven getTamedRavenModule() {
        return this.tamedRaven;
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
    // These wrappers exist so Landing.java can reuse the existing private flight logic
    // without changing behavior or moving more code across files.

    public Landing getLanding() {
        return this.landing;
    }

    public void landingSetFlyTarget(@org.jetbrains.annotations.Nullable Vec3 goal, int ttlTicks) {
        try {
            this.setFlyTarget(goal, ttlTicks);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] landingSetFlyTarget failed safely: {}", t.toString());
            }
        }
    }

    public void landingMaybeAvoidOrRetargetDuringFlightApproachOnly(@org.jetbrains.annotations.Nullable RandomSource rnd) {
        try {
            this.maybeAvoidOrRetargetDuringFlightApproachOnly(rnd);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] landingMaybeAvoidOrRetargetDuringFlightApproachOnly failed safely: {}", t.toString());
            }
        }
    }

    public void landingFlyTowardTarget(double speed) {
        try {
            this.flyTowardTarget(speed);
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] landingFlyTowardTarget failed safely: {}", t.toString());
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
                        //LOG.debug("[RavenEntity] landingTickFlyTargetTimeout: expired -> clearFlyTarget (why={}) target={}", why, this.flyTarget);
                    }
                    this.clearFlyTarget();
                    this.flyTargetTimeoutTicks = 0;
                }
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] landingTickFlyTargetTimeout failed safely: {}", t.toString());
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
            //LOG.warn("[RavenEntity] isPlayerAvoidanceOverrideActive failed safely: {}", t.toString());
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
                //LOG.warn("[RavenEntity] getTeleportation failed safely: {}", t.toString());
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
                //LOG.warn("[RavenEntity] resetLandingState(reason={}) failed safely: {}", reason, t.toString());
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
                //LOG.warn("[RavenEntity] requestLureFollowPlayer wrapper failed safely: {}", t.toString());
            }
        }
    }

    public boolean isLureFollowActive() {
        try {
            return lureFollowTame != null && lureFollowTame.isLureFollowActive();
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] isLureFollowActive wrapper failed safely: {}", t.toString());
            }
            return false;
        }
    }

    private int getFollowCooldownTicks() {
        try {
            return (lureFollowTame != null) ? lureFollowTame.getFollowCooldownTicks() : 0;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] getFollowCooldownTicks wrapper failed safely: {}", t.toString());
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
                //LOG.warn("[RavenEntity] setFollowCooldownTicks wrapper failed safely: {}", t.toString());
            }
        }
    }

    public boolean isFollowOverrideActive() {
        try {
            return lureFollowTame != null && lureFollowTame.isFollowOverrideActive();
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] isFollowOverrideActive wrapper failed safely: {}", t.toString());
            }
            return false;
        }
    }

    public int getGoldenNuggetsRequiredToTame() {
        try {
            return (lureFollowTame != null) ? lureFollowTame.getGoldenNuggetsRequiredToTame() : 0;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                //LOG.warn("[RavenEntity] getGoldenNuggetsRequiredToTame wrapper failed safely: {}", t.toString());
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
                //LOG.warn("[RavenEntity] setGoldenNuggetsRequiredToTame wrapper failed safely: {}", t.toString());
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
                //LOG.warn("[RavenEntity] initGoldenNuggetsRequiredToTameIfNeeded wrapper failed safely: {}", t.toString());
            }
        }
    }

}
