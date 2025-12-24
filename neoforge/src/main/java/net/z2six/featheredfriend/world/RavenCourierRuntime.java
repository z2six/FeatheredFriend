// neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierRuntime.java
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.RavenVariant;
import net.z2six.featheredfriend.entity.raven.modules.RavenSoundEngine;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import net.z2six.featheredfriend.entity.raven.modules.Teleportation;
import net.z2six.featheredfriend.registry.FFNeoForgeEntities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import java.util.HashMap;
import java.util.Map;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierRuntime.java
 *
 * Server-side runtime for raven courier deliveries.
 *
 * Responsibilities:
 *  - Periodically scan RavenCourierData for pending delivery jobs.
 *  - For online recipients, spawn a "courier raven" that visually carries the scroll
 *    and follows the recipient.
 *  - Enforce concurrency constraints:
 *      * Multiple deliveries can be in-flight at once.
 *      * But never two jobs simultaneously FROM the same sender, nor TO the same recipient.
 *  - Handle interaction with courier ravens:
 *      * Any player can RMB the courier raven to retrieve its Sealed Scroll.
 *      * The scroll is reconstructed with the exact SealedScroll NBT payload from the job.
 *      * If inventory is full, the scroll is dropped at the player's feet.
 *  - Handle courier raven death:
 *      * The scroll is dropped at the raven's position.
 *      * The sender is notified (if online): "Your raven, name, has perished..."
 *      * The job is removed from RavenCourierData so the sender is free to send again.
 *
 * Implementation notes:
 *  - We do NOT reuse the scroll-summoned raven tag ("ff_scroll_summoned").
 *    Courier ravens use their own scoreboard tag + NBT fields:
 *      * Scoreboard tag: "ff_courier_raven"
 *      * NBT (under Constants.MOD_ID):
 *          - CourierActive : boolean
 *          - CourierJobId  : long
 *          - CourierSenderUUID    : UUID
 *          - CourierRecipientUUID : UUID
 *  - Job.inFlight is treated as a runtime-only flag:
 *      * It is saved to NBT for debugging/visibility.
 *      * It is reset to false on world load (see RavenCourierData.readFromNbt).
 */
public final class RavenCourierRuntime {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Scoreboard tag used to mark courier ravens.
     */
    private static final String TAG_COURIER_RAVEN = "ff_courier_raven";

    /**
     * Registry name of the sealed scroll item.
     * (Same as in TamedRavenScrollWatcher / RavenCourierData.)
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
     * This still allows multiple players to get deliveries in parallel but prevents
     * pathological "explosions" if there is a large backlog.
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

    /**
     * Called from FeatheredFriend main class:
     *
     *   RavenCourierRuntime.register();
     *
     * This wires up:
     *  - ServerTickEvent.Post for periodic dispatch of courier ravens.
     *  - PlayerInteractEvent.EntityInteract for RMB retrieval of scrolls.
     *  - LivingDeathEvent for handling courier raven death.
     */
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
    // Server tick: periodic dispatch
    // ---------------------------------------------------------------------

