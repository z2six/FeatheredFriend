// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenPlayerData.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

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
     */
    public record TamedRavenInfo(boolean hasTamedRaven, String ravenName) {

        public static TamedRavenInfo empty() {
            return new TamedRavenInfo(false, "");
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

            return new TamedRavenInfo(has, name);
        } catch (Throwable t) {
            LOG.error("[TamedRavenPlayerData] getTamedRavenInfo failed for player={}",
                    (player == null ? "null" : player.getGameProfile().getName()), t);
            return TamedRavenInfo.empty();
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
