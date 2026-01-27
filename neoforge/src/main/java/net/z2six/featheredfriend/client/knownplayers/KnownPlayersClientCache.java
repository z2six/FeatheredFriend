// neoforge/src/main/java/net/z2six/featheredfriend/client/knownplayers/KnownPlayersClientCache.java
package net.z2six.featheredfriend.client.knownplayers;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.z2six.featheredfriend.network.FFNetwork;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class KnownPlayersClientCache {

    private static final Logger LOG = LogUtils.getLogger();

    public static final class KnownPlayerEntry {
        private final UUID uuid;
        private final String name;
        private final boolean online;

        public KnownPlayerEntry(@NotNull UUID uuid, @NotNull String name, boolean online) {
            this.uuid = uuid;
            this.name = name;
            this.online = online;
        }

        public @NotNull UUID getUuid() {
            return uuid;
        }

        public @NotNull String getName() {
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

    private final Map<UUID, String> knownNames = new ConcurrentHashMap<>();
    private volatile long lastOnlineRefreshClientTick = -1;

    private KnownPlayersClientCache() {
        LOG.debug("[KnownPlayersClientCache] Singleton created");
    }

    /**
     * Replace the entire known list from the server (UUID + name).
     * Online status is computed locally from the client connection.
     */
    public synchronized void replaceAllFromServer(@NotNull Collection<FFNetwork.KnownPlayerInfo> newEntries) {
        try {
            knownNames.clear();

            int kept = 0;
            for (FFNetwork.KnownPlayerInfo info : newEntries) {
                if (info == null) continue;
                UUID uuid = info.uuid();
                String name = info.name();
                if (uuid == null) continue;
                if (name == null || name.isBlank()) continue;

                knownNames.put(uuid, name);
                kept++;
            }

            LOG.debug("[KnownPlayersClientCache] replaceAllFromServer: now tracking {} known players", kept);

            // Immediately refresh online flags view
            refreshOnlineNow();
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] replaceAllFromServer failed", t);
        }
    }

    /**
     * Upsert from any source (defensive).
     */
    public synchronized void upsert(@Nullable UUID uuid, @Nullable String name) {
        if (uuid == null) return;
        if (name == null || name.isBlank()) return;
        try {
            knownNames.put(uuid, name);
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] upsert failed for uuid={}", uuid, t);
        }
    }

    /**
     * Ask the server to resend known players.
     * Useful when opening UI early, reconnect race, missed broadcast, etc.
     */
    public void requestRefreshFromServer() {
        try {
            FFNetwork.sendRequestKnownPlayersToServer();
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] requestRefreshFromServer failed", t);
        }
    }

    /**
     * Returns sorted view with online status computed from current connection.
     * Also periodically refreshes online list (cheap).
     */
    public synchronized @NotNull List<KnownPlayerEntry> getAllPlayersSorted() {
        try {
            refreshOnlinePeriodically();

            Set<UUID> onlineNow = getOnlineUuidsFromConnection();
            List<KnownPlayerEntry> out = new ArrayList<>(knownNames.size());

            for (Map.Entry<UUID, String> e : knownNames.entrySet()) {
                UUID uuid = e.getKey();
                String name = e.getValue();
                if (uuid == null || name == null || name.isBlank()) continue;

                boolean online = onlineNow.contains(uuid);
                out.add(new KnownPlayerEntry(uuid, name, online));
            }

            out.sort(Comparator.comparing(KnownPlayerEntry::getName, String.CASE_INSENSITIVE_ORDER));
            return out;
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] getAllPlayersSorted failed", t);
            return List.of();
        }
    }

    /**
     * Adds all currently online players to known list (defensive),
     * and also refreshes online flag timing.
     */
    public synchronized void refreshFromClientConnection() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null) {
                return;
            }

            Collection<PlayerInfo> infoList = mc.getConnection().getOnlinePlayers();
            if (infoList == null) {
                return;
            }

            int added = 0;
            for (PlayerInfo info : infoList) {
                if (info == null || info.getProfile() == null) continue;

                UUID uuid = info.getProfile().getId();
                String name = info.getProfile().getName();
                if (uuid == null || name == null || name.isBlank()) continue;

                if (!knownNames.containsKey(uuid)) {
                    added++;
                }
                knownNames.put(uuid, name);
            }

            if (added > 0) {
                LOG.debug("[KnownPlayersClientCache] refreshFromClientConnection: added {} online players into known list", added);
            }

            refreshOnlineNow();
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] refreshFromClientConnection failed", t);
        }
    }

    private void refreshOnlinePeriodically() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return;

            long tick = mc.player.tickCount;

            // refresh at most once per 10 ticks
            if (lastOnlineRefreshClientTick >= 0 && (tick - lastOnlineRefreshClientTick) < 10) {
                return;
            }
            lastOnlineRefreshClientTick = tick;

            // Pulling online UUIDs is cheap; we just do it to keep log timing consistent.
            Set<UUID> online = getOnlineUuidsFromConnection();
            LOG.debug("[KnownPlayersClientCache] refreshOnlinePeriodically: onlineCount={} knownCount={}",
                    online.size(), knownNames.size());

        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] refreshOnlinePeriodically failed", t);
        }
    }

    private void refreshOnlineNow() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return;
            lastOnlineRefreshClientTick = mc.player.tickCount;
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] refreshOnlineNow failed", t);
        }
    }

    private @NotNull Set<UUID> getOnlineUuidsFromConnection() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null) {
                return Set.of();
            }
            Collection<PlayerInfo> infoList = mc.getConnection().getOnlinePlayers();
            if (infoList == null) {
                return Set.of();
            }

            Set<UUID> out = new HashSet<>();
            for (PlayerInfo info : infoList) {
                if (info == null || info.getProfile() == null) continue;
                UUID uuid = info.getProfile().getId();
                if (uuid != null) out.add(uuid);
            }
            return out;
        } catch (Throwable t) {
            LOG.error("[KnownPlayersClientCache] getOnlineUuidsFromConnection failed", t);
            return Set.of();
        }
    }
}
