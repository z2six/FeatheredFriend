// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/PlayerAvoidance.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

public final class PlayerAvoidance {

    private static final Logger LOG = LogUtils.getLogger();

    // Player presence radius in blocks (3D)
    public static final double AVOID_PLAYER_RADIUS = 25.0D;
    private static final double AVOID_PLAYER_RADIUS_SQR = AVOID_PLAYER_RADIUS * AVOID_PLAYER_RADIUS;

    // Panic teleport radius (3D)
    private static final double PANIC_TELEPORT_RADIUS = 5.0D;
    private static final double PANIC_TELEPORT_RADIUS_SQR = PANIC_TELEPORT_RADIUS * PANIC_TELEPORT_RADIUS;

    // IMPORTANT: check every tick so panic teleport triggers immediately when you rush into it.
    // (We keep a cheap nearest-player scan; RavenEntity itself has its own anti-spam for panic teleports.)
    private static final int RECHECK_COOLDOWN_TICKS = 1;

    private PlayerAvoidance() {}

    /**
     * Call this from tickRoamFly() and tickIdleGround().
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

            Player nearest = findNearestPlayerWithin(raven, AVOID_PLAYER_RADIUS);
            if (nearest == null) return;

            Vec3 ravenPos = raven.position();
            Vec3 playerPos = nearest.position();

            double d2 = ravenPos.distanceToSqr(playerPos);
            if (d2 > AVOID_PLAYER_RADIUS_SQR) return;

            double dist = Math.sqrt(Math.max(0.0D, d2));

            // ------------------------------------------------------------
            // NEW: LURE FOLLOW OVERRIDE (gold nugget)
            // ------------------------------------------------------------
            boolean holdingNugget = isHoldingGoldenNugget(nearest);

            if (holdingNugget) {
                // Ask the raven to arm / maintain lure-follow.
                boolean armed = false;
                try {
                    raven.requestLureFollowPlayer(nearest, dist);
                    armed = raven.isLureFollowActive();
                } catch (Throwable t) {
                    armed = false;
                    if (raven.tickCount % 40 == 0) {
                        LOG.warn("[PlayerAvoidance] LURE follow request failed safely: {}", t.toString());
                    }
                }

                if (raven.tickCount % 20 == 0) {
                    LOG.info("[PlayerAvoidance] LURE follow requested: player={} dist={} ravenPos={} playerPos={} mainHand={} offHand={} armedNow={}",
                            safeName(nearest),
                            String.format("%.2f", dist),
                            ravenPos,
                            playerPos,
                            safeItem(nearest.getMainHandItem()),
                            safeItem(nearest.getOffhandItem()),
                            armed);
                }

                // If lure-follow is active, we MUST NOT run avoidance/panic logic.
                if (armed) {
                    return;
                }
                // If not armed for some reason, we fall through to normal avoidance logic.
            }

            // Panic teleport if extremely close.
            if (d2 <= PANIC_TELEPORT_RADIUS_SQR) {
                try {
                    net.z2six.featheredfriend.entity.raven.modules.Teleportation tp = null;
                    try {
                        tp = raven.getTeleportation();
                    } catch (Throwable ignored) {
                        tp = null;
                    }

                    if (tp != null) {
                        tp.requestPanicTeleportAwayFromPlayer(nearest, dist, raven);

                        if (raven.tickCount % 20 == 0) {
                            LOG.info("[PlayerAvoidance] PANIC teleport requested: player={} dist={} ravenPos={}",
                                    safeName(nearest),
                                    String.format("%.2f", dist),
                                    ravenPos);
                        }
                    } else {
                        if (raven.tickCount % 40 == 0) {
                            LOG.warn("[PlayerAvoidance] PANIC teleport skipped: teleportation module null. player={} dist={} ravenPos={}",
                                    safeName(nearest),
                                    String.format("%.2f", dist),
                                    ravenPos);
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
            raven.requestPlayerAvoidanceFleeTarget(nearest, dist);

            if (raven.tickCount % 20 == 0) {
                LOG.info("[PlayerAvoidance] avoidance requested: player={} dist={} ravenPos={}",
                        safeName(nearest),
                        String.format("%.2f", dist),
                        ravenPos);
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
     * actually arm a special avoidance path, because the user wants:
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
                    LOG.info(
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

    private static boolean isHoldingGoldenNugget(Player p) {
        try {
            if (p == null) return false;
            ItemStack a = p.getMainHandItem();
            if (a != null && !a.isEmpty() && a.is(Items.GOLD_NUGGET)) return true;
            ItemStack b = p.getOffhandItem();
            return b != null && !b.isEmpty() && b.is(Items.GOLD_NUGGET);
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

            AABB box = new AABB(
                    pos.x - r, pos.y - r, pos.z - r,
                    pos.x + r, pos.y + r, pos.z + r
            );

            List<Player> players = raven.level().getEntitiesOfClass(Player.class, box, p -> {
                try {
                    return p != null && p.isAlive() && !p.isSpectator();
                } catch (Throwable ignored) {
                    return false;
                }
            });

            Player best = null;
            double bestD2 = Double.MAX_VALUE;

            for (Player p : players) {
                if (p == null) continue;
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
            return p.getGameProfile() != null ? p.getGameProfile().getName() : p.getName().getString();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
