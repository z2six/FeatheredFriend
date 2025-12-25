// neoforge/src/main/java/net/z2six/featheredfriend/world/RavenSpawnEvents.java
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

import java.util.List;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/RavenSpawnEvents.java
 *
 * Natural spawner for Ravens.
 *
 * Intent / design goals:
 *  - Ravens are more common in tree-heavy biomes (we spawn on leaves tops).
 *  - You are likely to encounter at least one raven in a forest-style biome over time.
 *  - Seeing two ravens together should be rare but possible.
 *  - Seeing none at all is still possible, just less likely in dense forests.
 *
 * Implementation notes:
 *  - We no longer do "X ravens per player" or per-player spawn cooldown logic.
 *  - Instead, we enforce a *local* population cap in a radius around each player:
 *      * Soft target = 1 raven in the radius.
 *      * Hard cap   = 2 ravens in the radius.
 *  - When 0 ravens are nearby, spawn rolls are relatively more generous.
 *  - When 1 raven is nearby, spawn rolls are much rarer.
 *  - When 2 or more ravens are nearby, we skip further spawns in that region.
 *
 *  - Spawns happen on top of LEAVES blocks with some air above.
 *  - We avoid spawning when the player is far above the world surface (sky rigs).
 *  - Debug and spawn logs are throttled to avoid log spam.
 */
public final class RavenSpawnEvents {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // HARD TOGGLE
    // ---------------------------------------------------------------------

    /**
     * Master enable switch for ALL raven spawning by this event handler.
     *
     * Set to false when:
     *  - you are testing A* pathing in controlled environments
     *  - you are creating a new world and don't want any natural ravens yet
     *  - you are debugging other systems and want a quiet log
     *
     * Listener remains registered; we just early-return.
     */
    private static final boolean ENABLE_SPAWNING = true;

    // ---------------------------------------------------------------------
    // TUNING CONSTANTS
    // ---------------------------------------------------------------------

    /**
     * How often to run spawn attempts per level.
     * 20 ticks = 1 second. 200 ticks ~= 10 seconds.
     *
     * We keep this reasonably low frequency since the check walks chunks and entities.
     */
    private static final int CHECK_INTERVAL_TICKS = 200;

    /**
     * Local population radius within which we inspect existing ravens.
     *
     * We use this both for:
     *  - limiting local population (soft/hard cap)
     *  - clamping spawn search radius
     */
    private static final double LOCAL_RAVEN_RADIUS = 96.0D;

    /**
     * Soft target and hard maximum for ravens near a player.
     *
     * Behavior:
     *  - If ravenCount == 0:
     *        we use SPAWN_CHANCE_EMPTY_AREA
     *  - If ravenCount == 1:
     *        we use SPAWN_CHANCE_WITH_ONE_RAVEN (much smaller)
     *  - If ravenCount >= HARD_MAX_RAVENS_IN_RADIUS:
     *        we do not spawn
     *
     * This yields:
     *  - "likely to find one" over time in dense forests
     *  - "two is rare" because the second spawn is much less likely
     */
    private static final int SOFT_TARGET_RAVENS_IN_RADIUS = 1;
    private static final int HARD_MAX_RAVENS_IN_RADIUS = 2;

    /**
     * Spawn chances per check depending on local population.
     *
     * These are tuned under the assumption of:
     *  - CHECK_INTERVAL_TICKS = 200 (one check ~ every 10 seconds)
     *  - Leaves-rich area => high chance to find at least one valid leaves column.
     *
     * Rough intuition (for a player staying in a forest):
     *  - EMPTY_AREA: ~0.02 => about 1 raven per Minecraft day on average.
     *  - ONE_RAVEN: ~0.002 => much rarer second raven in the same area.
     */
    private static final double SPAWN_CHANCE_EMPTY_AREA = 0.04D;
    private static final double SPAWN_CHANCE_WITH_ONE_RAVEN = 0.002D;

    /**
     * How far from player we sample random columns for leaves-top spawns.
     *
     * This will be clamped to remain inside LOCAL_RAVEN_RADIUS so that
     * we never spawn outside the population-control radius.
     */
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
     * If player is more than this many blocks above the local WORLD_SURFACE height at their X/Z, we do not spawn.
     * (Prevents constant spawning around your 200-block-high test platforms.)
     */
    private static final int MAX_PLAYER_HEIGHT_ABOVE_SURFACE = 128;

