// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/TamedRavenDeathHandler.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.world.TamedRavenPlayerData;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/TamedRavenDeathHandler.java
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
    private static final String NBT_BOUND_RAVEN_ID = "BoundRavenId";

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

            // Only clear stored tamed raven data when a *real* tamed raven dies.
            // (Do NOT clear when courier ravens die; they are not the owner's "bound raven".)
            try {
                if (!raven.isTame()) {
                    return;
                }
            } catch (Throwable ignored) {
                return;
            }

            try {
                if (raven.getTags().contains("ff_courier_raven")) {
                    return;
                }
            } catch (Throwable ignored) {
            }

            UUID ownerUuid;
            try {
                ownerUuid = raven.getOwnerUUID();
            } catch (Throwable ignored) {
                return;
            }
            if (ownerUuid == null) {
                return;
            }

            ServerPlayer owner;
            try {
                owner = serverLevel.getServer().getPlayerList().getPlayer(ownerUuid);
            } catch (Throwable ignored) {
                owner = null;
            }
            if (owner == null) {
                if (raven.tickCount % 80 == 0) {
                    LOG.debug("[TamedRavenDeathHandler] Owner is offline/unavailable; cannot clear TamedRaven data for uuid={}",
                            ownerUuid);
                }
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

            TamedRavenPlayerData.TamedRavenInfo info = TamedRavenPlayerData.getTamedRavenInfo(owner);
            boolean hasStored = info != null && info.hasTamedRaven();
            String storedNameTrim = (info == null || info.ravenName() == null) ? "" : info.ravenName().trim();
            UUID storedBoundId = (info != null) ? info.boundRavenId() : null;

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
                    owner.sendSystemMessage(Component.literal(baseMessage));
                } catch (Throwable tSend) {
                    LOG.warn("[TamedRavenDeathHandler] Failed to send base death message to owner={}: {}",
                            owner.getGameProfile().getName(), tSend.toString());
                }

                // Optional "last seen nearby <player>" message.
                if (closestPlayer != null
                        && !closestPlayer.getUUID().equals(owner.getUUID())) {
                    String lastSeenMsg = "[FeatheredFriend] It was last seen nearby "
                            + closestPlayer.getGameProfile().getName() + ".";
                    try {
                        owner.sendSystemMessage(Component.literal(lastSeenMsg));
                    } catch (Throwable tSend2) {
                        LOG.warn("[TamedRavenDeathHandler] Failed to send last-seen message to owner={}: {}",
                                owner.getGameProfile().getName(), tSend2.toString());
                    }
                }

                // Only clear if this dead raven matches the stored bound record.
                boolean matchesStored = false;
                UUID entityBoundId = null;
                try {
                    CompoundTag root = raven.getPersistentData();
                    CompoundTag ffTag = (root == null) ? null : root.getCompound(Constants.MOD_ID);
                    if (ffTag != null && ffTag.hasUUID(NBT_BOUND_RAVEN_ID)) {
                        entityBoundId = ffTag.getUUID(NBT_BOUND_RAVEN_ID);
                    }
                } catch (Throwable ignored) {
                }

                boolean hasEntityName = raven.hasCustomName();
                boolean nameMatches = hasEntityName && !storedNameTrim.isEmpty()
                        && entityNameTrim.equals(storedNameTrim);

                if (storedBoundId != null && entityBoundId != null) {
                    matchesStored = storedBoundId.equals(entityBoundId);
                } else if (storedBoundId == null && entityBoundId != null) {
                    matchesStored = nameMatches;
                } else if (storedBoundId != null && entityBoundId == null) {
                    matchesStored = nameMatches;
                } else {
                    matchesStored = nameMatches;
                }

                if (!hasStored) {
                    matchesStored = false;
                }

                if (matchesStored) {
                    boolean cleared = TamedRavenPlayerData.clearPlayerTamedRavenData(owner);
                    if (cleared) {
                        LOG.debug("[TamedRavenDeathHandler] Cleared TamedRaven data for owner={} due to raven death. ravenName={}",
                                owner.getGameProfile().getName(), displayName);
                    } else {
                        LOG.debug("[TamedRavenDeathHandler] Owner={} had no TamedRaven data to clear on raven death. ravenName={}",
                                owner.getGameProfile().getName(), displayName);
                    }
                } else if (raven.tickCount % 80 == 0) {
                    LOG.debug("[TamedRavenDeathHandler] Skipped clear; dead raven did not match stored bound record. owner={} storedName='{}' entityName='{}' storedBoundId={} entityBoundId={}",
                            owner.getGameProfile().getName(),
                            storedNameTrim,
                            entityNameTrim,
                            storedBoundId,
                            entityBoundId);
                }

                return;

        } catch (Throwable t) {
            LOG.error("[TamedRavenDeathHandler] onRavenDeath failed safely", t);
        }
    }
}
