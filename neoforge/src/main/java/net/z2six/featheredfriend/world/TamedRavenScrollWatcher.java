// neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenScrollWatcher.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.modules.RavenSoundEngine;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import net.z2six.featheredfriend.entity.raven.modules.Teleportation;
import net.z2six.featheredfriend.registry.FFNeoForgeEntities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenScrollWatcher.java
 *
 * Behavior:
 *  - When a player holds a sealed scroll in MAIN HAND and has stored TamedRaven data:
 *      * Ensure there is exactly ONE "scroll-summoned" RavenEntity for that player.
 *      * On "start holding" edge (was not holding, now holding):
 *          - Despawn any existing scroll-summoned ravens for that player
 *            with TamedRaven's fade-out + Enderpop + feather FX.
 *          - Spawn a fresh raven with Enderpop-style spawn FX (no feather FX).
 *  - While the player keeps holding the sealed scroll:
 *      * If the raven somehow dies, we respawn a new one (single instance).
 *      * If duplicates exist (from previous bugs), we keep the closest and despawn the rest.
 *  - When the player stops holding the sealed scroll or loses the tamed raven:
 *      * We despawn all scroll-summoned ravens with TamedRaven's fade-out FX.
 *
 * Implementation:
 *  - Detection of "scroll-summoned" ravens is via scoreboard tag + owner UUID:
 *      * Scoreboard tag:  "ff_scroll_summoned"
 *      * Owner:           raven.getOwnerUUID() == playerUUID
 *  - No entity-id-based spawn logic; we derive the state from the world every tick.
 *  - Edge detection of "start holding" is robust across reloads:
 *      * We store LAST_HOLDING_SEALED_SCROLL per player UUID.
 *      * On a fresh player entity (player.tickCount == 0), we force wasHolding=false
 *        so a player always counts as “newly holding” on join if they have the scroll selected.
 *
 * Spawn FX:
 *  - Enderpop-like portal particles + enderman teleport sound.
 *  - NO feather FX on spawn (those are despawn-only via TamedRaven).
 *
 * Despawn FX:
 *  - Uses TamedRaven.beginDespawnWithFx, which is assumed to:
 *      * Fade out the raven.
 *      * Play Enderpop + feather particles.
 *
 * Important implementation detail:
 *  - As soon as we request despawn of a scroll-summoned raven, we remove:
 *      * The scoreboard tag "ff_scroll_summoned".
 *      * The NBT flags ScrollSummoned / ScrollSummonedOwner.
 *    This prevents repeated despawn calls while the fade-out is in progress.
 */
