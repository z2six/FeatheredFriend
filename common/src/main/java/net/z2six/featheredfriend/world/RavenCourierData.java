// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierData.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.util.datafix.DataFixTypes;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.log.RavenLogCategory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierData.java
 *
 * World-owned storage for all pending raven courier delivery jobs.
 *
 * Updated behavior (Dec 2025 changes):
 *  - Jobs can be marked FAILED (persisted) if delivery fails for non-death reasons (e.g. timeout/stuck).
 *  - Failed jobs are NOT removed automatically; sender must trigger retry later.
 *  - inFlight is still runtime-only (reset on load).
 */
public class RavenCourierData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String DATA_NAME = Constants.MOD_ID + "_raven_courier";

    private static final ResourceLocation SEALED_SCROLL_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "scroll_sealed");

    // ---------------------------------------------------------------------
    // Internal job representation
    // ---------------------------------------------------------------------

    public static final class DeliveryJob {
        public final long jobId;
        public final UUID senderUuid;
        public final String senderName;
        public final UUID recipientUuid;
        public final String recipientName;
        public final CompoundTag sealedScrollNbt;
        public final String ravenName;
        public @Nullable UUID courierRavenUuid;

        /**
         * Runtime-only: courier raven currently spawned for this job.
         * Saved only for visibility; reset to false on load.
         */
        public boolean inFlight;

        /**
         * Persisted failure state (NEW).
         * Failed jobs do not auto-dispatch; sender must retry.
         */
        public boolean failed;

        /** Persisted failure metadata (NEW). */
        public int failureCount;
        public long lastFailureGameTime;
        public String lastFailureReason;

        public DeliveryJob(long jobId,
                           @NotNull UUID senderUuid,
                           @NotNull String senderName,
                           @NotNull UUID recipientUuid,
                           @NotNull String recipientName,
                           @NotNull CompoundTag sealedScrollNbt,
                           boolean inFlight,
                           @NotNull String ravenName,
                           @Nullable UUID courierRavenUuid,
                           boolean failed,
                           int failureCount,
                           long lastFailureGameTime,
                           @NotNull String lastFailureReason) {
            this.jobId = jobId;
            this.senderUuid = senderUuid;
            this.senderName = senderName;
            this.recipientUuid = recipientUuid;
            this.recipientName = recipientName;
            this.sealedScrollNbt = sealedScrollNbt;
            this.inFlight = inFlight;
            this.ravenName = ravenName;
            this.courierRavenUuid = courierRavenUuid;

            this.failed = failed;
            this.failureCount = failureCount;
            this.lastFailureGameTime = lastFailureGameTime;
            this.lastFailureReason = lastFailureReason;
        }
    }

    // ---------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------

    private long nextJobId = 1L;

    private final Map<UUID, List<DeliveryJob>> jobsByRecipient = new HashMap<>();

    // ---------------------------------------------------------------------
    // Construction / factory
    // ---------------------------------------------------------------------

    public RavenCourierData() {
        // no-op
    }

    public static RavenCourierData create() {
        return new RavenCourierData();
    }

    public static RavenCourierData load(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        RavenCourierData data = new RavenCourierData();
        data.readFromNbt(tag);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            writeToNbt(tag);
        } catch (Throwable t) {
            LOG.error("[RavenCourierData] save failed safely: {}", t.toString());
        }
        return tag;
    }

    // ---------------------------------------------------------------------
    // NBT (de)serialization
    // ---------------------------------------------------------------------

    private void readFromNbt(@NotNull CompoundTag tag) {
        try {
            jobsByRecipient.clear();

            if (tag.contains("NextJobId", Tag.TAG_LONG)) {
                nextJobId = tag.getLong("NextJobId");
                if (nextJobId <= 0L) {
                    nextJobId = 1L;
                }
            } else {
                nextJobId = 1L;
            }

            if (!tag.contains("Jobs", Tag.TAG_LIST)) {
                return;
            }

            ListTag jobsList = tag.getList("Jobs", Tag.TAG_COMPOUND);
            for (int i = 0; i < jobsList.size(); i++) {
                Tag element = jobsList.get(i);
                if (!(element instanceof CompoundTag jobTag)) {
                    continue;
                }

                try {
                    long jobId = jobTag.getLong("JobId");

                    UUID senderUuid = jobTag.hasUUID("SenderUUID")
                            ? jobTag.getUUID("SenderUUID")
                            : parseUuidSafe(jobTag.getString("SenderUUIDStr"));

                    String senderName = jobTag.getString("SenderName");

                    UUID recipientUuid = jobTag.hasUUID("RecipientUUID")
                            ? jobTag.getUUID("RecipientUUID")
                            : parseUuidSafe(jobTag.getString("RecipientUUIDStr"));

                    String recipientName = jobTag.getString("RecipientName");

                    CompoundTag sealedScrollNbt = jobTag.getCompound("SealedScroll");

                    String ravenName = jobTag.getString("RavenName");
                    if (ravenName == null) {
                        ravenName = "";
                    }

                    // Runtime-only: always reset to false on load.
                    boolean inFlight = false;

                    // NEW persisted failure fields
                    boolean failed = jobTag.getBoolean("Failed");
                    int failureCount = jobTag.contains("FailureCount", Tag.TAG_INT) ? jobTag.getInt("FailureCount") : 0;
                    long lastFailureGameTime = jobTag.contains("LastFailureGameTime", Tag.TAG_LONG) ? jobTag.getLong("LastFailureGameTime") : 0L;
                    String lastFailureReason = jobTag.contains("LastFailureReason", Tag.TAG_STRING) ? jobTag.getString("LastFailureReason") : "";
                    UUID courierRavenUuid = jobTag.hasUUID("CourierRavenUUID")
                            ? jobTag.getUUID("CourierRavenUUID")
                            : parseUuidSafe(jobTag.getString("CourierRavenUUIDStr"));

                    if (senderUuid == null || recipientUuid == null || sealedScrollNbt.isEmpty()) {
                        LOG.warn("[RavenCourierData] Skipping malformed job entry at index {} (missing UUIDs or SealedScroll).", i);
                        continue;
                    }

                    DeliveryJob job = new DeliveryJob(
                            jobId,
                            senderUuid,
                            senderName == null ? "" : senderName,
                            recipientUuid,
                            recipientName == null ? "" : recipientName,
                            sealedScrollNbt.copy(),
                            inFlight,
                            ravenName,
                            courierRavenUuid,
                            failed,
                            Math.max(0, failureCount),
                            Math.max(0L, lastFailureGameTime),
                            lastFailureReason == null ? "" : lastFailureReason
                    );

                    jobsByRecipient.computeIfAbsent(recipientUuid, k -> new ArrayList<>()).add(job);

                    if (jobId >= nextJobId) {
                        nextJobId = jobId + 1L;
                    }

                } catch (Throwable jobErr) {
                    LOG.warn("[RavenCourierData] Failed to read job entry at index {}: {}", i, jobErr.toString());
                }
            }

            LOG.debug("[RavenCourierData] Loaded {} recipients with pending jobs (nextJobId={})",
                    jobsByRecipient.size(), nextJobId);

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] readFromNbt failed safely: {}", t.toString());
            jobsByRecipient.clear();
            nextJobId = 1L;
        }
    }

    private void writeToNbt(@NotNull CompoundTag tag) {
        try {
            tag.putLong("NextJobId", nextJobId);

            ListTag jobsList = new ListTag();

            for (Map.Entry<UUID, List<DeliveryJob>> entry : jobsByRecipient.entrySet()) {
                UUID recipientUuid = entry.getKey();
                List<DeliveryJob> jobs = entry.getValue();
                if (jobs == null || jobs.isEmpty()) {
                    continue;
                }

                for (DeliveryJob job : jobs) {
                    if (job == null) {
                        continue;
                    }

                    CompoundTag jobTag = new CompoundTag();
                    jobTag.putLong("JobId", job.jobId);

                    if (job.senderUuid != null) {
                        jobTag.putUUID("SenderUUID", job.senderUuid);
                        jobTag.putString("SenderUUIDStr", job.senderUuid.toString());
                    }
                    jobTag.putString("SenderName", job.senderName == null ? "" : job.senderName);

                    if (recipientUuid != null) {
                        jobTag.putUUID("RecipientUUID", recipientUuid);
                        jobTag.putString("RecipientUUIDStr", recipientUuid.toString());
                    }
                    jobTag.putString("RecipientName", job.recipientName == null ? "" : job.recipientName);

                    if (job.sealedScrollNbt != null) {
                        jobTag.put("SealedScroll", job.sealedScrollNbt.copy());
                    }

                    // Saved for visibility only; ignored on load.
                    jobTag.putBoolean("InFlight", job.inFlight);

                    jobTag.putString("RavenName", job.ravenName == null ? "" : job.ravenName);

                    if (job.courierRavenUuid != null) {
                        jobTag.putUUID("CourierRavenUUID", job.courierRavenUuid);
                        jobTag.putString("CourierRavenUUIDStr", job.courierRavenUuid.toString());
                    }

                    // NEW persisted failure fields
                    jobTag.putBoolean("Failed", job.failed);
                    jobTag.putInt("FailureCount", Math.max(0, job.failureCount));
                    jobTag.putLong("LastFailureGameTime", Math.max(0L, job.lastFailureGameTime));
                    jobTag.putString("LastFailureReason", job.lastFailureReason == null ? "" : job.lastFailureReason);

                    jobsList.add(jobTag);
                }
            }

            tag.put("Jobs", jobsList);

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] writeToNbt failed safely: {}", t.toString());
        }
    }

    @Nullable
    private static UUID parseUuidSafe(@Nullable String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(str);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Accessor for the saved data instance
    // ---------------------------------------------------------------------

    @NotNull
    public static RavenCourierData get(@NotNull ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                overworld = level;
            }

            var storage = overworld.getDataStorage();
            SavedData.Factory<RavenCourierData> factory =
                    new SavedData.Factory<>(RavenCourierData::create, RavenCourierData::load, DataFixTypes.LEVEL);

            return storage.computeIfAbsent(factory, DATA_NAME);

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] get(...) failed safely, returning empty volatile instance: {}", t.toString());
            return new RavenCourierData();
        }
    }

    // ---------------------------------------------------------------------
    // Public API: creation of jobs from Sealed Scrolls
    // ---------------------------------------------------------------------

    @Nullable
    public DeliveryJob createJobFromSealedScroll(@NotNull ServerPlayer sender,
                                                 @NotNull RavenEntity raven,
                                                 @NotNull ItemStack scrollStack) {
        try {
            if (scrollStack.isEmpty()) {
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: stack is empty for player='{}'.",
                        sender.getGameProfile().getName());
                return null;
            }

            ResourceLocation key = BuiltInRegistries.ITEM.getKey(scrollStack.getItem());
            if (key == null || !SEALED_SCROLL_ID.equals(key)) {
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: item is not a sealed scroll (key={} player='{}').",
                        key, sender.getGameProfile().getName());
                return null;
            }

            CustomData customData = scrollStack.get(DataComponents.CUSTOM_DATA);
            if (customData == null) {
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: CUSTOM_DATA missing on sealed scroll (player='{}').",
                        sender.getGameProfile().getName());
                return null;
            }

            CompoundTag customRoot = customData.getUnsafe();
            if (customRoot == null || !customRoot.contains("SealedScroll", Tag.TAG_COMPOUND)) {
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: SealedScroll compound missing in CUSTOM_DATA (player='{}').",
                        sender.getGameProfile().getName());
                return null;
            }

            CompoundTag sealed = customRoot.getCompound("SealedScroll");
            if (sealed.isEmpty()) {
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: SealedScroll compound empty (player='{}').",
                        sender.getGameProfile().getName());
                return null;
            }

            String recipientUuidStr = sealed.getString("RecipientUUID");
            if (recipientUuidStr == null || recipientUuidStr.isEmpty()) {
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: RecipientUUID missing in SealedScroll (player='{}').",
                        sender.getGameProfile().getName());
                return null;
            }

            UUID recipientUuid;
            try {
                recipientUuid = UUID.fromString(recipientUuidStr);
            } catch (IllegalArgumentException ex) {
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: RecipientUUID malformed '{}' (player='{}').",
                        recipientUuidStr, sender.getGameProfile().getName());
                return null;
            }

            String recipientName = sealed.getString("RecipientName");
            if (recipientName == null) {
                recipientName = "";
            }

            UUID senderUuid = sender.getUUID();
            String senderName = sender.getGameProfile().getName();

            String ravenName;
            try {
                if (raven.getCustomName() != null) {
                    String n = raven.getCustomName().getString();
                    ravenName = (n != null && !n.isEmpty())
                            ? n
                            : Component.translatable("entity.featheredfriend.raven").getString();
                } else {
                    ravenName = Component.translatable("entity.featheredfriend.raven").getString();
                }
            } catch (Throwable t) {
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: failed to read raven custom name for raven id={}: {}",
                        raven.getId(), t.toString());
                ravenName = Component.translatable("entity.featheredfriend.raven").getString();
            }

            long jobId = nextJobId++;
            if (jobId <= 0L) {
                jobId = 1L;
                nextJobId = 2L;
            }

            DeliveryJob job = new DeliveryJob(
                    jobId,
                    senderUuid,
                    senderName == null ? "" : senderName,
                    recipientUuid,
                    recipientName,
                    sealed.copy(),
                    false,
                    ravenName,
                    null,
                    false,     // failed
                    0,         // failureCount
                    0L,        // lastFailureGameTime
                    ""         // lastFailureReason
            );

            jobsByRecipient.computeIfAbsent(recipientUuid, k -> new ArrayList<>()).add(job);

            setDirty();

            LOG.debug("[RavenCourierData] Created delivery job id={} (sender='{}' [{}], recipient='{}' [{}], ravenId={} ravenName='{}')",
                    jobId, senderName, senderUuid, recipientName, recipientUuid, raven.getId(), ravenName);

            try {
                RavenLogService.logForPlayerKey(
                        sender,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.job.queued_outgoing",
                        jobId,
                        recipientName
                );
                if (recipientUuid != null && !recipientUuid.equals(senderUuid)) {
                    RavenLogService.logForPlayerKey(
                            sender.serverLevel(),
                            recipientUuid,
                            RavenLogCategory.COURIER,
                            "log.featheredfriend.courier.job.queued_incoming",
                            jobId,
                            senderName
                    );
                }
            } catch (Throwable ignored) {
            }

            return job;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] createJobFromSealedScroll failed safely: {}", t.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Failure + retry helpers (NEW)
    // ---------------------------------------------------------------------

    /**
     * Mark a job as failed (persisted).
     * Also clears inFlight so it doesn't remain locked.
     */
    public boolean markJobFailed(long jobId, @NotNull UUID recipientUuid, @NotNull String reason, long gameTime) {
        try {
            DeliveryJob job = getJobById(jobId);
            if (job == null) {
                LOG.warn("[RavenCourierData] markJobFailed: jobId={} not found", jobId);
                return false;
            }
            if (!recipientUuid.equals(job.recipientUuid)) {
                LOG.warn("[RavenCourierData] markJobFailed: recipient mismatch for jobId={} expected={} got={}",
                        jobId, job.recipientUuid, recipientUuid);
                return false;
            }

            job.failed = true;
            job.inFlight = false;
            job.failureCount = Math.max(0, job.failureCount) + 1;
            job.lastFailureGameTime = Math.max(0L, gameTime);
            job.lastFailureReason = (reason == null) ? "" : reason;

            setDirty();

            LOG.debug("[RavenCourierData] markJobFailed: jobId={} recipient={} reason='{}' failureCount={}",
                    jobId, recipientUuid, job.lastFailureReason, job.failureCount);

            return true;
        } catch (Throwable t) {
            LOG.error("[RavenCourierData] markJobFailed failed safely (jobId={}): {}", jobId, t.toString());
            return false;
        }
    }

    /**
     * Clears failed state for a sender-triggered retry.
     * Returns true only if job exists, sender matches, and job is currently failed and not inFlight.
     */
    public boolean clearFailedForRetry(long jobId, @NotNull UUID senderUuid) {
        try {
            DeliveryJob job = getJobById(jobId);
            if (job == null) {
                LOG.warn("[RavenCourierData] clearFailedForRetry: jobId={} not found", jobId);
                return false;
            }
            if (job.senderUuid == null || !senderUuid.equals(job.senderUuid)) {
                LOG.warn("[RavenCourierData] clearFailedForRetry: sender mismatch for jobId={} expected={} got={}",
                        jobId, job.senderUuid, senderUuid);
                return false;
            }
            if (!job.failed) {
                LOG.warn("[RavenCourierData] clearFailedForRetry: jobId={} is not failed; nothing to retry", jobId);
                return false;
            }
            if (job.inFlight) {
                LOG.warn("[RavenCourierData] clearFailedForRetry: jobId={} is inFlight; cannot retry while active", jobId);
                return false;
            }

            job.failed = false;
            job.lastFailureReason = "";
            job.lastFailureGameTime = 0L;

            setDirty();

            LOG.debug("[RavenCourierData] clearFailedForRetry: cleared failed state for jobId={} sender={}", jobId, senderUuid);
            return true;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] clearFailedForRetry failed safely (jobId={}): {}", jobId, t.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Query helpers
    // ---------------------------------------------------------------------

    @NotNull
    public List<DeliveryJob> getJobsForRecipient(@NotNull UUID recipientUuid) {
        List<DeliveryJob> list = jobsByRecipient.get(recipientUuid);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(list);
    }

    @NotNull
    public List<DeliveryJob> getAllJobsFlat() {
        List<DeliveryJob> out = new ArrayList<>();
        for (List<DeliveryJob> list : jobsByRecipient.values()) {
            if (list == null || list.isEmpty()) {
                continue;
            }
            out.addAll(list);
        }
        return List.copyOf(out);
    }

    @NotNull
    public List<DeliveryJob> getJobsForPlayer(@NotNull UUID playerUuid) {
        List<DeliveryJob> out = new ArrayList<>();
        for (List<DeliveryJob> list : jobsByRecipient.values()) {
            if (list == null || list.isEmpty()) {
                continue;
            }
            for (DeliveryJob job : list) {
                if (job == null) {
                    continue;
                }
                if (playerUuid.equals(job.senderUuid) || playerUuid.equals(job.recipientUuid)) {
                    out.add(job);
                }
            }
        }
        return List.copyOf(out);
    }

    public void removeJob(long jobId, @NotNull UUID recipientUuid) {
        try {
            List<DeliveryJob> list = jobsByRecipient.get(recipientUuid);
            if (list == null || list.isEmpty()) {
                return;
            }

            boolean removed = list.removeIf(job -> job != null && job.jobId == jobId);
            if (removed) {
                if (list.isEmpty()) {
                    jobsByRecipient.remove(recipientUuid);
                }
                setDirty();
                LOG.debug("[RavenCourierData] Removed job id={} for recipient={}", jobId, recipientUuid);
            }
        } catch (Throwable t) {
            LOG.error("[RavenCourierData] removeJob failed safely: {}", t.toString());
        }
    }

    public boolean hasOpenJobsAsSender(@NotNull UUID senderUuid) {
        try {
            if (jobsByRecipient.isEmpty()) {
                return false;
            }

            for (List<DeliveryJob> jobs : jobsByRecipient.values()) {
                if (jobs == null || jobs.isEmpty()) {
                    continue;
                }
                for (DeliveryJob job : jobs) {
                    if (job == null) {
                        continue;
                    }
                    if (senderUuid.equals(job.senderUuid)) {
                        return true;
                    }
                }
            }

            return false;
        } catch (Throwable t) {
            LOG.error("[RavenCourierData] hasOpenJobsAsSender failed safely: {}", t.toString());
            return false;
        }
    }

    public int clearAllJobs() {
        try {
            int count = 0;
            for (List<DeliveryJob> list : jobsByRecipient.values()) {
                if (list != null) {
                    count += list.size();
                }
            }
            jobsByRecipient.clear();
            if (count > 0) {
                setDirty();
            }
            LOG.debug("[RavenCourierData] clearAllJobs: removed {} job(s).", count);
            return count;
        } catch (Throwable t) {
            LOG.error("[RavenCourierData] clearAllJobs failed safely: {}", t.toString());
            return 0;
        }
    }

    public int clearJobsForPlayer(@NotNull UUID playerUuid) {
        try {
            int removed = 0;
            Iterator<Map.Entry<UUID, List<DeliveryJob>>> it = jobsByRecipient.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, List<DeliveryJob>> entry = it.next();
                List<DeliveryJob> list = entry.getValue();
                if (list == null || list.isEmpty()) {
                    continue;
                }

                int before = list.size();
                list.removeIf(job -> job != null && (playerUuid.equals(job.senderUuid) || playerUuid.equals(job.recipientUuid)));
                int after = list.size();

                removed += (before - after);

                if (list.isEmpty()) {
                    it.remove();
                }
            }

            if (removed > 0) {
                setDirty();
            }

            LOG.debug("[RavenCourierData] clearJobsForPlayer: removed {} job(s) for player={}", removed, playerUuid);
            return removed;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] clearJobsForPlayer failed safely: {}", t.toString());
            return 0;
        }
    }

    @Nullable
    public DeliveryJob getJobById(long jobId) {
        try {
            if (jobsByRecipient.isEmpty()) {
                return null;
            }
            for (List<DeliveryJob> list : jobsByRecipient.values()) {
                if (list == null || list.isEmpty()) {
                    continue;
                }
                for (DeliveryJob job : list) {
                    if (job != null && job.jobId == jobId) {
                        return job;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            LOG.error("[RavenCourierData] getJobById failed safely: {}", t.toString());
            return null;
        }
    }

    // Helper for TamedRavenScrollwatcher
    // ------------------------------------

    public boolean hasFailedJobsAsSender(@NotNull UUID senderUuid) {
        try {
            if (jobsByRecipient.isEmpty()) {
                return false;
            }

            for (List<DeliveryJob> jobs : jobsByRecipient.values()) {
                if (jobs == null || jobs.isEmpty()) {
                    continue;
                }
                for (DeliveryJob job : jobs) {
                    if (job == null) {
                        continue;
                    }
                    if (senderUuid.equals(job.senderUuid) && job.failed) {
                        return true;
                    }
                }
            }
            return false;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] hasFailedJobsAsSender failed safely: {}", t.toString());
            return false;
        }
    }

    public boolean hasActiveNonFailedJobsAsSender(@NotNull UUID senderUuid) {
        try {
            if (jobsByRecipient.isEmpty()) {
                return false;
            }

            for (List<DeliveryJob> jobs : jobsByRecipient.values()) {
                if (jobs == null || jobs.isEmpty()) {
                    continue;
                }
                for (DeliveryJob job : jobs) {
                    if (job == null) {
                        continue;
                    }
                    // "Active" here means: exists and not failed (regardless of inFlight)
                    if (senderUuid.equals(job.senderUuid) && !job.failed) {
                        return true;
                    }
                }
            }
            return false;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] hasActiveNonFailedJobsAsSender failed safely: {}", t.toString());
            return false;
        }
    }

    @Nullable
    public DeliveryJob getMostRecentFailedJobForSender(@NotNull UUID senderUuid) {
        try {
            DeliveryJob best = null;

            if (jobsByRecipient.isEmpty()) {
                return null;
            }

            for (List<DeliveryJob> jobs : jobsByRecipient.values()) {
                if (jobs == null || jobs.isEmpty()) {
                    continue;
                }
                for (DeliveryJob job : jobs) {
                    if (job == null) {
                        continue;
                    }
                    if (!senderUuid.equals(job.senderUuid)) {
                        continue;
                    }
                    if (!job.failed) {
                        continue;
                    }

                    if (best == null) {
                        best = job;
                        continue;
                    }

                    // Prefer the most recently failed; tie-breaker: higher failureCount; then higher jobId
                    long a = Math.max(0L, job.lastFailureGameTime);
                    long b = Math.max(0L, best.lastFailureGameTime);
                    if (a > b) {
                        best = job;
                    } else if (a == b) {
                        int fa = Math.max(0, job.failureCount);
                        int fb = Math.max(0, best.failureCount);
                        if (fa > fb) {
                            best = job;
                        } else if (fa == fb && job.jobId > best.jobId) {
                            best = job;
                        }
                    }
                }
            }

            return best;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] getMostRecentFailedJobForSender failed safely: {}", t.toString());
            return null;
        }
    }
}
