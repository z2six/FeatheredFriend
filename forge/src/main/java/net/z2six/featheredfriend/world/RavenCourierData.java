// MainFile: forge/src/main/java/net/z2six/featheredfriend/world/RavenCourierData.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;

import java.util.*;

/**
 * forge/src/main/java/net/z2six/featheredfriend/world/RavenCourierData.java
 *
 * World-owned storage for all pending raven courier delivery jobs.
 *
 * Updated behavior (Dec 2025 changes):
 *  - Jobs can be marked FAILED (persisted) if delivery fails for non-death reasons (e.g. timeout/stuck).
 *  - Failed jobs are NOT removed automatically; sender must trigger retry later.
 *  - inFlight is still runtime-only (reset on load).
 *
 * IMPORTANT (Forge 1.20.1 port):
 *  - Sealed scroll payload may exist in multiple NBT layouts due to port churn.
 *  - We now support reading from:
 *      1) tag[Constants.MOD_ID].SealedScroll        (preferred)
 *      2) tag.CustomData.SealedScroll              (current port writer output, observed in logs)
 *      3) tag.SealedScroll                         (legacy fallback)
 *  - If we read from (2) or (3), we migrate by copying into (1) server-side.
 */
public class RavenCourierData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String DATA_NAME = Constants.MOD_ID + "_raven_courier";

    private static final ResourceLocation SEALED_SCROLL_ID =
            new ResourceLocation(Constants.MOD_ID, "scroll_sealed");

    // Stack layout keys (1.20.1 NBT)
    private static final String KEY_CUSTOM_DATA = "CustomData";
    private static final String KEY_SEALED_SCROLL = "SealedScroll";

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
                           UUID senderUuid,
                           String senderName,
                           UUID recipientUuid,
                           String recipientName,
                           CompoundTag sealedScrollNbt,
                           boolean inFlight,
                           String ravenName,
                           boolean failed,
                           int failureCount,
                           long lastFailureGameTime,
                           String lastFailureReason) {
            this.jobId = jobId;
            this.senderUuid = senderUuid;
            this.senderName = senderName;
            this.recipientUuid = recipientUuid;
            this.recipientName = recipientName;
            this.sealedScrollNbt = sealedScrollNbt;
            this.inFlight = inFlight;
            this.ravenName = ravenName;

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

    public static RavenCourierData load(CompoundTag tag) {
        RavenCourierData data = new RavenCourierData();
        data.readFromNbt(tag);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
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

    private void readFromNbt(CompoundTag tag) {
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

            LOG.info("[RavenCourierData] Loaded {} recipients with pending jobs (nextJobId={})",
                    jobsByRecipient.size(), nextJobId);

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] readFromNbt failed safely: {}", t.toString());
            jobsByRecipient.clear();
            nextJobId = 1L;
        }
    }

    private void writeToNbt(CompoundTag tag) {
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

    private static UUID parseUuidSafe(String str) {
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

    public static RavenCourierData get(ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                overworld = level;
            }

            var storage = overworld.getDataStorage();
            return storage.computeIfAbsent(
                    RavenCourierData::load,
                    RavenCourierData::create,
                    DATA_NAME
            );

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] get(...) failed safely, returning empty volatile instance: {}", t.toString());
            return new RavenCourierData();
        }
    }

    // ---------------------------------------------------------------------
    // Public API: creation of jobs from Sealed Scrolls
    // ---------------------------------------------------------------------

    public DeliveryJob createJobFromSealedScroll(ServerPlayer sender,
                                                 RavenEntity raven,
                                                 ItemStack scrollStack) {
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

            // Read SealedScroll payload with robust back-compat + migration.
            SealedScrollRead read = getSealedScrollFromStack(scrollStack, true);
            CompoundTag sealed = (read == null) ? null : read.sealed;

            if (sealed == null || sealed.isEmpty()) {
                CompoundTag root = scrollStack.getTag();
                String keys = (root == null) ? "<no tag>" : safeKeys(root);
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: SealedScroll compound missing/empty in NBT (player='{}', stackKeys={}).",
                        sender.getGameProfile().getName(),
                        keys);
                return null;
            }

            if (read != null && read.sourcePath != null) {
                LOG.info("[RavenCourierData] createJobFromSealedScroll: Found SealedScroll at {} (migratedToModId={}) for player='{}'.",
                        read.sourcePath, read.migratedToModId, sender.getGameProfile().getName());
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
                    ravenName = (n != null && !n.isEmpty()) ? n : "Raven";
                } else {
                    ravenName = "Raven";
                }
            } catch (Throwable t) {
                LOG.warn("[RavenCourierData] createJobFromSealedScroll: failed to read raven custom name for raven id={}: {}",
                        raven.getId(), t.toString());
                ravenName = "Raven";
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
                    false,     // failed
                    0,         // failureCount
                    0L,        // lastFailureGameTime
                    ""         // lastFailureReason
            );

            jobsByRecipient.computeIfAbsent(recipientUuid, k -> new ArrayList<>()).add(job);

            setDirty();

            LOG.info("[RavenCourierData] Created delivery job id={} (sender='{}' [{}], recipient='{}' [{}], ravenId={} ravenName='{}')",
                    jobId, senderName, senderUuid, recipientName, recipientUuid, raven.getId(), ravenName);

            return job;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] createJobFromSealedScroll failed safely: {}", t.toString());
            return null;
        }
    }

    private static final class SealedScrollRead {
        final CompoundTag sealed;
        final String sourcePath;
        final boolean migratedToModId;

        private SealedScrollRead(CompoundTag sealed, String sourcePath, boolean migratedToModId) {
            this.sealed = sealed;
            this.sourcePath = sourcePath;
            this.migratedToModId = migratedToModId;
        }
    }

    /**
     * Robust sealed-scroll payload reader (Forge 1.20.1 NBT layouts).
     *
     * Supported layouts:
     *  1) tag[Constants.MOD_ID].SealedScroll   (preferred)
     *  2) tag.CustomData.SealedScroll         (current writer output, seen in your NBT dump)
     *  3) tag.SealedScroll                    (legacy fallback)
     *
     * If migrateToModId is true and we read from (2) or (3),
     * we copy the payload into (1) on the stack.
     */
    private static SealedScrollRead getSealedScrollFromStack(ItemStack stack, boolean migrateToModId) {
        try {
            CompoundTag root = stack.getTag();
            if (root == null || root.isEmpty()) {
                return null;
            }

            // 1) Preferred: tag[modid].SealedScroll
            try {
                if (root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                    CompoundTag ff = root.getCompound(Constants.MOD_ID);
                    if (ff.contains(KEY_SEALED_SCROLL, Tag.TAG_COMPOUND)) {
                        CompoundTag sealed = ff.getCompound(KEY_SEALED_SCROLL);
                        if (sealed != null && !sealed.isEmpty()) {
                            return new SealedScrollRead(sealed, "tag." + Constants.MOD_ID + "." + KEY_SEALED_SCROLL, false);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 2) Port layout: tag.CustomData.SealedScroll
            try {
                if (root.contains(KEY_CUSTOM_DATA, Tag.TAG_COMPOUND)) {
                    CompoundTag cd = root.getCompound(KEY_CUSTOM_DATA);
                    if (cd.contains(KEY_SEALED_SCROLL, Tag.TAG_COMPOUND)) {
                        CompoundTag sealed = cd.getCompound(KEY_SEALED_SCROLL);
                        if (sealed != null && !sealed.isEmpty()) {
                            boolean migrated = false;
                            if (migrateToModId) {
                                migrated = migrateSealedScrollToModId(stack, sealed);
                            }
                            return new SealedScrollRead(sealed, "tag." + KEY_CUSTOM_DATA + "." + KEY_SEALED_SCROLL, migrated);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 3) Legacy: tag.SealedScroll
            try {
                if (root.contains(KEY_SEALED_SCROLL, Tag.TAG_COMPOUND)) {
                    CompoundTag sealed = root.getCompound(KEY_SEALED_SCROLL);
                    if (sealed != null && !sealed.isEmpty()) {
                        boolean migrated = false;
                        if (migrateToModId) {
                            migrated = migrateSealedScrollToModId(stack, sealed);
                        }
                        return new SealedScrollRead(sealed, "tag." + KEY_SEALED_SCROLL, migrated);
                    }
                }
            } catch (Throwable ignored) {
            }

            return null;

        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Copies the given sealed-scroll payload into tag[Constants.MOD_ID].SealedScroll.
     * Leaves existing data in place (we copy, not move) for max compatibility.
     */
    private static boolean migrateSealedScrollToModId(ItemStack stack, CompoundTag sealed) {
        try {
            if (sealed == null || sealed.isEmpty()) {
                return false;
            }

            CompoundTag root = stack.getOrCreateTag();

            CompoundTag ff;
            try {
                ff = root.getCompound(Constants.MOD_ID);
            } catch (Throwable t) {
                ff = new CompoundTag();
            }

            if (ff == null) {
                ff = new CompoundTag();
            }

            // If already present and non-empty, don't overwrite (avoid stomping newer format).
            if (ff.contains(KEY_SEALED_SCROLL, Tag.TAG_COMPOUND)) {
                CompoundTag existing = ff.getCompound(KEY_SEALED_SCROLL);
                if (existing != null && !existing.isEmpty()) {
                    return false;
                }
            }

            ff.put(KEY_SEALED_SCROLL, sealed.copy());
            root.put(Constants.MOD_ID, ff);

            if (LOG.isDebugEnabled()) {
                LOG.debug("[RavenCourierData] migrateSealedScrollToModId: copied payload into tag.{}.{}",
                        Constants.MOD_ID, KEY_SEALED_SCROLL);
            }

            return true;

        } catch (Throwable t) {
            LOG.warn("[RavenCourierData] migrateSealedScrollToModId failed safely: {}", t.toString());
            return false;
        }
    }

    private static String safeKeys(CompoundTag tag) {
        try {
            if (tag == null) return "<null>";
            Set<String> keys = tag.getAllKeys();
            if (keys == null || keys.isEmpty()) return "[]";
            // keep logs sane
            int max = 12;
            StringBuilder sb = new StringBuilder("[");
            int i = 0;
            for (String k : keys) {
                if (i > 0) sb.append(", ");
                sb.append(k);
                i++;
                if (i >= max) {
                    if (keys.size() > max) sb.append(", ...");
                    break;
                }
            }
            sb.append("]");
            return sb.toString();
        } catch (Throwable t) {
            return "<keys-error>";
        }
    }

    // ---------------------------------------------------------------------
    // Failure + retry helpers (NEW)
    // ---------------------------------------------------------------------

    /**
     * Mark a job as failed (persisted).
     * Also clears inFlight so it doesn't remain locked.
     */
    public boolean markJobFailed(long jobId, UUID recipientUuid, String reason, long gameTime) {
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

            LOG.info("[RavenCourierData] markJobFailed: jobId={} recipient={} reason='{}' failureCount={}",
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
    public boolean clearFailedForRetry(long jobId, UUID senderUuid) {
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

            LOG.info("[RavenCourierData] clearFailedForRetry: cleared failed state for jobId={} sender={}", jobId, senderUuid);
            return true;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] clearFailedForRetry failed safely (jobId={}): {}", jobId, t.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Query helpers
    // ---------------------------------------------------------------------

    public List<DeliveryJob> getJobsForRecipient(UUID recipientUuid) {
        List<DeliveryJob> list = jobsByRecipient.get(recipientUuid);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(list);
    }

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

    public List<DeliveryJob> getJobsForPlayer(UUID playerUuid) {
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

    public void removeJob(long jobId, UUID recipientUuid) {
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
                LOG.info("[RavenCourierData] Removed job id={} for recipient={}", jobId, recipientUuid);
            }
        } catch (Throwable t) {
            LOG.error("[RavenCourierData] removeJob failed safely: {}", t.toString());
        }
    }

    public boolean hasOpenJobsAsSender(UUID senderUuid) {
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
            LOG.info("[RavenCourierData] clearAllJobs: removed {} job(s).", count);
            return count;
        } catch (Throwable t) {
            LOG.error("[RavenCourierData] clearAllJobs failed safely: {}", t.toString());
            return 0;
        }
    }

    public int clearJobsForPlayer(UUID playerUuid) {
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

            LOG.info("[RavenCourierData] clearJobsForPlayer: removed {} job(s) for player={}", removed, playerUuid);
            return removed;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] clearJobsForPlayer failed safely: {}", t.toString());
            return 0;
        }
    }

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

    public boolean hasFailedJobsAsSender(UUID senderUuid) {
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

    public boolean hasActiveNonFailedJobsAsSender(UUID senderUuid) {
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

    public DeliveryJob getMostRecentFailedJobForSender(UUID senderUuid) {
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