public final class TamedRavenScrollWatcher {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Registry name of the sealed scroll item.
     */
    private static final ResourceLocation SEALED_SCROLL_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "scroll_sealed");

    /**
     * Scoreboard tag used to mark scroll-summoned ravens.
     */
    private static final String TAG_SCROLL_SUMMONED = "ff_scroll_summoned";

    /**
     * Tracks whether each player was holding the sealed scroll on the previous tick.
     * Keyed by player UUID, survives across dimension changes and reconnects (on dedicated).
     */
    private static final Map<UUID, Boolean> LAST_HOLDING_SEALED_SCROLL = new ConcurrentHashMap<>();

    private TamedRavenScrollWatcher() {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Public registration
    // ---------------------------------------------------------------------

    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(TamedRavenScrollWatcher::onPlayerTick);
            LOG.info("[TamedRavenScrollWatcher] Registered PlayerTickEvent.Post listener");
        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] Failed to register PlayerTickEvent listener", t);
        }
    }

    // ---------------------------------------------------------------------
    // Tick handler
    // ---------------------------------------------------------------------

    private static void onPlayerTick(@NotNull PlayerTickEvent.Post event) {
        try {
            Player player = event.getEntity();
            if (player == null) {
                return;
            }

            Level level = player.level();
            if (!(level instanceof ServerLevel serverLevel) || level.isClientSide()) {
                // Only run on logical server.
                return;
            }

            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }

            UUID playerId = player.getUUID();

            boolean holdingNow = isHoldingSealedScroll(player);

            // Edge detection: on a fresh player entity (tickCount == 0), we treat as if
            // they were NOT holding last tick, regardless of previous map contents.
            boolean wasHoldingPrev = LAST_HOLDING_SEALED_SCROLL.getOrDefault(playerId, false);
            boolean wasHolding = (player.tickCount == 0) ? false : wasHoldingPrev;

            LAST_HOLDING_SEALED_SCROLL.put(playerId, holdingNow);

            // Read tamed raven data from player persistent data.
            TamedRavenInfo info = readTamedRavenInfo(player);
            boolean hasTamedRaven = info != null && info.hasTamedRaven;
            String ravenName = (info != null && info.ravenName != null && !info.ravenName.isEmpty())
                    ? info.ravenName
                    : "Raven";

            // Discover all scroll-summoned ravens for this player in the world via tag + owner UUID.
            List<RavenEntity> scrollRavens = findScrollSummonedRavensForPlayer(serverLevel, serverPlayer);

            // If player is gone or dead, just clean up any scroll ravens and bail.
            if (!player.isAlive() || player.isRemoved()) {
                if (!scrollRavens.isEmpty()) {
                    for (RavenEntity r : scrollRavens) {
                        despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "player dead/removed");
                    }
                }
                LAST_HOLDING_SEALED_SCROLL.remove(playerId);
                return;
            }

            // If the player does NOT have a tamed raven stored, they should never
            // have a scroll-summoned one. Despawn any that exist.
            if (!hasTamedRaven) {
                if (!scrollRavens.isEmpty()) {
                    for (RavenEntity r : scrollRavens) {
                        despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "no stored tamed raven");
                    }
                }
                return;
            }

            // If the player is NOT holding the sealed scroll, despawn all scroll-summoned ravens.
            if (!holdingNow) {
                if (!scrollRavens.isEmpty()) {
                    for (RavenEntity r : scrollRavens) {
                        despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "stopped holding sealed scroll");
                    }
                }
                return;
            }

            // From here on: player is alive, has a stored tamed raven AND is holding the scroll.

            // CASE 1: "Start holding" edge (was not holding, now holding).
            // We ALWAYS despawn any existing scroll ravens and spawn a fresh one.
            if (!wasHolding && holdingNow) {
                if (!scrollRavens.isEmpty()) {
                    for (RavenEntity r : scrollRavens) {
                        despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "start holding scroll (respawn)");
                    }
                }

                RavenEntity spawned = spawnSummonedRaven(serverLevel, serverPlayer, ravenName);
                if (spawned != null) {
                    LOG.info("[TamedRavenScrollWatcher] Start-hold: spawned scroll-raven id={} for player='{}' at {}",
                            spawned.getId(), safePlayerName(player), spawned.position());
                }
                return;
            }

            // CASE 2: Continuous holding:
            //   - If none exist -> respawn.
            //   - If more than one exists -> keep closest, despawn rest.
            //   - If exactly one -> ensure correct name, do nothing else.
            if (scrollRavens.isEmpty()) {
                RavenEntity spawned = spawnSummonedRaven(serverLevel, serverPlayer, ravenName);
                if (spawned != null && (serverPlayer.tickCount % 40 == 0)) {
                    LOG.info("[TamedRavenScrollWatcher] Continuous-hold: respawned scroll-raven id={} for player='{}' at {}",
                            spawned.getId(), safePlayerName(player), spawned.position());
                }
                return;
            }

            if (scrollRavens.size() > 1) {
                // Keep the closest raven as the "primary" one, despawn the rest.
                RavenEntity primary = pickClosestRaven(scrollRavens, serverPlayer);
                for (RavenEntity r : scrollRavens) {
                    if (r == primary) {
                        continue;
                    }
                    despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "deduplicate scroll ravens");
                }
                ensureRavenName(primary, ravenName);
                return;
            }

            // Exactly one scroll-raven exists; just ensure its name is correct.
            ensureRavenName(scrollRavens.get(0), ravenName);

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] onPlayerTick failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // World scanning helpers
    // ---------------------------------------------------------------------

    /**
     * Return all ravens in a radius around the player that:
     *  - Have scoreboard tag TAG_SCROLL_SUMMONED, AND
     *  - Are tamed by this player (owner UUID matches).
     */
    private static List<RavenEntity> findScrollSummonedRavensForPlayer(@NotNull ServerLevel level,
                                                                       @NotNull ServerPlayer owner) {
        List<RavenEntity> out = new ArrayList<>();
        try {
            UUID ownerId = owner.getUUID();
            BlockPos center = owner.blockPosition();
            double radius = 256.0D;

            AABB box = new AABB(center).inflate(radius);

            List<RavenEntity> candidates = level.getEntitiesOfClass(
                    RavenEntity.class,
                    box,
                    e -> e != null && e.isAlive() && !e.isRemoved()
            );

            for (RavenEntity raven : candidates) {
                boolean tagged;
                try {
                    tagged = raven.getTags().contains(TAG_SCROLL_SUMMONED);
                } catch (Throwable t) {
                    LOG.warn("[TamedRavenScrollWatcher] findScrollSummoned: tag check failed for id={}: {}",
                            raven.getId(), t.toString());
                    continue;
                }

                if (!tagged) {
                    continue;
                }

                UUID ravenOwner;
                try {
                    ravenOwner = raven.getOwnerUUID();
                } catch (Throwable t) {
                    LOG.warn("[TamedRavenScrollWatcher] findScrollSummoned: getOwnerUUID failed for id={}: {}",
                            raven.getId(), t.toString());
                    continue;
                }

                if (ravenOwner == null || !ravenOwner.equals(ownerId)) {
                    continue;
                }

                out.add(raven);
            }

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] findScrollSummonedRavensForPlayer failed safely", t);
        }
        return out;
    }

    @Nullable
    private static RavenEntity pickClosestRaven(@NotNull List<RavenEntity> ravens, @NotNull ServerPlayer owner) {
        if (ravens.isEmpty()) {
            return null;
        }
        try {
            Vec3 pos = owner.position();
            return ravens.stream()
                    .min(Comparator.comparingDouble(r -> r.position().distanceToSqr(pos)))
                    .orElse(ravens.get(0));
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] pickClosestRaven failed safely: {}", t.toString());
            return ravens.get(0);
        }
    }

    // ---------------------------------------------------------------------
    // Spawn / despawn
    // ---------------------------------------------------------------------

    @Nullable
    private static RavenEntity spawnSummonedRaven(@NotNull ServerLevel level,
                                                  @NotNull ServerPlayer owner,
                                                  @NotNull String ravenName) {
        try {
            RavenEntity raven = FFNeoForgeEntities.RAVEN.get().create(level);
            if (raven == null) {
                LOG.error("[TamedRavenScrollWatcher] spawnSummonedRaven: entity factory returned null");
                return null;
            }

            // Preferred: spawn ~15 blocks above the player in a 3x3x2 air column.
            Vec3 spawnPos = findSafeSpawnAbovePlayer(level, owner);
            if (spawnPos != null) {
                raven.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, owner.getYRot(), 0.0F);
            } else {
                // Fallback: slightly in front of the player's face.
                Vec3 playerPos = owner.position();
                Vec3 look = owner.getLookAngle();
                double lx = look.x;
                double lz = look.z;
                double len = Math.sqrt(lx * lx + lz * lz);
                if (len < 1.0E-4D) {
                    lx = 1.0D;
                    lz = 0.0D;
                    len = 1.0D;
                }
                lx /= len;
                lz /= len;

                double distance = 1.25D;
                double sx = playerPos.x + lx * distance;
                double sz = playerPos.z + lz * distance;
                double sy = owner.getEyeY() + 0.1D;

                Vec3 fallback = new Vec3(sx, sy, sz);
                raven.moveTo(fallback.x, fallback.y, fallback.z, owner.getYRot(), 0.0F);

                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: using fallback spawn={} for player='{}'",
                        fallback, safePlayerName(owner));
            }

            // Tame and bind to owner.
            try {
                // 1.21 TamableAnimal#setTame(boolean tame, boolean broadcastEvent)
                raven.setTame(true, true);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: setTame(true, true) failed safely: {}", t.toString());
            }
            try {
                raven.setOwnerUUID(owner.getUUID());
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: setOwnerUUID failed safely: {}", t.toString());
            }

            // Name & show name.
            ensureRavenName(raven, ravenName);

            // Tag as scroll-summoned via persistent data.
            try {
                CompoundTag root = raven.getPersistentData();
                CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
                ffTag.putBoolean("ScrollSummoned", true);
                ffTag.putString("ScrollSummonedOwner", owner.getUUID().toString());
                root.put(Constants.MOD_ID, ffTag);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: persistent ScrollSummoned tag failed safely: {}", t.toString());
            }

            // Tag via scoreboard tag: primary detection mechanism.
            try {
                raven.addTag(TAG_SCROLL_SUMMONED);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: addTag({}) failed safely: {}", TAG_SCROLL_SUMMONED, t.toString());
            }

            // Actually add to world.
            level.addFreshEntity(raven);

            // Play spawn FX: portal (Enderpop-ish) + enderman teleport sound, NO feathers.
            playScrollSummonSpawnFx(level, owner, raven);

            LOG.info("[TamedRavenScrollWatcher] spawnSummonedRaven: spawned id={} name='{}' for player='{}' at {}",
                    raven.getId(), ravenName, safePlayerName(owner), raven.position());

            return raven;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] spawnSummonedRaven failed safely", t);
            return null;
        }
    }

    /**
     * Play Enderpop-style spawn FX:
     *  - Portal particles (no feathers) at the raven.
     *  - Enderman teleport sound at the raven.
     *  - Raven wing woosh at the raven.
     *  - Player whistle at the *player's* position.
     */
    private static void playScrollSummonSpawnFx(@NotNull ServerLevel level,
                                                @NotNull ServerPlayer owner,
                                                @NotNull RavenEntity raven) {
        try {
            Vec3 ravenPos = raven.position();
            Vec3 playerPos = owner.position();

            // Portal particles around the raven
            level.sendParticles(
                    ParticleTypes.PORTAL,
                    ravenPos.x,
                    ravenPos.y + 0.4D,
                    ravenPos.z,
                    40,
                    0.3D,
                    0.4D,
                    0.3D,
                    0.02D
            );

            // Enderman-style teleport sound at the raven
            level.playSound(
                    null,
                    ravenPos.x,
                    ravenPos.y,
                    ravenPos.z,
                    SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.NEUTRAL,
                    1.0F,
                    1.0F + (level.random.nextFloat() - 0.5F) * 0.2F
            );

            // Player whistle at the player's position (so it feels like the player is "calling" the raven)
            RavenSoundEngine.playAt(
                    level,
                    "featheredfriend:raven.whistle",
                    SoundSource.NEUTRAL,
                    playerPos,
                    0.35F,
                    1.0F
            );

        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] playScrollSummonSpawnFx failed safely: {}", t.toString());
        }
    }

    /**
     * Despawn ONE scroll-summoned raven via TamedRaven.beginDespawnWithFx,
     * falling back to discard() if anything goes wrong.
     *
     * IMPORTANT:
     *  - After we request despawn, we immediately clear:
     *      * The scoreboard tag TAG_SCROLL_SUMMONED.
     *      * The NBT flags ScrollSummoned / ScrollSummonedOwner.
     *    This prevents repeated despawn calls while the fade-out is still running.
     */
    private static void despawnOneScrollSummonedRaven(@NotNull ServerLevel level,
                                                      @NotNull ServerPlayer owner,
                                                      @NotNull RavenEntity raven,
                                                      @NotNull String reason) {
        try {
            TamedRaven tamedModule = null;
            try {
                tamedModule = raven.getTamedRavenModule();
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: getTamedRavenModule failed safely: {}", t.toString());
            }

            String ravenName = "<unnamed>";
            try {
                if (raven.getCustomName() != null) {
                    ravenName = raven.getCustomName().getString();
                }
            } catch (Throwable ignored) {
            }

            // Trigger a one-shot Enderpop FX burst (particles + raven teleport/woosh sounds)
            // at the despawn position, reusing the Teleportation module's logic.
            try {
                Vec3 fxPos = raven.position().add(0.0D, 0.6D, 0.0D);
                long fxSeed =
                        raven.getUUID().getLeastSignificantBits()
                                ^ (long) raven.tickCount
                                ^ 0x5C829867; // just a fixed salt for scroll-despawn
                Teleportation teleportFx = new Teleportation(raven);
                teleportFx.spawnEnderpopBurst(
                        level,
                        fxPos.x,
                        fxPos.y,
                        fxPos.z,
                        fxSeed,
                        "scroll-despawn: " + reason,
                        raven
                );
            } catch (Throwable fxErr) {
                LOG.warn("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: Teleportation FX failed safely for id={}: {}",
                        raven.getId(), fxErr.toString());
            }

            // Request fade-out FX via module if present.
            if (tamedModule != null) {
                try {
                    tamedModule.beginDespawnWithFx(level, owner, ravenName);
                    LOG.info("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: triggered despawn FX for id={} name='{}' player='{}' reason={}",
                            raven.getId(), ravenName, safePlayerName(owner), reason);
                } catch (Throwable t) {
                    LOG.error("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: beginDespawnWithFx failed; discarding raven directly. err={}",
                            t.toString());
                    raven.discard();
                }
            } else {
                LOG.warn("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: TamedRaven module null; discarding raven without FX. player='{}' id={} reason={}",
                        safePlayerName(owner), raven.getId(), reason);
                raven.discard();
            }

            // --- CRUCIAL: stop treating this raven as scroll-summoned from now on ---

            // 1) Remove scoreboard tag so we no longer pick it up in findScrollSummonedRavensForPlayer.
            try {
                if (raven.getTags().contains(TAG_SCROLL_SUMMONED)) {
                    raven.removeTag(TAG_SCROLL_SUMMONED);
                }
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: removeTag({}) failed safely for id={}: {}",
                        TAG_SCROLL_SUMMONED, raven.getId(), t.toString());
            }

            // 2) Clear NBT flags used for scroll-summon bookkeeping.
            try {
                CompoundTag root = raven.getPersistentData();
                if (root != null) {
                    CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
                    if (ffTag != null && !ffTag.isEmpty()) {
                        ffTag.remove("ScrollSummoned");
                        ffTag.remove("ScrollSummonedOwner");
                        root.put(Constants.MOD_ID, ffTag);
                    }
                }
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: clearing ScrollSummoned NBT failed safely for id={}: {}",
                        raven.getId(), t.toString());
            }

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Spawn position helpers
    // ---------------------------------------------------------------------

    @Nullable
    private static Vec3 findSafeSpawnAbovePlayer(@NotNull ServerLevel level, @NotNull ServerPlayer owner) {
        try {
            Vec3 playerPos = owner.position();
            Vec3 look = owner.getLookAngle();

            double lx = look.x;
            double lz = look.z;
            double len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1.0E-4D) {
                lx = 1.0D;
                lz = 0.0D;
                len = 1.0D;
            }
            lx /= len;
            lz /= len;

            // Horizontal offset in front of the player.
            final double horizontalDistance = 1.25D;
            double baseX = playerPos.x + lx * horizontalDistance;
            double baseZ = playerPos.z + lz * horizontalDistance;

            // Preferred vertical offset (~15 blocks above).
            final int preferredOffsetY = 15;
            final int scanUp = 8;
            final int scanDown = 8;

            int baseY = Mth.floor(owner.getY() + preferredOffsetY + 0.5D);

            int minY = level.getMinBuildHeight() + 2;
            int maxY = level.getMaxBuildHeight() - 2;

            baseY = Mth.clamp(baseY, minY, maxY);

            int cx = Mth.floor(baseX + 0.5D);
            int cz = Mth.floor(baseZ + 0.5D);

            int maxDelta = Math.max(scanUp, scanDown);

            for (int dy = 0; dy <= maxDelta; dy++) {
                int[] candidates = (dy == 0)
                        ? new int[]{baseY}
                        : new int[]{baseY + dy, baseY - dy};

                for (int y : candidates) {
                    if (y < minY || y > maxY) {
                        continue;
                    }

                    if (is3x3x2Air(level, cx, y, cz)) {
                        double sy = y + 0.1D;
                        Vec3 spawn = new Vec3(cx + 0.5D, sy, cz + 0.5D);

                        LOG.info("[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer: chosen spawn={} for player='{}' (baseY={}, dy={})",
                                spawn, safePlayerName(owner), baseY, dy);

                        return spawn;
                    }
                }
            }

            LOG.warn("[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer: no 3x3x2 air column found near player='{}' (baseY={})",
                    safePlayerName(owner), baseY);

            return null;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer failed safely", t);
            return null;
        }
    }

    private static boolean is3x3x2Air(@NotNull ServerLevel level, int cx, int cy, int cz) {
        try {
            for (int dy = 0; dy <= 1; dy++) {
                int y = cy + dy;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                        if (!level.isEmptyBlock(pos)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] is3x3x2Air failed safely: {}", t.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Tamed raven info
    // ---------------------------------------------------------------------

    private static final class TamedRavenInfo {
        final boolean hasTamedRaven;
        final String ravenName;

        TamedRavenInfo(boolean hasTamedRaven, String ravenName) {
            this.hasTamedRaven = hasTamedRaven;
            this.ravenName = ravenName;
        }
    }

    /**
     * Reads the same structure stored by TamedRaven.storeTamedRavenForPlayer:
     *
     *   root = player.getPersistentData()
     *   ffTag = root.getCompound(Constants.MOD_ID)
     *   ravenTag = ffTag.getCompound("TamedRaven")
     *     - HasTamedRaven : boolean
     *     - RavenName     : string
     */
    @Nullable
    private static TamedRavenInfo readTamedRavenInfo(@NotNull Player player) {
        try {
            CompoundTag root = player.getPersistentData();
            if (root == null) {
                return null;
            }

            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return null;
            }

            if (!ffTag.contains("TamedRaven", Tag.TAG_COMPOUND)) {
                return null;
            }

            CompoundTag ravenTag = ffTag.getCompound("TamedRaven");
            if (ravenTag == null || ravenTag.isEmpty()) {
                return null;
            }

            boolean has = ravenTag.getBoolean("HasTamedRaven");
            String name = ravenTag.getString("RavenName");
            return new TamedRavenInfo(has, name);

        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] readTamedRavenInfo failed safely: {}", t.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Simple helpers
    // ---------------------------------------------------------------------

    private static void ensureRavenName(@NotNull RavenEntity raven, @NotNull String ravenName) {
        try {
            Component cur = raven.getCustomName();
            String curStr = (cur == null) ? "" : cur.getString();
            if (!ravenName.equals(curStr)) {
                raven.setCustomName(Component.literal(ravenName));
            }
            raven.setCustomNameVisible(true);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] ensureRavenName failed safely for id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    /**
     * Returns true if the player's main hand item is the sealed scroll.
     */
    private static boolean isHoldingSealedScroll(@NotNull Player player) {
        try {
            ItemStack main = player.getMainHandItem();
            if (main == null || main.isEmpty()) {
                return false;
            }

            ResourceLocation key = BuiltInRegistries.ITEM.getKey(main.getItem());
            if (key == null) {
                return false;
            }

            return SEALED_SCROLL_ID.equals(key);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] isHoldingSealedScroll failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return false;
        }
    }

    private static String safePlayerName(@NotNull Player player) {
        try {
            return player.getGameProfile().getName();
        } catch (Throwable ignored) {
            try {
                return player.getName().getString();
            } catch (Throwable ignored2) {
                return "<unknown>";
            }
        }
    }
}
