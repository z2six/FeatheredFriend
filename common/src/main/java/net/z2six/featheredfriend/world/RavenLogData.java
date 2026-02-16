package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.log.RavenLogCategory;
import net.z2six.featheredfriend.network.RavenLogEntryInfo;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side persistent Raven Log storage.
 */
public final class RavenLogData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String DATA_NAME = Constants.MOD_ID + "_raven_log";
    private static final int MAX_MESSAGE_CHARS = 600;
    private static final int MAX_ENTRIES_SENT_TO_CLIENT = 2048;

    private record LogEntry(
            long id,
            long gameTime,
            long createdAtMillis,
            @NotNull String categoryId,
            @NotNull String message
    ) {
    }

    private long nextEntryId = 1L;
    private final Map<UUID, List<LogEntry>> entriesByPlayer = new HashMap<>();

    public RavenLogData() {
    }

    public static @NotNull RavenLogData create() {
        return new RavenLogData();
    }

    public static @NotNull RavenLogData load(@NotNull CompoundTag tag, HolderLookup.Provider ignored) {
        RavenLogData data = new RavenLogData();
        data.readFromNbt(tag);
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.Provider registries) {
        writeToNbt(tag);
        return tag;
    }

    public static @NotNull RavenLogData get(@NotNull ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                overworld = level;
            }
            SavedData.Factory<RavenLogData> factory =
                    new SavedData.Factory<>(RavenLogData::create, RavenLogData::load, DataFixTypes.LEVEL);
            return overworld.getDataStorage().computeIfAbsent(factory, DATA_NAME);
        } catch (Throwable t) {
            LOG.error("[RavenLogData] get(...) failed safely", t);
            return new RavenLogData();
        }
    }

    public synchronized void append(@NotNull UUID playerUuid,
                                    @NotNull RavenLogCategory category,
                                    @NotNull String message,
                                    long gameTime,
                                    long nowMillis,
                                    int retentionMinutes,
                                    int maxBytesPerPlayer) {
        if (playerUuid == null || category == null) {
            return;
        }
        String safeMessage = sanitizeMessage(message);
        if (safeMessage.isBlank()) {
            return;
        }

        List<LogEntry> entries = this.entriesByPlayer.computeIfAbsent(playerUuid, u -> new ArrayList<>());
        long id = Math.max(1L, this.nextEntryId++);
        entries.add(new LogEntry(
                id,
                Math.max(0L, gameTime),
                Math.max(0L, nowMillis),
                category.id(),
                safeMessage
        ));

        prunePlayerEntries(entries, nowMillis, retentionMinutes, maxBytesPerPlayer);
        this.setDirty();
    }

    public synchronized @NotNull List<RavenLogEntryInfo> getEntriesForPlayer(@NotNull UUID playerUuid,
                                                                              long nowMillis,
                                                                              int retentionMinutes,
                                                                              int maxBytesPerPlayer) {
        List<LogEntry> entries = this.entriesByPlayer.get(playerUuid);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        boolean pruned = prunePlayerEntries(entries, nowMillis, retentionMinutes, maxBytesPerPlayer);
        if (entries.isEmpty()) {
            this.entriesByPlayer.remove(playerUuid);
            if (pruned) {
                this.setDirty();
            }
            return List.of();
        }
        if (pruned) {
            this.setDirty();
        }

        List<RavenLogEntryInfo> out = new ArrayList<>(Math.min(entries.size(), MAX_ENTRIES_SENT_TO_CLIENT));
        int start = Math.max(0, entries.size() - MAX_ENTRIES_SENT_TO_CLIENT);
        for (int i = start; i < entries.size(); i++) {
            LogEntry e = entries.get(i);
            out.add(new RavenLogEntryInfo(
                    e.id(),
                    e.gameTime(),
                    e.createdAtMillis(),
                    e.categoryId(),
                    e.message()
            ));
        }
        out.sort(Comparator.comparingLong(RavenLogEntryInfo::entryId).reversed());
        return out;
    }

    public synchronized void pruneAll(long nowMillis, int retentionMinutes, int maxBytesPerPlayer) {
        boolean changed = false;
        List<UUID> empty = new ArrayList<>();
        for (Map.Entry<UUID, List<LogEntry>> e : this.entriesByPlayer.entrySet()) {
            List<LogEntry> list = e.getValue();
            if (list == null || list.isEmpty()) {
                empty.add(e.getKey());
                changed = true;
                continue;
            }
            if (prunePlayerEntries(list, nowMillis, retentionMinutes, maxBytesPerPlayer)) {
                changed = true;
            }
            if (list.isEmpty()) {
                empty.add(e.getKey());
                changed = true;
            }
        }
        for (UUID id : empty) {
            this.entriesByPlayer.remove(id);
        }
        if (changed) {
            this.setDirty();
        }
    }

    public synchronized boolean clearPlayer(@NotNull UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        List<LogEntry> removed = this.entriesByPlayer.remove(playerUuid);
        if (removed == null || removed.isEmpty()) {
            return false;
        }
        this.setDirty();
        return true;
    }

    private static boolean prunePlayerEntries(@NotNull List<LogEntry> entries,
                                              long nowMillis,
                                              int retentionMinutes,
                                              int maxBytesPerPlayer) {
        boolean changed = false;

        if (retentionMinutes <= 0) {
            if (!entries.isEmpty()) {
                entries.clear();
                return true;
            }
            return false;
        }

        long safeNow = Math.max(0L, nowMillis);
        long retentionMillis = Math.max(0L, retentionMinutes) * 60_000L;
        long cutoff = safeNow - retentionMillis;

        for (int i = entries.size() - 1; i >= 0; i--) {
            LogEntry e = entries.get(i);
            if (e.createdAtMillis() < cutoff) {
                entries.remove(i);
                changed = true;
            }
        }

        if (entries.isEmpty()) {
            return changed;
        }

        if (maxBytesPerPlayer <= 0) {
            entries.clear();
            return true;
        }

        int total = 0;
        for (LogEntry e : entries) {
            total += estimateEntryBytes(e);
            if (total > maxBytesPerPlayer) {
                entries.clear();
                return true;
            }
        }

        return changed;
    }

    private static int estimateEntryBytes(@NotNull LogEntry e) {
        int base = 48;
        int cat = e.categoryId().getBytes(StandardCharsets.UTF_8).length;
        int msg = e.message().getBytes(StandardCharsets.UTF_8).length;
        return base + cat + msg;
    }

    private static @NotNull String sanitizeMessage(@NotNull String message) {
        String text = message == null ? "" : message.trim();
        if (text.length() > MAX_MESSAGE_CHARS) {
            return text.substring(0, MAX_MESSAGE_CHARS);
        }
        return text;
    }

    private void readFromNbt(@NotNull CompoundTag tag) {
        this.entriesByPlayer.clear();
        this.nextEntryId = Math.max(1L, tag.getLong("NextEntryId"));

        if (!tag.contains("Players", Tag.TAG_LIST)) {
            return;
        }

        ListTag players = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            if (!(players.get(i) instanceof CompoundTag playerTag)) {
                continue;
            }

            UUID owner;
            try {
                owner = playerTag.hasUUID("PlayerUUID")
                        ? playerTag.getUUID("PlayerUUID")
                        : UUID.fromString(playerTag.getString("PlayerUUIDStr"));
            } catch (Throwable ignored) {
                continue;
            }

            List<LogEntry> entries = new ArrayList<>();
            ListTag rawEntries = playerTag.getList("Entries", Tag.TAG_COMPOUND);
            for (int j = 0; j < rawEntries.size(); j++) {
                if (!(rawEntries.get(j) instanceof CompoundTag entryTag)) {
                    continue;
                }
                try {
                    long id = Math.max(1L, entryTag.getLong("Id"));
                    long gameTime = Math.max(0L, entryTag.getLong("GameTime"));
                    long createdAt = Math.max(0L, entryTag.getLong("CreatedAt"));
                    String categoryId = entryTag.getString("Category");
                    String message = sanitizeMessage(entryTag.getString("Message"));
                    if (message.isBlank()) {
                        continue;
                    }
                    entries.add(new LogEntry(
                            id,
                            gameTime,
                            createdAt,
                            RavenLogCategory.fromId(categoryId).id(),
                            message
                    ));
                    if (id >= this.nextEntryId) {
                        this.nextEntryId = id + 1L;
                    }
                } catch (Throwable ignored) {
                }
            }

            if (!entries.isEmpty()) {
                entries.sort(Comparator.comparingLong(LogEntry::id));
                this.entriesByPlayer.put(owner, entries);
            }
        }
    }

    private void writeToNbt(@NotNull CompoundTag tag) {
        tag.putLong("NextEntryId", Math.max(1L, this.nextEntryId));
        ListTag players = new ListTag();

        for (Map.Entry<UUID, List<LogEntry>> entry : this.entriesByPlayer.entrySet()) {
            List<LogEntry> logs = entry.getValue();
            if (logs == null || logs.isEmpty()) {
                continue;
            }

            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("PlayerUUID", entry.getKey());
            ListTag rawEntries = new ListTag();
            for (LogEntry log : logs) {
                CompoundTag e = new CompoundTag();
                e.putLong("Id", log.id());
                e.putLong("GameTime", log.gameTime());
                e.putLong("CreatedAt", log.createdAtMillis());
                e.putString("Category", log.categoryId());
                e.putString("Message", log.message());
                rawEntries.add(e);
            }
            playerTag.put("Entries", rawEntries);
            players.add(playerTag);
        }

        tag.put("Players", players);
    }
}
