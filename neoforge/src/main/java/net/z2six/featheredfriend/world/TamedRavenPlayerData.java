// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenPlayerData.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenPlayerData.java
 *
 * Centralized helper for reading and clearing per-player TamedRaven data stored
 * in NeoForge persistent player data.
 *
 * Structure:
 *
 * NeoForgeData: {
 *   featheredfriend: {
 *     TamedRaven: {
 *       OwnerUUID: ...
 *       OwnerDimension: ...
 *       RavenName: "..."
 *       HasTamedRaven: 1b
 *       ...
 *     }
 *   }
 * }
 */
public final class TamedRavenPlayerData {

    private static final Logger LOG = LogUtils.getLogger();

    private TamedRavenPlayerData() {
        // no-op
    }

    /**
     * Lightweight view of a player's TamedRaven info.
     *
     * hasTamedRaven:
     *   - true  => the player has a raven recorded in their persistent data.
     *   - false => no tamed raven data (or an error occurred).
     *
     * ravenName:
     *   - name stored under "RavenName" (may be empty or null if not present).
     *
     * boundRavenId:
     *   - stable UUID used to identify the bound raven instance (may be null for legacy data).
     */
    public record TamedRavenInfo(boolean hasTamedRaven, String ravenName, UUID boundRavenId) {

        public static TamedRavenInfo empty() {
            return new TamedRavenInfo(false, "", null);
        }
    }

    /**
     * Reads the player's TamedRaven info from persistent NBT without mutating it.
     */
    public static TamedRavenInfo getTamedRavenInfo(ServerPlayer player) {
        try {
            if (player == null) {
                return TamedRavenInfo.empty();
            }

            CompoundTag root = player.getPersistentData();
            if (root == null) {
                return TamedRavenInfo.empty();
            }

            if (!root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return TamedRavenInfo.empty();
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains("TamedRaven", Tag.TAG_COMPOUND)) {
                return TamedRavenInfo.empty();
            }

            CompoundTag tamed = modTag.getCompound("TamedRaven");
            if (tamed == null || tamed.isEmpty()) {
                return TamedRavenInfo.empty();
            }

            boolean has = tamed.getBoolean("HasTamedRaven");
            String name = "";
            if (tamed.contains("RavenName", Tag.TAG_STRING)) {
                name = tamed.getString("RavenName");
            }

            UUID boundId = null;
            try {
                if (tamed.hasUUID("BoundRavenId")) {
                    boundId = tamed.getUUID("BoundRavenId");
                }
            } catch (Throwable ignored) {
            }

            return new TamedRavenInfo(has, name, boundId);
        } catch (Throwable t) {
            LOG.error("[TamedRavenPlayerData] getTamedRavenInfo failed for player={}",
                    (player == null ? "null" : player.getGameProfile().getName()), t);
            return TamedRavenInfo.empty();
        }
    }

