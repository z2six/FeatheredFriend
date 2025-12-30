// neoforge/src/main/java/net/z2six/featheredfriend/data/FFKnownPlayersData.java
package net.z2six.featheredfriend.data;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.util.datafix.DataFixTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persisted, server-side known player cache.
 * Stores UUID -> last known name.
 *
 * Saved under: world/data/featheredfriend_known_players.dat
 */
public final class FFKnownPlayersData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String SAVE_ID = "featheredfriend_known_players";
    private static final String NBT_PLAYERS = "Players";
    private static final String NBT_UUID = "UUID";
    private static final String NBT_NAME = "Name";

    private final Map<UUID, String> known = new ConcurrentHashMap<>();

    public FFKnownPlayersData() {
        LOG.debug("[FFKnownPlayersData] Constructed new instance (empty)");
    }

    public static @NotNull FFKnownPlayersData get(@NotNull MinecraftServer server) {
        try {
            if (server.overworld() == null) {
                LOG.warn("[FFKnownPlayersData] get(): server.overworld() is null; returning non-persisting instance");
                return new FFKnownPlayersData();
            }

            DimensionDataStorage storage = server.overworld().getDataStorage();

            Factory<FFKnownPlayersData> factory = new Factory<>(
                    FFKnownPlayersData::new,
                    FFKnownPlayersData::load,
                    DataFixTypes.LEVEL
            );

            FFKnownPlayersData data = storage.computeIfAbsent(factory, SAVE_ID);
            if (data == null) {
                LOG.error("[FFKnownPlayersData] get(): computeIfAbsent returned null; returning non-persisting instance");
                return new FFKnownPlayersData();
            }
            return data;
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] get() failed safely; returning non-persisting instance", t);
            return new FFKnownPlayersData();
        }
    }

    private static @NotNull FFKnownPlayersData load(@NotNull CompoundTag tag, @NotNull net.minecraft.core.HolderLookup.Provider lookup) {
        FFKnownPlayersData data = new FFKnownPlayersData();
        try {
            if (tag.contains(NBT_PLAYERS, Tag.TAG_LIST)) {
                ListTag list = tag.getList(NBT_PLAYERS, Tag.TAG_COMPOUND);
                int loaded = 0;

                for (int i = 0; i < list.size(); i++) {
                    Tag t = list.get(i);
                    if (!(t instanceof CompoundTag ct)) continue;

                    String uuidStr = ct.getString(NBT_UUID);
                    String name = ct.getString(NBT_NAME);

                    if (uuidStr == null || uuidStr.isBlank() || name == null || name.isBlank()) continue;

                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        data.known.put(uuid, name);
                        loaded++;
                    } catch (Throwable ignored) {
                        // skip malformed UUID
                    }
                }

                LOG.info("[FFKnownPlayersData] Loaded {} known players from disk", loaded);
            } else {
                LOG.info("[FFKnownPlayersData] No '{}' list in saved data; starting empty", NBT_PLAYERS);
            }
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] load() failed safely; data may be incomplete", t);
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, @NotNull net.minecraft.core.HolderLookup.Provider lookup) {
        try {
            ListTag list = new ListTag();

            int written = 0;
            for (Map.Entry<UUID, String> e : known.entrySet()) {
                UUID uuid = e.getKey();
                String name = e.getValue();
                if (uuid == null || name == null || name.isBlank()) continue;

                CompoundTag ct = new CompoundTag();
                ct.putString(NBT_UUID, uuid.toString());
                ct.putString(NBT_NAME, name);
                list.add(ct);
                written++;
            }

            tag.put(NBT_PLAYERS, list);

            LOG.debug("[FFKnownPlayersData] save(): wrote {} players", written);
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] save() failed safely", t);
        }
        return tag;
    }

    public void addOrUpdate(@NotNull ServerPlayer player) {
        try {
            UUID uuid = player.getUUID();
            String name = player.getGameProfile().getName();

            if (uuid == null || name == null || name.isBlank()) {
                LOG.warn("[FFKnownPlayersData] addOrUpdate: invalid player identity (uuid={} name='{}')", uuid, name);
                return;
            }

            String prev = known.put(uuid, name);
            setDirty();

            if (prev == null) {
                LOG.info("[FFKnownPlayersData] Added known player: {} ({})", name, uuid);
            } else if (!prev.equals(name)) {
                LOG.info("[FFKnownPlayersData] Updated known player name: {} -> {} ({})", prev, name, uuid);
            } else {
                LOG.debug("[FFKnownPlayersData] addOrUpdate: unchanged for {} ({})", name, uuid);
            }
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] addOrUpdate failed safely", t);
        }
    }

    public @NotNull List<KnownPlayer> getSortedPlayers() {
        try {
            List<KnownPlayer> out = new ArrayList<>();
            for (Map.Entry<UUID, String> e : known.entrySet()) {
                UUID uuid = e.getKey();
                String name = e.getValue();
                if (uuid == null || name == null || name.isBlank()) continue;
                out.add(new KnownPlayer(uuid, name));
            }
            out.sort(Comparator.comparing(KnownPlayer::name, String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] getSortedPlayers failed safely", t);
            return List.of();
        }
    }

    public @NotNull List<String> getSortedNames() {
        try {
            List<String> names = new ArrayList<>();
            for (KnownPlayer kp : getSortedPlayers()) {
                names.add(kp.name());
            }
            return names;
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] getSortedNames failed safely", t);
            return List.of();
        }
    }

    public record KnownPlayer(@NotNull UUID uuid, @NotNull String name) {
    }
}
