package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * World-owned registry of Raven Chest ownership and labels.
 * Entries are keyed by (dimension, blockPos), with a single owner per chest.
 */
public final class RavenChestRegistryData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String DATA_NAME = Constants.MOD_ID + "_raven_chest_registry";

    public record ChestRecord(@NotNull UUID ownerUuid,
                              @NotNull String dimensionId,
                              long blockPos,
                              @NotNull String label) {
        public @NotNull BlockPos pos() {
            return BlockPos.of(blockPos);
        }
    }

    private final List<ChestRecord> entries = new ArrayList<>();

    public RavenChestRegistryData() {
    }

    public static @NotNull RavenChestRegistryData create() {
        return new RavenChestRegistryData();
    }

    public static @NotNull RavenChestRegistryData load(@NotNull CompoundTag tag, HolderLookup.Provider ignoredRegistries) {
        RavenChestRegistryData data = new RavenChestRegistryData();
        data.readFromNbt(tag);
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.Provider registries) {
        try {
            writeToNbt(tag);
        } catch (Throwable t) {
            LOG.error("[RavenChestRegistryData] save failed safely", t);
        }
        return tag;
    }

    public static @NotNull RavenChestRegistryData get(@NotNull ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                overworld = level;
            }

            SavedData.Factory<RavenChestRegistryData> factory =
                    new SavedData.Factory<>(RavenChestRegistryData::create, RavenChestRegistryData::load, DataFixTypes.LEVEL);

            return overworld.getDataStorage().computeIfAbsent(factory, DATA_NAME);
        } catch (Throwable t) {
            LOG.error("[RavenChestRegistryData] get(...) failed safely", t);
            return new RavenChestRegistryData();
        }
    }

    public synchronized int getCountForOwner(@NotNull UUID ownerUuid) {
        int count = 0;
        for (ChestRecord entry : this.entries) {
            if (entry.ownerUuid().equals(ownerUuid)) {
                count++;
            }
        }
        return count;
    }

    public synchronized @NotNull List<ChestRecord> getChestsForOwner(@NotNull UUID ownerUuid) {
        List<ChestRecord> out = new ArrayList<>();
        for (ChestRecord entry : this.entries) {
            if (entry.ownerUuid().equals(ownerUuid)) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    public synchronized boolean isOwnedBy(@NotNull UUID ownerUuid,
                                          @NotNull String dimensionId,
                                          long blockPos) {
        ChestRecord rec = findByLocation(dimensionId, blockPos);
        return rec != null && rec.ownerUuid().equals(ownerUuid);
    }

    public synchronized @Nullable ChestRecord getOwnedRecordAt(@NotNull UUID ownerUuid,
                                                               @NotNull String dimensionId,
                                                               long blockPos) {
        ChestRecord rec = findByLocation(dimensionId, blockPos);
        if (rec == null || !rec.ownerUuid().equals(ownerUuid)) {
            return null;
        }
        return rec;
    }

    public synchronized boolean registerChest(@NotNull UUID ownerUuid,
                                              @NotNull String dimensionId,
                                              @NotNull BlockPos pos,
                                              @NotNull String defaultLabel) {
        long blockPos = pos.asLong();
        ChestRecord existing = findByLocation(dimensionId, blockPos);
        String label = sanitizeLabel(defaultLabel);

        if (existing != null) {
            ChestRecord replacement = new ChestRecord(ownerUuid, dimensionId, blockPos,
                    existing.label() == null || existing.label().isBlank() ? label : existing.label());
            replace(existing, replacement);
            this.setDirty();
            return false;
        }

        this.entries.add(new ChestRecord(ownerUuid, dimensionId, blockPos, label));
        this.setDirty();
        return true;
    }

    public synchronized boolean unregisterChest(@NotNull String dimensionId, long blockPos) {
        ChestRecord rec = findByLocation(dimensionId, blockPos);
        if (rec == null) {
            return false;
        }
        this.entries.remove(rec);
        this.setDirty();
        return true;
    }

    public synchronized boolean setLabel(@NotNull UUID ownerUuid,
                                         @NotNull String dimensionId,
                                         long blockPos,
                                         @NotNull String label) {
        ChestRecord rec = findByLocation(dimensionId, blockPos);
        if (rec == null || !rec.ownerUuid().equals(ownerUuid)) {
            return false;
        }

        ChestRecord replacement = new ChestRecord(ownerUuid, dimensionId, blockPos, sanitizeLabel(label));
        replace(rec, replacement);
        this.setDirty();
        return true;
    }

    public synchronized @Nullable String getLabel(@NotNull String dimensionId, long blockPos) {
        ChestRecord rec = findByLocation(dimensionId, blockPos);
        return rec == null ? null : rec.label();
    }

    private @Nullable ChestRecord findByLocation(@NotNull String dimensionId, long blockPos) {
        for (ChestRecord entry : this.entries) {
            if (entry.blockPos() == blockPos && Objects.equals(entry.dimensionId(), dimensionId)) {
                return entry;
            }
        }
        return null;
    }

    private void replace(@NotNull ChestRecord oldEntry, @NotNull ChestRecord newEntry) {
        int idx = this.entries.indexOf(oldEntry);
        if (idx < 0) {
            this.entries.add(newEntry);
        } else {
            this.entries.set(idx, newEntry);
        }
    }

    private static @NotNull String sanitizeLabel(@Nullable String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) {
            return "Raven Chest";
        }
        if (raw.length() > 48) {
            return raw.substring(0, 48);
        }
        return raw;
    }

    private void readFromNbt(@NotNull CompoundTag tag) {
        this.entries.clear();
        if (!tag.contains("Chests", Tag.TAG_LIST)) {
            return;
        }

        ListTag list = tag.getList("Chests", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag entryTag)) {
                continue;
            }
            try {
                UUID owner = entryTag.hasUUID("OwnerUUID")
                        ? entryTag.getUUID("OwnerUUID")
                        : UUID.fromString(entryTag.getString("OwnerUUIDStr"));
                String dim = entryTag.getString("Dimension");
                long pos = entryTag.getLong("BlockPos");
                String label = sanitizeLabel(entryTag.getString("Label"));
                if (dim == null || dim.isBlank()) {
                    continue;
                }
                this.entries.add(new ChestRecord(owner, dim, pos, label));
            } catch (Throwable ignored) {
            }
        }
    }

    private void writeToNbt(@NotNull CompoundTag tag) {
        ListTag list = new ListTag();
        for (ChestRecord entry : this.entries) {
            try {
                CompoundTag chest = new CompoundTag();
                chest.putUUID("OwnerUUID", entry.ownerUuid());
                chest.putString("Dimension", entry.dimensionId());
                chest.putLong("BlockPos", entry.blockPos());
                chest.putString("Label", sanitizeLabel(entry.label()));
                list.add(chest);
            } catch (Throwable ignored) {
            }
        }
        tag.put("Chests", list);
    }
}