    /**
     * Stores/updates the player's TamedRaven info in persistent data.
     * This is the single source of truth used by taming and scroll-summon logic.
     */
    public static void storeTamedRavenInfo(ServerPlayer player, String ravenName, UUID boundRavenId) {
        try {
            if (player == null) {
                return;
            }

            CompoundTag root = player.getPersistentData();
            if (root == null) {
                LOG.warn("[TamedRavenPlayerData] storeTamedRavenInfo: player persistent data is null");
                return;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            CompoundTag tamed = new CompoundTag();

            tamed.putBoolean("HasTamedRaven", true);
            tamed.putString("RavenName", ravenName != null ? ravenName : "Raven");
            tamed.putUUID("OwnerUUID", player.getUUID());

            try {
                if (player.level() != null) {
                    tamed.putString("OwnerDimension", player.level().dimension().location().toString());
                }
            } catch (Throwable ignored) {
            }

            if (boundRavenId != null) {
                tamed.putUUID("BoundRavenId", boundRavenId);
            }

            modTag.put("TamedRaven", tamed);
            root.put(Constants.MOD_ID, modTag);

            LOG.debug("[TamedRavenPlayerData] Stored tamed raven for player={} name='{}' boundId={}",
                    player.getGameProfile().getName(),
                    ravenName,
                    boundRavenId);
        } catch (Throwable t) {
            LOG.error("[TamedRavenPlayerData] storeTamedRavenInfo failed for player={}",
                    (player == null ? "null" : player.getGameProfile().getName()), t);
        }
    }

    /**
     * Ensures a BoundRavenId exists for legacy records.
     * Returns the bound id that should be used going forward.
     */
    public static UUID ensureBoundRavenId(ServerPlayer player, UUID fallbackIfMissing) {
        try {
            if (player == null) {
                return fallbackIfMissing;
            }

            CompoundTag root = player.getPersistentData();
            if (root == null) {
                return fallbackIfMissing;
            }

            if (!root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return fallbackIfMissing;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains("TamedRaven", Tag.TAG_COMPOUND)) {
                return fallbackIfMissing;
            }

            CompoundTag tamed = modTag.getCompound("TamedRaven");
            if (tamed == null || tamed.isEmpty()) {
                return fallbackIfMissing;
            }

            if (tamed.hasUUID("BoundRavenId")) {
                return tamed.getUUID("BoundRavenId");
            }

            if (fallbackIfMissing != null) {
                tamed.putUUID("BoundRavenId", fallbackIfMissing);
                modTag.put("TamedRaven", tamed);
                root.put(Constants.MOD_ID, modTag);
                LOG.debug("[TamedRavenPlayerData] ensureBoundRavenId: added boundId={} for player={}",
                        fallbackIfMissing, player.getGameProfile().getName());
            }

            return fallbackIfMissing;
        } catch (Throwable t) {
            LOG.warn("[TamedRavenPlayerData] ensureBoundRavenId failed for player={}: {}",
                    (player == null ? "null" : player.getGameProfile().getName()), t.toString());
            return fallbackIfMissing;
        }
    }

    /**
     * Clears the player's TamedRaven compound in persistent NeoForge data.
     *
     * This is the same logic that used to live in FeatheredFriendCommands.
     *
     * @return true if HasTamedRaven == true before we cleared it.
     */
    public static boolean clearPlayerTamedRavenData(ServerPlayer player) {
        try {
            if (player == null) {
                return false;
            }

            CompoundTag root = player.getPersistentData();
            if (root == null) {
                LOG.debug("[TamedRavenPlayerData] clearPlayerTamedRavenData: no persistent data for player={}",
                        player.getGameProfile().getName());
                return false;
            }

            if (!root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                LOG.debug("[TamedRavenPlayerData] clearPlayerTamedRavenData: no '{}' tag for player={}",
                        Constants.MOD_ID, player.getGameProfile().getName());
                return false;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains("TamedRaven", Tag.TAG_COMPOUND)) {
                LOG.debug("[TamedRavenPlayerData] clearPlayerTamedRavenData: no TamedRaven compound for player={}",
                        player.getGameProfile().getName());
                return false;
            }

            CompoundTag tamed = modTag.getCompound("TamedRaven");
            if (tamed == null || tamed.isEmpty()) {
                LOG.debug("[TamedRavenPlayerData] clearPlayerTamedRavenData: empty TamedRaven compound for player={}",
                        player.getGameProfile().getName());
                return false;
            }

            boolean had = tamed.getBoolean("HasTamedRaven");

            LOG.debug("[TamedRavenPlayerData] clearPlayerTamedRavenData: BEFORE clear player={} tag={}",
                    player.getGameProfile().getName(), tamed);

            // Hard-reset the fields we know about.
            tamed.putBoolean("HasTamedRaven", false);
            tamed.remove("RavenName");
            tamed.remove("OwnerUUID");
            tamed.remove("OwnerDimension");
            tamed.remove("BoundRavenId");

            // If the compound is now empty, remove it entirely.
            if (tamed.isEmpty()) {
                modTag.remove("TamedRaven");
            } else {
                modTag.put("TamedRaven", tamed);
            }

            // If the mod compound is now empty, remove it entirely.
            if (modTag.isEmpty()) {
                root.remove(Constants.MOD_ID);
            } else {
                root.put(Constants.MOD_ID, modTag);
            }

            LOG.debug("[TamedRavenPlayerData] clearPlayerTamedRavenData: AFTER clear player={} had={} nowTag={}",
                    player.getGameProfile().getName(), had, tamed);

            return had;
        } catch (Throwable t) {
            LOG.error("[TamedRavenPlayerData] clearPlayerTamedRavenData failed for player={}",
                    (player == null ? "null" : player.getGameProfile().getName()), t);
            return false;
        }
    }
}
