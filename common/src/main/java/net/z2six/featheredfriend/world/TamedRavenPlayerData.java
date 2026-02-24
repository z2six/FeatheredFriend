// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenPlayerData.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenArmorVisual;
import net.z2six.featheredfriend.item.RavenArmorStats;
import net.z2six.featheredfriend.platform.Services;
import net.z2six.featheredfriend.registry.FFItems;
import org.jetbrains.annotations.NotNull;
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
    private static final String KEY_TAMED_RAVEN = "TamedRaven";
    private static final String KEY_HAS_TAMED_RAVEN = "HasTamedRaven";
    private static final String KEY_HAS_EVER_TAMED_RAVEN = "HasEverTamedRaven";
    private static final String KEY_RAVEN_NAME = "RavenName";
    private static final String KEY_BOUND_RAVEN_ID = "BoundRavenId";
    private static final String KEY_ARMOR_VISUAL = "EquippedArmorVisual";
    private static final String KEY_CURRENT_HEALTH = "CurrentHealth";
    private static final String KEY_LAST_HEALTH_UPDATE_GAME_TIME = "LastHealthUpdateGameTime";

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
    public record TamedRavenInfo(boolean hasTamedRaven,
                                 String ravenName,
                                 UUID boundRavenId,
                                 RavenArmorVisual armorVisual,
                                 float currentHealth,
                                 long lastHealthUpdateGameTime) {

        public static TamedRavenInfo empty() {
            return new TamedRavenInfo(false, "", null, RavenArmorVisual.NONE, 1.0F, 0L);
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

            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null) {
                return TamedRavenInfo.empty();
            }

            if (!root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return TamedRavenInfo.empty();
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                return TamedRavenInfo.empty();
            }

            CompoundTag tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            if (tamed == null || tamed.isEmpty()) {
                return TamedRavenInfo.empty();
            }

            boolean has = tamed.getBoolean(KEY_HAS_TAMED_RAVEN);
            String name = "";
            if (tamed.contains(KEY_RAVEN_NAME, Tag.TAG_STRING)) {
                name = tamed.getString(KEY_RAVEN_NAME);
            }

            UUID boundId = null;
            try {
                if (tamed.hasUUID(KEY_BOUND_RAVEN_ID)) {
                    boundId = tamed.getUUID(KEY_BOUND_RAVEN_ID);
                }
            } catch (Throwable ignored) {
            }

            RavenArmorVisual armorVisual = RavenArmorVisual.NONE;
            try {
                if (tamed.contains(KEY_ARMOR_VISUAL, Tag.TAG_INT)) {
                    armorVisual = RavenArmorVisual.fromId(tamed.getInt(KEY_ARMOR_VISUAL));
                }
            } catch (Throwable ignored) {
            }

            int maxHits = getMaxHitsForArmor(armorVisual);
            float currentHealth = (float) maxHits;
            try {
                if (tamed.contains(KEY_CURRENT_HEALTH, Tag.TAG_FLOAT)) {
                    currentHealth = tamed.getFloat(KEY_CURRENT_HEALTH);
                }
            } catch (Throwable ignored) {
            }

            long lastHealthUpdateGameTime = 0L;
            try {
                if (tamed.contains(KEY_LAST_HEALTH_UPDATE_GAME_TIME, Tag.TAG_LONG)) {
                    lastHealthUpdateGameTime = tamed.getLong(KEY_LAST_HEALTH_UPDATE_GAME_TIME);
                }
            } catch (Throwable ignored) {
            }

            currentHealth = clampHealthForArmor(currentHealth, armorVisual);
            return new TamedRavenInfo(has, name, boundId, armorVisual, currentHealth, lastHealthUpdateGameTime);
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

            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null) {
                LOG.warn("[TamedRavenPlayerData] storeTamedRavenInfo: player persistent data is null");
                return;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            RavenArmorVisual existingArmorVisual = RavenArmorVisual.NONE;
            float existingHealth = 1.0F;
            long existingHealthUpdateTime = 0L;
            try {
                if (modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                    CompoundTag existing = modTag.getCompound(KEY_TAMED_RAVEN);
                    boolean hadActiveRaven = existing.getBoolean(KEY_HAS_TAMED_RAVEN);
                    if (hadActiveRaven && existing.contains(KEY_ARMOR_VISUAL, Tag.TAG_INT)) {
                        existingArmorVisual = RavenArmorVisual.fromId(existing.getInt(KEY_ARMOR_VISUAL));
                    }
                    existingHealth = clampHealthForArmor(
                            (hadActiveRaven && existing.contains(KEY_CURRENT_HEALTH, Tag.TAG_FLOAT))
                                    ? existing.getFloat(KEY_CURRENT_HEALTH)
                                    : (float) getMaxHitsForArmor(existingArmorVisual),
                            existingArmorVisual
                    );
                    if (hadActiveRaven && existing.contains(KEY_LAST_HEALTH_UPDATE_GAME_TIME, Tag.TAG_LONG)) {
                        existingHealthUpdateTime = Math.max(0L, existing.getLong(KEY_LAST_HEALTH_UPDATE_GAME_TIME));
                    }
                }
            } catch (Throwable ignored) {
            }
            CompoundTag tamed = new CompoundTag();

            tamed.putBoolean(KEY_HAS_TAMED_RAVEN, true);
            tamed.putBoolean(KEY_HAS_EVER_TAMED_RAVEN, true);
            tamed.putString(
                    KEY_RAVEN_NAME,
                    ravenName != null ? ravenName : Component.translatable("entity.featheredfriend.raven").getString()
            );
            tamed.putUUID("OwnerUUID", player.getUUID());
            tamed.putInt(KEY_ARMOR_VISUAL, existingArmorVisual.id());
            tamed.putFloat(KEY_CURRENT_HEALTH, existingHealth);
            tamed.putLong(KEY_LAST_HEALTH_UPDATE_GAME_TIME, existingHealthUpdateTime);

            try {
                if (player.level() != null) {
                    tamed.putString("OwnerDimension", player.level().dimension().location().toString());
                }
            } catch (Throwable ignored) {
            }

            if (boundRavenId != null) {
                tamed.putUUID(KEY_BOUND_RAVEN_ID, boundRavenId);
            }

            modTag.put(KEY_TAMED_RAVEN, tamed);
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

    public static boolean hasEverTamedRaven(ServerPlayer player) {
        try {
            if (player == null) {
                return false;
            }

            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null || !root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return false;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                return false;
            }

            CompoundTag tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            if (tamed == null || tamed.isEmpty()) {
                return false;
            }

            if (tamed.contains(KEY_HAS_EVER_TAMED_RAVEN, Tag.TAG_BYTE)) {
                return tamed.getBoolean(KEY_HAS_EVER_TAMED_RAVEN);
            }

            // Backward compatibility for worlds that predate the "ever tamed" key.
            return tamed.getBoolean(KEY_HAS_TAMED_RAVEN);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenPlayerData] hasEverTamedRaven failed for player={}: {}",
                    (player == null ? "null" : player.getGameProfile().getName()),
                    t.toString());
            return false;
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

            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null) {
                return fallbackIfMissing;
            }

            if (!root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return fallbackIfMissing;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                return fallbackIfMissing;
            }

            CompoundTag tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            if (tamed == null || tamed.isEmpty()) {
                return fallbackIfMissing;
            }

            if (tamed.hasUUID(KEY_BOUND_RAVEN_ID)) {
                return tamed.getUUID(KEY_BOUND_RAVEN_ID);
            }

            if (fallbackIfMissing != null) {
                tamed.putUUID(KEY_BOUND_RAVEN_ID, fallbackIfMissing);
                modTag.put(KEY_TAMED_RAVEN, tamed);
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

    public static @NotNull RavenArmorVisual getEquippedArmorVisual(ServerPlayer player) {
        try {
            TamedRavenInfo info = getTamedRavenInfo(player);
            if (info == null) {
                return RavenArmorVisual.NONE;
            }
            return info.armorVisual() == null ? RavenArmorVisual.NONE : info.armorVisual();
        } catch (Throwable ignored) {
            return RavenArmorVisual.NONE;
        }
    }

    public static void setEquippedArmorVisual(ServerPlayer player, @NotNull RavenArmorVisual armorVisual) {
        try {
            if (player == null) {
                return;
            }

            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null || !root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                return;
            }

            CompoundTag tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            if (tamed == null || tamed.isEmpty()) {
                return;
            }

            RavenArmorVisual safe = (armorVisual == null) ? RavenArmorVisual.NONE : armorVisual;
            tamed.putInt(KEY_ARMOR_VISUAL, safe.id());
            float currentHealth = tamed.contains(KEY_CURRENT_HEALTH, Tag.TAG_FLOAT)
                    ? tamed.getFloat(KEY_CURRENT_HEALTH)
                    : (float) getMaxHitsForArmor(safe);
            tamed.putFloat(KEY_CURRENT_HEALTH, clampHealthForArmor(currentHealth, safe));
            if (!tamed.contains(KEY_LAST_HEALTH_UPDATE_GAME_TIME, Tag.TAG_LONG)) {
                tamed.putLong(KEY_LAST_HEALTH_UPDATE_GAME_TIME, getCurrentServerGameTime(player));
            }

            modTag.put(KEY_TAMED_RAVEN, tamed);
            root.put(Constants.MOD_ID, modTag);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenPlayerData] setEquippedArmorVisual failed for player={}: {}",
                    (player == null ? "null" : player.getGameProfile().getName()),
                    t.toString());
        }
    }

    public static float applyDespawnedHealthRegenAndGet(ServerPlayer player, long nowGameTime) {
        try {
            if (player == null) {
                return 1.0F;
            }

            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null || !root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return 1.0F;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                return 1.0F;
            }

            CompoundTag tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            if (tamed == null || tamed.isEmpty() || !tamed.getBoolean(KEY_HAS_TAMED_RAVEN)) {
                return 1.0F;
            }

            RavenArmorVisual armorVisual = RavenArmorVisual.NONE;
            if (tamed.contains(KEY_ARMOR_VISUAL, Tag.TAG_INT)) {
                armorVisual = RavenArmorVisual.fromId(tamed.getInt(KEY_ARMOR_VISUAL));
            }

            int maxHits = getMaxHitsForArmor(armorVisual);
            float currentHealth = tamed.contains(KEY_CURRENT_HEALTH, Tag.TAG_FLOAT)
                    ? tamed.getFloat(KEY_CURRENT_HEALTH)
                    : (float) maxHits;
            currentHealth = clampHealthForArmor(currentHealth, armorVisual);

            long lastUpdate = tamed.contains(KEY_LAST_HEALTH_UPDATE_GAME_TIME, Tag.TAG_LONG)
                    ? Math.max(0L, tamed.getLong(KEY_LAST_HEALTH_UPDATE_GAME_TIME))
                    : nowGameTime;
            long safeNow = Math.max(0L, nowGameTime);
            long elapsed = Math.max(0L, safeNow - lastUpdate);
            if (elapsed > 0L) {
                currentHealth = applyRegenTicks(currentHealth, armorVisual, elapsed);
            }

            tamed.putFloat(KEY_CURRENT_HEALTH, currentHealth);
            tamed.putLong(KEY_LAST_HEALTH_UPDATE_GAME_TIME, safeNow);
            modTag.put(KEY_TAMED_RAVEN, tamed);
            root.put(Constants.MOD_ID, modTag);
            return currentHealth;
        } catch (Throwable t) {
            LOG.warn("[TamedRavenPlayerData] applyDespawnedHealthRegenAndGet failed for player={}: {}",
                    (player == null ? "null" : player.getGameProfile().getName()),
                    t.toString());
            return 1.0F;
        }
    }

    public static void setStoredRavenHealth(ServerPlayer player, float health, long nowGameTime) {
        try {
            if (player == null) {
                return;
            }

            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null || !root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                return;
            }

            CompoundTag tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            if (tamed == null || tamed.isEmpty() || !tamed.getBoolean(KEY_HAS_TAMED_RAVEN)) {
                return;
            }

            RavenArmorVisual armorVisual = RavenArmorVisual.NONE;
            if (tamed.contains(KEY_ARMOR_VISUAL, Tag.TAG_INT)) {
                armorVisual = RavenArmorVisual.fromId(tamed.getInt(KEY_ARMOR_VISUAL));
            }

            tamed.putFloat(KEY_CURRENT_HEALTH, clampHealthForArmor(health, armorVisual));
            tamed.putLong(KEY_LAST_HEALTH_UPDATE_GAME_TIME, Math.max(0L, nowGameTime));
            modTag.put(KEY_TAMED_RAVEN, tamed);
            root.put(Constants.MOD_ID, modTag);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenPlayerData] setStoredRavenHealth failed for player={}: {}",
                    (player == null ? "null" : player.getGameProfile().getName()),
                    t.toString());
        }
    }

    public static boolean markStoredRavenDead(ServerPlayer player, long nowGameTime) {
        try {
            if (player == null) {
                return false;
            }

            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null || !root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return false;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                return false;
            }

            CompoundTag tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            if (tamed == null || tamed.isEmpty()) {
                return false;
            }

            tamed.putBoolean(KEY_HAS_EVER_TAMED_RAVEN, true);
            tamed.putBoolean(KEY_HAS_TAMED_RAVEN, false);
            tamed.putInt(KEY_ARMOR_VISUAL, RavenArmorVisual.NONE.id());
            tamed.putFloat(KEY_CURRENT_HEALTH, 0.0F);
            tamed.putLong(KEY_LAST_HEALTH_UPDATE_GAME_TIME, Math.max(0L, nowGameTime));

            modTag.put(KEY_TAMED_RAVEN, tamed);
            root.put(Constants.MOD_ID, modTag);
            return true;
        } catch (Throwable t) {
            LOG.warn("[TamedRavenPlayerData] markStoredRavenDead failed for player={}: {}",
                    (player == null ? "null" : player.getGameProfile().getName()),
                    t.toString());
            return false;
        }
    }

    public static long getCurrentServerGameTime(ServerPlayer player) {
        try {
            if (player == null) {
                return 0L;
            }
            if (player.serverLevel() != null) {
                return Math.max(0L, player.serverLevel().getGameTime());
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static float applyRegenTicks(float currentHealth, @NotNull RavenArmorVisual armorVisual, long elapsedTicks) {
        try {
            int regenPerMinute = getHealthRegenPerMinuteForArmor(armorVisual);
            if (regenPerMinute <= 0 || elapsedTicks <= 0L) {
                return clampHealthForArmor(currentHealth, armorVisual);
            }
            float healed = currentHealth + (elapsedTicks * (regenPerMinute / 1200.0F));
            return clampHealthForArmor(healed, armorVisual);
        } catch (Throwable ignored) {
            return clampHealthForArmor(currentHealth, armorVisual);
        }
    }

    private static float clampHealthForArmor(float health, @NotNull RavenArmorVisual armorVisual) {
        int maxHits = getMaxHitsForArmor(armorVisual);
        if (health < 0.0F) {
            return 0.0F;
        }
        return Math.min((float) maxHits, health);
    }

    private static int getMaxHitsForArmor(@NotNull RavenArmorVisual armorVisual) {
        try {
            if (!Services.PLATFORM.isRavenArmorEnabled()) {
                armorVisual = RavenArmorVisual.NONE;
            }
            RavenArmorStats stats = FFItems.getRavenArmorStats(armorVisual);
            if (stats != null) {
                return Math.max(1, stats.maxHits());
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private static int getHealthRegenPerMinuteForArmor(@NotNull RavenArmorVisual armorVisual) {
        try {
            if (!Services.PLATFORM.isRavenArmorEnabled()) {
                armorVisual = RavenArmorVisual.NONE;
            }
            RavenArmorStats stats = FFItems.getRavenArmorStats(armorVisual);
            if (stats != null) {
                return Math.max(0, stats.healthRegenPerMinute());
            }
        } catch (Throwable ignored) {
        }
        return 0;
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

            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
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
            if (modTag == null || !modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                LOG.debug("[TamedRavenPlayerData] clearPlayerTamedRavenData: no TamedRaven compound for player={}",
                        player.getGameProfile().getName());
                return false;
            }

            CompoundTag tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            if (tamed == null || tamed.isEmpty()) {
                LOG.debug("[TamedRavenPlayerData] clearPlayerTamedRavenData: empty TamedRaven compound for player={}",
                        player.getGameProfile().getName());
                return false;
            }

            boolean had = tamed.getBoolean(KEY_HAS_TAMED_RAVEN);

            LOG.debug("[TamedRavenPlayerData] clearPlayerTamedRavenData: BEFORE clear player={} tag={}",
                    player.getGameProfile().getName(), tamed);

            // Hard-reset the fields we know about.
            tamed.putBoolean(KEY_HAS_TAMED_RAVEN, false);
            tamed.remove(KEY_HAS_EVER_TAMED_RAVEN);
            tamed.remove(KEY_RAVEN_NAME);
            tamed.remove("OwnerUUID");
            tamed.remove("OwnerDimension");
            tamed.remove(KEY_BOUND_RAVEN_ID);
            tamed.remove(KEY_ARMOR_VISUAL);
            tamed.remove(KEY_CURRENT_HEALTH);
            tamed.remove(KEY_LAST_HEALTH_UPDATE_GAME_TIME);

            // If the compound is now empty, remove it entirely.
            if (tamed.isEmpty()) {
                modTag.remove(KEY_TAMED_RAVEN);
            } else {
                modTag.put(KEY_TAMED_RAVEN, tamed);
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
