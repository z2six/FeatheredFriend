// neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierData.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.saveddata.SavedData;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierData.java
 *
 * World-owned storage for all pending raven courier delivery jobs.
 *
 * Design notes:
 * - Lives on the SERVER, attached to the OVERWORLD's data storage.
 * - Jobs are keyed by recipient UUID and also have a unique jobId.
 * - We only store the SealedScroll sub-compound of the scroll's CustomData
 *   (plus sender/recipient info). The actual ItemStack can be reconstructed
 *   later when we spawn a courier raven for the recipient.
 *
 * This class is intentionally conservative:
 * - Any malformed data is logged and skipped.
 * - Public APIs never throw; they log and fail gracefully.
 */
public class RavenCourierData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Name of the saved data in the DimensionDataStorage.
     */
    private static final String DATA_NAME = Constants.MOD_ID + "_raven_courier";

    /**
     * Registry name of the sealed scroll item.
     */
    private static final ResourceLocation SEALED_SCROLL_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "scroll_sealed");

    // ---------------------------------------------------------------------
    // Internal job representation
    // ---------------------------------------------------------------------

    /**
     * One pending delivery job.
     *
     * For now:
     * - jobId: monotonically increasing ID, unique per world.
     * - senderUuid/senderName: who handed the scroll to their raven.
     * - recipientUuid/recipientName: where the raven should ultimately deliver.
     * - sealedScrollNbt: contents of the "SealedScroll" compound from the scroll's CustomData.
     * - inFlight: whether a courier raven is currently spawned for this job
     *   in THIS server session.
     *
     * IMPORTANT:
     * - inFlight is treated as a runtime-only hint:
     *   * It is saved for debugging/visibility.
     *   * It is ALWAYS reset to false on world-load (see readFromNbt).
     */
    public static final class DeliveryJob {
        public final long jobId;
        public final UUID senderUuid;
        public final String senderName;
        public final UUID recipientUuid;
        public final String recipientName;
        public final CompoundTag sealedScrollNbt;
        public boolean inFlight;

        public DeliveryJob(long jobId,
                           @NotNull UUID senderUuid,
                           @NotNull String senderName,
                           @NotNull UUID recipientUuid,
                           @NotNull String recipientName,
                           @NotNull CompoundTag sealedScrollNbt,
                           boolean inFlight) {
            this.jobId = jobId;
            this.senderUuid = senderUuid;
            this.senderName = senderName;
            this.recipientUuid = recipientUuid;
            this.recipientName = recipientName;
            this.sealedScrollNbt = sealedScrollNbt;
            this.inFlight = inFlight;
        }
    }

    // ---------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------

    /**
     * Monotonically increasing job ID counter.
     */
    private long nextJobId = 1L;

    /**
     * Pending jobs, grouped by recipient UUID.
     */
    private final Map<UUID, List<DeliveryJob>> jobsByRecipient = new HashMap<>();

    // ---------------------------------------------------------------------
    // Construction / factory
    // ---------------------------------------------------------------------

    public RavenCourierData() {
        // no-op
    }

    /**
     * Vanilla-style factory "create" method for SavedData.Factory.
     */
    public static RavenCourierData create() {
        return new RavenCourierData();
    }

    /**
     * Load from NBT (1.21 style: gets HolderLookup.Provider as well).
     */
    public static RavenCourierData load(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        RavenCourierData data = new RavenCourierData();
        data.readFromNbt(tag);
        return data;
    }

    // ---------------------------------------------------------------------
    // SavedData overrides
    // ---------------------------------------------------------------------

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

                    // We ignore any stored "InFlight" state on load.
                    // All jobs become "not in flight" in a fresh server session.
                    boolean inFlight = false;

                    if (senderUuid == null || recipientUuid == null || sealedScrollNbt.isEmpty()) {
                        LOG.warn("[RavenCourierData] Skipping malformed job entry at index {} (missing UUIDs or SealedScroll).", i);
                        continue;
                    }

                    DeliveryJob job = new DeliveryJob(
                            jobId,
                            senderUuid,
                            senderName,
                            recipientUuid,
                            recipientName,
                            sealedScrollNbt.copy(),
                            inFlight
                    );

                    jobsByRecipient
                            .computeIfAbsent(recipientUuid, k -> new ArrayList<>())
                            .add(job);

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

                    // Saved for debugging/visibility only; ignored on load.
                    jobTag.putBoolean("InFlight", job.inFlight);

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

    /**
     * Returns the global RavenCourierData instance, attached to the OVERWORLD's data storage.
     *
     * You can call this with any ServerLevel; it will internally resolve the overworld.
     */
    @NotNull
    public static RavenCourierData get(@NotNull ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                // Fallback: use level itself (e.g., in singleplayer debug worlds).
                overworld = level;
            }

            var storage = overworld.getDataStorage();
            SavedData.Factory<RavenCourierData> factory =
                    new SavedData.Factory<>(RavenCourierData::create, RavenCourierData::load);

            return storage.computeIfAbsent(factory, DATA_NAME);

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] get(...) failed safely, returning empty volatile instance: {}", t.toString());
            // In case of disaster, return a non-saved instance so callers don't NPE.
            return new RavenCourierData();
        }
    }

    // ---------------------------------------------------------------------
    // Public API: creation of jobs from Sealed Scrolls
    // ---------------------------------------------------------------------

    /**
     * Create and register a new courier delivery job based on a Sealed Scroll
     * used on a tamed raven.
     *
     * This method is intended to be called from server-side interaction logic,
     * e.g. TamedRavenScrollWatcher.handleSealedScrollInteract(...).
     *
     * Returns:
     *  - The created DeliveryJob if successful.
     *  - null if anything is invalid (not a sealed scroll, bad NBT, missing recipient, etc).
     */
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

            long jobId = nextJobId++;
            if (jobId <= 0L) {
                jobId = 1L;
                nextJobId = 2L;
            }

            DeliveryJob job = new DeliveryJob(
                    jobId,
                    senderUuid,
                    senderName,
                    recipientUuid,
                    recipientName,
                    sealed.copy(),
                    false // inFlight (runtime-only; never persisted across sessions)
            );

            jobsByRecipient
                    .computeIfAbsent(recipientUuid, k -> new ArrayList<>())
                    .add(job);

            setDirty();

            LOG.info(
                    "[RavenCourierData] Created delivery job id={} from sealed scroll (sender='{}' [{}], recipient='{}' [{}], ravenId={})",
                    jobId,
                    senderName,
                    senderUuid,
                    recipientName,
                    recipientUuid,
                    raven.getId()
            );

            return job;

        } catch (Throwable t) {
            LOG.error("[RavenCourierData] createJobFromSealedScroll failed safely: {}", t.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Query helpers
    // ---------------------------------------------------------------------

    /**
     * Returns an immutable snapshot of all pending jobs for the given recipient UUID.
     * Intended for use by your "batch job" or login handlers later.
     */
    @NotNull
    public List<DeliveryJob> getJobsForRecipient(@NotNull UUID recipientUuid) {
        List<DeliveryJob> list = jobsByRecipient.get(recipientUuid);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(list);
    }

    /**
     * Returns an immutable flat list of ALL pending jobs (across all recipients).
     */
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

    /**
     * Returns an immutable list of jobs where the given UUID is either sender OR recipient.
     */
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

    /**
     * Marks a specific job as removed (e.g. after successful delivery or failure).
     */
    public void removeJob(long jobId, @NotNull UUID recipientUuid) {
        try {
            List<DeliveryJob> list = jobsByRecipient.get(recipientUuid);
            if (list == null || list.isEmpty()) {
                return;
            }

            boolean removed = list.removeIf(job -> job.jobId == jobId);
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

    /**
     * Returns true if this player currently has ANY open courier jobs as sender.
     *
     * Used to prevent the scroll-summoned "follower" raven from being spawned
     * while the raven is busy delivering a scroll for that player.
     */
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
            // Fail-safe: don't block raven spawning if we couldn't check.
            return false;
        }
    }

    /**
     * Clears ALL courier jobs from the world.
     *
     * @return number of jobs removed.
     */
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

    /**
     * Clears all courier jobs where the given player is either sender OR recipient.
     *
     * @return number of jobs removed.
     */
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
                list.removeIf(job ->
                        job != null && (playerUuid.equals(job.senderUuid) || playerUuid.equals(job.recipientUuid)));
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

    /**
     * Lookup helper for runtime: find a job by its jobId.
     */
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
}
