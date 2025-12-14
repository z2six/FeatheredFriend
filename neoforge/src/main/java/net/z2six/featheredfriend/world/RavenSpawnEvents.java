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
 * DEBUG-HEAVY spawner tuning for testing Raven AI.
 *
 * Spawn rule (unchanged):
 *  - Very small chance of spawning on top of ANY leaves block.
 *  - Must have air blocks above the spawn position.
 *  - Singleton near a player: player should never see 2 ravens spawned.
 *
 * This file is tuned HIGH so you can see ravens quickly during dev.
 * After testing, reduce the constants.
 */
public final class RavenSpawnEvents {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------- DEV/TUNING ----------

    /** How often to run spawn attempts per level. 20 ticks = 1 second. */
    private static final int CHECK_INTERVAL_TICKS = 1;

    /** Spawn chance per eligible player per check. 1.0 = always roll "yes". */
    private static final double SPAWN_CHANCE_PER_CHECK = 1.0D;

    /** Singleton radius: if any raven exists inside this radius around player, do not spawn. */
    private static final double SINGLETON_RADIUS = 96.0D;

    /** How far from player we sample random columns for leaves-top spawns. */
    private static final int SEARCH_RADIUS_BLOCKS = 160;

    /** How many random columns we try each check before giving up. */
    private static final int CANDIDATE_COLUMNS_PER_CHECK = 200;

    /** How far down from surface we scan to find leaves. */
    private static final int MAX_DOWNWARD_SCAN = 96;

    /** Require at least this many air blocks above the spawn position. */
    private static final int REQUIRED_AIR_ABOVE = 2;

    /** Raven entity id (registry name). */
    private static final ResourceLocation RAVEN_ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "raven");

    /** Debug logging throttle (ticks). */
    private static final int DEBUG_LOG_INTERVAL_TICKS = 100;

    private RavenSpawnEvents() {}

    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(RavenSpawnEvents::onLevelTickPost);
            LOG.info("[RavenSpawnEvents] Registered LevelTickEvent.Post listener (DEV tuned)");
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] Failed to register listeners", t);
        }
    }

    private static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        try {
            long gameTime = level.getGameTime();
            if ((gameTime % CHECK_INTERVAL_TICKS) != 0) {
                return;
            }

            List<? extends Player> players = level.players();
            if (players.isEmpty()) {
                return;
            }

            EntityType<?> ravenType = BuiltInRegistries.ENTITY_TYPE.get(RAVEN_ID);
            if (ravenType == null) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0) {
                    LOG.warn("[RavenSpawnEvents] Raven EntityType not found for id {}", RAVEN_ID);
                }
                return;
            }

            for (Player player : players) {
                if (player == null || player.isSpectator()) {
                    continue;
                }

                // Singleton rule
                if (hasRavenNearPlayer(level, player)) {
                    continue;
                }

                RandomSource rnd = level.getRandom();
                if (rnd.nextDouble() > SPAWN_CHANCE_PER_CHECK) {
                    continue;
                }

                BlockPos spawnPos = findLeavesTopSpawnPos(level, player, rnd);
                if (spawnPos == null) {
                    if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0) {
                        LOG.info("[RavenSpawnEvents] No valid leaves-top spawn found near player {} (likely no trees/leaves nearby).",
                                player.getGameProfile().getName());
                    }
                    continue;
                }

                if (spawnRaven(level, ravenType, spawnPos)) {
                    LOG.info("[RavenSpawnEvents] Spawned raven at {} near player {}", spawnPos, player.getGameProfile().getName());
                }
            }

        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] onLevelTickPost failed", t);
        }
    }

    private static boolean hasRavenNearPlayer(net.minecraft.server.level.ServerLevel level, Player player) {
        try {
            AABB box = player.getBoundingBox().inflate(SINGLETON_RADIUS);
            List<Entity> ravens = level.getEntities((Entity) null, box, e ->
                    e != null && e.getType() != null && RAVEN_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))
            );
            return ravens != null && !ravens.isEmpty();
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] hasRavenNearPlayer failed", t);
            // Fail-safe: avoid spawning duplicates if check fails
            return true;
        }
    }

    private static BlockPos findLeavesTopSpawnPos(net.minecraft.server.level.ServerLevel level, Player player, RandomSource rnd) {
        BlockPos origin = player.blockPosition();

        for (int attempt = 0; attempt < CANDIDATE_COLUMNS_PER_CHECK; attempt++) {
            int dx = rnd.nextInt(SEARCH_RADIUS_BLOCKS * 2 + 1) - SEARCH_RADIUS_BLOCKS;
            int dz = rnd.nextInt(SEARCH_RADIUS_BLOCKS * 2 + 1) - SEARCH_RADIUS_BLOCKS;

            int x = origin.getX() + dx;
            int z = origin.getZ() + dz;

            ChunkPos cp = new ChunkPos(x >> 4, z >> 4);
            if (!level.hasChunk(cp.x, cp.z)) {
                continue;
            }

            int topY;
            try {
                topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
            } catch (Throwable t) {
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

                return spawnPos;
            }
        }

        return null;
    }

    private static boolean isAirColumn(net.minecraft.server.level.ServerLevel level, BlockPos start, int airBlocksNeeded) {
        try {
            BlockPos p = start;
            for (int i = 0; i < airBlocksNeeded; i++) {
                if (!level.isEmptyBlock(p)) {
                    return false;
                }
                p = p.above();
            }
            return true;
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] isAirColumn failed", t);
            return false;
        }
    }

    private static boolean spawnRaven(net.minecraft.server.level.ServerLevel level, EntityType<?> type, BlockPos pos) {
        try {
            Entity created = type.create(level);
            if (created == null) {
                LOG.warn("[RavenSpawnEvents] EntityType.create() returned null for {}", RAVEN_ID);
                return false;
            }

            created.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);

            if (created instanceof Mob mob) {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null);
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
