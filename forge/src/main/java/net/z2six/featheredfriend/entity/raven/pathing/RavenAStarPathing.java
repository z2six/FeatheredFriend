// forge/src/main/java/net/z2six/featheredfriend/entity/raven/pathing/RavenAStarPathing.java
package net.z2six.featheredfriend.entity.raven.pathing;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import it.unimi.dsi.fastutil.objects.ObjectHeapPriorityQueue;
import java.util.Comparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * forge/src/main/java/net/z2six/featheredfriend/entity/raven/pathing/RavenAStarPathing.java
 *
 * Raven flight pathing (A*) with:
 *  - NO DIAGONALS (strict 6-neighbor expansion).
 *  - GRID STEP decoupled from CLEARANCE:
 *      * gridStep controls node spacing (recommended: 1 block).
 *      * clearanceSizeXZ / clearanceHeight control the "must be empty" volume (recommended: 2x2x2).
 *
 * Logging guarantees:
 *  - FORCE_LOG_EVERY_CALL=true => every findPath() prints an INFO "ENTER" line.
 *  - On start/goal blocked, we print the *first* blocking block in the clearance volume including its BlockState.
 *
 * Core fix (your current symptom):
 *  - Even-sized clearance (2x2) + anchorX = floor(x - clearance/2) will often drag the clearance volume into walls.
 *  - Example: x=0.32, clearance=2 => anchorX=floor(-0.68)=-1 => clearance covers x=-1..0 (hits wall at -1).
 *
 * Fix:
 *  - Use an anchor that behaves well for even sizes:
 *      anchorX = floor(x) - ((clearanceXZ - 1) / 2)
 *    For clearanceXZ=2 => anchorX = floor(x).
 *
 * Y anchoring:
 *  - Raven passes entity.position() which is FEET/base Y in Minecraft.
 *  - With clearanceHeight=2 we should not subtract 1; we anchor at floor(y) (optionally minus ((ch-1)/2)).
 */
public final class RavenAStarPathing {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Master debug switch for this class.
     * Flip this to false to silence RavenAStarPathing logs without touching call sites.
     */
    public static boolean DEBUG_LOGS = false;

    /**
     * If true, prints an INFO line for EVERY findPath call.
     * This is the "I want to be 100% sure A* is running" switch.
     */
    public static boolean FORCE_LOG_EVERY_CALL = false;

    /**
     * If FORCE_LOG_EVERY_CALL is false, we rate-limit the ENTER log to at most once per N ms.
     */
    public static long ENTER_LOG_MIN_INTERVAL_MS = 350L;

    /**
     * If true, includes a short caller hint (stack top) in the ENTER log.
     */
    public static boolean LOG_CALLER_HINT = false;

    /**
     * Treat Vec3.y as FEET/base Y (Minecraft entity.position()).
     * For your RavenEntity usage, this should stay true.
     */
    public static boolean USE_FEET_BASED_Y_ANCHOR = true;

    // Defaults tuned for "path that works" with low CPU:
    public static final int DEFAULT_GRID_STEP = 1;           // node spacing in blocks
    public static final int DEFAULT_CLEARANCE_XZ = 2;        // 2x2 footprint
    public static final int DEFAULT_CLEARANCE_HEIGHT = 2;    // 2 blocks tall

    /** Safety cap: maximum nodes expanded in a single solve. */
    public static final int DEFAULT_MAX_EXPANDED = 6500;

    /** Safety cap: maximum nodes we allow in open set (guards memory churn). */
    public static final int DEFAULT_MAX_OPEN = 16000;

    /** Default max output waypoints before smoothing (guard insane paths). */
    public static final int DEFAULT_MAX_RAW_WAYPOINTS = 768;

    /** Neighbor model: STRICT 6 neighbors (no diagonals). */
    private static final int[] NX = new int[]{  1, -1,  0,  0,  0,  0 };
    private static final int[] NY = new int[]{  0,  0,  1, -1,  0,  0 };
    private static final int[] NZ = new int[]{  0,  0,  0,  0,  1, -1 };

    // Logging helpers
    private static final AtomicLong CALL_SEQ = new AtomicLong(0L);
    private static volatile long lastEnterLogMs = 0L;

    private RavenAStarPathing() {}

    public static final class Config {
        // ---------------------------------------------------------------------
        // NEW: obstacle proximity penalty
        //
        // Nodes that are very close to solid blocks get a small extra cost.
        // This makes A* prefer paths that run through the "middle" of corridors
        // and under ceilings, instead of scraping along edges.
        //
        // IMPORTANT:
        //  - This does NOT block nodes; isNodePassable() still decides passability.
        //  - 1x1 corridors remain usable; they just have no cheaper alternative.
        // ---------------------------------------------------------------------

        /** Enable / disable obstacle proximity penalty. */
        public boolean useProximityPenalty = true;

        /** Horizontal radius (in blocks) around node center to sample for solids (1 => 3x3 XZ). */
        public int proximityRadiusXZ = 1;

        /** Vertical radius (in blocks) around node center to sample for solids (1 => y-1..y+1). */
        public int proximityRadiusY = 1;

        /**
         * Weight per "nearby solid sample".
         * Effective cost added per node ~= proximityWeight * solidSamples, clamped by proximityMaxPenalty.
         */
        public double proximityWeight = 0.08D;

        /** Hard clamp on maximum penalty per node so cost doesn't explode. */
        public double proximityMaxPenalty = 0.9D;

        // Existing fields (kept)
        public int cellSize = DEFAULT_CLEARANCE_XZ;
        public int gridStep = DEFAULT_GRID_STEP;
        public int clearanceHeight = DEFAULT_CLEARANCE_HEIGHT;

        public boolean allowLeaves = false;
        public boolean allowReplaceables = false;

        public int maxExpanded = DEFAULT_MAX_EXPANDED;
        public int maxOpen = DEFAULT_MAX_OPEN;
        public int maxRawWaypoints = DEFAULT_MAX_RAW_WAYPOINTS;

        public long tieBreakSeed = 0L;
        public boolean smoothPath = true;

        public int maxCellRadiusFromStart = 0;

        /** If true, verbose debug (still gated by DEBUG_LOGS). */
        public boolean debug = false;

