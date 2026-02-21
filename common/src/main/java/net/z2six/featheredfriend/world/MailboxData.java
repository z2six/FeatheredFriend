package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World-owned, per-player mailbox inventory storage (8 slots).
 *
 * Unlike player persistent NBT, this SavedData is accessible even when a player is offline,
 * enabling server-side mailbox delivery.
 */
public final class MailboxData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String DATA_NAME = Constants.MOD_ID + "_mailbox_data";

    public static final int SLOT_COUNT = 8;

    private final Map<UUID, NonNullList<ItemStack>> byPlayer = new HashMap<>();

    public MailboxData() {
    }

    public static @NotNull MailboxData create() {
        return new MailboxData();
    }

    public static @NotNull MailboxData load(@NotNull CompoundTag tag, HolderLookup.Provider registries) {
        MailboxData data = new MailboxData();
        data.readFromNbt(tag, registries);
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.Provider registries) {
        try {
            writeToNbt(tag, registries);
        } catch (Throwable t) {
            LOG.error("[MailboxData] save failed safely", t);
        }
        return tag;
    }

    public static @NotNull MailboxData get(@NotNull ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                overworld = level;
            }

            Factory<MailboxData> factory = new Factory<>(MailboxData::create, MailboxData::load, DataFixTypes.LEVEL);
            MailboxData data = overworld.getDataStorage().computeIfAbsent(factory, DATA_NAME);
            return data == null ? new MailboxData() : data;
        } catch (Throwable t) {
            LOG.error("[MailboxData] get(...) failed safely", t);
            return new MailboxData();
        }
    }

    public synchronized @NotNull NonNullList<ItemStack> getContentsCopy(@NotNull UUID playerUuid) {
        NonNullList<ItemStack> out = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        NonNullList<ItemStack> stored = byPlayer.get(playerUuid);
        if (stored == null) {
            return out;
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack s = (i < stored.size()) ? stored.get(i) : ItemStack.EMPTY;
            out.set(i, (s == null || s.isEmpty()) ? ItemStack.EMPTY : s.copy());
        }
        return out;
    }

    public synchronized void setContents(@NotNull UUID playerUuid, @NotNull NonNullList<ItemStack> contents) {
        NonNullList<ItemStack> normalized = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack s = (i < contents.size()) ? contents.get(i) : ItemStack.EMPTY;
            normalized.set(i, (s == null || s.isEmpty()) ? ItemStack.EMPTY : s.copy());
        }

        boolean hasAny = false;
        for (ItemStack s : normalized) {
            if (s != null && !s.isEmpty()) {
                hasAny = true;
                break;
            }
        }

        if (!hasAny) {
            byPlayer.remove(playerUuid);
        } else {
            byPlayer.put(playerUuid, normalized);
        }
        setDirty();
    }

    /**
     * Attempts to insert the given stack into the first empty slot.
     * Returns true when fully inserted.
     */
    public synchronized boolean tryInsertFirstEmpty(@NotNull UUID playerUuid, @NotNull ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }

        NonNullList<ItemStack> stored = byPlayer.computeIfAbsent(playerUuid, k -> NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY));

        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack cur = stored.get(i);
            if (cur == null || cur.isEmpty()) {
                stored.set(i, stack.copy());
                setDirty();
                return true;
            }
        }

        return false;
    }

    private void readFromNbt(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        byPlayer.clear();

        if (!tag.contains("Players", Tag.TAG_LIST)) {
            return;
        }

        ListTag list = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag entryTag)) {
                continue;
            }
            try {
                UUID uuid = entryTag.hasUUID("UUID")
                        ? entryTag.getUUID("UUID")
                        : parseUuidSafe(entryTag.getString("UUIDStr"));
                if (uuid == null) {
                    continue;
                }

                CompoundTag mailboxTag = entryTag.getCompound("Mailbox");
                NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
                if (mailboxTag != null && !mailboxTag.isEmpty()) {
                    ContainerHelper.loadAllItems(mailboxTag, items, registries);
                }

                boolean hasAny = false;
                for (ItemStack s : items) {
                    if (s != null && !s.isEmpty()) {
                        hasAny = true;
                        break;
                    }
                }
                if (hasAny) {
                    byPlayer.put(uuid, items);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void writeToNbt(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        ListTag list = new ListTag();

        for (Map.Entry<UUID, NonNullList<ItemStack>> e : byPlayer.entrySet()) {
            UUID uuid = e.getKey();
            NonNullList<ItemStack> items = e.getValue();
            if (uuid == null || items == null) {
                continue;
            }

            boolean hasAny = false;
            for (ItemStack s : items) {
                if (s != null && !s.isEmpty()) {
                    hasAny = true;
                    break;
                }
            }
            if (!hasAny) {
                continue;
            }

            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("UUID", uuid);
            entryTag.putString("UUIDStr", uuid.toString());

            NonNullList<ItemStack> normalized = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
            for (int i = 0; i < SLOT_COUNT; i++) {
                ItemStack s = (i < items.size()) ? items.get(i) : ItemStack.EMPTY;
                normalized.set(i, (s == null || s.isEmpty()) ? ItemStack.EMPTY : s.copy());
            }

            CompoundTag mailboxTag = new CompoundTag();
            ContainerHelper.saveAllItems(mailboxTag, normalized, registries);
            mailboxTag.putInt("Size", SLOT_COUNT);
            entryTag.put("Mailbox", mailboxTag);

            list.add(entryTag);
        }

        tag.put("Players", list);
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

