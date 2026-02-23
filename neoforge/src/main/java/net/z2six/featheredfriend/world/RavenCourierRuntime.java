// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierRuntime.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.z2six.featheredfriend.client.ravenbadge.RavenBadgeEventType;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.MailboxBlock;
import net.z2six.featheredfriend.block.entity.MailboxBlockEntity;
import net.z2six.featheredfriend.config.FFServerConfig;
import net.z2six.featheredfriend.entity.raven.RavenAIState;
import net.z2six.featheredfriend.entity.raven.RavenAnimMode;
import net.z2six.featheredfriend.entity.raven.RavenArmorVisual;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.RavenVariant;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import net.z2six.featheredfriend.entity.raven.modules.Teleportation;
import net.z2six.featheredfriend.log.RavenLogCategory;
import net.z2six.featheredfriend.log.FFLogThrottle;
import net.z2six.featheredfriend.registry.FFBlocks;
import net.z2six.featheredfriend.registry.FFEntities;
import net.z2six.featheredfriend.world.TamedRavenPlayerData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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
 *      * If raven times out: mark job failed in RavenCourierData; do NOT remove job; do NOT drop scroll.
 *        Auto-retry-eligible failed jobs retry based on server config.
 *      * Payload-protected landed hits fail + requeue (no payload drop) and also auto-retry.
 *  - Courier raven name is just RavenName (no "Player's RavenName").
 */
public final class RavenCourierRuntime {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Scoreboard tag used to mark courier ravens.
     */
    private static final String TAG_COURIER_RAVEN = "ff_courier_raven";

    private static final String FAILURE_REASON_TIMEOUT = "timeout";
    private static final String FAILURE_REASON_PAYLOAD_PROTECTED_RETRY = "payload_protected_retry";
    private static final String FAILURE_REASON_PAYLOAD_PROTECTED_RETRY_LEGACY = "payload_saved_on_hit";
    private static final String FAILURE_REASON_SENDER_RECALL_REQUESTED = "sender_recall_requested";

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
    private static final String NBT_COURIER_LINK_PAUSE_LAST_TICK = "CourierLinkPauseLastTick";
    private static final String NBT_COURIER_LAST_THREAT_LOG_AT = "CourierLastThreatLogAt";

    // Mailbox delivery attempt state (stored on courier raven entity NBT)
    private static final String NBT_COURIER_MAILBOX_MODE = "CourierMailboxMode";
    private static final String NBT_COURIER_MAILBOX_DIMENSION = "CourierMailboxDimension";
    private static final String NBT_COURIER_MAILBOX_BLOCK_POS = "CourierMailboxBlockPos";
    private static final String NBT_COURIER_MAILBOX_OWNER_UUID = "CourierMailboxOwnerUUID";
    private static final String NBT_COURIER_MAILBOX_OWNER_UUID_STR = "CourierMailboxOwnerUUIDStr";
    private static final String NBT_COURIER_MAILBOX_ATTEMPT_END_AT = "CourierMailboxAttemptEndAt";
    private static final String NBT_COURIER_MAILBOX_OUTCOME = "CourierMailboxOutcome";
    private static final String NBT_COURIER_MAILBOX_DEPOSIT_AT = "CourierMailboxDepositAt";
    private static final String NBT_COURIER_MAILBOX_TICKET_CHUNK = "CourierMailboxTicketChunk";

    private static final int COURIER_MAILBOX_DWELL_TICKS = 40; // 2 seconds
    private static final int COURIER_MAILBOX_TICKET_RADIUS = 1;

    private static final int COURIER_MAILBOX_OUTCOME_NONE = 0;
    private static final int COURIER_MAILBOX_OUTCOME_DELIVERED = 1;
    private static final int COURIER_MAILBOX_OUTCOME_MISSING = 2;
    private static final int COURIER_MAILBOX_OUTCOME_FULL = 3;
    private static final String NBT_RAVEN_CHEST_PERCH_ASSIGNED = "RavenChestPerchAssigned";
    private static final String NBT_RAVEN_CHEST_PERCH_DIMENSION = "RavenChestPerchDimension";
    private static final String NBT_RAVEN_CHEST_PERCH_BLOCK_POS = "RavenChestPerchBlockPos";
    private static final double RAVEN_CHEST_PERCH_OFFSET_X = 0.5D;
    private static final double RAVEN_CHEST_PERCH_OFFSET_Y = 1.6D;
    private static final double RAVEN_CHEST_PERCH_OFFSET_Z = 0.5D;

    private static final double DEFAULT_COURIER_THREAT_SENSE_RADIUS_BLOCKS = 10.0D;
    private static final long COURIER_THREAT_LOG_COOLDOWN_TICKS = 5L * 20L;

    /**
     * How often (in server ticks) we attempt to dispatch new courier ravens.
     * 20 ticks = once per second.
     */
    private static final int DISPATCH_INTERVAL_TICKS = 20;

    /**
     * How often we do a full world scan to reconcile courier ravens with jobs.
     * This is intentionally much slower than the dispatch cadence.
     */
    private static final int FULL_RECONCILE_INTERVAL_TICKS = 200; // 10s

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
            NeoForge.EVENT_BUS.addListener(RavenCourierRuntime::onPlayerLoggedOut);
            LOG.debug("[RavenCourierRuntime] Registered server tick + interaction + death + logout listeners");
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
    public static boolean requestRetryDelivery(@NotNull MinecraftServer server, @NotNull UUID senderUuid, long jobId) {
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
                LOG.debug("[RavenCourierRuntime] requestRetryDelivery: sender={} requested retry for jobId={}", senderUuid, jobId);
                try {
                    // Best-effort: dispatch immediately so UI feels responsive.
                    dispatchPendingDeliveries(server, false);
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

    private static void onServerTick(@NotNull ServerTickEvent.Post event) {
        try {
            MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }

            serverTickCounter++;
            if ((serverTickCounter % DISPATCH_INTERVAL_TICKS) != 0) {
                return;
            }

            boolean doFullScan = (serverTickCounter % FULL_RECONCILE_INTERVAL_TICKS) == 0;
            dispatchPendingDeliveries(server, doFullScan);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onServerTick failed safely", t);
        }
    }

    private static void onPlayerLoggedOut(@NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }

            MinecraftServer server = player.server;
            if (server == null) {
                return;
            }

            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                return;
            }

            RavenCourierData data = RavenCourierData.get(overworld);
            UUID recipientUuid = player.getUUID();
            List<RavenCourierData.DeliveryJob> jobs = data.getJobsForRecipient(recipientUuid);
            if (jobs.isEmpty()) {
                return;
            }

