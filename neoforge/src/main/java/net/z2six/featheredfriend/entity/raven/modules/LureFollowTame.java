package net.z2six.featheredfriend.entity.raven.modules;

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


public class LureFollowTame {

    // ---------------------------------------------------------------------------------------------
    // VARIABLES -----------------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    // Random per-spawn "tame cost" (3..6 golden nuggets). Persisted via NBT.
    private static final String NBT_TAME_NUGGETS_REQUIRED = "GoldenNuggetsRequiredToTame";
    private int goldenNuggetsRequiredToTame = 0;

    // Follow owner: 3x3x3 pocket targeting + override gating
    private boolean followOverrideActive = false;
    private @org.jetbrains.annotations.Nullable BlockPos followPocketAnchor = null; // anchor block for 3x3x3 pocket (center block at y)
    private int followPocketRecalcCooldownTicks = 0;

    // Lure-follow (pre-taming) state
    private @org.jetbrains.annotations.Nullable java.util.UUID lureFollowPlayerUuid = null;
    private int lureFollowTicks = 0;

    // Small grace so follow doesn't flap off instantly if player briefly swaps items
    private static final int LURE_FOLLOW_GRACE_TICKS = 10; // 0.5s
    private static final int LURE_FOLLOW_REFRESH_TICKS = 3 * 20; // keep active for 3s per refresh

    // Follow goal stability (prevents constant re-path + vertical "hops")
    private @org.jetbrains.annotations.Nullable Vec3 followCachedGoal = null;
    private @org.jetbrains.annotations.Nullable Vec3 followCachedOwnerPos = null;
    private @org.jetbrains.annotations.Nullable Vec3 followCachedOwnerLook = null;
    private int followGoalTtlTicks = 0;
    private int followRepathCooldownTicks = 0;

    // Follow: desired distance band
    private static final double FOLLOW_MIN_DIST = 3.0D;
    private static final double FOLLOW_MAX_DIST = 8.0D;

    // Follow cooldown after bounds violation
    private static final int FOLLOW_COOLDOWN_MIN_TICKS = 5 * 20;
    private static final int FOLLOW_COOLDOWN_MAX_TICKS = 10 * 20;

    // Synced follow cooldown
    private static final EntityDataAccessor<Integer> DATA_FOLLOW_COOLDOWN_TICKS =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    // NBT Key
    private static final String NBT_FOLLOW_CD = "RavenFollowCooldown";

    // ---------------------------------------------------------------------------------------------
    // METHODS -------------------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------
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

    private int getFollowCooldownTicks() {
        return this.entityData.get(DATA_FOLLOW_COOLDOWN_TICKS);
    }