    private static void onServerTick(@NotNull ServerTickEvent.Post event) {
        try {
            MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }

            serverTickCounter++;
            if ((serverTickCounter % DISPATCH_INTERVAL_TICKS) != 0) {
                // Throttle to once per DISPATCH_INTERVAL_TICKS.
                return;
            }

            dispatchPendingDeliveries(server);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onServerTick failed safely", t);
        }
    }

    /**
     * Core dispatch logic:
     *  - Reads all pending jobs from RavenCourierData.
     *  - Reconciles them with any existing courier ravens already in the world:
     *      * Marks jobs as in-flight when a courier raven is already carrying them.
     *      * Enforces per-raven lifetime and cleans up expired / stray ravens.
     *  - Builds concurrency sets (senders/recipients already in-flight).
     *  - For jobs whose recipient is online and not currently busy as sender/recipient,
     *    spawns a courier raven carrying that job's scroll.
     */
    private static void dispatchPendingDeliveries(@NotNull MinecraftServer server) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                LOG.warn("[RavenCourierRuntime] dispatchPendingDeliveries: overworld is null; aborting dispatch.");
                return;
            }

            RavenCourierData data = RavenCourierData.get(overworld);
            List<RavenCourierData.DeliveryJob> allJobs = data.getAllJobsFlat();

            // These are filled both from existing courier ravens (via reconciliation)
            // and from jobs that are explicitly marked inFlight.
            Set<UUID> busySenders = new HashSet<>();
            Set<UUID> busyRecipients = new HashSet<>();

            // NEW: reconcile jobs with any courier ravens already in the world AND
            // enforce per-raven lifetimes (60s) before attempting to spawn new ones.
            reconcileJobsWithExistingCourierRavens(server, overworld, data, allJobs, busySenders, busyRecipients);

            // Also respect any jobs already marked inFlight that might not currently
            // have an in-world raven (should be rare but keeps us robust).
            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job == null) {
                    continue;
                }
                RavenCourierData.DeliveryJob liveJob = data.getJobById(job.jobId);
                if (liveJob == null) {
                    // Job may have been removed by a timeout or other logic.
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
                // No jobs left after reconciliation -> nothing to dispatch.
                return;
            }

            int dispatchedCount = 0;

            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job == null) {
                    continue;
                }

                // Always re-resolve the job from the data store so we see removals
                // performed by earlier logic (timeouts, manual removal, etc.).
                RavenCourierData.DeliveryJob liveJob = data.getJobById(job.jobId);
                if (liveJob == null) {
                    continue;
                }
                job = liveJob;

                // Already in-flight in this server session -> skip.
                if (job.inFlight) {
                    continue;
                }

                UUID senderId = job.senderUuid;
                UUID recipientId = job.recipientUuid;
                if (recipientId == null) {
                    continue;
                }

                // Apply concurrency constraints:
                //  - Never spawn a second delivery FROM the same sender concurrently.
                //  - Never spawn a second delivery TO the same recipient concurrently.
                if ((senderId != null && busySenders.contains(senderId))
                        || busyRecipients.contains(recipientId)) {
                    continue;
                }

                // Recipient must be online for the courier to spawn.
                ServerPlayer recipient = server.getPlayerList().getPlayer(recipientId);
                if (recipient == null) {
                    continue;
                }

                ServerLevel targetLevel = recipient.serverLevel();
                if (targetLevel == null || targetLevel.isClientSide()) {
                    continue;
                }

                // HARD SAFETY: if a courier raven already exists for this jobId anywhere
                // in the world, we must NOT spawn another one.
                if (hasActiveCourierRavenForJob(server, job.jobId)) {
                    job.inFlight = true;
                    data.setDirty();

                    if (senderId != null) {
                        busySenders.add(senderId);
                    }
                    busyRecipients.add(recipientId);

                    LOG.warn(
                            "[RavenCourierRuntime] dispatchPendingDeliveries: jobId={} already has an active courier raven; skipping duplicate spawn.",
                            job.jobId
                    );
                    continue;
                }

                RavenEntity raven = spawnCourierRavenForJob(targetLevel, recipient, job);
                if (raven == null) {
                    continue;
                }

                // Mark job as in-flight for the current server session.
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

    /**
     * Returns true if there is at least one live courier raven in any loaded level
     * whose CourierJobId matches the given jobId.
     */
    private static boolean hasActiveCourierRavenForJob(@NotNull MinecraftServer server, long jobId) {
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
                    LOG.warn(
                            "[RavenCourierRuntime] hasActiveCourierRavenForJob: level scan failed safely: {}",
                            levelErr.toString()
                    );
                }
            }
        } catch (Throwable t) {
            LOG.warn(
                    "[RavenCourierRuntime] hasActiveCourierRavenForJob failed safely for jobId={}: {}",
                    jobId,
                    t.toString()
            );
        }
        return false;
    }

    /**
     * Handles the case where a courier raven has exceeded its intended lifetime.
     * We treat this similar to death:
     *  - Drop the sealed scroll at the raven's position (failed delivery).
     *  - Notify the sender if online.
     *  - Remove the job from RavenCourierData.
     *  - Despawn the raven with FX and clear its courier flags.
     */
    private static void handleCourierRavenTimeout(@NotNull ServerLevel level,
                                                  @NotNull RavenCourierData data,
                                                  @NotNull RavenEntity raven,
                                                  @NotNull RavenCourierData.DeliveryJob job) {
        try {
            // Drop the scroll at the raven's location as a failed delivery.
            dropSealedScrollAtRaven(level, raven, job);

            // Inform the sender (same UX as death).
            notifySenderOfRavenDeath(level, raven, job);

            // Remove the job so the sender is free again.
            data.removeJob(job.jobId, job.recipientUuid);

            // Despawn the raven with FX, using the best available context player.
            ServerPlayer contextPlayer = null;
            MinecraftServer server = level.getServer();
            if (server != null) {
                // Prefer the recipient as context, then the sender, then any online player.
                if (job.recipientUuid != null) {
                    contextPlayer = server.getPlayerList().getPlayer(job.recipientUuid);
                }
                if (contextPlayer == null && job.senderUuid != null) {
                    contextPlayer = server.getPlayerList().getPlayer(job.senderUuid);
                }
                if (contextPlayer == null) {
                    List<ServerPlayer> players = server.getPlayerList().getPlayers();
                    if (!players.isEmpty()) {
                        contextPlayer = players.get(0);
                    }
                }
            }

            if (contextPlayer != null) {
                despawnCourierRaven(level, contextPlayer, raven, "lifetime expired");
            } else {
                // No online players -> just clear flags and discard without fancy FX.
                LOG.warn(
                        "[RavenCourierRuntime] handleCourierRavenTimeout: no context player available; discarding raven id={} silently.",
                        raven.getId()
                );
                clearCourierFlags(raven);
                raven.discard();
            }
        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] handleCourierRavenTimeout failed safely for jobId={}", job.jobId, t);
            try {
                clearCourierFlags(raven);
                raven.discard();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Reconciles RavenCourierData jobs with any courier ravens that already exist
     * in the world, and enforces per-raven lifetimes.
     *
     * Responsibilities:
     *  - For each courier raven with a valid job:
     *      * If CourierDespawnAt is missing, initialize it to now + COURIER_LIFETIME_TICKS.
     *      * If its lifetime has expired -> drop scroll, notify sender, remove job, despawn raven.
     *      * Otherwise mark the job as in-flight and add its sender/recipient to the busy sets.
     *  - For courier ravens with invalid or unknown jobIds:
     *      * Clear courier flags and discard them as strays (no scroll drop, since there is no job).
     */
    private static void reconcileJobsWithExistingCourierRavens(@NotNull MinecraftServer server,
                                                               @NotNull ServerLevel overworld,
                                                               @NotNull RavenCourierData data,
                                                               @NotNull List<RavenCourierData.DeliveryJob> allJobs,
                                                               @NotNull Set<UUID> busySenders,
                                                               @NotNull Set<UUID> busyRecipients) {
        try {
            // Build a quick lookup map from jobId -> job.
            Map<Long, RavenCourierData.DeliveryJob> jobsById = new HashMap<>();
            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job == null) {
                    continue;
                }
                jobsById.put(job.jobId, job);
            }

            // Scan all loaded levels for courier ravens.
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
                            // Courier-tagged raven with no valid job -> stray, clean it up.
                            LOG.warn(
                                    "[RavenCourierRuntime] reconcile: courier raven id={} has no valid CourierJobId; discarding stray courier.",
                                    raven.getId()
                            );
                            clearCourierFlags(raven);
                            raven.discard();
                            continue;
                        }

                        RavenCourierData.DeliveryJob job = jobsById.get(jobId);
                        if (job == null) {
                            // Raven references a job that no longer exists; treat as stray.
                            LOG.warn(
                                    "[RavenCourierRuntime] reconcile: courier raven id={} refers to unknown jobId={}; discarding stray courier.",
                                    raven.getId(),
                                    jobId
                            );
                            clearCourierFlags(raven);
                            raven.discard();
                            continue;
                        }

                        // Lifetime enforcement and in-flight bookkeeping for this raven/job.
                        try {
                            CompoundTag root = raven.getPersistentData();
                            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);

                            long now = level.getGameTime();
                            long despawnAt = ffTag.getLong(NBT_COURIER_DESPAWN_AT);

                            // Backwards-compat: older ravens may not have a despawn deadline yet.
                            if (despawnAt <= 0L) {
                                despawnAt = now + COURIER_LIFETIME_TICKS;
                                ffTag.putLong(NBT_COURIER_DESPAWN_AT, despawnAt);
                                root.put(Constants.MOD_ID, ffTag);

                                if (LOG.isDebugEnabled()) {
                                    LOG.debug(
                                            "[RavenCourierRuntime] reconcile: initializing lifetime for courier raven id={} jobId={} now={} despawnAt={}",
                                            raven.getId(),
                                            job.jobId,
                                            now,
                                            despawnAt
                                    );
                                }
                            }

                            if (now >= despawnAt) {
                                LOG.info(
                                        "[RavenCourierRuntime] reconcile: lifetime expired for courier raven id={} jobId={} now={} despawnAt={}",
                                        raven.getId(),
                                        job.jobId,
                                        now,
                                        despawnAt
                                );

                                // Treat as a failed delivery similar to death.
                                handleCourierRavenTimeout(level, data, raven, job);

                                // Remove this job from our local lookup so we don't
                                // accidentally treat it as dispatchable later.
                                jobsById.remove(job.jobId);
                                continue;
                            }
                        } catch (Throwable lifetimeErr) {
                            LOG.warn(
                                    "[RavenCourierRuntime] reconcile: lifetime check failed safely for raven id={} jobId={}: {}",
                                    raven.getId(),
                                    job.jobId,
                                    lifetimeErr.toString()
                            );
                        }

                        // Alive and within lifetime -> mark job as in-flight and update busy sets.
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
    private static RavenEntity spawnCourierRavenForJob(@NotNull ServerLevel level,
                                                       @NotNull ServerPlayer recipient,
                                                       @NotNull RavenCourierData.DeliveryJob job) {
        try {
            RavenEntity raven = FFNeoForgeEntities.RAVEN.get().create(level);
            if (raven == null) {
                LOG.error("[RavenCourierRuntime] spawnCourierRavenForJob: entity factory returned null for jobId={}", job.jobId);
                return null;
            }

            // Spawn position:  same logic style as TamedRavenScrollWatcher.findSafeSpawnAbovePlayer(...)
            Vec3 spawnPos = findSafeSpawnAbovePlayer(level, recipient);
            if (spawnPos != null) {
                raven.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, recipient.getYRot(), 0.0F);
            } else {
                // Fallback: slightly in front of the player's face.
                Vec3 playerPos = recipient.position();
                Vec3 look = recipient.getLookAngle();
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
                double sy = recipient.getEyeY() + 0.1D;

                Vec3 fallback = new Vec3(sx, sy, sz);
                raven.moveTo(fallback.x, fallback.y, fallback.z, recipient.getYRot(), 0.0F);

                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: using fallback spawn={} for recipient='{}' jobId={}",
                        fallback, safePlayerName(recipient), job.jobId);
            }

            // Tame and bind to the RECIPIENT so the raven will follow them using standard AI.
            try {
                raven.setTame(true, true); // 1.21 style: setTame(boolean tame, boolean broadcastEvent)
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setTame(true, true) failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }
            try {
                raven.setOwnerUUID(job.recipientUuid);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setOwnerUUID failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }

            // Visual: this raven is clearly a scroll courier.
            try {
                raven.setRavenVariant(RavenVariant.SCROLL);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setRavenVariant(SCROLL) failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }

            // Name: "<SenderName>'s <RavenName>" if available, otherwise fallback.
            String baseRavenName = (job.ravenName != null && !job.ravenName.isEmpty())
                    ? job.ravenName
                    : "Raven";

            String ravenName = (job.senderName != null && !job.senderName.isEmpty())
                    ? job.senderName + "'s " + baseRavenName
                    : baseRavenName;

            ensureRavenName(raven, ravenName);

            // Mark as a courier raven via persistent data + scoreboard tag + lifetime.
            attachCourierJobToRaven(level, raven, job);

            // Actually add to the world.
            level.addFreshEntity(raven);

            // Spawn FX: portal + teleport + whistle (same style as scroll-summoned spawn).
            playCourierSpawnFx(level, recipient, raven);

            LOG.info(
                    "[RavenCourierRuntime] spawnCourierRavenForJob: spawned courier raven id={} for jobId={} sender='{}' -> recipient='{}' at {}",
                    raven.getId(),
                    job.jobId,
                    job.senderName,
                    job.recipientName,
                    raven.position()
            );

            return raven;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] spawnCourierRavenForJob failed safely for jobId={}", job.jobId, t);
            return null;
        }
    }

    /**
     * Attach courier job metadata (including lifetime) to the raven (NBT + scoreboard tag).
     */
    private static void attachCourierJobToRaven(@NotNull ServerLevel level,
                                                @NotNull RavenEntity raven,
                                                @NotNull RavenCourierData.DeliveryJob job) {
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
                LOG.debug(
                        "[RavenCourierRuntime] attachCourierJobToRaven: lifetime set for raven id={} jobId={} now={} despawnAt={}",
                        raven.getId(),
                        job.jobId,
                        now,
                        despawnAt
                );
            }
        } catch (Throwable t) {
            LOG.warn(
                    "[RavenCourierRuntime] attachCourierJobToRaven: NBT write failed safely for raven id={}: {}",
                    raven.getId(),
                    t.toString()
            );
        }

        try {
            raven.addTag(TAG_COURIER_RAVEN);
        } catch (Throwable t) {
            LOG.warn(
                    "[RavenCourierRuntime] attachCourierJobToRaven: addTag({}) failed safely for raven id={}: {}",
                    TAG_COURIER_RAVEN,
                    raven.getId(),
                    t.toString()
            );
        }
    }

    /**
     * Portal + teleport sound + whistle.
     */
    private static void playCourierSpawnFx(@NotNull ServerLevel level,
                                           @NotNull ServerPlayer recipient,
                                           @NotNull RavenEntity raven) {
        try {
            Vec3 ravenPos = raven.position();
            Vec3 playerPos = recipient.position();

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

        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] playCourierSpawnFx failed safely: {}", t.toString());
        }
    }

    @Nullable
    private static Vec3 findSafeSpawnAbovePlayer(@NotNull ServerLevel level, @NotNull ServerPlayer player) {
        try {
            Vec3 playerPos = player.position();
            Vec3 look = player.getLookAngle();

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

            int baseY = Mth.floor(player.getY() + preferredOffsetY + 0.5D);

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

                        LOG.info("[RavenCourierRuntime] findSafeSpawnAbovePlayer: chosen spawn={} for player='{}' (baseY={}, dy={})",
                                spawn, safePlayerName(player), baseY, dy);

                        return spawn;
                    }
                }
            }

            LOG.warn("[RavenCourierRuntime] findSafeSpawnAbovePlayer: no 3x3x2 air column found near player='{}' (baseY={})",
                    safePlayerName(player), baseY);

            return null;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] findSafeSpawnAbovePlayer failed safely", t);
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
            LOG.warn("[RavenCourierRuntime] is3x3x2Air failed safely: {}", t.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Player RMB retrieval (courier raven -> sealed scroll)
    // ---------------------------------------------------------------------

    private static void onEntityInteract(@NotNull PlayerInteractEvent.EntityInteract event) {
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

            // Only care about ravens that are marked as courier ravens.
            if (!isCourierRaven(raven)) {
                return;
            }

            // Resolve jobId from the raven's NBT.
            long jobId = getCourierJobIdFromRaven(raven);
            if (jobId <= 0L) {
                LOG.warn("[RavenCourierRuntime] onEntityInteract: courier raven id={} has no valid CourierJobId; ignoring.",
                        raven.getId());
                return;
            }

            RavenCourierData data = RavenCourierData.get(serverLevel);
            RavenCourierData.DeliveryJob job = data.getJobById(jobId);
            if (job == null) {
                LOG.warn("[RavenCourierRuntime] onEntityInteract: no job found for CourierJobId={} (raven id={})",
                        jobId, raven.getId());
                // Clean up stray raven by despawning it without touching jobs.
                despawnCourierRaven(serverLevel, serverPlayer, raven, "orphaned courier (no job)");
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            // Give the scroll to whoever clicked (could be recipient or a "thief").
            boolean delivered = giveSealedScrollToPlayerFromJob(serverLevel, serverPlayer, job);
            if (!delivered) {
                // If we somehow failed to hand out or drop the scroll, don't clear the job yet.
                event.setCancellationResult(InteractionResult.PASS);
                return;
            }

            // Remove the job; delivery is complete.
            data.removeJob(job.jobId, job.recipientUuid);

            // Despawn courier raven with FX.
            despawnCourierRaven(serverLevel, serverPlayer, raven, "delivery complete: scroll retrieved");

            // Consume the interaction.
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onEntityInteract failed safely", t);
        }
    }

    private static boolean isCourierRaven(@NotNull RavenEntity raven) {
        try {
            if (raven.getTags().contains(TAG_COURIER_RAVEN)) {
                return true;
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] isCourierRaven: tag check failed for id={}: {}",
                    raven.getId(), t.toString());
        }

        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return false;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            return ffTag != null && ffTag.getBoolean("CourierActive");
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] isCourierRaven: NBT check failed for id={}: {}",
                    raven.getId(), t.toString());
            return false;
        }
    }

    private static long getCourierJobIdFromRaven(@NotNull RavenEntity raven) {
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
            LOG.warn("[RavenCourierRuntime] getCourierJobIdFromRaven failed safely for id={}: {}",
                    raven.getId(), t.toString());
            return -1L;
        }
    }

    /**
     * Build a Sealed Scroll ItemStack from a job's SealedScroll payload,
     * tracking delivery history in the SealedScroll compound:
     *
     *   SealedScroll: {
     *     ...original fields...,
     *
     *     // legacy (always mirrors "last" delivery)
     *     DeliveredToName: "<name or empty>",
     *     DeliveredToUUID: "<uuid or empty>",
     *     DeliveredToUnknown: true/false,
     *
     *     // new fields
     *     FirstDeliveredToName: "<name or empty>",
     *     FirstDeliveredToUUID: "<uuid or empty>",
     *     FirstDeliveredToUnknown: true/false,
     *
     *     LastDeliveredToName: "<name or empty>",
     *     LastDeliveredToUUID: "<uuid or empty>",
     *     LastDeliveredToUnknown: true/false,
     *
     *     SuccessfulDeliveries: <int>,
     *     FailedDeliveries:     <int>
     *   }
     *
     * Semantics:
     *  - FirstDelivered* is set on the *first* recorded delivery of this scroll
     *    (successful or not) and never changes afterwards.
     *  - LastDelivered* is overwritten on every delivery.
     *  - SuccessfulDeliveries is incremented for successful deliveries to a player
     *    (RMB with free inventory slot).
     *  - FailedDeliveries is incremented for deliveries that end up dropped on the ground
     *    (no free inventory slot, raven death, etc.).
     */
    @NotNull
    private static ItemStack buildDeliveredScrollStack(@NotNull Item sealedScrollItem,
                                                       @NotNull RavenCourierData.DeliveryJob job,
                                                       @Nullable String deliveredName,
                                                       @Nullable UUID deliveredUuid,
                                                       boolean successfulDelivery) {
        ItemStack stack = new ItemStack(sealedScrollItem);
        try {
            CompoundTag sealedCopy = (job.sealedScrollNbt == null)
                    ? new CompoundTag()
                    : job.sealedScrollNbt.copy();

            // --- Read existing counters (if any) ---
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

            // --- Bump appropriate counter for this outcome ---
            if (successfulDelivery) {
                successCount++;
            } else {
                failedCount++;
            }

            sealedCopy.putInt("SuccessfulDeliveries", successCount);
            sealedCopy.putInt("FailedDeliveries", failedCount);

            // --- Compute this delivery's identity ---
            String dName = (deliveredName == null) ? "" : deliveredName;
            String dUuidStr = (deliveredUuid == null) ? "" : deliveredUuid.toString();
            boolean unknown = dName.isEmpty();

            // --- First-delivery fields: only set once ---
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

            // --- Last-delivery fields: always overwrite ---
            sealedCopy.putString("LastDeliveredToName", dName);
            sealedCopy.putString("LastDeliveredToUUID", dUuidStr);
            sealedCopy.putBoolean("LastDeliveredToUnknown", unknown);

            // --- Legacy aliases (mirror "last" delivery for backward compatibility) ---
            sealedCopy.putString("DeliveredToName", dName);
            sealedCopy.putString("DeliveredToUUID", dUuidStr);
            sealedCopy.putBoolean("DeliveredToUnknown", unknown);

            // Wrap back into CustomData root: { SealedScroll: <sealedCopy> }
            CompoundTag customRoot = new CompoundTag();
            customRoot.put("SealedScroll", sealedCopy);
            CustomData customData = CustomData.of(customRoot);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, customData);

            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "[RavenCourierRuntime] buildDeliveredScrollStack: jobId={} success={} first='{}' last='{}' succCnt={} failCnt={}",
                        job.jobId,
                        successfulDelivery,
                        sealedCopy.getString("FirstDeliveredToName"),
                        sealedCopy.getString("LastDeliveredToName"),
                        successCount,
                        failedCount
                );
            }

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] buildDeliveredScrollStack failed safely for jobId={}: {}",
                    job.jobId, t.toString());
        }
        return stack;
    }

    /**
     * Constructs a new Sealed Scroll ItemStack from the job's SealedScroll NBT,
     * annotates it with delivery history, and either:
     *  - puts it into the player's inventory (successful delivery), or
     *  - drops it at the player's feet (failed delivery: no free slot).
     *
     * SuccessfulDeliveries is incremented only when the player has at least one
     * empty inventory slot and we place the scroll directly into their inventory.
     * FailedDeliveries is incremented when the scroll must be dropped on the ground.
     */
    private static boolean giveSealedScrollToPlayerFromJob(@NotNull ServerLevel level,
                                                           @NotNull ServerPlayer player,
                                                           @NotNull RavenCourierData.DeliveryJob job) {
        try {
            Item sealedScrollItem = BuiltInRegistries.ITEM.get(SEALED_SCROLL_ID);
            if (sealedScrollItem == null) {
                LOG.error("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: sealed scroll item not found in registry (id={})",
                        SEALED_SCROLL_ID);
                return false;
            }

            // Determine if the player has at least one empty slot.
            boolean hasSpace = hasEmptyInventorySlot(player);

            // Build the scroll stack with correct outcome (success/failure).
            ItemStack stack = buildDeliveredScrollStack(
                    sealedScrollItem,
                    job,
                    safePlayerName(player),
                    player.getUUID(),
                    hasSpace // successfulDelivery flag
            );

            boolean handled = false;

            if (hasSpace) {
                // Successful delivery: insert into first empty slot.
                handled = insertIntoFirstEmptySlot(player, stack);
                if (!handled) {
                    // Safety net: if insertion somehow failed despite hasSpace, treat as failure and drop it.
                    LOG.warn(
                            "[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: insertion failed despite hasSpace=true for player='{}'; dropping instead.",
                            safePlayerName(player)
                    );
                    handled = true;
                    player.drop(stack, false);
                }
            } else {
                // No free slot -> failed delivery; stack is already flagged as failure by buildDeliveredScrollStack.
                handled = true;
                player.drop(stack, false);
            }

            if (!handled) {
                LOG.warn("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: scroll not handed out correctly for player='{}' (jobId={})",
                        safePlayerName(player), job.jobId);
                return false;
            }

            // Notify the player.
            String senderName = job.senderName == null ? "" : job.senderName;
            String recipientName = job.recipientName == null ? "" : job.recipientName;

            Component msg = Component.literal(
                    "[FeatheredFriend] You retrieved a sealed scroll"
                            + (senderName.isEmpty() ? "" : " from " + senderName)
                            + (recipientName.isEmpty() ? "" : " addressed to " + recipientName)
                            + (hasSpace ? "." : " (your inventory was full, so it was dropped nearby).")
            );
            player.sendSystemMessage(msg);

            LOG.info(
                    "[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: {} delivery of sealed scroll for jobId={} to player='{}'",
                    hasSpace ? "successful" : "failed (dropped)",
                    job.jobId,
                    safePlayerName(player)
            );

            return true;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob failed safely for jobId={}", job.jobId, t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Courier raven death handling
    // ---------------------------------------------------------------------

    private static void onRavenDeath(@NotNull LivingDeathEvent event) {
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
                LOG.warn("[RavenCourierRuntime] onRavenDeath: courier raven id={} has no valid CourierJobId; dropping nothing.",
                        raven.getId());
                return;
            }

            RavenCourierData data = RavenCourierData.get(serverLevel);
            RavenCourierData.DeliveryJob job = data.getJobById(jobId);
            if (job == null) {
                LOG.warn("[RavenCourierRuntime] onRavenDeath: no job found for CourierJobId={} (raven id={})",
                        jobId, raven.getId());
                // No job to clean up; just fall through and let it die.
                return;
            }

            // Drop the scroll at the raven's position.
            dropSealedScrollAtRaven(serverLevel, raven, job);

            // Notify the sender if online.
            notifySenderOfRavenDeath(serverLevel, raven, job);

            // Remove the job so the sender is free again.
            data.removeJob(job.jobId, job.recipientUuid);

            // Clean up courier flags (not strictly necessary, entity is dying).
            clearCourierFlags(raven);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onRavenDeath failed safely", t);
        }
    }

    private static void dropSealedScrollAtRaven(@NotNull ServerLevel level,
                                                @NotNull RavenEntity raven,
                                                @NotNull RavenCourierData.DeliveryJob job) {
        try {
            Item sealedScrollItem = BuiltInRegistries.ITEM.get(SEALED_SCROLL_ID);
            if (sealedScrollItem == null) {
                LOG.error("[RavenCourierRuntime] dropSealedScrollAtRaven: sealed scroll item not found in registry (id={})",
                        SEALED_SCROLL_ID);
                return;
            }

            // Raven death: no direct recipient -> mark as failed delivery with unknown target.
            ItemStack stack = buildDeliveredScrollStack(
                    sealedScrollItem,
                    job,
                    null,
                    null,
                    false // successfulDelivery = false (failed delivery)
            );

            raven.spawnAtLocation(stack, 0.2F);

            LOG.info("[RavenCourierRuntime] dropSealedScrollAtRaven: dropped sealed scroll for jobId={} at raven death pos={}",
                    job.jobId, raven.position());

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] dropSealedScrollAtRaven failed safely for jobId={}", job.jobId, t);
        }
    }

    /**
     * Returns true if the player has at least one completely empty slot
     * in their inventory.
     */
    private static boolean hasEmptyInventorySlot(@NotNull ServerPlayer player) {
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

    /**
     * Inserts the given stack into the first empty inventory slot of the player.
     *
     * @return true if the stack was placed into an empty slot, false otherwise.
     */
    private static boolean insertIntoFirstEmptySlot(@NotNull ServerPlayer player,
                                                    @NotNull ItemStack stack) {
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

    private static void notifySenderOfRavenDeath(@NotNull ServerLevel anyLevel,
                                                 @NotNull RavenEntity raven,
                                                 @NotNull RavenCourierData.DeliveryJob job) {
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
                // Sender offline -> no message for now.
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

    private static void clearCourierFlags(@NotNull RavenEntity raven) {
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

    /**
     * Despawn ONE courier raven via TamedRaven.beginDespawnWithFx,
     * falling back to discard() if anything goes wrong.
     *
     * This is the courier analogue of TamedRavenScrollWatcher.despawnOneScrollSummonedRaven.
     */
    private static void despawnCourierRaven(@NotNull ServerLevel level,
                                            @NotNull ServerPlayer contextPlayer,
                                            @NotNull RavenEntity raven,
                                            @NotNull String reason) {
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

            // Trigger a one-shot Enderpop FX burst at the despawn position.
            try {
                Vec3 fxPos = raven.position().add(0.0D, 0.6D, 0.0D);
                long fxSeed =
                        raven.getUUID().getLeastSignificantBits()
                                ^ (long) raven.tickCount
                                ^ 0x9F42C3B1L; // fixed salt for courier-despawn
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

            // Request fade-out FX via module if present.
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

            // Clear courier flags so this raven is no longer treated as a courier.
            clearCourierFlags(raven);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] despawnCourierRaven failed safely", t);
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
            LOG.warn("[RavenCourierRuntime] ensureRavenName failed safely for id={}: {}",
                    raven.getId(), t.toString());
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