    // ---------------------------------------------------------------------
    // LIFECYCLE
    // ---------------------------------------------------------------------

    private RavenSpawnEvents() {
        // no instances
    }

    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(RavenSpawnEvents::onLevelTickPost);
            LOG.info(
                    "[RavenSpawnEvents] Registered LevelTickEvent.Post listener. ENABLE_SPAWNING={}",
                    ENABLE_SPAWNING
            );
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

        // Master kill-switch
        if (!ENABLE_SPAWNING) {
            // Intentionally silent; flip the flag when needed.
            return;
        }

        try {
            long gameTime = level.getGameTime();
            if ((gameTime % CHECK_INTERVAL_TICKS) != 0L) {
                return;
            }

            List<? extends Player> players = level.players();
            if (players.isEmpty()) {
                return;
            }

            EntityType<?> ravenType = BuiltInRegistries.ENTITY_TYPE.get(RAVEN_ID);
            if (ravenType == null) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                    LOG.warn("[RavenSpawnEvents] Raven EntityType not found for id {}", RAVEN_ID);
                }
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

                // Skip when player is far above the terrain.
                if (isPlayerTooHighAboveSurface(level, player, gameTime)) {
                    continue;
                }

                int ravenCount = countRavensNearPlayer(level, player);
                if (ravenCount >= HARD_MAX_RAVENS_IN_RADIUS) {
                    // Already at or above hard cap; do not spawn more here.
                    continue;
                }

                // Decide spawn chance based on local population.
                double spawnChance;
                if (ravenCount <= 0) {
                    spawnChance = SPAWN_CHANCE_EMPTY_AREA;
                } else if (ravenCount <= SOFT_TARGET_RAVENS_IN_RADIUS) {
                    spawnChance = SPAWN_CHANCE_WITH_ONE_RAVEN;
                } else {
                    // Between soft target and hard cap; very narrow band, but be conservative.
                    spawnChance = SPAWN_CHANCE_WITH_ONE_RAVEN;
                }

                RandomSource rnd = level.getRandom();
                if (rnd.nextDouble() >= spawnChance) {
                    continue;
                }