    private void setFollowCooldownTicks(int ticks) {
        try {
            int clamped = Math.max(0, ticks);
            int prev = 0;
            try {
                prev = this.entityData.get(DATA_FOLLOW_COOLDOWN_TICKS);
            } catch (Throwable ignored) {
            }

            this.entityData.set(DATA_FOLLOW_COOLDOWN_TICKS, clamped);

            if (prev != clamped) {
                if (!this.level().isClientSide) {
                    // Not spammy: only logs when it actually changes.
                    LOG.info("[RavenEntity] FollowCooldown set: {} -> {} pos={} ai={}",
                            prev, clamped, this.position(), getAIState());
                }
            }
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] setFollowCooldownTicks failed safely: {}", t.toString());
            }
        }
    }

    public boolean isLureFollowActive() {
        try {
            if (this.level().isClientSide) return false;
            if (!this.isAlive()) return false;

            // No remembered lure => not active
            if (this.lureFollowPlayerUuid == null) {
                this.lureFollowTicks = 0;
                return false;
            }

            // Tick down the memory
            if (this.lureFollowTicks > 0) {
                this.lureFollowTicks--;
            }

            if (this.lureFollowTicks <= 0) {
                // Expired
                this.lureFollowPlayerUuid = null;
                this.lureFollowTicks = 0;
                return false;
            }

            Player p = this.level().getPlayerByUUID(this.lureFollowPlayerUuid);
            if (p == null || !p.isAlive() || p.isSpectator()) {
                this.lureFollowPlayerUuid = null;
                this.lureFollowTicks = 0;
                return false;
            }

            return true;

        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isLureFollowActive failed safely: {}", t.toString());
            }
            this.lureFollowPlayerUuid = null;
            this.lureFollowTicks = 0;
            return false;
        }
    }

    private void clearLureFollowState(String reason) {
        try {
            if (this.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] clearLureFollowState: reason={} hadUuid={} ticksLeft={} pos={}",
                        reason,
                        (lureFollowPlayerUuid != null),
                        lureFollowTicks,
                        this.position());
            }
            lureFollowPlayerUuid = null;
            lureFollowTicks = 0;
        } catch (Throwable ignored) {
            lureFollowPlayerUuid = null;
            lureFollowTicks = 0;
        }
    }

    private @org.jetbrains.annotations.Nullable Player getLureFollowPlayerServerSafe() {
        try {
            if (!(this.level() instanceof ServerLevel serverLevel)) return null;
            if (!isLureFollowActive()) return null;

            Player p = serverLevel.getPlayerByUUID(lureFollowPlayerUuid);
            if (p == null) return null;
            if (!p.isAlive() || p.isSpectator()) return null;

            return p;
        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getLureFollowPlayerServerSafe failed safely: {}", t.toString());
            }
            return null;
        }
    }

    public void requestLureFollowPlayer(@org.jetbrains.annotations.Nullable net.minecraft.world.entity.player.Player player, double distToPlayer) {
        try {
            if (this.level().isClientSide) return;
            if (!this.isAlive()) return;

            // Basic sanity on player
            if (player == null || !player.isAlive() || player.isSpectator()) {
                // If we only had a tiny bit of memory left, just drop it.
                if (this.lureFollowTicks <= LURE_FOLLOW_GRACE_TICKS) {
                    this.lureFollowPlayerUuid = null;
                    this.lureFollowTicks = 0;
                    followOverrideActive = false;
                    followPocketAnchor = null;
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
            if (getAIState() != RavenAIState.FOLLOW_OWNER) {
                setAIState(RavenAIState.FOLLOW_OWNER);
            }
            followOverrideActive = true;

            // Kill avoidance overrides so FOLLOW_OWNER truly wins
            // (stuck teleport is gated separately by isTeleportRecoveryEligible).
            try {
                playerAvoidanceOverrideTicks = 0;
                playerAvoidanceRearmCooldownTicks = 0;
            } catch (Throwable ignored) {}

            // Cancel idle/landing so we actually start moving
            resetLandingState("lure follow arm");
            idleCommitTicks = 0;
            idleLockTicks = 0;
            idleLeafLossTicks = 0;
            roamTicksRemaining = 0;

            // Put bird into flight
            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            // --------------------------------------------------
            // OLD behavior: find 3x3x3 pocket near player, hover.
            // NEW behavior: we still optionally find a pocket (for logging / future use),
            // but the actual GOAL is "in front of player at eye-level".
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
                int y = clampYToHomeBounds(py + 3);
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

            Vec3 safeGoal = computeSafePathingGoal(desiredFront, cfg);

            long seed =
                    player.getUUID().getLeastSignificantBits()
                            ^ this.getUUID().getMostSignificantBits()
                            ^ (long) this.tickCount
                            ^ 0xF011000DL; // valid hex salt

            boolean pathOk;
            try {
                pathOk = ensurePathTo(
                        safeGoal,
                        true,
                        8 * 20,
                        seed,
                        "lure follow (front-of-player): player=" + player.getName().getString()
                                + " dist=" + String.format("%.2f", distToPlayer)
                );
            } catch (Throwable t) {
                if (this.tickCount % 40 == 0) {
                    LOG.warn("[RavenEntity] requestLureFollowPlayer ensurePathTo failed safely: {}", t.toString());
                }
                pathOk = false;
            }

            if (!pathOk) {
                setFlyTarget(safeGoal, 8 * 20);
            } else {
                pathPendingGoal = safeGoal;
            }

            if (this.tickCount % 20 == 0) {
                LOG.info("[RavenEntity] LURE FOLLOW armed: player={} dist={} pocket={} desiredFront={} safeGoal={} pathOk={} lureTicks={} aiState={} pos={}",
                        player.getName().getString(),
                        String.format("%.2f", distToPlayer),
                        pocket,
                        desiredFront,
                        safeGoal,
                        pathOk,
                        this.lureFollowTicks,
                        getAIState(),
                        this.position());
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

    private void tickFollowOwner() {
        try {
            // Flight posture
            this.setNoGravity(true);
            if (this.getAnimMode() != RavenAnimMode.IN_AIR) {
                this.setAnimMode(RavenAnimMode.IN_AIR);
            }

            resetLandingState("follow");
            this.idleLockTicks = 0;
            this.idleLeafLossTicks = 0;

            // Tick lure timer down (server-side only)
            try {
                if (!this.level().isClientSide) {
                    if (lureFollowTicks > 0) {
                        lureFollowTicks--;
                    }
                    if (lureFollowTicks <= 0 && lureFollowPlayerUuid != null) {
                        lureFollowTicks = 0;
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
                setAIState(RavenAIState.IDLE_GROUND);
                idleTicksRemaining = 0;
                clearPlannedPath("follow lost target");
                clearFlyTarget();
                roamTicksRemaining = 0;
                return;
            }

            // While lure-following: suppress player avoidance behavior by keeping override alive.
            if (usingLure) {
                try {
                    if (lureFollowTicks < LURE_FOLLOW_GRACE_TICKS && lureFollowPlayerUuid != null) {
                        lureFollowTicks = LURE_FOLLOW_GRACE_TICKS;
                    }
                } catch (Throwable ignored) {}
            }

            // Home bounds guard (your existing behavior)
            if (isOutOfHomeBounds(target.position())) {
                triggerFollowCooldownAndReturn();
                return;
            }

            // --------------------------------------------
            // AGGRESSIVE FOLLOW: stable front-of-player goal
            // --------------------------------------------
            if (followGoalTtlTicks > 0) followGoalTtlTicks--;
            if (followRepathCooldownTicks > 0) followRepathCooldownTicks--;

            // ARRIVAL CHECK: when already at the follow spot, stop fighting movement.
            // This also enables "disable stuck teleport when at player" to be true in practice.
            boolean atGoal = false;
            Vec3 computedFront = null;

            try {
                computedFront = computeFollowFrontPosition(target);
                atGoal = isCloseEnoughToFollowPlayer(target);
            } catch (Throwable ignored) {
                atGoal = false;
            }

            if (atGoal) {
                clearPlannedPath("follow: arrived");
                clearFlyTarget();

                // gentle hover damping to avoid jitter when player rotates
                this.setDeltaMovement(this.getDeltaMovement().scale(0.6D));

                // Reset cached goal so next meaningful player move triggers a clean refresh
                followCachedGoal = null;
                followCachedOwnerPos = null;
                followCachedOwnerLook = null;
                followGoalTtlTicks = 0;

                // Still tick common mechanics
                if (flyTargetTimeoutTicks > 0) flyTargetTimeoutTicks--;
                maybeAvoidOrRetargetDuringFlight(this.getRandom());

                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] tickFollowOwner: ARRIVED target={} usingLure={} pos={} frontGoal={}",
                            target.getName().getString(),
                            usingLure,
                            this.position(),
                            computedFront);
                }
                return;
            }

            // Decide whether to refresh goal (hysteresis: don’t replan constantly)
            Vec3 ownerPosNow = target.position();
            Vec3 ownerLookNow = target.getLookAngle();

            boolean needNewGoal = (followCachedGoal == null) || (followGoalTtlTicks <= 0);

            if (!needNewGoal) {
                // moved enough?
                double moved2 = (followCachedOwnerPos == null) ? Double.MAX_VALUE : ownerPosNow.distanceToSqr(followCachedOwnerPos);

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
                        ax /= al; az /= al;
                        bx /= bl; bz /= bl;
                        double dot = ax * bx + az * bz;
                        turned = dot < 0.92D; // ~>23 degrees
                    }
                }

                boolean hasFlyIntent = (flyTarget != null && flyTargetTimeoutTicks > 0);
                boolean hasPathIntent = (pathWaypoints != null && !pathWaypoints.isEmpty() && pathWaypointIndex < pathWaypoints.size());
                boolean hasAnyIntent = hasFlyIntent || hasPathIntent;

                boolean stuckOrColliding = (this.horizontalCollision || this.verticalCollision || stuckTicks >= STUCK_TICKS_THRESHOLD);

                if (moved2 >= (0.75D * 0.75D)) needNewGoal = true;
                if (turned) needNewGoal = true;
                if (!hasAnyIntent || stuckOrColliding) needNewGoal = true;
            }

            // Hard repath cooldown (prevents the “fly up a bit when I approach” jitter)
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

                lockedSafe = clampTargetToHomeBounds(lockedSafe);

                followCachedGoal = lockedSafe;
                desiredGoal = lockedSafe;

                followCachedOwnerPos = ownerPosNow;
                followCachedOwnerLook = ownerLookNow;

                // short TTL so it tracks smoothly but doesn’t spam replans
                followGoalTtlTicks = 12;         // ~0.6s
                followRepathCooldownTicks = 10;  // ~0.5s min between replans

                // Clear intent before replanning so path-vs-fly doesn’t fight
                clearPlannedPath("follow: new goal");
                clearFlyTarget();

                long seed = target.getUUID().getLeastSignificantBits()
                        ^ this.getUUID().getMostSignificantBits()
                        ^ (long) this.tickCount
                        ^ 0xF0110BEEFL;

                String reason = usingLure ? "follow lure player (aggressive)" : "follow owner (aggressive)";

                try {
                    pathOk = ensurePathTo(desiredGoal, 5 * 20, seed, reason);
                } catch (Throwable t) {
                    pathOk = false;
                    if (this.tickCount % 40 == 0) {
                        LOG.warn("[RavenEntity] tickFollowOwner ensurePathTo failed safely: {}", t.toString());
                    }
                }

                if (!pathOk) {
                    setFlyTarget(desiredGoal, 4 * 20);
                } else {
                    // Optional: keep your bookkeeping consistent
                    pathPendingGoal = desiredGoal;
                }
            } else {
                // no replan: if intent disappeared, re-assert a short fly target so it keeps moving
                boolean hasFlyIntent = (flyTarget != null && flyTargetTimeoutTicks > 0);
                boolean hasPathIntent = (pathWaypoints != null && !pathWaypoints.isEmpty() && pathWaypointIndex < pathWaypoints.size());

                if (!hasFlyIntent && !hasPathIntent && desiredGoal != null) {
                    setFlyTarget(desiredGoal, 2 * 20);
                }
            }

            // Original safety guard
            if (isOutOfHomeBounds(this.position())) {
                triggerFollowCooldownAndReturn();
                return;
            }

            if (flyTargetTimeoutTicks > 0) {
                flyTargetTimeoutTicks--;
            }

            // Keep your normal flight mechanics (not player avoidance)
            maybeAvoidOrRetargetDuringFlight(this.getRandom());

            if (flyTarget != null) {
                flyTowardTarget(FLY_SPEED_BASE);
            }

            if (pathWaypoints != null && !pathWaypoints.isEmpty()) {
                advanceWaypointIfNeeded(5 * 20, usingLure ? "follow lure player" : "follow owner");
            }

            if (this.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] tickFollowOwner: target={} usingLure={} goal={} ttl={} repathCd={} flyTarget={} pathPts={} pathIdx={} pos={} vel={}",
                        target.getName().getString(),
                        usingLure,
                        desiredGoal,
                        followGoalTtlTicks,
                        followRepathCooldownTicks,
                        flyTarget,
                        (pathWaypoints == null ? 0 : pathWaypoints.size()),
                        pathWaypointIndex,
                        this.position(),
                        this.getDeltaMovement());
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] tickFollowOwner failed safely", t);
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

    // ------------------------
    // Follow player TP helpers
    // ------------------------

    @org.jetbrains.annotations.Nullable
    private BlockPos findFollowPocketNearPlayer(Player player) {
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
            int baseYInt = clampYToHomeBounds(Mth.floor(baseYRaw));
            double baseY = baseYInt + 0.5D;

            Vec3 centerVec = new Vec3(baseX, baseY, baseZ);

            // Respect home radius bounds.
            centerVec = clampTargetToHomeBounds(centerVec);

            BlockPos center = BlockPos.containing(centerVec);

            long seed =
                    this.getUUID().getLeastSignificantBits()
                            ^ player.getUUID().getMostSignificantBits()
                            ^ (long) this.tickCount
                            ^ 0xF0110FACE5L; // valid hex salt

            // -----------------------------
            // Reuse your 3x3x3 empty-pocket scan
            // -----------------------------
            // Small radius so we stay around "front of face".
            BlockPos pocket = findEmptyTeleportBlock3x3x3Near(center, 4, 80, seed);

            if (pocket == null) {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] findFollowPocketNearPlayer: no 3x3x3 pocket near front center={} player={} pos={}",
                            center, player.getName().getString(), this.position());
                }
            } else {
                if (this.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] findFollowPocketNearPlayer: pocket={} for player={} frontCenter={} pos={}",
                            pocket, player.getName().getString(), center, this.position());
                }
            }

            return pocket;

        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] findFollowPocketNearPlayer failed safely: {}", t.toString());
            }
            return null;
        }
    }

    @org.jetbrains.annotations.Nullable
    private BlockPos findFollowPocketNearPlayerInternal(BlockPos playerCenter, int playerY, int[] yOffsets, int maxR) {
        try {
            if (playerCenter == null) return null;

            // Ring scan: r=0..maxR, testing perimeter of square ring in a stable order.
            // This guarantees “closest” in a discrete sense.
            for (int r = 0; r <= maxR; r++) {
                // For each ring, try preferred Y first (above)
                for (int yo : yOffsets) {
                    int yRaw = playerY + yo;

                    // Clamp to your home vertical bounds policy
                    int y = clampYToHomeBounds(yRaw);

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
            if (this.tickCount % 80 == 0) {
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
            if (isOutOfHomeBounds(center)) return false;

            // Must be a fully empty 3x3x3 pocket (air + no fluid) at anchor, with y..y+2
            if (!isEmptyTeleportPocket3x3x3At(anchor)) return false;

            return true;

        } catch (Throwable t) {
            return false;
        }
    }

    private Vec3 computeSafeFollowGoalXZLockedY(Vec3 rawGoal) {
        try {
            if (rawGoal == null) return null;

            // Lock Y exactly to the follow goal Y.
            final int y = Mth.floor(rawGoal.y + 1.0E-4D);

            // Snap XZ to block centers to stabilize.
            final double baseX = Math.floor(rawGoal.x) + 0.5D;
            final double baseZ = Math.floor(rawGoal.z) + 0.5D;

            BlockPos base = BlockPos.containing(baseX, y, baseZ);

            // If base is already empty enough, take it.
            if (this.level().isEmptyBlock(base) && this.level().getFluidState(base).isEmpty()
                    && this.level().isEmptyBlock(base.above())) {
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
                    if (this.level().isEmptyBlock(p1) && this.level().getFluidState(p1).isEmpty() && this.level().isEmptyBlock(p1.above())) {
                        chosen = p1;
                        break;
                    }

                    if (dzB != dzA) {
                        BlockPos p2 = base.offset(dx, 0, dzB);
                        if (this.level().isEmptyBlock(p2) && this.level().getFluidState(p2).isEmpty() && this.level().isEmptyBlock(p2.above())) {
                            chosen = p2;
                            break;
                        }
                    }
                }

                for (int dz = -r + 1; dz <= r - 1 && chosen == null; dz++) {
                    int dxA = -r;
                    int dxB = r;

                    BlockPos p1 = base.offset(dxA, 0, dz);
                    if (this.level().isEmptyBlock(p1) && this.level().getFluidState(p1).isEmpty() && this.level().isEmptyBlock(p1.above())) {
                        chosen = p1;
                        break;
                    }

                    if (dxB != dxA) {
                        BlockPos p2 = base.offset(dxB, 0, dz);
                        if (this.level().isEmptyBlock(p2) && this.level().getFluidState(p2).isEmpty() && this.level().isEmptyBlock(p2.above())) {
                            chosen = p2;
                            break;
                        }
                    }
                }
            }

            if (chosen != null) {
                Vec3 out = new Vec3(chosen.getX() + 0.5D, rawGoal.y, chosen.getZ() + 0.5D);
                out = clampTargetToHomeBounds(out);
                return out;
            }

            // Fallback: return raw goal (A* may still route around).
            return clampTargetToHomeBounds(new Vec3(baseX, rawGoal.y, baseZ));

        } catch (Throwable t) {
            return rawGoal;
        }
    }

    private Vec3 computeFollowFrontPosition(net.minecraft.world.entity.player.Player player) {
        try {
            Vec3 playerPos = player.position();

            // Look direction, XZ only (stable)
            Vec3 look = player.getLookAngle();
            double lx = look.x;
            double lz = look.z;

            double len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1.0E-4D) {
                // Fallback: use player->raven direction as "front" so we don't get a junk look vector.
                double dx = this.getX() - playerPos.x;
                double dz = this.getZ() - playerPos.z;
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

            // You asked: "right in front of it (about a block between raven and player)"
            // Tune range: 1.0..1.35 tends to look right for a bird-sized mob.
            final double FOLLOW_FRONT_DISTANCE = 1.15D;

            double tx = playerPos.x + lx * FOLLOW_FRONT_DISTANCE;
            double tz = playerPos.z + lz * FOLLOW_FRONT_DISTANCE;

            // Eye height policy: keep it near the player's eye Y, but clamp to home bounds.
            double eyeY = player.getEyeY();

            int tyInt = clampYToHomeBounds(Mth.floor(eyeY));
            double ty = tyInt + 0.05D;

            Vec3 raw = new Vec3(tx, ty, tz);
            Vec3 clamped = clampTargetToHomeBounds(raw);

            if (this.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] computeFollowFrontPosition: playerPos={} eyeY={} out={} raw={} dist={}",
                        playerPos,
                        String.format("%.2f", eyeY),
                        clamped,
                        raw,
                        String.format("%.2f", FOLLOW_FRONT_DISTANCE));
            }

            return clamped;

        } catch (Throwable t) {
            if (this.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] computeFollowFrontPosition failed safely: {}", t.toString());
            }
            return this.position().add(0.0D, 1.5D, 0.0D);
        }
    }

    private boolean isCloseEnoughToFollowPlayer(net.minecraft.world.entity.player.Player player) {
        try {
            if (player == null) return false;

            Vec3 goal = computeFollowFrontPosition(player);
            if (goal == null) return false;

            Vec3 pos = this.position();

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
}
