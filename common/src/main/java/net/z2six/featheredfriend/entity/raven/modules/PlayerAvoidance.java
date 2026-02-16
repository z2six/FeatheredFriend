// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/PlayerAvoidance.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.entity.raven.RavenAIState;
import net.z2six.featheredfriend.entity.raven.RavenAnimMode;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

public final class PlayerAvoidance {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Instance state
    // ---------------------------------------------------------------------
    private final RavenEntity raven;

    public PlayerAvoidance(RavenEntity raven) {
        this.raven = raven;
    }

    /**
     * Instance entrypoint you can call from RavenEntity:
     *
     *     this.playeravoidance.tick();
     *
     * This simply forwards to the existing static implementation so that
     * behavior stays identical.
     */
    public void tick() {
        try {
            tryTriggerPlayerAvoidance(this.raven);
        } catch (Throwable t) {
            try {
                if (this.raven != null && this.raven.tickCount % 40 == 0) {
                    LOG.warn("[PlayerAvoidance] tick() failed safely: {}", t.toString());
                }
            } catch (Throwable ignored) {
                // Completely swallow any logging failures
            }
        }
    }

    // Player presence radius in blocks (3D)
    public static final double AVOID_PLAYER_RADIUS = 25.0D;
    private static final double AVOID_PLAYER_RADIUS_SQR = AVOID_PLAYER_RADIUS * AVOID_PLAYER_RADIUS;

    // Lure-follow can reasonably start from farther away than avoidance.
    private static final double LURE_PLAYER_RADIUS = 64.0D;

    // Panic teleport radius (3D)
    private static final double PANIC_TELEPORT_RADIUS = 5.0D;
    private static final double PANIC_TELEPORT_RADIUS_SQR = PANIC_TELEPORT_RADIUS * PANIC_TELEPORT_RADIUS;

    // IMPORTANT: check every tick so panic teleport triggers immediately when you rush into it.
    // (We keep a cheap nearest-player scan; RavenEntity itself has its own anti-spam for panic teleports.)
    private static final int RECHECK_COOLDOWN_TICKS = 20;