        // ---------------------------------------------------------------------
        // NEW: "Safety buffer" passability (your requested behavior)
        //
        // If you set pathfinding to 1x1x1, you can still keep the raven away from
        // grazing collisions by requiring nearby blocks to be air too.
        //
        // This is NOT the same as 2x2 clearance anchoring; it keeps 1x1 nodes but
        // treats "tight corridors" as blocked.
        //
        // Example:
        //  - safetyBufferXZ = 1 means we require a 3x3 neighborhood in XZ to be clear
        //    (including diagonals).
        //  - safetyBufferYUp / safetyBufferYDown control vertical padding.
        //
        // This directly addresses your "clips the top edge slightly and gets stuck" issue.
        // ---------------------------------------------------------------------

        /** If true, we enforce a neighborhood emptiness buffer around each candidate node. */
        public boolean useSafetyBuffer = false;

        /** Radius in X/Z around the node (in blocks). 1 => checks -1..+1 (3x3) including diagonals. */
        public int safetyBufferXZ = 1;

        /** Blocks above the node to require empty (0..N). */
        public int safetyBufferYUp = 1;

        /** Blocks below the node to require empty (0..N). */
        public int safetyBufferYDown = 0;

        /**
         * If true, we allow the safety buffer to include leaves/replaceables based on allowLeaves/allowReplaceables.
         * If false, any non-air in the safety buffer fails (even leaves), while the node itself still respects allowLeaves.
         */
        public boolean safetyBufferRespectsAllowFlags = true;

        // ---------------------------------------------------------------------

        private int normalizedGridStep() {
            return Math.max(1, gridStep);
        }

        private int normalizedClearanceXZ() {
            return Math.max(1, cellSize);
        }

        private int normalizedClearanceHeight() {
            return Math.max(1, clearanceHeight);
        }

        private int normalizedSafetyBufferXZ() {
            return Math.max(0, safetyBufferXZ);
        }

        private int normalizedSafetyBufferYUp() {
            return Math.max(0, safetyBufferYUp);
        }

        private int normalizedSafetyBufferYDown() {
            return Math.max(0, safetyBufferYDown);
        }

        private int normalizedProximityRadiusXZ() {
            return Math.max(0, proximityRadiusXZ);
        }

        private int normalizedProximityRadiusY() {
            return Math.max(0, proximityRadiusY);
        }

        private double normalizedProximityWeight() {
            return Math.max(0.0D, proximityWeight);
        }

        private double normalizedProximityMaxPenalty() {
            return Math.max(0.0D, proximityMaxPenalty);
        }
    }

    public static final class CellBounds {
        public final int minCx;
        public final int minCy;
        public final int minCz;
        public final int maxCx;
        public final int maxCy;
        public final int maxCz;

        public CellBounds(int minCx, int minCy, int minCz, int maxCx, int maxCy, int maxCz) {
            this.minCx = minCx;
            this.minCy = minCy;
            this.minCz = minCz;
            this.maxCx = maxCx;
            this.maxCy = maxCy;
            this.maxCz = maxCz;
        }

        public boolean contains(int cx, int cy, int cz) {
            return cx >= minCx && cx <= maxCx
                    && cy >= minCy && cy <= maxCy
                    && cz >= minCz && cz <= maxCz;
        }

        @Override
        public String toString() {
            return "CellBounds{min=(" + minCx + "," + minCy + "," + minCz + "), max=(" + maxCx + "," + maxCy + "," + maxCz + ")}";
        }
    }

