// neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenScrollWatcher.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenScrollWatcher.java
 *
 * Step 1: Detect when a player is holding a sealed scroll and log:
 *  - Player is holding a sealed scroll.
 *  - Whether the player has a tamed raven and, if so, its name.
 *
 * Implementation details:
 *  - Listens to PlayerTickEvent.Post on the global NeoForge.EVENT_BUS.
 *  - Only runs on the logical server (ignores client-side ticks).
 *  - Checks the player's *main hand* item for the sealed scroll.
 *  - Reads the "TamedRaven" compound from the player's persistent data:
 *      root.getCompound(Constants.MOD_ID).getCompound("TamedRaven")
 *    as written by TamedRaven.storeTamedRavenForPlayer().
 *  - Uses a simple per-player map to only log when the player *starts*
 *    holding a sealed scroll, to avoid spam.
 */
public final class TamedRavenScrollWatcher {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Registry name of the sealed scroll item.
     * This matches the id used in FFItems / FFNeoForgeItems:
     *   featheredfriend:scroll_sealed
     */
    private static final ResourceLocation SEALED_SCROLL_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "scroll_sealed");

    /**
     * Tracks whether a given player was holding a sealed scroll last tick.
     * Keyed by the player's UUID to avoid entity id reuse issues.
     */
    private static final Map<UUID, Boolean> LAST_HOLDING_SEALED_SCROLL = new ConcurrentHashMap<>();

    private TamedRavenScrollWatcher() {
        // no-op
    }

    /**
     * Called from FeatheredFriend (mod entrypoint) during initialization.
     * Registers our handler on the global NeoForge.EVENT_BUS.
     */
    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(TamedRavenScrollWatcher::onPlayerTick);
            LOG.info("[TamedRavenScrollWatcher] Registered PlayerTickEvent.Post listener");
        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] Failed to register PlayerTickEvent listener", t);
        }
    }

    /**
     * Fired once per tick, per player, on both logical sides.
     * We immediately exit on the client side; only the server does the work.
     */
    private static void onPlayerTick(@NotNull PlayerTickEvent.Post event) {
        try {
            Player player = event.getEntity();
            if (player == null) {
                return;
            }

            if (player.level() == null || player.level().isClientSide()) {
                // Only care about logical server.
                return;
            }

            UUID uuid = player.getUUID();
            boolean holdingNow = isHoldingSealedScroll(player);

            Boolean previous = LAST_HOLDING_SEALED_SCROLL.put(uuid, holdingNow);
            boolean wasHolding = previous != null && previous;

            // Only log when the player *starts* holding a sealed scroll
            // (transition: not holding -> holding).
            if (!wasHolding && holdingNow) {
                logPlayerHoldingSealedScroll(player);
                logPlayerTamedRavenInfo(player);
            }

            // Optional cleanup: if the player is removed, drop their entry.
            if (!player.isAlive() || player.isRemoved()) {
                LAST_HOLDING_SEALED_SCROLL.remove(uuid);
            }

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] onPlayerTick failed safely", t);
        }
    }

    /**
     * Returns true if the player's *main hand* item is the sealed scroll.
     */
    private static boolean isHoldingSealedScroll(@NotNull Player player) {
        try {
            ItemStack main = player.getMainHandItem();
            if (main == null || main.isEmpty()) {
                return false;
            }

            ResourceLocation key = BuiltInRegistries.ITEM.getKey(main.getItem());
            if (key == null) {
                return false;
            }

            return SEALED_SCROLL_ID.equals(key);
        } catch (Throwable t) {
            // This should never happen, but we guard against it anyway.
            LOG.warn("[TamedRavenScrollWatcher] isHoldingSealedScroll failed for player={}",
                    safePlayerName(player), t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Logging helpers
    // ---------------------------------------------------------------------

    private static void logPlayerHoldingSealedScroll(@NotNull Player player) {
        try {
            ItemStack main = player.getMainHandItem();
            String itemDesc;
            try {
                itemDesc = String.valueOf(main.getItem());
            } catch (Throwable ignored) {
                itemDesc = "<unknown item>";
            }

            LOG.info("[TamedRavenScrollWatcher] Player '{}' is now holding a sealed scroll ({})",
                    safePlayerName(player), itemDesc);
        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] logPlayerHoldingSealedScroll failed", t);
        }
    }

    /**
     * Logs whether the player has a tamed raven and, if so, its name.
     * Reads the same data structure written by TamedRaven.storeTamedRavenForPlayer():
     *
     *   root = player.getPersistentData()
     *   ffTag = root.getCompound(Constants.MOD_ID)
     *   ravenTag = ffTag.getCompound("TamedRaven")
     *     - HasTamedRaven : boolean
     *     - RavenName     : string
     */
    private static void logPlayerTamedRavenInfo(@NotNull Player player) {
        try {
            CompoundTag root = player.getPersistentData();
            if (root == null) {
                LOG.info("[TamedRavenScrollWatcher] Player '{}' has NO persistent data; assuming no tamed raven",
                        safePlayerName(player));
                return;
            }

            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                LOG.info("[TamedRavenScrollWatcher] Player '{}' has no '{}' compound; no tamed raven recorded",
                        safePlayerName(player), Constants.MOD_ID);
                return;
            }

            if (!ffTag.contains("TamedRaven", Tag.TAG_COMPOUND)) {
                LOG.info("[TamedRavenScrollWatcher] Player '{}' does NOT have stored TamedRaven data",
                        safePlayerName(player));
                return;
            }

            CompoundTag ravenTag = ffTag.getCompound("TamedRaven");
            if (ravenTag == null || ravenTag.isEmpty()) {
                LOG.info("[TamedRavenScrollWatcher] Player '{}' has an empty TamedRaven tag",
                        safePlayerName(player));
                return;
            }

            boolean hasTamedRaven = ravenTag.getBoolean("HasTamedRaven");
            String ravenName = ravenTag.getString("RavenName");

            if (!hasTamedRaven) {
                LOG.info("[TamedRavenScrollWatcher] Player '{}' TamedRaven.HasTamedRaven = false",
                        safePlayerName(player));
                return;
            }

            if (ravenName == null || ravenName.isEmpty()) {
                ravenName = "Raven";
            }

            LOG.info("[TamedRavenScrollWatcher] Player '{}' HAS a tamed raven named '{}'",
                    safePlayerName(player), ravenName);

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] logPlayerTamedRavenInfo failed", t);
        }
    }

    private static String safePlayerName(@NotNull Player player) {
        try {
            return player.getGameProfile().getName();
        } catch (Throwable ignored) {
            try {
                return player.getName().getString();
            } catch (Throwable ignored2) {
                return "<unknown>";
            }
        }
    }
}
