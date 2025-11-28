// neoforge/src/main/java/net/z2six/featheredfriend/data/FFKnownPlayersData.java
package net.z2six.featheredfriend.data;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.HolderLookup;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.*;

/**
 * FFKnownPlayersData
 *
 * Server-side persistent storage of all players that have ever joined this world.
 * Backed by SavedData on the server, so it is world-specific and survives restarts.
 */
public class FFKnownPlayersData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String DATA_NAME = Constants.MOD_ID + "_known_players";

    private final Map<UUID, String> knownPlayers = new HashMap<>();

    public FFKnownPlayersData() {
        LOG.debug("[FFKnownPlayersData] Created new empty instance");
    }

    // -------------------------------------------------------------------------
    // SavedData plumbing
    // -------------------------------------------------------------------------

    public static @NotNull FFKnownPlayersData get(@NotNull MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            // Should never happen on a normal dedicated or integrated server.
            throw new IllegalStateException("[FFKnownPlayersData] Overworld is null");
        }

        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        FFKnownPlayersData::new,
                        FFKnownPlayersData::load,
                        null
                ),
                DATA_NAME
        );
    }

    private static @NotNull FFKnownPlayersData load(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider lookup) {
        FFKnownPlayersData data = new FFKnownPlayersData();

        try {
            ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
            for (Tag t : list) {
                if (!(t instanceof CompoundTag playerTag)) {
                    continue;
                }
                String uuidStr = playerTag.getString("uuid");
                String name = playerTag.getString("name");
                if (uuidStr.isEmpty() || name.isEmpty()) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    data.knownPlayers.put(uuid, name);
                } catch (IllegalArgumentException ex) {
                    LOG.warn("[FFKnownPlayersData] Skipping invalid UUID '{}'", uuidStr, ex);
                }
            }

            LOG.info("[FFKnownPlayersData] Loaded {} known players", data.knownPlayers.size());
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] Failed to load from NBT", t);
        }

        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider lookup) {
        try {
            ListTag list = new ListTag();

            for (Map.Entry<UUID, String> entry : knownPlayers.entrySet()) {
                CompoundTag playerTag = new CompoundTag();
                playerTag.putString("uuid", entry.getKey().toString());
                playerTag.putString("name", entry.getValue());
                list.add(playerTag);
            }

            tag.put("players", list);
            LOG.debug("[FFKnownPlayersData] Saved {} known players", knownPlayers.size());
        } catch (Throwable t) {
            LOG.error("[FFKnownPlayersData] Failed to save to NBT", t);
        }

        return tag;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void addOrUpdate(@NotNull ServerPlayer player) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();

        String old = knownPlayers.put(uuid, name);
        if (!name.equals(old)) {
            setDirty();
            LOG.debug("[FFKnownPlayersData] Recorded player {} ({})", name, uuid);
        }
    }

    /**
     * Returns a sorted, unmodifiable list of all known player names.
     * Sorting is case-insensitive, alphabetical.
     */
    public @NotNull List<String> getSortedNames() {
        List<String> names = new ArrayList<>(knownPlayers.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return Collections.unmodifiableList(names);
    }
}
