// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierRuntime.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.RavenVariant;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import net.z2six.featheredfriend.entity.raven.modules.Teleportation;
import net.z2six.featheredfriend.registry.FFNeoForgeEntities;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierRuntime.java
 *
 * Server-side runtime for raven courier deliveries.
 *
 * Updated behavior (Dec 2025 changes):
 *  - Spawn logic is cave-safe:
 *      * We scan up to +15 blocks above player.
 *      * If ceiling exists within 15 -> spawn under ceiling.
 *      * If no ceiling within 15 -> spawn around +15.
 *  - Delivery completion:
 *      * Only complete when a player RMBs and we hand out (or drop) the scroll.
 *      * If raven dies: drop scroll + remove job (complete).
 *      * If raven times out / fails (non-death): mark job failed in RavenCourierData; do NOT remove job; do NOT drop scroll.
 *  - Courier raven name is just RavenName (no "Player's RavenName").
 */
public final class RavenCourierRuntime {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Scoreboard tag used to mark courier ravens.
     */
    private static final String TAG_COURIER_RAVEN = "ff_courier_raven";

    /**
     * Registry name of the sealed scroll item.
     */
    private static final ResourceLocation SEALED_SCROLL_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "scroll_sealed");

    /**
     * Lifetime of a courier raven in ticks.
     * 60 seconds * 20 ticks per second = 1200 ticks.
     */
    private static final long COURIER_LIFETIME_TICKS = 60L * 20L;

    /**
     * NBT key used to store the lifetime deadline for courier ravens.
     */
    private static final String NBT_COURIER_DESPAWN_AT = "CourierDespawnAt";

    /**
     * How often (in server ticks) we attempt to dispatch new courier ravens.
     * 20 ticks = once per second.
     */
    private static final int DISPATCH_INTERVAL_TICKS = 20;

    /**
     * Hard cap on how many new courier ravens we will spawn in a single dispatch pass.
     */
    private static final int MAX_NEW_DISPATCHES_PER_PASS = 4;

    /**
     * Simple server tick counter for throttling the dispatch logic.
     */
    private static int serverTickCounter = 0;

    private RavenCourierRuntime() {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Public registration
    // ---------------------------------------------------------------------

    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(RavenCourierRuntime::onServerTick);
            NeoForge.EVENT_BUS.addListener(RavenCourierRuntime::onEntityInteract);
            NeoForge.EVENT_BUS.addListener(RavenCourierRuntime::onRavenDeath);
            LOG.info("[RavenCourierRuntime] Registered server tick + interaction + death listeners");
        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] Failed to register event listeners", t);
        }
    }

    // ---------------------------------------------------------------------
    // Public API: retry support (for future GUI)
    // ---------------------------------------------------------------------

    /**
     * Sender-triggered retry for a failed courier job.
     *
     * Rules:
     *  - Job must exist.
     *  - Sender UUID must match job.senderUuid.
     *  - Job must be marked failed (otherwise this is a no-op false).
     *  - Job must not be inFlight.
     *
     * Effects:
     *  - Clears failed status.
     *  - Leaves job pending so the regular dispatcher will spawn a courier again.
     *  - Tries an immediate dispatch pass (best effort).
     */
    public static boolean requestRetryDelivery(MinecraftServer server, UUID senderUuid, long jobId) {
        try {
            if (server == null) {
                return false;
            }
            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                LOG.warn("[RavenCourierRuntime] requestRetryDelivery: overworld is null (jobId={})", jobId);
                return false;
            }

            RavenCourierData data = RavenCourierData.get(overworld);
            boolean ok = data.clearFailedForRetry(jobId, senderUuid);

            if (ok) {
                LOG.info("[RavenCourierRuntime] requestRetryDelivery: sender={} requested retry for jobId={}", senderUuid, jobId);
                try {
                    // Best-effort: dispatch immediately so UI feels responsive.
                    dispatchPendingDeliveries(server);
                } catch (Throwable dispatchErr) {
                    LOG.warn("[RavenCourierRuntime] requestRetryDelivery: immediate dispatch failed safely: {}", dispatchErr.toString());
                }
            } else {
                LOG.warn("[RavenCourierRuntime] requestRetryDelivery: retry rejected (sender={} jobId={})", senderUuid, jobId);
            }

            return ok;
        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] requestRetryDelivery failed safely (sender={} jobId={})", senderUuid, jobId, t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Server tick: periodic dispatch
    // ---------------------------------------------------------------------

    private static void onServerTick(ServerTickEvent.Post event) {
        try {
            MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }

            serverTickCounter++;
            if ((serverTickCounter % DISPATCH_INTERVAL_TICKS) != 0) {
                return;
            }

            dispatchPendingDeliveries(server);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onServerTick failed safely", t);
        }
    }

    private static void dispatchPendingDeliveries(MinecraftServer server) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                LOG.warn("[RavenCourierRuntime] dispatchPendingDeliveries: overworld is null; aborting dispatch.");
                return;
            }

            RavenCourierData data = RavenCourierData.get(overworld);
            List<RavenCourierData.DeliveryJob> allJobs = data.getAllJobsFlat();

            Set<UUID> busySenders = new HashSet<>();
            Set<UUID> busyRecipients = new HashSet<>();

            reconcileJobsWithExistingCourierRavens(server, overworld, data, allJobs, busySenders, busyRecipients);

            // Respect jobs marked inFlight
            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job == null) {
                    continue;
                }
                RavenCourierData.DeliveryJob liveJob = data.getJobById(job.jobId);
                if (liveJob == null) {
                    continue;
                }
                if (!liveJob.inFlight) {
                    continue;
                }
                if (liveJob.senderUuid != null) {
                    busySenders.add(liveJob.senderUuid);
                }
                if (liveJob.recipientUuid != null) {
                    busyRecipients.add(liveJob.recipientUuid);
                }
            }

            if (allJobs.isEmpty()) {
                return;
            }

            int dispatchedCount = 0;

            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job == null) {
                    continue;
                }

                RavenCourierData.DeliveryJob liveJob = data.getJobById(job.jobId);
                if (liveJob == null) {
                    continue;
                }
                job = liveJob;

                // Skip failed jobs until sender triggers retry.
                if (job.failed) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("[RavenCourierRuntime] dispatch: jobId={} is failed; skipping until retry.", job.jobId);
                    }
                    continue;
                }

                if (job.inFlight) {
                    continue;
                }

                UUID senderId = job.senderUuid;
                UUID recipientId = job.recipientUuid;
                if (recipientId == null) {
                    continue;
                }

                if ((senderId != null && busySenders.contains(senderId)) || busyRecipients.contains(recipientId)) {
                    continue;
                }

                ServerPlayer recipient = server.getPlayerList().getPlayer(recipientId);
                if (recipient == null) {
                    continue;
                }

                ServerLevel targetLevel = recipient.serverLevel();
                if (targetLevel == null || targetLevel.isClientSide()) {
                    continue;
                }

                if (hasActiveCourierRavenForJob(server, job.jobId)) {
                    job.inFlight = true;
                    data.setDirty();

                    if (senderId != null) {
                        busySenders.add(senderId);
                    }
                    busyRecipients.add(recipientId);

                    LOG.warn("[RavenCourierRuntime] dispatch: jobId={} already has an active courier raven; skipping duplicate spawn.", job.jobId);
                    continue;
                }

                RavenEntity raven = spawnCourierRavenForJob(targetLevel, recipient, job);
                if (raven == null) {
                    continue;
                }

                job.inFlight = true;
                data.setDirty();

                if (senderId != null) {
                    busySenders.add(senderId);
                }
                busyRecipients.add(recipientId);

                dispatchedCount++;
                if (dispatchedCount >= MAX_NEW_DISPATCHES_PER_PASS) {
                    break;
                }
            }

            if (dispatchedCount > 0 && LOG.isInfoEnabled()) {
                LOG.info("[RavenCourierRuntime] Dispatched {} courier raven(s) this tick.", dispatchedCount);
            }

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] dispatchPendingDeliveries failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Courier raven spawn helpers
    // ---------------------------------------------------------------------

    private static boolean hasActiveCourierRavenForJob(MinecraftServer server, long jobId) {
        try {
            if (jobId <= 0L) {
                return false;
            }

            for (ServerLevel level : server.getAllLevels()) {
                try {
                    double minX = level.getWorldBorder().getMinX() - 16.0D;
                    double minZ = level.getWorldBorder().getMinZ() - 16.0D;
                    double maxX = level.getWorldBorder().getMaxX() + 16.0D;
                    double maxZ = level.getWorldBorder().getMaxZ() + 16.0D;
                    double minY = level.getMinBuildHeight() - 1;
                    double maxY = level.getMaxBuildHeight() + 1;

                    AABB worldBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

                    List<RavenEntity> ravens = level.getEntitiesOfClass(
                            RavenEntity.class,
                            worldBox,
                            e -> e != null && e.isAlive() && !e.isRemoved()
                    );

                    for (RavenEntity raven : ravens) {
                        if (!isCourierRaven(raven)) {
                            continue;
                        }
                        long ravenJobId = getCourierJobIdFromRaven(raven);
                        if (ravenJobId == jobId) {
                            return true;
                        }
                    }
                } catch (Throwable levelErr) {
                    LOG.warn("[RavenCourierRuntime] hasActiveCourierRavenForJob: level scan failed safely: {}", levelErr.toString());
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] hasActiveCourierRavenForJob failed safely for jobId={}: {}", jobId, t.toString());
        }
        return false;
    }

    /**
     * Timeout/failure (non-death) handling:
     *  - Mark job as failed in RavenCourierData (persisted).
     *  - Do NOT drop scroll.
     *  - Do NOT remove job.
     *  - Despawn raven (with FX if possible).
     */
    private static void handleCourierRavenTimeout(ServerLevel level,
                                                  RavenCourierData data,
                                                  RavenEntity raven,
                                                  RavenCourierData.DeliveryJob job) {
        try {
            long now = level.getGameTime();

            // Mark as failed (persisted) and unlock inFlight.
            boolean marked = data.markJobFailed(job.jobId, job.recipientUuid, "timeout", now);
            if (!marked) {
                LOG.warn("[RavenCourierRuntime] handleCourierRavenTimeout: failed to mark jobId={} as failed (still despawning raven).", job.jobId);
            }

            // Find a context player for despawn FX (recipient -> sender -> any).
            ServerPlayer contextPlayer = findBestContextPlayer(level.getServer(), job.senderUuid, job.recipientUuid);
            if (contextPlayer != null) {
                despawnCourierRaven(level, contextPlayer, raven, "timeout (job marked failed)");
            } else {
                LOG.warn("[RavenCourierRuntime] handleCourierRavenTimeout: no context player; discarding raven id={} silently.", raven.getId());
                clearCourierFlags(raven);
                raven.discard();
            }

            LOG.info("[RavenCourierRuntime] handleCourierRavenTimeout: jobId={} marked failed; courier raven id={} despawned.", job.jobId, raven.getId());

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] handleCourierRavenTimeout failed safely for jobId={}", job.jobId, t);
            try {
                clearCourierFlags(raven);
                raven.discard();
            } catch (Throwable ignored) {
            }
        }
    }

    private static ServerPlayer findBestContextPlayer(@Nullable MinecraftServer server, @Nullable UUID senderUuid, @Nullable UUID recipientUuid) {
        try {
            if (server == null) {
                return null;
            }
            // Prefer recipient then sender then any.
            if (recipientUuid != null) {
                ServerPlayer p = server.getPlayerList().getPlayer(recipientUuid);
                if (p != null) return p;
            }
            if (senderUuid != null) {
                ServerPlayer p = server.getPlayerList().getPlayer(senderUuid);
                if (p != null) return p;
            }
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                return players.get(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void reconcileJobsWithExistingCourierRavens(MinecraftServer server,
                                                               ServerLevel overworld,
                                                               RavenCourierData data,
                                                               List<RavenCourierData.DeliveryJob> allJobs,
                                                               Set<UUID> busySenders,
                                                               Set<UUID> busyRecipients) {
        try {
            Map<Long, RavenCourierData.DeliveryJob> jobsById = new HashMap<>();
            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job != null) {
                    jobsById.put(job.jobId, job);
                }
            }

            for (ServerLevel level : server.getAllLevels()) {
                try {
                    double minX = level.getWorldBorder().getMinX() - 16.0D;
                    double minZ = level.getWorldBorder().getMinZ() - 16.0D;
                    double maxX = level.getWorldBorder().getMaxX() + 16.0D;
                    double maxZ = level.getWorldBorder().getMaxZ() + 16.0D;
                    double minY = level.getMinBuildHeight() - 1;
                    double maxY = level.getMaxBuildHeight() + 1;

                    AABB worldBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

                    List<RavenEntity> ravens = level.getEntitiesOfClass(
                            RavenEntity.class,
                            worldBox,
                            e -> e != null && e.isAlive() && !e.isRemoved()
                    );

                    for (RavenEntity raven : ravens) {
                        if (!isCourierRaven(raven)) {
                            continue;
                        }

                        long jobId = getCourierJobIdFromRaven(raven);
                        if (jobId <= 0L) {
                            LOG.warn("[RavenCourierRuntime] reconcile: courier raven id={} has no valid CourierJobId; discarding stray courier.", raven.getId());
                            clearCourierFlags(raven);
                            raven.discard();
                            continue;
                        }

                        RavenCourierData.DeliveryJob job = jobsById.get(jobId);
                        if (job == null) {
                            LOG.warn("[RavenCourierRuntime] reconcile: courier raven id={} refers to unknown jobId={}; discarding stray courier.", raven.getId(), jobId);
                            clearCourierFlags(raven);
                            raven.discard();
                            continue;
                        }

                        // If job is already failed, we should not keep a courier raven alive.
                        if (job.failed) {
                            ServerPlayer ctx = findBestContextPlayer(level.getServer(), job.senderUuid, job.recipientUuid);
                            if (ctx != null) {
                                LOG.warn("[RavenCourierRuntime] reconcile: jobId={} is failed but courier raven id={} exists; despawning raven.", job.jobId, raven.getId());
                                job.inFlight = false;
                                data.setDirty();
                                despawnCourierRaven(level, ctx, raven, "job already failed (cleanup)");
                            } else {
                                LOG.warn("[RavenCourierRuntime] reconcile: jobId={} failed; discarding courier raven id={} silently (no context player).", job.jobId, raven.getId());
                                clearCourierFlags(raven);
                                raven.discard();
                            }
                            continue;
                        }

                        try {
                            CompoundTag root = raven.getPersistentData();
                            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);

                            long now = level.getGameTime();
                            long despawnAt = ffTag.getLong(NBT_COURIER_DESPAWN_AT);

                            if (despawnAt <= 0L) {
                                despawnAt = now + COURIER_LIFETIME_TICKS;
                                ffTag.putLong(NBT_COURIER_DESPAWN_AT, despawnAt);
                                root.put(Constants.MOD_ID, ffTag);

                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("[RavenCourierRuntime] reconcile: initializing lifetime for courier raven id={} jobId={} now={} despawnAt={}",
                                            raven.getId(), job.jobId, now, despawnAt);
                                }
                            }

                            if (now >= despawnAt) {
                                LOG.info("[RavenCourierRuntime] reconcile: lifetime expired for courier raven id={} jobId={} now={} despawnAt={}",
                                        raven.getId(), job.jobId, now, despawnAt);

                                // NEW semantics: mark job failed; do NOT remove; do NOT drop scroll.
                                handleCourierRavenTimeout(level, data, raven, job);
                                continue;
                            }
                        } catch (Throwable lifetimeErr) {
                            LOG.warn("[RavenCourierRuntime] reconcile: lifetime check failed safely for raven id={} jobId={}: {}",
                                    raven.getId(), job.jobId, lifetimeErr.toString());
                        }

                        // Still active -> mark in-flight and update busy sets.
                        job.inFlight = true;

                        if (job.senderUuid != null) {
                            busySenders.add(job.senderUuid);
                        }
                        if (job.recipientUuid != null) {
                            busyRecipients.add(job.recipientUuid);
                        }
                    }

                } catch (Throwable levelErr) {
                    LOG.warn("[RavenCourierRuntime] reconcile: level scan failed safely: {}", levelErr.toString());
                }
            }
        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] reconcileJobsWithExistingCourierRavens failed safely", t);
        }
    }

    @Nullable
    private static RavenEntity spawnCourierRavenForJob(ServerLevel level,
                                                       ServerPlayer recipient,
                                                       RavenCourierData.DeliveryJob job) {
        try {
            RavenEntity raven = FFNeoForgeEntities.RAVEN.get().create(level);
            if (raven == null) {
                LOG.error("[RavenCourierRuntime] spawnCourierRavenForJob: entity factory returned null for jobId={}", job.jobId);
                return null;
            }

            // EXACT SAME 6-STEP SPAWN LOGIC as scroll summon:
            // 1) ceiling scan within 15 above player
            // 2) if ceiling, find safe 3x3 pocket under ceiling (not 1 below ceiling)
            // 3) spawn in center of that pocket
            // 4) else fallback to pocket +15
            // 5) simulate A* from spawn -> player
            // 6) if sim fails, do not spawn
            Vec3 spawnPos = findCourierSpawnPos(level, recipient, raven, job.jobId);
            if (spawnPos == null) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: no valid spawn (or simulated path failed) for recipient='{}' jobId={} -> not spawning.",
                        safePlayerName(recipient), job.jobId);
                return null;
            }

            raven.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, recipient.getYRot(), 0.0F);

            try {
                raven.setTame(true, true);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setTame(true,true) failed safely for jobId={}: {}", job.jobId, t.toString());
            }
            try {
                raven.setOwnerUUID(job.recipientUuid);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setOwnerUUID failed safely for jobId={}: {}", job.jobId, t.toString());
            }

            try {
                raven.setRavenVariant(RavenVariant.SCROLL);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setRavenVariant(SCROLL) failed safely for jobId={}: {}", job.jobId, t.toString());
            }

            String ravenName = (job.ravenName != null && !job.ravenName.isEmpty()) ? job.ravenName : "Raven";
            ensureRavenName(raven, ravenName);

            attachCourierJobToRaven(level, raven, job);

            level.addFreshEntity(raven);

            playCourierSpawnFx(level, recipient, raven);

            LOG.info("[RavenCourierRuntime] spawnCourierRavenForJob: spawned courier raven id={} for jobId={} recipient='{}' at {} name='{}'",
                    raven.getId(), job.jobId, job.recipientName, raven.position(), ravenName);

            return raven;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] spawnCourierRavenForJob failed safely for jobId={}", job.jobId, t);
            return null;
        }
    }

    private static void attachCourierJobToRaven(ServerLevel level,
                                                RavenEntity raven,
                                                RavenCourierData.DeliveryJob job) {
        try {
            CompoundTag root = raven.getPersistentData();
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);

            ffTag.putBoolean("CourierActive", true);
            ffTag.putLong("CourierJobId", job.jobId);

            if (job.senderUuid != null) {
                ffTag.putUUID("CourierSenderUUID", job.senderUuid);
            }
            if (job.recipientUuid != null) {
                ffTag.putUUID("CourierRecipientUUID", job.recipientUuid);
            }

            long now = level.getGameTime();
            long despawnAt = now + COURIER_LIFETIME_TICKS;
            ffTag.putLong(NBT_COURIER_DESPAWN_AT, despawnAt);

            root.put(Constants.MOD_ID, ffTag);

            if (LOG.isDebugEnabled()) {
                LOG.debug("[RavenCourierRuntime] attachCourierJobToRaven: lifetime set for raven id={} jobId={} now={} despawnAt={}",
                        raven.getId(), job.jobId, now, despawnAt);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] attachCourierJobToRaven: NBT write failed safely for raven id={}: {}",
                    raven.getId(), t.toString());
        }

        try {
            raven.addTag(TAG_COURIER_RAVEN);
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] attachCourierJobToRaven: addTag({}) failed safely for raven id={}: {}",
                    TAG_COURIER_RAVEN, raven.getId(), t.toString());
        }
    }

    private static void playCourierSpawnFx(ServerLevel level,
                                           ServerPlayer recipient,
                                           RavenEntity raven) {
        try {
            Vec3 ravenPos = raven.position();

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

        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] playCourierSpawnFx failed safely: {}", t.toString());
        }
    }

    // ---------------------------------------------------------------------
    // NEW SPAWN LOGIC (cave-safe) — FIXED
    // ---------------------------------------------------------------------

    /**
     * Spawn policy:
     *  - Scan +1..+15 blocks above player's *eye-height block*.
     *  - We do NOT only scan a single X/Z column; we scan a small horizontal radius around the player.
     *    This matches "pseudo-cave" expectations (e.g., 5x5 chamber) even if the exact column above the player
     *    is air due to micro-offsets, trapdoors, slabs, etc.
     *
     * If we hit a ceiling within that range:
     *  - spawn base = (ceilingY - 2), then search for 3x3x2 air pocket under/near it.
     *
     * If no ceiling is found in the scanned radius:
     *  - spawn base = (playerY + 15), then search for pocket near that.
     */
    @Nullable
    private static Vec3 findCourierSpawnPos(ServerLevel level,
                                            ServerPlayer player,
                                            RavenEntity simRaven,
                                            long jobId) {
        try {
            BlockPos feet = player.blockPosition();
            final int cx = feet.getX();
            final int cz = feet.getZ();
            final int feetY = feet.getY();

            final int minY = level.getMinBuildHeight();
            final int maxY = level.getMaxBuildHeight() - 1;

            // Step 1: Ceiling scan 15 blocks above player (vertical column at (cx,cz))
            int ceilingY = scanFirstCeilingYWithin15(level, player, cx, feetY, cz, minY, maxY);

            // Step 2: If ceiling found, scan for a safe pocket below ceiling (3x3 pocket)
            // IMPORTANT: baseY = ceilingY - 3 (so a 3-high pocket fits under the ceiling)
            if (ceilingY > 0) {
                int baseYUnderCeiling = ceilingY - 3;

                // Clamp so a 3-high pocket fits in world bounds
                if (baseYUnderCeiling < minY) baseYUnderCeiling = minY;
                if (baseYUnderCeiling > maxY - 2) baseYUnderCeiling = maxY - 2;

                Vec3 pocketUnderCeiling = findFirst3x3x3PocketNear(level, player, cx, baseYUnderCeiling, cz, minY, maxY);
                if (pocketUnderCeiling != null) {
                    LOG.info("[RavenCourierRuntime] findCourierSpawnPos: CEILING FOUND at y={} -> pocketUnderCeiling={} jobId={} player='{}'",
                            ceilingY, pocketUnderCeiling, jobId, safePlayerName(player));

                    // Step 5/6: Simulated A* check from spawn to player
                    if (canSimulatePathToPlayer(level, player, pocketUnderCeiling, simRaven, jobId, "courier-under-ceiling")) {
                        // Step 3: Spawn in center of pocket
                        return pocketUnderCeiling;
                    }

                    LOG.warn("[RavenCourierRuntime] findCourierSpawnPos: simulated path FAILED -> not spawning. candidate={} jobId={} player='{}'",
                            pocketUnderCeiling, jobId, safePlayerName(player));
                    return null;
                }

                // Step 4: If no pocket under ceiling, fall through to +15 fallback.
                LOG.warn("[RavenCourierRuntime] findCourierSpawnPos: CEILING FOUND at y={} but NO pocket-under-ceiling found (baseY={}) jobId={} player='{}'",
                        ceilingY, baseYUnderCeiling, jobId, safePlayerName(player));
            }

            // Step 4: Fallback: safe pocket around +15 blocks above player
            final int preferredOffsetY = 15;
            int baseY = Mth.floor(player.getY() + preferredOffsetY + 0.5D);

            // Keep away from build limits; ensure 3-high pocket fits.
            int clampMin = level.getMinBuildHeight() + 2;
            int clampMax = level.getMaxBuildHeight() - 2;
            baseY = Mth.clamp(baseY, clampMin, clampMax);

            if (baseY < minY) baseY = minY;
            if (baseY > maxY - 2) baseY = maxY - 2;

            Vec3 pocket = findFirst3x3x3PocketNear(level, player, cx, baseY, cz, minY, maxY);
            if (pocket != null) {
                LOG.info("[RavenCourierRuntime] findCourierSpawnPos: fallback +15 pocket={} jobId={} player='{}'",
                        pocket, jobId, safePlayerName(player));

                if (canSimulatePathToPlayer(level, player, pocket, simRaven, jobId, "courier-fallback+15")) {
                    return pocket;
                }

                LOG.warn("[RavenCourierRuntime] findCourierSpawnPos: simulated path FAILED -> not spawning. candidate={} jobId={} player='{}'",
                        pocket, jobId, safePlayerName(player));
                return null;
            }

            LOG.warn("[RavenCourierRuntime] findCourierSpawnPos: no pocket found (ceilingY={}, baseY={}) jobId={} player='{}' feet={}",
                    ceilingY, baseY, jobId, safePlayerName(player), feet);
            return null;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] findCourierSpawnPos failed safely (jobId={})", jobId, t);
            return null;
        }
    }

    private static int scanFirstCeilingYWithin15(ServerLevel level,
                                                 ServerPlayer player,
                                                 int x,
                                                 int feetY,
                                                 int z,
                                                 int minY,
                                                 int maxY) {
        try {
            // Ensure the player chunk is available; avoid weird "air" reads from unloaded chunks.
            try {
                BlockPos feet = new BlockPos(x, feetY, z);
                if (!level.hasChunkAt(feet)) {
                    level.getChunk(feet);
                }
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] scanFirstCeilingYWithin15: failed to force-load player chunk at ({},{},{}) player='{}': {}",
                        x, feetY, z, safePlayerName(player), t.toString());
            }

            for (int dy = 1; dy <= 15; dy++) {
                int y = feetY + dy;
                if (y < minY || y > maxY) {
                    break;
                }

                BlockPos probe = new BlockPos(x, y, z);

                try {
                    if (!level.hasChunkAt(probe)) {
                        level.getChunk(probe);
                    }
                } catch (Throwable t) {
                    LOG.warn("[RavenCourierRuntime] scanFirstCeilingYWithin15: chunk load failed at {} (dy={}) player='{}': {}",
                            probe, dy, safePlayerName(player), t.toString());
                }

                BlockState st;
                try {
                    st = level.getBlockState(probe);
                } catch (Throwable t) {
                    LOG.warn("[RavenCourierRuntime] scanFirstCeilingYWithin15: getBlockState failed at {} (dy={}) player='{}': {}",
                            probe, dy, safePlayerName(player), t.toString());
                    continue;
                }

                boolean isAir;
                try {
                    isAir = (st == null) || st.isAir();
                } catch (Throwable t) {
                    isAir = false;
                }

                if (!isAir) {
                    String key = "unknown";
                    try {
                        key = String.valueOf(BuiltInRegistries.BLOCK.getKey(st.getBlock()));
                    } catch (Throwable ignored) {}

                    LOG.info("[RavenCourierRuntime] scanFirstCeilingYWithin15: HIT dy={} y={} block={} feetY={} player='{}'",
                            dy, y, key, feetY, safePlayerName(player));
                    return y;
                }
            }

            return -1;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] scanFirstCeilingYWithin15 failed safely for player='{}'", safePlayerName(player), t);
            return -1;
        }
    }

    @Nullable
    private static Vec3 findFirst3x3x3PocketNear(ServerLevel level,
                                                 ServerPlayer player,
                                                 int cx,
                                                 int baseY,
                                                 int cz,
                                                 int minY,
                                                 int maxY) {
        try {
            // We require a 3x3x3 air pocket for reliability under ceilings (matches what fixed your summon case).
            if (baseY < minY || baseY > (maxY - 2)) {
                LOG.warn("[RavenCourierRuntime] findFirst3x3x3PocketNear: baseY out of bounds for 3-high pocket. baseY={} minY={} maxY={} player='{}'",
                        baseY, minY, maxY, safePlayerName(player));
                return null;
            }

            final int maxR = 4;

            for (int r = 0; r <= maxR; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        // ring scan (skip interior when r>0)
                        if (r > 0 && (Math.abs(dx) != r && Math.abs(dz) != r)) {
                            continue;
                        }

                        int tx = cx + dx;
                        int tz = cz + dz;

                        if (is3x3x3Air(level, tx, baseY, tz)) {
                            Vec3 pocket = new Vec3(tx + 0.5D, baseY + 0.1D, tz + 0.5D);
                            LOG.info("[RavenCourierRuntime] findFirst3x3x3PocketNear: FOUND pocket={} baseY={} off=({}, {}) player='{}'",
                                    pocket, baseY, dx, dz, safePlayerName(player));
                            return pocket;
                        }
                    }
                }
            }

            LOG.info("[RavenCourierRuntime] findFirst3x3x3PocketNear: NONE baseY={} center=({}, {}) radius={} player='{}'",
                    baseY, cx, cz, maxR, safePlayerName(player));
            return null;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] findFirst3x3x3PocketNear failed safely", t);
            return null;
        }
    }

    private static boolean is3x3x3Air(ServerLevel level, int cx, int cy, int cz) {
        try {
            for (int dy = 0; dy <= 2; dy++) {
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
            LOG.warn("[RavenCourierRuntime] is3x3x3Air failed safely: {}", t.toString());
            return false;
        }
    }

    private static boolean canSimulatePathToPlayer(ServerLevel level,
                                                   ServerPlayer player,
                                                   Vec3 spawnPos,
                                                   RavenEntity simRaven,
                                                   long jobId,
                                                   String reasonTag) {
        try {
            // Place the simulation raven at start position (not added to world; pure planning).
            try {
                simRaven.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), 0.0F);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] canSimulatePathToPlayer: moveTo failed safely. player='{}' spawnPos={} jobId={} err={}",
                        safePlayerName(player), spawnPos, jobId, t.toString());
                return false;
            }

            Vec3 goal = player.position();

            long seed;
            try {
                seed =
                        player.getUUID().getMostSignificantBits()
                                ^ player.getUUID().getLeastSignificantBits()
                                ^ (long) level.getGameTime()
                                ^ jobId
                                ^ 0xC0092813;
            } catch (Throwable t) {
                seed = (long) level.getGameTime() ^ jobId ^ 0xC0092813;
            }

            boolean ok;
            try {
                ok = simRaven.simulateAStarPathTo(goal, 6 * 20, seed, "courier(" + jobId + "): " + reasonTag);
            } catch (Throwable t) {
                ok = false;
                LOG.warn("[RavenCourierRuntime] canSimulatePathToPlayer: simulateAStarPathTo threw. player='{}' spawnPos={} goal={} jobId={} err={}",
                        safePlayerName(player), spawnPos, goal, jobId, t.toString());
            }

            if (ok) {
                LOG.info("[RavenCourierRuntime] canSimulatePathToPlayer: A* simulation OK player='{}' spawnPos={} goal={} jobId={}",
                        safePlayerName(player), spawnPos, goal, jobId);
            } else {
                LOG.info("[RavenCourierRuntime] canSimulatePathToPlayer: A* simulation FAIL player='{}' spawnPos={} goal={} jobId={}",
                        safePlayerName(player), spawnPos, goal, jobId);
            }

            return ok;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] canSimulatePathToPlayer failed safely (jobId={})", jobId, t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Player RMB retrieval (courier raven -> sealed scroll)
    // ---------------------------------------------------------------------

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        try {
            Level level = event.getLevel();
            if (!(level instanceof ServerLevel serverLevel) || level.isClientSide()) {
                return;
            }

            Entity target = event.getTarget();
            if (!(target instanceof RavenEntity raven)) {
                return;
            }

            Player player = event.getEntity();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }

            if (!isCourierRaven(raven)) {
                return;
            }

            long jobId = getCourierJobIdFromRaven(raven);
            if (jobId <= 0L) {
                LOG.warn("[RavenCourierRuntime] onEntityInteract: courier raven id={} has no valid CourierJobId; ignoring.", raven.getId());
                return;
            }

            RavenCourierData data = RavenCourierData.get(serverLevel);
            RavenCourierData.DeliveryJob job = data.getJobById(jobId);
            if (job == null) {
                LOG.warn("[RavenCourierRuntime] onEntityInteract: no job found for CourierJobId={} (raven id={})", jobId, raven.getId());
                despawnCourierRaven(serverLevel, serverPlayer, raven, "orphaned courier (no job)");
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            boolean delivered = giveSealedScrollToPlayerFromJob(serverLevel, serverPlayer, job);
            if (!delivered) {
                event.setCancellationResult(InteractionResult.PASS);
                return;
            }

            // COMPLETE ONLY HERE (RMB path)
            data.removeJob(job.jobId, job.recipientUuid);

            despawnCourierRaven(serverLevel, serverPlayer, raven, "delivery complete: scroll retrieved");
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onEntityInteract failed safely", t);
        }
    }

    private static boolean isCourierRaven(RavenEntity raven) {
        try {
            if (raven.getTags().contains(TAG_COURIER_RAVEN)) {
                return true;
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] isCourierRaven: tag check failed for id={}: {}", raven.getId(), t.toString());
        }

        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return false;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            return ffTag != null && ffTag.getBoolean("CourierActive");
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] isCourierRaven: NBT check failed for id={}: {}", raven.getId(), t.toString());
            return false;
        }
    }

    private static long getCourierJobIdFromRaven(RavenEntity raven) {
        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return -1L;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || !ffTag.contains("CourierJobId", Tag.TAG_LONG)) {
                return -1L;
            }
            return ffTag.getLong("CourierJobId");
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] getCourierJobIdFromRaven failed safely for id={}: {}", raven.getId(), t.toString());
            return -1L;
        }
    }

    // ---------------------------------------------------------------------
    // Scroll building / delivery (unchanged)
    // ---------------------------------------------------------------------

    @NotNull
    private static ItemStack buildDeliveredScrollStack(Item sealedScrollItem,
                                                       RavenCourierData.DeliveryJob job,
                                                       @Nullable String deliveredName,
                                                       @Nullable UUID deliveredUuid,
                                                       boolean successfulDelivery) {
        ItemStack stack = new ItemStack(sealedScrollItem);
        try {
            CompoundTag sealedCopy = (job.sealedScrollNbt == null)
                    ? new CompoundTag()
                    : job.sealedScrollNbt.copy();

            int successCount = 0;
            int failedCount = 0;
            try {
                if (sealedCopy.contains("SuccessfulDeliveries", Tag.TAG_INT)) {
                    successCount = sealedCopy.getInt("SuccessfulDeliveries");
                }
                if (sealedCopy.contains("FailedDeliveries", Tag.TAG_INT)) {
                    failedCount = sealedCopy.getInt("FailedDeliveries");
                }
            } catch (Throwable counterReadErr) {
                LOG.warn("[RavenCourierRuntime] buildDeliveredScrollStack: failed to read existing delivery counters for jobId={}: {}",
                        job.jobId, counterReadErr.toString());
            }

            if (successfulDelivery) {
                successCount++;
            } else {
                failedCount++;
            }

            sealedCopy.putInt("SuccessfulDeliveries", successCount);
            sealedCopy.putInt("FailedDeliveries", failedCount);

            String dName = (deliveredName == null) ? "" : deliveredName;
            String dUuidStr = (deliveredUuid == null) ? "" : deliveredUuid.toString();
            boolean unknown = dName.isEmpty();

            boolean hasFirst = false;
            try {
                String firstName = sealedCopy.getString("FirstDeliveredToName");
                String firstUuid = sealedCopy.getString("FirstDeliveredToUUID");
                boolean firstUnknown = sealedCopy.getBoolean("FirstDeliveredToUnknown");
                hasFirst = (!firstName.isEmpty() || !firstUuid.isEmpty() || firstUnknown);
            } catch (Throwable firstReadErr) {
                LOG.warn("[RavenCourierRuntime] buildDeliveredScrollStack: failed to read FirstDelivered* for jobId={}: {}",
                        job.jobId, firstReadErr.toString());
            }

            if (!hasFirst) {
                sealedCopy.putString("FirstDeliveredToName", dName);
                sealedCopy.putString("FirstDeliveredToUUID", dUuidStr);
                sealedCopy.putBoolean("FirstDeliveredToUnknown", unknown);
            }
            sealedCopy.putString("LastDeliveredToName", dName);
            sealedCopy.putString("LastDeliveredToUUID", dUuidStr);
            sealedCopy.putBoolean("LastDeliveredToUnknown", unknown);

            sealedCopy.putString("DeliveredToName", dName);
            sealedCopy.putString("DeliveredToUUID", dUuidStr);
            sealedCopy.putBoolean("DeliveredToUnknown", unknown);

            CompoundTag customRoot = new CompoundTag();
            customRoot.put("SealedScroll", sealedCopy);
            CustomData customData = CustomData.of(customRoot);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, customData);

            if (LOG.isDebugEnabled()) {
                LOG.debug("[RavenCourierRuntime] buildDeliveredScrollStack: jobId={} success={} first='{}' last='{}' succCnt={} failCnt={}",
                        job.jobId, successfulDelivery,
                        sealedCopy.getString("FirstDeliveredToName"),
                        sealedCopy.getString("LastDeliveredToName"),
                        successCount, failedCount);
            }

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] buildDeliveredScrollStack failed safely for jobId={}: {}",
                    job.jobId, t.toString());
        }
        return stack;
    }

    private static boolean giveSealedScrollToPlayerFromJob(ServerLevel level,
                                                           ServerPlayer player,
                                                           RavenCourierData.DeliveryJob job) {
        try {
            Item sealedScrollItem = BuiltInRegistries.ITEM.get(SEALED_SCROLL_ID);
            if (sealedScrollItem == null) {
                LOG.error("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: sealed scroll item not found (id={})", SEALED_SCROLL_ID);
                return false;
            }

            boolean hasSpace = hasEmptyInventorySlot(player);
            ItemStack stack = buildDeliveredScrollStack(
                    sealedScrollItem,
                    job,
                    safePlayerName(player),
                    player.getUUID(),
                    hasSpace
            );

            boolean handled;
            if (hasSpace) {
                handled = insertIntoFirstEmptySlot(player, stack);
                if (!handled) {
                    LOG.warn("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: insertion failed despite hasSpace=true for player='{}'; dropping instead.",
                            safePlayerName(player));
                    handled = true;
                    player.drop(stack, false);
                }
            } else {
                handled = true;
                player.drop(stack, false);
            }

            if (!handled) {
                LOG.warn("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: scroll not handled for player='{}' (jobId={})",
                        safePlayerName(player), job.jobId);
                return false;
            }

            String senderName = job.senderName == null ? "" : job.senderName;
            String recipientName = job.recipientName == null ? "" : job.recipientName;

            Component msg = Component.literal(
                    "[FeatheredFriend] You retrieved a sealed scroll"
                            + (senderName.isEmpty() ? "" : " from " + senderName)
                            + (recipientName.isEmpty() ? "" : " addressed to " + recipientName)
                            + (hasSpace ? "." : " (your inventory was full, so it was dropped nearby).")
            );
            player.sendSystemMessage(msg);

            LOG.info("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: {} delivery of sealed scroll for jobId={} to player='{}'",
                    hasSpace ? "successful" : "failed (dropped)", job.jobId, safePlayerName(player));

            return true;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob failed safely for jobId={}", job.jobId, t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Courier raven death handling (death COMPLETES job)
    // ---------------------------------------------------------------------

    private static void onRavenDeath(LivingDeathEvent event) {
        try {
            LivingEntity living = event.getEntity();
            if (!(living instanceof RavenEntity raven)) {
                return;
            }

            Level level = raven.level();
            if (!(level instanceof ServerLevel serverLevel) || level.isClientSide()) {
                return;
            }

            if (!isCourierRaven(raven)) {
                return;
            }

            long jobId = getCourierJobIdFromRaven(raven);
            if (jobId <= 0L) {
                LOG.warn("[RavenCourierRuntime] onRavenDeath: courier raven id={} has no valid CourierJobId; dropping nothing.", raven.getId());
                return;
            }

            RavenCourierData data = RavenCourierData.get(serverLevel);
            RavenCourierData.DeliveryJob job = data.getJobById(jobId);
            if (job == null) {
                LOG.warn("[RavenCourierRuntime] onRavenDeath: no job found for CourierJobId={} (raven id={})", jobId, raven.getId());
                return;
            }

            // Death COMPLETES: drop scroll + remove job.
            dropSealedScrollAtRaven(serverLevel, raven, job);
            notifySenderOfRavenDeath(serverLevel, raven, job);
            data.removeJob(job.jobId, job.recipientUuid);
            clearCourierFlags(raven);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onRavenDeath failed safely", t);
        }
    }

    private static void dropSealedScrollAtRaven(ServerLevel level,
                                                RavenEntity raven,
                                                RavenCourierData.DeliveryJob job) {
        try {
            Item sealedScrollItem = BuiltInRegistries.ITEM.get(SEALED_SCROLL_ID);
            if (sealedScrollItem == null) {
                LOG.error("[RavenCourierRuntime] dropSealedScrollAtRaven: sealed scroll item not found (id={})", SEALED_SCROLL_ID);
                return;
            }

            ItemStack stack = buildDeliveredScrollStack(
                    sealedScrollItem,
                    job,
                    null,
                    null,
                    false
            );

            raven.spawnAtLocation(stack, 0.2F);

            LOG.info("[RavenCourierRuntime] dropSealedScrollAtRaven: dropped sealed scroll for jobId={} at pos={}", job.jobId, raven.position());

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] dropSealedScrollAtRaven failed safely for jobId={}", job.jobId, t);
        }
    }

    private static boolean hasEmptyInventorySlot(ServerPlayer player) {
        try {
            int size = player.getInventory().getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack existing = player.getInventory().getItem(i);
                if (existing == null || existing.isEmpty()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("[RavenCourierRuntime] hasEmptyInventorySlot: found empty slot {} for player='{}'",
                                i, safePlayerName(player));
                    }
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] hasEmptyInventorySlot failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
        }
        return false;
    }

    private static boolean insertIntoFirstEmptySlot(ServerPlayer player,
                                                    ItemStack stack) {
        try {
            if (stack.isEmpty()) {
                return false;
            }

            int size = player.getInventory().getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack existing = player.getInventory().getItem(i);
                if (existing == null || existing.isEmpty()) {
                    player.getInventory().setItem(i, stack);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("[RavenCourierRuntime] insertIntoFirstEmptySlot: placed scroll in slot {} for player='{}'",
                                i, safePlayerName(player));
                    }
                    return true;
                }
            }

            LOG.warn("[RavenCourierRuntime] insertIntoFirstEmptySlot: no empty slot found for player='{}' despite hasEmptyInventorySlot=true",
                    safePlayerName(player));
            return false;

        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] insertIntoFirstEmptySlot failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return false;
        }
    }

    private static void notifySenderOfRavenDeath(ServerLevel anyLevel,
                                                 RavenEntity raven,
                                                 RavenCourierData.DeliveryJob job) {
        try {
            MinecraftServer server = anyLevel.getServer();
            if (server == null) {
                return;
            }

            if (job.senderUuid == null) {
                return;
            }

            ServerPlayer sender = server.getPlayerList().getPlayer(job.senderUuid);
            if (sender == null) {
                return;
            }

            String ravenName = "<your raven>";
            try {
                if (raven.getCustomName() != null) {
                    String n = raven.getCustomName().getString();
                    if (n != null && !n.isEmpty()) {
                        ravenName = n;
                    }
                }
            } catch (Throwable ignored) {
            }

            String recipientPart = (job.recipientName == null || job.recipientName.isEmpty())
                    ? ""
                    : " while delivering a scroll to " + job.recipientName;

            Component msg = Component.literal(
                    "[FeatheredFriend] Your raven, " + ravenName + ", has perished"
                            + recipientPart
                            + ". The scroll was dropped where it fell."
            );
            sender.sendSystemMessage(msg);

            LOG.info("[RavenCourierRuntime] notifySenderOfRavenDeath: notified sender='{}' of raven death (jobId={})",
                    safePlayerName(sender), job.jobId);
        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] notifySenderOfRavenDeath failed safely for jobId={}", job.jobId, t);
        }
    }

    private static void clearCourierFlags(RavenEntity raven) {
        try {
            if (raven.getTags().contains(TAG_COURIER_RAVEN)) {
                raven.removeTag(TAG_COURIER_RAVEN);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] clearCourierFlags: removeTag({}) failed safely for id={}: {}",
                    TAG_COURIER_RAVEN, raven.getId(), t.toString());
        }

        try {
            CompoundTag root = raven.getPersistentData();
            if (root != null) {
                CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
                if (ffTag != null && !ffTag.isEmpty()) {
                    ffTag.remove("CourierActive");
                    ffTag.remove("CourierJobId");
                    ffTag.remove("CourierSenderUUID");
                    ffTag.remove("CourierRecipientUUID");
                    ffTag.remove(NBT_COURIER_DESPAWN_AT);
                    root.put(Constants.MOD_ID, ffTag);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] clearCourierFlags: NBT cleanup failed safely for id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    private static void despawnCourierRaven(ServerLevel level,
                                            ServerPlayer contextPlayer,
                                            RavenEntity raven,
                                            String reason) {
        try {
            TamedRaven tamedModule = null;
            try {
                tamedModule = raven.getTamedRavenModule();
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] despawnCourierRaven: getTamedRavenModule failed safely: {}", t.toString());
            }
            String ravenName = "<unnamed>";
            try {
                if (raven.getCustomName() != null) {
                    ravenName = raven.getCustomName().getString();
                }
            } catch (Throwable ignored) {
            }

            try {
                Vec3 fxPos = raven.position().add(0.0D, 0.6D, 0.0D);
                long fxSeed = raven.getUUID().getLeastSignificantBits() ^ (long) raven.tickCount ^ 0x9F42C3B1L;
                Teleportation teleportFx = new Teleportation(raven);
                teleportFx.spawnEnderpopBurst(
                        level,
                        fxPos.x,
                        fxPos.y,
                        fxPos.z,
                        fxSeed,
                        "courier-despawn: " + reason,
                        raven
                );
            } catch (Throwable fxErr) {
                LOG.warn("[RavenCourierRuntime] despawnCourierRaven: Teleportation FX failed safely for id={}: {}",
                        raven.getId(), fxErr.toString());
            }

            if (tamedModule != null) {
                try {
                    tamedModule.beginDespawnWithFx(level, contextPlayer, ravenName);
                    LOG.info("[RavenCourierRuntime] despawnCourierRaven: triggered despawn FX for id={} name='{}' player='{}' reason={}",
                            raven.getId(), ravenName, safePlayerName(contextPlayer), reason);
                } catch (Throwable t) {
                    LOG.error("[RavenCourierRuntime] despawnCourierRaven: beginDespawnWithFx failed; discarding raven directly. err={}",
                            t.toString());
                    raven.discard();
                }
            } else {
                LOG.warn("[RavenCourierRuntime] despawnCourierRaven: TamedRaven module null; discarding raven without FX. player='{}' id={} reason={}",
                        safePlayerName(contextPlayer), raven.getId(), reason);
                raven.discard();
            }

            clearCourierFlags(raven);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] despawnCourierRaven failed safely", t);
        }
    }

    private static void ensureRavenName(RavenEntity raven, String ravenName) {
        try {
            Component cur = raven.getCustomName();
            String curStr = (cur == null) ? "" : cur.getString();
            if (!ravenName.equals(curStr)) {
                raven.setCustomName(Component.literal(ravenName));
            }
            raven.setCustomNameVisible(true);
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] ensureRavenName failed safely for id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    private static String safePlayerName(Player player) {
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
