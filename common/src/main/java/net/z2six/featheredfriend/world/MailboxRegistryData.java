package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
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
 * World-owned registry of "known mailbox locations", keyed by the observer player UUID.
 *
 * Structure:
 *   observerUuid -> mailboxOwnerUuid -> list of mailbox locations (dimension + pos)
 *
 * This is server-side and independent from any specific raven.
 */
public final class MailboxRegistryData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String DATA_NAME = Constants.MOD_ID + "_mailbox_registry";

    public record MailboxLocation(@NotNull String dimensionId,
                                  long blockPos,
                                  @NotNull String mailboxOwnerName) {
        public @NotNull BlockPos pos() {
            return BlockPos.of(blockPos);
        }
    }

    private final Map<UUID, Map<UUID, List<MailboxLocation>>> byObserver = new HashMap<>();

    public MailboxRegistryData() {
    }

    public static @NotNull MailboxRegistryData create() {
        return new MailboxRegistryData();
    }

    public static @NotNull MailboxRegistryData load(@NotNull CompoundTag tag) {
        MailboxRegistryData data = new MailboxRegistryData();
        data.readFromNbt(tag);
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        try {
            writeToNbt(tag);
        } catch (Throwable t) {
            LOG.error("[MailboxRegistryData] save failed safely", t);
        }
        return tag;
    }

    public static @NotNull MailboxRegistryData get(@NotNull ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                overworld = level;
            }

            MailboxRegistryData data = overworld.getDataStorage().computeIfAbsent(MailboxRegistryData::load, MailboxRegistryData::create, DATA_NAME);
            return data == null ? new MailboxRegistryData() : data;
        } catch (Throwable t) {
            LOG.error("[MailboxRegistryData] get(...) failed safely", t);
            return new MailboxRegistryData();
        }
    }

    public synchronized int getKnownMailboxCount(@NotNull UUID observerUuid, @NotNull UUID mailboxOwnerUuid) {
        Map<UUID, List<MailboxLocation>> owners = byObserver.get(observerUuid);
        if (owners == null) {
            return 0;
        }
        List<MailboxLocation> list = owners.get(mailboxOwnerUuid);
        return (list == null) ? 0 : list.size();
    }

    public synchronized @NotNull Map<UUID, Integer> getMailboxCountsForObserver(@NotNull UUID observerUuid) {
        Map<UUID, Integer> out = new HashMap<>();
        Map<UUID, List<MailboxLocation>> owners = byObserver.get(observerUuid);
        if (owners == null || owners.isEmpty()) {
            return out;
        }
        for (Map.Entry<UUID, List<MailboxLocation>> e : owners.entrySet()) {
            UUID ownerUuid = e.getKey();
            List<MailboxLocation> list = e.getValue();
            if (ownerUuid == null || list == null) {
                continue;
            }
            out.put(ownerUuid, Math.max(0, list.size()));
        }
        return out;
    }

    public synchronized @Nullable MailboxLocation getFirstKnownMailbox(@NotNull UUID observerUuid, @NotNull UUID mailboxOwnerUuid) {
        Map<UUID, List<MailboxLocation>> owners = byObserver.get(observerUuid);
        if (owners == null) {
            return null;
        }
        List<MailboxLocation> list = owners.get(mailboxOwnerUuid);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    public synchronized @NotNull List<MailboxLocation> getKnownMailboxes(@NotNull UUID observerUuid, @NotNull UUID mailboxOwnerUuid) {
        Map<UUID, List<MailboxLocation>> owners = byObserver.get(observerUuid);
        if (owners == null) {
            return List.of();
        }
        List<MailboxLocation> list = owners.get(mailboxOwnerUuid);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return List.copyOf(list);
    }

    /**
     * Records a mailbox location as "known" for the observer.
     *
     * @return true when added as a new location (i.e. a new discovery).
     */
    public synchronized boolean registerMailbox(@NotNull UUID observerUuid,
                                               @NotNull UUID mailboxOwnerUuid,
                                               @NotNull String mailboxOwnerName,
                                               @NotNull String dimensionId,
                                               @NotNull BlockPos pos) {
        Map<UUID, List<MailboxLocation>> owners =
                byObserver.computeIfAbsent(observerUuid, k -> new HashMap<>());
        List<MailboxLocation> list =
                owners.computeIfAbsent(mailboxOwnerUuid, k -> new ArrayList<>());

        long blockPos = pos.asLong();
        for (int i = 0; i < list.size(); i++) {
            MailboxLocation existing = list.get(i);
            if (existing == null) {
                continue;
            }
            if (existing.blockPos == blockPos && Objects.equals(existing.dimensionId, dimensionId)) {
                // Update stored owner name if it changed.
                String cleaned = sanitizeName(mailboxOwnerName);
                if (!Objects.equals(existing.mailboxOwnerName, cleaned)) {
                    list.set(i, new MailboxLocation(existing.dimensionId, existing.blockPos, cleaned));
                    setDirty();
                }
                return false;
            }
        }

        list.add(new MailboxLocation(
                sanitizeDim(dimensionId),
                blockPos,
                sanitizeName(mailboxOwnerName)
        ));
        setDirty();
        return true;
    }

    public synchronized boolean removeKnownMailbox(@NotNull UUID observerUuid,
                                                   @NotNull UUID mailboxOwnerUuid,
                                                   @NotNull String dimensionId,
                                                   long blockPos) {
        Map<UUID, List<MailboxLocation>> owners = byObserver.get(observerUuid);
        if (owners == null) {
            return false;
        }
        List<MailboxLocation> list = owners.get(mailboxOwnerUuid);
        if (list == null || list.isEmpty()) {
            return false;
        }

        boolean removedAny = false;
        Iterator<MailboxLocation> it = list.iterator();
        while (it.hasNext()) {
            MailboxLocation loc = it.next();
            if (loc == null) {
                continue;
            }
            if (loc.blockPos == blockPos && Objects.equals(loc.dimensionId, dimensionId)) {
                it.remove();
                removedAny = true;
                break;
            }
        }

        if (removedAny) {
            if (list.isEmpty()) {
                owners.remove(mailboxOwnerUuid);
            }
            if (owners.isEmpty()) {
                byObserver.remove(observerUuid);
            }
            setDirty();
        }

        return removedAny;
    }

    private void readFromNbt(@NotNull CompoundTag tag) {
        byObserver.clear();

        if (!tag.contains("Mailboxes", Tag.TAG_LIST)) {
            return;
        }

        ListTag list = tag.getList("Mailboxes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag entryTag)) {
                continue;
            }
            try {
                UUID observerUuid = entryTag.hasUUID("ObserverUUID")
                        ? entryTag.getUUID("ObserverUUID")
                        : parseUuidSafe(entryTag.getString("ObserverUUIDStr"));
                UUID ownerUuid = entryTag.hasUUID("MailboxOwnerUUID")
                        ? entryTag.getUUID("MailboxOwnerUUID")
                        : parseUuidSafe(entryTag.getString("MailboxOwnerUUIDStr"));
                String ownerName = entryTag.contains("MailboxOwnerName", Tag.TAG_STRING) ? entryTag.getString("MailboxOwnerName") : "";
                String dim = entryTag.getString("Dimension");
                long pos = entryTag.getLong("BlockPos");

                if (observerUuid == null || ownerUuid == null) {
                    continue;
                }
                if (dim == null || dim.isBlank()) {
                    continue;
                }
                if (pos == 0L) {
                    continue;
                }

                Map<UUID, List<MailboxLocation>> owners =
                        byObserver.computeIfAbsent(observerUuid, k -> new HashMap<>());
                List<MailboxLocation> ownerList =
                        owners.computeIfAbsent(ownerUuid, k -> new ArrayList<>());

                // Avoid duplicates on load.
                boolean exists = false;
                for (MailboxLocation loc : ownerList) {
                    if (loc == null) continue;
                    if (loc.blockPos == pos && Objects.equals(loc.dimensionId, dim)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    ownerList.add(new MailboxLocation(sanitizeDim(dim), pos, sanitizeName(ownerName)));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void writeToNbt(@NotNull CompoundTag tag) {
        ListTag list = new ListTag();

        for (Map.Entry<UUID, Map<UUID, List<MailboxLocation>>> obsEntry : byObserver.entrySet()) {
            UUID observerUuid = obsEntry.getKey();
            Map<UUID, List<MailboxLocation>> owners = obsEntry.getValue();
            if (observerUuid == null || owners == null || owners.isEmpty()) {
                continue;
            }

            for (Map.Entry<UUID, List<MailboxLocation>> ownerEntry : owners.entrySet()) {
                UUID mailboxOwnerUuid = ownerEntry.getKey();
                List<MailboxLocation> locs = ownerEntry.getValue();
                if (mailboxOwnerUuid == null || locs == null || locs.isEmpty()) {
                    continue;
                }

                for (MailboxLocation loc : locs) {
                    if (loc == null) {
                        continue;
                    }
                    try {
                        CompoundTag entryTag = new CompoundTag();
                        entryTag.putUUID("ObserverUUID", observerUuid);
                        entryTag.putString("ObserverUUIDStr", observerUuid.toString());
                        entryTag.putUUID("MailboxOwnerUUID", mailboxOwnerUuid);
                        entryTag.putString("MailboxOwnerUUIDStr", mailboxOwnerUuid.toString());
                        entryTag.putString("MailboxOwnerName", sanitizeName(loc.mailboxOwnerName));
                        entryTag.putString("Dimension", sanitizeDim(loc.dimensionId));
                        entryTag.putLong("BlockPos", loc.blockPos);
                        list.add(entryTag);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        tag.put("Mailboxes", list);
    }

    private static @NotNull String sanitizeDim(@Nullable String value) {
        String raw = value == null ? "" : value.trim();
        return raw.isEmpty() ? "minecraft:overworld" : raw;
    }

    private static @NotNull String sanitizeName(@Nullable String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.length() > 64) {
            return raw.substring(0, 64);
        }
        return raw;
    }

    private static @Nullable UUID parseUuidSafe(@Nullable String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return UUID.fromString(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