    public static List<Vec3> findPath(Level level, Vec3 startWorld, Vec3 goalWorld, CellBounds bounds, Config cfg) {
        final long callId = CALL_SEQ.incrementAndGet();

        if (level == null || startWorld == null || goalWorld == null || bounds == null) {
            if (DEBUG_LOGS) {
                LOG.warn("[RavenAStarPathing] findPath#{} NULL ARG(S): level={} start={} goal={} bounds={}",
                        callId, level, startWorld, goalWorld, bounds);
            }
            return Collections.emptyList();
        }

        if (cfg == null) {
            cfg = new Config();
        }

        final int gridStep = cfg.normalizedGridStep();
        final int clearanceXZ = cfg.normalizedClearanceXZ();
        final int clearanceH = cfg.normalizedClearanceHeight();

        maybeLogEnter(callId, level, startWorld, goalWorld, bounds, cfg, gridStep, clearanceXZ, clearanceH);

        // A* open entry: we order by f, and skip stale entries based on gScore.
        final class OpenEntry {
            final long key;
            final double g;
            final double f;

            OpenEntry(long key, double g, double f) {
                this.key = key;
                this.g = g;
                this.f = f;
            }
        }

        try {
            final long t0 = System.nanoTime();

            final NodeCoord start = NodeCoord.fromWorld(startWorld, gridStep, clearanceXZ, clearanceH);
            final NodeCoord goal = NodeCoord.fromWorld(goalWorld, gridStep, clearanceXZ, clearanceH);

            if (!bounds.contains(start.cx, start.cy, start.cz)) {
                if (DEBUG_LOGS) {
                    LOG.info("[RavenAStarPathing] findPath#{} NO PATH: start outside bounds. start={} bounds={} gridStep={} clearance={}x{}",
                            callId, start, bounds, gridStep, clearanceXZ, clearanceH);
                }
                return Collections.emptyList();
            }
            if (!bounds.contains(goal.cx, goal.cy, goal.cz)) {
                if (DEBUG_LOGS) {
                    LOG.info("[RavenAStarPathing] findPath#{} NO PATH: goal outside bounds. goal={} bounds={} gridStep={} clearance={}x{}",
                            callId, goal, bounds, gridStep, clearanceXZ, clearanceH);
                }
                return Collections.emptyList();
            }

            final Long2ByteOpenHashMap passableCache = new Long2ByteOpenHashMap();
            passableCache.defaultReturnValue((byte) -1);

            // NEW: cache for per-node safety penalty so we don’t rescan the same node 100x.
            final Long2DoubleOpenHashMap nodePenaltyCache = new Long2DoubleOpenHashMap();
            nodePenaltyCache.defaultReturnValue(Double.NaN);

            // Validate start/goal nodes.
            if (!isNodePassable(level, start.cx, start.cy, start.cz, cfg, passableCache)) {
                if (DEBUG_LOGS) {
                    BlockPos anchor = nodeAnchorBlock(start.cx, start.cy, start.cz, gridStep);
                    String why = describeFirstBlockingBlock(level, anchor, cfg, clearanceXZ, clearanceH);
                    LOG.info("[RavenAStarPathing] findPath#{} NO PATH: start blocked. start={} anchorBlock={} gridStep={} clearance={}x{} allowLeaves={} allowReplaceables={} firstBlocker={}",
                            callId, start, anchor, gridStep, clearanceXZ, clearanceH, cfg.allowLeaves, cfg.allowReplaceables, why);
                }
                return Collections.emptyList();
            }

            if (!isNodePassable(level, goal.cx, goal.cy, goal.cz, cfg, passableCache)) {
                if (DEBUG_LOGS) {
                    BlockPos anchor = nodeAnchorBlock(goal.cx, goal.cy, goal.cz, gridStep);
                    String why = describeFirstBlockingBlock(level, anchor, cfg, clearanceXZ, clearanceH);
                    LOG.info("[RavenAStarPathing] findPath#{} NO PATH: goal blocked. goal={} anchorBlock={} gridStep={} clearance={}x{} allowLeaves={} allowReplaceables={} firstBlocker={}",
                            callId, goal, anchor, gridStep, clearanceXZ, clearanceH, cfg.allowLeaves, cfg.allowReplaceables, why);
                }
                return Collections.emptyList();
            }

            final Long2DoubleOpenHashMap gScore = new Long2DoubleOpenHashMap();
            gScore.defaultReturnValue(Double.POSITIVE_INFINITY);

            final Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
            cameFrom.defaultReturnValue(0L);

            final LongOpenHashSet closed = new LongOpenHashSet(4096);

            final long startKey = pack(start.cx, start.cy, start.cz);
            final long goalKey = pack(goal.cx, goal.cy, goal.cz);

            gScore.put(startKey, 0.0D);

            // Proper priority queue ordered by f-score.
            final ObjectHeapPriorityQueue<OpenEntry> open =
                    new ObjectHeapPriorityQueue<>(Comparator.comparingDouble(a -> a.f));

            double h0 = heuristicManhattan(start, goal);
            open.enqueue(new OpenEntry(startKey, 0.0D, h0));

            final RandomSource tieRnd = (cfg.tieBreakSeed != 0L) ? RandomSource.create(cfg.tieBreakSeed) : null;

            int expanded = 0;
            int pushes = 1;
            int stalePops = 0;

            while (!open.isEmpty()) {
                if (expanded >= cfg.maxExpanded) {
                    if (DEBUG_LOGS) {
                        LOG.info("[RavenAStarPathing] findPath#{} NO PATH: maxExpanded reached ({}). expanded={} pushes={} stalePops={} start={} goal={} bounds={} gridStep={} clearance={}x{}",
                                callId, cfg.maxExpanded, expanded, pushes, stalePops, start, goal, bounds, gridStep, clearanceXZ, clearanceH);
                    }
                    break;
                }

                if (open.size() > cfg.maxOpen) {
                    if (DEBUG_LOGS) {
                        LOG.info("[RavenAStarPathing] findPath#{} NO PATH: maxOpen reached ({}). expanded={} pushes={} stalePops={} start={} goal={} bounds={} gridStep={} clearance={}x{}",
                                callId, cfg.maxOpen, expanded, pushes, stalePops, start, goal, bounds, gridStep, clearanceXZ, clearanceH);
                    }
                    break;
                }

                OpenEntry entry = open.dequeue();

                // Skip stale entries (typical A* implementation detail).
                double bestKnownG = gScore.get(entry.key);
                if (Double.isInfinite(bestKnownG) || Math.abs(bestKnownG - entry.g) > 1.0E-9D) {
                    stalePops++;
                    continue;
                }

                if (closed.contains(entry.key)) {
                    continue;
                }

                if (entry.key == goalKey) {
                    List<Vec3> raw = reconstructPath(cameFrom, entry.key, startKey, cfg.maxRawWaypoints, cfg);
                    List<Vec3> out = (cfg.smoothPath) ? smooth(level, raw, cfg, passableCache) : raw;

                    if (DEBUG_LOGS) {
                        double ms = (System.nanoTime() - t0) / 1_000_000.0;
                        LOG.info("[RavenAStarPathing] findPath#{} PATH OK: expanded={} pushes={} stalePops={} rawPts={} outPts={} timeMs={} start={} goal={} bounds={} gridStep={} clearance={}x{}",
                                callId, expanded, pushes, stalePops, raw.size(), out.size(), String.format("%.3f", ms),
                                start, goal, bounds, gridStep, clearanceXZ, clearanceH);
                    }

                    return out;
                }

                closed.add(entry.key);
                expanded++;

                final NodeCoord cur = unpack(entry.key);

                if (cfg.maxCellRadiusFromStart > 0) {
                    int md = Math.abs(cur.cx - start.cx) + Math.abs(cur.cy - start.cy) + Math.abs(cur.cz - start.cz);
                    if (md > cfg.maxCellRadiusFromStart) {
                        continue;
                    }
                }

                final double curG = entry.g;

                for (int i = 0; i < NX.length; i++) {
                    int ncx = cur.cx + NX[i];
                    int ncy = cur.cy + NY[i];
                    int ncz = cur.cz + NZ[i];

                    if (!bounds.contains(ncx, ncy, ncz)) {
                        continue;
                    }

                    if (cfg.maxCellRadiusFromStart > 0) {
                        int md = Math.abs(ncx - start.cx) + Math.abs(ncy - start.cy) + Math.abs(ncz - start.cz);
                        if (md > cfg.maxCellRadiusFromStart) {
                            continue;
                        }
                    }

                    long nKey = pack(ncx, ncy, ncz);
                    if (closed.contains(nKey)) {
                        continue;
                    }

                    if (!isNodePassable(level, ncx, ncy, ncz, cfg, passableCache)) {
                        continue;
                    }

                    double stepCost = 1.0D;
                    if (tieRnd != null) {
                        stepCost += (tieRnd.nextDouble() - 0.5D) * 0.002D;
                    }

                    // NEW: add a tiny penalty for risky nodes (near ceilings / walls).
                    stepCost += extraCostForNode(level, ncx, ncy, ncz, cfg, nodePenaltyCache);

                    double tentativeG = curG + stepCost;

                    double prevBest = gScore.get(nKey);
                    if (tentativeG + 1.0E-9D < prevBest) {
                        cameFrom.put(nKey, entry.key);
                        gScore.put(nKey, tentativeG);

                        NodeCoord nn = new NodeCoord(ncx, ncy, ncz);
                        double h = heuristicManhattan(nn, goal);
                        double f = tentativeG + h;

                        open.enqueue(new OpenEntry(nKey, tentativeG, f));
                        pushes++;
                    }
                }
            }

            if (DEBUG_LOGS) {
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                LOG.info("[RavenAStarPathing] findPath#{} PATH FAIL: expanded={} pushes={} stalePops={} timeMs={} start={} goal={} bounds={} gridStep={} clearance={}x{}",
                        callId, expanded, pushes, stalePops, String.format("%.3f", ms), start, goal, bounds, gridStep, clearanceXZ, clearanceH);
            }

            return Collections.emptyList();

        } catch (Throwable t) {
            if (DEBUG_LOGS) {
                LOG.error("[RavenAStarPathing] findPath#{} FAILED (gridStep={} clearance={}x{} cellSize={} allowLeaves={} allowReplaceables={})",
                        callId, gridStep, clearanceXZ, clearanceH, cfg.cellSize, cfg.allowLeaves, cfg.allowReplaceables, t);
            }
            return Collections.emptyList();
        }
    }

