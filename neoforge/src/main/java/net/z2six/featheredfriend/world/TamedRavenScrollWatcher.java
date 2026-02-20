// neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenScrollWatcher.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.RavenChestBlock;
import net.z2six.featheredfriend.entity.raven.RavenAIState;
import net.z2six.featheredfriend.entity.raven.RavenAnimMode;
import net.z2six.featheredfriend.entity.raven.RavenArmorVisual;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.modules.RavenSoundEngine;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import net.z2six.featheredfriend.entity.raven.modules.Teleportation;
import net.z2six.featheredfriend.item.EnderpackStorage;
import net.z2six.featheredfriend.log.RavenLogCategory;
import net.z2six.featheredfriend.network.RavenChestChoiceInfo;
import net.z2six.featheredfriend.network.RavenChestSelectAction;
import net.z2six.featheredfriend.platform.Services;
import net.z2six.featheredfriend.block.entity.RavenChestBlockEntity;
import net.z2six.featheredfriend.world.RavenChestRegistryData;
import net.z2six.featheredfriend.world.TamedRavenPlayerData;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import net.z2six.featheredfriend.registry.FFEntities;
import net.z2six.featheredfriend.registry.FFItems;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Method;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/TamedRavenScrollWatcher.java
 *
 * Behavior:
 *  - Scroll-summoned ravens are spawned manually via whistle.
 *  - This watcher keeps bookkeeping robust:
 *      * Lifetime timeout.
 *      * Duplicate cleanup (keep one).
 *      * Name sync with stored tamed raven data when available.
 *
 * Implementation:
 *  - Detection of "scroll-summoned" ravens is via scoreboard tag + owner UUID:
 *      * Scoreboard tag:  "ff_scroll_summoned"
 *      * Owner:           raven.getOwnerUUID() == playerUUID
 *  - No entity-id-based spawn logic; we derive the state from the world every tick.
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
    private static final String TAG_COURIER_RAVEN = "ff_courier_raven";

    private static final String NBT_SCROLL_SUMMONED = "ScrollSummoned";
    private static final String NBT_SCROLL_SUMMONED_OWNER = "ScrollSummonedOwner";
    private static final String NBT_SCROLL_SUMMONED_DESPAWN_AT = "ScrollSummonedDespawnAt";
    private static final String NBT_SCROLL_SUMMON_LINK_PAUSE_LAST_TICK = "ScrollSummonedLinkPauseLastTick";
    private static final String NBT_BOUND_RAVEN_ID = "BoundRavenId";
    private static final String NBT_RAVEN_CHEST_PERCH_ASSIGNED = "RavenChestPerchAssigned";
    private static final String NBT_RAVEN_CHEST_PERCH_DIMENSION = "RavenChestPerchDimension";
    private static final String NBT_RAVEN_CHEST_PERCH_BLOCK_POS = "RavenChestPerchBlockPos";

    private static final double RAVEN_CHEST_PERCH_OFFSET_X = 0.5D;
    private static final double RAVEN_CHEST_PERCH_OFFSET_Y = 1.6D;
    private static final double RAVEN_CHEST_PERCH_OFFSET_Z = 0.5D;

    /**
     * Cache of the currently tracked scroll-summoned raven per player.
     * This avoids full AABB scans every single tick.
     */
    private static final Map<UUID, ScrollRavenCache> SCROLL_RAVEN_CACHE = new ConcurrentHashMap<>();

    /** How often we re-scan the world for scroll-summoned ravens (per player). */
    private static final int SCROLL_SCAN_INTERVAL_TICKS = 20; // 1s

    /** How often we re-check scroll raven lifetime (per player). */
    private static final int SCROLL_LIFETIME_CHECK_INTERVAL_TICKS = 20; // 1s

    private static final class ScrollRavenCache {
        private long lastScanGameTime = 0L;
        private long lastLifetimeCheckGameTime = 0L;
        private @Nullable UUID cachedRavenUuid = null;
    }

    private static final int ENDERPACK_WORKFLOW_DEPOSIT_DELAY_TICKS = 2;
    private static final int ENDERPACK_WORKFLOW_CHEST_HOLD_TICKS = 20;
    private static final int ENDERPACK_WORKFLOW_RETURN_DELAY_TICKS = ENDERPACK_WORKFLOW_CHEST_HOLD_TICKS + 2;

    private enum EnderpackReturnTargetKind {
        INVENTORY,
        CURIOS
    }

    private static final class EnderpackExtraction {
        private final @NotNull ItemStack enderpackStack;
        private final @NotNull EnderpackReturnTargetKind returnTargetKind;
        private final @Nullable String curiosIdentifier;
        private final int curiosIndex;

        private EnderpackExtraction(@NotNull ItemStack enderpackStack,
                                    @NotNull EnderpackReturnTargetKind returnTargetKind,
                                    @Nullable String curiosIdentifier,
                                    int curiosIndex) {
            this.enderpackStack = enderpackStack;
            this.returnTargetKind = returnTargetKind;
            this.curiosIdentifier = curiosIdentifier;
            this.curiosIndex = curiosIndex;
        }
    }

    private static final class EnderpackDepositWorkflow {
        private @NotNull UUID ravenUuid;
        private @NotNull UUID ownerUuid;
        private @NotNull String chestDimensionId;
        private long chestBlockPos;
        private @NotNull EnderpackExtraction extraction;
        private long depositAtGameTime;
        private long returnAtGameTime;
        private boolean deposited;
        private int movedItems;
        private boolean invalidTarget;
    }

    private static final Map<UUID, EnderpackDepositWorkflow> ENDERPACK_DEPOSIT_WORKFLOWS = new ConcurrentHashMap<>();

    /**
     * Lifetime of a scroll-summoned raven in ticks.
     * 30 seconds * 20 ticks per second = 600 ticks.
     */
    private static final long SCROLL_SUMMON_LIFETIME_TICKS = 30L * 20L;

    private static final String NBT_RECALL_ACTIVE = "ScrollRecallActive";
    private static final String NBT_RECALL_JOB_ID = "ScrollRecallJobId";
    private static final String NBT_RECALL_RECIPIENT_UUID = "ScrollRecallRecipientUUID";
    private static final String NBT_RECALL_SEALED_SCROLL = "ScrollRecallSealedScroll";
    private static final String NBT_COURIER_ACTIVE = "CourierActive";
    private static final String NBT_COURIER_JOB_ID = "CourierJobId";
    private static final String NBT_COURIER_SENDER_UUID = "CourierSenderUUID";
    private static final String NBT_COURIER_RECIPIENT_UUID = "CourierRecipientUUID";
    private static final String NBT_COURIER_DESPAWN_AT = "CourierDespawnAt";
    private static final String NBT_COURIER_LINK_PAUSE_LAST_TICK = "CourierLinkPauseLastTick";
    private static final String NBT_ENDERPACK_DEPOSIT_LAST_AT_MS = "EnderpackDepositLastAtMs";
    private static final String NBT_SCROLL_DELIVERY_LAST_AT_MS = "ScrollDeliveryLastAtMs";

    private TamedRavenScrollWatcher() {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Public registration
    // ---------------------------------------------------------------------

    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(TamedRavenScrollWatcher::onPlayerTick);
            NeoForge.EVENT_BUS.addListener(TamedRavenScrollWatcher::onServerTickPost);
            NeoForge.EVENT_BUS.addListener(TamedRavenScrollWatcher::onEntityInteract);
            LOG.debug("[TamedRavenScrollWatcher] Registered PlayerTickEvent.Post + ServerTickEvent.Post + EntityInteract listeners");
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
            ScrollRavenCache cache = SCROLL_RAVEN_CACHE.computeIfAbsent(playerId, k -> new ScrollRavenCache());

            TamedRavenPlayerData.TamedRavenInfo info = TamedRavenPlayerData.getTamedRavenInfo(serverPlayer);
            boolean hasTamedRaven = info != null && info.hasTamedRaven();
            String ravenName = (info != null && info.ravenName() != null && !info.ravenName().isEmpty())
                    ? info.ravenName()
                    : Component.translatable("entity.featheredfriend.raven").getString();
            RavenArmorVisual armorVisual = (info != null && info.armorVisual() != null)
                    ? info.armorVisual()
                    : RavenArmorVisual.NONE;
            UUID boundRavenId = (info != null) ? info.boundRavenId() : null;
            if (hasTamedRaven && boundRavenId == null) {
                boundRavenId = TamedRavenPlayerData.ensureBoundRavenId(serverPlayer, UUID.randomUUID());
            }

            long nowGameTime = serverLevel.getGameTime();
            boolean shouldScan = (player.tickCount == 0)
                    || ((nowGameTime - cache.lastScanGameTime) >= SCROLL_SCAN_INTERVAL_TICKS);

            List<RavenEntity> scrollRavens = new ArrayList<>(1);
            if (shouldScan) {
                scrollRavens = findScrollSummonedRavensForPlayer(serverLevel, serverPlayer, boundRavenId);
                cache.lastScanGameTime = nowGameTime;
                cache.cachedRavenUuid = scrollRavens.isEmpty() ? null : scrollRavens.get(0).getUUID();
            } else {
                RavenEntity cached = getCachedScrollRaven(serverLevel, playerId, cache.cachedRavenUuid, boundRavenId);
                if (cached != null) {
                    scrollRavens.add(cached);
                } else {
                    cache.cachedRavenUuid = null;
                }
            }

            // Lifetime expiry
            if (!scrollRavens.isEmpty()
                    && (nowGameTime - cache.lastLifetimeCheckGameTime) >= SCROLL_LIFETIME_CHECK_INTERVAL_TICKS) {
                cache.lastLifetimeCheckGameTime = nowGameTime;
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

                        long despawnAt = ffTag.getLong(NBT_SCROLL_SUMMONED_DESPAWN_AT);
                        if (RavenLinkRuntime.isRavenLinkControlled(r)) {
                            if (despawnAt > 0L) {
                                long lastPauseTick = ffTag.getLong(NBT_SCROLL_SUMMON_LINK_PAUSE_LAST_TICK);
                                long anchorTick = lastPauseTick > 0L ? lastPauseTick : nowGameTime;
                                long elapsed = Math.max(0L, nowGameTime - anchorTick);
                                if (elapsed > 0L) {
                                    despawnAt += elapsed;
                                    ffTag.putLong(NBT_SCROLL_SUMMONED_DESPAWN_AT, despawnAt);
                                }
                            }
                            ffTag.putLong(NBT_SCROLL_SUMMON_LINK_PAUSE_LAST_TICK, nowGameTime);
                            root.put(Constants.MOD_ID, ffTag);
                            continue;
                        }

                        if (ffTag.contains(NBT_SCROLL_SUMMON_LINK_PAUSE_LAST_TICK, Tag.TAG_LONG)) {
                            ffTag.remove(NBT_SCROLL_SUMMON_LINK_PAUSE_LAST_TICK);
                            root.put(Constants.MOD_ID, ffTag);
                        }

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
                            despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "scroll lifetime expired (30s)", false);
                        } catch (Throwable t) {
                            LOG.error(
                                    "[TamedRavenScrollWatcher] Failed safely while despawning expired scroll raven id={}: {}",
                                    r.getId(),
                                    t.toString()
                            );
                        }
                        scrollRavens.remove(r);
                    }
                    if (scrollRavens.isEmpty()) {
                        cache.cachedRavenUuid = null;
                    }
                }
            }

            if (!player.isAlive() || player.isRemoved()) {
                SCROLL_RAVEN_CACHE.remove(playerId);
                return;
            }

            if (scrollRavens.isEmpty()) {
                cache.cachedRavenUuid = null;
                return;
            }

            if (scrollRavens.size() > 1) {
                RavenEntity primary = pickClosestRaven(scrollRavens, serverPlayer);
                for (RavenEntity r : scrollRavens) {
                    if (r == primary) {
                        continue;
                    }
                    despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "deduplicate scroll ravens (manual summon)", false);
                }
                if (hasTamedRaven) {
                    ensureRavenName(primary, ravenName);
                }
                cache.cachedRavenUuid = primary.getUUID();
                return;
            }

            if (hasTamedRaven) {
                ensureRavenName(scrollRavens.get(0), ravenName);
            }
            cache.cachedRavenUuid = scrollRavens.get(0).getUUID();

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] onPlayerTick failed safely", t);
        }
    }

    private static void onServerTickPost(@NotNull ServerTickEvent.Post event) {
        try {
            tickEnderpackDepositWorkflows(event.getServer());
        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] onServerTickPost failed safely", t);
        }
    }

    private static void tickEnderpackDepositWorkflows(@NotNull MinecraftServer server) {
        if (ENDERPACK_DEPOSIT_WORKFLOWS.isEmpty()) {
            return;
        }

        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }

        long now = overworld.getGameTime();
        List<UUID> done = new ArrayList<>();
        List<UUID> rollback = new ArrayList<>();

        for (Map.Entry<UUID, EnderpackDepositWorkflow> entry : ENDERPACK_DEPOSIT_WORKFLOWS.entrySet()) {
            UUID workflowKey = entry.getKey();
            EnderpackDepositWorkflow workflow = entry.getValue();
            if (workflow == null) {
                done.add(workflowKey);
                continue;
            }

            boolean returnedToOwner = false;
            try {
                ServerPlayer owner = server.getPlayerList().getPlayer(workflow.ownerUuid);
                RavenEntity raven = findRavenByUuid(server, workflow.ravenUuid);
                if (raven == null || !raven.isAlive() || raven.isRemoved()) {
                    RavenEntity loaded = tryLoadWorkflowRaven(server, workflow);
                    if (loaded != null && loaded.isAlive() && !loaded.isRemoved()) {
                        raven = loaded;
                    }
                }

                if (!workflow.deposited && now >= workflow.depositAtGameTime) {
                    if (raven == null || !raven.isAlive() || raven.isRemoved()) {
                        workflow.returnAtGameTime = Math.min(workflow.returnAtGameTime, now);
                        if (owner != null) {
                            logPlayer(
                                    owner,
                                    RavenLogCategory.ENDERPACK,
                                    "log.featheredfriend.enderpack.workflow.raven_missing_before_deposit"
                            );
                        }
                    } else {
                        ResourceLocation dimLoc = ResourceLocation.tryParse(workflow.chestDimensionId);
                        if (dimLoc == null) {
                            workflow.invalidTarget = true;
                            workflow.deposited = true;
                            workflow.returnAtGameTime = Math.min(workflow.returnAtGameTime, now);
                            if (owner != null) {
                                logPlayer(
                                        owner,
                                        RavenLogCategory.CHEST,
                                        "log.featheredfriend.enderpack.workflow.invalid_dimension_id"
                                );
                            }
                        } else {
                            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
                            ServerLevel chestLevel = server.getLevel(dimKey);
                            if (chestLevel == null) {
                                workflow.invalidTarget = true;
                                workflow.deposited = true;
                                workflow.returnAtGameTime = Math.min(workflow.returnAtGameTime, now);
                                if (owner != null) {
                                    logPlayer(
                                            owner,
                                            RavenLogCategory.CHEST,
                                            "log.featheredfriend.enderpack.workflow.level_unavailable"
                                    );
                                }
                            } else {
                                BlockPos chestPos = BlockPos.of(workflow.chestBlockPos);
                                chestLevel.getChunk(chestPos.getX() >> 4, chestPos.getZ() >> 4);
                                BlockEntity be = chestLevel.getBlockEntity(chestPos);
                                if (!(be instanceof RavenChestBlockEntity ravenChest) || raven.level() != chestLevel) {
                                    workflow.invalidTarget = true;
                                    workflow.deposited = true;
                                    workflow.returnAtGameTime = Math.min(workflow.returnAtGameTime, now);
                                    if (owner != null) {
                                        logPlayer(
                                                owner,
                                                RavenLogCategory.CHEST,
                                                "log.featheredfriend.enderpack.workflow.target_missing_or_wrong_level"
                                        );
                                    }
                                } else {
                                    applyRavenChestPerchPose(chestLevel, raven, chestPos);
                                    playRavenChestArrivalFx(chestLevel, raven, "enderpack-deposit-arrive");
                                    ravenChest.triggerScriptedOpenForTicks(ENDERPACK_WORKFLOW_CHEST_HOLD_TICKS);

                                    int moved = transferEnderpackContentsIntoContainer(
                                            workflow.extraction.enderpackStack,
                                            ravenChest,
                                            chestLevel.registryAccess()
                                    );
                                    workflow.movedItems = Math.max(0, moved);
                                    workflow.deposited = true;
                                    workflow.returnAtGameTime = Math.max(
                                            workflow.returnAtGameTime,
                                            now + ENDERPACK_WORKFLOW_RETURN_DELAY_TICKS
                                    );
                                    if (owner != null) {
                                        logPlayer(
                                                owner,
                                                RavenLogCategory.ENDERPACK,
                                                "log.featheredfriend.enderpack.workflow.deposit_finished",
                                                workflow.movedItems
                                        );
                                    }
                                }
                            }
                        }
                    }
                }

                boolean ravenMissing = (raven == null || !raven.isAlive() || raven.isRemoved());
                if (now < workflow.returnAtGameTime || (!workflow.deposited && !ravenMissing)) {
                    continue;
                }

                if (owner == null || !owner.isAlive() || owner.isRemoved()) {
                    continue;
                }

                if (raven != null && raven.isAlive() && !raven.isRemoved() && raven.level() instanceof ServerLevel ravenLevel) {
                    despawnOneScrollSummonedRaven(
                            ravenLevel,
                            owner,
                            raven,
                            "enderpack-deposit-return",
                            false
                    );
                }

                RavenEntity returnedRaven = spawnReturnRavenForOwner(owner);
                Vec3 dropPos = returnedRaven != null ? returnedRaven.position() : owner.position();
                returnExtractedEnderpack(owner, workflow.extraction, dropPos);
                returnedToOwner = true;

                if (workflow.invalidTarget) {
                    owner.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                    logPlayer(
                            owner,
                            RavenLogCategory.CHEST,
                            "log.featheredfriend.enderpack.workflow.target_became_invalid"
                    );
                } else if (workflow.deposited) {
                    if (workflow.movedItems > 0) {
                        owner.sendSystemMessage(Component.translatable(
                                "message.featheredfriend.raven_chest.deposit.success",
                                workflow.movedItems
                        ));
                        logPlayer(
                                owner,
                                RavenLogCategory.ENDERPACK,
                                "log.featheredfriend.enderpack.workflow.returned_success",
                                workflow.movedItems
                        );
                    } else {
                        owner.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.deposit.empty"));
                        logPlayer(
                                owner,
                                RavenLogCategory.ENDERPACK,
                                "log.featheredfriend.enderpack.workflow.returned_empty"
                        );
                    }
                }

                done.add(workflowKey);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] tickEnderpackDepositWorkflows: workflow failed safely key={}: {}",
                        workflowKey, t.toString());
                if (!returnedToOwner) {
                    rollback.add(workflowKey);
                }
                done.add(workflowKey);
            }
        }

        for (UUID key : done) {
            EnderpackDepositWorkflow workflow = ENDERPACK_DEPOSIT_WORKFLOWS.remove(key);
            if (workflow == null) {
                continue;
            }
            if (!rollback.contains(key)) {
                continue;
            }
            try {
                ServerPlayer owner = server.getPlayerList().getPlayer(workflow.ownerUuid);
                if (owner != null) {
                    returnExtractedEnderpack(owner, workflow.extraction, owner.position());
                    logPlayer(
                            owner,
                            RavenLogCategory.ENDERPACK,
                            "log.featheredfriend.enderpack.workflow.failed_returned"
                    );
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Nullable
    private static RavenEntity findRavenByUuid(@NotNull MinecraftServer server, @Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof RavenEntity raven) {
                return raven;
            }
        }
        return null;
    }

    @Nullable
    private static RavenEntity tryLoadWorkflowRaven(@NotNull MinecraftServer server,
                                                    @NotNull EnderpackDepositWorkflow workflow) {
        try {
            ResourceLocation dimLoc = ResourceLocation.tryParse(workflow.chestDimensionId);
            if (dimLoc == null) {
                return null;
            }
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
            ServerLevel level = server.getLevel(dimKey);
            if (level == null) {
                return null;
            }

            BlockPos chestPos = BlockPos.of(workflow.chestBlockPos);
            level.getChunk(chestPos.getX() >> 4, chestPos.getZ() >> 4);
            Entity entity = level.getEntity(workflow.ravenUuid);
            return (entity instanceof RavenEntity raven) ? raven : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static RavenEntity getCachedScrollRaven(@NotNull ServerLevel level,
                                                    @NotNull UUID ownerId,
                                                    @Nullable UUID ravenUuid,
                                                    @Nullable UUID boundRavenId) {
        try {
            if (ravenUuid == null) {
                return null;
            }
            Entity e = level.getEntity(ravenUuid);
            if (!(e instanceof RavenEntity raven)) {
                return null;
            }
            if (!raven.isAlive() || raven.isRemoved()) {
                return null;
            }
            if (!isScrollSummonedForOwner(raven, ownerId, boundRavenId)) {
                return null;
            }
            return raven;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // World scanning helpers
    // ---------------------------------------------------------------------

    private static boolean isScrollSummonedForOwner(@NotNull RavenEntity raven,
                                                    @NotNull UUID ownerId,
                                                    @Nullable UUID boundRavenId) {
        try {
            UUID ravenOwner = raven.getOwnerUUID();
            if (ravenOwner == null || !ravenOwner.equals(ownerId)) {
                return false;
            }

            if (isScrollSummonedRaven(raven)) {
                return true;
            }

            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return false;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return false;
            }

            if (ffTag.getBoolean(NBT_SCROLL_SUMMONED)) {
                return true;
            }

            if (ffTag.contains(NBT_SCROLL_SUMMONED_OWNER, Tag.TAG_STRING)) {
                String ownerStr = ffTag.getString(NBT_SCROLL_SUMMONED_OWNER);
                if (ownerId.toString().equals(ownerStr)) {
                    return true;
                }
            }

            if (boundRavenId != null && ffTag.hasUUID(NBT_BOUND_RAVEN_ID)) {
                UUID boundId = ffTag.getUUID(NBT_BOUND_RAVEN_ID);
                return boundRavenId.equals(boundId);
            }
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] isScrollSummonedForOwner failed safely for id={}: {}",
                    raven.getId(), t.toString());
        }
        return false;
    }

    /**
     * Return all ravens in a radius around the player that:
     *  - Are owned by this player, AND
     *  - Are identified as scroll-summoned (scoreboard tag or NBT), or
     *    match the bound raven id (fallback for legacy tag loss).
     */
    private static List<RavenEntity> findScrollSummonedRavensForPlayer(@NotNull ServerLevel level,
                                                                       @NotNull ServerPlayer owner,
                                                                       @Nullable UUID boundRavenId) {
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
                if (isScrollSummonedForOwner(raven, ownerId, boundRavenId)) {
                    out.add(raven);
                }
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

    @Nullable
    private static Vec3 findSpawnNearPlayer(@NotNull ServerLevel level, @NotNull ServerPlayer owner) {
        try {
            int minY = level.getMinBuildHeight() + 1;
            int maxY = level.getMaxBuildHeight() - 2;

            int baseY = Mth.clamp(owner.blockPosition().getY() + 1, minY, maxY);

            Vec3 look = owner.getLookAngle();
            double lx = look.x;
            double lz = look.z;
            double len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1.0E-4D) {
                lx = 1.0D;
                lz = 0.0D;
            } else {
                lx /= len;
                lz /= len;
            }

            double frontDist = 2.8D;
            int frontX = Mth.floor(owner.getX() + lx * frontDist);
            int frontZ = Mth.floor(owner.getZ() + lz * frontDist);
            Vec3 frontSpawn = findSpawnNearBase(level, frontX, baseY, frontZ, minY, maxY);
            if (frontSpawn != null) {
                return frontSpawn;
            }

            BlockPos base = owner.blockPosition();
            return findSpawnNearBase(level, base.getX(), baseY, base.getZ(), minY, maxY);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] findSpawnNearPlayer failed safely: {}", t.toString());
        }
        return null;
    }

    @Nullable
    private static Vec3 findSpawnNearBase(@NotNull ServerLevel level,
                                          int baseX,
                                          int baseY,
                                          int baseZ,
                                          int minY,
                                          int maxY) {
        try {
            int[][] offsets = new int[][]{
                    {0, 0, 0},
                    {1, 0, 0},
                    {-1, 0, 0},
                    {0, 0, 1},
                    {0, 0, -1},
                    {1, 0, 1},
                    {-1, 0, -1},
                    {1, 0, -1},
                    {-1, 0, 1},
                    {0, 1, 0},
                    {0, 2, 0}
            };

            for (int[] o : offsets) {
                BlockPos pos = new BlockPos(baseX + o[0], Mth.clamp(baseY + o[1], minY, maxY), baseZ + o[2]);
                if (!level.getWorldBorder().isWithinBounds(pos)) {
                    continue;
                }
                if (level.isEmptyBlock(pos) && level.isEmptyBlock(pos.above())) {
                    return new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Spawn / despawn
    // ---------------------------------------------------------------------

    @Nullable
    private static RavenEntity spawnSummonedRaven(@NotNull ServerLevel level,
                                                  @NotNull ServerPlayer owner,
                                                  @NotNull String ravenName,
                                                  @Nullable UUID boundRavenId,
                                                  @NotNull RavenArmorVisual armorVisual) {
        try {
            despawnAllOwnedRavensBeforeSummon(owner, null, "single-raven pre-spawn cleanup (scroll summon)");

            RavenEntity raven = FFEntities.RAVEN.get().create(level);
            if (raven == null) {
                LOG.error("[TamedRavenScrollWatcher] spawnSummonedRaven: entity factory returned null");
                return null;
            }

            // Spawn right by the owner (no path simulation).
            Vec3 spawnPos = findSpawnNearPlayer(level, owner);
            if (spawnPos == null) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: no valid nearby spawn for player='{}' -> not spawning.",
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
            try {
                raven.setRavenArmorVisual(armorVisual == null ? RavenArmorVisual.NONE : armorVisual);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: setRavenArmorVisual failed safely: {}", t.toString());
            }

            try {
                long now = level.getGameTime();
                float health = TamedRavenPlayerData.applyDespawnedHealthRegenAndGet(owner, now);
                float maxHealth = Math.max(1.0F, raven.getMaxHealth());
                float clamped = Mth.clamp(health, 1.0F, maxHealth);
                raven.setHealth(clamped);
                TamedRavenPlayerData.setStoredRavenHealth(owner, clamped, now);
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: restoring stored health failed safely: {}", t.toString());
            }

            // Arm recall payload with sender's most recent recallable courier job:
            // - Prefer in-flight jobs (active delivery) and reserve them for recall.
            // - Else prefer queued jobs and reserve them for recall.
            // - Else fall back to already-failed jobs.
            try {
                RavenCourierData courierData = RavenCourierData.get(level);
                RavenCourierData.DeliveryJob recallJob = null;
                String recallMode = "failed";

                RavenCourierData.DeliveryJob inFlightJob = courierData.getMostRecentInFlightJobForSender(owner.getUUID());
                if (inFlightJob != null && inFlightJob.recipientUuid != null) {
                    boolean marked = courierData.markJobFailed(
                            inFlightJob.jobId,
                            inFlightJob.recipientUuid,
                            "sender_recall_requested",
                            level.getGameTime()
                    );
                    if (marked) {
                        recallJob = courierData.getJobById(inFlightJob.jobId);
                        recallMode = "in_flight_marked_failed";
                    } else {
                        LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: failed to reserve in-flight jobId={} for recall for player='{}'",
                                inFlightJob.jobId, safePlayerName(owner));
                    }
                }

                if (recallJob == null) {
                    RavenCourierData.DeliveryJob queuedJob = courierData.getMostRecentQueuedJobForSender(owner.getUUID());
                    if (queuedJob != null && queuedJob.recipientUuid != null) {
                        boolean marked = courierData.markJobFailed(
                                queuedJob.jobId,
                                queuedJob.recipientUuid,
                                "sender_recall_requested",
                                level.getGameTime()
                        );
                        if (marked) {
                            recallJob = courierData.getJobById(queuedJob.jobId);
                            recallMode = "queued_marked_failed";
                        } else {
                            LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: failed to reserve queued jobId={} for recall for player='{}'",
                                    queuedJob.jobId, safePlayerName(owner));
                        }
                    }
                }

                if (recallJob == null) {
                    recallJob = courierData.getMostRecentFailedJobForSender(owner.getUUID());
                }

                if (recallJob != null && recallJob.sealedScrollNbt != null && !recallJob.sealedScrollNbt.isEmpty()) {
                    CompoundTag root = raven.getPersistentData();
                    CompoundTag ffTag = root.getCompound(Constants.MOD_ID);

                    ffTag.putBoolean(NBT_RECALL_ACTIVE, true);
                    ffTag.putLong(NBT_RECALL_JOB_ID, recallJob.jobId);
                    ffTag.putString(NBT_RECALL_RECIPIENT_UUID, recallJob.recipientUuid.toString());
                    ffTag.put(NBT_RECALL_SEALED_SCROLL, recallJob.sealedScrollNbt.copy());

                    root.put(Constants.MOD_ID, ffTag);

                    try {
                        raven.setRavenVariant(net.z2six.featheredfriend.entity.raven.RavenVariant.SCROLL);
                    } catch (Throwable t) {
                        LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: setRavenVariant(SCROLL) failed safely: {}", t.toString());
                    }

                    LOG.debug("[TamedRavenScrollWatcher] spawnSummonedRaven: recall armed for player='{}' jobId={} recipient={} mode={}",
                            safePlayerName(owner), recallJob.jobId, recallJob.recipientUuid, recallMode);
                }
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: recall arming failed safely for player='{}': {}",
                        safePlayerName(owner), t.toString());
            }

            // Lifetime tag as before
            try {
                CompoundTag root = raven.getPersistentData();
                CompoundTag ffTag = root.getCompound(Constants.MOD_ID);

                long now = level.getGameTime();
                long despawnAt = now + SCROLL_SUMMON_LIFETIME_TICKS;

                ffTag.putBoolean(NBT_SCROLL_SUMMONED, true);
                ffTag.putString(NBT_SCROLL_SUMMONED_OWNER, owner.getUUID().toString());
                ffTag.putLong(NBT_SCROLL_SUMMONED_DESPAWN_AT, despawnAt);
                if (boundRavenId != null) {
                    ffTag.putUUID(NBT_BOUND_RAVEN_ID, boundRavenId);
                }

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

            try {
                Teleportation tp = raven.getTeleportation();
                if (tp != null) {
                    tp.startFadeInOnly("scroll summon spawn", raven);
                }
            } catch (Throwable t) {
                LOG.warn("[TamedRavenScrollWatcher] spawnSummonedRaven: startFadeInOnly failed safely: {}",
                        t.toString());
            }

            level.addFreshEntity(raven);

            playScrollSummonSpawnFx(level, owner, raven);
            LOG.debug("[TamedRavenScrollWatcher] spawnSummonedRaven: spawned id={} name='{}' for player='{}' at {}",
                    raven.getId(), ravenName, safePlayerName(owner), raven.position());
            logPlayer(
                    owner,
                    RavenLogCategory.SUMMON,
                    "log.featheredfriend.summon.spawned_at",
                    formatVec(raven.position())
            );

            return raven;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] spawnSummonedRaven failed safely", t);
            return null;
        }
    }

    public static void despawnAllOwnedRavensBeforeSummon(@NotNull ServerPlayer owner,
                                                         @Nullable UUID keepRavenUuid,
                                                         @NotNull String reason) {
        try {
            MinecraftServer server = owner.server;
            if (server == null) {
                return;
            }

            UUID ownerId = owner.getUUID();
            Map<UUID, RavenEntity> byId = new ConcurrentHashMap<>();

            // Load owner-registered Raven Chest chunks and collect nearby owner ravens.
            collectOwnerRavensNearRegisteredChests(server, ownerId, byId);

            // Scan all currently loaded owner ravens across dimensions.
            collectLoadedOwnerRavens(server, ownerId, byId);

            for (RavenEntity raven : byId.values()) {
                if (raven == null || !raven.isAlive() || raven.isRemoved()) {
                    continue;
                }
                if (keepRavenUuid != null && keepRavenUuid.equals(raven.getUUID())) {
                    continue;
                }
                forceDespawnOwnerRavenNow(owner, raven, reason);
            }
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] despawnAllOwnedRavensBeforeSummon failed safely for player='{}': {}",
                    safePlayerName(owner), t.toString());
        }
    }

    private static void collectOwnerRavensNearRegisteredChests(@NotNull MinecraftServer server,
                                                               @NotNull UUID ownerId,
                                                               @NotNull Map<UUID, RavenEntity> out) {
        try {
            for (ServerLevel level : server.getAllLevels()) {
                RavenChestRegistryData data = RavenChestRegistryData.get(level);
                List<RavenChestRegistryData.ChestRecord> records = data.getChestsForOwner(ownerId);
                if (records == null || records.isEmpty()) {
                    continue;
                }

                for (RavenChestRegistryData.ChestRecord record : records) {
                    if (record == null || record.dimensionId() == null || record.dimensionId().isBlank()) {
                        continue;
                    }

                    ResourceLocation dimLoc = ResourceLocation.tryParse(record.dimensionId());
                    if (dimLoc == null) {
                        continue;
                    }
                    ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
                    ServerLevel targetLevel = server.getLevel(dimKey);
                    if (targetLevel == null) {
                        continue;
                    }

                    BlockPos chestPos = BlockPos.of(record.blockPos());
                    targetLevel.getChunk(chestPos.getX() >> 4, chestPos.getZ() >> 4);
                    AABB box = new AABB(chestPos).inflate(8.0D, 4.0D, 8.0D);

                    List<RavenEntity> near = targetLevel.getEntitiesOfClass(
                            RavenEntity.class,
                            box,
                            e -> e != null
                                    && e.isAlive()
                                    && !e.isRemoved()
                                    && ownerId.equals(e.getOwnerUUID())
                    );
                    for (RavenEntity raven : near) {
                        out.putIfAbsent(raven.getUUID(), raven);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collectLoadedOwnerRavens(@NotNull MinecraftServer server,
                                                 @NotNull UUID ownerId,
                                                 @NotNull Map<UUID, RavenEntity> out) {
        try {
            for (ServerLevel level : server.getAllLevels()) {
                WorldBorder border = level.getWorldBorder();
                double cx = border.getCenterX();
                double cz = border.getCenterZ();
                double half = Math.min(border.getSize() * 0.5D, 30_000_000D);
                AABB worldLoadedBox = new AABB(
                        cx - half,
                        level.getMinBuildHeight(),
                        cz - half,
                        cx + half,
                        level.getMaxBuildHeight(),
                        cz + half
                );

                List<RavenEntity> ravens = level.getEntitiesOfClass(
                        RavenEntity.class,
                        worldLoadedBox,
                        e -> e != null
                                && e.isAlive()
                                && !e.isRemoved()
                                && ownerId.equals(e.getOwnerUUID())
                );
                for (RavenEntity raven : ravens) {
                    out.putIfAbsent(raven.getUUID(), raven);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void forceDespawnOwnerRavenNow(@NotNull ServerPlayer owner,
                                                  @NotNull RavenEntity raven,
                                                  @NotNull String reason) {
        try {
            if (!(raven.level() instanceof ServerLevel serverLevel)) {
                raven.discard();
                return;
            }

            try {
                Vec3 fxPos = raven.position().add(0.0D, 0.6D, 0.0D);
                long fxSeed =
                        raven.getUUID().getLeastSignificantBits()
                                ^ (long) raven.tickCount
                                ^ 0x42A5E61DL;
                Teleportation teleportFx = new Teleportation(raven);
                teleportFx.spawnEnderpopBurst(
                        serverLevel,
                        fxPos.x,
                        fxPos.y,
                        fxPos.z,
                        fxSeed,
                        "single-raven-force-despawn: " + reason,
                        raven
                );
            } catch (Throwable ignored) {
            }

            logPlayer(
                    owner,
                    RavenLogCategory.SUMMON,
                    "log.featheredfriend.summon.force_despawn_before_new",
                    reason
            );
            clearSingleRavenTrackingFlags(raven);
            raven.discard();
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] forceDespawnOwnerRavenNow failed safely for owner='{}' ravenId={}: {}",
                    safePlayerName(owner), raven.getId(), t.toString());
            try {
                clearSingleRavenTrackingFlags(raven);
                raven.discard();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void clearSingleRavenTrackingFlags(@NotNull RavenEntity raven) {
        try {
            if (raven.getTags().contains(TAG_SCROLL_SUMMONED)) {
                raven.removeTag(TAG_SCROLL_SUMMONED);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (raven.getTags().contains(TAG_COURIER_RAVEN)) {
                raven.removeTag(TAG_COURIER_RAVEN);
            }
        } catch (Throwable ignored) {
        }

        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return;
            }

            ffTag.remove(NBT_SCROLL_SUMMONED);
            ffTag.remove(NBT_SCROLL_SUMMONED_OWNER);
            ffTag.remove(NBT_SCROLL_SUMMONED_DESPAWN_AT);
            ffTag.remove(NBT_SCROLL_SUMMON_LINK_PAUSE_LAST_TICK);
            ffTag.remove(NBT_BOUND_RAVEN_ID);

            ffTag.remove(NBT_RAVEN_CHEST_PERCH_ASSIGNED);
            ffTag.remove(NBT_RAVEN_CHEST_PERCH_DIMENSION);
            ffTag.remove(NBT_RAVEN_CHEST_PERCH_BLOCK_POS);

            ffTag.remove(NBT_COURIER_ACTIVE);
            ffTag.remove(NBT_COURIER_JOB_ID);
            ffTag.remove(NBT_COURIER_SENDER_UUID);
            ffTag.remove(NBT_COURIER_RECIPIENT_UUID);
            ffTag.remove(NBT_COURIER_DESPAWN_AT);
            ffTag.remove(NBT_COURIER_LINK_PAUSE_LAST_TICK);

            root.put(Constants.MOD_ID, ffTag);
        } catch (Throwable ignored) {
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
                        long nowMillis = System.currentTimeMillis();
                        long cooldownRemainingMs = getScrollDeliveryCooldownRemainingMillis(player, nowMillis);
                        if (cooldownRemainingMs > 0L) {
                            long remainingSeconds = Math.max(1L, (cooldownRemainingMs + 999L) / 1000L);
                            player.sendSystemMessage(Component.translatable(
                                    "message.featheredfriend.courier.dispatch.cooldown",
                                    remainingSeconds
                            ));
                            logPlayer(
                                    player,
                                    RavenLogCategory.COURIER,
                                    "log.featheredfriend.courier.create_aborted.cooldown",
                                    remainingSeconds
                            );
                        } else {
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
                            setLastScrollDeliveryAtMillis(player, nowMillis);
                        } else {
                            LOG.warn("[TamedRavenScrollWatcher] RecallInteract: failed to create NEW job from held sealed scroll for player='{}'",
                                    safePlayerName(player));
                        }
                        }
                    } catch (Throwable t) {
                        LOG.warn("[TamedRavenScrollWatcher] RecallInteract: createJobFromSealedScroll threw safely for player='{}': {}",
                                safePlayerName(player), t.toString());
                    }
                }

                // 4) Despawn the scroll-summoned raven (always) after recall interaction
                try {
                    despawnOneScrollSummonedRaven(level, player, raven, "recall-complete", false);
                } catch (Throwable t) {
                    LOG.warn("[TamedRavenScrollWatcher] RecallInteract: despawn failed safely for raven id={}: {}",
                            raven.getId(), t.toString());
                }

                return InteractionResult.CONSUME;
            }

            // No recall payload -> fall back to original behavior:
            // 1) Sealed scroll in-hand -> existing courier-dispatch behavior.
            // 2) Ender Eye in-hand -> open Raven Chest picker for chest perch assignment.
            // 3) Empty hand/Enderpack in-hand + Enderpack available -> open Raven Chest picker for deposit.
            ItemStack stack = player.getItemInHand(hand);
            if (isSealedScrollStack(stack)) {
                return handleSealedScrollInteract(raven, player, hand);
            }

            if (stack != null && !stack.isEmpty() && stack.is(Items.ENDER_EYE)) {
                List<RavenChestChoiceInfo> chestChoices = collectValidRavenChestChoices(level, player);
                if (chestChoices.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.none_registered"));
                    return InteractionResult.CONSUME;
                }
                Services.PLATFORM.sendOpenRavenChestSelectScreen(
                        player,
                        raven.getId(),
                        chestChoices,
                        RavenChestSelectAction.PERCH_ASSIGNMENT
                );
                return InteractionResult.CONSUME;
            }

            if ((stack == null || stack.isEmpty()) && player.isCrouching()) {
                tryUnequipRavenArmorToOwner(player, raven);
                return InteractionResult.CONSUME;
            }

            if (stack != null && !stack.isEmpty() && !FFItems.isEnderpack(stack)) {
                return InteractionResult.PASS;
            }

            if (!Services.PLATFORM.hasAccessibleEnderpack(player)) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.enderpack.none_found"));
                return InteractionResult.CONSUME;
            }

            List<RavenChestChoiceInfo> chestChoices = collectValidRavenChestChoices(level, player);
            if (chestChoices.isEmpty()) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.none_registered"));
                return InteractionResult.CONSUME;
            }

            Services.PLATFORM.sendOpenRavenChestSelectScreen(
                    player,
                    raven.getId(),
                    chestChoices,
                    RavenChestSelectAction.ENDERPACK_DEPOSIT
            );
            return InteractionResult.CONSUME;

        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] handleScrollSummonedRavenInteract failed safely", t);
            return InteractionResult.PASS;
        }
    }

    private static boolean tryUnequipRavenArmorToOwner(@NotNull ServerPlayer player,
                                                       @NotNull RavenEntity raven) {
        try {
            RavenArmorVisual equipped = raven.getRavenArmorVisual();
            if (equipped == null || equipped == RavenArmorVisual.NONE) {
                return false;
            }

            raven.setRavenArmorVisual(RavenArmorVisual.NONE);
            TamedRavenPlayerData.setEquippedArmorVisual(player, RavenArmorVisual.NONE);

            ItemStack previousArmor = FFItems.createRavenArmorStack(equipped);
            if (!previousArmor.isEmpty()) {
                boolean added = player.getInventory().add(previousArmor);
                if (!added) {
                    raven.spawnAtLocation(previousArmor);
                }
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] tryUnequipRavenArmorToOwner failed safely for player='{}' ravenId={}: {}",
                    safePlayerName(player), raven.getId(), t.toString());
            return false;
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
                                                      @NotNull String reason,
                                                      boolean spawnFeathers) {
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
                    tamedModule.beginDespawnWithFx(level, owner, ravenName, spawnFeathers);
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

            logPlayer(
                    owner,
                    RavenLogCategory.SUMMON,
                    "log.featheredfriend.summon.despawned_reason",
                    reason
            );

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
                        ffTag.remove(NBT_SCROLL_SUMMONED);
                        ffTag.remove(NBT_SCROLL_SUMMONED_OWNER);
                        ffTag.remove(NBT_SCROLL_SUMMONED_DESPAWN_AT);
                        ffTag.remove(NBT_SCROLL_SUMMON_LINK_PAUSE_LAST_TICK);
                        ffTag.remove(NBT_BOUND_RAVEN_ID);
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
                    // Spawn at pocket center
                    return pocketUnderCeiling;
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
                return pocket;
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
            // We require a 3x3x3 air pocket (not 3x3x2) so the raven can safely spawn under low ceilings.
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

            long nowMillis = System.currentTimeMillis();
            long cooldownRemainingMs = getScrollDeliveryCooldownRemainingMillis(serverPlayer, nowMillis);
            if (cooldownRemainingMs > 0L) {
                long remainingSeconds = Math.max(1L, (cooldownRemainingMs + 999L) / 1000L);
                serverPlayer.sendSystemMessage(Component.translatable(
                        "message.featheredfriend.courier.dispatch.cooldown",
                        remainingSeconds
                ));
                logPlayer(
                        serverPlayer,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.create_aborted.cooldown",
                        remainingSeconds
                );
                return InteractionResult.CONSUME;
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
                logPlayer(
                        serverPlayer,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.create_failed_from_scroll"
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
            setLastScrollDeliveryAtMillis(serverPlayer, nowMillis);
            logPlayer(
                    serverPlayer,
                    RavenLogCategory.COURIER,
                    "log.featheredfriend.courier.job_created_for_recipient",
                    job.jobId,
                    job.recipientName
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

            // 4) If this job came from a Raven Chest perch assignment, keep the raven perched
            // with the scroll while the job is queued/offline. It will be dispatched by runtime.
            if (job.hasSenderPerchAssignment()) {
                logPlayer(
                        serverPlayer,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.raven_waiting_on_perch",
                        job.jobId
                );
            } else {
                // Non-perch flow: trigger fade-out / despawn FX now.
                try {
                    despawnOneScrollSummonedRaven(
                            serverLevel,
                            serverPlayer,
                            raven,
                            "courier-dispatch: sealed scroll accepted",
                            false
                    );
                } catch (Throwable t) {
                    LOG.error("[TamedRavenScrollWatcher] handleSealedScrollInteract: despawnOneScrollSummonedRaven failed safely for id={}: {}",
                            raven.getId(),
                            t.toString());
                    // Fail-safe: if FX despawn fails, we do NOT forcibly discard here,
                    // so the raven remains in-world rather than causing a hard state mismatch.
                }

                logPlayer(
                        serverPlayer,
                        RavenLogCategory.COURIER,
                        "log.featheredfriend.courier.raven_departed_with_scroll"
                );
            }

            // We fully handled this interaction: the scroll was turned into a courier job,
            // the raven swapped to SCROLL variant and either remained perched or began fade-out.
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
     *  - If they don't have a stored tamed raven -> tells them and does nothing.
     *  - If any scroll-summoned raven exists -> despawns all of them (toggle off).
     *  - If none exist -> spawns one (toggle on).
     */
    public static void handleWhistleSummonRequest(@NotNull ServerPlayer serverPlayer) {
        try {
            ServerLevel serverLevel = serverPlayer.serverLevel();
            long nowGameTime = serverLevel.getGameTime();
            TamedRavenPlayerData.applyDespawnedHealthRegenAndGet(serverPlayer, nowGameTime);

            TamedRavenPlayerData.TamedRavenInfo info = TamedRavenPlayerData.getTamedRavenInfo(serverPlayer);
            boolean hasTamedRaven = info != null && info.hasTamedRaven();
            String ravenName = (info != null && info.ravenName() != null && !info.ravenName().isEmpty())
                    ? info.ravenName()
                    : Component.translatable("entity.featheredfriend.raven").getString();
            RavenArmorVisual armorVisual = (info != null && info.armorVisual() != null)
                    ? info.armorVisual()
                    : RavenArmorVisual.NONE;
            UUID boundRavenId = (info != null) ? info.boundRavenId() : null;
            if (hasTamedRaven && boundRavenId == null) {
                boundRavenId = TamedRavenPlayerData.ensureBoundRavenId(serverPlayer, UUID.randomUUID());
            }

            if (!hasTamedRaven) {
                serverPlayer.sendSystemMessage(
                        Component.translatable("message.featheredfriend.whistle.no_raven")
                );
                LOG.debug("[TamedRavenScrollWatcher] Whistle request denied: player='{}' has no stored tamed raven.",
                        safePlayerName(serverPlayer));
                logPlayer(
                        serverPlayer,
                        RavenLogCategory.SUMMON,
                        "log.featheredfriend.summon.whistle_failed_no_raven"
                );
                return;
            }

            List<RavenEntity> scrollRavens = findScrollSummonedRavensForPlayer(serverLevel, serverPlayer, boundRavenId);

            if (!scrollRavens.isEmpty()) {
                for (RavenEntity r : scrollRavens) {
                    despawnOneScrollSummonedRaven(serverLevel, serverPlayer, r, "manual whistle toggle-off", false);
                }
                LOG.debug("[TamedRavenScrollWatcher] Whistle: toggled OFF {} scroll-summoned raven(s) for player='{}'",
                        scrollRavens.size(), safePlayerName(serverPlayer));
                logPlayer(
                        serverPlayer,
                        RavenLogCategory.SUMMON,
                        "log.featheredfriend.summon.whistle_toggled_off",
                        scrollRavens.size()
                );
                return;
            }

            RavenEntity spawned = spawnSummonedRaven(serverLevel, serverPlayer, ravenName, boundRavenId, armorVisual);
            if (spawned != null) {
                LOG.debug("[TamedRavenScrollWatcher] Whistle: spawned scroll-raven id={} for player='{}' at {}",
                        spawned.getId(), safePlayerName(serverPlayer), spawned.position());
                logPlayer(
                        serverPlayer,
                        RavenLogCategory.SUMMON,
                        "log.featheredfriend.summon.whistle_spawned_at",
                        formatVec(spawned.position())
                );
            } else {
                logPlayer(
                        serverPlayer,
                        RavenLogCategory.SUMMON,
                        "log.featheredfriend.summon.whistle_spawn_failed"
                );
            }

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

    public static void handleRavenChestLabelSubmission(@NotNull ServerPlayer player,
                                                       @NotNull String dimensionId,
                                                       long blockPos,
                                                       @NotNull String label) {
        try {
            ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionId);
            if (dimLoc == null) {
                return;
            }
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
            ServerLevel targetLevel = player.server.getLevel(dimKey);
            if (targetLevel == null) {
                return;
            }

            RavenChestRegistryData data = RavenChestRegistryData.get(targetLevel);
            boolean updated = data.setLabel(player.getUUID(), dimensionId, blockPos, label == null ? "" : label.trim());
            if (!updated) {
                LOG.debug("[TamedRavenScrollWatcher] Label submit denied/ignored for player='{}' chest={} dim={}",
                        safePlayerName(player), blockPos, dimensionId);
            }
        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] handleRavenChestLabelSubmission failed safely", t);
        }
    }

    public static void handleConfirmRavenChestDeposit(@NotNull ServerPlayer player,
                                                      int ravenEntityId,
                                                      @NotNull String dimensionId,
                                                      long blockPos,
                                                      @Nullable RavenChestSelectAction action) {
        try {
            if (ravenEntityId <= 0) {
                return;
            }

            Entity e = player.serverLevel().getEntity(ravenEntityId);
            if (!(e instanceof RavenEntity raven) || !raven.isAlive() || raven.isRemoved()) {
                return;
            }
            if (!isScrollSummonedRaven(raven)) {
                return;
            }

            boolean owner = false;
            try {
                owner = raven.isTame() && raven.isOwnedBy(player);
            } catch (Throwable ignored) {
            }
            if (!owner) {
                return;
            }

            RavenChestSelectAction safeAction = action == null
                    ? RavenChestSelectAction.ENDERPACK_DEPOSIT
                    : action;
            if (safeAction == RavenChestSelectAction.PERCH_ASSIGNMENT) {
                handleConfirmRavenChestPerchAssignment(player, raven, dimensionId, blockPos);
                return;
            }

            long nowMillis = System.currentTimeMillis();
            long cooldownRemainingMs = getEnderpackDepositCooldownRemainingMillis(player, nowMillis);
            if (cooldownRemainingMs > 0L) {
                long remainingSeconds = Math.max(1L, (cooldownRemainingMs + 999L) / 1000L);
                player.sendSystemMessage(Component.translatable(
                        "message.featheredfriend.enderpack.deposit.cooldown",
                        remainingSeconds
                ));
                logPlayer(
                        player,
                        RavenLogCategory.ENDERPACK,
                        "log.featheredfriend.enderpack.deposit.aborted.cooldown",
                        remainingSeconds
                );
                return;
            }

            logPlayer(
                    player,
                    RavenLogCategory.ENDERPACK,
                    "log.featheredfriend.enderpack.deposit.workflow_started"
            );

            if (!Services.PLATFORM.hasAccessibleEnderpack(player)) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.enderpack.none_found"));
                logPlayer(
                        player,
                        RavenLogCategory.ENDERPACK,
                        "log.featheredfriend.enderpack.deposit.aborted.no_accessible_pack"
                );
                return;
            }

            ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionId);
            if (dimLoc == null) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.CHEST, "log.featheredfriend.enderpack.deposit.aborted.invalid_chest_target");
                return;
            }
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
            ServerLevel targetLevel = player.server.getLevel(dimKey);
            if (targetLevel == null) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.CHEST, "log.featheredfriend.enderpack.deposit.aborted.dimension_unavailable");
                return;
            }

            RavenChestRegistryData data = RavenChestRegistryData.get(targetLevel);
            if (!data.isOwnedBy(player.getUUID(), dimensionId, blockPos)) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.CHEST, "log.featheredfriend.enderpack.deposit.aborted.chest_not_owned");
                return;
            }

            List<RavenChestChoiceInfo> choices = collectValidRavenChestChoices(player.serverLevel(), player);
            RavenChestChoiceInfo selected = null;
            for (RavenChestChoiceInfo c : choices) {
                if (c != null && c.matches(dimensionId, blockPos)) {
                    selected = c;
                    break;
                }
            }
            if (selected == null) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.CHEST, "log.featheredfriend.enderpack.deposit.aborted.chest_not_in_registry");
                return;
            }
            if (!selected.available()) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.unavailable_target"));
                logPlayer(player, RavenLogCategory.CHEST, "log.featheredfriend.enderpack.deposit.aborted.chest_unavailable_cap");
                return;
            }

            BlockPos targetPos = BlockPos.of(blockPos);
            targetLevel.getChunk(targetPos.getX() >> 4, targetPos.getZ() >> 4);
            BlockEntity blockEntity = targetLevel.getBlockEntity(targetPos);
            if (!(blockEntity instanceof RavenChestBlockEntity ravenChest)) {
                data.unregisterChest(dimensionId, blockPos);
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.CHEST, "log.featheredfriend.enderpack.deposit.aborted.chest_block_missing");
                return;
            }
            if (raven.level() != targetLevel) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.CHEST, "log.featheredfriend.enderpack.deposit.aborted.raven_wrong_dimension");
                return;
            }

            EnderpackExtraction extraction = extractAccessibleEnderpack(player);
            if (extraction == null) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.enderpack.none_found"));
                logPlayer(player, RavenLogCategory.ENDERPACK, "log.featheredfriend.enderpack.deposit.aborted.extraction_failed");
                return;
            }

            setRavenChestPerchAssignment(raven, dimensionId, blockPos);
            applyRavenChestPerchPose(targetLevel, raven, targetPos);
            raven.setPersistenceRequired();

            EnderpackDepositWorkflow workflow = new EnderpackDepositWorkflow();
            workflow.ravenUuid = raven.getUUID();
            workflow.ownerUuid = player.getUUID();
            workflow.chestDimensionId = dimensionId;
            workflow.chestBlockPos = blockPos;
            workflow.extraction = extraction;
            workflow.depositAtGameTime = targetLevel.getGameTime() + ENDERPACK_WORKFLOW_DEPOSIT_DELAY_TICKS;
            workflow.returnAtGameTime = workflow.depositAtGameTime + ENDERPACK_WORKFLOW_RETURN_DELAY_TICKS;
            workflow.deposited = false;
            workflow.movedItems = 0;
            workflow.invalidTarget = false;

            ENDERPACK_DEPOSIT_WORKFLOWS.put(raven.getUUID(), workflow);
            setLastEnderpackDepositAtMillis(player, nowMillis);
            logPlayer(
                    player,
                    RavenLogCategory.ENDERPACK,
                    "log.featheredfriend.enderpack.deposit.raven_moved_to_chest",
                    BlockPos.of(blockPos).toShortString()
            );
        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] handleConfirmRavenChestDeposit failed safely", t);
        }
    }

    private static long getEnderpackDepositCooldownRemainingMillis(@NotNull ServerPlayer player, long nowMillis) {
        try {
            long cooldownMs = Math.max(0L, (long) Services.PLATFORM.getEnderpackDepositCooldownSeconds() * 1000L);
            if (cooldownMs <= 0L) {
                return 0L;
            }
            long lastAtMs = getLastEnderpackDepositAtMillis(player);
            if (lastAtMs <= 0L) {
                return 0L;
            }
            long elapsed = Math.max(0L, nowMillis - lastAtMs);
            if (elapsed >= cooldownMs) {
                return 0L;
            }
            return cooldownMs - elapsed;
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] getEnderpackDepositCooldownRemainingMillis failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return 0L;
        }
    }

    private static long getLastEnderpackDepositAtMillis(@NotNull ServerPlayer player) {
        try {
            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null || !root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return 0L;
            }
            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || modTag.isEmpty()) {
                return 0L;
            }
            if (!modTag.contains(NBT_ENDERPACK_DEPOSIT_LAST_AT_MS, Tag.TAG_LONG)) {
                return 0L;
            }
            return Math.max(0L, modTag.getLong(NBT_ENDERPACK_DEPOSIT_LAST_AT_MS));
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] getLastEnderpackDepositAtMillis failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return 0L;
        }
    }

    private static void setLastEnderpackDepositAtMillis(@NotNull ServerPlayer player, long whenMillis) {
        try {
            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null) {
                return;
            }
            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            modTag.putLong(NBT_ENDERPACK_DEPOSIT_LAST_AT_MS, Math.max(0L, whenMillis));
            root.put(Constants.MOD_ID, modTag);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] setLastEnderpackDepositAtMillis failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
        }
    }

    private static long getScrollDeliveryCooldownRemainingMillis(@NotNull ServerPlayer player, long nowMillis) {
        try {
            long cooldownMs = Math.max(0L, (long) Services.PLATFORM.getScrollDeliveryCooldownSeconds() * 1000L);
            if (cooldownMs <= 0L) {
                return 0L;
            }
            long lastAtMs = getLastScrollDeliveryAtMillis(player);
            if (lastAtMs <= 0L) {
                return 0L;
            }
            long elapsed = Math.max(0L, nowMillis - lastAtMs);
            if (elapsed >= cooldownMs) {
                return 0L;
            }
            return cooldownMs - elapsed;
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] getScrollDeliveryCooldownRemainingMillis failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return 0L;
        }
    }

    private static long getLastScrollDeliveryAtMillis(@NotNull ServerPlayer player) {
        try {
            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null || !root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return 0L;
            }
            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || modTag.isEmpty()) {
                return 0L;
            }
            if (!modTag.contains(NBT_SCROLL_DELIVERY_LAST_AT_MS, Tag.TAG_LONG)) {
                return 0L;
            }
            return Math.max(0L, modTag.getLong(NBT_SCROLL_DELIVERY_LAST_AT_MS));
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] getLastScrollDeliveryAtMillis failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return 0L;
        }
    }

    private static void setLastScrollDeliveryAtMillis(@NotNull ServerPlayer player, long whenMillis) {
        try {
            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null) {
                return;
            }
            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            modTag.putLong(NBT_SCROLL_DELIVERY_LAST_AT_MS, Math.max(0L, whenMillis));
            root.put(Constants.MOD_ID, modTag);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] setLastScrollDeliveryAtMillis failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
        }
    }

    @Nullable
    private static EnderpackExtraction extractAccessibleEnderpack(@NotNull ServerPlayer player) {
        try {
            ItemStack main = player.getMainHandItem();
            if (FFItems.isEnderpack(main)) {
                ItemStack extracted = main.copy();
                extracted.setCount(1);
                main.shrink(1);
                if (main.isEmpty()) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                }
                return new EnderpackExtraction(extracted, EnderpackReturnTargetKind.INVENTORY, null, -1);
            }

            ItemStack off = player.getOffhandItem();
            if (FFItems.isEnderpack(off)) {
                ItemStack extracted = off.copy();
                extracted.setCount(1);
                off.shrink(1);
                if (off.isEmpty()) {
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                }
                return new EnderpackExtraction(extracted, EnderpackReturnTargetKind.INVENTORY, null, -1);
            }

            for (int i = 0; i < player.getInventory().items.size(); i++) {
                ItemStack stack = player.getInventory().items.get(i);
                if (!FFItems.isEnderpack(stack)) {
                    continue;
                }
                ItemStack extracted = stack.copy();
                extracted.setCount(1);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    player.getInventory().items.set(i, ItemStack.EMPTY);
                }
                return new EnderpackExtraction(extracted, EnderpackReturnTargetKind.INVENTORY, null, -1);
            }

            return extractEnderpackFromCurios(player);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] extractAccessibleEnderpack failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return null;
        }
    }

    @Nullable
    private static EnderpackExtraction extractEnderpackFromCurios(@NotNull ServerPlayer player) {
        if (!Services.PLATFORM.isModLoaded("curios")) {
            return null;
        }

        try {
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Method getCuriosInventory = curiosApiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            @SuppressWarnings("unchecked")
            Optional<Object> curiosInventory = (Optional<Object>) getCuriosInventory.invoke(null, player);
            if (curiosInventory.isEmpty()) {
                return null;
            }

            Object curiosHandler = curiosInventory.get();
            Method findCurios = curiosHandler.getClass().getMethod("findCurios", Predicate.class);
            Method setEquippedCurio = curiosHandler.getClass()
                    .getMethod("setEquippedCurio", String.class, int.class, ItemStack.class);

            Class<?> slotResultClass = Class.forName("top.theillusivec4.curios.api.SlotResult");
            Method slotContextMethod = slotResultClass.getMethod("slotContext");
            Method stackMethod = slotResultClass.getMethod("stack");

            Class<?> slotContextClass = Class.forName("top.theillusivec4.curios.api.SlotContext");
            Method identifierMethod = slotContextClass.getMethod("identifier");
            Method indexMethod = slotContextClass.getMethod("index");

            @SuppressWarnings("unchecked")
            List<Object> results = (List<Object>) findCurios.invoke(
                    curiosHandler,
                    (Predicate<ItemStack>) stack -> stack != null && !stack.isEmpty() && FFItems.isEnderpack(stack)
            );

            if (results == null || results.isEmpty()) {
                return null;
            }

            for (Object result : results) {
                if (result == null) {
                    continue;
                }
                Object slotContext = slotContextMethod.invoke(result);
                ItemStack stack = (ItemStack) stackMethod.invoke(result);
                if (!FFItems.isEnderpack(stack)) {
                    continue;
                }

                String identifier = String.valueOf(identifierMethod.invoke(slotContext));
                int index = ((Integer) indexMethod.invoke(slotContext)).intValue();
                if (identifier == null || identifier.isBlank() || index < 0) {
                    continue;
                }

                ItemStack extracted = stack.copy();
                extracted.setCount(1);
                setEquippedCurio.invoke(curiosHandler, identifier, index, ItemStack.EMPTY);
                return new EnderpackExtraction(extracted, EnderpackReturnTargetKind.CURIOS, identifier, index);
            }
            return null;
        } catch (Throwable t) {
            LOG.debug("[TamedRavenScrollWatcher] extractEnderpackFromCurios unavailable for player='{}': {}",
                    safePlayerName(player), t.toString());
            return null;
        }
    }

    private static int transferEnderpackContentsIntoContainer(@NotNull ItemStack enderpackStack,
                                                              @NotNull net.minecraft.world.Container container,
                                                              @NotNull HolderLookup.Provider registries) {
        try {
            if (!FFItems.isEnderpack(enderpackStack)) {
                return 0;
            }
            List<ItemStack> packStacks = new ArrayList<>(EnderpackStorage.load(enderpackStack, registries));
            if (packStacks.isEmpty()) {
                return 0;
            }

            int moved = moveStacksIntoContainer(packStacks, container);
            EnderpackStorage.save(enderpackStack, packStacks, registries);
            container.setChanged();
            return Math.max(0, moved);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] transferEnderpackContentsIntoContainer failed safely: {}", t.toString());
            return 0;
        }
    }

    private static int moveStacksIntoContainer(@NotNull List<ItemStack> sourceStacks,
                                               @NotNull net.minecraft.world.Container container) {
        int moved = 0;
        int slots = container.getContainerSize();

        for (int i = 0; i < sourceStacks.size(); i++) {
            ItemStack remaining = sourceStacks.get(i);
            if (remaining == null || remaining.isEmpty()) {
                sourceStacks.set(i, ItemStack.EMPTY);
                continue;
            }

            ItemStack work = remaining.copy();

            for (int slot = 0; slot < slots && !work.isEmpty(); slot++) {
                ItemStack target = container.getItem(slot);
                if (target.isEmpty()) {
                    continue;
                }
                if (!ItemStack.isSameItemSameComponents(target, work)) {
                    continue;
                }

                int max = Math.min(target.getMaxStackSize(), container.getMaxStackSize());
                int room = max - target.getCount();
                if (room <= 0) {
                    continue;
                }

                int toMove = Math.min(room, work.getCount());
                if (toMove <= 0) {
                    continue;
                }

                target.grow(toMove);
                work.shrink(toMove);
                moved += toMove;
                container.setItem(slot, target);
            }

            for (int slot = 0; slot < slots && !work.isEmpty(); slot++) {
                ItemStack target = container.getItem(slot);
                if (!target.isEmpty()) {
                    continue;
                }

                int toMove = Math.min(work.getCount(), Math.min(work.getMaxStackSize(), container.getMaxStackSize()));
                if (toMove <= 0) {
                    continue;
                }

                ItemStack placed = work.copy();
                placed.setCount(toMove);
                container.setItem(slot, placed);
                work.shrink(toMove);
                moved += toMove;
            }

            sourceStacks.set(i, work.isEmpty() ? ItemStack.EMPTY : work);
        }

        return moved;
    }

    private static void returnExtractedEnderpack(@NotNull ServerPlayer player,
                                                 @Nullable EnderpackExtraction extraction,
                                                 @NotNull Vec3 dropPos) {
        if (extraction == null) {
            return;
        }

        ItemStack stackToReturn = extraction.enderpackStack.copy();
        if (stackToReturn.isEmpty()) {
            return;
        }

        if (extraction.returnTargetKind == EnderpackReturnTargetKind.CURIOS) {
            boolean restored = tryReturnEnderpackToCuriosSlot(
                    player,
                    extraction.curiosIdentifier,
                    extraction.curiosIndex,
                    stackToReturn.copy()
            );
            if (restored) {
                return;
            }
        }

        giveOrDropNearPlayer(player, stackToReturn, dropPos);
    }

    private static boolean tryReturnEnderpackToCuriosSlot(@NotNull ServerPlayer player,
                                                          @Nullable String identifier,
                                                          int index,
                                                          @NotNull ItemStack stack) {
        if (stack.isEmpty() || identifier == null || identifier.isBlank() || index < 0) {
            return false;
        }
        if (!Services.PLATFORM.isModLoaded("curios")) {
            return false;
        }

        try {
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Method getCuriosInventory = curiosApiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            @SuppressWarnings("unchecked")
            Optional<Object> curiosInventory = (Optional<Object>) getCuriosInventory.invoke(null, player);
            if (curiosInventory.isEmpty()) {
                return false;
            }

            Object curiosHandler = curiosInventory.get();
            Method getStacksHandler = curiosHandler.getClass().getMethod("getStacksHandler", String.class);
            @SuppressWarnings("unchecked")
            Optional<Object> stacksHandlerOpt = (Optional<Object>) getStacksHandler.invoke(curiosHandler, identifier);
            if (stacksHandlerOpt.isEmpty()) {
                return false;
            }

            Object stacksHandler = stacksHandlerOpt.get();
            Method getStacks = stacksHandler.getClass().getMethod("getStacks");
            Object dynamicStacks = getStacks.invoke(stacksHandler);
            Method getStackInSlot = dynamicStacks.getClass().getMethod("getStackInSlot", int.class);
            ItemStack current = (ItemStack) getStackInSlot.invoke(dynamicStacks, index);
            if (current != null && !current.isEmpty()) {
                return false;
            }

            Method setEquippedCurio = curiosHandler.getClass()
                    .getMethod("setEquippedCurio", String.class, int.class, ItemStack.class);
            setEquippedCurio.invoke(curiosHandler, identifier, index, stack);
            return true;
        } catch (Throwable t) {
            LOG.debug("[TamedRavenScrollWatcher] tryReturnEnderpackToCuriosSlot failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return false;
        }
    }

    private static void giveOrDropNearPlayer(@NotNull ServerPlayer player,
                                             @NotNull ItemStack stack,
                                             @NotNull Vec3 dropPos) {
        if (stack.isEmpty()) {
            return;
        }

        try {
            ItemStack remaining = stack.copy();
            player.getInventory().add(remaining);
            if (remaining.isEmpty()) {
                return;
            }

            ItemEntity drop = new ItemEntity(
                    player.serverLevel(),
                    dropPos.x,
                    dropPos.y,
                    dropPos.z,
                    remaining
            );
            player.serverLevel().addFreshEntity(drop);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] giveOrDropNearPlayer failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            try {
                player.drop(stack, false);
            } catch (Throwable ignored) {
            }
        }
    }

    @Nullable
    private static RavenEntity spawnReturnRavenForOwner(@NotNull ServerPlayer owner) {
        try {
            ServerLevel level = owner.serverLevel();
            long now = level.getGameTime();
            TamedRavenPlayerData.applyDespawnedHealthRegenAndGet(owner, now);

            TamedRavenPlayerData.TamedRavenInfo info = TamedRavenPlayerData.getTamedRavenInfo(owner);
            if (info == null || !info.hasTamedRaven()) {
                return null;
            }

            String ravenName = (info.ravenName() != null && !info.ravenName().isBlank())
                    ? info.ravenName()
                    : Component.translatable("entity.featheredfriend.raven").getString();
            RavenArmorVisual armorVisual = (info.armorVisual() != null)
                    ? info.armorVisual()
                    : RavenArmorVisual.NONE;
            UUID boundRavenId = info.boundRavenId();
            if (boundRavenId == null) {
                boundRavenId = TamedRavenPlayerData.ensureBoundRavenId(owner, UUID.randomUUID());
            }

            return spawnSummonedRaven(level, owner, ravenName, boundRavenId, armorVisual);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] spawnReturnRavenForOwner failed safely for player='{}': {}",
                    safePlayerName(owner), t.toString());
            return null;
        }
    }

    private static void handleConfirmRavenChestPerchAssignment(@NotNull ServerPlayer player,
                                                               @NotNull RavenEntity raven,
                                                               @NotNull String dimensionId,
                                                               long blockPos) {
        try {
            logPlayer(
                    player,
                    RavenLogCategory.PERCH,
                    "log.featheredfriend.perch.assignment_requested"
            );
            ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionId);
            if (dimLoc == null) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.PERCH, "log.featheredfriend.perch.assignment_failed.invalid_dimension");
                return;
            }
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
            ServerLevel targetLevel = player.server.getLevel(dimKey);
            if (targetLevel == null) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.PERCH, "log.featheredfriend.perch.assignment_failed.level_unavailable");
                return;
            }

            RavenChestRegistryData data = RavenChestRegistryData.get(targetLevel);
            if (!data.isOwnedBy(player.getUUID(), dimensionId, blockPos)) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.PERCH, "log.featheredfriend.perch.assignment_failed.chest_not_owned");
                return;
            }

            List<RavenChestChoiceInfo> choices = collectValidRavenChestChoices(player.serverLevel(), player);
            RavenChestChoiceInfo selected = null;
            for (RavenChestChoiceInfo c : choices) {
                if (c != null && c.matches(dimensionId, blockPos)) {
                    selected = c;
                    break;
                }
            }
            if (selected == null) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.PERCH, "log.featheredfriend.perch.assignment_failed.chest_not_found");
                return;
            }
            if (!selected.available()) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.unavailable_target"));
                logPlayer(player, RavenLogCategory.PERCH, "log.featheredfriend.perch.assignment_failed.chest_unavailable");
                return;
            }

            BlockPos targetPos = BlockPos.of(blockPos);
            targetLevel.getChunk(targetPos.getX() >> 4, targetPos.getZ() >> 4);
            BlockEntity blockEntity = targetLevel.getBlockEntity(targetPos);
            if (!(blockEntity instanceof RavenChestBlockEntity)) {
                data.unregisterChest(dimensionId, blockPos);
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.PERCH, "log.featheredfriend.perch.assignment_failed.chest_block_missing");
                return;
            }
            if (raven.level() != targetLevel) {
                player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.invalid_target"));
                logPlayer(player, RavenLogCategory.PERCH, "log.featheredfriend.perch.assignment_failed.raven_wrong_dimension");
                return;
            }

            if (!consumeEnderEyeFromHands(player)) {
                player.sendSystemMessage(Component.translatable(
                        "message.featheredfriend.raven_chest.perch.requires_ender_eye"
                ));
                logPlayer(player, RavenLogCategory.PERCH, "log.featheredfriend.perch.assignment_failed.no_ender_eye");
                return;
            }

            setRavenChestPerchAssignment(raven, dimensionId, blockPos);
            clearScrollSummonedTracking(raven);

            applyRavenChestPerchPose(targetLevel, raven, targetPos);
            playRavenChestArrivalFx(targetLevel, raven, "perch-assignment-arrive");
            raven.setPersistenceRequired();

            player.sendSystemMessage(Component.translatable("message.featheredfriend.raven_chest.perch.assigned"));
            logPlayer(
                    player,
                    RavenLogCategory.PERCH,
                    "log.featheredfriend.perch.assignment_success",
                    targetPos.toShortString()
            );
        } catch (Throwable t) {
            LOG.error("[TamedRavenScrollWatcher] handleConfirmRavenChestPerchAssignment failed safely", t);
        }
    }

    private static boolean consumeEnderEyeFromHands(@NotNull ServerPlayer player) {
        try {
            ItemStack main = player.getMainHandItem();
            if (main != null && !main.isEmpty() && main.is(Items.ENDER_EYE)) {
                main.shrink(1);
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            ItemStack off = player.getOffhandItem();
            if (off != null && !off.isEmpty() && off.is(Items.ENDER_EYE)) {
                off.shrink(1);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void clearScrollSummonedTracking(@NotNull RavenEntity raven) {
        try {
            if (raven.getTags().contains(TAG_SCROLL_SUMMONED)) {
                raven.removeTag(TAG_SCROLL_SUMMONED);
            }
        } catch (Throwable ignored) {
        }
        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return;
            }
            ffTag.remove(NBT_SCROLL_SUMMONED);
            ffTag.remove(NBT_SCROLL_SUMMONED_OWNER);
            ffTag.remove(NBT_SCROLL_SUMMONED_DESPAWN_AT);
            ffTag.remove(NBT_SCROLL_SUMMON_LINK_PAUSE_LAST_TICK);
            ffTag.remove(NBT_BOUND_RAVEN_ID);
            root.put(Constants.MOD_ID, ffTag);
        } catch (Throwable ignored) {
        }
    }

    private static void setRavenChestPerchAssignment(@NotNull RavenEntity raven,
                                                     @NotNull String dimensionId,
                                                     long blockPos) {
        try {
            CompoundTag root = raven.getPersistentData();
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            ffTag.putBoolean(NBT_RAVEN_CHEST_PERCH_ASSIGNED, true);
            ffTag.putString(NBT_RAVEN_CHEST_PERCH_DIMENSION, dimensionId);
            ffTag.putLong(NBT_RAVEN_CHEST_PERCH_BLOCK_POS, blockPos);
            root.put(Constants.MOD_ID, ffTag);
        } catch (Throwable ignored) {
        }
    }

    private static void applyRavenChestPerchPose(@NotNull ServerLevel targetLevel,
                                                 @NotNull RavenEntity raven,
                                                 @NotNull BlockPos chestPos) {
        float yaw = defaultYawFromChest(targetLevel, chestPos);
        float pitch = 0.0F;
        double x = chestPos.getX() + RAVEN_CHEST_PERCH_OFFSET_X;
        double y = chestPos.getY() + RAVEN_CHEST_PERCH_OFFSET_Y;
        double z = chestPos.getZ() + RAVEN_CHEST_PERCH_OFFSET_Z;

        raven.moveTo(x, y, z, yaw, pitch);
        raven.setYRot(yaw);
        raven.setYHeadRot(yaw);
        raven.yBodyRot = yaw;
        raven.setXRot(pitch);
        raven.setRavenChestPerchLockRotation(yaw, pitch);
        raven.setDeltaMovement(Vec3.ZERO);
        raven.setNoGravity(true);
        raven.setNoAi(false);
        raven.setAIState(RavenAIState.RAVEN_CHEST_PERCH);
        raven.setAnimMode(RavenAnimMode.NO_AIR);
    }

    private static void playRavenChestArrivalFx(@NotNull ServerLevel level,
                                                @NotNull RavenEntity raven,
                                                @NotNull String reason) {
        try {
            Teleportation tp = raven.getTeleportation();
            if (tp == null) {
                return;
            }

            Vec3 fxPos = raven.position().add(0.0D, 0.6D, 0.0D);
            long fxSeed =
                    raven.getUUID().getLeastSignificantBits()
                            ^ (long) raven.tickCount
                            ^ 0x3A7C02D1L;

            tp.startFadeInOnly(reason, raven);
            tp.spawnEnderpopBurst(
                    level,
                    fxPos.x,
                    fxPos.y,
                    fxPos.z,
                    fxSeed,
                    reason,
                    raven
            );
        } catch (Throwable ignored) {
        }
    }

    private static float defaultYawFromChest(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            if (state.hasProperty(RavenChestBlock.FACING)) {
                Direction facing = state.getValue(RavenChestBlock.FACING);
                return Mth.wrapDegrees(facing.toYRot());
            }
        } catch (Throwable ignored) {
        }
        return 0.0F;
    }

    private static @NotNull List<RavenChestChoiceInfo> collectValidRavenChestChoices(@NotNull ServerLevel referenceLevel,
                                                                                      @NotNull ServerPlayer player) {
        try {
            RavenChestRegistryData data = RavenChestRegistryData.get(referenceLevel);
            List<RavenChestRegistryData.ChestRecord> records = data.getChestsForOwner(player.getUUID());
            if (records.isEmpty()) {
                return List.of();
            }

            int cap = 0;
            try {
                cap = Math.max(0, Services.PLATFORM.getMaxRavenChestsPerPlayer());
            } catch (Throwable ignored) {
                cap = 0;
            }

            List<RavenChestChoiceInfo> out = new ArrayList<>();
            int visibleIndex = 0;
            for (RavenChestRegistryData.ChestRecord record : records) {
                if (record == null) {
                    continue;
                }

                String dim = record.dimensionId();
                long pos = record.blockPos();
                if (dim == null || dim.isBlank()) {
                    continue;
                }

                // Keep registered chests visible even if chunks are currently not loaded.
                ResourceLocation dimLoc = ResourceLocation.tryParse(dim);
                if (dimLoc == null) {
                    data.unregisterChest(dim, pos);
                    continue;
                }
                ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
                ServerLevel targetLevel = player.server.getLevel(dimKey);
                if (targetLevel == null) {
                    continue;
                }

                BlockPos blockPosObj = BlockPos.of(pos);
                if (targetLevel.hasChunkAt(blockPosObj)) {
                    BlockEntity be = targetLevel.getBlockEntity(blockPosObj);
                    if (!(be instanceof RavenChestBlockEntity)) {
                        data.unregisterChest(dim, pos);
                        continue;
                    }
                }

                String label = record.label();
                if (label == null || label.isBlank()) {
                    label = Component.translatable("container.featheredfriend.raven_chest").getString();
                }
                boolean available = visibleIndex < cap;
                out.add(new RavenChestChoiceInfo(dim, pos, label, available));
                visibleIndex++;
            }
            return out.isEmpty() ? List.of() : List.copyOf(out);
        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] collectValidRavenChestChoices failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
            return List.of();
        }
    }

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
            if (raven.getTags().contains(TAG_SCROLL_SUMMONED)) {
                return true;
            }

            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return false;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return false;
            }

            return ffTag.getBoolean(NBT_SCROLL_SUMMONED);
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
     *      * the owner is online in this level.
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

            return owner;

        } catch (Throwable t) {
            LOG.warn("[TamedRavenScrollWatcher] getScrollSummonOwnerIfHoldingScroll failed safely for raven id={}: {}",
                    raven.getId(), t.toString());
            return null;
        }
    }

    private static void logPlayer(@NotNull ServerPlayer player,
                                  @NotNull RavenLogCategory category,
                                  @NotNull String key,
                                  @Nullable Object... args) {
        RavenLogService.logForPlayerKey(player, category, key, args);
    }

    private static void logPlayer(@NotNull ServerLevel level,
                                  @Nullable UUID playerUuid,
                                  @NotNull RavenLogCategory category,
                                  @NotNull String key,
                                  @Nullable Object... args) {
        RavenLogService.logForPlayerKey(level, playerUuid, category, key, args);
    }

    private static @NotNull String formatVec(@NotNull Vec3 vec) {
        if (vec == null) {
            return "?, ?, ?";
        }
        return String.format("%.2f, %.2f, %.2f", vec.x, vec.y, vec.z);
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
