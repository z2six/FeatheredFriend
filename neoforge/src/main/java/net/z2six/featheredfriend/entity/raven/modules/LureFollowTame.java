// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/LureFollowTame.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.entity.raven.RavenAIState;
import net.z2six.featheredfriend.entity.raven.RavenAnimMode;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.pathing.RavenAStarPathing;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class LureFollowTame {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Owning raven instance. All Minecraft-facing state & methods are delegated to this.
     */
    private final RavenEntity raven;

    // ---------------------------------------------------------------------------------------------
    // VARIABLES -----------------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    // Random per-spawn "tame cost" (3..6 golden nuggets). Persisted via NBT.
    // NOTE: NBT read/write for this field still lives in RavenEntity; this is just the backing store.
    public static final String NBT_TAME_NUGGETS_REQUIRED = "GoldenNuggetsRequiredToTame";
    private int goldenNuggetsRequiredToTame = 0;

    // Follow owner: 3x3x3 pocket targeting + override gating
    private boolean followOverrideActive = false;
    private @Nullable BlockPos followPocketAnchor = null; // anchor block for 3x3x3 pocket (center block at y)
    private int followPocketRecalcCooldownTicks = 0;

    // Lure-follow (pre-taming) state
    private @Nullable java.util.UUID lureFollowPlayerUuid = null;
    private int lureFollowTicks = 0;

    // Small grace so follow doesn't flap off instantly if player briefly swaps items
    private static final int LURE_FOLLOW_GRACE_TICKS = 10; // 0.5s
    private static final int LURE_FOLLOW_REFRESH_TICKS = 3 * 20; // keep active for 3s per refresh

    // Follow goal stability (prevents constant re-path + vertical "hops")
    private @Nullable Vec3 followCachedGoal = null;
    private @Nullable Vec3 followCachedOwnerPos = null;
    private @Nullable Vec3 followCachedOwnerLook = null;
    private int followGoalTtlTicks = 0;
    private int followRepathCooldownTicks = 0;

    // Follow: desired distance band
    private static final double FOLLOW_MIN_DIST = 3.0D;
    private static final double FOLLOW_MAX_DIST = 8.0D;

    // Follow cooldown after bounds violation
    private static final int FOLLOW_COOLDOWN_MIN_TICKS = 5 * 20;
    private static final int FOLLOW_COOLDOWN_MAX_TICKS = 10 * 20;

    // Synced follow cooldown
    public static final EntityDataAccessor<Integer> DATA_FOLLOW_COOLDOWN_TICKS =
            RavenEntity.DATA_FOLLOW_COOLDOWN_TICKS;

    // NBT Key
    public static final String NBT_FOLLOW_CD = "RavenFollowCooldown";

    public LureFollowTame(RavenEntity raven) {
        this.raven = raven;
    }

    // ---------------------------------------------------------------------------------------------
    // ACCESSORS / BASIC HELPERS -------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    public int getFollowCooldownTicks() {
        try {
            return raven.getEntityData().get(DATA_FOLLOW_COOLDOWN_TICKS);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getFollowCooldownTicks failed safely: {}", t.toString());
            }
            return 0;
        }
    }

    public void setFollowCooldownTicks(int ticks) {
        try {
            int clamped = Math.max(0, ticks);
            int prev = 0;
            try {
                prev = raven.getEntityData().get(DATA_FOLLOW_COOLDOWN_TICKS);
            } catch (Throwable ignored) {
            }

            raven.getEntityData().set(DATA_FOLLOW_COOLDOWN_TICKS, clamped);

            if (prev != clamped) {
                if (!raven.level().isClientSide) {
                    LOG.info("[RavenEntity] FollowCooldown set: {} -> {} pos={} ai={}",
                            prev, clamped, raven.position(), raven.getAIState());
                }
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] setFollowCooldownTicks failed safely: {}", t.toString());
            }
        }
    }

    // Simple accessors for the tame-cost

    public int getGoldenNuggetsRequiredToTame() {
        return goldenNuggetsRequiredToTame;
    }

    public void setGoldenNuggetsRequiredToTame(int value) {
        goldenNuggetsRequiredToTame = value;
    }

    /**
     * Ensure goldenNuggetsRequiredToTame is initialized to [3..6].
     */
    public void initGoldenNuggetsRequiredToTameIfNeeded(String context) {
        try {
            if (goldenNuggetsRequiredToTame <= 0) {
                RandomSource rnd = raven.getRandom();
                int rolled = 3 + rnd.nextInt(4); // 3..6
                goldenNuggetsRequiredToTame = rolled;

                if (!raven.level().isClientSide && raven.tickCount % 200 == 0) {
                    LOG.debug("[RavenEntity] initGoldenNuggetsRequiredToTameIfNeeded: context={} rolled={} pos={}",
                            context, rolled, raven.position());
                }
            }
        } catch (Throwable t) {
            if (raven.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] initGoldenNuggetsRequiredToTameIfNeeded failed safely: {}", t.toString());
            }
            if (goldenNuggetsRequiredToTame <= 0) {
                goldenNuggetsRequiredToTame = 4;
            }
        }
    }

    // Expose followOverrideActive as a read-only flag

    public boolean isFollowOverrideActive() {
        return followOverrideActive;
    }

    @Nullable
    public Player getOwnerPlayerServerSafe() {
        try {
            if (!(raven.level() instanceof ServerLevel serverLevel)) {
                return null;
            }
            if (!raven.isTame()) {
                return null;
            }
            if (raven.getOwnerUUID() == null) {
                return null;
            }
            return serverLevel.getPlayerByUUID(raven.getOwnerUUID());
        } catch (Throwable t) {
            LOG.error("[RavenEntity] getOwnerPlayerServerSafe failed", t);
            return null;
        }
    }

    public boolean isLureFollowActive() {
        try {
            if (raven.level().isClientSide) {
                return false;
            }
            if (!raven.isAlive()) {
                // If the raven died while we still had lure state, clear it.
                if (lureFollowPlayerUuid != null || lureFollowTicks > 0 || followOverrideActive) {
                    clearLureFollowState("isLureFollowActive: raven not alive");
                }
                return false;
            }

            // No remembered lure => not active
            if (this.lureFollowPlayerUuid == null) {
                // Make sure all related fields are reset if they somehow weren't.
                if (lureFollowTicks != 0 || followOverrideActive || followPocketAnchor != null) {
                    clearLureFollowState("isLureFollowActive: missing UUID");
                } else {
                    this.lureFollowTicks = 0;
                }
                return false;
            }

            // Tick down the memory
            if (this.lureFollowTicks > 0) {
                this.lureFollowTicks--;
            }

            // Timer expired -> full state clear
            if (this.lureFollowTicks <= 0) {
                clearLureFollowState("isLureFollowActive: timer expired");
                return false;
            }

            // Resolve the player by stored UUID
            Player p = raven.level().getPlayerByUUID(this.lureFollowPlayerUuid);
            if (p == null || !p.isAlive() || p.isSpectator()) {
                clearLureFollowState("isLureFollowActive: lure player invalid");
                return false;
            }

            // 🔴 NEW: Lure player must STILL be holding a lure item (gold nugget)
            // in either hand, otherwise we drop lure-follow immediately.
            if (!isLureItemInHand(p)) {
                clearLureFollowState("isLureFollowActive: lure player no longer holding lure item");
                return false;
            }

            // All checks passed: lure-follow is considered active.
            return true;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isLureFollowActive failed safely: {}", t.toString());
            }
            // On any failure, drop lure state so we don't get stuck.
            clearLureFollowState("isLureFollowActive: exception");
            return false;
        }
    }

    public @Nullable Player getLureFollowPlayerServerSafe() {
        try {
            if (!(raven.level() instanceof ServerLevel serverLevel)) return null;
            if (!isLureFollowActive()) return null;

            Player p = serverLevel.getPlayerByUUID(lureFollowPlayerUuid);
            if (p == null) return null;
            if (!p.isAlive() || p.isSpectator()) return null;

            return p;
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getLureFollowPlayerServerSafe failed safely: {}", t.toString());
            }
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // LURE-FOLLOW REQUEST -------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    public void requestLureFollowPlayer(@Nullable Player player, double distToPlayer) {
        try {
            if (raven.level().isClientSide) return;
            if (!raven.isAlive()) return;

            // Basic sanity on player
            if (player == null || !player.isAlive() || player.isSpectator()) {
                // Player is not a valid lure source -> drop lure if we had one.
                if (lureFollowPlayerUuid != null || lureFollowTicks > 0 || followOverrideActive) {
                    clearLureFollowState("requestLureFollowPlayer: player invalid");
                }
                return;
            }

            // If the player is NOT currently holding a lure item (gold nugget),
            // treat this as "lure dropped" and clear state.
            if (!isLureItemInHand(player)) {
                if (lureFollowPlayerUuid != null || lureFollowTicks > 0 || followOverrideActive) {
                    clearLureFollowState("requestLureFollowPlayer: player no longer holding lure item");
                }
                return;
            }

            // Refresh lure memory
            this.lureFollowPlayerUuid = player.getUUID();
            int refresh = LURE_FOLLOW_REFRESH_TICKS;
            if (this.lureFollowTicks < refresh) {
                this.lureFollowTicks = refresh;
            }

            // Force FOLLOW_OWNER AI state + override
            if (raven.getAIState() != RavenAIState.FOLLOW_OWNER) {
                raven.setAIState(RavenAIState.FOLLOW_OWNER);
            }
            followOverrideActive = true;

            // Kill avoidance overrides so FOLLOW_OWNER truly wins
            try {
                setPrivateInt("playerAvoidanceOverrideTicks", 0);
                setPrivateInt("playerAvoidanceRearmCooldownTicks", 0);
            } catch (Throwable ignored) {}

            // Cancel idle/landing so we actually start moving
            invokeResetLandingState("lure follow arm");
            setPrivateInt("idleCommitTicks", 0);
            setPrivateInt("idleLockTicks", 0);
            setPrivateInt("idleLeafLossTicks", 0);
            setPrivateInt("roamTicksRemaining", 0);

            // Put bird into flight
            raven.setNoGravity(true);
            if (raven.getAnimMode() != RavenAnimMode.IN_AIR) {
                raven.setAnimMode(RavenAnimMode.IN_AIR);
            }

            // --------------------------------------------------
            // Pocket near player (optional, mostly for logging / sanity)
            // --------------------------------------------------
            BlockPos pocket = null;
            try {
                pocket = findFollowPocketNearPlayer(player);
            } catch (Throwable ignored) {
                pocket = null;
            }

            if (pocket == null) {
                // Fallback: hover above player, still clamped to home Y
                int py = Mth.floor(player.getY());
                int y = invokeClampYToHomeBounds(py + 3);
                pocket = new BlockPos(Mth.floor(player.getX()), y, Mth.floor(player.getZ()));
            }

            followPocketAnchor = pocket;
            followPocketRecalcCooldownTicks = 10; // small pause before re-scanning

            // Desired final follow position: in front of player's face.
            Vec3 desiredFront = computeFollowFrontPosition(player);

            // Use the same “safe goal” logic you already have for A*.
            RavenAStarPathing.Config cfg = new RavenAStarPathing.Config();
            cfg.allowLeaves = false;
            cfg.allowReplaceables = false;
            cfg.clearanceHeight = 2;
            cfg.cellSize = 1;

            Vec3 safeGoal = invokeComputeSafePathingGoal(desiredFront, cfg);

            long seed =
                    player.getUUID().getLeastSignificantBits()
                            ^ raven.getUUID().getMostSignificantBits()
                            ^ (long) raven.tickCount
                            ^ 0xF011000DL; // valid hex salt

            boolean pathOk;
            try {
                pathOk = invokeEnsurePathToWithFlags(
                        safeGoal,
                        true,
                        8 * 20,
                        seed,
                        "lure follow (front-of-player): player=" + player.getName().getString()
                                + " dist=" + String.format("%.2f", distToPlayer)
                );
            } catch (Throwable t) {
                if (raven.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] requestLureFollowPlayer ensurePathTo failed safely: {}", t.toString());
                }
                pathOk = false;
            }

            if (pathOk) {
                // A* path is in charge; do NOT "fly straight at the player".
                setPrivateObject("pathPendingGoal", safeGoal);
                invokeClearFlyTarget(); // ensure we're driven by waypoints, not a direct line
            } else {
                // If we cannot path at all, hovering is safer than ramming into walls.
                invokeClearPlannedPath("lure follow: path failed");
                invokeClearFlyTarget();
            }

            if (raven.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] LURE FOLLOW armed: player={} dist={} pocket={} desiredFront={} safeGoal={} pathOk={} lureTicks={} aiState={} pos={}",
                        player.getName().getString(),
                        String.format("%.2f", distToPlayer),
                        pocket,
                        desiredFront,
                        safeGoal,
                        pathOk,
                        this.lureFollowTicks,
                        raven.getAIState(),
                        raven.position());
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] requestLureFollowPlayer failed safely", t);
            try {
                // Keep a tiny grace but drop the hard override if something exploded
                if (this.lureFollowTicks > LURE_FOLLOW_GRACE_TICKS) {
                    this.lureFollowTicks = LURE_FOLLOW_GRACE_TICKS;
                }
                followOverrideActive = false;
                followPocketAnchor = null;
            } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FOLLOW OWNER / LURE TICK --------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    public void tickFollowOwner() {
        try {
            // Flight posture
            raven.setNoGravity(true);
            if (raven.getAnimMode() != RavenAnimMode.IN_AIR) {
                raven.setAnimMode(RavenAnimMode.IN_AIR);
            }

            invokeResetLandingState("follow");
            setPrivateInt("idleLockTicks", 0);
            setPrivateInt("idleLeafLossTicks", 0);

            // Tick lure timer down (server-side only)
            try {
                if (!raven.level().isClientSide) {
                    if (lureFollowTicks > 0) {
                        lureFollowTicks--;
                    }
                    // If timer fully expires while we still remember a lure player, clear the state.
                    if (lureFollowTicks <= 0 && lureFollowPlayerUuid != null) {
                        clearLureFollowState("follow: lure timer expired");
                    }
                }
            } catch (Throwable ignored) {}

            // Decide follow target:
            //  - If tamed: owner
            //  - Else (or if owner missing): lure player (gold nugget)
            Player target = null;
            boolean usingLure = false;

            try {
                target = getOwnerPlayerServerSafe();
            } catch (Throwable ignored) {
                target = null;
            }

            if (target == null) {
                try {
                    Player lure = getLureFollowPlayerServerSafe();
                    if (lure != null) {
                        target = lure;
                        usingLure = true;
                    }
                } catch (Throwable ignored) {
                    target = null;
                }
            }

            // If no target, bail out
            if (target == null) {
                if (isLureFollowActive()) {
                    clearLureFollowState("follow: target missing");
                }
                raven.setAIState(RavenAIState.IDLE_GROUND);
                setPrivateInt("idleTicksRemaining", 0);
                invokeClearPlannedPath("follow lost target");
                invokeClearFlyTarget();
                setPrivateInt("roamTicksRemaining", 0);
                return;
            }

            // If we're following the owner (not lure), drop the follow override.
            if (!usingLure && followOverrideActive) {
                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] tickFollowOwner: clearing followOverrideActive (not using lure). pos={} ai={}",
                            raven.position(), raven.getAIState());
                }
                followOverrideActive = false;
            }

            // While lure-following: make sure lure timer never drops below the small grace window
            if (usingLure) {
                try {
                    if (lureFollowTicks < LURE_FOLLOW_GRACE_TICKS && lureFollowPlayerUuid != null) {
                        lureFollowTicks = LURE_FOLLOW_GRACE_TICKS;
                    }
                } catch (Throwable ignored) {}
            }

            // Home bounds guard
            if (invokeIsOutOfHomeBounds(target.position())) {
                triggerFollowCooldownAndReturn();
                return;
            }

            // --------------------------------------------
            // AGGRESSIVE FOLLOW: stable front-of-player goal (A* ONLY)
            // --------------------------------------------
            if (followGoalTtlTicks > 0) followGoalTtlTicks--;
            if (followRepathCooldownTicks > 0) followRepathCooldownTicks--;

            // ARRIVAL CHECK: when already at the follow spot, stop fighting movement.
            boolean atGoal = false;
            Vec3 computedFront = null;

            try {
                computedFront = computeFollowFrontPosition(target);
                atGoal = isCloseEnoughToFollowPlayer(target);
            } catch (Throwable ignored) {
                atGoal = false;
            }

            if (atGoal) {
                // We consider ourselves "parked" in front of the player.
                invokeClearPlannedPath("follow: arrived");
                invokeClearFlyTarget();

                // gentle hover damping to avoid jitter when player rotates
                raven.setDeltaMovement(raven.getDeltaMovement().scale(0.6D));

                // Reset cached goal so next meaningful player move triggers a clean refresh
                followCachedGoal = null;
                followCachedOwnerPos = null;
                followCachedOwnerLook = null;
                followGoalTtlTicks = 0;

                // Still tick common mechanics
                int flyTtl = getPrivateInt("flyTargetTimeoutTicks", 0);
                if (flyTtl > 0) {
                    setPrivateInt("flyTargetTimeoutTicks", flyTtl - 1);
                }

                // We still let the generic avoidance fix any minor path issues,
                // but it's mostly a no-op once we're hovering.
                invokeMaybeAvoidOrRetargetDuringFlight(raven.getRandom());

                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] tickFollowOwner: ARRIVED target={} usingLure={} pos={} frontGoal={}",
                            target.getName().getString(),
                            usingLure,
                            raven.position(),
                            computedFront);
                }
                return;
            }

            Vec3 ownerPosNow = target.position();
            Vec3 ownerLookNow = target.getLookAngle();

            boolean needNewGoal = (followCachedGoal == null) || (followGoalTtlTicks <= 0);

            if (!needNewGoal) {
                // moved enough?
                double moved2 = (followCachedOwnerPos == null)
                        ? Double.MAX_VALUE
                        : ownerPosNow.distanceToSqr(followCachedOwnerPos);

                // turned enough? (XZ dot)
                boolean turned = false;
                if (followCachedOwnerLook != null) {
                    double ax = followCachedOwnerLook.x;
                    double az = followCachedOwnerLook.z;
                    double bx = ownerLookNow.x;
                    double bz = ownerLookNow.z;

                    double al = Math.sqrt(ax * ax + az * az);
                    double bl = Math.sqrt(bx * bx + bz * bz);

                    if (al > 1.0E-4D && bl > 1.0E-4D) {
                        ax /= al;
                        az /= al;
                        bx /= bl;
                        bz /= bl;
                        double dot = ax * bx + az * bz;
                        turned = dot < 0.92D; // ~>23 degrees
                    }
                }

                Vec3 currentFlyTarget = getFlyTargetField();
                int flyTtl = getPrivateInt("flyTargetTimeoutTicks", 0);
                List<Vec3> waypoints = getPathWaypoints();
                int wpIndex = getPrivateInt("pathWaypointIndex", 0);
                int stuckTicks = getPrivateInt("stuckTicks", 0);
                int stuckThreshold = getStuckTicksThreshold();

                boolean hasFlyIntent = (currentFlyTarget != null && flyTtl > 0);
                boolean hasPathIntent = (waypoints != null && !waypoints.isEmpty() && wpIndex < waypoints.size());
                boolean hasAnyIntent = hasFlyIntent || hasPathIntent;

                boolean stuckOrColliding =
                        (raven.horizontalCollision || raven.verticalCollision || stuckTicks >= stuckThreshold);

                if (moved2 >= (0.75D * 0.75D)) needNewGoal = true;
                if (turned) needNewGoal = true;
                if (!hasAnyIntent || stuckOrColliding) needNewGoal = true;
            }

            // Hard repath cooldown (prevents "micro replan" jitter)
            if (needNewGoal && followRepathCooldownTicks > 0) {
                needNewGoal = false;
            }

            Vec3 desiredGoal = followCachedGoal;
            boolean pathOk = false;

            if (needNewGoal) {
                // 1) compute strict “front of player at eye height”
                Vec3 rawFront = computeFollowFrontPosition(target);

                // 2) apply XZ-only safety adjuster that LOCKS Y (no vertical hop)
                Vec3 lockedSafe = computeSafeFollowGoalXZLockedY(rawFront);
                lockedSafe = invokeClampTargetToHomeBounds(lockedSafe);

                followCachedGoal = lockedSafe;
                desiredGoal = lockedSafe;

                followCachedOwnerPos = ownerPosNow;
                followCachedOwnerLook = ownerLookNow;

                // short TTL so it tracks smoothly but doesn’t spam replans
                followGoalTtlTicks = 12;         // ~0.6s
                followRepathCooldownTicks = 10;  // ~0.5s min between replans

                // Clear A* intent before replanning so path-vs-fly doesn't fight
                invokeClearPlannedPath("follow: new goal");
                invokeClearFlyTarget();

                long seed = target.getUUID().getLeastSignificantBits()
                        ^ raven.getUUID().getMostSignificantBits()
                        ^ (long) raven.tickCount
                        ^ 0xF0110BEEFL;

                String reason = usingLure ? "follow lure player (aggressive)" : "follow owner (aggressive)";

                try {
                    pathOk = invokeEnsurePathTo(desiredGoal, 5 * 20, seed, reason);
                } catch (Throwable t) {
                    pathOk = false;
                    if (raven.tickCount % 40 == 0) {
                        LOG.warn("[RavenEntity] tickFollowOwner ensurePathTo failed safely: {}", t.toString());
                    }
                }

                if (pathOk) {
                    setPrivateObject("pathPendingGoal", desiredGoal);
                    invokeClearFlyTarget(); // force navigation via waypoints, not straight-line
                } else {
                    // Path failed: better to hover than to try a ballistic line that ignores maze walls
                    invokeClearPlannedPath("follow: path failed");
                    invokeClearFlyTarget();
                }

            } else {
                // no replan requested: if ALL path/fly intent disappeared while the cached goal is still valid,
                // we very gently try to re-assert the PATH (not a straight-line target).
                Vec3 currentFlyTarget = getFlyTargetField();
                int flyTtl = getPrivateInt("flyTargetTimeoutTicks", 0);
                List<Vec3> waypoints = getPathWaypoints();
                int wpIndex = getPrivateInt("pathWaypointIndex", 0);

                boolean hasFlyIntent = (currentFlyTarget != null && flyTtl > 0);
                boolean hasPathIntent = (waypoints != null && !waypoints.isEmpty() && wpIndex < waypoints.size());

                if (!hasFlyIntent && !hasPathIntent && desiredGoal != null) {
                    long seed2 = target.getUUID().getLeastSignificantBits()
                            ^ raven.getUUID().getMostSignificantBits()
                            ^ (long) raven.tickCount
                            ^ 0xF0110C0DEL;

                    String reason2 = usingLure ? "follow lure player (reassert path)"
                            : "follow owner (reassert path)";

                    boolean ok2 = false;
                    try {
                        ok2 = invokeEnsurePathTo(desiredGoal, 5 * 20, seed2, reason2);
                    } catch (Throwable t) {
                        if (raven.tickCount % 40 == 0) {
                            LOG.warn("[RavenEntity] tickFollowOwner reassert ensurePathTo failed safely: {}", t.toString());
                        }
                        ok2 = false;
                    }

                    if (ok2) {
                        setPrivateObject("pathPendingGoal", desiredGoal);
                        invokeClearFlyTarget();
                    } else {
                        // Still no path – safest behavior is to just hover.
                        invokeClearPlannedPath("follow: reassert path failed");
                        invokeClearFlyTarget();
                    }
                }
            }

            // Original safety guard
            if (invokeIsOutOfHomeBounds(raven.position())) {
                triggerFollowCooldownAndReturn();
                return;
            }

            int flyTimeout = getPrivateInt("flyTargetTimeoutTicks", 0);
            if (flyTimeout > 0) {
                setPrivateInt("flyTargetTimeoutTicks", flyTimeout - 1);
            }

            // Keep your normal flight mechanics (not player avoidance)
            invokeMaybeAvoidOrRetargetDuringFlight(raven.getRandom());

            Vec3 flyTargetNow = getFlyTargetField();
            if (flyTargetNow != null) {
                double speed = getFlySpeedBase();
                invokeFlyTowardTarget(speed);
            }

            List<Vec3> wpNow = getPathWaypoints();
            if (wpNow != null && !wpNow.isEmpty()) {
                invokeAdvanceWaypointIfNeeded(5 * 20, usingLure ? "follow lure player" : "follow owner");
            }

            if (raven.tickCount % 40 == 0) {
                Vec3 logFlyTarget = getFlyTargetField();
                List<Vec3> logWaypoints = getPathWaypoints();
                int logIdx = getPrivateInt("pathWaypointIndex", 0);
                int logPts = (logWaypoints == null ? 0 : logWaypoints.size());

                LOG.debug("[RavenEntity] tickFollowOwner: target={} usingLure={} goal={} ttl={} repathCd={} flyTarget={} pathPts={} pathIdx={} pos={} vel={}",
                        target.getName().getString(),
                        usingLure,
                        desiredGoal,
                        followGoalTtlTicks,
                        followRepathCooldownTicks,
                        logFlyTarget,
                        logPts,
                        logIdx,
                        raven.position(),
                        raven.getDeltaMovement());
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] tickFollowOwner failed safely", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FOLLOW COOLDOWN / RETURN --------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    public void triggerFollowCooldownAndReturn() {
        try {
            RandomSource rnd = raven.getRandom();

            int cd = FOLLOW_COOLDOWN_MIN_TICKS + rnd.nextInt(Math.max(1, FOLLOW_COOLDOWN_MAX_TICKS - FOLLOW_COOLDOWN_MIN_TICKS + 1));
            setFollowCooldownTicks(cd);

            raven.setAIState(RavenAIState.ROAM_FLY);

            invokeClearPlannedPath("follow cooldown return");
            Vec3 ret = invokeHomeCenterReturnTarget();
            long seed = raven.getUUID().getMostSignificantBits() ^ (long) raven.tickCount ^ 0xA11CE5EDL;
            boolean ok = invokeEnsurePathTo(ret, 10 * 20, seed, "follow cooldown return");
            if (!ok) {
                invokeSetFlyTarget(ret, 10 * 20);
            }

            setPrivateInt("roamTicksRemaining", 0);

            invokeResetLandingState("follow cooldown");
            setPrivateInt("idleLockTicks", 0);
            setPrivateInt("idleLeafLossTicks", 0);

            // beginRoamFlightWindow is presumably non-private (no compile error previously),
            // so we can call it directly.
            raven.beginRoamFlightWindow("follow cooldown -> roam");

            invokeClearPlannedPath("override roam window with return");
            boolean ok2 = invokeEnsurePathTo(ret, 10 * 20, seed ^ 0x55AA55AAL, "follow cooldown return (post-roam window)");
            if (!ok2) {
                invokeSetFlyTarget(ret, 10 * 20);
            }

            if (raven.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] Follow bounds violated -> cooldown {} ticks and return to home", cd);
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] triggerFollowCooldownAndReturn failed safely: {}", t.toString());
            }
        }
    }

    // ------------------------
    // Lure/follow resetting
    // ------------------------

    public void clearLureFollowState(String reason) {
        try {
            if (raven.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] clearLureFollowState: reason={} hadUuid={} ticksLeft={} overrideActive={} pocket={} pos={}",
                        reason,
                        (lureFollowPlayerUuid != null),
                        lureFollowTicks,
                        followOverrideActive,
                        followPocketAnchor,
                        raven.position());
            }
        } catch (Throwable ignored) {
            // Logging is best-effort only.
        }

        // Hard reset of all lure-follow specific state.
        lureFollowPlayerUuid = null;
        lureFollowTicks = 0;

        followOverrideActive = false;
        followPocketAnchor = null;
        followPocketRecalcCooldownTicks = 0;
    }

    // Returns true if the player is *currently* holding a lure item (gold nugget) in either hand.
    private boolean isLureItemInHand(Player player) {
        try {
            if (player == null) return false;

            ItemStack main = player.getMainHandItem();
            ItemStack off  = player.getOffhandItem();

            return isLureItem(main) || isLureItem(off);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isLureItemInHand failed safely: {}", t.toString());
            }
            return false;
        }
    }

    private boolean isLureItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.is(Items.GOLD_NUGGET);
    }

    // ------------------------
    // Follow player TP helpers
    // ------------------------

    @Nullable
    public BlockPos findFollowPocketNearPlayer(Player player) {
        try {
            if (player == null || !player.isAlive() || player.isSpectator()) {
                return null;
            }

            // -----------------------------
            // Base point: IN FRONT of player
            // -----------------------------
            Vec3 look = player.getLookAngle();
            double lx = look.x;
            double lz = look.z;
            double lenXZ = Math.sqrt(lx * lx + lz * lz);

            if (lenXZ < 1.0E-4D) {
                // Degenerate look (e.g. straight up/down) -> pick a stable fallback.
                lx = 0.0D;
                lz = 1.0D;
                lenXZ = 1.0D;
            }
            lx /= lenXZ;
            lz /= lenXZ;

            // Distance from player center to the "front" pocket center.
            final double FRONT_DISTANCE = 2.0D;

            double baseX = player.getX() + lx * FRONT_DISTANCE;
            double baseZ = player.getZ() + lz * FRONT_DISTANCE;

            // Place the bottom of the 3x3x3 pocket slightly below eye level.
            double baseYRaw = player.getY() + player.getEyeHeight() - 1.0D;
            int baseYInt = invokeClampYToHomeBounds(Mth.floor(baseYRaw));
            double baseY = baseYInt + 0.5D;

            Vec3 centerVec = new Vec3(baseX, baseY, baseZ);

            // Respect home radius bounds.
            centerVec = invokeClampTargetToHomeBounds(centerVec);

            BlockPos center = BlockPos.containing(centerVec);

            long seed =
                    raven.getUUID().getLeastSignificantBits()
                            ^ player.getUUID().getMostSignificantBits()
                            ^ (long) raven.tickCount
                            ^ 0xF0110FACE5L; // valid hex salt

            // -----------------------------
            // Reuse your 3x3x3 empty-pocket scan
            // -----------------------------
            // Small radius so we stay around "front of face".
            net.z2six.featheredfriend.entity.raven.modules.Teleportation tp = raven.getTeleportation();
            BlockPos pocket = (tp == null) ? null : tp.findEmptyTeleportBlock3x3x3Near(center, 4, 80, seed, raven);

            if (pocket == null) {
                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] findFollowPocketNearPlayer: no 3x3x3 pocket near front center={} player={} pos={}",
                            center, player.getName().getString(), raven.position());
                }
            } else {
                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] findFollowPocketNearPlayer: pocket={} for player={} frontCenter={} pos={}",
                            pocket, player.getName().getString(), center, raven.position());
                }
            }

            return pocket;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] findFollowPocketNearPlayer failed safely: {}", t.toString());
            }
            return null;
        }
    }

    @Nullable
    public BlockPos findFollowPocketNearPlayerInternal(BlockPos playerCenter, int playerY, int[] yOffsets, int maxR) {
        try {
            if (playerCenter == null) return null;

            // Ring scan: r=0..maxR, testing perimeter of square ring in a stable order.
            // This guarantees “closest” in a discrete sense.
            for (int r = 0; r <= maxR; r++) {
                // For each ring, try preferred Y first (above)
                for (int yo : yOffsets) {
                    int yRaw = playerY + yo;

                    // Clamp to your home vertical bounds policy
                    int y = invokeClampYToHomeBounds(yRaw);

                    // We also refuse “below player” in the preferred pass by caller choosing yOffsets accordingly.
                    // Still, if clamp pushes down unexpectedly, keep it sane:
                    if (yo >= 0 && y < playerY) {
                        y = playerY;
                    }

                    // Perimeter scan of square ring
                    int x0 = playerCenter.getX() - r;
                    int x1 = playerCenter.getX() + r;
                    int z0 = playerCenter.getZ() - r;
                    int z1 = playerCenter.getZ() + r;

                    // Top edge z0: x0..x1
                    for (int x = x0; x <= x1; x++) {
                        BlockPos cand = new BlockPos(x, y, z0);
                        if (isFollowPocketCandidateOk(cand)) return cand;
                    }
                    // Bottom edge z1: x0..x1
                    if (z1 != z0) {
                        for (int x = x0; x <= x1; x++) {
                            BlockPos cand = new BlockPos(x, y, z1);
                            if (isFollowPocketCandidateOk(cand)) return cand;
                        }
                    }
                    // Left edge x0: z0+1..z1-1
                    for (int z = z0 + 1; z <= z1 - 1; z++) {
                        BlockPos cand = new BlockPos(x0, y, z);
                        if (isFollowPocketCandidateOk(cand)) return cand;
                    }
                    // Right edge x1: z0+1..z1-1
                    if (x1 != x0) {
                        for (int z = z0 + 1; z <= z1 - 1; z++) {
                            BlockPos cand = new BlockPos(x1, y, z);
                            if (isFollowPocketCandidateOk(cand)) return cand;
                        }
                    }
                }
            }

            return null;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] findFollowPocketNearPlayerInternal failed safely: {}", t.toString());
            }
            return null;
        }
    }

    private boolean isFollowPocketCandidateOk(BlockPos anchor) {
        try {
            if (anchor == null) return false;

            // Must be within home bounds (using pocket center)
            Vec3 center = new Vec3(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
            if (invokeIsOutOfHomeBounds(center)) return false;

            // Must be a fully empty 3x3x3 pocket (air + no fluid) at anchor, with y..y+2
            if (!isEmptyTeleportPocket3x3x3At(anchor)) return false;

            return true;

        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isEmptyTeleportPocket3x3x3At(BlockPos anchor) {
        try {
            if (anchor == null) return false;

            int x0 = anchor.getX() - 1;
            int y0 = anchor.getY();
            int z0 = anchor.getZ() - 1;

            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    for (int dy = 0; dy < 3; dy++) {
                        BlockPos p = new BlockPos(x0 + dx, y0 + dy, z0 + dz);
                        if (!raven.level().isEmptyBlock(p)) return false;
                        if (!raven.level().getFluidState(p).isEmpty()) return false;
                    }
                }
            }

            return true;
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isEmptyTeleportPocket3x3x3At failed safely: {}", t.toString());
            }
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GOAL COMPUTATION ---------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    public Vec3 computeSafeFollowGoalXZLockedY(Vec3 rawGoal) {
        try {
            if (rawGoal == null) return null;

            // Lock Y exactly to the follow goal Y.
            final int y = Mth.floor(rawGoal.y + 1.0E-4D);

            // Snap XZ to block centers to stabilize.
            final double baseX = Math.floor(rawGoal.x) + 0.5D;
            final double baseZ = Math.floor(rawGoal.z) + 0.5D;

            BlockPos base = BlockPos.containing(baseX, y, baseZ);

            // If base is already empty enough, take it.
            if (raven.level().isEmptyBlock(base) && raven.level().getFluidState(base).isEmpty()
                    && raven.level().isEmptyBlock(base.above())) {
                return new Vec3(baseX, rawGoal.y, baseZ);
            }

            // Otherwise: search a small horizontal ring at SAME Y.
            final int R = 3;
            BlockPos chosen = null;

            for (int r = 1; r <= R && chosen == null; r++) {
                for (int dx = -r; dx <= r && chosen == null; dx++) {
                    int dzA = -r;
                    int dzB = r;

                    BlockPos p1 = base.offset(dx, 0, dzA);
                    if (raven.level().isEmptyBlock(p1) && raven.level().getFluidState(p1).isEmpty() && raven.level().isEmptyBlock(p1.above())) {
                        chosen = p1;
                        break;
                    }

                    if (dzB != dzA) {
                        BlockPos p2 = base.offset(dx, 0, dzB);
                        if (raven.level().isEmptyBlock(p2) && raven.level().getFluidState(p2).isEmpty() && raven.level().isEmptyBlock(p2.above())) {
                            chosen = p2;
                            break;
                        }
                    }
                }

                for (int dz = -r + 1; dz <= r - 1 && chosen == null; dz++) {
                    int dxA = -r;
                    int dxB = r;

                    BlockPos p1 = base.offset(dxA, 0, dz);
                    if (raven.level().isEmptyBlock(p1) && raven.level().getFluidState(p1).isEmpty() && raven.level().isEmptyBlock(p1.above())) {
                        chosen = p1;
                        break;
                    }

                    if (dxB != dxA) {
                        BlockPos p2 = base.offset(dxB, 0, dz);
                        if (raven.level().isEmptyBlock(p2) && raven.level().getFluidState(p2).isEmpty() && raven.level().isEmptyBlock(p2.above())) {
                            chosen = p2;
                            break;
                        }
                    }
                }
            }

            if (chosen != null) {
                Vec3 out = new Vec3(chosen.getX() + 0.5D, rawGoal.y, chosen.getZ() + 0.5D);
                out = invokeClampTargetToHomeBounds(out);
                return out;
            }

            // Fallback: return raw goal (A* may still route around).
            return invokeClampTargetToHomeBounds(new Vec3(baseX, rawGoal.y, baseZ));

        } catch (Throwable t) {
            return rawGoal;
        }
    }

    public Vec3 computeFollowFrontPosition(Player player) {
        try {
            Vec3 playerPos = player.position();

            // Look direction, XZ only (stable)
            Vec3 look = player.getLookAngle();
            double lx = look.x;
            double lz = look.z;

            double len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1.0E-4D) {
                // Fallback: use player->raven direction as "front" so we don't get a junk look vector.
                double dx = raven.getX() - playerPos.x;
                double dz = raven.getZ() - playerPos.z;
                double len2 = Math.sqrt(dx * dx + dz * dz);
                if (len2 < 1.0E-4D) {
                    lx = 1.0D;
                    lz = 0.0D;
                    len = 1.0D;
                } else {
                    lx = dx / len2;
                    lz = dz / len2;
                    len = 1.0D;
                }
            } else {
                lx /= len;
                lz /= len;
            }

            // "Right in front of it (about a block between raven and player)"
            final double FOLLOW_FRONT_DISTANCE = 1.15D;

            double tx = playerPos.x + lx * FOLLOW_FRONT_DISTANCE;
            double tz = playerPos.z + lz * FOLLOW_FRONT_DISTANCE;

            // Eye height policy: keep it near the player's eye Y, but clamp to home bounds.
            double eyeY = player.getEyeY();

            int tyInt = invokeClampYToHomeBounds(Mth.floor(eyeY));
            double ty = tyInt + 0.05D;

            Vec3 raw = new Vec3(tx, ty, tz);
            Vec3 clamped = invokeClampTargetToHomeBounds(raw);

            if (raven.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] computeFollowFrontPosition: playerPos={} eyeY={} out={} raw={} dist={}",
                        playerPos,
                        String.format("%.2f", eyeY),
                        clamped,
                        raw,
                        String.format("%.2f", FOLLOW_FRONT_DISTANCE));
            }

            return clamped;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] computeFollowFrontPosition failed safely: {}", t.toString());
            }
            return raven.position().add(0.0D, 1.5D, 0.0D);
        }
    }

    public boolean isCloseEnoughToFollowPlayer(Player player) {
        try {
            if (player == null) return false;

            Vec3 goal = computeFollowFrontPosition(player);
            if (goal == null) return false;

            Vec3 pos = raven.position();

            // Very tight: you want "at eye height and right in front".
            // Use tighter XZ + modest Y tolerance.
            double dx = pos.x - goal.x;
            double dz = pos.z - goal.z;
            double dXZ2 = dx * dx + dz * dz;

            double dy = Math.abs(pos.y - goal.y);

            // ~0.85 blocks in XZ is tight for path-following without jitter.
            // Y tolerance a bit larger because flight smoothing may not land exactly on goal.y.
            boolean closeXZ = dXZ2 <= (0.85D * 0.85D);
            boolean closeY  = dy <= 1.15D;

            return closeXZ && closeY;

        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // REFLECTION HELPERS INTO RavenEntity PRIVATES ------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    private void setPrivateInt(String fieldName, int value) {
        try {
            Field f = RavenEntity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.setInt(raven, value);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.setPrivateInt({}) failed: {}", fieldName, t.toString());
            }
        }
    }

    private int getPrivateInt(String fieldName, int fallback) {
        try {
            Field f = RavenEntity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(raven);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.getPrivateInt({}) failed: {}", fieldName, t.toString());
            }
            return fallback;
        }
    }

    private int getPrivateStaticInt(String fieldName, int fallback) {
        try {
            Field f = RavenEntity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.getPrivateStaticInt({}) failed: {}", fieldName, t.toString());
            }
            return fallback;
        }
    }

    private double getPrivateStaticDouble(String fieldName, double fallback) {
        try {
            Field f = RavenEntity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getDouble(null);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.getPrivateStaticDouble({}) failed: {}", fieldName, t.toString());
            }
            return fallback;
        }
    }

    private void setPrivateObject(String fieldName, Object value) {
        try {
            Field f = RavenEntity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(raven, value);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.setPrivateObject({}) failed: {}", fieldName, t.toString());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Vec3> getPathWaypoints() {
        try {
            Field f = RavenEntity.class.getDeclaredField("pathWaypoints");
            f.setAccessible(true);
            Object v = f.get(raven);
            if (v instanceof List) {
                return (List<Vec3>) v;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.getPathWaypoints failed: {}", t.toString());
            }
        }
        return null;
    }

    private Vec3 getFlyTargetField() {
        try {
            Field f = RavenEntity.class.getDeclaredField("flyTarget");
            f.setAccessible(true);
            Object v = f.get(raven);
            if (v instanceof Vec3) {
                return (Vec3) v;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.getFlyTargetField failed: {}", t.toString());
            }
        }
        return null;
    }

    private int getStuckTicksThreshold() {
        return getPrivateStaticInt("STUCK_TICKS_THRESHOLD", 40);
    }

    private double getFlySpeedBase() {
        return getPrivateStaticDouble("FLY_SPEED_BASE", 0.25D);
    }

    private void invokeResetLandingState(String reason) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("resetLandingState", String.class);
            m.setAccessible(true);
            m.invoke(raven, reason);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeResetLandingState failed: {}", t.toString());
            }
        }
    }

    private void invokeClearPlannedPath(String reason) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("clearPlannedPath", String.class);
            m.setAccessible(true);
            m.invoke(raven, reason);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeClearPlannedPath failed: {}", t.toString());
            }
        }
    }

    private void invokeClearFlyTarget() {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("clearFlyTarget");
            m.setAccessible(true);
            m.invoke(raven);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeClearFlyTarget failed: {}", t.toString());
            }
        }
    }

    private int invokeClampYToHomeBounds(int y) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("clampYToHomeBounds", int.class);
            m.setAccessible(true);
            Object res = m.invoke(raven, y);
            if (res instanceof Integer) {
                return (Integer) res;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeClampYToHomeBounds failed: {}", t.toString());
            }
        }
        return y;
    }

    private Vec3 invokeClampTargetToHomeBounds(Vec3 in) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("clampTargetToHomeBounds", Vec3.class);
            m.setAccessible(true);
            Object res = m.invoke(raven, in);
            if (res instanceof Vec3) {
                return (Vec3) res;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeClampTargetToHomeBounds failed: {}", t.toString());
            }
        }
        return in;
    }

    private Vec3 invokeComputeSafePathingGoal(Vec3 rawGoal, RavenAStarPathing.Config cfg) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("computeSafePathingGoal", Vec3.class, RavenAStarPathing.Config.class);
            m.setAccessible(true);
            Object res = m.invoke(raven, rawGoal, cfg);
            if (res instanceof Vec3) {
                return (Vec3) res;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeComputeSafePathingGoal failed: {}", t.toString());
            }
        }
        return rawGoal;
    }

    private boolean invokeEnsurePathToWithFlags(Vec3 goal, boolean allowLeaves, int ttlTicks, long seed, String reason) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod(
                    "ensurePathTo",
                    Vec3.class,
                    boolean.class,
                    int.class,
                    long.class,
                    String.class
            );
            m.setAccessible(true);
            Object res = m.invoke(raven, goal, allowLeaves, ttlTicks, seed, reason);
            if (res instanceof Boolean) {
                return (Boolean) res;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeEnsurePathToWithFlags failed: {}", t.toString());
            }
        }
        return false;
    }

    private boolean invokeEnsurePathTo(Vec3 goal, int ttlTicks, long seed, String reason) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod(
                    "ensurePathTo",
                    Vec3.class,
                    int.class,
                    long.class,
                    String.class
            );
            m.setAccessible(true);
            Object res = m.invoke(raven, goal, ttlTicks, seed, reason);
            if (res instanceof Boolean) {
                return (Boolean) res;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeEnsurePathTo failed: {}", t.toString());
            }
        }
        return false;
    }

    private boolean invokeIsOutOfHomeBounds(Vec3 pos) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("isOutOfHomeBounds", Vec3.class);
            m.setAccessible(true);
            Object res = m.invoke(raven, pos);
            if (res instanceof Boolean) {
                return (Boolean) res;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeIsOutOfHomeBounds failed: {}", t.toString());
            }
        }
        return false;
    }

    private Vec3 invokeHomeCenterReturnTarget() {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("homeCenterReturnTarget");
            m.setAccessible(true);
            Object res = m.invoke(raven);
            if (res instanceof Vec3) {
                return (Vec3) res;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeHomeCenterReturnTarget failed: {}", t.toString());
            }
        }
        // Fallback: current pos to avoid nulls
        return raven.position();
    }

    private void invokeSetFlyTarget(Vec3 target, int ttlTicks) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("setFlyTarget", Vec3.class, int.class);
            m.setAccessible(true);
            m.invoke(raven, target, ttlTicks);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeSetFlyTarget failed: {}", t.toString());
            }
        }
    }

    private void invokeMaybeAvoidOrRetargetDuringFlight(RandomSource rnd) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("maybeAvoidOrRetargetDuringFlight", RandomSource.class);
            m.setAccessible(true);
            m.invoke(raven, rnd);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeMaybeAvoidOrRetargetDuringFlight failed: {}", t.toString());
            }
        }
    }

    private void invokeFlyTowardTarget(double speed) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("flyTowardTarget", double.class);
            m.setAccessible(true);
            m.invoke(raven, speed);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeFlyTowardTarget failed: {}", t.toString());
            }
        }
    }

    private void invokeAdvanceWaypointIfNeeded(int ttlTicks, String debugTag) {
        try {
            Method m = RavenEntity.class.getDeclaredMethod("advanceWaypointIfNeeded", int.class, String.class);
            m.setAccessible(true);
            m.invoke(raven, ttlTicks, debugTag);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeAdvanceWaypointIfNeeded failed: {}", t.toString());
            }
        }
    }
}
