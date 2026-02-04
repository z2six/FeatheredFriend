// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/world/RavenSpawnEvents.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.config.FFServerConfig;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/RavenSpawnEvents.java
 *
 * Natural spawner for Ravens.
 *
 * NOTE (critical reality check):
 * - This file ONLY controls ravens spawned by THIS handler (manual addFreshEntity()).
 * - If you also added your raven to biome spawn lists (BiomeModifier / datapack add_spawns / BiomeModifications),
 *   then vanilla's mob spawner will spawn ravens normally on ground (no leaves requirement) and ignore this fileâ€™s cap.
 *
 * This implementation therefore:
 *  - Keeps the original "leaves-top spawn" behavior.
 *  - Adds strong fail-safes:
 *      * hard cap: max 2 WILD (untamed) ravens within LOCAL_RAVEN_RADIUS of each player
 *      * active culling of extras (tamed ravens are never culled)
 *      * optional global level cap for WILD ravens (defense-in-depth)
 *      * register-once guard (prevents accidental double listener registration)
 *      * hard validation that spawnPos is above leaves at time of spawn
 *
 * Logs are throttled.
 */
public final class RavenSpawnEvents {
    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // HARD TOGGLE
    // ---------------------------------------------------------------------

    /** Master enable switch for ALL raven spawning & culling by this handler. */
    private static final boolean ENABLE_SPAWNING = true;

    // ---------------------------------------------------------------------
    // REGISTRATION GUARD
    // ---------------------------------------------------------------------

    private static volatile boolean REGISTERED = false;

    // ---------------------------------------------------------------------
    // TUNING CONSTANTS
    // ---------------------------------------------------------------------

    /** How often to run spawn attempts per level. 20 ticks = 1 second. */
    private static final int CHECK_INTERVAL_TICKS = 200;

    /** How often to run cleanup (culling) checks. */
    private static final int CLEANUP_INTERVAL_TICKS = 100;

    /** Local population radius around each player for counting / culling. */
    private static final double LOCAL_RAVEN_RADIUS = 96.0D;

    /** Spawn chances per CHECK depending on local WILD population. */
    private static final double SPAWN_CHANCE_EMPTY_AREA = 0.02D;      // rarer than before
    private static final double SPAWN_CHANCE_WITH_ONE_RAVEN = 0.001D; // much rarer

    /** How far from player we sample random columns for leaves-top spawns. */
    private static final int SEARCH_RADIUS_BLOCKS = 64;

    /** How many random columns we try each check before giving up. */
    private static final int CANDIDATE_COLUMNS_PER_CHECK = 64;

    /** How far down from surface we scan to find leaves. */
    private static final int MAX_DOWNWARD_SCAN = 96;

    /** Require at least this many air blocks above the spawn position. */
    private static final int REQUIRED_AIR_ABOVE = 2;

