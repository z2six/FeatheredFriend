// neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenScrollWatcher.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.modules.RavenSoundEngine;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import net.z2six.featheredfriend.entity.raven.modules.Teleportation;
import net.z2six.featheredfriend.world.FeatheredFriendSettingsData;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.Entity;

import net.z2six.featheredfriend.registry.FFNeoForgeEntities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenScrollWatcher.java
 *
 * Behavior:
 *  - When a player holds a sealed scroll in MAIN HAND and has stored TamedRaven data:
 *      * Ensure there is exactly ONE "scroll-summoned" RavenEntity for that player.
 *      * On "start holding" edge (was not holding, now holding):
 *          - Despawn any existing scroll-summoned ravens for that player
 *            with TamedRaven's fade-out + Enderpop + feather FX.
 *          - Spawn a fresh raven with Enderpop-style spawn FX (no feather FX).
 *  - While the player keeps holding the sealed scroll:
 *      * If the raven somehow dies, we respawn a new one (single instance).
 *      * If duplicates exist (from previous bugs), we keep the closest and despawn the rest.
 *  - When the player stops holding the sealed scroll or loses the tamed raven:
 *      * We despawn all scroll-summoned ravens with TamedRaven's fade-out FX.
 *
 * Implementation:
 *  - Detection of "scroll-summoned" ravens is via scoreboard tag + owner UUID:
 *      * Scoreboard tag:  "ff_scroll_summoned"
 *      * Owner:           raven.getOwnerUUID() == playerUUID
 *  - No entity-id-based spawn logic; we derive the state from the world every tick.
 *  - Edge detection of "start holding" is robust across reloads:
 *      * We store LAST_HOLDING_SEALED_SCROLL per player UUID.
 *      * On a fresh player entity (player.tickCount == 0), we force wasHolding=false
 *        so a player always counts as “newly holding” on join if they have the scroll selected.
 *
 * Spawn FX:
 *  - Enderpop-like portal particles + enderman teleport sound.
 *  - NO feather FX on spawn (those are despawn-only via TamedRaven).
 *
 * Despawn FX:
 *  - Uses TamedRaven.beginDespawnWithFx, which is assumed to:
 *      * Fade out the raven.
 *      * Play Enderpop + feather particles.
 *
 * Important implementation detail:
 *  - As soon as we request despawn of a scroll-summoned raven, we remove:
 *      * The scoreboard tag "ff_scroll_summoned".
 *      * The NBT flags ScrollSummoned / ScrollSummonedOwner.
 *    This prevents repeated despawn calls while the fade-out is in progress.
 */
