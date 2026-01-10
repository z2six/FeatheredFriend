// MainFile: forge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/TamedRavenDeathHandler.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.command.FeatheredFriendCommands;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.world.TamedRavenPlayerData;
import org.slf4j.Logger;

import java.util.List;

/**
 * forge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/TamedRavenDeathHandler.java
 *
 * Handles logic that should run when a RavenEntity dies and is associated with a
 * tamed raven stored in player persistent data.
 *
 * Responsibilities:
 *  - Identify the owner (player whose TamedRaven NBT matches this raven's name).
 *  - Send a death notification to the owner:
 *        "Your raven, [name], has perished by the hands of [killer]."
 *     or "Your raven, [name], has perished of natural causes."
 *  - Optionally append:
 *        "It was last seen nearby [playername]."
 *    where playername is the closest player at the time of death (if any).
 *  - Clear the owner's TamedRaven persistent data using the exact same logic as the command.
 */
public final class TamedRavenDeathHandler {

    private static final Logger LOG = LogUtils.getLogger();

    /** Radius (in blocks) used to pick the "last seen nearby" player. */
    private static final double LAST_SEEN_PLAYER_RADIUS = 64.0D;

    private TamedRavenDeathHandler() {
        // no-op
    }

    /**
     * Called from RavenEntity#die on the server side.
     */
    public static void onRavenDeath(RavenEntity raven, DamageSource source) {
        try {
            if (raven == null) {
                return;
            }

            if (!(raven.level() instanceof ServerLevel serverLevel)) {
                // Client side or weird dimension; ignore.
                return;
            }

            List<ServerPlayer> players = serverLevel.players();
            if (players.isEmpty()) {
                return;
            }

            // Determine the closest player for "last seen nearby" hint.
            ServerPlayer closestPlayer = null;
            double closestDistSq = Double.MAX_VALUE;
            double maxDistSq = LAST_SEEN_PLAYER_RADIUS * LAST_SEEN_PLAYER_RADIUS;
            Vec3 ravenPos = raven.position();

            for (ServerPlayer p : players) {
                if (p == null || p.isSpectator()) {
                    continue;
                }
                double d2 = p.position().distanceToSqr(ravenPos);
                if (d2 < closestDistSq && d2 <= maxDistSq) {
                    closestDistSq = d2;
                    closestPlayer = p;
                }
            }

            // Determine killer (if any).
            String killerName = null;
            boolean killedByPlayer = false;
            if (source != null) {
                Entity killerEntity = source.getEntity();
                if (killerEntity instanceof ServerPlayer killerPlayer) {
                    killedByPlayer = true;
                    killerName = killerPlayer.getGameProfile().getName();
                } else if (killerEntity != null) {
                    killerName = killerEntity.getName().getString();
                }
            }

            // Use the raven's display name if available.
            String ravenNameFromEntity = null;
            try {
                if (raven.hasCustomName()) {
                    ravenNameFromEntity = raven.getName().getString();
                }
            } catch (Throwable ignored) {
            }

            String entityNameTrim = ravenNameFromEntity == null ? "" : ravenNameFromEntity.trim();

            // Try to find the owner via persistent TamedRaven data.
            for (ServerPlayer candidate : players) {
                if (candidate == null) {
                    continue;
                }

                TamedRavenPlayerData.TamedRavenInfo info =
                        TamedRavenPlayerData.getTamedRavenInfo(candidate);

                if (!info.hasTamedRaven()) {
                    continue;
                }

                String storedName = info.ravenName();
                String storedNameTrim = storedName == null ? "" : storedName.trim();

                // ---------------------------------------------------------------------
                // MATCHING LOGIC
                //
                // Stored base name is something like "Crux".
                // Entity display name is something like "Dev's Crux".
                //
                // We treat it as a match if:
                //   - storedName == entityName
                //   - OR entityName ends with storedName (e.g., "Dev's Crux".endsWith("Crux"))
                // ---------------------------------------------------------------------
                if (!storedNameTrim.isEmpty() && !entityNameTrim.isEmpty()) {
                    boolean directMatch = storedNameTrim.equals(entityNameTrim);
                    boolean suffixMatch = entityNameTrim.endsWith(storedNameTrim);

                    if (!directMatch && !suffixMatch) {
                        if (raven.tickCount % 80 == 0) {
                            LOG.debug("[TamedRavenDeathHandler] Skipping candidate owner={} storedName='{}' entityName='{}' (no match)",
                                    candidate.getGameProfile().getName(), storedNameTrim, entityNameTrim);
                        }
                        continue;
                    }
                } else {
                    // If we have no reliable name match, be conservative and skip this candidate.
                    if (raven.tickCount % 80 == 0) {
                        LOG.debug("[TamedRavenDeathHandler] Skipping candidate owner={} due to empty names. stored='{}' entity='{}'",
                                candidate.getGameProfile().getName(), storedNameTrim, entityNameTrim);
                    }
                    continue;
                }

                // At this point we treat 'candidate' as the owner.
                if (raven.tickCount % 40 == 0) {
                    LOG.info("[TamedRavenDeathHandler] Matched owner={} for raven id={} storedName='{}' entityName='{}'",
                            candidate.getGameProfile().getName(), raven.getId(), storedNameTrim, entityNameTrim);
                }

                String displayName = !entityNameTrim.isEmpty()
                        ? entityNameTrim
                        : (!storedNameTrim.isEmpty() ? storedNameTrim : "your raven");

                String baseMessage;
                if (killedByPlayer && killerName != null && !killerName.isBlank()) {
                    baseMessage = "[FeatheredFriend] Your raven, " + displayName
                            + ", has perished by the hands of " + killerName + ".";
                } else {
                    baseMessage = "[FeatheredFriend] Your raven, " + displayName
                            + ", has perished of natural causes.";
                }

                try {
                    candidate.sendSystemMessage(Component.literal(baseMessage));
                } catch (Throwable tSend) {
                    LOG.warn("[TamedRavenDeathHandler] Failed to send base death message to owner={}: {}",
                            candidate.getGameProfile().getName(), tSend.toString());
                }

                // Optional "last seen nearby <player>" message.
                if (closestPlayer != null
                        && !closestPlayer.getUUID().equals(candidate.getUUID())) {
                    String lastSeenMsg = "[FeatheredFriend] It was last seen nearby "
                            + closestPlayer.getGameProfile().getName() + ".";
                    try {
                        candidate.sendSystemMessage(Component.literal(lastSeenMsg));
                    } catch (Throwable tSend2) {
                        LOG.warn("[TamedRavenDeathHandler] Failed to send last-seen message to owner={}: {}",
                                candidate.getGameProfile().getName(), tSend2.toString());
                    }
                }

                // Clear the TamedRaven data for this owner using the SAME logic as the command.
                boolean cleared = FeatheredFriendCommands.clearPlayerTamedRavenData(candidate);
                if (cleared) {
                    LOG.info("[TamedRavenDeathHandler] Cleared TamedRaven data for owner={} due to raven death. ravenName={}",
                            candidate.getGameProfile().getName(), displayName);
                } else {
                    LOG.info("[TamedRavenDeathHandler] Owner={} had no TamedRaven data to clear on raven death. ravenName={}",
                            candidate.getGameProfile().getName(), displayName);
                }

                // Only one owner expected; break after first match.
                return;
            }

            // If we get here, no matching owner was found.
            if (raven.tickCount % 80 == 0) {
                LOG.debug("[TamedRavenDeathHandler] No matching TamedRaven owner found for raven id={} entityName='{}'",
                        raven.getId(), entityNameTrim);
            }

        } catch (Throwable t) {
            LOG.error("[TamedRavenDeathHandler] onRavenDeath failed safely", t);
        }
    }
}
