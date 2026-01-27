package net.z2six.featheredfriend.data;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
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

    public static FFKnownPlayersData get(MinecraftServer server) {
        try {
            if (server == null || server.overworld() == null) {
                LOG.warn("[FFKnownPlayersData] get(): server/overworld null; returning non-persisting instance");
                return new FFKnownPlayersData();
            }

            DimensionDataStorage storage = server.overworld().getDataStorage();

            // Forge/Mojang 1.20.1 signature: computeIfAbsent(loadFn, newFn, id)
            FFKnownPlayersData data = storage.computeIfAbsent(
                    FFKnownPlayersData::load,
                    FFKnownPlayersData::new,
                    SAVE_ID
            );

            return data != null ? data : new FFKnownPlayersData();
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] get() failed safely; returning non-persisting instance", t);
            return new FFKnownPlayersData();
        }
    }

    private static FFKnownPlayersData load(CompoundTag tag) {
        FFKnownPlayersData data = new FFKnownPlayersData();
        try {
            if (tag != null && tag.contains(NBT_PLAYERS, Tag.TAG_LIST)) {
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

                LOG.debug("[FFKnownPlayersData] Loaded {} known players from disk", loaded);
            } else {
                LOG.debug("[FFKnownPlayersData] No '{}' list in saved data; starting empty", NBT_PLAYERS);
            }
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] load() failed safely; data may be incomplete", t);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
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

    public void addOrUpdate(ServerPlayer player) {
        try {
            if (player == null) return;

            UUID uuid = player.getUUID();
            String name = player.getGameProfile().getName();

            if (uuid == null || name == null || name.isBlank()) {
                LOG.warn("[FFKnownPlayersData] addOrUpdate: invalid player identity (uuid={} name='{}')", uuid, name);
                return;
            }

            String prev = known.put(uuid, name);
            setDirty();

            if (prev == null) {
                LOG.debug("[FFKnownPlayersData] Added known player: {} ({})", name, uuid);
            } else if (!prev.equals(name)) {
                LOG.debug("[FFKnownPlayersData] Updated known player name: {} -> {} ({})", prev, name, uuid);
            } else {
                LOG.debug("[FFKnownPlayersData] addOrUpdate: unchanged for {} ({})", name, uuid);
            }
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] addOrUpdate failed safely", t);
        }
    }

    public List<KnownPlayer> getSortedPlayers() {
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

    public List<String> getSortedNames() {
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

    public record KnownPlayer(UUID uuid, String name) {
    }
}