    public static CellBounds boundsFrom(BlockPos center, int radiusBlocks, int minY, int maxY, int gridStep) {
        if (center == null) {
            if (DEBUG_LOGS) {
                LOG.warn("[RavenAStarPathing] boundsFrom called with null center");
            }
            return new CellBounds(0, 0, 0, 0, 0, 0);
        }

        int gs = Math.max(1, gridStep);

        int minBx = center.getX() - radiusBlocks;
        int maxBx = center.getX() + radiusBlocks;
        int minBz = center.getZ() - radiusBlocks;
        int maxBz = center.getZ() + radiusBlocks;

        int minCx = floorDiv(minBx, gs);
        int maxCx = floorDiv(maxBx, gs);
        int minCz = floorDiv(minBz, gs);
        int maxCz = floorDiv(maxBz, gs);

        int minCy = floorDiv(minY, gs);
        int maxCy = floorDiv(maxY, gs);

        return new CellBounds(minCx, minCy, minCz, maxCx, maxCy, maxCz);
    }

    public static CellBounds boundsFrom(BlockPos center, int radiusBlocks, int minY, int maxY) {
        return boundsFrom(center, radiusBlocks, minY, maxY, DEFAULT_GRID_STEP);
    }

    // -------------------------------------------------------------------------
    // safety-penalty helpers
    // -------------------------------------------------------------------------