    /**
     * Call this from tickRoamFly() and tickIdleGround().
     *
     * Static variant (legacy-style) so existing callers can keep working:
     *     PlayerAvoidance.tryTriggerPlayerAvoidance(this);
     *
     * The instance wrapper (tick()) just calls this with its bound RavenEntity.
     */
    public static void tryTriggerPlayerAvoidance(RavenEntity raven) {
        try {
            if (raven == null) return;
            if (raven.level() == null) return;
            if (raven.level().isClientSide) return;
            if (!raven.isAlive()) return;

            if (RECHECK_COOLDOWN_TICKS > 1 && (raven.tickCount % RECHECK_COOLDOWN_TICKS) != 0) {
                return;
            }

            // ------------------------------------------------------------
            // LURE FOLLOW OVERRIDE (iron/gold nuggets)
            //  - We allow lure-follow requests from a larger radius than avoidance.
            // ------------------------------------------------------------
            try {
                Player lureCandidate = findNearestPlayerWithin(raven, LURE_PLAYER_RADIUS);
                if (lureCandidate != null && isHoldingLureNugget(lureCandidate)) {
                    double dist = Math.sqrt(Math.max(0.0D, raven.position().distanceToSqr(lureCandidate.position())));

                    boolean armed = false;
                    try {
                        raven.requestLureFollowPlayer(lureCandidate, dist);
                        armed = raven.isLureFollowActive();
                    } catch (Throwable t) {
                        armed = false;
                        if (raven.tickCount % 40 == 0) {
                            LOG.warn("[PlayerAvoidance] LURE follow request failed safely: {}", t.toString());
                        }
                    }

                    if (raven.tickCount % 20 == 0) {
                        LOG.debug(
                                "[PlayerAvoidance] LURE follow requested: player={} dist={} ravenPos={} playerPos={} mainHand={} offHand={} armedNow={}",
                                safeName(lureCandidate),
                                String.format("%.2f", dist),
                                raven.position(),
                                lureCandidate.position(),
                                safeItem(lureCandidate.getMainHandItem()),
                                safeItem(lureCandidate.getOffhandItem()),
                                armed
                        );
                    }

                    // If lure-follow is active, we MUST NOT run avoidance/panic logic.
                    if (armed) {
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }

            Player nearest = findNearestPlayerWithin(raven, AVOID_PLAYER_RADIUS);
            if (nearest == null) return;

            Vec3 ravenPos = raven.position();
            Vec3 playerPos = nearest.position();

            double d2 = ravenPos.distanceToSqr(playerPos);
            if (d2 > AVOID_PLAYER_RADIUS_SQR) return;

            double dist = Math.sqrt(Math.max(0.0D, d2));

            // If the player is holding a lure nugget, do not run ANY avoidance/panic logic.
            // Lure-follow (if possible) is handled above; if it can't arm, we still don't flee/teleport.
            if (isHoldingLureNugget(nearest)) {
                return;
            }

            // Panic teleport if extremely close.
            if (d2 <= PANIC_TELEPORT_RADIUS_SQR) {
                try {
                    Teleportation tp = null;
                    try {
                        tp = raven.getTeleportation();
                    } catch (Throwable ignored) {
                        tp = null;
                    }

                    if (tp != null) {
                        tp.requestPanicTeleportAwayFromPlayer(nearest, dist, raven);

                        if (raven.tickCount % 20 == 0) {
                            LOG.debug(
                                    "[PlayerAvoidance] PANIC teleport requested: player={} dist={} ravenPos={}",
                                    safeName(nearest),
                                    String.format("%.2f", dist),
                                    ravenPos
                            );
                        }
                    } else {
                        if (raven.tickCount % 40 == 0) {
                            LOG.warn(
                                    "[PlayerAvoidance] PANIC teleport skipped: teleportation module null. player={} dist={} ravenPos={}",
                                    safeName(nearest),
                                    String.format("%.2f", dist),
                                    ravenPos
                            );
                        }
                    }
                } catch (Throwable t) {
                    if (raven.tickCount % 40 == 0) {
                        LOG.warn("[PlayerAvoidance] PANIC teleport failed safely: {}", t.toString());
                    }
                }

                return;
            }

            // Otherwise normal fly-away avoidance.
            net.z2six.featheredfriend.entity.raven.modules.PlayerAvoidance module = raven.getPlayerAvoidanceModule();
            if (module != null) {
                module.requestPlayerAvoidanceFleeTarget(nearest, dist, raven);
            }

            if (raven.tickCount % 20 == 0) {
                LOG.debug(
                        "[PlayerAvoidance] avoidance requested: player={} dist={} ravenPos={}",
                        safeName(nearest),
                        String.format("%.2f", dist),
                        ravenPos
                );
            }

        } catch (Throwable t) {
            LOG.error("[PlayerAvoidance] tryTriggerPlayerAvoidance failed", t);
        }
    }

    /**
     * Global "should landing/perching be blocked right now?" check.
     *
     * Rules:
     *  - If a player-avoidance override is active, always block landing.
     *  - Otherwise, if there is any player within (slightly inflated) avoidance radius,
     *    we still block landing. This guarantees that once a player walks into the
     *    avoidance bubble, the raven will not try to perch until the player backs off.
     *
     * This is deliberately conservative: it can return true even when we did not
     * actually arm special avoidance behavior, because the user wants:
     *
     *      "Avoidance should never be interrupted by landing."
     */
    public static boolean shouldBlockLanding(RavenEntity raven) {
        try {
            if (raven == null) {
                return false;
            }
            if (raven.level() == null) {
                return false;
            }
            if (raven.level().isClientSide) {
                return false;
            }
            if (!raven.isAlive()) {
                return false;
            }

            // If we have an explicit override window, landing is *hard* disabled.
            try {
                if (raven.isPlayerAvoidanceOverrideActive()) {
                    if (raven.tickCount % 40 == 0) {
                        LOG.debug(
                                "[PlayerAvoidance] shouldBlockLanding: true (override active). pos={} aiState={}",
                                raven.position(),
                                raven.getAIState()
                        );
                    }
                    return true;
                }
            } catch (Throwable t) {
                if (raven.tickCount % 80 == 0) {
                    LOG.warn(
                            "[PlayerAvoidance] shouldBlockLanding: override check failed safely: {}",
                            t.toString()
                    );
                }
            }

            // Even without an override, any nearby player within (radius * 1.1)
            // is enough to block landing.
            Player nearest = null;
            try {
                // Slightly inflated radius so we don't flap right on the edge.
                double inflatedRadius = AVOID_PLAYER_RADIUS * 1.1D;
                nearest = findNearestPlayerWithin(raven, inflatedRadius);
            } catch (Throwable t) {
                if (raven.tickCount % 80 == 0) {
                    LOG.warn(
                            "[PlayerAvoidance] shouldBlockLanding: player search failed safely: {}",
                            t.toString()
                    );
                }
            }

            if (nearest != null) {
                if (raven.tickCount % 40 == 0) {
                    double dist = nearest.distanceTo(raven);
                    LOG.debug(
                            "[PlayerAvoidance] shouldBlockLanding: true (nearest player={} dist={}). pos={} aiState={}",
                            nearest.getName().getString(),
                            String.format("%.3f", dist),
                            raven.position(),
                            raven.getAIState()
                    );
                }
                return true;
            }

        } catch (Throwable t) {
            // Completely swallow failures with a debug log; landing must not crash the game.
            if (raven != null && raven.tickCount % 80 == 0) {
                LOG.warn(
                        "[PlayerAvoidance] shouldBlockLanding: failed safely with exception: {}",
                        t.toString()
                );
            }
        }
        return false;
    }

    private static boolean isHoldingLureNugget(Player p) {
        try {
            if (p == null) return false;
            ItemStack a = p.getMainHandItem();
            if (a != null && !a.isEmpty() && (a.is(Items.GOLD_NUGGET) || a.is(Items.IRON_NUGGET))) return true;
            ItemStack b = p.getOffhandItem();
            return b != null && !b.isEmpty() && (b.is(Items.GOLD_NUGGET) || b.is(Items.IRON_NUGGET));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String safeItem(ItemStack st) {
        try {
            if (st == null) return "null";
            if (st.isEmpty()) return "empty";
            return String.valueOf(st.getItem());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Nullable
    private static Player findNearestPlayerWithin(RavenEntity raven, double radius) {
        try {
            Vec3 pos = raven.position();
            double r = Math.max(0.1D, radius);

            Player best = null;
            double bestD2 = Double.MAX_VALUE;

            List<? extends Player> players = raven.level().players();
            for (Player p : players) {
                if (p == null || !p.isAlive() || p.isSpectator()) {
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
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[PlayerAvoidance] findNearestPlayerWithin failed safely: {}", t.toString());
            }
            return null;
        }
    }

    private static String safeName(Player p) {
        try {
            if (p == null) return "null";
            return p.getGameProfile() != null
                    ? p.getGameProfile().getName()
                    : p.getName().getString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Nullable
    public Vec3 computePlayerAvoidanceFleeTarget(Player player, RavenEntity ravenEntity) {
        try {
            if (player == null) return null;

            final Vec3 ravenPos = ravenEntity.position();
            final Vec3 playerPos = player.position();

            // -----------------------------
            // 1) Direction away from player in XZ
            // -----------------------------
            double dx = ravenPos.x - playerPos.x;
            double dz = ravenPos.z - playerPos.z;

            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-4D) {
                // Degenerate case: same XZ. Choose a random horizontal direction.
                RandomSource rnd = ravenEntity.getRandom();
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
            // -----------------------------
            boolean playerAbove = playerPos.y > ravenPos.y + 1.25D;

            double baseY = ravenPos.y;

            // Gentle down-bias if player is above (still stable, not a search).
            double desiredDrop = playerAbove ? 3.0D : 0.75D;

            double tyRaw = baseY - desiredDrop;

            // Clamp Y to home bounds using RavenEntity accessor (logic identical).
            int tyInt = ravenEntity.clampYToHomeBoundsPublic(Mth.floor(tyRaw));
            double ty = tyInt + 0.75D; // keep in-air, stable

            // -----------------------------
            // 4) Find an empty-ish target cell WITHOUT changing Y all the time
            // -----------------------------
            BlockPos base = BlockPos.containing(tx, ty, tz);

            BlockPos chosen = null;

            // First attempt: base spot.
            if (ravenEntity.level().isEmptyBlock(base) && ravenEntity.level().getFluidState(base).isEmpty()) {
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
                        if (ravenEntity.level().isEmptyBlock(p1) && ravenEntity.level().getFluidState(p1).isEmpty()) {
                            chosen = p1;
                            break;
                        }

                        if (ozB != ozA) {
                            BlockPos p2 = new BlockPos(base.getX() + ox, y, base.getZ() + ozB);
                            if (ravenEntity.level().isEmptyBlock(p2) && ravenEntity.level().getFluidState(p2).isEmpty()) {
                                chosen = p2;
                                break;
                            }
                        }
                    }

                    for (int oz = -r + 1; oz <= r - 1 && chosen == null; oz++) {
                        int oxA = -r;
                        int oxB = r;

                        BlockPos p1 = new BlockPos(base.getX() + oxA, y, base.getZ() + oz);
                        if (ravenEntity.level().isEmptyBlock(p1) && ravenEntity.level().getFluidState(p1).isEmpty()) {
                            chosen = p1;
                            break;
                        }

                        if (oxB != oxA) {
                            BlockPos p2 = new BlockPos(base.getX() + oxB, y, base.getZ() + oz);
                            if (ravenEntity.level().isEmptyBlock(p2) && ravenEntity.level().getFluidState(p2).isEmpty()) {
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
                if (ravenEntity.level().isEmptyBlock(up) && ravenEntity.level().getFluidState(up).isEmpty()) {
                    chosen = up;
                }
            }
            if (chosen == null) {
                BlockPos down = base.below(1);
                if (ravenEntity.level().isEmptyBlock(down) && ravenEntity.level().getFluidState(down).isEmpty()) {
                    chosen = down;
                }
            }

            // Absolute fallback: keep stable Y and just use the target XZ (even if not empty)
            if (chosen == null) {
                chosen = base;
                if (ravenEntity.tickCount % 40 == 0) {
                    RavenEntity.getSharedLogger().debug(
                            "[RavenEntity] computePlayerAvoidanceFleeTarget: no empty spot found near base={} (using base as fallback) ravenPos={} playerPos={} playerAbove={}",
                            base, ravenPos, playerPos, playerAbove
                    );
                }
            }

            Vec3 out = new Vec3(chosen.getX() + 0.5D, ty, chosen.getZ() + 0.5D);

            if (ravenEntity.tickCount % 40 == 0) {
                RavenEntity.getSharedLogger().debug(
                        "[RavenEntity] computePlayerAvoidanceFleeTarget: ravenPos={} playerPos={} playerAbove={} out={} baseY={} tyRaw={} tyStable={}",
                        ravenPos, playerPos, playerAbove, out,
                        String.format("%.2f", baseY),
                        String.format("%.2f", tyRaw),
                        String.format("%.2f", ty)
                );
            }

            return out;

        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 40 == 0) {
                RavenEntity.getSharedLogger().warn("[RavenEntity] computePlayerAvoidanceFleeTarget failed safely: {}", t.toString());
            }
            return null;
        }
    }

    /**
     * Called by PlayerAvoidance when a player is close enough that we should
     * "fly away" instead of idling / roaming normally.
     */
    public void requestPlayerAvoidanceFleeTarget(Player player, double distance, RavenEntity ravenEntity) {
        try {
            if (player == null) {
                return;
            }
            if (ravenEntity.level() == null || ravenEntity.level().isClientSide) {
                return;
            }
            if (!ravenEntity.isAlive()) {
                return;
            }

            // If we're already teleporting (panic or otherwise), don't fight that.
            Teleportation tpNow = null;
            try {
                tpNow = ravenEntity.getTeleportation();
            } catch (Throwable ignored) {
                tpNow = null;
            }
            if (tpNow != null && tpNow.teleportSeqPhase != Teleportation.TeleportSeqPhase.NONE) {
                return;
            }

            // ------------------------------------------------------------------
            // AVOIDANCE RE-ARM GUARD:
            // If an avoidance override is already active AND we still have a
            // flyTarget intent, do NOT clear / re-plan every tick.
            // Just extend the override window and bail.
            // ------------------------------------------------------------------
            boolean overrideActive = ravenEntity.getPlayerAvoidanceOverrideTicks() > 0;

            Vec3 currentFly = ravenEntity.getFlyTarget();
            int flyTtl = ravenEntity.getFlyTargetTimeoutTicks();
            boolean hasFlyIntent = (currentFly != null && flyTtl > 0);

            if (overrideActive && hasFlyIntent) {
                int minOverride = 80; // ~4s
                ravenEntity.setPlayerAvoidanceOverrideTicks(
                        Math.max(ravenEntity.getPlayerAvoidanceOverrideTicks(), minOverride)
                );

                if (ravenEntity.tickCount % 40 == 0) {
                    String name;
                    try {
                        name = player.getGameProfile() != null
                                ? player.getGameProfile().getName()
                                : player.getName().getString();
                    } catch (Throwable t) {
                        name = "unknown";
                    }

                    RavenEntity.getSharedLogger().debug(
                            "[RavenEntity] Player avoidance re-arm skipped (already have intent). player={} dist={} overrideTicks={} hasFlyIntent={} flyTarget={}",
                            name,
                            String.format("%.2f", distance),
                            ravenEntity.getPlayerAvoidanceOverrideTicks(),
                            hasFlyIntent,
                            currentFly
                    );
                }
                return;
            }

            // Do not re-arm avoidance too aggressively if a rearm cooldown is active.
            if (ravenEntity.getPlayerAvoidanceRearmCooldownTicks() > 0) {
                return;
            }

            final Vec3 ravenPos = ravenEntity.position();
            Vec3 fleeTarget = null;

            // ------------------------------------------------------------------
            // 1) Compute a flee target that keeps us away from the player.
            // ------------------------------------------------------------------
            try {
                fleeTarget = computePlayerAvoidanceFleeTarget(player, ravenEntity);
            } catch (Throwable t) {
                fleeTarget = null;
                if (ravenEntity.tickCount % 40 == 0) {
                    RavenEntity.getSharedLogger().warn(
                            "[RavenEntity] requestPlayerAvoidanceFleeTarget: computePlayerAvoidanceFleeTarget failed safely: {}",
                            t.toString()
                    );
                }
            }

            // If we could not compute a flee target, abort.
            if (fleeTarget == null) {
                if (ravenEntity.tickCount % 40 == 0) {
                    String name;
                    try {
                        name = player.getGameProfile() != null
                                ? player.getGameProfile().getName()
                                : player.getName().getString();
                    } catch (Throwable t) {
                        name = "unknown";
                    }
                    RavenEntity.getSharedLogger().warn(
                            "[RavenEntity] requestPlayerAvoidanceFleeTarget: no flee target (computePlayerAvoidanceFleeTarget returned null). " +
                                    "Skipping avoidance. player={} dist={} pos={}",
                            name,
                            String.format("%.2f", distance),
                            ravenPos
                    );
                }
                return;
            }

            // ------------------------------------------------------------------
            // 2) Arm avoidance flight intent.
            // ------------------------------------------------------------------
            try {
                ravenEntity.forceRoamFlightFromThreat(fleeTarget, player);
            } catch (Throwable t) {
                if (ravenEntity.tickCount % 40 == 0) {
                    RavenEntity.getSharedLogger().error(
                            "[RavenEntity] requestPlayerAvoidanceFleeTarget: forceRoamFlightFromThreat failed safely: {}",
                            t.toString()
                    );
                }
            }

            // ------------------------------------------------------------------
            // 3) Arm / extend the hard override window
            // ------------------------------------------------------------------
            int minOverride = 80; // ~4 seconds
            ravenEntity.setPlayerAvoidanceOverrideTicks(
                    Math.max(ravenEntity.getPlayerAvoidanceOverrideTicks(), minOverride)
            );

            // While in avoidance we don't want the normal local avoidance cooldown to block us.
            ravenEntity.setAvoidanceCooldownTicks(0);
            ravenEntity.setStuckTicks(0);

            // Flight posture: we MUST be flying, not grounded.
            ravenEntity.setNoGravity(true);
            if (ravenEntity.getAnimMode() != RavenAnimMode.IN_AIR) {
                ravenEntity.setAnimMode(RavenAnimMode.IN_AIR);
            }

            // Give a little upward kick so we actually take off from idle/perch.
            Vec3 vel = ravenEntity.getDeltaMovement();
            double vy = vel.y;
            if (vy < 0.25D) {
                vy = 0.25D;
            }
            ravenEntity.setDeltaMovement(vel.x, vy, vel.z);

            // Make sure ROAM_FLY takeoff lock engages; this keeps us in air briefly.
            if (ravenEntity.getAIState() != RavenAIState.ROAM_FLY) {
                ravenEntity.setAIState(RavenAIState.ROAM_FLY);
            }
            ravenEntity.setRoamTicksRemaining(0);
            if (ravenEntity.getIdleLockTicks() <= 0) {
                ravenEntity.setIdleLockTicks(12);
            }

            if (ravenEntity.tickCount % 20 == 0) {
                String name;
                try {
                    name = player.getGameProfile() != null
                            ? player.getGameProfile().getName()
                            : player.getName().getString();
                } catch (Throwable t) {
                    name = "unknown";
                }

                Vec3 flyTargetNow = ravenEntity.getFlyTarget();

                RavenEntity.getSharedLogger().debug(
                        "[RavenEntity] Player avoidance flee: player={} dist={} fromPos={} fleeTarget={} overrideTicks={} flyTarget={}",
                        name,
                        String.format("%.2f", distance),
                        ravenPos,
                        fleeTarget,
                        ravenEntity.getPlayerAvoidanceOverrideTicks(),
                        flyTargetNow
                );
            }

        } catch (Throwable t) {
            RavenEntity.getSharedLogger().error("[RavenEntity] requestPlayerAvoidanceFleeTarget failed", t);
        }
    }
}