    /** Raven entity id (registry name). */
    private static final ResourceLocation RAVEN_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "raven");

    /** Debug logging throttle (ticks). */
    private static final int DEBUG_LOG_INTERVAL_TICKS = 100;

    /** Spawn log throttle (ticks). */
    private static final int SPAWN_LOG_INTERVAL_TICKS = 200;

    /**
     * Sky-test safety:
     * If player is more than this many blocks above WORLD_SURFACE height at their X/Z, do not spawn.
     */
    private static final int MAX_PLAYER_HEIGHT_ABOVE_SURFACE = 128;

    /**
     * Optional global safety cap: total WILD ravens allowed in this level.
     * This prevents â€œrunaway spawn bugâ€ floods even if other logic is broken.
     *
     * Rule of thumb: (players * 2) + buffer.
     */
    /**
     * Additional allowance above the player-count cap.
     *
     * Design goal for FeatheredFriend: never have more wild ravens than online players.
     */
    private static final int GLOBAL_WILD_RAVEN_BUFFER = 0;

    private static int wildRavensPerPlayer() {
        try {
            return Math.max(0, FFServerConfig.getWildRavensPerPlayer());
        } catch (Throwable t) {
            return 1;
        }
    }

    // ---------------------------------------------------------------------
    // LIFECYCLE
    // ---------------------------------------------------------------------

    private RavenSpawnEvents() {
        // no instances
    }

    public static void register() {
        try {
            if (REGISTERED) {
                LOG.warn("[RavenSpawnEvents] register() called but listener is already registered. Skipping duplicate registration.");
                return;
            }
            REGISTERED = true;

            NeoForge.EVENT_BUS.addListener(RavenSpawnEvents::onLevelTickPost);
            LOG.debug("[RavenSpawnEvents] Registered LevelTickEvent.Post listener. ENABLE_SPAWNING={}", ENABLE_SPAWNING);
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] Failed to register listeners", t);
        }
    }

    // ---------------------------------------------------------------------
    // EVENT HANDLER
    // ---------------------------------------------------------------------

    private static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        if (!ENABLE_SPAWNING) {
            return;
        }

        try {
            final long gameTime = level.getGameTime();

            // We use the registry lookup each run to avoid stale references in dev reload edge cases.
            final EntityType<?> ravenType = BuiltInRegistries.ENTITY_TYPE.get(RAVEN_ID);
            if (ravenType == null) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                    LOG.warn("[RavenSpawnEvents] Raven EntityType not found for id {}", RAVEN_ID);
                }
                return;
            }

            final List<? extends Player> players = level.players();
            if (players.isEmpty()) {
                return;
            }

            // Defense-in-depth: global cap for WILD ravens.
            // If this trips, something is spawning too many ravens (this handler, vanilla spawn lists, or another mod).
            // We only cull if ravens are near a player (we do not roam the entire world).
            if ((gameTime % CLEANUP_INTERVAL_TICKS) == 0L) {
                enforceGlobalWildRavenCapNearPlayers(level, players, ravenType, gameTime);
            }

            // Per-player cleanup always runs on CLEANUP_INTERVAL_TICKS.
            if ((gameTime % CLEANUP_INTERVAL_TICKS) == 0L) {
                for (Player player : players) {
                    if (player == null || player.isSpectator()) {
                        continue;
                    }
                    // Even if player is too high for spawning, we still want cleanup in case they fly into a raven swarm.
                    cullExtraWildRavensNearPlayer(level, player, ravenType, gameTime);
                }
            }

            // Spawn checks run on CHECK_INTERVAL_TICKS.
            if ((gameTime % CHECK_INTERVAL_TICKS) != 0L) {
                return;
            }

            final int effectiveSearchRadius = computeEffectiveSearchRadius();
            if (effectiveSearchRadius <= 0) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                    LOG.warn(
                            "[RavenSpawnEvents] effectiveSearchRadius <= 0 (SEARCH_RADIUS_BLOCKS={}, LOCAL_RAVEN_RADIUS={}). Spawning disabled.",
                            SEARCH_RADIUS_BLOCKS,
                            LOCAL_RAVEN_RADIUS
                    );
                }
                return;
            }

            for (Player player : players) {
                if (player == null || player.isSpectator()) {
                    continue;
                }

                // Skip spawns when player is far above the terrain (sky rigs).
                if (isPlayerTooHighAboveSurface(level, player, gameTime)) {
                    continue;
                }

                final int wildCount = countWildRavensNearPlayer(level, player, ravenType, gameTime);
                final int localCap = wildRavensPerPlayer();
                if (wildCount >= localCap) {
                    // Hard cap reached: never spawn more here.
                    continue;
                }

                // Decide spawn chance based on local WILD population.
                final double spawnChance = (wildCount <= 0) ? SPAWN_CHANCE_EMPTY_AREA : SPAWN_CHANCE_WITH_ONE_RAVEN;

                final RandomSource rnd = level.getRandom();
                if (rnd.nextDouble() >= spawnChance) {
                    continue;
                }

                final BlockPos spawnPos = findLeavesTopSpawnPos(level, player, rnd, effectiveSearchRadius);
                if (spawnPos == null) {
                    if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                        LOG.debug(
                                "[RavenSpawnEvents] No valid leaves-top spawn found near player {} (effectiveRadius={} blocks).",
                                safeName(player),
                                effectiveSearchRadius
                        );
                    }
                    continue;
                }

                // Before we actually spawn, re-check the cap to minimize â€œraceâ€ behavior if multiple handlers exist.
                final int wildCountPreSpawn = countWildRavensNearPlayer(level, player, ravenType, gameTime);
                final int localCapNow = wildRavensPerPlayer();
                if (wildCountPreSpawn >= localCapNow) {
                    if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                        LOG.debug(
                                "[RavenSpawnEvents] Spawn aborted: cap already reached near player {} (wildCountPreSpawn={}).",
                                safeName(player),
                                wildCountPreSpawn
                        );
                    }
                    continue;
                }

                if (spawnRaven(level, ravenType, spawnPos, gameTime)) {
                    if ((gameTime % SPAWN_LOG_INTERVAL_TICKS) == 0L) {
                        LOG.debug(
                                "[RavenSpawnEvents] Spawned WILD raven at {} near player {} (wildCountBeforeSpawn={})",
                                spawnPos,
                                safeName(player),
                                wildCountPreSpawn
                        );
                    } else if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                        LOG.debug(
                                "[RavenSpawnEvents] Spawned WILD raven at {} near player {} (wildCountBeforeSpawn={})",
                                spawnPos,
                                safeName(player),
                                wildCountPreSpawn
                        );
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] onLevelTickPost failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // SUPPORT / HELPERS
    // ---------------------------------------------------------------------

    /** Ensure search radius stays inside LOCAL_RAVEN_RADIUS (population-control radius). */
    private static int computeEffectiveSearchRadius() {
        try {
            int maxInsideLocal = (int) Math.floor(LOCAL_RAVEN_RADIUS) - 8; // safety buffer
            if (maxInsideLocal <= 0) {
                return 0;
            }
            return Math.max(1, Math.min(SEARCH_RADIUS_BLOCKS, maxInsideLocal));
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] computeEffectiveSearchRadius failed", t);
            return 0;
        }
    }

    private static boolean isPlayerTooHighAboveSurface(net.minecraft.server.level.ServerLevel level, Player player, long gameTime) {
        try {
            final BlockPos p = player.blockPosition();

            final int surfaceY;
            try {
                surfaceY = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                        p.getX(),
                        p.getZ()
                );
            } catch (Throwable t) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                    LOG.debug(
                            "[RavenSpawnEvents] Surface height query failed for player {} at xz=({},{}). Spawning skipped.",
                            safeName(player),
                            p.getX(),
                            p.getZ()
                    );
                }
                return true;
            }

            final int above = p.getY() - surfaceY;
            if (above > MAX_PLAYER_HEIGHT_ABOVE_SURFACE) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                    LOG.debug(
                            "[RavenSpawnEvents] Player {} is too high above surface (playerY={}, surfaceY={}, delta={} > {}). Spawning disabled for this player.",
                            safeName(player),
                            p.getY(),
                            surfaceY,
                            above,
                            MAX_PLAYER_HEIGHT_ABOVE_SURFACE
                    );
                }
                return true;
            }

            return false;
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] isPlayerTooHighAboveSurface failed", t);
            return true;
        }
    }

    private static String safeName(Player player) {
        try {
            if (player.getGameProfile() != null && player.getGameProfile().getName() != null) {
                return player.getGameProfile().getName();
            }
        } catch (Throwable ignored) {
        }
        return "<unknown>";
    }

    /**
     * True if this entity is our Raven type.
     * We compare by EntityType instance to avoid registry-key weirdness.
     */
    private static boolean isRavenEntity(Entity e, EntityType<?> ravenType) {
        try {
            return e != null && e.getType() == ravenType;
        } catch (Throwable t) {
            LOG.debug("[RavenSpawnEvents] isRavenEntity check failed", t);
            return false;
        }
    }

    /**
     * We only cap/cull WILD ravens.
     * Tamed ravens are exempt and should never be limited by spawn mechanics.
     */
    private static boolean isWildRaven(Entity e, EntityType<?> ravenType) {
        try {
            if (!isRavenEntity(e, ravenType)) {
                return false;
            }

            // If your RavenEntity extends TamableAnimal, this is the cleanest check.
            // If it does not, this will simply not match, and the raven will be considered "wild".
            if (e instanceof TamableAnimal ta) {
                if (ta.isTame()) {
                    return false;
                }
            }

            // Extra safety: if something sets persistence (named, etc.) you may want to keep it.
            // But user requested only tamed are exempt, so we do NOT exempt persistence here.

            return true;
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] isWildRaven failed (defaulting to NOT wild to avoid accidental culling)", t);
            return false;
        }
    }

    /**
     * Count WILD ravens within LOCAL_RAVEN_RADIUS of player.
     */
    private static int countWildRavensNearPlayer(net.minecraft.server.level.ServerLevel level,
                                                 Player player,
                                                 EntityType<?> ravenType,
                                                 long gameTime) {
        try {
            final AABB box = player.getBoundingBox().inflate(LOCAL_RAVEN_RADIUS);

            // Use getEntitiesOfClass(Entity.class) to avoid overload ambiguity and keep broad compatibility.
            final List<Entity> matches = level.getEntitiesOfClass(
                    Entity.class,
                    box,
                    e -> isWildRaven(e, ravenType)
            );

            final int count = (matches == null) ? 0 : matches.size();

            if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                LOG.debug(
                        "[RavenSpawnEvents] countWildRavensNearPlayer: player={} wildCount={} radius={}",
                        safeName(player),
                        count,
                        LOCAL_RAVEN_RADIUS
                );
            }

            return count;
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] countWildRavensNearPlayer failed", t);
            // Fail-safe: if counting fails, pretend cap is reached to avoid accidental floods.
            return wildRavensPerPlayer();
        }
    }

    /**
     * Hard enforcement: if >2 WILD ravens are near this player, despawn extras immediately.
     * We remove the farthest ones first (keeps â€œlocal pairâ€ close to player).
     */
    private static void cullExtraWildRavensNearPlayer(net.minecraft.server.level.ServerLevel level,
                                                      Player player,
                                                      EntityType<?> ravenType,
                                                      long gameTime) {
        try {
            final AABB box = player.getBoundingBox().inflate(LOCAL_RAVEN_RADIUS);

            final List<Entity> wildRavens = level.getEntitiesOfClass(
                    Entity.class,
                    box,
                    e -> isWildRaven(e, ravenType)
            );

            int localCap = wildRavensPerPlayer();
            if (wildRavens == null || wildRavens.size() <= localCap) {
                return;
            }

            // Sort by distance descending: farthest removed first.
            wildRavens.sort(Comparator.comparingDouble((Entity e) -> {
                try {
                    return e.distanceToSqr(player);
                } catch (Throwable t) {
                    return Double.MAX_VALUE;
                }
            }).reversed());

            int removed = 0;
            for (int i = localCap; i < wildRavens.size(); i++) {
                Entity e = wildRavens.get(i);
                if (e == null || !e.isAlive()) {
                    continue;
                }

                try {
                    // discard() is the standard safe removal in modern MC.
                    e.discard();
                    removed++;
                } catch (Throwable t) {
                    LOG.warn("[RavenSpawnEvents] Failed to discard extra wild raven {}", e, t);
                }
            }

            if (removed > 0 && (gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                LOG.warn(
                        "[RavenSpawnEvents] CULLED extra wild ravens near player {}: before={} removed={} kept={}",
                        safeName(player),
                        wildRavens.size(),
                        removed,
                        localCap
                );
            }
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] cullExtraWildRavensNearPlayer failed", t);
        }
    }

    /**
     * Optional global cap near players only (we don't scan the entire world).
     * We compute an allowed maximum based on player count and cull if exceeded.
     */
    private static void enforceGlobalWildRavenCapNearPlayers(net.minecraft.server.level.ServerLevel level,
                                                             List<? extends Player> players,
                                                             EntityType<?> ravenType,
                                                             long gameTime) {
        try {
            int nonSpectators = 0;
            for (Player p : players) {
                if (p != null && !p.isSpectator()) nonSpectators++;
            }
            if (nonSpectators <= 0) {
                return;
            }

            final int perPlayer = wildRavensPerPlayer();
            final int globalCap = Math.max(0, (nonSpectators * perPlayer) + GLOBAL_WILD_RAVEN_BUFFER);

            // Gather wild ravens near all players (union-ish). We allow duplicates temporarily; we dedupe by id.
            final List<Entity> gathered = new ArrayList<>();
            for (Player p : players) {
                if (p == null || p.isSpectator()) continue;

                final AABB box = p.getBoundingBox().inflate(LOCAL_RAVEN_RADIUS);
                List<Entity> local = level.getEntitiesOfClass(Entity.class, box, e -> isWildRaven(e, ravenType));
                if (local != null && !local.isEmpty()) {
                    gathered.addAll(local);
                }
            }

            if (gathered.isEmpty()) {
                return;
            }

            // Dedupe by entity id
            final List<Entity> unique = new ArrayList<>();
            final java.util.HashSet<Integer> seen = new java.util.HashSet<>();
            for (Entity e : gathered) {
                if (e == null) continue;
                int id;
                try {
                    id = e.getId();
                } catch (Throwable t) {
                    continue;
                }
                if (seen.add(id)) {
                    unique.add(e);
                }
            }

            if (unique.size() <= globalCap) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                    LOG.debug("[RavenSpawnEvents] Global wild raven count near players OK: count={} cap={}", unique.size(), globalCap);
                }
                return;
            }

            // Cull extras: remove farthest-from-nearest-player first.
            unique.sort(Comparator.comparingDouble((Entity e) -> {
                double best = Double.MIN_VALUE;
                try {
                    // We want farthest first, so compute "nearest distance" and sort descending by that.
                    double nearest = Double.POSITIVE_INFINITY;
                    for (Player p : players) {
                        if (p == null || p.isSpectator()) continue;
                        double d = e.distanceToSqr(p);
                        if (d < nearest) nearest = d;
                    }
                    best = nearest;
                } catch (Throwable ignored) {
                    best = Double.NEGATIVE_INFINITY;
                }
                return best;
            }).reversed());

            int toRemove = unique.size() - globalCap;
            int removed = 0;

            for (Entity e : unique) {
                if (removed >= toRemove) break;
                if (e == null || !e.isAlive()) continue;

                try {
                    e.discard();
                    removed++;
                } catch (Throwable t) {
                    LOG.warn("[RavenSpawnEvents] Failed to discard wild raven during global cap enforcement {}", e, t);
                }
            }

            if (removed > 0) {
                LOG.warn(
                        "[RavenSpawnEvents] GLOBAL CULL: wild ravens near players exceeded cap. countBefore={} cap={} removed={}",
                        unique.size(),
                        globalCap,
                        removed
                );
            }
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] enforceGlobalWildRavenCapNearPlayers failed", t);
        }
    }

    /**
     * Try to find a spawn position above a LEAVES block near the player.
     */
    private static BlockPos findLeavesTopSpawnPos(net.minecraft.server.level.ServerLevel level,
                                                  Player player,
                                                  RandomSource rnd,
                                                  int effectiveSearchRadius) {
        final BlockPos origin = player.blockPosition();

        for (int attempt = 0; attempt < CANDIDATE_COLUMNS_PER_CHECK; attempt++) {
            final int dx = rnd.nextInt(effectiveSearchRadius * 2 + 1) - effectiveSearchRadius;
            final int dz = rnd.nextInt(effectiveSearchRadius * 2 + 1) - effectiveSearchRadius;

            final int x = origin.getX() + dx;
            final int z = origin.getZ() + dz;

            final ChunkPos cp = new ChunkPos(x >> 4, z >> 4);
            if (!level.hasChunk(cp.x, cp.z)) {
                continue;
            }

            final int topY;
            try {
                topY = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                        x,
                        z
                );
            } catch (Throwable t) {
                continue;
            }

            final int minY = Math.max(level.getMinBuildHeight(), topY - MAX_DOWNWARD_SCAN);
            for (int y = topY; y >= minY; y--) {
                final BlockPos leavesPos = new BlockPos(x, y, z);
                final BlockState state = level.getBlockState(leavesPos);
                if (state == null) {
                    continue;
                }

                if (!state.is(BlockTags.LEAVES)) {
                    continue;
                }

                final BlockPos spawnPos = leavesPos.above();

                if (!isAirColumn(level, spawnPos, REQUIRED_AIR_ABOVE)) {
                    continue;
                }

                if (!level.getWorldBorder().isWithinBounds(spawnPos)) {
                    continue;
                }

                if (!level.isEmptyBlock(spawnPos)) {
                    continue;
                }

                // Keep spawn within local population-control radius (horizontal clamp).
                final double dxp = (spawnPos.getX() + 0.5D) - (origin.getX() + 0.5D);
                final double dzp = (spawnPos.getZ() + 0.5D) - (origin.getZ() + 0.5D);
                final double distSq = dxp * dxp + dzp * dzp;
                final double maxDist = LOCAL_RAVEN_RADIUS - 1.0D;
                if (distSq > (maxDist * maxDist)) {
                    continue;
                }

                return spawnPos;
            }
        }

        return null;
    }

    private static boolean isAirColumn(net.minecraft.server.level.ServerLevel level, BlockPos start, int airBlocksNeeded) {
        try {
            BlockPos pos = start;
            for (int i = 0; i < airBlocksNeeded; i++) {
                if (!level.isEmptyBlock(pos)) {
                    return false;
                }
                pos = pos.above();
            }
            return true;
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] isAirColumn failed", t);
            return false;
        }
    }

    /**
     * Spawns a raven ONLY if the spawn position is still valid and STILL above LEAVES.
     * This is a hard safety gate: this handler will not create ravens on non-leaves.
     */
    private static boolean spawnRaven(net.minecraft.server.level.ServerLevel level,
                                      EntityType<?> type,
                                      BlockPos pos,
                                      long gameTime) {
        try {
            // HARD GATE: must be above leaves at time of spawn.
            BlockPos below = pos.below();
            BlockState belowState;
            try {
                belowState = level.getBlockState(below);
            } catch (Throwable t) {
                LOG.warn("[RavenSpawnEvents] Spawn aborted: failed to read block below {}.", pos, t);
                return false;
            }

            if (belowState == null || !belowState.is(BlockTags.LEAVES)) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                    LOG.warn(
                            "[RavenSpawnEvents] Spawn aborted: position {} is NOT above leaves (below={} state={}). " +
                                    "If you're seeing ravens spawn on ground, they are likely coming from biome spawn lists, not this handler.",
                            pos,
                            below,
                            belowState
                    );
                }
                return false;
            }

            if (!level.isEmptyBlock(pos)) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                    LOG.debug("[RavenSpawnEvents] Spawn aborted: {} is not empty.", pos);
                }
                return false;
            }

            final Entity created = type.create(level);
            if (created == null) {
                LOG.warn("[RavenSpawnEvents] EntityType.create() returned null for {}", RAVEN_ID);
                return false;
            }

            created.moveTo(
                    pos.getX() + 0.5D,
                    pos.getY(),
                    pos.getZ() + 0.5D,
                    level.getRandom().nextFloat() * 360.0F,
                    0.0F
            );

            if (created instanceof Mob mob) {
                try {
                    mob.finalizeSpawn(
                            level,
                            level.getCurrentDifficultyAt(pos),
                            MobSpawnType.NATURAL,
                            null
                    );
                } catch (Throwable t) {
                    LOG.warn(
                            "[RavenSpawnEvents] finalizeSpawn failed for raven at {} â€“ continuing with spawned entity",
                            pos,
                            t
                    );
                }
            }

            final boolean ok = level.addFreshEntity(created);
            if (!ok) {
                LOG.warn("[RavenSpawnEvents] addFreshEntity returned false at {}", pos);
            }

            return ok;
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] spawnRaven failed at {}", pos, t);
            return false;
        }
    }
}
