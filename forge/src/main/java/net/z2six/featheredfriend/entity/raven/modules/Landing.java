// forge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/Landing.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.entity.raven.RavenAIState;
import net.z2six.featheredfriend.entity.raven.RavenAnimMode;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class Landing {

    private static final Logger LOG = LogUtils.getLogger();

    private final RavenEntity raven;

    public Landing(RavenEntity raven) {
        this.raven = raven;
    }

    // -------------
    // BEGIN VARS
    // -------------

    // Perch footprint rules (2x2, step allowed)
    private static final int PERCH_FOOTPRINT_SIZE = 2; // 2x2
    private static final int PERCH_STEP_DOWN_MAX = 1;  // allow topY and topY-1 within the 2x2

    // How close we must be to the true center of the 2x2 to consider "perched"
    private static final double PERCH_CENTER_EPS = 0.55D;

    // Store landing target as the NW corner of the 2x2 footprint at the chosen topY
    // landingLeafPos used to be a single leaf; now it represents the perch-corner "anchor".
    @Nullable
    public BlockPos landingLeafPos = null;

    @Nullable
    public BlockPos idlePerchCorner = null; // NW corner of the 2x2 perch footprint we are committed to during IDLE_GROUND

    // Target selection search
    private static final int LAND_SEARCH_RADIUS = 18;
    private static final int LAND_SEARCH_ATTEMPTS = 60;
    private static final int LAND_SCAN_DOWN = 32;

    // REQUIRED: always require 10 blocks of air above the leaves block.
    public static final int LAND_REQUIRED_AIR_ABOVE = 10;

    // Canopy edge avoidance: require thick canopy neighbors around the leaf block.
    public static final int CANOPY_NEIGHBOR_LEAVES_REQUIRED = 5;

    // Landing phases tuning
    public static final int LANDING_MAX_TOTAL_TICKS = 8 * 20;

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

    // Local view of landing phases—mapped onto RavenEntity.landingPhase via reflection.
    private enum Phase {
        NONE,
        FLY_TO_OVERHEAD,
        DESCEND_SLOW,
        DROP
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

    // -------------
    // END VARS
    // -------------

    // ---------------------------------------------------------------------
    // Small reflection helpers (low-hanging fruit: avoid direct private access compiler errors)
    // ---------------------------------------------------------------------

    @Nullable
    private static Field findField(Class<?> clazz, String fieldName) {
        try {
            Class<?> c = clazz;
            while (c != null) {
                try {
                    return c.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {
                }
                c = c.getSuperclass();
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object getFieldValueSafe(Object target, String fieldName) {
        try {
            if (target == null || fieldName == null) return null;
            Field f = findField(target.getClass(), fieldName);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean setFieldValueSafe(Object target, String fieldName, Object value) {
        try {
            if (target == null || fieldName == null) return false;
            Field f = findField(target.getClass(), fieldName);
            if (f == null) return false;
            f.setAccessible(true);
            f.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int getIntFieldSafe(Object target, String fieldName, int fallback) {
        try {
            Object v = getFieldValueSafe(target, fieldName);
            if (v instanceof Integer i) return i;
            return fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void setIntFieldSafe(Object target, String fieldName, int value) {
        try {
            if (target == null || fieldName == null) return;
            Field f = findField(target.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.setInt(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static boolean invokeVoidMethodSafe(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            if (target == null || methodName == null) return false;

            Method m = null;
            Class<?> c = target.getClass();
            while (c != null && m == null) {
                try {
                    m = c.getDeclaredMethod(methodName, paramTypes);
                } catch (NoSuchMethodException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (m == null) return false;

            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static double horizontalDistanceTo(Vec3 a, Vec3 b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---------------------------------------------------------------------
    // Reflection helpers for landingPhase / landingTicks on RavenEntity
    // ---------------------------------------------------------------------

    private static Phase getPhaseFromRaven(RavenEntity ravenEntity) {
        try {
            Object v = getFieldValueSafe(ravenEntity, "landingPhase");
            if (v instanceof Enum<?> e) {
                try {
                    return Phase.valueOf(e.name());
                } catch (IllegalArgumentException ignored) {
                    return Phase.NONE;
                }
            }
            if (v != null) {
                try {
                    return Phase.valueOf(String.valueOf(v));
                } catch (IllegalArgumentException ignored) {
                    return Phase.NONE;
                }
            }
            return Phase.NONE;
        } catch (Throwable ignored) {
            return Phase.NONE;
        }
    }

    private static void setPhaseOnRaven(RavenEntity ravenEntity, Phase phase) {
        try {
            if (ravenEntity == null || phase == null) return;
            Field f = findField(ravenEntity.getClass(), "landingPhase");
            if (f == null) return;
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (!t.isEnum()) return;

            @SuppressWarnings("unchecked")
            Class<? extends Enum> enumClass = (Class<? extends Enum>) t.asSubclass(Enum.class);

            Enum<?> enumVal;
            try {
                enumVal = Enum.valueOf(enumClass, phase.name());
            } catch (IllegalArgumentException ex) {
                // Underlying enum does not have this constant; fail silently.
                return;
            }
            f.set(ravenEntity, enumVal);
        } catch (Throwable ignored) {
        }
    }

    private static int getLandingTicksFromRaven(RavenEntity ravenEntity) {
        return getIntFieldSafe(ravenEntity, "landingTicks", 0);
    }

    private static void setLandingTicksOnRaven(RavenEntity ravenEntity, int value) {
        setIntFieldSafe(ravenEntity, "landingTicks", value);
    }

    // ---------------------------------------------------------------------
    // Public-ish accessors for RavenEntity usage (optional, but handy)
    // ---------------------------------------------------------------------

    @Nullable
    public BlockPos getLandingLeafPos() {
        return landingLeafPos;
    }

    public void setLandingLeafPos(BlockPos pos) {
        this.landingLeafPos = pos;
    }

    @Nullable
    public BlockPos getIdlePerchCorner() {
        return idlePerchCorner;
    }

    public void setIdlePerchCorner(BlockPos pos) {
        this.idlePerchCorner = pos;
    }

    public boolean isLandingActive(RavenEntity ravenEntity) {
        return getPhaseFromRaven(ravenEntity) != Phase.NONE;
    }

    // ---------------------------------------------------------------------
    // Core landing helper logic
    // ---------------------------------------------------------------------

    @Nullable
    public BlockPos findBestPerchCornerForLanding(BlockPos landingLeaf, BlockPos feetBlock, RavenEntity ravenEntity) {
        try {
            BlockPos best = null;
            double bestD2 = Double.MAX_VALUE;

            // Candidate corners to try.
            // Priority:
            //  1) corners around the committed landingLeafPos (prevents random other 2x2 selection)
            //  2) corners around feetBlock (fallback when landingLeaf is null or stale)
            BlockPos[] seeds;
            if (landingLeaf != null) {
                seeds = new BlockPos[]{landingLeaf, feetBlock};
            } else {
                seeds = new BlockPos[]{feetBlock};
            }

            for (BlockPos seed : seeds) {
                BlockPos[] corners = new BlockPos[]{
                        seed,
                        seed.west(),
                        seed.north(),
                        seed.west().north()
                };

                for (BlockPos c : corners) {
                    if (!isValidPerchCornerAtTopY(c, ravenEntity)) {
                        continue;
                    }

                    Vec3 center = perchCenterTop(c, ravenEntity);
                    double dx = ravenEntity.getX() - center.x;
                    double dz = ravenEntity.getZ() - center.z;
                    double d2 = dx * dx + dz * dz;

                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = c;
                    }
                }
            }

            return best;

        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] findBestPerchCornerForLanding failed safely: {}", t.toString());
            }
            return null;
        }
    }

    public boolean isOnValidPerchNowRelaxedForLanding(String debugTag, RavenEntity ravenEntity) {
        // ---------------------------------------------------------------------
        // HARD GATE: do not allow relaxed perch acceptance while the raven is
        // in a player-avoidance override window. Even if we are physically
        // over a perfect perch, avoidance must win.
        // ---------------------------------------------------------------------
        try {
            if (ravenEntity.isPlayerAvoidanceOverrideActive()) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug(
                            "[Landing] {}: skipping relaxed perch check due to player avoidance override. pos={} aiState={}",
                            debugTag,
                            ravenEntity.position(),
                            ravenEntity.getAIState()
                    );
                }
                return false;
            }
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn(
                        "[Landing] {}: avoidance gate failed safely in isOnValidPerchNowRelaxedForLanding: {}",
                        debugTag,
                        t.toString()
                );
            }
            // On failure we fall back to the normal logic.
        }

        try {
            Phase phase = getPhaseFromRaven(ravenEntity);
            int landingTicks = getLandingTicksFromRaven(ravenEntity);

            // Only makes sense when we are in "landing/resting physics".
            // If we are still flying (noGravity=true), do NOT use relaxed logic.
            if (ravenEntity.isNoGravity()) {
                return false;
            }

            if (ravenEntity.getBoundingBox() == null) {
                return false;
            }

            // We only want to do the "force commit" behavior during DROP.
            // Otherwise, preserve your stricter semantics.
            final boolean inDrop = (phase == Phase.DROP);

            // Probe block under feet (use bb minY)
            BlockPos feetBlock = BlockPos.containing(
                    ravenEntity.getX(),
                    ravenEntity.getBoundingBox().minY - 0.001D,
                    ravenEntity.getZ()
            );

            // Step 1: pick the best perch corner.
            // Prefer corners around the committed landingLeafPos (prevents selecting a "different" nearby 2x2).
            BlockPos bestCorner;
            try {
                bestCorner = findBestPerchCornerForLanding(this.landingLeafPos, feetBlock, ravenEntity);
            } catch (Throwable t) {
                bestCorner = null;
            }

            if (bestCorner == null) {
                bestCorner = findValidPerchCornerNearXZ(feetBlock.getX(), feetBlock.getZ(), ravenEntity);
            }

            if (bestCorner == null) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug(
                            "[Landing] {}: relaxedPerch=false (no corner) pos={} feetBlock={} onGround={} vColl={} phase={} landingTicks={}",
                            debugTag, ravenEntity.position(), feetBlock,
                            ravenEntity.onGround(), ravenEntity.verticalCollision, phase, landingTicks
                    );
                }
                return false;
            }

            // Basic validity guard (cheap).
            if (!isValidPerchCornerAtTopY(bestCorner, ravenEntity)) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug(
                            "[Landing] {}: relaxedPerch=false (corner invalid) pos={} corner={} phase={} landingTicks={}",
                            debugTag, ravenEntity.position(), bestCorner, phase, landingTicks
                    );
                }
                return false;
            }

            Vec3 center = perchCenterTop(bestCorner, ravenEntity);
            Vec3 pos = ravenEntity.position();

            double dx = pos.x - center.x;
            double dz = pos.z - center.z;
            double dXZ = Math.sqrt(dx * dx + dz * dz);

            // Normal relaxed threshold: only slightly relaxed vs strict perched checks.
            final double relaxedEps = Math.max(PERCH_CENTER_EPS, 0.35D);

            boolean withinRelaxed = dXZ <= relaxedEps;

            // ---------------------------------------------------------
            // "DROP COMMIT" SNAP (only during DROP)
            // ---------------------------------------------------------
            boolean canForceCommit =
                    inDrop
                            && (ravenEntity.onGround() || ravenEntity.verticalCollision)
                            && landingTicks >= 20; // 1s settle time; tune if needed

            if (!withinRelaxed && canForceCommit) {
                final double maxSnapRadius = 1.80D;

                Vec3 vel = ravenEntity.getDeltaMovement();
                double speedSqr = vel.lengthSqr();
                boolean movingSlowEnough = speedSqr <= 0.08D;

                if (dXZ <= maxSnapRadius && movingSlowEnough) {
                    double snapX = center.x;
                    double snapZ = center.z;
                    double keepY = ravenEntity.getY();

                    ravenEntity.setPos(snapX, keepY, snapZ);
                    ravenEntity.hurtMarked = true;

                    // Keep landingLeafPos consistent with what we're snapping to.
                    this.landingLeafPos = bestCorner;

                    if (ravenEntity.tickCount % 20 == 0) {
                        LOG.debug(
                                "[Landing] {}: DROP-COMMIT SNAP applied dXZ={} -> 0.0 corner={} center={} posNow={} vel={} landingTicks={}",
                                debugTag,
                                String.format("%.3f", dXZ),
                                bestCorner,
                                center,
                                ravenEntity.position(),
                                vel,
                                landingTicks
                        );
                    }

                    return true;
                } else {
                    if (ravenEntity.tickCount % 20 == 0) {
                        LOG.debug(
                                "[Landing] {}: DROP force-commit skipped dXZ={} maxSnap={} movingSlow={} vel={} corner={} center={} landingTicks={}",
                                debugTag,
                                String.format("%.3f", dXZ),
                                String.format("%.3f", maxSnapRadius),
                                movingSlowEnough,
                                vel,
                                bestCorner,
                                center,
                                landingTicks
                        );
                    }
                }
            }

            boolean ok = withinRelaxed;

            if (!ok && (ravenEntity.tickCount % 20 == 0)) {
                LOG.debug(
                        "[Landing] {}: relaxedPerch=false dXZ={} eps={} pos={} center={} corner={} onGround={} vColl={} vel={} phase={} landingTicks={}",
                        debugTag,
                        String.format("%.3f", dXZ),
                        String.format("%.3f", relaxedEps),
                        pos,
                        center,
                        bestCorner,
                        ravenEntity.onGround(),
                        ravenEntity.verticalCollision,
                        ravenEntity.getDeltaMovement(),
                        phase,
                        landingTicks
                );
            } else if (ok && (ravenEntity.tickCount % 20 == 0)) {
                LOG.debug(
                        "[Landing] {}: relaxedPerch=true dXZ={} eps={} pos={} center={} corner={} (will enter idle + snap)",
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
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] isOnValidPerchNowRelaxedForLanding failed safely: {}", t.toString());
            }
            return false;
        }
    }

    private boolean isPerchFootprintStillSupported(BlockPos cornerTop, RavenEntity ravenEntity) {
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

                    boolean atTop = isLeavesAt(x, topY, z, ravenEntity);
                    boolean atStep = isLeavesAt(x, topY - 1, z, ravenEntity);

                    if (!atTop && !atStep) {
                        return false;
                    }
                    if (atTop) anyAtTop = true;
                }
            }

            return anyAtTop;
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] isPerchFootprintStillSupported failed safely: {}", t.toString());
            }
            return false;
        }
    }

    public PerchValidity validatePerchCornerAtTopY(BlockPos cornerTop, RavenEntity ravenEntity) {
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

                    boolean atTop = isLeavesAt(x, topY, z, ravenEntity);
                    boolean atStep = isLeavesAt(x, topY - 1, z, ravenEntity);

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
                    if (!hasAirColumn(start, LAND_REQUIRED_AIR_ABOVE, ravenEntity)) {
                        int blockedAtY = Integer.MIN_VALUE;
                        BlockPos blockedPos = null;
                        for (int i = 0; i < LAND_REQUIRED_AIR_ABOVE; i++) {
                            BlockPos p = start.above(i);
                            if (!ravenEntity.level().isEmptyBlock(p)) {
                                blockedAtY = p.getY();
                                blockedPos = p;
                                break;
                            }
                        }

                        String extra = "";
                        if (blockedPos != null) {
                            BlockState st = ravenEntity.level().getBlockState(blockedPos);
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

                    BlockState st = ravenEntity.level().getBlockState(new BlockPos(cx + ox, topY, cz + oz));
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

    /**
     * Landing state machine (refactored):
     * - FLY_TO_OVERHEAD uses coarse A* waypoint pathing to reach the overhead point without punching through canopy/structures.
     * - DESCEND_SLOW and DROP remain local/physics-driven (no A*), unchanged in behavior.
     *
     * IMPORTANT: landingPhase / landingTicks are stored on RavenEntity.
     * We *only* read/write those via reflection here so that tickRoamFly() and Teleportation
     * see the same state and we don't get stuck in "landingPhase=FLY_TO_OVERHEAD forever".
     */
    public void tickLandingStateMachine(RandomSource rnd, RavenEntity ravenEntity) {
        // ---------------------------------------------------------------------
        // HARD GATE: while player avoidance wants to block landing, landing is
        // NOT allowed to run at all. This is broader than just "override":
        //  - true if explicit player-avoidance override is active
        //  - OR if any player is within the avoidance radius.
        //
        // This guarantees:
        //   "Avoidance. Should. Not. Ever. Be. Interrupted. By. Landing."
        // ---------------------------------------------------------------------
        try {
            if (PlayerAvoidance.shouldBlockLanding(ravenEntity)) {
                Phase phaseNow = getPhaseFromRaven(ravenEntity);
                int landingTicksNow = getLandingTicksFromRaven(ravenEntity);

                if (phaseNow != Phase.NONE || this.landingLeafPos != null || landingTicksNow != 0) {
                    if (ravenEntity.tickCount % 20 == 0) {
                        LOG.debug(
                                "[Landing] skipping landing due to player avoidance; clearing landing state. phase={} pos={} leafPos={} idlePerchCorner={} landingTicks={}",
                                phaseNow,
                                ravenEntity.position(),
                                this.landingLeafPos,
                                this.idlePerchCorner,
                                landingTicksNow
                        );
                    }
                }

                resetLandingState("player avoidance (global block)", ravenEntity);
                return;
            }
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 40 == 0) {
                LOG.warn("[Landing] tickLandingStateMachine avoidance gate failed safely: {}", t.toString());
            }
        }

        Phase phase = getPhaseFromRaven(ravenEntity);
        int landingTicks = getLandingTicksFromRaven(ravenEntity);

        // Extra heartbeat for debugging stuck teleport / frozen flight.
        try {
            if (ravenEntity.tickCount % 40 == 0) {
                LOG.debug(
                        "[Landing] HEARTBEAT: phase={} ai={} pos={} vel={} leafPos={} idlePerchCorner={} landingTicks={}",
                        phase,
                        ravenEntity.getAIState(),
                        ravenEntity.position(),
                        ravenEntity.getDeltaMovement(),
                        this.landingLeafPos,
                        this.idlePerchCorner,
                        landingTicks
                );
            }
        } catch (Throwable ignored) {
        }

        // FIX: Do NOT allow "I’m on a perch" shortcut while we’re still in flight pathing.
        // Otherwise, passing near the 2x2 center during FLY_TO_OVERHEAD can trigger enterIdleFromLanding(),
        // which can then snap (setPos) and look like a silent teleport.
        try {
            boolean allowPerchShortcutNow = false;

            if (phase == Phase.DROP) {
                allowPerchShortcutNow = true;
            } else {
                try {
                    allowPerchShortcutNow = !ravenEntity.isNoGravity();
                } catch (Throwable t) {
                    allowPerchShortcutNow = false;
                }
            }

            if (allowPerchShortcutNow && isOnValidPerchNow(ravenEntity)) {
                if (ravenEntity.tickCount % 20 == 0) {
                    LOG.debug(
                            "[Landing] Landing shortcut -> IDLE allowed (phase={} noGravity={} pos={} vel={} leafPos={})",
                            phase, ravenEntity.isNoGravity(), ravenEntity.position(), ravenEntity.getDeltaMovement(), this.landingLeafPos
                    );
                }
                enterIdleFromLanding("Landing: perched (phase=" + phase + ")", ravenEntity);
                return;
            }
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 40 == 0) {
                LOG.warn("[Landing] tickLandingStateMachine perch-shortcut gate failed safely: {}", t.toString());
            }
        }

        switch (phase) {
            case NONE -> {
                // If tickLandingStateMachine is called while Raven thinks landingPhase=NONE,
                // we just ensure local state is clean and clear any leftover path.
                resetLandingState("tickLandingStateMachine called with NONE", ravenEntity);
                invokeVoidMethodSafe(
                        ravenEntity,
                        "clearPlannedPath",
                        new Class<?>[]{String.class},
                        new Object[]{"landing NONE"}
                );
            }

            case FLY_TO_OVERHEAD -> {
                ravenEntity.setNoGravity(true);
                if (ravenEntity.getAnimMode() != RavenAnimMode.IN_AIR) {
                    ravenEntity.setAnimMode(RavenAnimMode.IN_AIR);
                }

                if (this.landingLeafPos == null) {
                    resetLandingState("FLY_TO_OVERHEAD missing leaf", ravenEntity);
                    invokeVoidMethodSafe(
                            ravenEntity,
                            "clearPlannedPath",
                            new Class<?>[]{String.class},
                            new Object[]{"FLY_TO_OVERHEAD missing leaf"}
                    );
                    return;
                }

                Vec3 overhead = overheadTargetForLeaf(this.landingLeafPos, ravenEntity);

                // 1) Tick flyTarget TTL using the RavenEntity bridge so behavior is identical
                //    to the old in-entity logic.
                try {
                    ravenEntity.landingTickFlyTargetTimeout("Landing FLY_TO_OVERHEAD");
                } catch (Throwable t) {
                    if (ravenEntity.tickCount % 80 == 0) {
                        LOG.warn("[Landing] landingTickFlyTargetTimeout failed safely: {}", t.toString());
                    }
                }

                // 2) Detect whether we already have path/fly intent.
                boolean hasFlyIntent = false;
                boolean hasPathIntent = false;

                try {
                    Object ft = getFieldValueSafe(ravenEntity, "flyTarget");
                    Object ftt = getFieldValueSafe(ravenEntity, "flyTargetTimeoutTicks");
                    if (ft instanceof Vec3 && ftt instanceof Integer) {
                        hasFlyIntent = (ft != null) && ((Integer) ftt) > 0;
                    } else if (ft instanceof Vec3) {
                        hasFlyIntent = true;
                    }
                } catch (Throwable ignored) {
                    hasFlyIntent = false;
                }

                try {
                    Object wp = getFieldValueSafe(ravenEntity, "pathWaypoints");
                    Object wpi = getFieldValueSafe(ravenEntity, "pathWaypointIndex");
                    if (wp instanceof List<?> list) {
                        int idx = (wpi instanceof Integer) ? (Integer) wpi : 0;
                        hasPathIntent = !list.isEmpty() && idx < list.size();
                    }
                } catch (Throwable ignored) {
                    hasPathIntent = false;
                }

                Vec3 pos = ravenEntity.position();
                double horizNow = horizontalDistanceTo(pos, overhead);
                double vertNow = Math.abs(pos.y - overhead.y);

                // 3) Overhead reached -> transition to DESCEND_SLOW and clean up path/fly intent.
                if (horizNow <= OVERHEAD_HORIZONTAL_EPS && vertNow <= OVERHEAD_VERTICAL_EPS) {
                    setPhaseOnRaven(ravenEntity, Phase.DESCEND_SLOW);

                    invokeVoidMethodSafe(ravenEntity, "clearFlyTarget", new Class<?>[]{}, new Object[]{});
                    invokeVoidMethodSafe(
                            ravenEntity,
                            "clearPlannedPath",
                            new Class<?>[]{String.class},
                            new Object[]{"overhead reached -> descent"}
                    );

                    if (ravenEntity.tickCount % 20 == 0) {
                        LOG.debug(
                                "[Landing] Overhead reached -> DESCEND_SLOW (leaf={} pos={} horiz={} vert={})",
                                this.landingLeafPos,
                                pos,
                                String.format("%.3f", horizNow),
                                String.format("%.3f", vertNow)
                        );
                    }
                    return;
                }

                // 4) Only compute a new path if we don't already have one.
                if (!hasFlyIntent && !hasPathIntent) {
                    long seed = ravenEntity.getUUID().getLeastSignificantBits()
                            ^ (long) ravenEntity.tickCount
                            ^ this.landingLeafPos.asLong();

                    boolean ok = false;
                    try {
                        ok = ravenEntity.landingEnsurePathTo(overhead, 6 * 20, seed, "FLY_TO_OVERHEAD");
                    } catch (Throwable t) {
                        ok = false;
                        if (ravenEntity.tickCount % 40 == 0) {
                            LOG.warn(
                                    "[Landing] ensurePathTo failed safely (will fallback to setFlyTarget): {}",
                                    t.toString()
                            );
                        }
                    }

                    if (!ok) {
                        try {
                            ravenEntity.landingSetFlyTarget(overhead, 6 * 20);
                        } catch (Throwable t) {
                            if (ravenEntity.tickCount % 40 == 0) {
                                LOG.warn("[Landing] setFlyTarget fallback failed safely: {}", t.toString());
                            }
                        }
                    }

                    if (ravenEntity.tickCount % 20 == 0) {
                        LOG.debug(
                                "[Landing] FLY_TO_OVERHEAD acquire intent: pathOk={} overhead={} pos={} vel={}",
                                ok,
                                overhead,
                                pos,
                                ravenEntity.getDeltaMovement()
                        );
                    }
                }

                // 5) Allow your "approach-only" avoidance / retargeting.
                try {
                    ravenEntity.landingMaybeAvoidOrRetargetDuringFlightApproachOnly(rnd);
                } catch (Throwable t) {
                    if (ravenEntity.tickCount % 80 == 0) {
                        LOG.warn("[Landing] maybeAvoidOrRetargetDuringFlightApproachOnly failed safely: {}", t.toString());
                    }
                }

                // 6) Execute flight intent. This is what actually moves the raven.
                try {
                    ravenEntity.landingFlyTowardTarget(0.25D); // keep the literal used previously
                } catch (Throwable t) {
                    if (ravenEntity.tickCount % 80 == 0) {
                        LOG.warn("[Landing] flyTowardTarget failed safely: {}", t.toString());
                    }
                }

                try {
                    ravenEntity.landingAdvanceWaypointIfNeeded(6 * 20, "FLY_TO_OVERHEAD");
                } catch (Throwable t) {
                    if (ravenEntity.tickCount % 80 == 0) {
                        LOG.warn("[Landing] advanceWaypointIfNeeded failed safely: {}", t.toString());
                    }
                }

                // Extra debug when we're clearly stuck but still in this phase.
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug(
                            "[Landing] FLY_TO_OVERHEAD tick: pos={} vel={} overhead={} horiz={} vert={} hasFlyIntent={} hasPathIntent={} leaf={} landingTicks={}",
                            ravenEntity.position(),
                            ravenEntity.getDeltaMovement(),
                            overhead,
                            String.format("%.3f", horizNow),
                            String.format("%.3f", vertNow),
                            hasFlyIntent,
                            hasPathIntent,
                            this.landingLeafPos,
                            landingTicks
                    );
                }
            }

            case DESCEND_SLOW -> {
                if (this.landingLeafPos == null) {
                    resetLandingState("DESCEND_SLOW missing leaf", ravenEntity);
                    invokeVoidMethodSafe(
                            ravenEntity,
                            "clearPlannedPath",
                            new Class<?>[]{String.class},
                            new Object[]{"DESCEND_SLOW missing leaf"}
                    );
                    return;
                }

                ravenEntity.setNoGravity(true);
                if (ravenEntity.getAnimMode() != RavenAnimMode.IN_AIR) {
                    ravenEntity.setAnimMode(RavenAnimMode.IN_AIR);
                }

                Vec3 pos = ravenEntity.position();
                Vec3 leafCenter = leafCenterTop(this.landingLeafPos, ravenEntity);

                Vec3 toCenter = new Vec3(leafCenter.x - pos.x, 0.0D, leafCenter.z - pos.z);
                double d2 = toCenter.length();
                Vec3 horizVel = Vec3.ZERO;
                if (d2 > 0.0001D) {
                    Vec3 dir = toCenter.scale(1.0D / d2);
                    double sp = Mth.clamp(DESCEND_CENTER_SPEED, 0.0D, DESCEND_CENTER_MAX);
                    horizVel = new Vec3(dir.x * sp, 0.0D, dir.z * sp);
                }

                double vy = ravenEntity.getDeltaMovement().y;
                if (vy > DESCEND_SPEED_Y_MAX_UP) {
                    vy = DESCEND_SPEED_Y_MAX_UP;
                }
                if (vy > DESCEND_SPEED_Y) {
                    vy = DESCEND_SPEED_Y;
                }
                if (vy < DESCEND_SPEED_Y_MIN) {
                    vy = DESCEND_SPEED_Y_MIN;
                }

                ravenEntity.setDeltaMovement(horizVel.x, vy, horizVel.z);

                if (horizVel.lengthSqr() > 0.0001D) {
                    float yaw = (float) (Mth.atan2(horizVel.z, horizVel.x) * (180.0D / Math.PI)) - 90.0F;
                    ravenEntity.setYRot(yaw);
                    ravenEntity.setYHeadRot(yaw);
                    ravenEntity.yBodyRot = yaw;
                }

                double centerDist = horizontalDistanceTo(pos, leafCenter);
                double leafTopY = this.landingLeafPos.getY() + 1.0D;
                double aboveTop = pos.y - leafTopY;

                if (centerDist <= DROP_CENTER_EPS && aboveTop <= (DROP_START_ABOVE_LEAF_TOP_Y + 0.25D)) {
                    setPhaseOnRaven(ravenEntity, Phase.DROP);
                    if (ravenEntity.tickCount % 120 == 0) {
                        LOG.debug(
                                "[Landing] DESCEND_SLOW -> DROP (leaf={}, pos={}, centerDist={}, aboveTop={})",
                                this.landingLeafPos,
                                pos,
                                centerDist,
                                aboveTop
                        );
                    }
                } else if (ravenEntity.tickCount % 80 == 0) {
                    LOG.debug(
                            "[Landing] DESCEND_SLOW tick pos={} vel={} leaf={} centerDist={} aboveTop={}",
                            pos,
                            ravenEntity.getDeltaMovement(),
                            this.landingLeafPos,
                            String.format("%.3f", centerDist),
                            String.format("%.3f", aboveTop)
                    );
                }
            }

            case DROP -> {
                if (this.landingLeafPos == null) {
                    resetLandingState("DROP missing leaf", ravenEntity);
                    invokeVoidMethodSafe(
                            ravenEntity,
                            "clearPlannedPath",
                            new Class<?>[]{String.class},
                            new Object[]{"DROP missing leaf"}
                    );
                    return;
                }

                ravenEntity.setNoGravity(false);
                if (ravenEntity.getAnimMode() != RavenAnimMode.NO_AIR) {
                    ravenEntity.setAnimMode(RavenAnimMode.NO_AIR);
                }

                Vec3 vel = ravenEntity.getDeltaMovement();
                double vy = vel.y;

                final double IDLE_SETTLE_MAX_UP = 0.04D;
                final double IDLE_SETTLE_MIN_FALL = -0.28D;

                if (vy > IDLE_SETTLE_MAX_UP) {
                    vy = IDLE_SETTLE_MAX_UP;
                }
                if (vy > DROP_NUDGE_Y) {
                    vy = DROP_NUDGE_Y;
                }
                if (vy < IDLE_SETTLE_MIN_FALL) {
                    vy = IDLE_SETTLE_MIN_FALL;
                }

                ravenEntity.setDeltaMovement(0.0D, vy, 0.0D);

                // --- Accept slightly-off landings and COMMIT the correct 2x2 corner ---
                try {
                    boolean groundedStable = ravenEntity.onGround() || ravenEntity.verticalCollision;

                    if (groundedStable) {
                        BlockPos feetBlock = BlockPos.containing(
                                ravenEntity.getX(),
                                ravenEntity.getBoundingBox().minY - 0.001D,
                                ravenEntity.getZ()
                        );

                        BlockPos bestCorner;
                        try {
                            bestCorner = findBestPerchCornerForLanding(this.landingLeafPos, feetBlock, ravenEntity);
                        } catch (Throwable t) {
                            bestCorner = null;
                            if (ravenEntity.tickCount % 40 == 0) {
                                LOG.warn("[Landing] DROP: findBestPerchCornerForLanding failed safely: {}", t.toString());
                            }
                        }

                        if (bestCorner != null && isValidPerchCornerAtTopY(bestCorner, ravenEntity)) {
                            Vec3 center = perchCenterTop(bestCorner, ravenEntity);
                            Vec3 pos = ravenEntity.position();

                            double dx = pos.x - center.x;
                            double dz = pos.z - center.z;
                            double dXZ = Math.sqrt(dx * dx + dz * dz);

                            final double DROP_SNAP_EPS = 1.60D;

                            if (ravenEntity.tickCount % 20 == 0) {
                                LOG.debug(
                                        "[Landing] DROP: groundedStable={} bestCorner={} center={} pos={} dXZ={} eps={} landingLeafPos={} onGround={} vColl={} vel={}",
                                        groundedStable,
                                        bestCorner,
                                        center,
                                        pos,
                                        String.format("%.3f", dXZ),
                                        String.format("%.2f", DROP_SNAP_EPS),
                                        this.landingLeafPos,
                                        ravenEntity.onGround(),
                                        ravenEntity.verticalCollision,
                                        ravenEntity.getDeltaMovement()
                                );
                            }

                            if (dXZ <= DROP_SNAP_EPS) {
                                this.idlePerchCorner = bestCorner;
                                this.landingLeafPos = bestCorner;

                                enterIdleFromLanding(
                                        "DROP: grounded stable + bestCorner within snap eps (dXZ=" + String.format("%.3f", dXZ) + ")",
                                        ravenEntity
                                );
                                return;
                            }
                        }
                    }
                } catch (Throwable t) {
                    if (ravenEntity.tickCount % 40 == 0) {
                        LOG.warn("[Landing] DROP: snap-accept logic failed safely: {}", t.toString());
                    }
                }

                // Fallback: keep relaxed check.
                if (isOnValidPerchNowRelaxedForLanding("DROP", ravenEntity)) {
                    enterIdleFromLanding("DROP: on valid 2x2 perch (relaxed threshold, will snap)", ravenEntity);
                    return;
                }

                Vec3 pos = ravenEntity.position();
                double leafTopY = this.landingLeafPos.getY() + 1.0D;
                if (pos.y < leafTopY - 1.25D) {
                    if (ravenEntity.tickCount % 80 == 0) {
                        LOG.debug(
                                "[Landing] DROP missed leaf (posY={}, leafTopY={}) -> resetting landing and reattempting",
                                pos.y,
                                leafTopY
                        );
                    }
                    resetLandingState("drop missed", ravenEntity);
                    invokeVoidMethodSafe(
                            ravenEntity,
                            "clearPlannedPath",
                            new Class<?>[]{String.class},
                            new Object[]{"drop missed"}
                    );
                } else if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug(
                            "[Landing] DROP tick pos={} vel={} leafTopY={} landingTicks={}",
                            pos,
                            ravenEntity.getDeltaMovement(),
                            leafTopY,
                            landingTicks
                    );
                }
            }
        }
    }

    public void resetLandingState(String reason, RavenEntity ravenEntity) {
        try {
            Phase phase = getPhaseFromRaven(ravenEntity);
            int ticks = getLandingTicksFromRaven(ravenEntity);

            if (phase != Phase.NONE || this.landingLeafPos != null || ticks != 0) {
                if (ravenEntity.tickCount % 120 == 0) {
                    LOG.debug(
                            "[Landing] resetLandingState(reason={}) phase={} leaf={} ticks={}",
                            reason,
                            phase,
                            this.landingLeafPos,
                            ticks
                    );
                }
            }

            setPhaseOnRaven(ravenEntity, Phase.NONE);
            setLandingTicksOnRaven(ravenEntity, 0);
            this.landingLeafPos = null;

        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] resetLandingState failed safely: {}", t.toString());
            }
        }
    }

    public void enterIdleFromLanding(String reason, RavenEntity ravenEntity) {
        try {
            // Best-effort: clear path + fly target
            invokeVoidMethodSafe(
                    ravenEntity,
                    "clearPlannedPath",
                    new Class<?>[]{String.class},
                    new Object[]{"enter idle: " + reason}
            );
            invokeVoidMethodSafe(
                    ravenEntity,
                    "clearFlyTarget",
                    new Class<?>[]{},
                    new Object[]{}
            );

            setFieldValueSafe(ravenEntity, "flyTargetTimeoutTicks", 0);
            setFieldValueSafe(ravenEntity, "avoidanceCooldownTicks", 0);
            setFieldValueSafe(ravenEntity, "stuckTicks", 0);
            setFieldValueSafe(ravenEntity, "lastDistToTarget", Double.NaN);

            boolean hadAnyIntent = false;
            try {
                Object pg = getFieldValueSafe(ravenEntity, "pathGoal");
                Object ppg = getFieldValueSafe(ravenEntity, "pathPendingGoal");
                Object wp = getFieldValueSafe(ravenEntity, "pathWaypoints");
                if (pg != null || ppg != null) hadAnyIntent = true;
                if (wp instanceof List<?> list && !list.isEmpty()) hadAnyIntent = true;
            } catch (Throwable ignored) {
            }

            setFieldValueSafe(ravenEntity, "pathGoal", null);
            setFieldValueSafe(ravenEntity, "pathPendingGoal", null);
            setFieldValueSafe(ravenEntity, "pathWaypoints", null);
            setFieldValueSafe(ravenEntity, "pathWaypointIndex", 0);
            setFieldValueSafe(ravenEntity, "pathRetryCooldownTicks", 0);

            if (hadAnyIntent && ravenEntity.tickCount % 20 == 0) {
                LOG.debug(
                        "[Landing] enterIdleFromLanding: cleared leftover path/goal intent. reason={} pos={}",
                        reason,
                        ravenEntity.position()
                );
            }

            BlockPos committedCorner = this.landingLeafPos;
            if (committedCorner == null) {
                BlockPos feetBlock = BlockPos.containing(
                        ravenEntity.getX(),
                        ravenEntity.getBoundingBox().minY - 0.001D,
                        ravenEntity.getZ()
                );
                committedCorner = findValidPerchCornerNearXZ(feetBlock.getX(), feetBlock.getZ(), ravenEntity);
            }

            resetLandingState("enter idle: " + reason, ravenEntity);
            this.idlePerchCorner = committedCorner;

            ravenEntity.setAIState(RavenAIState.IDLE_GROUND);

            setFieldValueSafe(ravenEntity, "idleCommitTicks", 60); // ~3 seconds
            setFieldValueSafe(ravenEntity, "idleLockTicks", 50);   // matches your original constant
            setFieldValueSafe(ravenEntity, "idleLeafLossTicks", 0);

            try {
                Object itr = getFieldValueSafe(ravenEntity, "idleTicksRemaining");
                int idleTicksRemaining = (itr instanceof Integer) ? (Integer) itr : 0;
                if (idleTicksRemaining <= 0) {
                    RandomSource rnd = ravenEntity.getRandom();
                    int IDLE_MIN_TICKS = 15 * 20;
                    int IDLE_MAX_TICKS = 30 * 20;
                    int newTicks = IDLE_MIN_TICKS + rnd.nextInt(Math.max(1, IDLE_MAX_TICKS - IDLE_MIN_TICKS + 1));
                    setFieldValueSafe(ravenEntity, "idleTicksRemaining", newTicks);
                    setFieldValueSafe(ravenEntity, "idleTargetYaw", ravenEntity.getYRot());
                    setFieldValueSafe(ravenEntity, "idleNextTurnTicks", 10 + rnd.nextInt(50));
                }
            } catch (Throwable ignored) {
            }

            setFieldValueSafe(ravenEntity, "roamTicksRemaining", 0);

            ravenEntity.setNoGravity(false);
            ravenEntity.setAnimMode(RavenAnimMode.NO_AIR);

            final double IDLE_SETTLE_NUDGE_DOWN = -0.10D;
            final double IDLE_SETTLE_MIN_FALL = -0.28D;
            final double IDLE_SETTLE_MAX_UP = 0.04D;

            Vec3 vel = ravenEntity.getDeltaMovement();
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

            ravenEntity.setDeltaMovement(0.0D, vy, 0.0D);

            boolean canSnapNow;
            try {
                boolean notFlying = !ravenEntity.isNoGravity();
                boolean lowVertical = Math.abs(ravenEntity.getDeltaMovement().y) <= 0.10D;
                boolean touchingSomething = ravenEntity.onGround() || ravenEntity.verticalCollision;
                canSnapNow = notFlying && (lowVertical || touchingSomething);
            } catch (Throwable t) {
                canSnapNow = false;
            }

            if (canSnapNow && this.idlePerchCorner != null && isValidPerchCornerAtTopY(this.idlePerchCorner, ravenEntity)) {
                Vec3 centerTop = perchCenterTop(this.idlePerchCorner, ravenEntity);

                double snapX = centerTop.x;
                double snapZ = centerTop.z;
                double keepY = ravenEntity.getY();

                ravenEntity.setPos(snapX, keepY, snapZ);
                ravenEntity.hurtMarked = true;

                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug(
                            "[Landing] enterIdleFromLanding: snapped to perch center. reason={} idlePerchCorner={} newPos={} vel={}",
                            reason,
                            this.idlePerchCorner,
                            ravenEntity.position(),
                            ravenEntity.getDeltaMovement()
                    );
                }
            } else {
                if (ravenEntity.tickCount % 80 == 0) {
                    LOG.debug(
                            "[Landing] enterIdleFromLanding: snap skipped (canSnapNow={}). reason={} idlePerchCorner={} pos={} vel={} noGravity={} onGround={} vColl={}",
                            canSnapNow,
                            reason,
                            this.idlePerchCorner,
                            ravenEntity.position(),
                            ravenEntity.getDeltaMovement(),
                            ravenEntity.isNoGravity(),
                            ravenEntity.onGround(),
                            ravenEntity.verticalCollision
                    );
                }
            }

            if (ravenEntity.tickCount % 20 == 0) {
                Object commitTicks = getFieldValueSafe(ravenEntity, "idleCommitTicks");
                LOG.debug(
                        "[Landing] ENTER IDLE: reason={} pos={} idlePerchCorner={} idleTicksRemaining={} commitTicks={}",
                        reason,
                        ravenEntity.position(),
                        this.idlePerchCorner,
                        getFieldValueSafe(ravenEntity, "idleTicksRemaining"),
                        commitTicks
                );
            }

        } catch (Throwable t) {
            LOG.error("[Landing] enterIdleFromLanding failed (reason={})", reason, t);
        }
    }

    public boolean isStillValidLandingLeaf(BlockPos leaf, RavenEntity ravenEntity) {
        try {
            if (leaf == null) return false;
            return isValidPerchCornerAtTopY(leaf, ravenEntity);
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 200 == 0) {
                LOG.warn("[Landing] isStillValidLandingLeaf failed: {}", t.toString());
            }
            return false;
        }
    }

    public Vec3 overheadTargetForLeaf(BlockPos leaf, RavenEntity ravenEntity) {
        Vec3 centerTop = perchCenterTop(leaf, ravenEntity);
        return new Vec3(centerTop.x, leaf.getY() + OVERHEAD_Y_OFFSET_FROM_LEAF, centerTop.z);
    }

    private Vec3 leafCenterTop(BlockPos leaf, RavenEntity ravenEntity) {
        return perchCenterTop(leaf, ravenEntity);
    }

    @Nullable
    public BlockPos pickLandingLeafBlock(RandomSource rnd, RavenEntity ravenEntity) {
        // ---------------------------------------------------------------------
        // HARD GATE: if player avoidance is blocking landing (override or just
        // a nearby player), we do NOT even begin a landing search.
        // This guarantees that avoidance flight can never transition into
        // FLY_TO_OVERHEAD or any landing phase until the avoidance condition
        // has fully cleared.
        // ---------------------------------------------------------------------
        try {
            if (PlayerAvoidance.shouldBlockLanding(ravenEntity)) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug(
                            "[Landing] pickLandingLeafBlock: aborted because player avoidance is blocking landing. pos={} aiState={}",
                            ravenEntity.position(),
                            ravenEntity.getAIState()
                    );
                }
                return null;
            }
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] pickLandingLeafBlock: avoidance gate failed safely: {}", t.toString());
            }
            // If the gate fails, we fall through and behave as before.
        }

        try {
            BlockPos homePos = null;
            try {
                Object hp = getFieldValueSafe(ravenEntity, "homePos");
                if (hp instanceof BlockPos) homePos = (BlockPos) hp;
            } catch (Throwable ignored) {
            }

            if (homePos == null) {
                homePos = ravenEntity.blockPosition();
            }

            double hx = homePos.getX() + 0.5D;
            double hz = homePos.getZ() + 0.5D;

            double angle = rnd.nextDouble() * (Math.PI * 2.0D);

            int HOME_RADIUS_BLOCKS = 50;
            try {
                Object hr = getFieldValueSafe(ravenEntity, "HOME_RADIUS_BLOCKS");
                if (hr instanceof Integer) HOME_RADIUS_BLOCKS = (Integer) hr;
            } catch (Throwable ignored) {
            }

            double radius = 4.0D + rnd.nextDouble() * (HOME_RADIUS_BLOCKS - 4.0D);

            int cx = Mth.floor(hx + Math.cos(angle) * radius);
            int cz = Mth.floor(hz + Math.sin(angle) * radius);

            int baseY = homePos.getY();
            int clampedY = baseY; // we skip clampYToHomeBounds here for safety

            BlockPos center = new BlockPos(cx, clampedY, cz);
            return pickLandingLeafBlockNear(center, rnd, ravenEntity);
        } catch (Throwable t) {
            LOG.error("[Landing] pickLandingLeafBlock failed", t);
            return null;
        }
    }

    @Nullable
    private BlockPos pickLandingLeafBlockNear(BlockPos center, RandomSource rnd, RavenEntity ravenEntity) {
        try {
            for (int i = 0; i < LAND_SEARCH_ATTEMPTS; i++) {
                int dx = rnd.nextInt(LAND_SEARCH_RADIUS * 2 + 1) - LAND_SEARCH_RADIUS;
                int dz = rnd.nextInt(LAND_SEARCH_RADIUS * 2 + 1) - LAND_SEARCH_RADIUS;

                int x = center.getX() + dx;
                int z = center.getZ() + dz;

                int topY;
                try {
                    topY = ravenEntity.level().getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                } catch (Throwable t) {
                    continue;
                }

                int scanMinY = Math.max(ravenEntity.level().getMinBuildHeight(), topY - LAND_SCAN_DOWN);
                for (int y = topY; y >= scanMinY; y--) {
                    BlockPos probe = new BlockPos(x, y, z);
                    BlockState state = ravenEntity.level().getBlockState(probe);
                    if (state == null || !state.is(BlockTags.LEAVES)) {
                        continue;
                    }

                    BlockPos perchCorner = findValidPerchCornerNearXZ(probe.getX(), probe.getZ(), ravenEntity);
                    if (perchCorner == null) {
                        continue;
                    }

                    Vec3 perchCenter = perchCenterTop(perchCorner, ravenEntity);

                    boolean out = false;
                    try {
                        Method m = null;
                        Class<?> c = ravenEntity.getClass();
                        while (c != null && m == null) {
                            try {
                                m = c.getDeclaredMethod("isOutOfHomeBounds", Vec3.class);
                            } catch (NoSuchMethodException ignored) {
                                c = c.getSuperclass();
                            }
                        }
                        if (m != null) {
                            m.setAccessible(true);
                            Object res = m.invoke(ravenEntity, perchCenter);
                            if (res instanceof Boolean) out = (Boolean) res;
                        }
                    } catch (Throwable ignored) {
                        out = false;
                    }
                    if (out) {
                        continue;
                    }

                    if (!isValidPerchCornerAtTopY(perchCorner, ravenEntity)) {
                        continue;
                    }

                    return perchCorner;
                }
            }
            return null;
        } catch (Throwable t) {
            LOG.error("[Landing] pickLandingLeafBlockNear failed", t);
            return null;
        }
    }

    private boolean isThickCanopyLeaf(BlockPos leaf, RavenEntity ravenEntity) {
        try {
            int leavesNeighbors = 0;

            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (ox == 0 && oz == 0) {
                        continue;
                    }
                    BlockPos p = leaf.offset(ox, 0, oz);
                    BlockState st = ravenEntity.level().getBlockState(p);
                    if (st != null && st.is(BlockTags.LEAVES)) {
                        leavesNeighbors++;
                    }
                }
            }

            BlockState below = ravenEntity.level().getBlockState(leaf.below());
            boolean hasSupport = below != null && below.is(BlockTags.LEAVES);

            return leavesNeighbors >= CANOPY_NEIGHBOR_LEAVES_REQUIRED && hasSupport;
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 200 == 0) {
                LOG.warn("[Landing] isThickCanopyLeaf failed: {}", t.toString());
            }
            return false;
        }
    }

    private boolean hasAirColumn(BlockPos start, int count, RavenEntity ravenEntity) {
        try {
            BlockPos p = start;
            for (int i = 0; i < count; i++) {
                if (!ravenEntity.level().isEmptyBlock(p)) {
                    return false;
                }
                p = p.above();
            }
            return true;
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 200 == 0) {
                LOG.warn("[Landing] hasAirColumn failed: {}", t.toString());
            }
            return false;
        }
    }

    /**
     * Returns the BlockPos of the NW corner of a valid 2x2 perch footprint at a specific topY
     * (corner.y = topY). The 2x2 may be "stepped": each of the 4 blocks must be LEAVES at either
     * topY or topY-1.
     */
    @Nullable
    public BlockPos findValidPerchCornerNearXZ(int x, int z, RavenEntity ravenEntity) {
        try {
            int[][] corners = new int[][]{
                    {x, z},
                    {x - 1, z},
                    {x, z - 1},
                    {x - 1, z - 1}
            };

            for (int[] c : corners) {
                int cx = c[0];
                int cz = c[1];

                Integer topY = computePerchTopY(cx, cz, ravenEntity);
                if (topY == null) continue;

                BlockPos cornerTop = new BlockPos(cx, topY, cz);

                if (isValidPerchCornerAtTopY(cornerTop, ravenEntity)) {
                    return cornerTop;
                }
            }

            return null;
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] findValidPerchCornerNearXZ failed safely: {}", t.toString());
            }
            return null;
        }
    }

    @Nullable
    private Integer computePerchTopY(int cx, int cz, RavenEntity ravenEntity) {
        try {
            int hintY;
            try {
                hintY = ravenEntity.level().getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
            } catch (Throwable t) {
                hintY = ravenEntity.blockPosition().getY();
            }

            int maxY = Integer.MIN_VALUE;

            int scanTop = Mth.clamp(hintY + 2, ravenEntity.level().getMinBuildHeight(), ravenEntity.level().getMaxBuildHeight() - 1);
            int scanBottom = Mth.clamp(hintY - 8, ravenEntity.level().getMinBuildHeight(), ravenEntity.level().getMaxBuildHeight() - 1);

            for (int y = scanTop; y >= scanBottom; y--) {
                if (isLeavesAt(cx, y, cz, ravenEntity)
                        || isLeavesAt(cx + 1, y, cz, ravenEntity)
                        || isLeavesAt(cx, y, cz + 1, ravenEntity)
                        || isLeavesAt(cx + 1, y, cz + 1, ravenEntity)) {
                    maxY = y;
                    break;
                }
            }

            if (maxY == Integer.MIN_VALUE) {
                return null;
            }
            return maxY;
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] computePerchTopY failed safely: {}", t.toString());
            }
            return null;
        }
    }

    private boolean isLeavesAt(int x, int y, int z, RavenEntity ravenEntity) {
        try {
            BlockState st = ravenEntity.level().getBlockState(new BlockPos(x, y, z));
            return st != null && st.is(BlockTags.LEAVES);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isValidPerchCornerAtTopY(BlockPos cornerTop, RavenEntity ravenEntity) {
        try {
            return validatePerchCornerAtTopY(cornerTop, ravenEntity).ok;
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] isValidPerchCornerAtTopY wrapper failed safely: {}", t.toString());
            }
            return false;
        }
    }

    public Vec3 perchCenterTop(BlockPos cornerTop, RavenEntity ravenEntity) {
        try {
            if (cornerTop == null) return ravenEntity.position();
            double x = cornerTop.getX() + 1.0D;
            double z = cornerTop.getZ() + 1.0D;
            double y = cornerTop.getY() + 1.0D;
            return new Vec3(x, y, z);
        } catch (Throwable t) {
            return ravenEntity.position();
        }
    }

    public boolean isOnValidPerchNow(RavenEntity ravenEntity) {
        // ---------------------------------------------------------------------
        // HARD GATE: do not allow strict perch detection to succeed while
        // player avoidance override is active. This avoids any ROAM_FLY -> IDLE
        // shortcuts or other snaps triggered mid-flee.
        // ---------------------------------------------------------------------
        try {
            if (ravenEntity.isPlayerAvoidanceOverrideActive()) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug(
                            "[Landing] isOnValidPerchNow: skipping because player avoidance override is active. pos={} aiState={}",
                            ravenEntity.position(),
                            ravenEntity.getAIState()
                    );
                }
                return false;
            }
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] isOnValidPerchNow: avoidance gate failed safely: {}", t.toString());
            }
            // On failure we fall back to the normal logic.
        }

        try {
            BlockPos feetBlock = BlockPos.containing(
                    ravenEntity.getX(),
                    ravenEntity.getBoundingBox().minY - 0.001D,
                    ravenEntity.getZ()
            );

            BlockPos corner = findValidPerchCornerNearXZ(feetBlock.getX(), feetBlock.getZ(), ravenEntity);
            if (corner == null) {
                return false;
            }

            Vec3 center = perchCenterTop(corner, ravenEntity);
            Vec3 pos = ravenEntity.position();

            double dx = pos.x - center.x;
            double dz = pos.z - center.z;
            double dXZ = Math.sqrt(dx * dx + dz * dz);

            return dXZ <= PERCH_CENTER_EPS;
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Landing] isOnValidPerchNow failed safely: {}", t.toString());
            }
            return false;
        }
    }
}