    /**
     * Extra movement cost for nodes that are "risky" for a flying raven:
     *  - very low headroom (close to a ceiling),
     *  - hugging walls / obstacles on the sides.
     *
     * This does NOT make nodes impassable; it just nudges A* to prefer
     * nodes with more open space whenever an alternative path exists.
     *
     * Effect:
     *  - In a 3x3 corridor, the raven will favor the more central /
     *    lower-altitude nodes instead of scraping the ceiling.
     *  - Under a 1-block-thick roof, as long as there is enough height,
     *    it prefers flying a bit lower instead of skimming the roof edge.
     *  - In a tight 1x1 corridor, there is no alternative, so A* still
     *    uses these nodes and the path remains valid.
     */
    private static double extraCostForNode(
            Level level,
            int cx,
            int cy,
            int cz,
            Config cfg,
            Long2DoubleOpenHashMap nodePenaltyCache
    ) {
        try {
            if (level == null) return 0.0D;

            // Reuse the same packed key as the A* maps.
            long key = pack(cx, cy, cz);

            // Cached?
            double cached = nodePenaltyCache.get(key);
            if (!Double.isNaN(cached)) {
                return cached;
            }

            // For proximity checks we work in world block coordinates around the node's anchor block.
            int gridStep = cfg.normalizedGridStep();
            int clearanceXZ = cfg.normalizedClearanceXZ();
            int clearanceH = cfg.normalizedClearanceHeight();

            BlockPos anchor = nodeAnchorBlock(cx, cy, cz, gridStep);

            // How far out we scan for nearby solid stuff.
            // Small radius keeps it cheap but enough to steer away from tight corridors.
            final int MAX_SCAN_RADIUS = 2;

            int bestDistSq = Integer.MAX_VALUE;

            // Scan a cube around the anchor and treat any non-air / non-fluid block as an "obstacle".
            for (int dx = -MAX_SCAN_RADIUS; dx <= MAX_SCAN_RADIUS; dx++) {
                for (int dy = -MAX_SCAN_RADIUS; dy <= MAX_SCAN_RADIUS; dy++) {
                    for (int dz = -MAX_SCAN_RADIUS; dz <= MAX_SCAN_RADIUS; dz++) {
                        BlockPos bp = anchor.offset(dx, dy, dz);

                        // Only pay attention to blocks within the node's vertical clearance band.
                        if (dy < 0 || dy >= clearanceH) {
                            continue;
                        }

                        // Basic obstruction test: anything that is not empty air & not empty fluid counts.
                        if (level.isEmptyBlock(bp) && level.getFluidState(bp).isEmpty()) {
                            continue;
                        }

                        int dSq = dx * dx + dy * dy + dz * dz;
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq;
                        }
                    }
                }
            }

            // If we found no nearby obstacles at all, no extra cost.
            if (bestDistSq == Integer.MAX_VALUE) {
                nodePenaltyCache.put(key, 0.0D);
                return 0.0D;
            }

            double bestDist = Math.sqrt(bestDistSq);

            // Within this radius we start to "dislike" nodes.
            final double SAFE_RADIUS = 2.5D;

            double penalty;
            if (bestDist >= SAFE_RADIUS) {
                penalty = 0.0D;
            } else {
                // Linear ramp: closer to solids => stronger penalty.
                double t = (SAFE_RADIUS - bestDist) / SAFE_RADIUS; // 0..1
                // Scale into a modest cost so A* prefers safer cells, but still
                // can go near walls if that's the *only* way.
                penalty = 0.25D + 0.55D * t; // ~0.25..0.80 extra cost
            }

            // Clamp just in case.
            if (penalty < 0.0D) penalty = 0.0D;
            if (penalty > 0.80D) penalty = 0.80D;

            nodePenaltyCache.put(key, penalty);
            return penalty;

        } catch (Throwable t) {
            // Fail safe: if anything goes wrong, we don't add extra cost.
            return 0.0D;
        }
    }

    /**
     * "Solid" from the perspective of safety:
     * - Anything non-air is treated as solid, UNLESS:
     *   - allowLeaves is true and it's leaves, or
     *   - allowReplaceables is true and it's replaceable.
     *
     * This matches the semantics of computeNodePassable, so we don't
     * call leaves/flowers a "ceiling" unless the config says so.
     */
    private static boolean isSolidForSafety(Level level, BlockPos pos, Config cfg) {
        try {
            if (level.isEmptyBlock(pos)) {
                return false;
            }

            BlockState st = level.getBlockState(pos);
            if (st == null) {
                return true;
            }

            if (cfg.allowLeaves && st.is(BlockTags.LEAVES)) {
                return false;
            }
            if (cfg.allowReplaceables && st.canBeReplaced()) {
                return false;
            }

            // Here "solid" includes anything that would reasonably be a collision hazard.
            return !st.getCollisionShape(level, pos).isEmpty();
        } catch (Throwable t) {
            // On error, assume solid to be conservative.
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Logging helpers
    // -------------------------------------------------------------------------

    private static void maybeLogEnter(
            long callId,
            Level level,
            Vec3 startWorld,
            Vec3 goalWorld,
            CellBounds bounds,
            Config cfg,
            int gridStep,
            int clearanceXZ,
            int clearanceH
    ) {
        if (!DEBUG_LOGS) {
            return;
        }

        try {
            if (FORCE_LOG_EVERY_CALL) {
                LOG.info("[RavenAStarPathing] findPath#{} ENTER: dim={} start={} goal={} bounds={} gridStep={} clearance={}x{} allowLeaves={} allowReplaceables={} smooth={} maxExpanded={} maxOpen={} yAnchorFeet={} caller={}",
                        callId,
                        safeDim(level),
                        fmtVec(startWorld),
                        fmtVec(goalWorld),
                        bounds,
                        gridStep,
                        clearanceXZ,
                        clearanceH,
                        cfg.allowLeaves,
                        cfg.allowReplaceables,
                        cfg.smoothPath,
                        cfg.maxExpanded,
                        cfg.maxOpen,
                        USE_FEET_BASED_Y_ANCHOR,
                        (LOG_CALLER_HINT ? safeCallerHint() : "<off>")
                );
                return;
            }

            long nowMs = System.currentTimeMillis();
            long last = lastEnterLogMs;
            if ((nowMs - last) >= Math.max(0L, ENTER_LOG_MIN_INTERVAL_MS)) {
                lastEnterLogMs = nowMs;
                LOG.info("[RavenAStarPathing] findPath#{} ENTER(throttled): dim={} start={} goal={} bounds={} gridStep={} clearance={}x{} yAnchorFeet={} caller={}",
                        callId,
                        safeDim(level),
                        fmtVec(startWorld),
                        fmtVec(goalWorld),
                        bounds,
                        gridStep,
                        clearanceXZ,
                        clearanceH,
                        USE_FEET_BASED_Y_ANCHOR,
                        (LOG_CALLER_HINT ? safeCallerHint() : "<off>")
                );
            }
        } catch (Throwable ignored) {
        }
    }

    private static String safeDim(Level level) {
        try {
            if (level != null && level.dimension() != null) {
                return String.valueOf(level.dimension().location());
            }
        } catch (Throwable ignored) {}
        return "<unknown-dim>";
    }

    private static String fmtVec(Vec3 v) {
        if (v == null) return "<null>";
        try {
            return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
        } catch (Throwable t) {
            return String.valueOf(v);
        }
    }

    private static String safeCallerHint() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 4; i < st.length; i++) {
                StackTraceElement e = st[i];
                if (e == null) continue;
                String cn = e.getClassName();
                if (cn == null) continue;
                if (cn.equals(RavenAStarPathing.class.getName())) continue;
                if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")) continue;
                return e.getClassName() + "#" + e.getMethodName() + ":" + e.getLineNumber();
            }
        } catch (Throwable ignored) {}
        return "<unknown-caller>";
    }

    private static String describeFirstBlockingBlock(Level level, BlockPos anchor, Config cfg, int clearanceXZ, int clearanceH) {
        if (level == null || anchor == null) {
            return "<null>";
        }
        try {
            for (int ox = 0; ox < clearanceXZ; ox++) {
                for (int oy = 0; oy < clearanceH; oy++) {
                    for (int oz = 0; oz < clearanceXZ; oz++) {
                        BlockPos p = anchor.offset(ox, oy, oz);

                        boolean empty;
                        try {
                            empty = level.isEmptyBlock(p);
                        } catch (Throwable t) {
                            return "pos=" + p + " empty=<err:" + t.getClass().getSimpleName() + ">";
                        }

                        if (empty) {
                            continue;
                        }

                        BlockState st;
                        try {
                            st = level.getBlockState(p);
                        } catch (Throwable t) {
                            return "pos=" + p + " empty=false state=<err:" + t.getClass().getSimpleName() + ">";
                        }

                        if (st == null) {
                            return "pos=" + p + " empty=false state=<null>";
                        }

                        // If it's non-empty but allowed by config, keep scanning.
                        if (cfg.allowLeaves && st.is(BlockTags.LEAVES)) {
                            continue;
                        }
                        if (cfg.allowReplaceables && st.canBeReplaced()) {
                            continue;
                        }

                        return "pos=" + p + " state=" + st + " isLeaves=" + st.is(BlockTags.LEAVES) + " canBeReplaced=" + st.canBeReplaced();
                    }
                }
            }
            return "<none-found-but-nonpassable?>";
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ":" + t.getMessage() + ">";
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static long popBestCandidate(
            LongPriorityQueue open,
            LongOpenHashSet inOpen,
            Long2DoubleOpenHashMap gScore,
            NodeCoord goal,
            Config cfg,
            RandomSource tieRnd
    ) {
        final int K = 12;

        if (open.isEmpty()) {
            return Long.MIN_VALUE;
        }

        long bestKey = Long.MIN_VALUE;
        double bestF = Double.POSITIVE_INFINITY;

        LongArrayList pulled = new LongArrayList(K);

        for (int i = 0; i < K && !open.isEmpty(); i++) {
            long k = open.dequeueLong();
            if (!inOpen.contains(k)) {
                continue;
            }

            double g = gScore.get(k);
            if (Double.isInfinite(g)) {
                inOpen.remove(k);
                continue;
            }

            NodeCoord c = unpack(k);
            double h = heuristicManhattan(c, goal);
            double f = g + h;

            if (cfg.tieBreakSeed != 0L && tieRnd != null) {
                f += (tieRnd.nextDouble() * 1.0E-6D);
            }

            pulled.add(k);

            if (f < bestF) {
                bestF = f;
                bestKey = k;
            }
        }

        if (bestKey == Long.MIN_VALUE) {
            for (int i = 0; i < pulled.size(); i++) {
                open.enqueue(pulled.getLong(i));
            }
            return Long.MIN_VALUE;
        }

        for (int i = 0; i < pulled.size(); i++) {
            long k = pulled.getLong(i);
            if (k == bestKey) {
                continue;
            }
            open.enqueue(k);
        }

        inOpen.remove(bestKey);
        return bestKey;
    }

    private static double heuristicManhattan(NodeCoord a, NodeCoord b) {
        return (double) (Math.abs(a.cx - b.cx) + Math.abs(a.cy - b.cy) + Math.abs(a.cz - b.cz));
    }

    private static List<Vec3> reconstructPath(Long2LongOpenHashMap cameFrom, long currentKey, long startKey, int maxPts, Config cfg) {
        try {
            LongArrayList keys = new LongArrayList();

            long cur = currentKey;
            keys.add(cur);

            int guard = 0;
            while (cur != startKey) {
                long prev = cameFrom.get(cur);
                if (prev == 0L) {
                    if (DEBUG_LOGS) {
                        LOG.warn("[RavenAStarPathing] reconstructPath broke chain early (missing cameFrom). cur={} startKey={}", cur, startKey);
                    }
                    break;
                }
                cur = prev;
                keys.add(cur);

                guard++;
                if (guard > maxPts) {
                    if (DEBUG_LOGS) {
                        LOG.warn("[RavenAStarPathing] reconstructPath exceeded maxPts={}, aborting chain build (startKey={})", maxPts, startKey);
                    }
                    break;
                }
            }

            final long[] arr = keys.elements();
            for (int i = 0, j = keys.size() - 1; i < j; i++, j--) {
                long tmp = arr[i];
                arr[i] = arr[j];
                arr[j] = tmp;
            }

            final int gs = Math.max(1, cfg.gridStep);
            final int clearanceXZ = Math.max(1, cfg.cellSize);
            final int clearanceH = Math.max(1, cfg.clearanceHeight);

            List<Vec3> out = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                NodeCoord c = unpack(keys.getLong(i));
                out.add(nodeCenterWorld(c.cx, c.cy, c.cz, gs, clearanceXZ, clearanceH));
            }

            return out;
        } catch (Throwable t) {
            if (DEBUG_LOGS) {
                LOG.error("[RavenAStarPathing] reconstructPath failed", t);
            }
            return Collections.emptyList();
        }
    }

    private static List<Vec3> smooth(Level level, List<Vec3> raw, Config cfg, Long2ByteOpenHashMap passableCache) {
        if (raw == null || raw.size() <= 2) {
            return raw == null ? Collections.emptyList() : raw;
        }

        final int gs = Math.max(1, cfg.gridStep);

        try {
            ArrayList<Vec3> out = new ArrayList<>();
            int i = 0;
            out.add(raw.get(0));

            while (i < raw.size() - 1) {
                int best = i + 1;

                for (int j = raw.size() - 1; j > i + 1; j--) {
                    if (hasAxisAlignedNodeLineOfSight(level, raw.get(i), raw.get(j), cfg, passableCache, gs)) {
                        best = j;
                        break;
                    }
                }

                out.add(raw.get(best));
                i = best;
            }

            return out;
        } catch (Throwable t) {
            if (DEBUG_LOGS) {
                LOG.error("[RavenAStarPathing] smooth failed", t);
            }
            return raw;
        }
    }

    private static boolean hasAxisAlignedNodeLineOfSight(Level level, Vec3 a, Vec3 b, Config cfg, Long2ByteOpenHashMap passableCache, int gridStep) {
        try {
            final int clearanceXZ = Math.max(1, cfg.cellSize);
            final int clearanceH = Math.max(1, cfg.clearanceHeight);

            NodeCoord na = NodeCoord.fromWorld(a, gridStep, clearanceXZ, clearanceH);
            NodeCoord nb = NodeCoord.fromWorld(b, gridStep, clearanceXZ, clearanceH);

            int dx = nb.cx - na.cx;
            int dy = nb.cy - na.cy;
            int dz = nb.cz - na.cz;

            int axesChanged = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
            if (axesChanged == 0) return true;
            if (axesChanged > 1) return false;

            int stepX = Integer.compare(dx, 0);
            int stepY = Integer.compare(dy, 0);
            int stepZ = Integer.compare(dz, 0);

            int steps = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);

            int cx = na.cx;
            int cy = na.cy;
            int cz = na.cz;

            for (int s = 0; s <= steps; s++) {
                if (!isNodePassable(level, cx, cy, cz, cfg, passableCache)) {
                    return false;
                }
                cx += stepX;
                cy += stepY;
                cz += stepZ;
            }

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isNodePassable(Level level, int cx, int cy, int cz, Config cfg, Long2ByteOpenHashMap cache) {
        long key = pack(cx, cy, cz);
        byte cached = cache.get(key);
        if (cached != (byte) -1) {
            return cached == (byte) 1;
        }

        boolean ok;
        try {
            ok = computeNodePassable(level, cx, cy, cz, cfg);
        } catch (Throwable t) {
            ok = false;
        }

        cache.put(key, ok ? (byte) 1 : (byte) 0);
        return ok;
    }

    /**
     * Compute a small cost penalty for nodes that are very close to solid blocks.
     *
     * - Node must already be passable (computeNodePassable succeeded).
     * - We just scan a small neighborhood around the node's "center-ish" point
     *   and count how many samples are "solid".
     * - More nearby solids => slightly higher cost.
     *
     * This steers paths away from scraping ceilings/walls when there is a slightly
     * more open alternative, but still allows genuinely tight corridors.
     */
    private static double computeProximityPenalty(Level level, int cx, int cy, int cz, Config cfg) {
        if (level == null) return 0.0D;
        if (!cfg.useProximityPenalty) return 0.0D;

        try {
            final int rXZ = cfg.normalizedProximityRadiusXZ();
            final int rY  = cfg.normalizedProximityRadiusY();
            if (rXZ <= 0 && rY <= 0) {
                return 0.0D;
            }

            final int gs = Math.max(1, cfg.gridStep);
            final int clearanceXZ = Math.max(1, cfg.cellSize);
            final int clearanceH  = Math.max(1, cfg.clearanceHeight);

            final int baseX = cx * gs;
            final int baseY = cy * gs;
            final int baseZ = cz * gs;

            // Approximate "center" of the footprint.
            final int centerX = baseX + (clearanceXZ / 2);
            final int centerY = baseY + (clearanceH / 2);
            final int centerZ = baseZ + (clearanceXZ / 2);

            int solidSamples = 0;
            int totalSamples = 0;

            for (int dx = -rXZ; dx <= rXZ; dx++) {
                for (int dz = -rXZ; dz <= rXZ; dz++) {
                    for (int dy = -rY; dy <= rY; dy++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            // Skip the exact center; passability check already handled that footprint.
                            continue;
                        }

                        BlockPos p = new BlockPos(centerX + dx, centerY + dy, centerZ + dz);
                        totalSamples++;

                        boolean empty;
                        try {
                            empty = level.isEmptyBlock(p);
                        } catch (Throwable t) {
                            // If we cannot query this sample reliably, treat as "solid-ish"
                            // so the path avoids sketchy regions.
                            solidSamples++;
                            continue;
                        }

                        if (empty) {
                            continue;
                        }

                        BlockState st;
                        try {
                            st = level.getBlockState(p);
                        } catch (Throwable t) {
                            solidSamples++;
                            continue;
                        }

                        if (st == null) {
                            solidSamples++;
                            continue;
                        }

                        // Anything with a collision shape counts as "solid"; we don't care about leaves here,
                        // because passability already handled allowLeaves/allowReplaceables.
                        if (!st.getCollisionShape(level, p).isEmpty()) {
                            solidSamples++;
                        }
                    }
                }
            }

            if (totalSamples <= 0 || solidSamples <= 0) {
                return 0.0D;
            }

            // Fraction of nearby samples that are solid.
            double density = (double) solidSamples / (double) totalSamples;

            double weight = cfg.normalizedProximityWeight();
            double maxPen = cfg.normalizedProximityMaxPenalty();

            // Simple linear mapping: more solids -> higher penalty, clamped.
            double rawPenalty = density * weight * totalSamples;
            if (rawPenalty > maxPen) {
                rawPenalty = maxPen;
            }

            return rawPenalty;

        } catch (Throwable t) {
            // Never break A* from here; just fall back to zero penalty.
            if (DEBUG_LOGS) {
                // Super rare, so log occasionally.
                // No tickCount here, so no modulo; A* calls are relatively sparse.
                LOG.warn("[RavenAStarPathing] computeProximityPenalty failed safely: {}", t.toString());
            }
            return 0.0D;
        }
    }

    private static boolean computeNodePassable(Level level, int cx, int cy, int cz, Config cfg) {
        final int gs = Math.max(1, cfg.gridStep);
        final int clearanceXZ = Math.max(1, cfg.cellSize);
        final int clearanceH = Math.max(1, cfg.clearanceHeight);

        final int baseX = cx * gs;
        final int baseY = cy * gs;
        final int baseZ = cz * gs;

        // ------------------------------------------------------------
        // 1) The "core occupancy" check (this is the A* node footprint)
        // ------------------------------------------------------------
        for (int ox = 0; ox < clearanceXZ; ox++) {
            for (int oy = 0; oy < clearanceH; oy++) {
                for (int oz = 0; oz < clearanceXZ; oz++) {
                    BlockPos p = new BlockPos(baseX + ox, baseY + oy, baseZ + oz);

                    boolean empty;
                    try {
                        empty = level.isEmptyBlock(p);
                    } catch (Throwable t) {
                        if (DEBUG_LOGS) {
                            LOG.warn("[RavenAStarPathing] computeNodePassable: isEmptyBlock failed at {} ({})", p, t.toString());
                        }
                        return false;
                    }

                    if (empty) {
                        continue;
                    }

                    BlockState st;
                    try {
                        st = level.getBlockState(p);
                    } catch (Throwable t) {
                        if (DEBUG_LOGS) {
                            LOG.warn("[RavenAStarPathing] computeNodePassable: getBlockState failed at {} ({})", p, t.toString());
                        }
                        return false;
                    }

                    if (st == null) {
                        return false;
                    }

                    if (cfg.allowLeaves && st.is(BlockTags.LEAVES)) {
                        continue;
                    }

                    if (cfg.allowReplaceables && st.canBeReplaced()) {
                        continue;
                    }

                    return false;
                }
            }
        }

        // ------------------------------------------------------------
        // 2) NEW: Safety buffer check (your requested behavior)
        //
        // If enabled, require that the neighborhood around the node is empty
        // to avoid grazing collisions (including diagonals).
        //
        // IMPORTANT:
        // - This should be used with clearanceXZ=1 and clearanceH=1 for the
        //   "1x1x1 pathfinding but safe" mode.
        // - But it also works with larger clearance if you want.
        // ------------------------------------------------------------
        if (cfg.useSafetyBuffer) {
            final int r = cfg.normalizedSafetyBufferXZ();
            final int yUp = cfg.normalizedSafetyBufferYUp();
            final int yDown = cfg.normalizedSafetyBufferYDown();

            // If r==0 and yUp==0 and yDown==0 then buffer does nothing.
            if (r > 0 || yUp > 0 || yDown > 0) {
                // Anchor for buffer should be the node footprint's "center-ish" location.
                // For 1x1x1 footprint, this is just baseX/baseY/baseZ.
                // For larger footprints, we bias to the middle of the footprint so the buffer is symmetric.
                final int centerX = baseX + (clearanceXZ / 2);
                final int centerY = baseY + (clearanceH / 2);
                final int centerZ = baseZ + (clearanceXZ / 2);

                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        // include diagonals by design
                        for (int dy = -yDown; dy <= yUp; dy++) {
                            BlockPos p = new BlockPos(centerX + dx, centerY + dy, centerZ + dz);

                            boolean empty;
                            try {
                                empty = level.isEmptyBlock(p);
                            } catch (Throwable t) {
                                if (DEBUG_LOGS) {
                                    LOG.warn("[RavenAStarPathing] safetyBuffer: isEmptyBlock failed at {} ({})", p, t.toString());
                                }
                                return false;
                            }

                            if (empty) {
                                continue;
                            }

                            BlockState st;
                            try {
                                st = level.getBlockState(p);
                            } catch (Throwable t) {
                                if (DEBUG_LOGS) {
                                    LOG.warn("[RavenAStarPathing] safetyBuffer: getBlockState failed at {} ({})", p, t.toString());
                                }
                                return false;
                            }

                            if (st == null) {
                                return false;
                            }

                            boolean allowed = false;
                            if (cfg.safetyBufferRespectsAllowFlags) {
                                if (cfg.allowLeaves && st.is(BlockTags.LEAVES)) {
                                    allowed = true;
                                } else if (cfg.allowReplaceables && st.canBeReplaced()) {
                                    allowed = true;
                                }
                            }

                            if (!allowed) {
                                return false;
                            }
                        }
                    }
                }
            }
        }

        return true;
    }

    private static Vec3 nodeCenterWorld(int cx, int cy, int cz, int gridStep, int clearanceXZ, int clearanceH) {
        int gs = Math.max(1, gridStep);
        int cl = Math.max(1, clearanceXZ);
        int ch = Math.max(1, clearanceH);

        double bx = (double) (cx * gs);
        double by = (double) (cy * gs);
        double bz = (double) (cz * gs);

        double x = bx + (cl / 2.0D);
        double y = by + (ch / 2.0D);
        double z = bz + (cl / 2.0D);

        return new Vec3(x, y, z);
    }

    private static BlockPos nodeAnchorBlock(int cx, int cy, int cz, int gridStep) {
        int gs = Math.max(1, gridStep);
        return new BlockPos(cx * gs, cy * gs, cz * gs);
    }

    // -------------------------------------------------------------------------
    // Node coordinates & mapping
    // -------------------------------------------------------------------------

    private static final class NodeCoord {
        final int cx;
        final int cy;
        final int cz;

        private NodeCoord(int cx, int cy, int cz) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
        }

        /**
         * Map a world point to the node anchor.
         *
         * FIXED FOR EVEN CLEARANCE:
         *  - anchorX/Z = floor(pos.x/z) - ((clearanceXZ - 1) / 2)
         *
         * For clearanceXZ=2 => subtract 0 => anchor is floor(pos).
         * This prevents the 2x2 clearance box from being shifted "one block left/down" into a nearby wall.
         *
         * Y:
         *  - feet-based by default => anchorY = floor(pos.y) - ((clearanceH - 1) / 2)
         *  - for clearanceH=2 => subtract 0
         */
        static NodeCoord fromWorld(Vec3 pos, int gridStep, int clearanceXZ, int clearanceH) {
            int gs = Math.max(1, gridStep);
            int cl = Math.max(1, clearanceXZ);
            int ch = Math.max(1, clearanceH);

            int halfSpanXZ = Math.max(0, (cl - 1) / 2);
            int halfSpanY = Math.max(0, (ch - 1) / 2);

            int fx = Mth.floor(pos.x + 1.0E-4D);
            int fz = Mth.floor(pos.z + 1.0E-4D);

            int anchorX = fx - halfSpanXZ;
            int anchorZ = fz - halfSpanXZ;

            int anchorY;
            if (USE_FEET_BASED_Y_ANCHOR) {
                int fy = Mth.floor(pos.y + 1.0E-4D);
                anchorY = fy - halfSpanY;
            } else {
                // center-ish fallback (rare for your usage)
                anchorY = Mth.floor(pos.y - (ch / 2.0D));
            }

            int cx = floorDiv(anchorX, gs);
            int cy = floorDiv(anchorY, gs);
            int cz = floorDiv(anchorZ, gs);

            return new NodeCoord(cx, cy, cz);
        }

        @Override
        public String toString() {
            return "NodeCoord{cx=" + cx + ", cy=" + cy + ", cz=" + cz + "}";
        }
    }

    // MainFile: forge/src/main/java/net/z2six/featheredfriend/entity/raven/pathing/RavenAStarPathing.java
    private static boolean isCellPassable(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos feetPos,
            int clearanceX,
            int clearanceZ,
            boolean allowLeaves,
            boolean allowReplaceables,
            boolean yAnchorFeet
    ) {
        final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

        // We check a clearance footprint around "feetPos". For a 1x1 flyer this can remain 1x1.
        // For a flyer: we only care that the volume is not colliding.
        // For a ground mob: we also require a "floor" when yAnchorFeet==true.

        for (int dx = 0; dx < clearanceX; dx++) {
            for (int dz = 0; dz < clearanceZ; dz++) {
                net.minecraft.core.BlockPos p = feetPos.offset(dx, 0, dz);
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);

                // Leaves / replaceables rule.
                if (!allowLeaves && st.is(net.minecraft.tags.BlockTags.LEAVES)) {
                    return false;
                }
                if (!allowReplaceables && st.canBeReplaced()) {
                    return false;
                }

                // Collision check: if it has any collision shape, it's not passable space.
                // (This is the correct rule for a flying mob navigating through air.)
                if (!st.getCollisionShape(level, p).isEmpty()) {
                    return false;
                }

                // If we are doing feet-anchored navigation (ground mode),
                // require a supporting block beneath each footprint cell.
                if (yAnchorFeet) {
                    net.minecraft.core.BlockPos below = p.below();
                    net.minecraft.world.level.block.state.BlockState belowSt = level.getBlockState(below);

                    // Supporting block must have collision (i.e., something solid-ish),
                    // and must not be something we consider "non-floor".
                    if (belowSt.getCollisionShape(level, below).isEmpty()) {
                        return false;
                    }
                }
            }
        }

        return true;
    }


    // -------------------------------------------------------------------------
    // Packing (node coords -> long key)
    // -------------------------------------------------------------------------

    private static int floorDiv(int a, int b) {
        int r = a / b;
        int m = a % b;
        if ((m != 0) && ((a ^ b) < 0)) {
            r--;
        }
        return r;
    }

    private static long pack(int cx, int cy, int cz) {
        final int BIAS_XZ = 1 << 20;
        final int BIAS_Y  = 1 << 21;

        int px = cx + BIAS_XZ;
        int py = cy + BIAS_Y;
        int pz = cz + BIAS_XZ;

        px &= 0x1FFFFF;
        py &= 0x3FFFFF;
        pz &= 0x1FFFFF;

        return ((long) px << 43) | ((long) py << 21) | (long) pz;
    }

    private static NodeCoord unpack(long key) {
        final int BIAS_XZ = 1 << 20;
        final int BIAS_Y  = 1 << 21;

        int px = (int) ((key >>> 43) & 0x1FFFFF);
        int py = (int) ((key >>> 21) & 0x3FFFFF);
        int pz = (int) (key & 0x1FFFFF);

        int cx = px - BIAS_XZ;
        int cy = py - BIAS_Y;
        int cz = pz - BIAS_XZ;

        return new NodeCoord(cx, cy, cz);
    }
}
