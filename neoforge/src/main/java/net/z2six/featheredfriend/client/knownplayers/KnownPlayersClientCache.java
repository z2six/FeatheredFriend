// neoforge/src/main/java/net/z2six/featheredfriend/client/knownplayers/KnownPlayersClientCache.java
package net.z2six.featheredfriend.client.knownplayers;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KnownPlayersClientCache
 *
 * Simple client-side cache of known players.
 *
 * For now:
 *  - Can be seeded from the current client connection (online players).
 *  - Can be updated wholesale from a network payload (not wired here yet).
 *
 * This is intentionally generic so we can plug it into FFNetwork later, but
 * also works standalone by just calling refreshFromClientConnection().
 */
public final class KnownPlayersClientCache {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Simple DTO used by the overlay.
     */
    public static final class KnownPlayerEntry {
        private final UUID uuid;
        private final String name;
        private final boolean online;

        public KnownPlayerEntry(UUID uuid, String name, boolean online) {
            this.uuid = uuid;
            this.name = name;
            this.online = online;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public boolean isOnline() {
            return online;
        }
    }

    private static final KnownPlayersClientCache INSTANCE = new KnownPlayersClientCache();

    public static KnownPlayersClientCache getInstance() {
        return INSTANCE;
    }

    // uuid -> entry
    private final Map<UUID, KnownPlayerEntry> entries = new ConcurrentHashMap<>();

    private KnownPlayersClientCache() {
        LOG.debug("[KnownPlayersClientCache] Singleton created");
    }

    /**
     * Replace the entire cache with a new collection (for network payload).
     */
    public synchronized void replaceAll(Collection<KnownPlayerEntry> newEntries) {
        try {
            entries.clear();
            for (KnownPlayerEntry e : newEntries) {
                if (e != null && e.getUuid() != null) {
                    entries.put(e.getUuid(), e);
                }
            }
            LOG.debug("[KnownPlayersClientCache] replaceAll: now tracking {} players", entries.size());
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] replaceAll failed", t);
        }
    }

    /**
     * Upsert a single player (used by local seeding).
     */
    public synchronized void upsert(UUID uuid, String name, boolean online) {
        if (uuid == null || name == null || name.isEmpty()) {
            return;
        }
        try {
            entries.put(uuid, new KnownPlayerEntry(uuid, name, online));
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] upsert failed for {}", name, t);
        }
    }

    /**
     * Get a snapshot list of all known players.
     */
    public synchronized List<KnownPlayerEntry> getAllPlayers() {
        return new ArrayList<>(entries.values());
    }

    /**
     * Convenience: seed/update from the current client connection.
     * This gives at least all *online* players even if no payload has arrived.
     */
    public void refreshFromClientConnection() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null) {
                return;
            }

            Collection<PlayerInfo> infoList = mc.getConnection().getOnlinePlayers();
            if (infoList == null) {
                return;
            }

            for (PlayerInfo info : infoList) {
                if (info == null || info.getProfile() == null) {
                    continue;
                }
                UUID uuid = info.getProfile().getId();
                String name = info.getProfile().getName();
                upsert(uuid, name, true);
            }

            LOG.debug("[KnownPlayersClientCache] refreshFromClientConnection: now tracking {} players",
                    entries.size());
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] refreshFromClientConnection failed", t);
        }
    }
}