                BlockPos spawnPos = findLeavesTopSpawnPos(level, player, rnd, effectiveSearchRadius);
                if (spawnPos == null) {
                    if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                        LOG.info(
                                "[RavenSpawnEvents] No valid leaves-top spawn found near player {} (effectiveRadius={} blocks).",
                                safeName(player),
                                effectiveSearchRadius
                        );
                    }
                    continue;
                }

                if (spawnRaven(level, ravenType, spawnPos)) {
                    if ((gameTime % SPAWN_LOG_INTERVAL_TICKS) == 0L) {
                        LOG.info(
                                "[RavenSpawnEvents] Spawned raven at {} near player {} (previousCount={})",
                                spawnPos,
                                safeName(player),
                                ravenCount
                        );
                    } else if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                        LOG.debug(
                                "[RavenSpawnEvents] Spawned raven at {} near player {} (previousCount={})",
                                spawnPos,
                                safeName(player),
                                ravenCount
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

    /**
     * Ensure our spawn search radius is always fully inside LOCAL_RAVEN_RADIUS so
     * we never spawn outside the population-control radius.
     */
    private static int computeEffectiveSearchRadius() {
        try {
            int maxInsideLocal = (int) Math.floor(LOCAL_RAVEN_RADIUS) - 8; // small safety buffer
            if (maxInsideLocal <= 0) {
                return 0;
            }
            return Math.max(1, Math.min(SEARCH_RADIUS_BLOCKS, maxInsideLocal));
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] computeEffectiveSearchRadius failed", t);
            return 0;
        }
    }

    private static boolean isPlayerTooHighAboveSurface(net.minecraft.server.level.ServerLevel level,
                                                       Player player,
                                                       long gameTime) {
        try {
            BlockPos p = player.blockPosition();

            int surfaceY;
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

            int playerY = p.getY();
            int above = playerY - surfaceY;

            if (above > MAX_PLAYER_HEIGHT_ABOVE_SURFACE) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                    LOG.info(
                            "[RavenSpawnEvents] Player {} is too high above surface (playerY={}, surfaceY={}, delta={} > {}). Spawning disabled for this player.",
                            safeName(player),
                            playerY,
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
     * Counts how many ravens exist within LOCAL_RAVEN_RADIUS of the given player.
     *
     * This is a *local population* check, not a "per player" quota in the sense of
     * "X ravens per connected player". We simply limit density in the area around
     * the player.
     */
    private static int countRavensNearPlayer(net.minecraft.server.level.ServerLevel level, Player player) {
        try {
            AABB box = player.getBoundingBox().inflate(LOCAL_RAVEN_RADIUS);

            // *** FIX: use getEntitiesOfClass to avoid ambiguous getEntities() overload ***
            List<Entity> ravens = level.getEntitiesOfClass(
                    Entity.class,
                    box,
                    e -> e != null
                            && e.getType() != null
                            && RAVEN_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))
            );

            int count = (ravens == null) ? 0 : ravens.size();

            // Very light, throttled debug.
            long gameTime = level.getGameTime();
            if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0L) {
                LOG.debug(
                        "[RavenSpawnEvents] countRavensNearPlayer: player={} count={} radius={}",
                        safeName(player),
                        count,
                        LOCAL_RAVEN_RADIUS
                );
            }

            return count;
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] countRavensNearPlayer failed", t);
            // Fail-safe: if we can't count, pretend we are at hard cap so we don't spam spawns.
            return HARD_MAX_RAVENS_IN_RADIUS;
        }
    }

    /**
     * Try to find a spawn position above a LEAVES block near the player.
     */
    private static BlockPos findLeavesTopSpawnPos(net.minecraft.server.level.ServerLevel level,
                                                  Player player,
                                                  RandomSource rnd,
                                                  int effectiveSearchRadius) {
        BlockPos origin = player.blockPosition();

        for (int attempt = 0; attempt < CANDIDATE_COLUMNS_PER_CHECK; attempt++) {
            int dx = rnd.nextInt(effectiveSearchRadius * 2 + 1) - effectiveSearchRadius;
            int dz = rnd.nextInt(effectiveSearchRadius * 2 + 1) - effectiveSearchRadius;

            int x = origin.getX() + dx;
            int z = origin.getZ() + dz;

            ChunkPos cp = new ChunkPos(x >> 4, z >> 4);
            if (!level.hasChunk(cp.x, cp.z)) {
                continue;
            }

            int topY;
            try {
                topY = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                        x,
                        z
                );
            } catch (Throwable t) {
                // Heightmap lookup failed; skip this column.
                continue;
            }

            int minY = Math.max(level.getMinBuildHeight(), topY - MAX_DOWNWARD_SCAN);
            for (int y = topY; y >= minY; y--) {
                BlockPos leavesPos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(leavesPos);
                if (state == null) {
                    continue;
                }

                if (!state.is(BlockTags.LEAVES)) {
                    continue;
                }

                BlockPos spawnPos = leavesPos.above();

                if (!isAirColumn(level, spawnPos, REQUIRED_AIR_ABOVE)) {
                    continue;
                }

                if (!level.getWorldBorder().isWithinBounds(spawnPos)) {
                    continue;
                }

                if (!level.isEmptyBlock(spawnPos)) {
                    continue;
                }

                // Keep spawn within the same local radius we're using for population control.
                double dxp = (spawnPos.getX() + 0.5D) - (origin.getX() + 0.5D);
                double dzp = (spawnPos.getZ() + 0.5D) - (origin.getZ() + 0.5D);
                double distSq = dxp * dxp + dzp * dzp;
                double maxDist = LOCAL_RAVEN_RADIUS - 1.0D;
                if (distSq > (maxDist * maxDist)) {
                    continue;
                }

                return spawnPos;
            }
        }

        return null;
    }

    private static boolean isAirColumn(net.minecraft.server.level.ServerLevel level,
                                       BlockPos start,
                                       int airBlocksNeeded) {
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

    private static boolean spawnRaven(net.minecraft.server.level.ServerLevel level,
                                      EntityType<?> type,
                                      BlockPos pos) {
        try {
            Entity created = type.create(level);
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
                            "[RavenSpawnEvents] finalizeSpawn failed for raven at {} – continuing with spawned entity",
                            pos,
                            t
                    );
                }
            }

            boolean ok = level.addFreshEntity(created);
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
