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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/RavenSpawnEvents.java
 *
 * DEBUG-HEAVY spawner tuning for testing Raven AI.
 *
 * Fixes included:
 *  1) Prevent "infinite spawns" by ensuring:
 *      - spawn search radius is ALWAYS within singleton radius
 *      - per-player spawn cooldown is enforced
 *  2) Prevent spawning while player is far above terrain (e.g., pathfinding test rig in the sky)
 *  3) Throttle spawn logs to avoid latest.log spam
 *  4) NEW: Hard toggle to disable ALL spawning without removing the event listener.
 *
 * Spawn rule (still the core behavior):
 *  - Very small chance of spawning on top of ANY leaves block.
 *  - Must have air blocks above the spawn position.
 *  - Singleton near a player: player should never see 2 ravens spawned.
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
     * Note:
     *  - Listener still remains registered; we just early-return.
     *  - This is intentional so you can flip this in code without touching registration.
     */
    private static final boolean ENABLE_SPAWNING = false;

    // ---------- DEV/TUNING ----------

    /** How often to run spawn attempts per level. 20 ticks = 1 second. */
    private static final int CHECK_INTERVAL_TICKS = 1;

    /** Spawn chance per eligible player per check. 1.0 = always roll "yes". */
    private static final double SPAWN_CHANCE_PER_CHECK = 1.0D;

    /**
     * Singleton radius:
     *  - If any raven exists inside this radius around player, do not spawn.
     * IMPORTANT:
     *  - Your old setup searched up to 160 blocks but singleton radius was 96 blocks,
     *    so you could spawn ravens outside the singleton AABB every tick forever.
     */
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

    /** Spawn log throttle (ticks). */
    private static final int SPAWN_LOG_INTERVAL_TICKS = 60;

    /**
     * Per-player cooldown after a spawn succeeds (ticks).
     * This is a hard stop against spam even if something goes wrong with singleton detection.
     */
    private static final int PER_PLAYER_SPAWN_COOLDOWN_TICKS = 20 * 10; // 10s

    /**
     * Sky-test safety:
     * If player is more than this many blocks above the local WORLD_SURFACE height at their X/Z, we do not spawn.
     * (This avoids your "200 blocks above ground" test area from constantly triggering spawns.)
     */
    private static final int MAX_PLAYER_HEIGHT_ABOVE_SURFACE = 48;

    // --- runtime state ---
    private static final Map<UUID, Long> LAST_SPAWN_TICK_BY_PLAYER = new HashMap<>();

    private RavenSpawnEvents() {}

    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(RavenSpawnEvents::onLevelTickPost);
            LOG.info("[RavenSpawnEvents] Registered LevelTickEvent.Post listener (DEV tuned). ENABLE_SPAWNING={}", ENABLE_SPAWNING);
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] Failed to register listeners", t);
        }
    }

    private static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        // Master kill-switch (NEW)
        if (!ENABLE_SPAWNING) {
            // Keep silent; you asked for a toggle you can flip without log spam.
            // If you want visibility, temporarily add a throttled log here.
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

            // Keep search radius inside singleton radius to prevent "spawn outside the singleton AABB" spam.
            final int effectiveSearchRadius = computeEffectiveSearchRadius();
            if (effectiveSearchRadius <= 0) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0) {
                    LOG.warn("[RavenSpawnEvents] effectiveSearchRadius <= 0 (SEARCH_RADIUS_BLOCKS={}, SINGLETON_RADIUS={}). Spawning disabled.",
                            SEARCH_RADIUS_BLOCKS, SINGLETON_RADIUS);
                }
                return;
            }

            for (Player player : players) {
                if (player == null || player.isSpectator()) {
                    continue;
                }

                // Sky-test safety: don't spawn when player is far above terrain.
                if (isPlayerTooHighAboveSurface(level, player, gameTime)) {
                    continue;
                }

                // Per-player cooldown: prevents spam even if singleton logic fails (and also reduces noise).
                if (isPlayerOnSpawnCooldown(player, gameTime)) {
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

                BlockPos spawnPos = findLeavesTopSpawnPos(level, player, rnd, effectiveSearchRadius);
                if (spawnPos == null) {
                    if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0) {
                        LOG.info("[RavenSpawnEvents] No valid leaves-top spawn found near player {} (effectiveRadius={} blocks).",
                                safeName(player), effectiveSearchRadius);
                    }
                    continue;
                }

                if (spawnRaven(level, ravenType, spawnPos)) {
                    // Record cooldown
                    LAST_SPAWN_TICK_BY_PLAYER.put(player.getUUID(), gameTime);

                    // Throttle log spam
                    if ((gameTime % SPAWN_LOG_INTERVAL_TICKS) == 0) {
                        LOG.info("[RavenSpawnEvents] Spawned raven at {} near player {}", spawnPos, safeName(player));
                    } else if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0) {
                        LOG.debug("[RavenSpawnEvents] Spawned raven at {} near player {}", spawnPos, safeName(player));
                    }
                }
            }

        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] onLevelTickPost failed", t);
        }
    }

    private static int computeEffectiveSearchRadius() {
        try {
            // We must ensure any spawn found is inside SINGLETON_RADIUS,
            // otherwise you can spawn a raven outside the singleton box and then spawn another next tick.
            int maxInsideSingleton = (int) Math.floor(SINGLETON_RADIUS) - 8; // small buffer
            if (maxInsideSingleton <= 0) {
                return 0;
            }
            return Math.max(1, Math.min(SEARCH_RADIUS_BLOCKS, maxInsideSingleton));
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] computeEffectiveSearchRadius failed", t);
            return 0;
        }
    }

    private static boolean isPlayerOnSpawnCooldown(Player player, long gameTime) {
        try {
            UUID id = player.getUUID();
            Long last = LAST_SPAWN_TICK_BY_PLAYER.get(id);
            if (last == null) {
                return false;
            }
            long dt = gameTime - last;
            if (dt < 0) {
                // world time weirdness; reset
                LAST_SPAWN_TICK_BY_PLAYER.remove(id);
                return false;
            }
            return dt < PER_PLAYER_SPAWN_COOLDOWN_TICKS;
        } catch (Throwable t) {
            LOG.error("[RavenSpawnEvents] isPlayerOnSpawnCooldown failed", t);
            // Fail-safe: if in doubt, don't spawn spam
            return true;
        }
    }

    private static boolean isPlayerTooHighAboveSurface(net.minecraft.server.level.ServerLevel level, Player player, long gameTime) {
        try {
            BlockPos p = player.blockPosition();

            int surfaceY;
            try {
                surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, p.getX(), p.getZ());
            } catch (Throwable t) {
                // If height query fails, be conservative and don't spawn
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0) {
                    LOG.debug("[RavenSpawnEvents] Surface height query failed for player {} at xz=({},{}). Spawning skipped.",
                            safeName(player), p.getX(), p.getZ());
                }
                return true;
            }

            int playerY = p.getY();
            int above = playerY - surfaceY;

            if (above > MAX_PLAYER_HEIGHT_ABOVE_SURFACE) {
                if ((gameTime % DEBUG_LOG_INTERVAL_TICKS) == 0) {
                    LOG.info("[RavenSpawnEvents] Player {} is too high above surface (playerY={}, surfaceY={}, delta={} > {}). Spawning disabled for this player.",
                            safeName(player), playerY, surfaceY, above, MAX_PLAYER_HEIGHT_ABOVE_SURFACE);
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
        } catch (Throwable ignored) {}
        return "<unknown>";
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

    private static BlockPos findLeavesTopSpawnPos(net.minecraft.server.level.ServerLevel level, Player player, RandomSource rnd, int effectiveSearchRadius) {
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

                // One more small safety: ensure spawn position is still within singleton radius from player.
                // (Should be guaranteed by effectiveSearchRadius, but keep it robust.)
                double dxp = (spawnPos.getX() + 0.5D) - (origin.getX() + 0.5D);
                double dzp = (spawnPos.getZ() + 0.5D) - (origin.getZ() + 0.5D);
                double dist = Math.sqrt(dxp * dxp + dzp * dzp);
                if (dist > SINGLETON_RADIUS - 1.0D) {
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