public final class TamedRavenScrollWatcher {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Registry name of the sealed scroll item.
     */
    private static final ResourceLocation SEALED_SCROLL_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "scroll_sealed");

    /**
     * Scoreboard tag used to mark scroll-summoned ravens.
     */
    private static final String TAG_SCROLL_SUMMONED = "ff_scroll_summoned";

    /**
     * Tracks whether each player was holding the sealed scroll on the previous tick.
     * Keyed by player UUID, survives across dimension changes and reconnects (on dedicated).
     */
    private static final Map<UUID, Boolean> LAST_HOLDING_SEALED_SCROLL = new ConcurrentHashMap<>();

    /**
     * Lifetime of a scroll-summoned raven in ticks.
     * 60 seconds * 20 ticks per second = 1200 ticks.
     */
    private static final long SCROLL_SUMMON_LIFETIME_TICKS = 60L * 20L;

    private static final String NBT_RECALL_ACTIVE = "ScrollRecallActive";
    private static final String NBT_RECALL_JOB_ID = "ScrollRecallJobId";
    private static final String NBT_RECALL_RECIPIENT_UUID = "ScrollRecallRecipientUUID";
    private static final String NBT_RECALL_SEALED_SCROLL = "ScrollRecallSealedScroll";

    private TamedRavenScrollWatcher() {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Public registration
    // ---------------------------------------------------------------------

    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(TamedRavenScrollWatcher::onPlayerTick);
            NeoForge.EVENT_BUS.addListener(TamedRavenScrollWatcher::onEntityInteract);
            LOG.debug("[TamedRavenScrollWatcher] Registered PlayerTickEvent.Post + EntityInteract listeners");
        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] Failed to register event listeners", t);
        }
    }

    // ---------------------------------------------------------------------
    // Tick handler
    // ---------------------------------------------------------------------

    private static void onPlayerTick(@NotNull PlayerTickEvent.Post event) {
        try {
            Player player = event.getEntity();
            if (player == null) {
                return;
            }

            Level level = player.level();
            if (!(level instanceof ServerLevel serverLevel) || level.isClientSide()) {
                return;
            }

            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }

            UUID playerId = player.getUUID();

            boolean holdingNow = isHoldingSealedScroll(player);

            boolean wasHoldingPrev = LAST_HOLDING_SEALED_SCROLL.getOrDefault(playerId, false);
            boolean wasHolding = (player.tickCount == 0) ? false : wasHoldingPrev;

            LAST_HOLDING_SEALED_SCROLL.put(playerId, holdingNow);

            TamedRavenInfo info = readTamedRavenInfo(player);
            boolean hasTamedRaven = info != null && info.hasTamedRaven;
            String ravenName = (info != null && info.ravenName != null && !info.ravenName.isEmpty())
                    ? info.ravenName
                    : "Raven";

            List<RavenEntity> scrollRavens = findScrollSummonedRavensForPlayer(serverLevel, serverPlayer);

            // Lifetime expiry (unchanged)
            if (!scrollRavens.isEmpty()) {
                long nowGameTime = serverLevel.getGameTime();
                List<RavenEntity> expired = new ArrayList<>();

                for (RavenEntity r : scrollRavens) {
                    try {
                        CompoundTag root = r.getPersistentData();
                        if (root == null) {
                            continue;
                        }
                        CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
                        if (ffTag == null || ffTag.isEmpty()) {
                            continue;
                        }

                        long despawnAt = ffTag.getLong("ScrollSummonedDespawnAt");
                        if (despawnAt > 0L && nowGameTime >= despawnAt) {
                            expired.add(r);

                            LOG.debug(
                                    "[TamedRavenScrollWatcher] Lifetime expired for scroll raven id={} owner='{}' now={} despawnAt={}",
                                    r.getId(),
                                    safePlayerName(serverPlayer),
                                    nowGameTime,
                                    despawnAt
                            );
                        }
                    } catch (Throwable t) {
                        LOG.warn(
                                "[TamedRavenScrollWatcher] Lifetime check failed safely for raven id={}: {}",
                                r.getId(),
                                t.toString()
                        );
                    }
                }

                if (!expired.isEmpty()) {
                    for (RavenEntity r : expired) {
                        try {
                            despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "scroll lifetime expired (60s)");
                        } catch (Throwable t) {
                            LOG.error(
                                    "[TamedRavenScrollWatcher] Failed safely while despawning expired scroll raven id={}: {}",
                                    r.getId(),
                                    t.toString()
                            );
                        }
                        scrollRavens.remove(r);
                    }
                }
            }

            if (!player.isAlive() || player.isRemoved()) {
                if (!scrollRavens.isEmpty()) {
                    for (RavenEntity r : scrollRavens) {
                        despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "player dead/removed");
                    }
                }
                LAST_HOLDING_SEALED_SCROLL.remove(playerId);
                return;
            }

            if (!hasTamedRaven) {
                if (!scrollRavens.isEmpty()) {
                    for (RavenEntity r : scrollRavens) {
                        despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "no stored tamed raven");
                    }
                }
                return;
            }

            // Courier gating: distinguish ACTIVE vs FAILED
            RavenCourierData courierData = RavenCourierData.get(serverLevel);
            boolean hasActiveNonFailedAsSender = courierData.hasActiveNonFailedJobsAsSender(playerId);
            boolean hasFailedAsSender = courierData.hasFailedJobsAsSender(playerId);

            // OLD RULE still applies only for ACTIVE (non-failed) jobs:
            // If you are holding a sealed scroll while you already have an active job, we disallow scroll-summon (prevents multi-send abuse).
            if (hasActiveNonFailedAsSender && holdingNow) {
                if (!scrollRavens.isEmpty()) {
                    for (RavenEntity r : scrollRavens) {
                        despawnOneScrollSummonedRaven(
                                serverLevel,
                                serverPlayer,
                                r,
                                "courier-dispatch: sender has ACTIVE (non-failed) delivery job"
                        );
                    }
                }
                if (serverPlayer.tickCount % 80 == 0) {
                    LOG.debug(
                            "[TamedRavenScrollWatcher] Player '{}' has ACTIVE courier job(s); disabling scroll-summoned raven while sealed scroll is held.",
                            safePlayerName(player)
                    );
                }
                return;
            }

            // If not holding a scroll:
            // - If sender has FAILED jobs, allow scroll-summoned raven to exist (recall mode), but do not auto-spawn.
            // - Otherwise keep old behavior: despawn and stop.
            if (!holdingNow) {
                if (!hasFailedAsSender) {
                    if (!scrollRavens.isEmpty()) {
                        for (RavenEntity r : scrollRavens) {
                            despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "stopped holding sealed scroll");
                        }
                    }
                } else {
                    // Failed jobs exist -> keep the raven alive if one exists; do nothing otherwise.
                    if (!scrollRavens.isEmpty()) {
                        if (scrollRavens.size() > 1) {
                            RavenEntity primary = pickClosestRaven(scrollRavens, serverPlayer);
                            for (RavenEntity r : scrollRavens) {
                                if (r == primary) continue;
                                despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "deduplicate scroll ravens (failed-job recall)");
                            }
                            ensureRavenName(primary, ravenName);
                        } else {
                            ensureRavenName(scrollRavens.get(0), ravenName);
                        }
                    }
                }
                return;
            }

            // From here on: holdingNow == true (original auto-summon flow continues)
            boolean autoSummonEnabled = true;
            try {
                FeatheredFriendSettingsData settingsData = FeatheredFriendSettingsData.get(serverLevel);
                autoSummonEnabled = settingsData.isAutoSummonOnScrollEnabled();
            } catch (Throwable settingsErr) {
                LOG.warn("[TamedRavenScrollWatcher] Failed to read autoSummonOnScroll setting; defaulting to enabled: {}",
                        settingsErr.toString());
                autoSummonEnabled = true;
            }

            if (!autoSummonEnabled) {
                if (scrollRavens.isEmpty()) {
                    return;
                }

                if (scrollRavens.size() > 1) {
                    RavenEntity primary = pickClosestRaven(scrollRavens, serverPlayer);
                    for (RavenEntity r : scrollRavens) {
                        if (r == primary) {
                            continue;
                        }
                        despawnOneScrollSummonedRaven(
                                serverLevel,
                                serverPlayer,
                                r,
                                "deduplicate scroll ravens (auto-summon disabled)"
                        );
                    }
                    ensureRavenName(primary, ravenName);
                    return;
                }

                ensureRavenName(scrollRavens.get(0), ravenName);
                return;
            }

            if (!wasHolding && holdingNow) {
                if (!scrollRavens.isEmpty()) {
                    for (RavenEntity r : scrollRavens) {
                        despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "start holding scroll (respawn)");
                    }
                }

                RavenEntity spawned = spawnSummonedRaven(serverLevel, serverPlayer, ravenName);
                if (spawned != null) {
                    LOG.debug("[TamedRavenScrollWatcher] Start-hold: spawned scroll-raven id={} for player='{}' at {}",
                            spawned.getId(), safePlayerName(player), spawned.position());
                }
                return;
            }

            if (scrollRavens.isEmpty()) {
                RavenEntity spawned = spawnSummonedRaven(serverLevel, serverPlayer, ravenName);
                if (spawned != null && (serverPlayer.tickCount % 40 == 0)) {
                    LOG.debug("[TamedRavenScrollWatcher] Continuous-hold: respawned scroll-raven id={} for player='{}' at {}",
                            spawned.getId(), safePlayerName(player), spawned.position());
                }
                return;
            }

            if (scrollRavens.size() > 1) {
                RavenEntity primary = pickClosestRaven(scrollRavens, serverPlayer);
                for (RavenEntity r : scrollRavens) {
                    if (r == primary) {
                        continue;
                    }
                    despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "deduplicate scroll ravens");
                }
                ensureRavenName(primary, ravenName);
                return;
            }

            ensureRavenName(scrollRavens.get(0), ravenName);

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] onPlayerTick failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // World scanning helpers
    // ---------------------------------------------------------------------

    /**
     * Return all ravens in a radius around the player that:
     *  - Have scoreboard tag TAG_SCROLL_SUMMONED, AND
     *  - Are tamed by this player (owner UUID matches).
     */
    private static List<RavenEntity> findScrollSummonedRavensForPlayer(@NotNull ServerLevel level,
                                                                       @NotNull ServerPlayer owner) {
        List<RavenEntity> out = new ArrayList<>();
        try {
            UUID ownerId = owner.getUUID();
            BlockPos center = owner.blockPosition();
            double radius = 256.0D;

            AABB box = new AABB(center).inflate(radius);

            List<RavenEntity> candidates = level.getEntitiesOfClass(
                    RavenEntity.class,
                    box,
                    e -> e != null && e.isAlive() && !e.isRemoved()
            );

            for (RavenEntity raven : candidates) {
                boolean tagged;
                try {
                    tagged = raven.getTags().contains(TAG_SCROLL_SUMMONED);
                } catch (Throwable t) {
                    LOG.warn("[TamedRavenScrollWatcher] findScrollSummoned: tag check failed for id={}: {}",
                            raven.getId(), t.toString());
                    continue;
                }

                if (!tagged) {
                    continue;
                }

                UUID ravenOwner;
                try {
                    ravenOwner = raven.getOwnerUUID();
                } catch (Throwable t) {
                    LOG.warn("[TamedRavenScrollWatcher] findScrollSummoned: getOwnerUUID failed for id={}: {}",
                            raven.getId(), t.toString());
                    continue;
                }

                if (ravenOwner == null || !ravenOwner.equals(ownerId)) {
                    continue;
                }

                out.add(raven);
            }

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] findScrollSummonedRavensForPlayer failed safely", t);
        }
        return out;
    }

    @Nullable
    private static RavenEntity pickClosestRaven(@NotNull List<RavenEntity> ravens, @NotNull ServerPlayer owner) {
        if (ravens.isEmpty()) {
            return null;
        }
        try {
            Vec3 pos = owner.position();
            return ravens.stream()
                    .min(Comparator.comparingDouble(r -> r.position().distanceToSqr(pos)))
                    .orElse(ravens.get(0));
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] pickClosestRaven failed safely: {}", t.toString());
            return ravens.get(0);
        }
    }

    // ---------------------------------------------------------------------
    // Spawn / despawn
    // ---------------------------------------------------------------------

    @Nullable
    private static RavenEntity spawnSummonedRaven(@NotNull ServerLevel level,
                                                  @NotNull ServerPlayer owner,
                                                  @NotNull String ravenName) {
        try {
            RavenEntity raven = FFNeoForgeEntities.RAVEN.get().create(level);
            if (raven == null) {
                LOG.error("[TamedRavenScrollWatcher] spawnSummonedRaven: entity factory returned null");
                return null;
            }

            // Find spawn using your 6-step logic + simulated path test
            Vec3 spawnPos = findSafeSpawnAbovePlayer(level, owner, raven);
            if (spawnPos == null) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: no valid spawn (or simulated path failed) for player='{}' -> not spawning.",
                        safePlayerName(owner));
                return null;
            }

            raven.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, owner.getYRot(), 0.0F);

            try {
                raven.setTame(true, true);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: setTame(true, true) failed safely: {}", t.toString());
            }
            try {
                raven.setOwnerUUID(owner.getUUID());
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: setOwnerUUID failed safely: {}", t.toString());
            }

            ensureRavenName(raven, ravenName);

            // If sender has FAILED jobs, arm this raven with recall payload (carry the failed scroll back).
            try {
                RavenCourierData courierData = RavenCourierData.get(level);
                RavenCourierData.DeliveryJob failedJob = courierData.getMostRecentFailedJobForSender(owner.getUUID());

                if (failedJob != null && failedJob.sealedScrollNbt != null && !failedJob.sealedScrollNbt.isEmpty()) {
                    CompoundTag root = raven.getPersistentData();
                    CompoundTag ffTag = root.getCompound(Constants.MOD_ID);

                    ffTag.putBoolean(NBT_RECALL_ACTIVE, true);
                    ffTag.putLong(NBT_RECALL_JOB_ID, failedJob.jobId);
                    ffTag.putString(NBT_RECALL_RECIPIENT_UUID, failedJob.recipientUuid.toString());
                    ffTag.put(NBT_RECALL_SEALED_SCROLL, failedJob.sealedScrollNbt.copy());

                    root.put(Constants.MOD_ID, ffTag);

                    try {
                        raven.setRavenVariant(net.z2six.featheredfriend.entity.raven.RavenVariant.SCROLL);
                    } catch (Throwable t) {
                        LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: setRavenVariant(SCROLL) failed safely: {}", t.toString());
                    }

                    LOG.debug("[TamedRavenScrollWatcher] spawnSummonedRaven: recall armed for player='{}' jobId={} recipient={} (failed).",
                            safePlayerName(owner), failedJob.jobId, failedJob.recipientUuid);
                }
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: failed-job recall arming failed safely for player='{}': {}",
                        safePlayerName(owner), t.toString());
            }

            // Lifetime tag as before
            try {
                CompoundTag root = raven.getPersistentData();
                CompoundTag ffTag = root.getCompound(Constants.MOD_ID);

                long now = level.getGameTime();
                long despawnAt = now + SCROLL_SUMMON_LIFETIME_TICKS;

                ffTag.putBoolean("ScrollSummoned", true);
                ffTag.putString("ScrollSummonedOwner", owner.getUUID().toString());
                ffTag.putLong("ScrollSummonedDespawnAt", despawnAt);

                root.put(Constants.MOD_ID, ffTag);

                LOG.debug("[TamedRavenScrollWatcher] spawnSummonedRaven: lifetime set for id={} now={} despawnAt={}",
                        raven.getId(), now, despawnAt);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: persistent ScrollSummoned tag failed safely: {}",
                        t.toString());
            }

            try {
                raven.addTag(TAG_SCROLL_SUMMONED);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: addTag({}) failed safely: {}", TAG_SCROLL_SUMMONED, t.toString());
            }

            level.addFreshEntity(raven);

            playScrollSummonSpawnFx(level, owner, raven);
            LOG.debug("[TamedRavenScrollWatcher] spawnSummonedRaven: spawned id={} name='{}' for player='{}' at {}",
                    raven.getId(), ravenName, safePlayerName(owner), raven.position());

            return raven;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] spawnSummonedRaven failed safely", t);
            return null;
        }
    }

    private static void onEntityInteract(@NotNull PlayerInteractEvent.EntityInteract event) {
        try {
            Level level = event.getLevel();
            if (!(level instanceof ServerLevel serverLevel) || level.isClientSide()) {
                return;
            }

            Entity target = event.getTarget();
            if (!(target instanceof RavenEntity raven)) {
                return;
            }

            Player player = event.getEntity();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }

            // Only handle scroll-summoned ravens owned by this player
            if (!isScrollSummonedRaven(raven)) {
                return;
            }

            boolean isOwner;
            try {
                isOwner = raven.isTame() && raven.isOwnedBy(serverPlayer);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] onEntityInteract: owner check failed safely for raven id={}: {}",
                        raven.getId(), t.toString());
                return;
            }

            if (!isOwner) {
                return;
            }

            InteractionHand hand = event.getHand();
            InteractionResult result = handleScrollSummonedRavenInteract(serverLevel, serverPlayer, raven, hand);

            if (result.consumesAction()) {
                event.setCancellationResult(result);
                event.setCanceled(true);
            }

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] onEntityInteract failed safely", t);
        }
    }

    @NotNull
    private static InteractionResult handleScrollSummonedRavenInteract(@NotNull ServerLevel level,
                                                                       @NotNull ServerPlayer player,
                                                                       @NotNull RavenEntity raven,
                                                                       @NotNull InteractionHand hand) {
        try {
            // If raven is armed with a failed-job recall payload:
            RecallPayload recall = readRecallPayload(raven);
            if (recall != null) {
                LOG.debug("[TamedRavenScrollWatcher] RecallInteract: player='{}' ravenId={} jobId={} recipient={}",
                        safePlayerName(player), raven.getId(), recall.jobId, recall.recipientUuidStr);

                // 1) Give/drop the failed scroll back to player
                ItemStack returned = buildSealedScrollFromNbt(recall.sealedScrollNbt);
                if (!returned.isEmpty()) {
                    giveOrDropFirstEmpty(player, returned);
                } else {
                    LOG.warn("[TamedRavenScrollWatcher] RecallInteract: failed to reconstruct sealed scroll item for jobId={} player='{}'",
                            recall.jobId, safePlayerName(player));
                }

                // 2) Remove the failed job from world data (it is now "resolved")
                try {
                    RavenCourierData data = RavenCourierData.get(level);
                    UUID recipientUuid = null;
                    try {
                        recipientUuid = UUID.fromString(recall.recipientUuidStr);
                    } catch (Throwable ignored) {}

                    if (recipientUuid != null) {
                        data.removeJob(recall.jobId, recipientUuid);
                        LOG.debug("[TamedRavenScrollWatcher] RecallInteract: removed failed jobId={} recipient={} after return to sender='{}'",
                                recall.jobId, recipientUuid, safePlayerName(player));
                    } else {
                        LOG.warn("[TamedRavenScrollWatcher] RecallInteract: could not parse recipient UUID '{}' for jobId={} (job not removed).",
                                recall.recipientUuidStr, recall.jobId);
                    }
                } catch (Throwable t) {
                    LOG.warn("[TamedRavenScrollWatcher] RecallInteract: removeJob failed safely for jobId={} player='{}': {}",
                            recall.jobId, safePlayerName(player), t.toString());
                }

                // 3) If player is holding a NEW sealed scroll in-hand, create a new courier job and consume it.
                ItemStack inHand = player.getItemInHand(hand);
                boolean holdingNewSealedScroll = isSealedScrollStack(inHand);

                if (holdingNewSealedScroll) {
                    try {
                        RavenCourierData courierData = RavenCourierData.get(level);
                        RavenCourierData.DeliveryJob newJob = courierData.createJobFromSealedScroll(player, raven, inHand);

                        if (newJob != null) {
                            try {
                                inHand.shrink(1);
                            } catch (Throwable shrinkErr) {
                                LOG.warn("[TamedRavenScrollWatcher] RecallInteract: shrink(1) failed safely for player='{}': {}",
                                        safePlayerName(player), shrinkErr.toString());
                            }

                            LOG.debug("[TamedRavenScrollWatcher] RecallInteract: created NEW courier jobId={} after recall return (player='{}')",
                                    newJob.jobId, safePlayerName(player));
                        } else {
                            LOG.warn("[TamedRavenScrollWatcher] RecallInteract: failed to create NEW job from held sealed scroll for player='{}'",
                                    safePlayerName(player));
                        }
                    } catch (Throwable t) {
                        LOG.warn("[TamedRavenScrollWatcher] RecallInteract: createJobFromSealedScroll threw safely for player='{}': {}",
                                safePlayerName(player), t.toString());
                    }
                }

                // 4) Despawn the scroll-summoned raven (always) after recall interaction
                try {
                    despawnOneScrollSummonedRaven(level, player, raven, "recall-complete");
                } catch (Throwable t) {
                    LOG.warn("[TamedRavenScrollWatcher] RecallInteract: despawn failed safely for raven id={}: {}",
                            raven.getId(), t.toString());
                }

                return InteractionResult.CONSUME;
            }

            // No recall payload -> fall back to original behavior:
            // only accept interaction when player is actually using a sealed scroll on the raven.
            ItemStack stack = player.getItemInHand(hand);
            if (!isSealedScrollStack(stack)) {
                return InteractionResult.PASS;
            }

            // Delegate to your existing "send scroll" logic:
            return handleSealedScrollInteract(raven, player, hand);

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] handleScrollSummonedRavenInteract failed safely", t);
            return InteractionResult.PASS;
        }
    }

    private static final class RecallPayload {
        final long jobId;
        final String recipientUuidStr;
        final CompoundTag sealedScrollNbt;

        private RecallPayload(long jobId, @NotNull String recipientUuidStr, @NotNull CompoundTag sealedScrollNbt) {
            this.jobId = jobId;
            this.recipientUuidStr = recipientUuidStr;
            this.sealedScrollNbt = sealedScrollNbt;
        }
    }

    @Nullable
    private static RecallPayload readRecallPayload(@NotNull RavenEntity raven) {
        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return null;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return null;
            }

            if (!ffTag.getBoolean(NBT_RECALL_ACTIVE)) {
                return null;
            }

            long jobId = ffTag.getLong(NBT_RECALL_JOB_ID);
            String recipientUuidStr = ffTag.getString(NBT_RECALL_RECIPIENT_UUID);
            CompoundTag sealed = ffTag.getCompound(NBT_RECALL_SEALED_SCROLL);

            if (jobId <= 0L || recipientUuidStr == null || recipientUuidStr.isEmpty() || sealed == null || sealed.isEmpty()) {
                LOG.warn("[TamedRavenScrollWatcher] readRecallPayload: malformed recall payload on raven id={} (jobId={} recipient='{}' sealedEmpty={})",
                        raven.getId(), jobId, recipientUuidStr, (sealed == null || sealed.isEmpty()));
                return null;
            }

            return new RecallPayload(jobId, recipientUuidStr, sealed.copy());

        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] readRecallPayload failed safely for raven id={}: {}", raven.getId(), t.toString());
            return null;
        }
    }

    private static boolean isSealedScrollStack(@Nullable ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return key != null && SEALED_SCROLL_ID.equals(key);
        } catch (Throwable t) {
            return false;
        }
    }

    @NotNull
    private static ItemStack buildSealedScrollFromNbt(@NotNull CompoundTag sealedScrollNbt) {
        try {
            Item item = BuiltInRegistries.ITEM.get(SEALED_SCROLL_ID);
            if (item == null) {
                LOG.warn("[TamedRavenScrollWatcher] buildSealedScrollFromNbt: sealed scroll item missing (id={})", SEALED_SCROLL_ID);
                return ItemStack.EMPTY;
            }

            ItemStack stack = new ItemStack(item);

            CompoundTag customRoot = new CompoundTag();
            customRoot.put("SealedScroll", sealedScrollNbt.copy());

            CustomData customData = CustomData.of(customRoot);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, customData);

            return stack;

        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] buildSealedScrollFromNbt failed safely: {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    private static void giveOrDropFirstEmpty(@NotNull ServerPlayer player, @NotNull ItemStack stack) {
        try {
            if (stack.isEmpty()) {
                return;
            }

            int size = player.getInventory().getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack existing = player.getInventory().getItem(i);
                if (existing == null || existing.isEmpty()) {
                    player.getInventory().setItem(i, stack);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("[TamedRavenScrollWatcher] giveOrDropFirstEmpty: placed returned scroll in slot {} for player='{}'",
                                i, safePlayerName(player));
                    }
                    return;
                }
            }

            // No empty slot -> drop
            player.drop(stack, false);
            LOG.debug("[TamedRavenScrollWatcher] giveOrDropFirstEmpty: inventory full; dropped returned scroll for player='{}'",
                    safePlayerName(player));

        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] giveOrDropFirstEmpty failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            try {
                player.drop(stack, false);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Play Enderpop-style spawn FX:
     *  - Portal particles (no feathers) at the raven.
     *  - Enderman teleport sound at the raven.
     *  - Raven wing woosh at the raven.
     *  - Player whistle at the *player's* position.
     */
    private static void playScrollSummonSpawnFx(@NotNull ServerLevel level,
                                                @NotNull ServerPlayer owner,
                                                @NotNull RavenEntity raven) {
        try {
            Vec3 ravenPos = raven.position();
            Vec3 playerPos = owner.position();

            // Portal particles around the raven
            level.sendParticles(
                    ParticleTypes.PORTAL,
                    ravenPos.x,
                    ravenPos.y + 0.4D,
                    ravenPos.z,
                    40,
                    0.3D,
                    0.4D,
                    0.3D,
                    0.02D
            );

            // Enderman-style teleport sound at the raven
            level.playSound(
                    null,
                    ravenPos.x,
                    ravenPos.y,
                    ravenPos.z,
                    SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.NEUTRAL,
                    1.0F,
                    1.0F + (level.random.nextFloat() - 0.5F) * 0.2F
            );

            // Player whistle at the player's position (so it feels like the player is "calling" the raven)
            RavenSoundEngine.playAt(
                    level,
                    "featheredfriend:raven.whistle",
                    SoundSource.NEUTRAL,
                    playerPos,
                    0.35F,
                    1.0F
            );

        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] playScrollSummonSpawnFx failed safely: {}", t.toString());
        }
    }

    /**
     * Despawn ONE scroll-summoned raven via TamedRaven.beginDespawnWithFx,
     * falling back to discard() if anything goes wrong.
     *
     * IMPORTANT:
     *  - After we request despawn, we immediately clear:
     *      * The scoreboard tag TAG_SCROLL_SUMMONED.
     *      * The NBT flags ScrollSummoned / ScrollSummonedOwner.
     *    This prevents repeated despawn calls while the fade-out is still running.
     */
    private static void despawnOneScrollSummonedRaven(@NotNull ServerLevel level,
                                                      @NotNull ServerPlayer owner,
                                                      @NotNull RavenEntity raven,
                                                      @NotNull String reason) {
        try {
            TamedRaven tamedModule = null;
            try {
                tamedModule = raven.getTamedRavenModule();
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: getTamedRavenModule failed safely: {}", t.toString());
            }

            String ravenName = "<unnamed>";
            try {
                if (raven.getCustomName() != null) {
                    ravenName = raven.getCustomName().getString();
                }
            } catch (Throwable ignored) {
            }

            // Trigger a one-shot Enderpop FX burst (particles + raven teleport/woosh sounds)
            // at the despawn position, reusing the Teleportation module's logic.
            try {
                Vec3 fxPos = raven.position().add(0.0D, 0.6D, 0.0D);
                long fxSeed =
                        raven.getUUID().getLeastSignificantBits()
                                ^ (long) raven.tickCount
                                ^ 0x5C829867; // just a fixed salt for scroll-despawn
                Teleportation teleportFx = new Teleportation(raven);
                teleportFx.spawnEnderpopBurst(
                        level,
                        fxPos.x,
                        fxPos.y,
                        fxPos.z,
                        fxSeed,
                        "scroll-despawn: " + reason,
                        raven
                );
            } catch (Throwable fxErr) {
                LOG.warn("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: Teleportation FX failed safely for id={}: {}",
                        raven.getId(), fxErr.toString());
            }

            // Request fade-out FX via module if present.
            if (tamedModule != null) {
                try {
                    tamedModule.beginDespawnWithFx(level, owner, ravenName);
                    LOG.debug("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: triggered despawn FX for id={} name='{}' player='{}' reason={}",
                            raven.getId(), ravenName, safePlayerName(owner), reason);
                } catch (Throwable t) {
                    LOG.error("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: beginDespawnWithFx failed; discarding raven directly. err={}",
                            t.toString());
                    raven.discard();
                }
            } else {
                LOG.warn("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: TamedRaven module null; discarding raven without FX. player='{}' id={} reason={}",
                        safePlayerName(owner), raven.getId(), reason);
                raven.discard();
            }

            // --- CRUCIAL: stop treating this raven as scroll-summoned from now on ---

            // 1) Remove scoreboard tag so we no longer pick it up in findScrollSummonedRavensForPlayer.
            try {
                if (raven.getTags().contains(TAG_SCROLL_SUMMONED)) {
                    raven.removeTag(TAG_SCROLL_SUMMONED);
                }
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: removeTag({}) failed safely for id={}: {}",
                        TAG_SCROLL_SUMMONED, raven.getId(), t.toString());
            }

            // 2) Clear NBT flags used for scroll-summon bookkeeping.
            try {
                CompoundTag root = raven.getPersistentData();
                if (root != null) {
                    CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
                    if (ffTag != null && !ffTag.isEmpty()) {
                        ffTag.remove("ScrollSummoned");
                        ffTag.remove("ScrollSummonedOwner");
                        ffTag.remove("ScrollSummonedDespawnAt");
                        root.put(Constants.MOD_ID, ffTag);
                    }
                }
            } catch (Throwable t) {
                LOG.warn(
                        "[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven: clearing ScrollSummoned NBT failed safely for id={}: {}",
                        raven.getId(),
                        t.toString()
                );
            }

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] despawnOneScrollSummonedRaven failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Spawn position helpers
    // ---------------------------------------------------------------------

    @Nullable
    private static Vec3 findSafeSpawnAbovePlayer(@NotNull ServerLevel level,
                                                 @NotNull ServerPlayer owner,
                                                 @NotNull RavenEntity simRaven) {
        try {
            BlockPos feet = owner.blockPosition();
            final int cx = feet.getX();
            final int cz = feet.getZ();
            final int feetY = feet.getY();

            final int minY = level.getMinBuildHeight();
            final int maxY = level.getMaxBuildHeight() - 1;

            // 1) Ceiling scan within 15 blocks above player feet
            int ceilingY = scanFirstCeilingYWithin15(level, owner, cx, feetY, cz, minY, maxY);

            // 2) If ceiling found, scan for a safe 3x3 pocket BELOW ceiling.
            //    IMPORTANT FIX: use baseY = ceilingY - 3 so our 3-high pocket has clearance under the ceiling.
            if (ceilingY > 0) {
                int baseYUnderCeiling = ceilingY - 3;

                // Clamp so a 3-high pocket fits in world bounds
                if (baseYUnderCeiling < minY) baseYUnderCeiling = minY;
                if (baseYUnderCeiling > maxY - 2) baseYUnderCeiling = maxY - 2;

                Vec3 pocketUnderCeiling = findFirst3x3x2PocketNear(level, owner, cx, baseYUnderCeiling, cz, minY, maxY);
                if (pocketUnderCeiling != null) {
                    LOG.debug(
                            "[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer: CEILING FOUND at y={} -> pocketUnderCeiling={}",
                            ceilingY,
                            pocketUnderCeiling
                    );

                    // 5) Simulated A* check
                    if (canSimulatePathToPlayer(level, owner, pocketUnderCeiling, simRaven)) {
                        // 6) Spawn at pocket center
                        return pocketUnderCeiling;
                    }

                    LOG.warn(
                            "[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer: simulated path FAILED -> not spawning. candidate={} player='{}'",
                            pocketUnderCeiling,
                            safePlayerName(owner)
                    );
                    return null;
                }

                // 4) If no pocket under ceiling, fall through to +15 fallback.
                LOG.warn(
                        "[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer: CEILING FOUND at y={} but NO pocket-under-ceiling found (baseY={}) player='{}'",
                        ceilingY,
                        baseYUnderCeiling,
                        safePlayerName(owner)
                );
            }

            // 4) Fallback: safe pocket around +15 blocks above player
            final int preferredOffsetY = 15;
            int baseY = Mth.floor(owner.getY() + preferredOffsetY + 0.5D);

            // Keep away from build limits; then also ensure 3-high pocket fits.
            int clampMin = level.getMinBuildHeight() + 2;
            int clampMax = level.getMaxBuildHeight() - 2;
            baseY = Mth.clamp(baseY, clampMin, clampMax);

            if (baseY < minY) baseY = minY;
            if (baseY > maxY - 2) baseY = maxY - 2;

            Vec3 pocket = findFirst3x3x2PocketNear(level, owner, cx, baseY, cz, minY, maxY);
            if (pocket != null) {
                LOG.debug("[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer: fallback +15 pocket={}", pocket);

                if (canSimulatePathToPlayer(level, owner, pocket, simRaven)) {
                    return pocket;
                }

                LOG.warn(
                        "[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer: simulated path FAILED -> not spawning. candidate={} player='{}'",
                        pocket,
                        safePlayerName(owner)
                );
                return null;
            }

            LOG.warn(
                    "[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer: no pocket found (ceilingY={}, baseY={}) for player='{}' feet={}",
                    ceilingY,
                    baseY,
                    safePlayerName(owner),
                    feet
            );
            return null;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] findSafeSpawnAbovePlayer failed safely", t);
            return null;
        }
    }

    private static int scanFirstCeilingYWithin15(@NotNull ServerLevel level,
                                                 @NotNull ServerPlayer owner,
                                                 int cx,
                                                 int feetY,
                                                 int cz,
                                                 int minY,
                                                 int maxY) {
        try {
            // Guarantee we are scanning the player's FEET column only (no offsets).
            // Scan exactly 15 blocks above feet.
            for (int dy = 1; dy <= 15; dy++) {
                int y = feetY + dy;
                if (y < minY || y > maxY) {
                    break;
                }

                BlockPos probe = new BlockPos(cx, y, cz);

                BlockState st;
                try {
                    st = level.getBlockState(probe);
                } catch (Throwable t) {
                    LOG.warn("[TamedRavenScrollWatcher] scanFirstCeilingYWithin15: getBlockState failed at {} for player='{}': {}",
                            probe, safePlayerName(owner), t.toString());
                    continue;
                }

                boolean isAir;
                try {
                    isAir = st.isAir();
                } catch (Throwable t) {
                    // If blockstate is weird, treat as non-air (safer for "ANY non-air" semantics).
                    isAir = false;
                    LOG.warn("[TamedRavenScrollWatcher] scanFirstCeilingYWithin15: st.isAir() threw at {} st={} player='{}': {}",
                            probe, st, safePlayerName(owner), t.toString());
                }

                if (!isAir) {
                    if (LOG.isInfoEnabled()) {
                        String key = "unknown";
                        try {
                            key = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()));
                        } catch (Throwable ignored) {}
                        LOG.debug("[TamedRavenScrollWatcher] scanFirstCeilingYWithin15: HIT dy={} y={} block={} feetY={} player='{}'",
                                dy, y, key, feetY, safePlayerName(owner));
                    }
                    return y;
                }
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("[TamedRavenScrollWatcher] scanFirstCeilingYWithin15: NONE within 15 (feetY={}) player='{}'",
                        feetY, safePlayerName(owner));
            }
            return -1;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] scanFirstCeilingYWithin15 failed safely", t);
            return -1;
        }
    }

    @Nullable
    private static Vec3 findFirst3x3x2PocketNear(@NotNull ServerLevel level,
                                                 @NotNull ServerPlayer owner,
                                                 int cx,
                                                 int baseY,
                                                 int cz,
                                                 int minY,
                                                 int maxY) {
        try {
            // We require a 3x3x3 air pocket (not 3x3x2) so A* start nodes are valid under low ceilings.
            if (baseY < minY || baseY > (maxY - 2)) {
                LOG.warn(
                        "[TamedRavenScrollWatcher] findFirst3x3x2PocketNear: baseY out of bounds for 3-high pocket. baseY={} minY={} maxY={} player='{}'",
                        baseY,
                        minY,
                        maxY,
                        safePlayerName(owner)
                );
                return null;
            }

            final int maxR = 4;

            for (int r = 0; r <= maxR; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (r > 0 && (Math.abs(dx) != r && Math.abs(dz) != r)) {
                            continue;
                        }

                        int tx = cx + dx;
                        int tz = cz + dz;

                        if (is3x3x3Air(level, tx, baseY, tz)) {
                            Vec3 pocket = new Vec3(tx + 0.5D, baseY + 0.1D, tz + 0.5D);

                            LOG.debug(
                                    "[TamedRavenScrollWatcher] findFirst3x3x2PocketNear: FOUND pocket={} baseY={} off=({}, {}) player='{}'",
                                    pocket,
                                    baseY,
                                    dx,
                                    dz,
                                    safePlayerName(owner)
                            );
                            return pocket;
                        }
                    }
                }
            }

            LOG.debug(
                    "[TamedRavenScrollWatcher] findFirst3x3x2PocketNear: NONE baseY={} center=({}, {}) radius={} player='{}'",
                    baseY,
                    cx,
                    cz,
                    maxR,
                    safePlayerName(owner)
            );
            return null;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] findFirst3x3x2PocketNear failed safely", t);
            return null;
        }
    }

    private static boolean is3x3x2Air(@NotNull ServerLevel level, int cx, int cy, int cz) {
        try {
            for (int dy = 0; dy <= 1; dy++) {
                int y = cy + dy;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                        if (!level.isEmptyBlock(pos)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] is3x3x2Air failed safely: {}", t.toString());
            return false;
        }
    }

    private static boolean is3x3x3Air(@NotNull ServerLevel level, int cx, int cy, int cz) {
        try {
            for (int dy = 0; dy <= 2; dy++) {
                int y = cy + dy;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                        if (!level.isEmptyBlock(pos)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] is3x3x3Air failed safely: {}", t.toString());
            return false;
        }
    }

    private static boolean canSimulatePathToPlayer(@NotNull ServerLevel level,
                                                   @NotNull ServerPlayer owner,
                                                   @NotNull Vec3 spawnPos,
                                                   @NotNull RavenEntity simRaven) {
        try {
            // Put the simulation raven at the candidate start position.
            // This raven is NOT added to the world, so this stays purely "planning".
            try {
                simRaven.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, owner.getYRot(), 0.0F);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] canSimulatePathToPlayer: moveTo failed safely. player='{}' spawnPos={} err={}",
                        safePlayerName(owner), spawnPos, t.toString());
                return false;
            }

            Vec3 goal = owner.position();

            long seed;
            try {
                seed =
                        owner.getUUID().getMostSignificantBits()
                                ^ owner.getUUID().getLeastSignificantBits()
                                ^ (long) level.getGameTime()
                                ^ 0x6D2B79F5A5A5A5A5L;
            } catch (Throwable t) {
                seed = (long) level.getGameTime() ^ 0x6D2B79F5A5A5A5A5L;
            }

            boolean ok;
            try {
                ok = simRaven.simulateAStarPathTo(goal, 6 * 20, seed, "scroll-summon simulated path");
            } catch (Throwable t) {
                ok = false;
                LOG.warn("[TamedRavenScrollWatcher] canSimulatePathToPlayer: simulateAStarPathTo threw. player='{}' spawnPos={} goal={} err={}",
                        safePlayerName(owner), spawnPos, goal, t.toString());
            }

            if (ok) {
                LOG.debug("[TamedRavenScrollWatcher] canSimulatePathToPlayer: A* simulation OK player='{}' spawnPos={} goal={}",
                        safePlayerName(owner), spawnPos, goal);
            } else {
                LOG.debug("[TamedRavenScrollWatcher] canSimulatePathToPlayer: A* simulation FAIL player='{}' spawnPos={} goal={}",
                        safePlayerName(owner), spawnPos, goal);
            }

            return ok;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] canSimulatePathToPlayer failed safely", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Tamed raven info
    // ---------------------------------------------------------------------

    private static final class TamedRavenInfo {
        final boolean hasTamedRaven;
        final String ravenName;

        TamedRavenInfo(boolean hasTamedRaven, String ravenName) {
            this.hasTamedRaven = hasTamedRaven;
            this.ravenName = ravenName;
        }
    }

    /**
     * Reads the same structure stored by TamedRaven.storeTamedRavenForPlayer:
     *
     *   root = player.getPersistentData()
     *   ffTag = root.getCompound(Constants.MOD_ID)
     *   ravenTag = ffTag.getCompound("TamedRaven")
     *     - HasTamedRaven : boolean
     *     - RavenName     : string
     */
    @Nullable
    private static TamedRavenInfo readTamedRavenInfo(@NotNull Player player) {
        try {
            CompoundTag root = player.getPersistentData();
            if (root == null) {
                return null;
            }

            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return null;
            }

            if (!ffTag.contains("TamedRaven", Tag.TAG_COMPOUND)) {
                return null;
            }

            CompoundTag ravenTag = ffTag.getCompound("TamedRaven");
            if (ravenTag == null || ravenTag.isEmpty()) {
                return null;
            }

            boolean has = ravenTag.getBoolean("HasTamedRaven");
            String name = ravenTag.getString("RavenName");
            return new TamedRavenInfo(has, name);

        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] readTamedRavenInfo failed safely: {}", t.toString());
            return null;
        }
    }

    /**
     * Handles the specific case:
     *  - Player RMBs a tamed RavenEntity
     *  - with a Sealed Scroll in hand
     *  - and the player is the raven's owner.
     *
     * Called from RavenEntity.mobInteract(...) on BOTH CLIENT and SERVER.
     *
     * Returns:
     *   - PASS    -> scroll interaction not handled here; let other logic run.
     *   - SUCCESS / CONSUME / sidedSuccess(...) -> interaction consumed by scroll logic.
     */
    public static InteractionResult handleSealedScrollInteract(@NotNull RavenEntity raven,
                                                               @NotNull Player player,
                                                               @NotNull InteractionHand hand) {
        try {
            Level level = raven.level();
            if (level == null) {
                return InteractionResult.PASS;
            }

            boolean clientSide = level.isClientSide;
            ItemStack stack = player.getItemInHand(hand);

            // Only care about our sealed scroll item
            if (stack == null || stack.isEmpty()) {
                return InteractionResult.PASS;
            }

            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null || !SEALED_SCROLL_ID.equals(key)) {
                return InteractionResult.PASS;
            }

            // Only when this raven is tamed and owned by the player using the scroll
            boolean isOwner = false;
            try {
                isOwner = raven.isTame() && raven.isOwnedBy(player);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] handleSealedScrollInteract: owner check failed safely for raven id={}: {}",
                        raven.getId(), t.toString());
            }

            if (!isOwner) {
                // Not the owner's raven -> do nothing special, let other logic run.
                LOG.debug(
                        "[TamedRavenScrollWatcher] handleSealedScrollInteract: player='{}' used scroll on raven id={} but is not owner (ignoring).",
                        safePlayerName(player),
                        raven.getId()
                );
                return InteractionResult.PASS;
            }

            // At this point we KNOW:
            //  - The item is a sealed scroll
            //  - The raven is tamed
            //  - The player is the raven's owner

            if (clientSide) {
                // CLIENT: just make it look successful, real logic is server-side.
                LOG.debug(
                        "[TamedRavenScrollWatcher] handleSealedScrollInteract: CLIENT accepted sealed scroll use " +
                                "(player='{}', raven id={}, hand={}, stack={})",
                        safePlayerName(player),
                        raven.getId(),
                        hand,
                        stack
                );
                return InteractionResult.sidedSuccess(true);
            }

            // SERVER: perform the first leg of courier dispatch.
            if (!(player instanceof ServerPlayer serverPlayer)) {
                // Should never happen on logical server, but we guard anyway.
                LOG.warn("[TamedRavenScrollWatcher] handleSealedScrollInteract: player is not ServerPlayer on server side");
                return InteractionResult.PASS;
            }
            if (!(level instanceof ServerLevel serverLevel)) {
                LOG.warn("[TamedRavenScrollWatcher] handleSealedScrollInteract: level is not ServerLevel on server side");
                return InteractionResult.PASS;
            }

            // 1) Create a delivery job in RavenCourierData from this Sealed Scroll.
            RavenCourierData courierData = RavenCourierData.get(serverLevel);
            RavenCourierData.DeliveryJob job = courierData.createJobFromSealedScroll(
                    serverPlayer,
                    raven,
                    stack
            );

            if (job == null) {
                // Something about the scroll's NBT / recipient data was invalid.
                // We do NOT consume the item and we do NOT despawn the raven.
                LOG.warn(
                        "[TamedRavenScrollWatcher] handleSealedScrollInteract: FAILED to create courier job " +
                                "(player='{}', raven id={}, hand={}, stack={})",
                        safePlayerName(serverPlayer),
                        raven.getId(),
                        hand,
                        stack
                );
                return InteractionResult.PASS;
            }

            LOG.debug(
                    "[TamedRavenScrollWatcher] handleSealedScrollInteract: SERVER created courier jobId={} from sealed scroll " +
                            "(sender='{}', recipient='{}', ravenId={})",
                    job.jobId,
                    job.senderName,
                    job.recipientName,
                    raven.getId()
            );

            // 2) Switch the model of this raven to the SCROLL variant (visually holding the scroll).
            try {
                raven.setRavenVariant(net.z2six.featheredfriend.entity.raven.RavenVariant.SCROLL);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] handleSealedScrollInteract: setRavenVariant(SCROLL) failed safely for id={}: {}",
                        raven.getId(), t.toString());
            }

            // 3) Consume exactly ONE Sealed Scroll from the player's hand (the one we just used),
            //    now that we KNOW the job was created and stored successfully.
            try {
                ItemStack inHand = serverPlayer.getItemInHand(hand);
                if (!inHand.isEmpty() && inHand.getItem() == stack.getItem()) {
                    inHand.shrink(1);
                } else {
                    // If for some weird reason the item changed between checks, log and skip.
                    LOG.warn("[TamedRavenScrollWatcher] handleSealedScrollInteract: item in hand changed before consumption for player='{}'",
                            safePlayerName(serverPlayer));
                }
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] handleSealedScrollInteract: shrink(1) failed safely for player='{}': {}",
                        safePlayerName(serverPlayer),
                        t.toString());
            }

            // 4) Trigger fade-out / despawn FX for this raven, but ONLY because job creation succeeded.
            try {
                despawnOneScrollSummonedRaven(
                        serverLevel,
                        serverPlayer,
                        raven,
                        "courier-dispatch: sealed scroll accepted"
                );
            } catch (Throwable t) {
                LOG.error("[TamedRavenScrollWatcher] handleSealedScrollInteract: despawnOneScrollSummonedRaven failed safely for id={}: {}",
                        raven.getId(),
                        t.toString());
                // Fail-safe: if FX despawn fails, we do NOT forcibly discard here,
                // so the raven remains in-world rather than causing a hard state mismatch.
            }

            // We fully handled this interaction: the scroll was turned into a courier job,
            // the raven swapped to SCROLL variant and began its fade-out.
            return InteractionResult.CONSUME;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] handleSealedScrollInteract failed safely", t);
            return InteractionResult.PASS;
        }
    }

    // ---------------------------------------------------------------------
    // Simple helpers
    // ---------------------------------------------------------------------

    /**
     * Handles a whistle keybind request from the client.
     *
     * Contract:
     *  - Must be called on the logical server.
     *  - The caller is expected to be the raven's owner.
     *
     * Behavior:
     *  - If the player is not holding a sealed scroll -> tells them and does nothing.
     *  - If they don't have a stored tamed raven -> tells them and does nothing.
     *  - If no scroll-summoned ravens exist -> spawns one (same FX as auto-summon).
     *  - If multiple exist -> keeps the closest, despawns the rest, replays FX on the survivor.
     *  - If exactly one exists -> enforces correct name and replays FX on it.
     *
     * This path ignores the "auto-summon on scroll" setting; it is a manual override.
     */
    public static void handleWhistleSummonRequest(@NotNull ServerPlayer serverPlayer) {
        try {
            ServerLevel serverLevel = serverPlayer.serverLevel();
            UUID playerId = serverPlayer.getUUID();

            boolean holding = isHoldingSealedScroll(serverPlayer);

            RavenCourierData courierData = RavenCourierData.get(serverLevel);
            boolean hasFailedAsSender = courierData.hasFailedJobsAsSender(playerId);

            if (!holding && !hasFailedAsSender) {
                serverPlayer.sendSystemMessage(
                        Component.literal("[FeatheredFriend] You must hold a sealed scroll to whistle for your raven.")
                );
                LOG.debug("[TamedRavenScrollWatcher] Whistle request denied: player='{}' not holding sealed scroll and no failed jobs.",
                        safePlayerName(serverPlayer));
                return;
            }

            TamedRavenInfo info = readTamedRavenInfo(serverPlayer);
            boolean hasTamedRaven = info != null && info.hasTamedRaven;
            String ravenName = (info != null && info.ravenName != null && !info.ravenName.isEmpty())
                    ? info.ravenName
                    : "Raven";

            if (!hasTamedRaven) {
                serverPlayer.sendSystemMessage(
                        Component.literal("[FeatheredFriend] You do not have a tamed raven bound to you.")
                );
                LOG.debug("[TamedRavenScrollWatcher] Whistle request denied: player='{}' has no stored tamed raven.",
                        safePlayerName(serverPlayer));
                return;
            }

            List<RavenEntity> scrollRavens = findScrollSummonedRavensForPlayer(serverLevel, serverPlayer);

            if (scrollRavens.isEmpty()) {
                RavenEntity spawned = spawnSummonedRaven(serverLevel, serverPlayer, ravenName);
                if (spawned != null) {
                    LOG.debug("[TamedRavenScrollWatcher] Whistle: spawned scroll-raven id={} for player='{}' at {}",
                            spawned.getId(), safePlayerName(serverPlayer), spawned.position());
                }
                return;
            }

            if (scrollRavens.size() > 1) {
                RavenEntity primary = pickClosestRaven(scrollRavens, serverPlayer);
                for (RavenEntity r : scrollRavens) {
                    if (r == primary) continue;
                    despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "deduplicate scroll ravens (whistle)");
                }
                ensureRavenName(primary, ravenName);
                return;
            }

            ensureRavenName(scrollRavens.get(0), ravenName);

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] handleWhistleSummonRequest failed safely", t);
        }
    }

    private static void ensureRavenName(@NotNull RavenEntity raven, @NotNull String ravenName) {
        try {
            Component cur = raven.getCustomName();
            String curStr = (cur == null) ? "" : cur.getString();
            if (!ravenName.equals(curStr)) {
                raven.setCustomName(Component.literal(ravenName));
            }
            raven.setCustomNameVisible(true);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] ensureRavenName failed safely for id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    /**
     * Returns true if the player's main hand item is the sealed scroll.
     */
    public static boolean isHoldingSealedScroll(@NotNull Player player) { // ← was private
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
            LOG.warn("[TamedRavenScrollWatcher] isHoldingSealedScroll failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return false;
        }
    }

    /**
     * Returns true if this raven is currently marked as a scroll-summoned raven
     * via the TAG_SCROLL_SUMMONED scoreboard tag.
     */
    public static boolean isScrollSummonedRaven(@NotNull RavenEntity raven) {
        try {
            return raven.getTags().contains(TAG_SCROLL_SUMMONED);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] isScrollSummonedRaven failed safely for id={}: {}",
                    raven.getId(), t.toString());
            return false;
        }
    }

    /**
     * Convenience helper:
     *  - Only valid on the server (ServerLevel).
     *  - Returns the raven's owner as ServerPlayer *iff*:
     *      * the raven is scroll-summoned, AND
     *      * the owner is online in this level, AND
     *      * the owner is currently holding a sealed scroll in main hand.
     *
     * Otherwise returns null.
     */
    @Nullable
    public static ServerPlayer getScrollSummonOwnerIfHoldingScroll(@NotNull ServerLevel level,
                                                                   @NotNull RavenEntity raven) {
        try {
            // Must be tagged as scroll-summoned.
            if (!isScrollSummonedRaven(raven)) {
                return null;
            }

            UUID ownerId;
            try {
                ownerId = raven.getOwnerUUID();
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] getScrollSummonOwnerIfHoldingScroll: getOwnerUUID failed safely for id={}: {}",
                        raven.getId(), t.toString());
                return null;
            }

            if (ownerId == null) {
                return null;
            }

            ServerPlayer owner;
            try {
                owner = level.getServer().getPlayerList().getPlayer(ownerId);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] getScrollSummonOwnerIfHoldingScroll: player lookup failed safely for ownerId={}: {}",
                        ownerId, t.toString());
                return null;
            }

            if (owner == null) {
                return null;
            }

            // Reuse the existing helper to validate scroll in main hand.
            if (!isHoldingSealedScroll(owner)) {
                return null;
            }

            return owner;

        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] getScrollSummonOwnerIfHoldingScroll failed safely for raven id={}: {}",
                    raven.getId(), t.toString());
            return null;
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
