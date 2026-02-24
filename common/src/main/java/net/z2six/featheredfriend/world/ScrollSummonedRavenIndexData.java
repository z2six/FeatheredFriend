package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;

/**
 * World-owned index of "scroll-summoned" ravens (whistle-spawned temporary ravens).
 *
 * Why this exists:
 *  - Avoids expensive full-world AABB scans every tick/second.
 *  - Keeps owner-offline and owner-dimension cleanup server-authoritative and fast.
 *  - Persists across restarts, so stray scroll-summoned ravens don't become permanent.
 *
 * Shape:
 *  - ownerUuid -> (ravenUuid, dimensionId)
 *
 * Note:
 *  - The NBT on the raven remains the source of truth for lifetime/flags.
 *  - This index is a best-effort accelerator and is reconciled at runtime.
 */
public final class ScrollSummonedRavenIndexData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String DATA_NAME = Constants.MOD_ID + "_scroll_summoned_ravens";

    public record Entry(@NotNull UUID ownerUuid,
                        @NotNull UUID ravenUuid,
                        @NotNull String dimensionId) {
    }

    private final Map<UUID, Entry> byOwner = new HashMap<>();

    public ScrollSummonedRavenIndexData() {
    }

    public static @NotNull ScrollSummonedRavenIndexData create() {
        return new ScrollSummonedRavenIndexData();
    }

    public static @NotNull ScrollSummonedRavenIndexData load(@NotNull CompoundTag tag) {
        ScrollSummonedRavenIndexData data = new ScrollSummonedRavenIndexData();
        data.readFromNbt(tag);
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        try {
            writeToNbt(tag);
        } catch (Throwable t) {
            LOG.error("[ScrollSummonedRavenIndexData] save failed safely", t);
        }
        return tag;
    }

    public static @NotNull ScrollSummonedRavenIndexData get(@NotNull ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                overworld = level;
            }

            ScrollSummonedRavenIndexData data = overworld.getDataStorage().computeIfAbsent(
                    ScrollSummonedRavenIndexData::load,
                    ScrollSummonedRavenIndexData::create,
                    DATA_NAME
            );
            return data == null ? new ScrollSummonedRavenIndexData() : data;
        } catch (Throwable t) {
            LOG.error("[ScrollSummonedRavenIndexData] get(...) failed safely", t);
            return new ScrollSummonedRavenIndexData();
        }
    }

    public synchronized void setForOwner(@NotNull UUID ownerUuid, @NotNull UUID ravenUuid, @NotNull String dimensionId) {
        if (ownerUuid == null || ravenUuid == null) {
            return;
        }
        String dim = (dimensionId == null) ? "" : dimensionId;
        byOwner.put(ownerUuid, new Entry(ownerUuid, ravenUuid, dim));
        setDirty();
    }

    public synchronized @Nullable Entry getForOwner(@NotNull UUID ownerUuid) {
        return byOwner.get(ownerUuid);
    }

    public synchronized void removeOwner(@NotNull UUID ownerUuid) {
        if (byOwner.remove(ownerUuid) != null) {
            setDirty();
        }
    }

    public synchronized void removeIfMatches(@NotNull UUID ownerUuid, @NotNull UUID ravenUuid) {
        Entry e = byOwner.get(ownerUuid);
        if (e == null) {
            return;
        }
        if (ravenUuid.equals(e.ravenUuid)) {
            byOwner.remove(ownerUuid);
            setDirty();
        }
    }

    public synchronized @NotNull List<Entry> getAllEntries() {
        if (byOwner.isEmpty()) {
            return List.of();
        }
        return List.copyOf(byOwner.values());
    }

    // ---------------------------------------------------------------------
    // NBT
    // ---------------------------------------------------------------------

    private void writeToNbt(@NotNull CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry entry : byOwner.values()) {
            if (entry == null || entry.ownerUuid == null || entry.ravenUuid == null) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putUUID("Owner", entry.ownerUuid);
            e.putUUID("Raven", entry.ravenUuid);
            e.putString("Dim", entry.dimensionId == null ? "" : entry.dimensionId);
            list.add(e);
        }
        tag.put("Entries", list);
    }

    private void readFromNbt(@NotNull CompoundTag tag) {
        try {
            byOwner.clear();
            if (!tag.contains("Entries", Tag.TAG_LIST)) {
                return;
            }
            ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                Tag element = list.get(i);
                if (!(element instanceof CompoundTag e)) {
                    continue;
                }
                if (!e.hasUUID("Owner") || !e.hasUUID("Raven")) {
                    continue;
                }
                UUID owner = e.getUUID("Owner");
                UUID raven = e.getUUID("Raven");
                String dim = e.contains("Dim", Tag.TAG_STRING) ? e.getString("Dim") : "";
                if (owner == null || raven == null) {
                    continue;
                }
                byOwner.put(owner, new Entry(owner, raven, dim == null ? "" : dim));
            }
        } catch (Throwable t) {
            LOG.error("[ScrollSummonedRavenIndexData] readFromNbt failed safely", t);
            byOwner.clear();
        }
    }
}