            for (RavenCourierData.DeliveryJob job : jobs) {
                if (job == null || job.failed || !job.inFlight || job.courierRavenUuid == null) {
                    continue;
                }

                RavenEntity raven = findCourierRavenByUuid(server, job.courierRavenUuid);
                if (raven == null || !raven.isAlive() || raven.isRemoved() || !isCourierRaven(raven)) {
                    job.inFlight = false;
                    job.courierRavenUuid = null;
                    data.setDirty();
                    continue;
                }

                ServerLevel ravenLevel = (raven.level() instanceof ServerLevel sl) ? sl : overworld;

                // Mailbox deliveries are server-independent and should continue even if the recipient logs out.
                if (job.mailboxDeposited || isCourierMailboxModeActive(raven)) {
                    continue;
                }

                // If the recipient just logged out mid-delivery, redirect to mailbox if possible.
                boolean redirected = tryDeliverOfflineRecipientToMailbox(server, ravenLevel, data, raven, job);
                if (redirected) {
                    continue;
                }

                cancelInFlightCourierDelivery(server, ravenLevel, data, raven, job, "recipient logged out (re-queued)");
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] onPlayerLoggedOut failed safely: {}", t.toString());
        }
    }

    private static void dispatchPendingDeliveries(@NotNull MinecraftServer server, boolean fullScan) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                LOG.warn("[RavenCourierRuntime] dispatchPendingDeliveries: overworld is null; aborting dispatch.");
                return;
            }

            RavenCourierData data = RavenCourierData.get(overworld);
            if (!fullScan && !data.hasAnyJobs()) {
                return;
            }
            List<RavenCourierData.DeliveryJob> allJobs = data.getAllJobsFlat();

            Set<UUID> busySenders = new HashSet<>();
            Set<UUID> busyRecipients = new HashSet<>();

            if (allJobs.isEmpty() && !fullScan) {
                return;
            }

            reconcileJobsWithExistingCourierRavens(server, overworld, data, allJobs, busySenders, busyRecipients, fullScan);

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

                // Failed jobs may be auto-retried based on reason + config interval.
                if (job.failed) {
                    boolean autoRetryReady = tryAutoRetryFailedJob(overworld, data, job);
                    if (!autoRetryReady) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("[RavenCourierRuntime] dispatch: jobId={} is failed; skipping until retry.", job.jobId);
                        }
                        continue;
                    }
                }

                // Safety cleanup: mailbox-deposited jobs are already complete.
                if (job.mailboxDeposited) {
                    RavenEntity courier = null;
                    try {
                        if (job.courierRavenUuid != null) {
                            courier = findCourierRavenByUuid(server, job.courierRavenUuid);
                        }
                    } catch (Throwable ignored) {
                    }

                    // Keep the job around while the mailbox courier raven is still in-world so it can
                    // complete its 2-second dwell + FX despawn. If the courier is missing (crash/restart),
                    // remove the job immediately to prevent duplication.
                    if (courier == null || courier.isRemoved() || !courier.isAlive()) {
                        data.removeJob(job.jobId, job.recipientUuid);
                        if (job.hasSenderPerchAssignment()) {
                            maybeRespawnSenderPerchRavenForJob(overworld, job, false);
                        }
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
                    boolean dispatchedMailbox = tryDeliverQueuedJobToOfflineRecipientMailbox(server, overworld, data, job);
                    if (dispatchedMailbox) {
                        if (senderId != null) {
                            busySenders.add(senderId);
                        }
                        busyRecipients.add(recipientId);

                        dispatchedCount++;
                        if (dispatchedCount >= MAX_NEW_DISPATCHES_PER_PASS) {
                            break;
                        }
                    }
                    continue;
                }

                ServerLevel targetLevel = recipient.serverLevel();
                if (targetLevel == null || targetLevel.isClientSide()) {
                    continue;
                }

                RavenEntity raven = spawnCourierRavenForJob(targetLevel, recipient, job);
                if (raven == null) {
                    continue;
                }

                if (job.hasSenderPerchAssignment()) {
                    despawnSenderPerchRavenForDispatch(server, job);
                }

                logToJobParticipants(
                        targetLevel,
                        job,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.job.dispatched_online",
                        job.jobId
                );

                job.inFlight = true;
                job.courierRavenUuid = raven.getUUID();
                data.setDirty();
                RavenBadgeRuntime.onCourierJobUpdated(targetLevel, job.senderUuid, RavenBadgeEventType.NONE);

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
                LOG.debug("[RavenCourierRuntime] Dispatched {} courier raven(s) this tick.", dispatchedCount);
            }

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] dispatchPendingDeliveries failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Courier raven spawn helpers
    // ---------------------------------------------------------------------

    /**
     * Timeout/failure (non-death) handling:
     *  - Mark job as failed in RavenCourierData (persisted).
     *  - Do NOT drop scroll.
     *  - Do NOT remove job.
     *  - Despawn raven (with FX if possible).
     */
    private static void handleCourierRavenTimeout(@NotNull ServerLevel level,
                                                  @NotNull RavenCourierData data,
                                                  @NotNull RavenEntity raven,
                                                  @NotNull RavenCourierData.DeliveryJob job) {
        handleCourierRavenFailure(level, data, raven, job, FAILURE_REASON_TIMEOUT, "timeout", false);
    }

    private static void handleCourierRavenFailure(@NotNull ServerLevel level,
                                                  @NotNull RavenCourierData data,
                                                  @NotNull RavenEntity raven,
                                                  @NotNull RavenCourierData.DeliveryJob job,
                                                  @NotNull String failureReason,
                                                  @NotNull String reasonTag,
                                                  boolean spawnFeathers) {
        try {
            long now = level.getGameTime();

            // Mark as failed (persisted) and unlock inFlight.
            boolean marked = data.markJobFailed(job.jobId, job.recipientUuid, failureReason, now);
            if (!marked) {
                LOG.warn("[RavenCourierRuntime] handleCourierRavenFailure: failed to mark jobId={} as failed (reason='{}'; still despawning raven).",
                        job.jobId, failureReason);
            }

            try {
                LOG.info("[RavenCourierRuntime] Courier job failed jobId={} reason='{}' sender='{}' recipient='{}'",
                        job.jobId,
                        failureReason,
                        job.senderName,
                        job.recipientName
                );
            } catch (Throwable ignored) {
            }

            logToJobParticipants(
                    level,
                    job,
                    RavenLogCategory.COURIER,
                    "log.featheredfriend.courier.job.failed_reason",
                    job.jobId,
                    failureReason
            );
            job.courierRavenUuid = null;
            data.setDirty();

            // Find a context player for despawn FX (recipient -> sender -> any).
            ServerPlayer contextPlayer = findBestContextPlayer(level.getServer(), job.senderUuid, job.recipientUuid);
            if (contextPlayer != null) {
                despawnCourierRaven(level, contextPlayer, raven, reasonTag + " (job marked failed)", spawnFeathers);
            } else {
                LOG.warn("[RavenCourierRuntime] handleCourierRavenFailure: no context player; discarding raven id={} silently (reason='{}').",
                        raven.getId(), failureReason);
                clearCourierFlags(raven);
                raven.discard();
            }

            LOG.debug("[RavenCourierRuntime] handleCourierRavenFailure: jobId={} marked failed (reason='{}'); courier raven id={} despawned.",
                    job.jobId, failureReason, raven.getId());

            // If this job originated from a Raven Chest perch, restore the perched sender raven
            // to hold the queued payload while waiting for retry/online recipient.
            maybeRespawnSenderPerchRavenForJob(level, job, true);
            RavenBadgeRuntime.onCourierJobUpdated(level, job.senderUuid, RavenBadgeEventType.NONE);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] handleCourierRavenFailure failed safely for jobId={} reason='{}'",
                    job.jobId, failureReason, t);
            try {
                clearCourierFlags(raven);
                raven.discard();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean tryAutoRetryFailedJob(@NotNull ServerLevel level,
                                                 @NotNull RavenCourierData data,
                                                 @NotNull RavenCourierData.DeliveryJob job) {
        try {
            if (!job.failed) {
                return true;
            }
            if (!isAutoRetryFailureReason(job.lastFailureReason)) {
                return false;
            }

            int retrySeconds = FFServerConfig.getCourierTimeoutRetrySeconds();
            if (retrySeconds <= 0) {
                return false;
            }

            long retryIntervalTicks = Math.max(20L, (long) retrySeconds * 20L);
            long now = level.getGameTime();
            long last = Math.max(0L, job.lastFailureGameTime);

            if (last > 0L && (now - last) < retryIntervalTicks) {
                return false;
            }

            String previousReason = job.lastFailureReason;
            job.failed = false;
            job.inFlight = false;
            job.lastFailureReason = "";
            job.lastFailureGameTime = 0L;
            job.courierRavenUuid = null;
            data.setDirty();

            if (LOG.isDebugEnabled()) {
                LOG.debug("[RavenCourierRuntime] auto-retry unlocked failed jobId={} reason='{}' retrySeconds={}",
                        job.jobId,
                        previousReason,
                        retrySeconds);
            }

            return true;
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] tryAutoRetryFailedJob failed safely for jobId={}: {}",
                    job.jobId, t.toString());
            return false;
        }
    }

    private static boolean isAutoRetryFailureReason(@Nullable String reason) {
        if (reason == null) {
            return false;
        }
        String normalized = reason.trim().toLowerCase(Locale.ROOT);
        return normalized.equals(FAILURE_REASON_TIMEOUT)
                || normalized.equals(FAILURE_REASON_PAYLOAD_PROTECTED_RETRY)
                || normalized.equals(FAILURE_REASON_PAYLOAD_PROTECTED_RETRY_LEGACY)
                || normalized.equals(FAILURE_REASON_SENDER_RECALL_REQUESTED);
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

    private static void logToJobParticipants(@NotNull ServerLevel level,
                                             @NotNull RavenCourierData.DeliveryJob job,
                                             @NotNull RavenLogCategory category,
                                             @NotNull String key,
                                             @Nullable Object... args) {
        try {
            if (job.senderUuid != null) {
                RavenLogService.logForPlayerKey(level, job.senderUuid, category, key, args);
            }
            if (job.recipientUuid != null && !job.recipientUuid.equals(job.senderUuid)) {
                RavenLogService.logForPlayerKey(level, job.recipientUuid, category, key, args);
            }
        } catch (Throwable ignored) {
        }
    }

    private record SenderPerchTarget(@NotNull ServerLevel level, @NotNull BlockPos chestPos) {
    }

    @Nullable
    private static SenderPerchTarget resolveSenderPerchTarget(@Nullable MinecraftServer server,
                                                              @NotNull RavenCourierData.DeliveryJob job) {
        try {
            if (server == null || !job.hasSenderPerchAssignment()) {
                return null;
            }

            ResourceLocation dimLoc = ResourceLocation.tryParse(job.senderPerchDimensionId);
            if (dimLoc == null) {
                return null;
            }
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
            ServerLevel targetLevel = server.getLevel(dimKey);
            if (targetLevel == null) {
                return null;
            }

            return new SenderPerchTarget(targetLevel, BlockPos.of(job.senderPerchBlockPos));
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] resolveSenderPerchTarget failed safely for jobId={}: {}",
                    job.jobId, t.toString());
            return null;
        }
    }

    private static boolean isSenderPerchRavenForJob(@NotNull RavenEntity raven,
                                                     @NotNull RavenCourierData.DeliveryJob job,
                                                     @NotNull SenderPerchTarget perchTarget) {
        try {
            if (job.senderUuid == null || !job.senderUuid.equals(raven.getOwnerUUID())) {
                return false;
            }
            if (!(raven.level() instanceof ServerLevel ravenLevel) || ravenLevel != perchTarget.level()) {
                return false;
            }

            CompoundTag root = raven.getPersistentData();
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return false;
            }
            if (!ffTag.getBoolean(NBT_RAVEN_CHEST_PERCH_ASSIGNED)) {
                return false;
            }
            if (!ffTag.contains(NBT_RAVEN_CHEST_PERCH_DIMENSION, Tag.TAG_STRING)
                    || !ffTag.contains(NBT_RAVEN_CHEST_PERCH_BLOCK_POS, Tag.TAG_LONG)) {
                return false;
            }
            String dim = ffTag.getString(NBT_RAVEN_CHEST_PERCH_DIMENSION);
            long pos = ffTag.getLong(NBT_RAVEN_CHEST_PERCH_BLOCK_POS);
            return dim != null
                    && dim.equals(job.senderPerchDimensionId)
                    && pos == job.senderPerchBlockPos;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    private static RavenEntity findSenderPerchRavenForJob(@NotNull RavenCourierData.DeliveryJob job,
                                                           @Nullable SenderPerchTarget perchTarget) {
        try {
            if (perchTarget == null) {
                return null;
            }

            AABB box = new AABB(perchTarget.chestPos()).inflate(2.0D, 3.0D, 2.0D);
            List<RavenEntity> candidates = perchTarget.level().getEntitiesOfClass(
                    RavenEntity.class,
                    box,
                    e -> e != null
                            && e.isAlive()
                            && !e.isRemoved()
                            && isSenderPerchRavenForJob(e, job, perchTarget)
            );

            if (candidates.isEmpty()) {
                return null;
            }

            Vec3 perchCenter = new Vec3(
                    perchTarget.chestPos().getX() + RAVEN_CHEST_PERCH_OFFSET_X,
                    perchTarget.chestPos().getY() + RAVEN_CHEST_PERCH_OFFSET_Y,
                    perchTarget.chestPos().getZ() + RAVEN_CHEST_PERCH_OFFSET_Z
            );
            RavenEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (RavenEntity candidate : candidates) {
                double dist = candidate.position().distanceToSqr(perchCenter);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidate;
                }
            }
            return best;
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] findSenderPerchRavenForJob failed safely for jobId={}: {}",
                    job.jobId, t.toString());
            return null;
        }
    }

    private static void despawnSenderPerchRavenForDispatch(@NotNull MinecraftServer server,
                                                           @NotNull RavenCourierData.DeliveryJob job) {
        try {
            if (!job.hasSenderPerchAssignment()) {
                return;
            }

            SenderPerchTarget perchTarget = resolveSenderPerchTarget(server, job);
            RavenEntity perched = findSenderPerchRavenForJob(job, perchTarget);
            if (perched == null) {
                return;
            }

            ServerPlayer senderOwner = null;
            try {
                if (job.senderUuid != null) {
                    senderOwner = server.getPlayerList().getPlayer(job.senderUuid);
                }
            } catch (Throwable ignored) {
            }

            if (perchTarget != null) {
                try {
                    TamedRaven tamed = perched.getTamedRavenModule();
                    if (tamed != null) {
                        String name = perched.getCustomName() == null
                                ? Component.translatable("entity.featheredfriend.raven").getString()
                                : perched.getCustomName().getString();
                        tamed.beginDespawnWithFx(perchTarget.level(), senderOwner, name, false);
                    } else {
                        perched.discard();
                    }
                } catch (Throwable t) {
                    LOG.warn("[RavenCourierRuntime] despawnSenderPerchRavenForDispatch: despawn FX failed safely for jobId={}: {}",
                            job.jobId, t.toString());
                    perched.discard();
                }
            } else {
                perched.discard();
            }

            LOG.debug("[RavenCourierRuntime] despawnSenderPerchRavenForDispatch: sender perch raven removed for dispatch jobId={}",
                    job.jobId);
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] despawnSenderPerchRavenForDispatch failed safely for jobId={}: {}",
                    job.jobId, t.toString());
        }
    }

    private static void writePerchAssignment(@NotNull RavenEntity raven, @NotNull SenderPerchTarget perchTarget) {
        try {
            CompoundTag root = raven.getPersistentData();
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            ffTag.putBoolean(NBT_RAVEN_CHEST_PERCH_ASSIGNED, true);
            ffTag.putString(NBT_RAVEN_CHEST_PERCH_DIMENSION, perchTarget.level().dimension().location().toString());
            ffTag.putLong(NBT_RAVEN_CHEST_PERCH_BLOCK_POS, perchTarget.chestPos().asLong());
            root.put(Constants.MOD_ID, ffTag);
        } catch (Throwable ignored) {
        }
    }

    private static void applyPerchPose(@NotNull ServerLevel level,
                                       @NotNull RavenEntity raven,
                                       @NotNull BlockPos chestPos) {
        float yaw = defaultYawFromChest(level, chestPos);
        float pitch = 0.0F;
        double x = chestPos.getX() + RAVEN_CHEST_PERCH_OFFSET_X;
        double y = chestPos.getY() + RAVEN_CHEST_PERCH_OFFSET_Y;
        double z = chestPos.getZ() + RAVEN_CHEST_PERCH_OFFSET_Z;

        raven.moveTo(x, y, z, yaw, pitch);
        raven.setYRot(yaw);
        raven.setYHeadRot(yaw);
        raven.yBodyRot = yaw;
        raven.setXRot(pitch);
        raven.setRavenChestPerchLockRotation(yaw, pitch);
        raven.setDeltaMovement(Vec3.ZERO);
        raven.setNoGravity(true);
        raven.setNoAi(false);
        raven.setAIState(RavenAIState.RAVEN_CHEST_PERCH);
        raven.setAnimMode(RavenAnimMode.NO_AIR);
    }

    private static float defaultYawFromChest(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            if (state.hasProperty(net.z2six.featheredfriend.block.RavenChestBlock.FACING)) {
                Direction facing = state.getValue(net.z2six.featheredfriend.block.RavenChestBlock.FACING);
                return Mth.wrapDegrees(facing.toYRot());
            }
        } catch (Throwable ignored) {
        }
        return 0.0F;
    }

    private static void maybeRespawnSenderPerchRavenForJob(@NotNull ServerLevel anyLevel,
                                                           @NotNull RavenCourierData.DeliveryJob job,
                                                           boolean carryScroll) {
        try {
            if (!FFServerConfig.isSuspiciousChestEnabled()) {
                return;
            }
            MinecraftServer server = anyLevel.getServer();
            SenderPerchTarget perchTarget = resolveSenderPerchTarget(server, job);
            if (perchTarget == null) {
                return;
            }

            perchTarget.level().getChunk(perchTarget.chestPos().getX() >> 4, perchTarget.chestPos().getZ() >> 4);
            if (!perchTarget.level().getBlockState(perchTarget.chestPos()).is(FFBlocks.RAVEN_CHEST.get())) {
                return;
            }

            RavenEntity existing = findSenderPerchRavenForJob(job, perchTarget);
            if (existing != null) {
                try {
                    existing.setRavenArmorVisual(RavenArmorVisual.fromId(job.ravenArmorVisualId));
                    existing.setRavenVariant(carryScroll ? RavenVariant.SCROLL : RavenVariant.NORMAL);
                    String ravenName = (job.ravenName == null || job.ravenName.isBlank())
                            ? Component.translatable("entity.featheredfriend.raven").getString()
                            : job.ravenName;
                    ensureRavenName(existing, ravenName);
                    writePerchAssignment(existing, perchTarget);
                    applyPerchPose(perchTarget.level(), existing, perchTarget.chestPos());
                    existing.setPersistenceRequired();
                } catch (Throwable ignored) {
                }
                return;
            }

            RavenEntity raven = FFEntities.RAVEN.get().create(perchTarget.level());
            if (raven == null) {
                return;
            }

            try {
                raven.setTame(true, true);
            } catch (Throwable ignored) {
            }
            try {
                if (job.senderUuid != null) {
                    raven.setOwnerUUID(job.senderUuid);
                }
            } catch (Throwable ignored) {
            }
            try {
                raven.setRavenArmorVisual(RavenArmorVisual.fromId(job.ravenArmorVisualId));
            } catch (Throwable ignored) {
            }
            try {
                raven.setRavenVariant(carryScroll ? RavenVariant.SCROLL : RavenVariant.NORMAL);
            } catch (Throwable ignored) {
            }
            try {
                float clamped = Mth.clamp(raven.getMaxHealth(), 1.0F, raven.getMaxHealth());
                raven.setHealth(clamped);
            } catch (Throwable ignored) {
            }

            String ravenName = (job.ravenName == null || job.ravenName.isBlank())
                    ? Component.translatable("entity.featheredfriend.raven").getString()
                    : job.ravenName;
            ensureRavenName(raven, ravenName);
            writePerchAssignment(raven, perchTarget);
            applyPerchPose(perchTarget.level(), raven, perchTarget.chestPos());
            raven.setPersistenceRequired();
            perchTarget.level().addFreshEntity(raven);

            LOG.debug("[RavenCourierRuntime] maybeRespawnSenderPerchRavenForJob: spawned perched sender raven id={} for jobId={} carryScroll={}",
                    raven.getId(), job.jobId, carryScroll);
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] maybeRespawnSenderPerchRavenForJob failed safely for jobId={}: {}",
                    job.jobId, t.toString());
        }
    }

    private static void reconcileJobsWithExistingCourierRavens(@NotNull MinecraftServer server,
                                                               @NotNull ServerLevel overworld,
                                                               @NotNull RavenCourierData data,
                                                               @NotNull List<RavenCourierData.DeliveryJob> allJobs,
                                                               @NotNull Set<UUID> busySenders,
                                                               @NotNull Set<UUID> busyRecipients,
                                                               boolean fullScan) {
        try {
            boolean dirty = false;

            if (!fullScan) {
                for (RavenCourierData.DeliveryJob job : allJobs) {
                    if (job == null) {
                        continue;
                    }

                    if (job.courierRavenUuid == null) {
                        job.inFlight = false;
                        continue;
                    }

                    RavenEntity raven = findCourierRavenByUuid(server, job.courierRavenUuid);
                    if (raven == null || !raven.isAlive() || raven.isRemoved()) {
                        job.courierRavenUuid = null;
                        job.inFlight = false;
                        dirty = true;
                        continue;
                    }

                    if (!isCourierRaven(raven)) {
                        job.courierRavenUuid = null;
                        job.inFlight = false;
                        dirty = true;
                        continue;
                    }

                    long jobId = getCourierJobIdFromRaven(raven);
                    if (jobId != job.jobId) {
                        job.courierRavenUuid = null;
                        job.inFlight = false;
                        dirty = true;
                        continue;
                    }

                    ServerLevel ravenLevel = (raven.level() instanceof ServerLevel level) ? level : overworld;

                    if (job.failed) {
                        ServerPlayer ctx = findBestContextPlayer(server, job.senderUuid, job.recipientUuid);
                        job.inFlight = false;
                        if (job.courierRavenUuid != null) {
                            job.courierRavenUuid = null;
                            dirty = true;
                        }

                        if (ctx != null) {
                            LOG.warn("[RavenCourierRuntime] reconcile: jobId={} is failed but courier raven id={} exists; despawning raven.",
                                    job.jobId, raven.getId());
                            despawnCourierRaven(ravenLevel, ctx, raven, "job already failed (cleanup)");
                        } else {
                            LOG.warn("[RavenCourierRuntime] reconcile: jobId={} failed; discarding courier raven id={} silently (no context player).",
                                    job.jobId, raven.getId());
                            clearCourierFlags(raven);
                            raven.discard();
                        }
                        continue;
                    }

                    if (tickMailboxCourierMode(server, ravenLevel, data, raven, job)) {
                        dirty = true;
                        continue;
                    }

                    if (handleInFlightRecipientOffline(server, ravenLevel, data, raven, job)) {
                        dirty = true;
                        continue;
                    }

                    if (checkCourierLifetime(ravenLevel, data, raven, job)) {
                        continue;
                    }
                    checkCourierThreat(ravenLevel, raven, job);

                    markJobInFlight(job, busySenders, busyRecipients);
                }

                if (dirty) {
                    data.setDirty();
                }
                return;
            }

            Map<Long, RavenCourierData.DeliveryJob> jobsById = new HashMap<>();
            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job != null) {
                    jobsById.put(job.jobId, job);
                }
            }

            Set<Long> matchedJobIds = new HashSet<>();

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

                        matchedJobIds.add(jobId);

                        if (job.courierRavenUuid == null || !job.courierRavenUuid.equals(raven.getUUID())) {
                            job.courierRavenUuid = raven.getUUID();
                            dirty = true;
                        }

                        if (job.failed) {
                            ServerPlayer ctx = findBestContextPlayer(server, job.senderUuid, job.recipientUuid);
                            job.inFlight = false;
                            if (job.courierRavenUuid != null) {
                                job.courierRavenUuid = null;
                                dirty = true;
                            }

                            if (ctx != null) {
                                LOG.warn("[RavenCourierRuntime] reconcile: jobId={} is failed but courier raven id={} exists; despawning raven.", job.jobId, raven.getId());
                                despawnCourierRaven(level, ctx, raven, "job already failed (cleanup)");
                            } else {
                                LOG.warn("[RavenCourierRuntime] reconcile: jobId={} failed; discarding courier raven id={} silently (no context player).", job.jobId, raven.getId());
                                clearCourierFlags(raven);
                                raven.discard();
                            }
                            continue;
                        }

                        if (tickMailboxCourierMode(server, level, data, raven, job)) {
                            dirty = true;
                            continue;
                        }

                        if (handleInFlightRecipientOffline(server, level, data, raven, job)) {
                            dirty = true;
                            continue;
                        }

                        if (checkCourierLifetime(level, data, raven, job)) {
                            continue;
                        }
                        checkCourierThreat(level, raven, job);

                        markJobInFlight(job, busySenders, busyRecipients);
                    }

                } catch (Throwable levelErr) {
                    LOG.warn("[RavenCourierRuntime] reconcile: level scan failed safely: {}", levelErr.toString());
                }
            }

            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job == null) {
                    continue;
                }
                if (job.courierRavenUuid != null && !matchedJobIds.contains(job.jobId)) {
                    job.courierRavenUuid = null;
                    job.inFlight = false;
                    dirty = true;
                } else if (job.courierRavenUuid == null) {
                    job.inFlight = false;
                }
            }

            if (dirty) {
                data.setDirty();
            }
        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] reconcileJobsWithExistingCourierRavens failed safely", t);
        }
    }

    @Nullable
    private static RavenEntity findCourierRavenByUuid(@NotNull MinecraftServer server, @NotNull UUID uuid) {
        try {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof RavenEntity raven) {
                    return raven;
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] findCourierRavenByUuid failed safely for uuid={}: {}", uuid, t.toString());
        }
        return null;
    }

    private static boolean tickMailboxCourierMode(@NotNull MinecraftServer server,
                                                 @NotNull ServerLevel level,
                                                 @NotNull RavenCourierData data,
                                                 @NotNull RavenEntity raven,
                                                 @NotNull RavenCourierData.DeliveryJob job) {
        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return false;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || !ffTag.getBoolean(NBT_COURIER_MAILBOX_MODE)) {
                return false;
            }

            long now = level.getGameTime();
            long endAt = ffTag.getLong(NBT_COURIER_MAILBOX_ATTEMPT_END_AT);
            if (endAt <= 0L) {
                endAt = now + COURIER_MAILBOX_DWELL_TICKS;
                ffTag.putLong(NBT_COURIER_MAILBOX_ATTEMPT_END_AT, endAt);
                root.put(Constants.MOD_ID, ffTag);
            }

            // If the payload has been deposited, swap the raven back to a normal (non-scroll) variant after 1 second
            // so the player sees a clear "delivered" transition before the 2-second despawn.
            try {
                int outcomePreview = ffTag.contains(NBT_COURIER_MAILBOX_OUTCOME, Tag.TAG_INT)
                        ? ffTag.getInt(NBT_COURIER_MAILBOX_OUTCOME)
                        : COURIER_MAILBOX_OUTCOME_NONE;
                if (outcomePreview == COURIER_MAILBOX_OUTCOME_DELIVERED) {
                    long depositedAt = ffTag.contains(NBT_COURIER_MAILBOX_DEPOSIT_AT, Tag.TAG_LONG)
                            ? ffTag.getLong(NBT_COURIER_MAILBOX_DEPOSIT_AT)
                            : 0L;
                    if (depositedAt > 0L && (now - depositedAt) >= 20L) {
                        if (raven.getRavenVariant() == RavenVariant.SCROLL) {
                            raven.setRavenVariant(RavenVariant.NORMAL);
                        }
                    }
                }

                // Keep mailbox couriers in-air and pinned while waiting.
                raven.setDeltaMovement(Vec3.ZERO);
                raven.hurtMarked = true;
                raven.setNoGravity(true);
                if (raven.getAnimMode() != RavenAnimMode.IN_AIR) {
                    raven.setAnimMode(RavenAnimMode.IN_AIR);
                }
            } catch (Throwable ignored) {
            }

            if (now < endAt) {
                return false;
            }

            int outcome = ffTag.contains(NBT_COURIER_MAILBOX_OUTCOME, Tag.TAG_INT)
                    ? ffTag.getInt(NBT_COURIER_MAILBOX_OUTCOME)
                    : COURIER_MAILBOX_OUTCOME_NONE;

            boolean hadSenderPerchAssignment = job.hasSenderPerchAssignment();

            if (outcome == COURIER_MAILBOX_OUTCOME_DELIVERED) {
                logToJobParticipants(
                        level,
                        job,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.job.completed_mailbox",
                        job.jobId
                );

                data.removeJob(job.jobId, job.recipientUuid);

                ServerPlayer ctx = findBestContextPlayer(server, job.senderUuid, job.recipientUuid);
                if (ctx != null) {
                    despawnCourierRaven(level, ctx, raven, "delivery complete: mailbox deposit");
                } else {
                    clearCourierFlags(raven);
                    raven.discard();
                }

                if (hadSenderPerchAssignment) {
                    maybeRespawnSenderPerchRavenForJob(level, job, false);
                }

                try {
                    LOG.info("[RavenCourierRuntime] Mailbox delivery completed jobId={} sender='{}' recipient='{}'",
                            job.jobId, job.senderName, job.recipientName);
                } catch (Throwable ignored) {
                }

                return true;
            }

            // Missing/full/unknown -> re-queue and try next mailbox later (or stay queued if none).
            job.inFlight = false;
            job.courierRavenUuid = null;
            data.setDirty();

            boolean hasAnyRemainingMailbox = false;
            try {
                if (job.senderUuid != null && job.recipientUuid != null) {
                    MailboxRegistryData registry = MailboxRegistryData.get(level);
                    hasAnyRemainingMailbox = registry.getKnownMailboxCount(job.senderUuid, job.recipientUuid) > 0;
                }
            } catch (Throwable ignored) {
            }

            if (hadSenderPerchAssignment && !hasAnyRemainingMailbox) {
                maybeRespawnSenderPerchRavenForJob(level, job, true);
            }

            ServerPlayer ctx = findBestContextPlayer(server, job.senderUuid, job.recipientUuid);
            if (ctx != null) {
                despawnCourierRaven(level, ctx, raven, "mailbox delivery attempt finished");
            } else {
                clearCourierFlags(raven);
                raven.discard();
            }

            return true;

        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenCourierRuntime.tickMailboxCourierMode", 10_000L)) {
                LOG.warn("[RavenCourierRuntime] tickMailboxCourierMode failed safely for raven id={} jobId={}",
                        raven.getId(), job.jobId, t);
            } else {
                LOG.debug("[RavenCourierRuntime] tickMailboxCourierMode failed safely for raven id={} jobId={}: {}",
                        raven.getId(), job.jobId, t.toString());
            }
            return false;
        }
    }

    /**
     * If a courier raven is currently in-flight but the recipient goes offline, stop the delivery attempt.
     *
     * Current behavior:
     *  - Despawn the courier raven (best-effort FX).
     *  - Clear inFlight + courierRavenUuid so the job becomes queued again.
     *  - If the job has a sender perch assignment, restore the perched raven carrying the scroll.
     *
     * Future behavior (mailbox):
     *  - If an offline-recipient mailbox is registered, deposit the scroll there instead of re-queuing.
     */
    private static boolean cancelInFlightCourierDelivery(@NotNull MinecraftServer server,
                                                         @NotNull ServerLevel level,
                                                         @NotNull RavenCourierData data,
                                                         @NotNull RavenEntity raven,
                                                         @NotNull RavenCourierData.DeliveryJob job,
                                                         @NotNull String reason) {
        try {
            job.inFlight = false;
            job.courierRavenUuid = null;
            data.setDirty();

            ServerPlayer ctx = findBestContextPlayer(server, job.senderUuid, job.recipientUuid);
            if (ctx != null) {
                despawnCourierRaven(level, ctx, raven, reason);
            } else {
                clearCourierFlags(raven);
                raven.discard();
            }

            if (job.hasSenderPerchAssignment()) {
                maybeRespawnSenderPerchRavenForJob(level, job, true);
            }
            RavenBadgeRuntime.onCourierJobUpdated(level, job.senderUuid, RavenBadgeEventType.NONE);
            return true;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenCourierRuntime.cancelInFlightCourierDelivery", 10_000L)) {
                LOG.warn("[RavenCourierRuntime] cancelInFlightCourierDelivery failed safely jobId={} reason='{}'",
                        job.jobId, reason, t);
            } else {
                LOG.debug("[RavenCourierRuntime] cancelInFlightCourierDelivery failed safely jobId={} reason='{}': {}",
                        job.jobId, reason, t.toString());
            }
            return false;
        }
    }

    private static boolean handleInFlightRecipientOffline(@NotNull MinecraftServer server,
                                                         @NotNull ServerLevel level,
                                                         @NotNull RavenCourierData data,
                                                         @NotNull RavenEntity raven,
                                                         @NotNull RavenCourierData.DeliveryJob job) {
        try {
            if (job.failed) {
                return false;
            }
            if (job.recipientUuid == null) {
                return false;
            }

            ServerPlayer recipient = server.getPlayerList().getPlayer(job.recipientUuid);
            if (recipient != null && recipient.isAlive() && !recipient.isRemoved()) {
                return false;
            }

            boolean redirected = tryDeliverOfflineRecipientToMailbox(server, level, data, raven, job);
            if (redirected) {
                return true;
            }

            if (isCourierMailboxModeActive(raven)) {
                return false;
            }

            return cancelInFlightCourierDelivery(server, level, data, raven, job, "recipient offline (re-queued)");
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenCourierRuntime.handleInFlightRecipientOffline", 10_000L)) {
                LOG.warn("[RavenCourierRuntime] handleInFlightRecipientOffline failed safely jobId={}", job.jobId, t);
            } else {
                LOG.debug("[RavenCourierRuntime] handleInFlightRecipientOffline failed safely jobId={}: {}",
                        job.jobId, t.toString());
            }
            return false;
        }
    }

    /**
     * Mailbox system hook (not implemented yet).
     *
     * Return true when the scroll payload has been deposited and the job is completed,
     * so the in-flight courier raven can be despawned permanently.
     */
    private static boolean tryDeliverOfflineRecipientToMailbox(@NotNull MinecraftServer server,
                                                              @NotNull ServerLevel level,
                                                              @NotNull RavenCourierData data,
                                                              @NotNull RavenEntity raven,
                                                              @NotNull RavenCourierData.DeliveryJob job) {
        try {
            if (!FFServerConfig.isMailboxEnabled()) {
                return false;
            }
            if (job.failed) {
                return false;
            }
            if (job.senderUuid == null || job.recipientUuid == null) {
                return false;
            }
            if (isCourierMailboxModeActive(raven)) {
                return false;
            }

            MailboxRegistryData registry = MailboxRegistryData.get(level);
            MailboxRegistryData.MailboxLocation target = registry.getFirstKnownMailbox(job.senderUuid, job.recipientUuid);
            if (target == null) {
                return false;
            }

            ServerLevel targetLevel = resolveLevelFromId(server, target.dimensionId());
            if (targetLevel == null || targetLevel.isClientSide()) {
                return false;
            }

            BlockPos mailboxPos = BlockPos.of(target.blockPos());
            RavenEntity redirected = spawnCourierRavenForJobAtMailbox(targetLevel, mailboxPos, job);
            if (redirected == null) {
                return false;
            }

            startCourierMailboxAttempt(server, targetLevel, data, redirected, job, job.senderUuid, job.recipientUuid, target);

            job.courierRavenUuid = redirected.getUUID();
            job.inFlight = true;
            data.setDirty();

            clearCourierFlags(raven);
            raven.discard();

            logToJobParticipants(
                    targetLevel,
                    job,
                    RavenLogCategory.COURIER,
                    "log.featheredfriend.courier.job.redirected_to_mailbox",
                    job.jobId
            );

            try {
                LOG.info("[RavenCourierRuntime] Redirected courier jobId={} to mailbox (recipient offline) sender='{}' recipient='{}' mailboxDim='{}' mailboxPos={}",
                        job.jobId,
                        job.senderName,
                        job.recipientName,
                        target.dimensionId(),
                        BlockPos.of(target.blockPos()).toShortString()
                );
            } catch (Throwable ignored) {
            }

            return true;

        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenCourierRuntime.tryDeliverOfflineRecipientToMailbox", 10_000L)) {
                LOG.warn("[RavenCourierRuntime] tryDeliverOfflineRecipientToMailbox failed safely for jobId={}",
                        job.jobId, t);
            } else {
                LOG.debug("[RavenCourierRuntime] tryDeliverOfflineRecipientToMailbox failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }
            return false;
        }
    }

    /**
     * Mailbox system hook (not implemented yet) for queued jobs.
     *
     * Intended behavior:
     *  - If recipient is offline and has a known mailbox target, deposit the sealed scroll there and complete the job
     *    without spawning a courier raven.
     */
    private static boolean tryDeliverQueuedJobToOfflineRecipientMailbox(@NotNull MinecraftServer server,
                                                                       @NotNull ServerLevel level,
                                                                       @NotNull RavenCourierData data,
                                                                       @NotNull RavenCourierData.DeliveryJob job) {
        try {
            if (!FFServerConfig.isMailboxEnabled()) {
                return false;
            }
            if (job == null || job.failed) {
                return false;
            }
            if (job.senderUuid == null || job.recipientUuid == null) {
                return false;
            }

            if (job.mailboxDeposited) {
                // Job payload already deposited; keep the job until the courier raven finishes its dwell + FX despawn.
                // If the courier raven is missing (crash/restart), the dispatcher-level cleanup will remove the job.
                return false;
            }

            if (job.inFlight || job.courierRavenUuid != null) {
                return false;
            }

            MailboxRegistryData registry = MailboxRegistryData.get(level);
            MailboxRegistryData.MailboxLocation target = registry.getFirstKnownMailbox(job.senderUuid, job.recipientUuid);
            if (target == null) {
                return false;
            }

            ServerLevel targetLevel = resolveLevelFromId(server, target.dimensionId());
            if (targetLevel == null || targetLevel.isClientSide()) {
                return false;
            }

            BlockPos mailboxPos = BlockPos.of(target.blockPos());
            RavenEntity raven = spawnCourierRavenForJobAtMailbox(targetLevel, mailboxPos, job);
            if (raven == null) {
                return false;
            }

            if (job.hasSenderPerchAssignment()) {
                despawnSenderPerchRavenForDispatch(server, job);
            }

            startCourierMailboxAttempt(server, targetLevel, data, raven, job, job.senderUuid, job.recipientUuid, target);

            job.inFlight = true;
            job.courierRavenUuid = raven.getUUID();
            data.setDirty();

            logToJobParticipants(
                    targetLevel,
                    job,
                    RavenLogCategory.COURIER,
                    "log.featheredfriend.courier.job.dispatched_mailbox",
                    job.jobId
            );

            try {
                LOG.info("[RavenCourierRuntime] Dispatched courier jobId={} to mailbox (recipient offline) sender='{}' recipient='{}' mailboxDim='{}' mailboxPos={}",
                        job.jobId,
                        job.senderName,
                        job.recipientName,
                        target.dimensionId(),
                        BlockPos.of(target.blockPos()).toShortString()
                );
            } catch (Throwable ignored) {
            }

            return true;

        } catch (Throwable t) {
            long jobId = job == null ? -1L : job.jobId;
            if (FFLogThrottle.shouldLog("RavenCourierRuntime.tryDeliverQueuedJobToOfflineRecipientMailbox", 10_000L)) {
                LOG.warn("[RavenCourierRuntime] tryDeliverQueuedJobToOfflineRecipientMailbox failed safely for jobId={}",
                        jobId, t);
            } else {
                LOG.debug("[RavenCourierRuntime] tryDeliverQueuedJobToOfflineRecipientMailbox failed safely for jobId={}: {}",
                        jobId, t.toString());
            }
            return false;
        }
    }

    private static boolean isCourierMailboxModeActive(@NotNull RavenEntity raven) {
        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return false;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            return ffTag != null && ffTag.getBoolean(NBT_COURIER_MAILBOX_MODE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static @Nullable ServerLevel resolveLevelFromId(@NotNull MinecraftServer server, @NotNull String dimensionId) {
        try {
            ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionId);
            if (dimLoc == null) {
                return null;
            }
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
            return server.getLevel(dimKey);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static RavenEntity spawnCourierRavenForJobAtMailbox(@NotNull ServerLevel level,
                                                                @NotNull BlockPos mailboxPos,
                                                                @NotNull RavenCourierData.DeliveryJob job) {
        try {
            // Ensure the target chunk is available (mailboxes may be far from all players).
            try {
                level.getChunk(mailboxPos);
            } catch (Throwable ignored) {
            }

            RavenEntity raven = FFEntities.RAVEN.get().create(level);
            if (raven == null) {
                return null;
            }

            Vec3 spawnPos = null;
            float yaw = 0.0F;

            // Prefer: 1 block in front of the mailbox, facing the mailbox, floating in-air.
            try {
                BlockState mailboxState = level.getBlockState(mailboxPos);
                if (mailboxState != null && mailboxState.is(FFBlocks.MAILBOX.get())
                        && mailboxState.hasProperty(MailboxBlock.FACING)) {
                    Direction facing = mailboxState.getValue(MailboxBlock.FACING);
                    BlockPos front = mailboxPos.relative(facing);
                    if (level.getWorldBorder().isWithinBounds(front)
                            && level.isEmptyBlock(front)
                            && level.isEmptyBlock(front.above())) {
                        spawnPos = new Vec3(front.getX() + 0.5D, mailboxPos.getY() + 0.5D, front.getZ() + 0.5D);
                        yaw = facing.getOpposite().toYRot();
                    }
                }
            } catch (Throwable ignored) {
            }

            if (spawnPos == null) {
                int minY = level.getMinBuildHeight() + 1;
                int maxY = level.getMaxBuildHeight() - 2;
                int baseY = Mth.clamp(mailboxPos.getY() + 1, minY, maxY);

                spawnPos = findSpawnNearBase(level, mailboxPos.getX(), baseY, mailboxPos.getZ(), minY, maxY);
                if (spawnPos == null) {
                    spawnPos = new Vec3(mailboxPos.getX() + 0.5D, baseY, mailboxPos.getZ() + 0.5D);
                }
            }

            raven.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, yaw, 0.0F);
            try {
                raven.setYRot(yaw);
                raven.setYHeadRot(yaw);
                raven.yBodyRot = yaw;
                raven.setXRot(0.0F);
                raven.setDeltaMovement(Vec3.ZERO);
                raven.hurtMarked = true;
                raven.setNoGravity(true);
                if (raven.getAnimMode() != RavenAnimMode.IN_AIR) {
                    raven.setAnimMode(RavenAnimMode.IN_AIR);
                }
            } catch (Throwable ignored) {
            }

            try {
                raven.setTame(true, true);
            } catch (Throwable ignored) {
            }
            try {
                UUID ownerUuid = (job.senderUuid != null) ? job.senderUuid : job.recipientUuid;
                if (ownerUuid != null) {
                    raven.setOwnerUUID(ownerUuid);
                }
            } catch (Throwable ignored) {
            }
            try {
                raven.setRavenVariant(RavenVariant.SCROLL);
            } catch (Throwable ignored) {
            }
            try {
                raven.setRavenArmorVisual(RavenArmorVisual.fromId(job.ravenArmorVisualId));
            } catch (Throwable ignored) {
            }
            try {
                float maxHealth = Math.max(1.0F, raven.getMaxHealth());
                raven.setHealth(maxHealth);
            } catch (Throwable ignored) {
            }

            String ravenName = (job.ravenName != null && !job.ravenName.isEmpty())
                    ? job.ravenName
                    : Component.translatable("entity.featheredfriend.raven").getString();
            ensureRavenName(raven, ravenName);

            attachCourierJobToRaven(level, raven, job);

            try {
                Teleportation tp = raven.getTeleportation();
                if (tp != null) {
                    tp.startFadeInOnly("courier mailbox spawn", raven);
                }
            } catch (Throwable ignored) {
            }

            level.addFreshEntity(raven);

            // Defensive: ensure the client sees the fade-in initial alpha even on the spawn packet.
            try {
                Teleportation tp = raven.getTeleportation();
                if (tp != null) {
                    tp.setTeleportFadeAlpha(0, raven);
                }
            } catch (Throwable ignored) {
            }

            // Mailbox couriers should use the usual Enderpop teleport-style spawn FX.
            try {
                Vec3 fxPos = raven.position().add(0.0D, 0.6D, 0.0D);
                long fxSeed =
                        raven.getUUID().getLeastSignificantBits()
                                ^ (long) raven.tickCount
                                ^ mailboxPos.asLong()
                                ^ job.jobId
                                ^ 0xB8F3A2D1L;
                Teleportation teleportFx = new Teleportation(raven);
                teleportFx.spawnEnderpopBurst(
                        level,
                        fxPos.x,
                        fxPos.y,
                        fxPos.z,
                        fxSeed,
                        "courier mailbox spawn",
                        raven
                );
            } catch (Throwable ignored) {
            }

            logToJobParticipants(
                    level,
                    job,
                    RavenLogCategory.COURIER,
                    "log.featheredfriend.courier.raven_spawned_at_mailbox",
                    job.jobId
            );

            return raven;
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJobAtMailbox failed safely for jobId={}: {}",
                    job.jobId, t.toString());
            return null;
        }
    }

    private static void startCourierMailboxAttempt(@NotNull MinecraftServer server,
                                                   @NotNull ServerLevel level,
                                                   @NotNull RavenCourierData data,
                                                   @NotNull RavenEntity raven,
                                                   @NotNull RavenCourierData.DeliveryJob job,
                                                   @NotNull UUID observerUuid,
                                                   @NotNull UUID mailboxOwnerUuid,
                                                   @NotNull MailboxRegistryData.MailboxLocation target) {
        try {
            CompoundTag root = raven.getPersistentData();
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);

            ffTag.putBoolean(NBT_COURIER_MAILBOX_MODE, true);
            ffTag.putString(NBT_COURIER_MAILBOX_DIMENSION, target.dimensionId());
            ffTag.putLong(NBT_COURIER_MAILBOX_BLOCK_POS, target.blockPos());
            ffTag.putUUID(NBT_COURIER_MAILBOX_OWNER_UUID, mailboxOwnerUuid);
            ffTag.putString(NBT_COURIER_MAILBOX_OWNER_UUID_STR, mailboxOwnerUuid.toString());

            long now = level.getGameTime();
            ffTag.putLong(NBT_COURIER_MAILBOX_ATTEMPT_END_AT, now + COURIER_MAILBOX_DWELL_TICKS);

            // Safety: if this job already deposited its payload (e.g. crash/restart window),
            // do not deposit again. Let tickMailboxCourierMode clean up after the dwell time.
            if (job.mailboxDeposited) {
                ffTag.putInt(NBT_COURIER_MAILBOX_OUTCOME, COURIER_MAILBOX_OUTCOME_DELIVERED);
                ffTag.putLong(NBT_COURIER_MAILBOX_DEPOSIT_AT, Math.max(0L, job.mailboxDepositedGameTime));
                root.put(Constants.MOD_ID, ffTag);
                return;
            }

            // Keep the mailbox chunk entity-ticking while the courier raven is present.
            try {
                BlockPos mailboxPos = BlockPos.of(target.blockPos());
                ChunkPos chunkPos = new ChunkPos(mailboxPos);
                level.getChunkSource().addRegionTicket(
                        TicketType.FORCED,
                        chunkPos,
                        COURIER_MAILBOX_TICKET_RADIUS,
                        chunkPos,
                        true
                );
                ffTag.putLong(NBT_COURIER_MAILBOX_TICKET_CHUNK, chunkPos.toLong());
            } catch (Throwable ignored) {
            }

            // Evaluate mailbox presence immediately to trigger badge/log instantly.
            BlockPos mailboxPos = BlockPos.of(target.blockPos());
            boolean mailboxExistsForOwner = false;
            try {
                BlockState state = level.getBlockState(mailboxPos);
                if (state != null && state.is(FFBlocks.MAILBOX.get())) {
                    BlockEntity be = level.getBlockEntity(mailboxPos);
                    if (be instanceof MailboxBlockEntity mailboxBe) {
                        UUID beOwner = mailboxBe.getOwnerUuid();
                        mailboxExistsForOwner = mailboxOwnerUuid.equals(beOwner);
                    }
                }
            } catch (Throwable ignored) {
            }

            if (!mailboxExistsForOwner) {
                ffTag.putInt(NBT_COURIER_MAILBOX_OUTCOME, COURIER_MAILBOX_OUTCOME_MISSING);
                ffTag.remove(NBT_COURIER_MAILBOX_DEPOSIT_AT);
                root.put(Constants.MOD_ID, ffTag);

                try {
                    MailboxRegistryData.get(level).removeKnownMailbox(
                            observerUuid,
                            mailboxOwnerUuid,
                            target.dimensionId(),
                            target.blockPos()
                    );
                } catch (Throwable ignored) {
                }

                logToJobParticipants(
                        level,
                        job,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.mailbox.missing",
                        job.jobId
                );
                RavenBadgeRuntime.onCourierJobUpdated(level, job.senderUuid, RavenBadgeEventType.PLAYER_NOT_FOUND);
                return;
            }

            Item sealedScrollItem = BuiltInRegistries.ITEM.get(SEALED_SCROLL_ID);
            if (sealedScrollItem == null) {
                ffTag.putInt(NBT_COURIER_MAILBOX_OUTCOME, COURIER_MAILBOX_OUTCOME_FULL);
                ffTag.remove(NBT_COURIER_MAILBOX_DEPOSIT_AT);
                root.put(Constants.MOD_ID, ffTag);
                return;
            }

            ItemStack toDeposit = buildDeliveredScrollStack(
                    sealedScrollItem,
                    job,
                    job.recipientName,
                    job.recipientUuid,
                    true
            );

            boolean inserted = false;
            try {
                BlockEntity be = level.getBlockEntity(mailboxPos);
                if (be instanceof MailboxBlockEntity mailboxBe) {
                    inserted = mailboxBe.tryInsertFirstEmpty(toDeposit);
                }
            } catch (Throwable ignored) {
            }
            if (inserted) {
                job.mailboxDeposited = true;
                job.mailboxDepositedGameTime = now;
                data.setDirty();

                ffTag.putInt(NBT_COURIER_MAILBOX_OUTCOME, COURIER_MAILBOX_OUTCOME_DELIVERED);
                ffTag.putLong(NBT_COURIER_MAILBOX_DEPOSIT_AT, now);
                root.put(Constants.MOD_ID, ffTag);

                logToJobParticipants(
                        level,
                        job,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.mailbox.delivered",
                        job.jobId
                );
                RavenBadgeRuntime.onCourierJobUpdated(level, job.senderUuid, RavenBadgeEventType.DELIVERY_SUCCESS);
            } else {
                ffTag.putInt(NBT_COURIER_MAILBOX_OUTCOME, COURIER_MAILBOX_OUTCOME_FULL);
                ffTag.remove(NBT_COURIER_MAILBOX_DEPOSIT_AT);
                root.put(Constants.MOD_ID, ffTag);

                logToJobParticipants(
                        level,
                        job,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.mailbox.full",
                        job.jobId
                );
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] startCourierMailboxAttempt failed safely for jobId={}: {}",
                    job.jobId, t.toString());
        }
    }

    private static boolean checkCourierLifetime(@NotNull ServerLevel level,
                                                @NotNull RavenCourierData data,
                                                @NotNull RavenEntity raven,
                                                @NotNull RavenCourierData.DeliveryJob job) {
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

            if (RavenLinkRuntime.isRavenLinked(raven)) {
                long lastPauseTick = ffTag.getLong(NBT_COURIER_LINK_PAUSE_LAST_TICK);
                long anchorTick = lastPauseTick > 0L ? lastPauseTick : now;
                long elapsed = Math.max(0L, now - anchorTick);
                if (elapsed > 0L) {
                    despawnAt += elapsed;
                    ffTag.putLong(NBT_COURIER_DESPAWN_AT, despawnAt);
                }
                ffTag.putLong(NBT_COURIER_LINK_PAUSE_LAST_TICK, now);
                root.put(Constants.MOD_ID, ffTag);
                return false;
            }

            if (ffTag.contains(NBT_COURIER_LINK_PAUSE_LAST_TICK, Tag.TAG_LONG)) {
                ffTag.remove(NBT_COURIER_LINK_PAUSE_LAST_TICK);
                root.put(Constants.MOD_ID, ffTag);
            }

            if (now >= despawnAt) {
                LOG.debug("[RavenCourierRuntime] reconcile: lifetime expired for courier raven id={} jobId={} now={} despawnAt={}",
                        raven.getId(), job.jobId, now, despawnAt);

                // NEW semantics: mark job failed; do NOT remove; do NOT drop scroll.
                handleCourierRavenTimeout(level, data, raven, job);
                return true;
            }
        } catch (Throwable lifetimeErr) {
            LOG.warn("[RavenCourierRuntime] reconcile: lifetime check failed safely for raven id={} jobId={}: {}",
                    raven.getId(), job.jobId, lifetimeErr.toString());
        }
        return false;
    }

    private static void checkCourierThreat(@NotNull ServerLevel level,
                                           @NotNull RavenEntity raven,
                                           @NotNull RavenCourierData.DeliveryJob job) {
        try {
            if (RavenLinkRuntime.isRavenLinked(raven)) {
                return;
            }

            double detectionRadius = Math.max(
                    0.0D,
                    raven.getEffectiveThreatDetectionRadiusBlocks()
            );
            if (detectionRadius <= 0.0D) {
                detectionRadius = DEFAULT_COURIER_THREAT_SENSE_RADIUS_BLOCKS;
            }

            ThreatScanResult threats = scanCourierThreats(raven, detectionRadius, job.senderUuid, job.recipientUuid);
            if (threats.hasThreat()) {
                long now = level.getGameTime();
                CompoundTag root = raven.getPersistentData();
                CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
                long lastLoggedAt = ffTag.getLong(NBT_COURIER_LAST_THREAT_LOG_AT);

                if (lastLoggedAt <= 0L || (now - lastLoggedAt) >= COURIER_THREAT_LOG_COOLDOWN_TICKS) {
                    ffTag.putLong(NBT_COURIER_LAST_THREAT_LOG_AT, now);
                    root.put(Constants.MOD_ID, ffTag);

                    logToJobParticipants(
                            level,
                            job,
                            RavenLogCategory.COURIER,
                            "log.featheredfriend.courier.threat_detected",
                            job.jobId
                    );
                    RavenBadgeRuntime.onCourierJobUpdated(level, job.senderUuid, RavenBadgeEventType.HOSTILE_WITH_SCROLL);
                    LOG.debug("[RavenCourierRuntime] reconcile: threat near courier raven id={} jobId={} hostiles={} nonRecipientPlayers={}",
                            raven.getId(), job.jobId, threats.hostileMobCount, threats.nonRecipientPlayerCount);
                }
            }
        } catch (Throwable threatErr) {
            LOG.warn("[RavenCourierRuntime] reconcile: threat check failed safely for raven id={} jobId={}: {}",
                    raven.getId(), job.jobId, threatErr.toString());
        }
    }

    private static final class ThreatScanResult {
        final int hostileMobCount;
        final int nonRecipientPlayerCount;

        private ThreatScanResult(int hostileMobCount, int nonRecipientPlayerCount) {
            this.hostileMobCount = Math.max(0, hostileMobCount);
            this.nonRecipientPlayerCount = Math.max(0, nonRecipientPlayerCount);
        }

        private boolean hasThreat() {
            return hostileMobCount > 0 || nonRecipientPlayerCount > 0;
        }
    }

    @NotNull
    private static ThreatScanResult scanCourierThreats(@NotNull RavenEntity raven,
                                                       double radiusBlocks,
                                                       @Nullable UUID senderUuid,
                                                       @Nullable UUID recipientUuid) {
        try {
            if (!(raven.level() instanceof ServerLevel level) || level.isClientSide()) {
                return new ThreatScanResult(0, 0);
            }
            if (radiusBlocks <= 0.0D) {
                return new ThreatScanResult(0, 0);
            }

            AABB box = raven.getBoundingBox().inflate(radiusBlocks);
            double radiusSq = radiusBlocks * radiusBlocks;

            List<Mob> hostiles = level.getEntitiesOfClass(
                    Mob.class,
                    box,
                    mob -> mob != null
                            && mob.isAlive()
                            && !mob.isRemoved()
                            && (mob instanceof Enemy)
                            && mob.distanceToSqr(raven) <= radiusSq
            );

            List<ServerPlayer> nonRecipientPlayers = level.getEntitiesOfClass(
                    ServerPlayer.class,
                    box,
                    p -> p != null
                            && p.isAlive()
                            && !p.isRemoved()
                            && !p.isSpectator()
                            && p.distanceToSqr(raven) <= radiusSq
                            && (senderUuid == null || !senderUuid.equals(p.getUUID()))
                            && (recipientUuid == null || !recipientUuid.equals(p.getUUID()))
            );

            return new ThreatScanResult(hostiles.size(), nonRecipientPlayers.size());
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] scanCourierThreats failed safely for raven id={}: {}",
                    raven.getId(), t.toString());
            return new ThreatScanResult(0, 0);
        }
    }

    private static void markJobInFlight(@NotNull RavenCourierData.DeliveryJob job,
                                        @NotNull Set<UUID> busySenders,
                                        @NotNull Set<UUID> busyRecipients) {
        job.inFlight = true;
        if (job.senderUuid != null) {
            busySenders.add(job.senderUuid);
        }
        if (job.recipientUuid != null) {
            busyRecipients.add(job.recipientUuid);
        }
    }

    @Nullable
    private static RavenEntity spawnCourierRavenForJob(@NotNull ServerLevel level,
                                                       @NotNull ServerPlayer recipient,
                                                       @NotNull RavenCourierData.DeliveryJob job) {
        try {
            RavenEntity raven = FFEntities.RAVEN.get().create(level);
            if (raven == null) {
                LOG.error("[RavenCourierRuntime] spawnCourierRavenForJob: entity factory returned null for jobId={}", job.jobId);
                return null;
            }

            // Spawn right by the recipient (no path simulation).
            Vec3 spawnPos = findSpawnNearPlayer(level, recipient);
            if (spawnPos == null) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: no valid nearby spawn for recipient='{}' jobId={} -> not spawning.",
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
                UUID ownerUuid = (job.senderUuid != null) ? job.senderUuid : job.recipientUuid;
                if (ownerUuid != null) {
                    raven.setOwnerUUID(ownerUuid);
                }
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setOwnerUUID failed safely for jobId={}: {}", job.jobId, t.toString());
            }

            try {
                raven.setRavenVariant(RavenVariant.SCROLL);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setRavenVariant(SCROLL) failed safely for jobId={}: {}", job.jobId, t.toString());
            }

            try {
                RavenArmorVisual armorVisual = RavenArmorVisual.fromId(job.ravenArmorVisualId);
                raven.setRavenArmorVisual(armorVisual);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setRavenArmorVisual failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }
            try {
                ServerPlayer healthOwner = null;
                if (level.getServer() != null && job.senderUuid != null) {
                    healthOwner = level.getServer().getPlayerList().getPlayer(job.senderUuid);
                }
                if (healthOwner == null) {
                    healthOwner = recipient;
                }
                long now = level.getGameTime();
                float health = TamedRavenPlayerData.applyDespawnedHealthRegenAndGet(healthOwner, now);
                float maxHealth = Math.max(1.0F, raven.getMaxHealth());
                float clamped = Mth.clamp(health, 1.0F, maxHealth);
                raven.setHealth(clamped);
                TamedRavenPlayerData.setStoredRavenHealth(healthOwner, clamped, now);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: restoring stored health failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }

            String ravenName = (job.ravenName != null && !job.ravenName.isEmpty())
                    ? job.ravenName
                    : Component.translatable("entity.featheredfriend.raven").getString();
            ensureRavenName(raven, ravenName);

            attachCourierJobToRaven(level, raven, job);

            try {
                Teleportation tp = raven.getTeleportation();
                if (tp != null) {
                    tp.startFadeInOnly("courier spawn", raven);
                }
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: startFadeInOnly failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }

            level.addFreshEntity(raven);

            playCourierSpawnFx(level, recipient, raven);

            LOG.debug("[RavenCourierRuntime] spawnCourierRavenForJob: spawned courier raven id={} for jobId={} recipient='{}' at {} name='{}'",
                    raven.getId(), job.jobId, job.recipientName, raven.position(), ravenName);
            logToJobParticipants(
                    level,
                    job,
                    RavenLogCategory.COURIER,
                    "log.featheredfriend.courier.raven_spawned_at",
                    job.jobId,
                    raven.position()
            );

            return raven;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] spawnCourierRavenForJob failed safely for jobId={}", job.jobId, t);
            return null;
        }
    }

    @Nullable
    private static Vec3 findSpawnNearPlayer(@NotNull ServerLevel level, @NotNull ServerPlayer target) {
        try {
            int minY = level.getMinBuildHeight() + 1;
            int maxY = level.getMaxBuildHeight() - 2;

            int baseY = Mth.clamp(target.blockPosition().getY() + 1, minY, maxY);

            Vec3 look = target.getLookAngle();
            double lx = look.x;
            double lz = look.z;
            double len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1.0E-4D) {
                lx = 1.0D;
                lz = 0.0D;
            } else {
                lx /= len;
                lz /= len;
            }

            double frontDist = 2.8D;
            int frontX = Mth.floor(target.getX() + lx * frontDist);
            int frontZ = Mth.floor(target.getZ() + lz * frontDist);
            Vec3 frontSpawn = findSpawnNearBase(level, frontX, baseY, frontZ, minY, maxY);
            if (frontSpawn != null) {
                return frontSpawn;
            }

            BlockPos base = target.blockPosition();
            return findSpawnNearBase(level, base.getX(), baseY, base.getZ(), minY, maxY);
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] findSpawnNearPlayer failed safely: {}", t.toString());
        }
        return null;
    }

    @Nullable
    private static Vec3 findSpawnNearBase(@NotNull ServerLevel level,
                                          int baseX,
                                          int baseY,
                                          int baseZ,
                                          int minY,
                                          int maxY) {
        try {
            int[][] offsets = new int[][]{
                    {0, 0, 0},
                    {1, 0, 0},
                    {-1, 0, 0},
                    {0, 0, 1},
                    {0, 0, -1},
                    {1, 0, 1},
                    {-1, 0, -1},
                    {1, 0, -1},
                    {-1, 0, 1},
                    {0, 1, 0},
                    {0, 2, 0}
            };

            for (int[] o : offsets) {
                BlockPos pos = new BlockPos(baseX + o[0], Mth.clamp(baseY + o[1], minY, maxY), baseZ + o[2]);
                if (!level.getWorldBorder().isWithinBounds(pos)) {
                    continue;
                }
                if (level.isEmptyBlock(pos) && level.isEmptyBlock(pos.above())) {
                    return new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

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
            ffTag.remove(NBT_COURIER_LINK_PAUSE_LAST_TICK);

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

    private static void playCourierSpawnFx(@NotNull ServerLevel level,
                                           @NotNull ServerPlayer recipient,
                                           @NotNull RavenEntity raven) {
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
    private static Vec3 findCourierSpawnPos(@NotNull ServerLevel level,
                                            @NotNull ServerPlayer player,
                                            @NotNull RavenEntity simRaven,
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
                    LOG.debug("[RavenCourierRuntime] findCourierSpawnPos: CEILING FOUND at y={} -> pocketUnderCeiling={} jobId={} player='{}'",
                            ceilingY, pocketUnderCeiling, jobId, safePlayerName(player));
                    // Step 3: Spawn in center of pocket
                    return pocketUnderCeiling;
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
                LOG.debug("[RavenCourierRuntime] findCourierSpawnPos: fallback +15 pocket={} jobId={} player='{}'",
                        pocket, jobId, safePlayerName(player));
                return pocket;
            }

            LOG.warn("[RavenCourierRuntime] findCourierSpawnPos: no pocket found (ceilingY={}, baseY={}) jobId={} player='{}' feet={}",
                    ceilingY, baseY, jobId, safePlayerName(player), feet);
            return null;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] findCourierSpawnPos failed safely (jobId={})", jobId, t);
            return null;
        }
    }

    private static int scanFirstCeilingYWithin15(@NotNull ServerLevel level,
                                                 @NotNull ServerPlayer player,
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

                    LOG.debug("[RavenCourierRuntime] scanFirstCeilingYWithin15: HIT dy={} y={} block={} feetY={} player='{}'",
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
    private static Vec3 findFirst3x3x3PocketNear(@NotNull ServerLevel level,
                                                 @NotNull ServerPlayer player,
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
                            LOG.debug("[RavenCourierRuntime] findFirst3x3x3PocketNear: FOUND pocket={} baseY={} off=({}, {}) player='{}'",
                                    pocket, baseY, dx, dz, safePlayerName(player));
                            return pocket;
                        }
                    }
                }
            }

            LOG.debug("[RavenCourierRuntime] findFirst3x3x3PocketNear: NONE baseY={} center=({}, {}) radius={} player='{}'",
                    baseY, cx, cz, maxR, safePlayerName(player));
            return null;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] findFirst3x3x3PocketNear failed safely", t);
            return null;
        }
    }

    private static boolean is3x3x3Air(@NotNull ServerLevel level, int cx, int cy, int cz) {
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

            if (!isCourierRaven(raven)) {
                return;
            }

            // Mailbox-delivery couriers deposit server-side and should not be interactable (prevents duplication).
            if (isCourierMailboxModeActive(raven)) {
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
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
                if (!RavenLinkRuntime.isRavenLinked(raven)) {
                    despawnCourierRaven(serverLevel, serverPlayer, raven, "orphaned courier (no job)");
                }
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            boolean delivered = giveSealedScrollToPlayerFromJob(serverLevel, serverPlayer, job);
            if (!delivered) {
                event.setCancellationResult(InteractionResult.PASS);
                return;
            }

            logToJobParticipants(
                    serverLevel,
                    job,
                    RavenLogCategory.COURIER,
                    "log.featheredfriend.courier.job.completed_retrieved",
                    job.jobId
            );

            // COMPLETE ONLY HERE (RMB path)
            data.removeJob(job.jobId, job.recipientUuid);
            boolean hadSenderPerchAssignment = job.hasSenderPerchAssignment();
            RavenBadgeRuntime.onCourierJobUpdated(serverLevel, job.senderUuid, RavenBadgeEventType.DELIVERY_SUCCESS);

            if (RavenLinkRuntime.isRavenLinked(raven)) {
                RavenLinkRuntime.markCourierDeliveryCompleteDeferred(raven);
            } else {
                despawnCourierRaven(serverLevel, serverPlayer, raven, "delivery complete: scroll retrieved");
                if (hadSenderPerchAssignment) {
                    maybeRespawnSenderPerchRavenForJob(serverLevel, job, false);
                }
            }
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
            LOG.warn("[RavenCourierRuntime] getCourierJobIdFromRaven failed safely for id={}: {}", raven.getId(), t.toString());
            return -1L;
        }
    }

    // ---------------------------------------------------------------------
    // Scroll building / delivery (unchanged)
    // ---------------------------------------------------------------------

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

    private static boolean giveSealedScrollToPlayerFromJob(@NotNull ServerLevel level,
                                                           @NotNull ServerPlayer player,
                                                           @NotNull RavenCourierData.DeliveryJob job) {
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

            MutableComponent msg = Component.empty()
                    .append(Component.translatable("message.featheredfriend.courier.retrieved.base"));
            if (!senderName.isEmpty()) {
                msg.append(Component.translatable("message.featheredfriend.courier.retrieved.from", senderName));
            }
            if (!recipientName.isEmpty()) {
                msg.append(Component.translatable("message.featheredfriend.courier.retrieved.addressed_to", recipientName));
            }
            msg.append(Component.translatable(
                    hasSpace
                            ? "message.featheredfriend.courier.retrieved.suffix.inventory"
                            : "message.featheredfriend.courier.retrieved.suffix.dropped"
            ));
            player.sendSystemMessage(msg);

            LOG.debug("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: {} delivery of sealed scroll for jobId={} to player='{}'",
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

    public static void handleCourierRavenLandedHit(@NotNull RavenEntity raven) {
        try {
            if (raven == null) {
                return;
            }

            Level level = raven.level();
            if (!(level instanceof ServerLevel serverLevel) || level.isClientSide()) {
                return;
            }

            // Lethal hits are handled by death path to avoid duplicate drops.
            if (!raven.isAlive()) {
                return;
            }

            if (!isCourierRaven(raven)) {
                return;
            }

            long jobId = getCourierJobIdFromRaven(raven);
            if (jobId <= 0L) {
                return;
            }

            RavenCourierData data = RavenCourierData.get(serverLevel);
            RavenCourierData.DeliveryJob job = data.getJobById(jobId);
            if (job == null) {
                clearCourierFlags(raven);
                return;
            }

            float payloadLossChance = Mth.clamp(raven.getEffectivePayloadDropOnLandedHitChance(), 0.0F, 1.0F);
            boolean payloadLost;
            if (payloadLossChance <= 0.0F) {
                payloadLost = false;
            } else if (payloadLossChance >= 1.0F) {
                payloadLost = true;
            } else {
                payloadLost = serverLevel.random.nextFloat() < payloadLossChance;
            }

            if (payloadLost) {
                // Payload safety roll failed: scroll is lost from raven inventory,
                // so materialize it into the world before clearing the job.
                dropSealedScrollAtRaven(serverLevel, raven, job);
                data.removeJob(job.jobId, job.recipientUuid);

                ServerPlayer contextPlayer = findBestContextPlayer(serverLevel.getServer(), job.senderUuid, job.recipientUuid);
                if (contextPlayer != null) {
                    despawnCourierRaven(serverLevel, contextPlayer, raven, "payload lost on landed hit", true);
                } else {
                    clearCourierFlags(raven);
                    raven.discard();
                }

                maybeRespawnSenderPerchRavenForJob(serverLevel, job, false);

                LOG.debug("[RavenCourierRuntime] handleCourierRavenLandedHit: payload lost; courier raven despawned id={} jobId={}",
                        raven.getId(), job.jobId);
                logToJobParticipants(
                        serverLevel,
                        job,
                        RavenLogCategory.COMBAT,
                        "log.featheredfriend.courier.payload_dropped_on_hit",
                        job.jobId
                );
                RavenBadgeRuntime.onCourierJobUpdated(serverLevel, job.senderUuid, RavenBadgeEventType.HIT_LOSE_SCROLL);
                return;
            }

            handleCourierRavenFailure(
                    serverLevel,
                    data,
                    raven,
                    job,
                    FAILURE_REASON_PAYLOAD_PROTECTED_RETRY,
                    "payload-protected-hit",
                    false
            );
            logToJobParticipants(
                    serverLevel,
                    job,
                    RavenLogCategory.COMBAT,
                    "log.featheredfriend.courier.payload_protected_retry",
                    job.jobId
            );
            RavenBadgeRuntime.onCourierJobUpdated(serverLevel, job.senderUuid, RavenBadgeEventType.HIT_KEEP_SCROLL);
        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] handleCourierRavenLandedHit failed safely", t);
        }
    }

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
                LOG.warn("[RavenCourierRuntime] onRavenDeath: courier raven id={} has no valid CourierJobId; dropping nothing.", raven.getId());
                return;
            }

            RavenCourierData data = RavenCourierData.get(serverLevel);
            RavenCourierData.DeliveryJob job = data.getJobById(jobId);
            if (job == null) {
                LOG.warn("[RavenCourierRuntime] onRavenDeath: no job found for CourierJobId={} (raven id={})", jobId, raven.getId());
                return;
            }

            try {
                ServerPlayer owner = null;
                UUID ownerUuid = raven.getOwnerUUID();
                if (ownerUuid != null && serverLevel.getServer() != null) {
                    owner = serverLevel.getServer().getPlayerList().getPlayer(ownerUuid);
                }
                if (owner != null) {
                    TamedRavenPlayerData.setStoredRavenHealth(owner, 0.0F, serverLevel.getGameTime());
                }
            } catch (Throwable ignored) {
            }

            // If payload already deposited into mailbox, do NOT drop/duplicate.
            if (job.mailboxDeposited) {
                logToJobParticipants(
                        serverLevel,
                        job,
                        RavenLogCategory.COMBAT,
                        "log.featheredfriend.courier.raven_died_after_mailbox_delivery",
                        job.jobId
                );
                data.removeJob(job.jobId, job.recipientUuid);
                maybeRespawnSenderPerchRavenForJob(serverLevel, job, false);
                clearCourierFlags(raven);
                return;
            }

            // Death COMPLETES: drop scroll + remove job.
            dropSealedScrollAtRaven(serverLevel, raven, job);
            notifySenderOfRavenDeath(serverLevel, raven, job);
            logToJobParticipants(
                    serverLevel,
                    job,
                    RavenLogCategory.COMBAT,
                    "log.featheredfriend.courier.raven_died_drop_scroll",
                    job.jobId
            );
            data.removeJob(job.jobId, job.recipientUuid);
            RavenBadgeRuntime.onCourierJobUpdated(serverLevel, job.senderUuid, RavenBadgeEventType.NONE);
            maybeRespawnSenderPerchRavenForJob(serverLevel, job, false);
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

            LOG.debug("[RavenCourierRuntime] dropSealedScrollAtRaven: dropped sealed scroll for jobId={} at pos={}", job.jobId, raven.position());

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] dropSealedScrollAtRaven failed safely for jobId={}", job.jobId, t);
        }
    }

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
                return;
            }

            String ravenName = Component.translatable("message.featheredfriend.courier.raven_death.fallback_name").getString();
            try {
                if (raven.getCustomName() != null) {
                    String n = raven.getCustomName().getString();
                    if (n != null && !n.isEmpty()) {
                        ravenName = n;
                    }
                }
            } catch (Throwable ignored) {
            }

            MutableComponent msg = Component.empty()
                    .append(Component.translatable("message.featheredfriend.courier.raven_death.base", ravenName));
            if (job.recipientName != null && !job.recipientName.isEmpty()) {
                msg.append(Component.translatable("message.featheredfriend.courier.raven_death.recipient", job.recipientName));
            }
            msg.append(Component.translatable("message.featheredfriend.courier.raven_death.suffix"));
            sender.sendSystemMessage(msg);

            LOG.debug("[RavenCourierRuntime] notifySenderOfRavenDeath: notified sender='{}' of raven death (jobId={})",
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
                    // Remove forced mailbox ticket if present.
                    try {
                        long ticketChunk = ffTag.getLong(NBT_COURIER_MAILBOX_TICKET_CHUNK);
                        if (ticketChunk != 0L && raven.level() instanceof ServerLevel ticketLevel) {
                            ChunkPos cp = new ChunkPos(ticketChunk);
                            ticketLevel.getChunkSource().removeRegionTicket(
                                    TicketType.FORCED,
                                    cp,
                                    COURIER_MAILBOX_TICKET_RADIUS,
                                    cp,
                                    true
                            );
                        }
                    } catch (Throwable ignored) {
                    }

                    ffTag.remove("CourierActive");
                    ffTag.remove("CourierJobId");
                    ffTag.remove("CourierSenderUUID");
                    ffTag.remove("CourierRecipientUUID");
                    ffTag.remove(NBT_COURIER_DESPAWN_AT);
                    ffTag.remove(NBT_COURIER_LINK_PAUSE_LAST_TICK);
                    ffTag.remove(NBT_COURIER_LAST_THREAT_LOG_AT);

                    ffTag.remove(NBT_COURIER_MAILBOX_MODE);
                    ffTag.remove(NBT_COURIER_MAILBOX_DIMENSION);
                    ffTag.remove(NBT_COURIER_MAILBOX_BLOCK_POS);
                    ffTag.remove(NBT_COURIER_MAILBOX_OWNER_UUID);
                    ffTag.remove(NBT_COURIER_MAILBOX_OWNER_UUID_STR);
                    ffTag.remove(NBT_COURIER_MAILBOX_ATTEMPT_END_AT);
                    ffTag.remove(NBT_COURIER_MAILBOX_OUTCOME);
                    ffTag.remove(NBT_COURIER_MAILBOX_DEPOSIT_AT);
                    ffTag.remove(NBT_COURIER_MAILBOX_TICKET_CHUNK);
                    root.put(Constants.MOD_ID, ffTag);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] clearCourierFlags: NBT cleanup failed safely for id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    public static void finishLinkedCourierDelivery(@NotNull MinecraftServer server,
                                                   @NotNull ServerPlayer ownerContext,
                                                   @NotNull RavenEntity raven) {
        try {
            if (!(raven.level() instanceof ServerLevel level) || level.isClientSide()) {
                clearCourierFlags(raven);
                raven.discard();
                return;
            }
            despawnCourierRaven(level, ownerContext, raven, "delivery complete: scroll retrieved (post-link)");
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] finishLinkedCourierDelivery failed safely for raven id={}: {}",
                    raven.getId(), t.toString());
            try {
                clearCourierFlags(raven);
                raven.discard();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void despawnCourierRaven(@NotNull ServerLevel level,
                                            @NotNull ServerPlayer contextPlayer,
                                            @NotNull RavenEntity raven,
                                            @NotNull String reason) {
        despawnCourierRaven(level, contextPlayer, raven, reason, false);
    }

    private static void despawnCourierRaven(@NotNull ServerLevel level,
                                            @NotNull ServerPlayer contextPlayer,
                                            @NotNull RavenEntity raven,
                                            @NotNull String reason,
                                            boolean spawnFeathers) {
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
                    tamedModule.beginDespawnWithFx(level, contextPlayer, ravenName, spawnFeathers);
                    LOG.debug("[RavenCourierRuntime] despawnCourierRaven: triggered despawn FX for id={} name='{}' player='{}' reason={} spawnFeathers={}",
                            raven.getId(), ravenName, safePlayerName(contextPlayer), reason, spawnFeathers);
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

            try {
                RavenLogService.logForPlayerKey(
                        level,
                        contextPlayer.getUUID(),
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.raven_despawned_reason",
                        reason
                );
            } catch (Throwable ignored) {
            }

            clearCourierFlags(raven);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] despawnCourierRaven failed safely", t);
        }
    }

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
