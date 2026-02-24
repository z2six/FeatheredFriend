package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.RavenChestBlock;
import net.z2six.featheredfriend.config.FFServerConfig;
import net.z2six.featheredfriend.entity.raven.RavenAIState;
import net.z2six.featheredfriend.entity.raven.RavenAnimMode;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.ravenlink.RavenLinkEffigyEntity;
import net.z2six.featheredfriend.entity.raven.modules.Teleportation;
import net.z2six.featheredfriend.log.FFLogThrottle;
import net.z2six.featheredfriend.log.RavenLogCategory;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.network.RavenLinkEffigyPoseSnapshotPacket;
import net.z2six.featheredfriend.registry.FFEntities;
import net.z2six.featheredfriend.registry.FFBlocks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Server-authoritative Raven Link runtime.
 */
public final class RavenLinkRuntime {

    private static final Logger LOG = LogUtils.getLogger();

    private static final long LINK_DURATION_TICKS = 30L * 20L;
    // Delay actual server-side link start slightly so the client "eyes closing"
    // transition is already fully covering the view before effigy spawn/teleport.
    private static final long LINK_START_DELAY_TICKS = 10L;
    private static final long LINK_START_BLACKOUT_ACK_TIMEOUT_TICKS = 80L;
    private static final long LINK_END_GRACE_FAILSAFE_TICKS = 80L;
    private static final double LINK_HORIZONTAL_SPEED = 0.25D;
    private static final double LINK_VERTICAL_SPEED = 0.18D;
    private static final double LINK_ACCEL_FACTOR = 0.22D;
    private static final double LINK_ACTIVE_DRAG = 0.96D;
    private static final double LINK_IDLE_DRAG = 0.88D;
    private static final double PLAYER_FREEZE_MAX_DRIFT_SQR = 0.05D * 0.05D;
    private static final int MIN_STREAM_VIEW_DISTANCE = 2;
    private static final int MAX_STREAM_VIEW_DISTANCE = 32;
    private static final int STREAM_TICKET_RADIUS = 3;
    private static final int EFFIGY_TICKET_RADIUS = 1;
    private static final int MANUAL_STREAM_MAX_RADIUS = 8;
    private static final int MANUAL_STREAM_CHUNKS_PER_TICK = 18;
    private static final long LINK_INPUT_TIMEOUT_TICKS = 40L;
    private static final long MANUAL_STREAM_FULL_RESYNC_INTERVAL_TICKS = 20L;
    private static final float LINK_OWNER_FLY_SPEED = 0.06F;
    private static final long EFFIGY_POSE_SNAPSHOT_MAX_AGE_TICKS = 40L;

    private static final String TAG_SCROLL_SUMMONED = "ff_scroll_summoned";
    private static final String TAG_COURIER_RAVEN = "ff_courier_raven";
    private static final String TAG_RAVEN_LINK_HIDDEN_OWNER = "ff_raven_link_hidden_owner";

    private static final String NBT_RAVEN_CHEST_PERCH_ASSIGNED = "RavenChestPerchAssigned";
    private static final String NBT_RAVEN_CHEST_PERCH_DIMENSION = "RavenChestPerchDimension";
    private static final String NBT_RAVEN_CHEST_PERCH_BLOCK_POS = "RavenChestPerchBlockPos";

    private static final double RAVEN_CHEST_PERCH_OFFSET_X = 0.5D;
    private static final double RAVEN_CHEST_PERCH_OFFSET_Y = 1.6D;
    private static final double RAVEN_CHEST_PERCH_OFFSET_Z = 0.5D;

    private static final Map<UUID, LinkSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingLinkStart> PENDING_LINK_STARTS = new ConcurrentHashMap<>();
    private static final Map<UUID, EffigyPoseSnapshot> LAST_EFFIGY_POSE_SNAPSHOTS = new ConcurrentHashMap<>();
    private static volatile @Nullable Method APPLY_CHUNK_TRACKING_VIEW_METHOD = null;
    private static volatile boolean APPLY_CHUNK_TRACKING_VIEW_LOOKED_UP = false;
    private static volatile @Nullable Field PLAYER_CHUNK_SENDER_PENDING_CHUNKS_FIELD = null;
    private static volatile @Nullable Field PLAYER_CHUNK_SENDER_UNACKED_BATCHES_FIELD = null;
    private static volatile @Nullable Field PLAYER_CHUNK_SENDER_MAX_UNACKED_BATCHES_FIELD = null;
    private static volatile @Nullable Field PLAYER_CHUNK_SENDER_BATCH_QUOTA_FIELD = null;
    private static volatile @Nullable Field ENTITY_NO_PHYSICS_FIELD = null;
    private static volatile boolean ENTITY_NO_PHYSICS_FIELD_LOOKED_UP = false;
    private static volatile boolean PLAYER_CHUNK_SENDER_REFLECTION_READY = false;
    private static volatile boolean REGISTERED = false;

    private RavenLinkRuntime() {
    }

    public static void register() {
        try {
            if (REGISTERED) {
                return;
            }
            REGISTERED = true;
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onServerTick);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onPlayerLoggedIn);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onPlayerLoggedOut);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onAttackEntity);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onLeftClickBlock);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onRightClickItem);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onRightClickBlock);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onEntityInteract);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onEntityInteractSpecific);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onBreakBlock);
            MinecraftForge.EVENT_BUS.addListener(RavenLinkRuntime::onLivingAttack);
        } catch (Throwable t) {
            LOG.error("[RavenLinkRuntime] register failed safely", t);
        }
    }

    private static boolean isLinkedOwner(@Nullable net.minecraft.world.entity.player.Player player) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return false;
            }
            return ACTIVE_SESSIONS.containsKey(serverPlayer.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void onAttackEntity(@NotNull AttackEntityEvent event) {
        try {
            if (isLinkedOwner(event.getEntity())) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onLeftClickBlock(@NotNull PlayerInteractEvent.LeftClickBlock event) {
        try {
            if (isLinkedOwner(event.getEntity())) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onRightClickItem(@NotNull PlayerInteractEvent.RightClickItem event) {
        try {
            if (isLinkedOwner(event.getEntity())) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onRightClickBlock(@NotNull PlayerInteractEvent.RightClickBlock event) {
        try {
            if (isLinkedOwner(event.getEntity())) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onEntityInteract(@NotNull PlayerInteractEvent.EntityInteract event) {
        try {
            if (isLinkedOwner(event.getEntity())) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onEntityInteractSpecific(@NotNull PlayerInteractEvent.EntityInteractSpecific event) {
        try {
            if (isLinkedOwner(event.getEntity())) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onBreakBlock(@NotNull BlockEvent.BreakEvent event) {
        try {
            if (isLinkedOwner(event.getPlayer())) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onLivingAttack(@NotNull LivingAttackEvent event) {
        try {
            if (!(event.getEntity() instanceof RavenLinkEffigyEntity effigy)) {
                return;
            }
            event.setCanceled(true);
            handleEffigyHit(effigy);
        } catch (Throwable ignored) {
        }
    }

    public static boolean tryStartLink(@NotNull ServerPlayer owner) {
        try {
            if (owner.server == null) {
                return false;
            }

            if (!FFServerConfig.isSuspiciousFeatherEnabled()) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.featheredfriend.feature_disabled.suspicious_feather"
                ));
                return false;
            }

            LinkSession existing = ACTIVE_SESSIONS.get(owner.getUUID());
            PendingLinkStart pendingExisting = PENDING_LINK_STARTS.get(owner.getUUID());
            if (existing != null || pendingExisting != null) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.featheredfriend.raven_link.already_active"
                ));
                return false;
            }

            RavenEntity raven = findBestActiveRavenForOwner(owner.server, owner, false);
            if (raven == null || !raven.isAlive() || raven.isRemoved()) {
                RavenEntity revived = null;
                try {
                    revived = TamedRavenScrollWatcher.tryRevivePerishedRavenForRavenLink(owner);
                } catch (Throwable ignored) {
                }
                if (revived == null || !revived.isAlive() || revived.isRemoved()) {
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "message.featheredfriend.raven_link.no_active_raven"
                    ));
                    return false;
                }
                raven = revived;
            }
            if (raven.level() != owner.level()) {
                RavenEntity sameDimensionRaven = findBestActiveRavenForOwner(owner.server, owner, true);
                if (sameDimensionRaven == null || !sameDimensionRaven.isAlive() || sameDimensionRaven.isRemoved()) {
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "message.featheredfriend.raven_link.cross_dimension_unavailable"
                    ));
                    return false;
                }
                raven = sameDimensionRaven;
            }

            LinkSession session = buildSession(owner, raven);
            ServerLevel tickLevel = owner.server.overworld();
            long now = tickLevel == null ? 0L : tickLevel.getGameTime();
            PENDING_LINK_STARTS.put(owner.getUUID(), new PendingLinkStart(
                    session,
                    now + LINK_START_DELAY_TICKS,
                    now + LINK_START_BLACKOUT_ACK_TIMEOUT_TICKS
            ));

            int durationTicks = safeLinkDurationTicksFromConfig();
            FFNetwork.sendStartRavenLink(
                    owner,
                    raven.getId(),
                    (int) ((long) durationTicks + LINK_START_DELAY_TICKS + LINK_START_BLACKOUT_ACK_TIMEOUT_TICKS),
                    session.ownerAnchorPos.x,
                    session.ownerAnchorPos.y + owner.getEyeHeight(owner.getPose()),
                    session.ownerAnchorPos.z,
                    session.ownerAnchorYaw,
                    session.ownerAnchorPitch
            );
            return true;
        } catch (Throwable t) {
            LOG.warn("[RavenLinkRuntime] tryStartLink failed safely for player='{}': {}",
                    safePlayerName(owner), t.toString());
            return false;
        }
    }

    private static int safeLinkDurationTicksFromConfig() {
        try {
            int seconds = FFServerConfig.getRavenLinkDurationSeconds();
            return Math.max(5, Math.min(600, seconds)) * 20;
        } catch (Throwable ignored) {
            return (int) LINK_DURATION_TICKS;
        }
    }

    private static @Nullable EffigyPoseSnapshot consumeFreshEffigyPoseSnapshot(@NotNull ServerPlayer owner) {
        try {
            UUID ownerId = owner.getUUID();
            EffigyPoseSnapshot snap = LAST_EFFIGY_POSE_SNAPSHOTS.get(ownerId);
            if (snap == null) {
                return null;
            }
            long now = owner.serverLevel().getGameTime();
            if (now - snap.receivedAtGameTime > EFFIGY_POSE_SNAPSHOT_MAX_AGE_TICKS) {
                LAST_EFFIGY_POSE_SNAPSHOTS.remove(ownerId);
                return null;
            }
            LAST_EFFIGY_POSE_SNAPSHOTS.remove(ownerId);
            return snap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void handleClientBlackoutAck(@NotNull ServerPlayer owner) {
        try {
            PendingLinkStart pending = PENDING_LINK_STARTS.get(owner.getUUID());
            if (pending == null) {
                return;
            }
            pending.blackoutAcked = true;
        } catch (Throwable ignored) {
        }
    }

    public static void handleEffigyPoseSnapshot(@NotNull ServerPlayer owner,
                                                @NotNull RavenLinkEffigyPoseSnapshotPacket payload) {
        try {
            long now = owner.serverLevel().getGameTime();
            UUID ownerId = owner.getUUID();

            EffigyPoseSnapshot snapshot = new EffigyPoseSnapshot(
                    payload.headXRot(), payload.headYRot(), payload.headZRot(),
                    payload.bodyXRot(), payload.bodyYRot(), payload.bodyZRot(),
                    payload.rightArmXRot(), payload.rightArmYRot(), payload.rightArmZRot(),
                    payload.leftArmXRot(), payload.leftArmYRot(), payload.leftArmZRot(),
                    payload.rightLegXRot(), payload.rightLegYRot(), payload.rightLegZRot(),
                    payload.leftLegXRot(), payload.leftLegYRot(), payload.leftLegZRot(),
                    now
            );

            LAST_EFFIGY_POSE_SNAPSHOTS.put(ownerId, snapshot);

            PendingLinkStart pending = PENDING_LINK_STARTS.get(ownerId);
            if (pending != null) {
                applySnapshotToSession(pending.session, snapshot);
            }

            LinkSession session = ACTIVE_SESSIONS.get(ownerId);
            if (session != null) {
                applySnapshotToSession(session, snapshot);
                applySnapshotToExistingEffigy(owner.server, session, snapshot);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applySnapshotToSession(@NotNull LinkSession session, @NotNull EffigyPoseSnapshot snapshot) {
        try {
            session.effigyPoseSnapshotPresent = true;
            session.effigyHeadXRot = snapshot.headXRot;
            session.effigyHeadYRot = snapshot.headYRot;
            session.effigyHeadZRot = snapshot.headZRot;
            session.effigyBodyXRot = snapshot.bodyXRot;
            session.effigyBodyYRot = snapshot.bodyYRot;
            session.effigyBodyZRot = snapshot.bodyZRot;
            session.effigyRightArmXRot = snapshot.rightArmXRot;
            session.effigyRightArmYRot = snapshot.rightArmYRot;
            session.effigyRightArmZRot = snapshot.rightArmZRot;
            session.effigyLeftArmXRot = snapshot.leftArmXRot;
            session.effigyLeftArmYRot = snapshot.leftArmYRot;
            session.effigyLeftArmZRot = snapshot.leftArmZRot;
            session.effigyRightLegXRot = snapshot.rightLegXRot;
            session.effigyRightLegYRot = snapshot.rightLegYRot;
            session.effigyRightLegZRot = snapshot.rightLegZRot;
            session.effigyLeftLegXRot = snapshot.leftLegXRot;
            session.effigyLeftLegYRot = snapshot.leftLegYRot;
            session.effigyLeftLegZRot = snapshot.leftLegZRot;
        } catch (Throwable ignored) {
        }
    }

    private static void applySnapshotToExistingEffigy(@Nullable MinecraftServer server,
                                                      @NotNull LinkSession session,
                                                      @NotNull EffigyPoseSnapshot snapshot) {
        try {
            if (server == null || session.effigyUuid == null) {
                return;
            }
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(session.effigyUuid);
                if (e instanceof RavenLinkEffigyEntity effigy) {
                    effigy.setPoseSnapshot(
                            snapshot.headXRot, snapshot.headYRot, snapshot.headZRot,
                            snapshot.bodyXRot, snapshot.bodyYRot, snapshot.bodyZRot,
                            snapshot.rightArmXRot, snapshot.rightArmYRot, snapshot.rightArmZRot,
                            snapshot.leftArmXRot, snapshot.leftArmYRot, snapshot.leftArmZRot,
                            snapshot.rightLegXRot, snapshot.rightLegYRot, snapshot.rightLegZRot,
                            snapshot.leftLegXRot, snapshot.leftLegYRot, snapshot.leftLegZRot
                    );
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void handleLinkInput(@NotNull ServerPlayer owner,
                                       boolean forward,
                                       boolean backward,
                                       boolean left,
                                       boolean right,
                                       boolean ascend,
                                       boolean descend,
                                       float yaw,
                                       float pitch) {
        try {
            LinkSession session = ACTIVE_SESSIONS.get(owner.getUUID());
            if (session == null) {
                return;
            }
            session.input = new LinkInput(
                    forward,
                    backward,
                    left,
                    right,
                    ascend,
                    descend,
                    Mth.wrapDegrees(yaw),
                    Mth.clamp(pitch, -89.9F, 89.9F)
            );
            session.lastInputGameTime = owner.serverLevel().getGameTime();
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.handleLinkInput", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] handleLinkInput failed safely for player='{}'",
                        safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] handleLinkInput failed safely for player='{}': {}",
                        safePlayerName(owner), t.toString());
            }
        }
    }

    public static void stopLinkForOwner(@NotNull ServerPlayer owner, @NotNull String reason) {
        try {
            LinkSession session = ACTIVE_SESSIONS.remove(owner.getUUID());
            if (session == null) {
                PENDING_LINK_STARTS.remove(owner.getUUID());
                discardAnyEffigiesForOwner(owner.server, owner.getUUID());
                return;
            }
            String effectiveReason = reason;
            if (session.endRequested && session.forcedStopReason != null && !session.forcedStopReason.isBlank()) {
                effectiveReason = session.forcedStopReason;
            }
            stopSession(owner.server, owner, session, effectiveReason);
        } catch (Throwable t) {
            LOG.warn("[RavenLinkRuntime] stopLinkForOwner failed safely for player='{}': {}",
                    safePlayerName(owner), t.toString());
        }
    }

    public static boolean isRavenLinked(@NotNull RavenEntity raven) {
        try {
            UUID ravenId = raven.getUUID();
            for (LinkSession s : ACTIVE_SESSIONS.values()) {
                if (s != null && ravenId.equals(s.ravenUuid)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isRavenLinkControlled(@NotNull RavenEntity raven) {
        try {
            if (raven.isRavenLinkControlled()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return isRavenLinked(raven);
    }

    public static void markCourierDeliveryCompleteDeferred(@NotNull RavenEntity raven) {
        try {
            UUID ravenId = raven.getUUID();
            for (LinkSession s : ACTIVE_SESSIONS.values()) {
                if (s == null || !ravenId.equals(s.ravenUuid)) {
                    continue;
                }
                s.pendingCourierDeliveryDespawn = true;
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onServerTick(@NotNull TickEvent.ServerTickEvent event) {
        try {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }

            if (!FFServerConfig.isSuspiciousFeatherEnabled()) {
                if (!ACTIVE_SESSIONS.isEmpty()) {
                    List<UUID> owners = new ArrayList<>(ACTIVE_SESSIONS.keySet());
                    for (UUID ownerId : owners) {
                        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
                        if (owner != null) {
                            stopLinkForOwner(owner, "disabled");
                        } else {
                            ACTIVE_SESSIONS.remove(ownerId);
                            PENDING_LINK_STARTS.remove(ownerId);
                            discardAnyEffigiesForOwner(server, ownerId);
                        }
                    }
                }
                if (!PENDING_LINK_STARTS.isEmpty()) {
                    for (UUID ownerId : new ArrayList<>(PENDING_LINK_STARTS.keySet())) {
                        PENDING_LINK_STARTS.remove(ownerId);
                        discardAnyEffigiesForOwner(server, ownerId);
                    }
                }
                return;
            }

            if (ACTIVE_SESSIONS.isEmpty() && PENDING_LINK_STARTS.isEmpty()) {
                return;
            }

            ServerLevel overworld = server.overworld();
            long now = overworld == null ? 0L : overworld.getGameTime();

            if (!PENDING_LINK_STARTS.isEmpty()) {
                processPendingLinkStarts(server, now);
            }

            if (ACTIVE_SESSIONS.isEmpty()) {
                return;
            }
            List<UUID> toStop = new ArrayList<>();

            for (Map.Entry<UUID, LinkSession> e : ACTIVE_SESSIONS.entrySet()) {
                UUID ownerId = e.getKey();
                LinkSession session = e.getValue();
                if (session == null) {
                    toStop.add(ownerId);
                    continue;
                }

                ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
                if (owner == null || !owner.isAlive() || owner.isRemoved()) {
                    toStop.add(ownerId);
                    continue;
                }

                if (!session.endRequested) {
                    if (session.linkStartedAtGameTime > 0L) {
                        long desiredEnd = session.linkStartedAtGameTime + safeLinkDurationTicksFromConfig();
                        if (desiredEnd != session.endsAtGameTime) {
                            session.endsAtGameTime = desiredEnd;
                        }
                    }
                }

                if (session.endRequested) {
                    if (now >= session.endForceStopAtGameTime) {
                        toStop.add(ownerId);
                        continue;
                    }
                } else if (now >= session.endsAtGameTime) {
                    requestGracefulEnd(server, owner, session, "timeout_or_invalid", now);
                }
                RavenEntity raven = findRavenByUuid(server, session.ravenUuid);
                if (raven == null || !raven.isAlive() || raven.isRemoved()) {
                    toStop.add(ownerId);
                    continue;
                }

                tickRemoteStream(server, owner, raven, session, now);
                if (session.forcedStopReason != null && !session.forcedStopReason.isBlank()) {
                    if (session.endRequested) {
                        if (now >= session.endForceStopAtGameTime) {
                            toStop.add(ownerId);
                            continue;
                        }
                    } else {
                        requestGracefulEnd(server, owner, session, session.forcedStopReason, now);
                    }
                }

                applyLinkedRavenState(raven);
                enforceOwnerLinkState(owner, session);
                syncRavenToOwner(owner, raven, session);
                sendRavenLinkState(owner, raven, session);

                if (now - session.lastStreamDebugLogTime >= 20L) {
                    session.lastStreamDebugLogTime = now;
                    boolean mounted = false;
                    boolean ravenPassenger = false;
                    String vehicleInfo = "none";
                    int ownerPassengerCount = 0;
                    try {
                        mounted = (raven.getVehicle() == owner && owner.getPassengers().contains(raven));
                        ravenPassenger = raven.isPassenger();
                        Entity vehicle = raven.getVehicle();
                        if (vehicle != null) {
                            vehicleInfo = vehicle.getType().toShortString() + "#" + vehicle.getId();
                        }
                        ownerPassengerCount = owner.getPassengers().size();
                    } catch (Throwable ignored) {
                    }
                    LOG.debug(
                            "[RavenLinkStream] owner='{}' center=({}, {}) pendingBefore={} sentTick={} pendingAfter={} loaded={} radius={} mounted={} ravenPassenger={} vehicle={} ownerPassengers={}",
                            safePlayerName(owner),
                            session.streamCenter == null ? 0 : session.streamCenter.x,
                            session.streamCenter == null ? 0 : session.streamCenter.z,
                            session.lastPendingBefore,
                            session.lastChunksSentThisTick,
                            session.lastChunksPending,
                            session.lastChunksLoaded,
                            session.lastStreamRadius,
                            mounted,
                            ravenPassenger,
                            vehicleInfo,
                            ownerPassengerCount
                    );
                }
            }

            if (!toStop.isEmpty()) {
                for (UUID ownerId : toStop) {
                    LinkSession session = ACTIVE_SESSIONS.remove(ownerId);
                    if (session == null) {
                        continue;
                    }
                    ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
                    String reason = (session.forcedStopReason != null && !session.forcedStopReason.isBlank())
                            ? session.forcedStopReason
                            : "timeout_or_invalid";
                    stopSession(server, owner, session, reason);
                }
            }
        } catch (Throwable t) {
            LOG.error("[RavenLinkRuntime] onServerTick failed safely", t);
        }
    }

    private static void processPendingLinkStarts(@NotNull MinecraftServer server, long now) {
        try {
            List<UUID> toRemove = new ArrayList<>();
            for (Map.Entry<UUID, PendingLinkStart> e : PENDING_LINK_STARTS.entrySet()) {
                UUID ownerId = e.getKey();
                PendingLinkStart pending = e.getValue();
                if (ownerId == null || pending == null || pending.session == null) {
                    toRemove.add(ownerId);
                    continue;
                }
                if (ACTIVE_SESSIONS.containsKey(ownerId)) {
                    toRemove.add(ownerId);
                    continue;
                }
                if (now < pending.startAtGameTime) {
                    continue;
                }

                ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
                if (owner == null || !owner.isAlive() || owner.isRemoved()) {
                    toRemove.add(ownerId);
                    continue;
                }

                RavenEntity raven = findRavenByUuid(server, pending.session.ravenUuid);
                if (raven == null || !raven.isAlive() || raven.isRemoved() || raven.level() != owner.level()) {
                    FFNetwork.sendStopRavenLink(owner);
                    toRemove.add(ownerId);
                    continue;
                }

                if (!pending.blackoutAcked) {
                    if (now >= pending.abortAtGameTime) {
                        FFNetwork.sendStopRavenLink(owner);
                        toRemove.add(ownerId);
                    }
                    continue;
                }

                boolean perchAssignmentTemporarilyCleared = false;
                if (pending.session.returnToAssignedPerch) {
                    perchAssignmentTemporarilyCleared = clearPerchAssignmentForLink(raven);
                }

                RavenLinkEffigyEntity effigy = spawnEffigyForSession(server, owner, pending.session);
                if (effigy != null) {
                    pending.session.effigyUuid = effigy.getUUID();
                }

                if (!prepareOwnerForLink(owner, raven, pending.session)) {
                    if (pending.session.effigyUuid != null) {
                        discardSessionEffigy(server, pending.session);
                    }
                    if (perchAssignmentTemporarilyCleared) {
                        restorePerchAssignmentAfterLink(raven, pending.session);
                    }
                    FFNetwork.sendStopRavenLink(owner);
                    toRemove.add(ownerId);
                    continue;
                }

                if (pending.session.returnToAssignedPerch) {
                    raven.forceExitRavenChestPerchForLink();
                }

                long startedAt = owner.serverLevel().getGameTime();
                pending.session.linkStartedAtGameTime = startedAt;
                pending.session.endsAtGameTime = startedAt + safeLinkDurationTicksFromConfig();
                ACTIVE_SESSIONS.put(ownerId, pending.session);
                applyLinkedRavenState(raven);
                FFNetwork.sendRavenLinkOwnerVisibilityToAll(server, owner.getId(), true);

                try {
                    LOG.info("[RavenLinkRuntime] Raven Link started owner='{}' ravenId={} dim={} pos={}",
                            safePlayerName(owner),
                            raven.getId(),
                            owner.serverLevel().dimension().location(),
                            owner.blockPosition().toShortString()
                    );
                } catch (Throwable ignored) {
                }

                RavenLogService.logForPlayerKey(
                        owner.serverLevel(),
                        owner.getUUID(),
                        RavenLogCategory.SYSTEM,
                        "log.featheredfriend.raven_link.started"
                );
                toRemove.add(ownerId);
            }

            for (UUID ownerId : toRemove) {
                if (ownerId != null) {
                    PENDING_LINK_STARTS.remove(ownerId);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RavenLinkRuntime] processPendingLinkStarts failed safely: {}", t.toString());
        }
    }

    private static void requestGracefulEnd(@NotNull MinecraftServer server,
                                          @NotNull ServerPlayer owner,
                                          @NotNull LinkSession session,
                                          @NotNull String reason,
                                          long now) {
        try {
            if (session.endRequested) {
                return;
            }

            session.endRequested = true;
            session.endRequestedAtGameTime = now;
            session.endForceStopAtGameTime = now + LINK_END_GRACE_FAILSAFE_TICKS;

            if (reason != null && !reason.isBlank()) {
                session.forcedStopReason = reason;
            } else if (session.forcedStopReason == null || session.forcedStopReason.isBlank()) {
                session.forcedStopReason = "timeout_or_invalid";
            }

            FFNetwork.sendBeginRavenLinkEnd(owner);

        } catch (Throwable t) {
            // Failsafe: if we can't request gracefully, stop ASAP.
            session.endRequested = true;
            session.endRequestedAtGameTime = now;
            session.endForceStopAtGameTime = now;
            if (session.forcedStopReason == null || session.forcedStopReason.isBlank()) {
                session.forcedStopReason = (reason == null || reason.isBlank()) ? "timeout_or_invalid" : reason;
            }
            LOG.warn("[RavenLinkRuntime] requestGracefulEnd failed safely owner='{}': {}",
                    safePlayerName(owner), t.toString());
        }
    }

    private static void onPlayerLoggedOut(@NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer owner)) {
                return;
            }
            PENDING_LINK_STARTS.remove(owner.getUUID());
            LinkSession session = ACTIVE_SESSIONS.remove(owner.getUUID());
            if (session == null) {
                return;
            }
            stopSession(owner.server, owner, session, "owner_logged_out");
        } catch (Throwable t) {
            LOG.warn("[RavenLinkRuntime] onPlayerLoggedOut failed safely: {}", t.toString());
        }
    }

    private static void onPlayerLoggedIn(@NotNull PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer joined)) {
                return;
            }
            if (ACTIVE_SESSIONS.isEmpty()) {
                return;
            }
            if (joined.server == null) {
                return;
            }
            for (UUID ownerId : ACTIVE_SESSIONS.keySet()) {
                if (ownerId == null) {
                    continue;
                }
                ServerPlayer owner = joined.server.getPlayerList().getPlayer(ownerId);
                if (owner == null || !owner.isAlive() || owner.isRemoved()) {
                    continue;
                }
                FFNetwork.sendRavenLinkOwnerVisibilityToPlayer(joined, owner.getId(), true);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenLinkRuntime] onPlayerLoggedIn failed safely: {}", t.toString());
        }
    }

    private static void stopSession(@Nullable MinecraftServer server,
                                    @Nullable ServerPlayer owner,
                                    @NotNull LinkSession session,
                                    @NotNull String reason) {
        try {
            discardSessionEffigy(server, session);
            restoreOwnerChunkStreaming(server, owner, session);
            restoreOwnerAfterLink(server, owner, session);

            RavenEntity raven = server == null ? null : findRavenByUuid(server, session.ravenUuid);
            if (raven != null && raven.isAlive() && !raven.isRemoved()) {
                if (raven.isPassenger()) {
                    raven.stopRiding();
                }
                raven.setRavenLinkControlled(false);
                raven.setNoAi(false);
                setRavenNoPhysics(raven, false);
                raven.setDeltaMovement(Vec3.ZERO);
                raven.hurtMarked = true;
                if (session.returnToAssignedPerch) {
                    restorePerchAssignmentAfterLink(raven, session);
                }

                if (session.pendingCourierDeliveryDespawn && server != null && owner != null) {
                    RavenCourierRuntime.finishLinkedCourierDelivery(server, owner, raven);
                } else if (session.returnToAssignedPerch && server != null) {
                    returnRavenToAssignedPerch(server, raven, session);
                } else {
                    raven.setAnimMode(RavenAnimMode.AUTO);
                }
            }
            discardAnyEffigiesForOwner(server, session.ownerUuid);

            if (owner != null && owner.isAlive() && !owner.isRemoved()) {
                if (owner.server != null) {
                    FFNetwork.sendRavenLinkOwnerVisibilityToAll(owner.server, owner.getId(), false);
                }
                FFNetwork.sendStopRavenLink(owner);

                try {
                    Object reasonArg = switch (reason) {
                        case "client_stop" -> net.minecraft.network.chat.Component.translatable(
                                "reason.featheredfriend.raven_link.client_stop"
                        );
                        case "disabled" -> net.minecraft.network.chat.Component.translatable(
                                "reason.featheredfriend.raven_link.disabled"
                        );
                        default -> reason;
                    };
                    RavenLogService.logForPlayerKey(
                            owner.serverLevel(),
                            owner.getUUID(),
                            RavenLogCategory.SYSTEM,
                            "log.featheredfriend.raven_link.ended",
                            reasonArg
                    );
                } catch (Throwable ignored) {
                }
            }

            try {
                String ownerName = owner == null ? "<offline>" : safePlayerName(owner);
                long now = 0L;
                try {
                    if (owner != null) {
                        now = owner.serverLevel().getGameTime();
                    } else if (server != null && server.overworld() != null) {
                        now = server.overworld().getGameTime();
                    }
                } catch (Throwable ignored) {
                }
                long startedAt = Math.max(0L, session.linkStartedAtGameTime);
                long durationTicks = (now > 0L && startedAt > 0L) ? Math.max(0L, now - startedAt) : -1L;

                LOG.info("[RavenLinkRuntime] Raven Link ended owner='{}' reason='{}' durationTicks={}",
                        ownerName,
                        reason,
                        durationTicks
                );
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            LOG.warn("[RavenLinkRuntime] stopSession failed safely owner={} reason='{}': {}",
                    owner == null ? "<offline>" : safePlayerName(owner),
                    reason,
                    t.toString());
        }
    }

    private static void discardAnyEffigiesForOwner(@Nullable MinecraftServer server, @Nullable UUID ownerId) {
        try {
            if (server == null || ownerId == null) {
                return;
            }
            for (ServerLevel level : server.getAllLevels()) {
                if (level == null) {
                    continue;
                }
                double cx = level.getWorldBorder().getCenterX();
                double cz = level.getWorldBorder().getCenterZ();
                double half = Math.min(level.getWorldBorder().getSize() * 0.5D, 30_000_000D);
                AABB worldBox = new AABB(
                        cx - half,
                        level.getMinBuildHeight(),
                        cz - half,
                        cx + half,
                        level.getMaxBuildHeight(),
                        cz + half
                );
                List<RavenLinkEffigyEntity> effigies = level.getEntitiesOfClass(
                        RavenLinkEffigyEntity.class,
                        worldBox,
                        e -> e != null && ownerId.equals(e.getLinkedOwnerUuid())
                );
                for (RavenLinkEffigyEntity effigy : effigies) {
                    if (effigy != null && !effigy.isRemoved()) {
                        effigy.discard();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void tickManualMovement(@NotNull RavenEntity raven, @NotNull LinkInput input) {
        try {
            Teleportation tp = raven.getTeleportation();
            if (tp != null && tp.teleportSeqPhase != Teleportation.TeleportSeqPhase.NONE) {
                return;
            }

            float yaw = Mth.wrapDegrees(input.yaw);
            float pitch = Mth.clamp(input.pitch, -89.9F, 89.9F);

            raven.setYRot(yaw);
            raven.setYHeadRot(yaw);
            raven.yBodyRot = yaw;
            raven.setXRot(pitch);

            double forwardAxis = (input.forward ? 1.0D : 0.0D) - (input.backward ? 1.0D : 0.0D);
            double strafeAxis = (input.right ? 1.0D : 0.0D) - (input.left ? 1.0D : 0.0D);
            double verticalAxis = (input.ascend ? 1.0D : 0.0D) - (input.descend ? 1.0D : 0.0D);

            Vec3 horizontalForward = Vec3.directionFromRotation(0.0F, yaw);
            Vec3 right = new Vec3(-horizontalForward.z, 0.0D, horizontalForward.x);

            Vec3 desiredHorizontal = horizontalForward.scale(forwardAxis)
                    .add(right.scale(strafeAxis));
            if (desiredHorizontal.lengthSqr() > 1.0D) {
                desiredHorizontal = desiredHorizontal.normalize();
            }
            desiredHorizontal = desiredHorizontal.scale(LINK_HORIZONTAL_SPEED);

            double desiredY = Mth.clamp(verticalAxis * LINK_VERTICAL_SPEED, -LINK_VERTICAL_SPEED, LINK_VERTICAL_SPEED);
            Vec3 target = new Vec3(desiredHorizontal.x, desiredY, desiredHorizontal.z);

            Vec3 current = raven.getDeltaMovement();
            Vec3 blended = current.lerp(target, LINK_ACCEL_FACTOR);

            boolean hasInput = Math.abs(forwardAxis) > 0.0001D
                    || Math.abs(strafeAxis) > 0.0001D
                    || Math.abs(verticalAxis) > 0.0001D;
            if (hasInput) {
                blended = new Vec3(blended.x * LINK_ACTIVE_DRAG, blended.y * 0.98D, blended.z * LINK_ACTIVE_DRAG);
            } else {
                blended = new Vec3(blended.x * LINK_IDLE_DRAG, blended.y * 0.90D, blended.z * LINK_IDLE_DRAG);
            }

            if (Math.abs(blended.y) > LINK_VERTICAL_SPEED) {
                blended = new Vec3(blended.x, Mth.clamp(blended.y, -LINK_VERTICAL_SPEED, LINK_VERTICAL_SPEED), blended.z);
            }

            raven.setDeltaMovement(blended);
            raven.hurtMarked = true;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.tickManualMovement", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] tickManualMovement failed safely for raven id={}", raven.getId(), t);
            } else {
                LOG.debug("[RavenLinkRuntime] tickManualMovement failed safely for raven id={}: {}",
                        raven.getId(), t.toString());
            }
        }
    }

    private static boolean prepareOwnerForLink(@NotNull ServerPlayer owner,
                                               @NotNull RavenEntity raven,
                                               @NotNull LinkSession session) {
        try {
            if (!(raven.level() instanceof ServerLevel ravenLevel)) {
                return false;
            }

            session.ownerWasInvisible = owner.isInvisible();
            session.ownerAbilitiesInvulnerable = owner.getAbilities().invulnerable;
            session.ownerAbilitiesMayfly = owner.getAbilities().mayfly;
            session.ownerAbilitiesFlying = owner.getAbilities().flying;
            session.ownerFlySpeed = owner.getAbilities().getFlyingSpeed();
            session.ownerHadInvisibilityEffect = owner.hasEffect(MobEffects.INVISIBILITY);

            Vec3 ravenPos = raven.position();
            owner.teleportTo(
                    ravenLevel,
                    ravenPos.x,
                    ravenPos.y,
                    ravenPos.z,
                    Set.of(),
                    raven.getYRot(),
                    raven.getXRot()
            );

            owner.setInvisible(true);
            owner.addTag(TAG_RAVEN_LINK_HIDDEN_OWNER);
            if (!session.ownerHadInvisibilityEffect) {
                owner.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false));
            }
            owner.getAbilities().invulnerable = true;
            owner.getAbilities().mayfly = true;
            owner.getAbilities().flying = true;
            owner.getAbilities().setFlyingSpeed(LINK_OWNER_FLY_SPEED);
            owner.onUpdateAbilities();
            return true;
        } catch (Throwable t) {
            LOG.warn("[RavenLinkRuntime] prepareOwnerForLink failed safely owner='{}': {}",
                    safePlayerName(owner), t.toString());
            return false;
        }
    }

    private static void restoreOwnerAfterLink(@Nullable MinecraftServer server,
                                              @Nullable ServerPlayer owner,
                                              @NotNull LinkSession session) {
        try {
            if (owner == null || server == null || !owner.isAlive() || owner.isRemoved()) {
                return;
            }

            owner.setInvisible(session.ownerWasInvisible);
            owner.removeTag(TAG_RAVEN_LINK_HIDDEN_OWNER);
            if (!session.ownerHadInvisibilityEffect) {
                owner.removeEffect(MobEffects.INVISIBILITY);
            }
            owner.getAbilities().invulnerable = session.ownerAbilitiesInvulnerable;
            owner.getAbilities().mayfly = session.ownerAbilitiesMayfly;
            owner.getAbilities().flying = session.ownerAbilitiesFlying;
            owner.getAbilities().setFlyingSpeed(session.ownerFlySpeed);
            owner.onUpdateAbilities();

            ServerLevel anchorLevel = server.getLevel(session.ownerAnchorDimension);
            if (anchorLevel != null) {
                owner.teleportTo(
                        anchorLevel,
                        session.ownerAnchorPos.x,
                        session.ownerAnchorPos.y,
                        session.ownerAnchorPos.z,
                        Set.of(),
                        session.ownerAnchorYaw,
                        session.ownerAnchorPitch
                );
            }
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.restoreOwnerAfterLink", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] restoreOwnerAfterLink failed safely owner={}",
                        owner == null ? "<offline>" : safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] restoreOwnerAfterLink failed safely owner={}: {}",
                        owner == null ? "<offline>" : safePlayerName(owner),
                        t.toString());
            }
        }
    }

    private static void syncRavenToOwner(@NotNull ServerPlayer owner,
                                         @NotNull RavenEntity raven,
                                         @NotNull LinkSession session) {
        try {
            raven.forceExitRavenChestPerchForLink();
            float yaw = owner.getYRot();
            float pitch = owner.getXRot();
            boolean mounted = ensureRavenMountedToOwner(owner, raven);
            Vec3 ownerPos = owner.position();

            // Perch-assigned ravens can still be pinned by loaded-world perch constraints.
            // Keep riding for visual attachment, but force a hard position sync for this session type.
            boolean forceHardSync = session.returnToAssignedPerch;

            // Riding is primary; hard-sync when needed.
            if (forceHardSync || !mounted) {
                raven.absMoveTo(ownerPos.x, ownerPos.y, ownerPos.z, yaw, pitch);
                raven.setOldPosAndRot();
            }
            raven.setYRot(yaw);
            raven.setYHeadRot(yaw);
            raven.yBodyRot = yaw;
            raven.setXRot(pitch);
            raven.yRotO = yaw;
            raven.yHeadRotO = yaw;
            raven.yBodyRotO = yaw;
            raven.xRotO = pitch;
            raven.setDeltaMovement(Vec3.ZERO);
            raven.fallDistance = owner.fallDistance;
            raven.hurtMarked = true;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.syncRavenToOwner", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] syncRavenToOwner failed safely owner='{}' ravenId={}",
                        safePlayerName(owner), raven.getId(), t);
            } else {
                LOG.debug("[RavenLinkRuntime] syncRavenToOwner failed safely owner='{}' ravenId={}: {}",
                        safePlayerName(owner), raven.getId(), t.toString());
            }
        }
    }

    private static boolean ensureRavenMountedToOwner(@NotNull ServerPlayer owner, @NotNull RavenEntity raven) {
        try {
            if (raven.getVehicle() == owner && owner.getPassengers().contains(raven)) {
                return true;
            }
            if (raven.isPassenger()) {
                raven.stopRiding();
            }
            boolean started = raven.startRiding(owner, true);
            boolean mounted = raven.getVehicle() == owner && owner.getPassengers().contains(raven);
            if (!mounted) {
                try {
                    long now = owner.serverLevel().getGameTime();
                    if (now % 20L == 0L) {
                        Entity vehicle = raven.getVehicle();
                        String vehicleInfo = vehicle == null
                                ? "none"
                                : vehicle.getType().toShortString() + "#" + vehicle.getId();
                        if (FFLogThrottle.shouldLog("RavenLinkRuntime.mount_fail_summary", 30_000L)) {
                            LOG.warn(
                                    "[RavenLinkMountFail] owner='{}' ravenId={} started={} mounted={} ravenPassenger={} vehicle={} ownerPassengers={}",
                                    safePlayerName(owner),
                                    raven.getId(),
                                    started,
                                    mounted,
                                    raven.isPassenger(),
                                    vehicleInfo,
                                    owner.getPassengers().size()
                            );
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return mounted;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.ensureRavenMountedToOwner", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] ensureRavenMountedToOwner failed safely owner='{}' ravenId={}",
                        safePlayerName(owner), raven.getId(), t);
            } else {
                LOG.debug("[RavenLinkRuntime] ensureRavenMountedToOwner failed safely owner='{}' ravenId={}: {}",
                        safePlayerName(owner), raven.getId(), t.toString());
            }
            return false;
        }
    }

    private static void enforceOwnerLinkState(@NotNull ServerPlayer owner, @NotNull LinkSession session) {
        try {
            if (!owner.isInvisible()) {
                owner.setInvisible(true);
            }
            if (!owner.getTags().contains(TAG_RAVEN_LINK_HIDDEN_OWNER)) {
                owner.addTag(TAG_RAVEN_LINK_HIDDEN_OWNER);
            }
            if (!owner.hasEffect(MobEffects.INVISIBILITY)) {
                owner.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false));
            }
            if (!owner.getAbilities().invulnerable || !owner.getAbilities().mayfly || !owner.getAbilities().flying) {
                owner.getAbilities().invulnerable = true;
                owner.getAbilities().mayfly = true;
                owner.getAbilities().flying = true;
                owner.getAbilities().setFlyingSpeed(LINK_OWNER_FLY_SPEED);
                owner.onUpdateAbilities();
            }
            owner.setPose(Pose.STANDING);
            session.lastInputGameTime = owner.serverLevel().getGameTime();
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.enforceOwnerLinkState", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] enforceOwnerLinkState failed safely for player='{}'",
                        safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] enforceOwnerLinkState failed safely for player='{}': {}",
                        safePlayerName(owner), t.toString());
            }
        }
    }

    private static void setRavenNoPhysics(@NotNull RavenEntity raven, boolean value) {
        try {
            Field field = getEntityNoPhysicsField();
            if (field == null) {
                return;
            }
            field.setBoolean(raven, value);
        } catch (Throwable ignored) {
        }
    }

    private static @Nullable Field getEntityNoPhysicsField() {
        try {
            if (ENTITY_NO_PHYSICS_FIELD_LOOKED_UP) {
                return ENTITY_NO_PHYSICS_FIELD;
            }
            Field f = Entity.class.getDeclaredField("noPhysics");
            f.setAccessible(true);
            ENTITY_NO_PHYSICS_FIELD = f;
            ENTITY_NO_PHYSICS_FIELD_LOOKED_UP = true;
            return f;
        } catch (Throwable t) {
            ENTITY_NO_PHYSICS_FIELD_LOOKED_UP = true;
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.getEntityNoPhysicsField", 60_000L)) {
                LOG.warn("[RavenLinkRuntime] getEntityNoPhysicsField unavailable", t);
            } else {
                LOG.debug("[RavenLinkRuntime] getEntityNoPhysicsField unavailable: {}", t.toString());
            }
            return null;
        }
    }

    private static void enforceOwnerFreeze(@NotNull ServerPlayer owner, @NotNull LinkSession session) {
        try {
            owner.setDeltaMovement(Vec3.ZERO);
            owner.fallDistance = 0.0F;
            owner.setShiftKeyDown(false);
            owner.setPose(Pose.STANDING);
            owner.setYRot(session.ownerAnchorYaw);
            owner.setYHeadRot(session.ownerAnchorYaw);
            owner.setXRot(session.ownerAnchorPitch);
            owner.yBodyRot = session.ownerAnchorYaw;
            owner.yRotO = session.ownerAnchorYaw;
            owner.yHeadRotO = session.ownerAnchorYaw;
            owner.yBodyRotO = session.ownerAnchorYaw;
            owner.xRotO = session.ownerAnchorPitch;

            if (owner.serverLevel().dimension() != session.ownerAnchorDimension) {
                ServerLevel target = owner.server.getLevel(session.ownerAnchorDimension);
                if (target != null) {
                    owner.teleportTo(target,
                            session.ownerAnchorPos.x,
                            session.ownerAnchorPos.y,
                            session.ownerAnchorPos.z,
                            Set.of(),
                            session.ownerAnchorYaw,
                            session.ownerAnchorPitch
                    );
                }
                return;
            }

            double d2 = owner.position().distanceToSqr(session.ownerAnchorPos);
            if (d2 > PLAYER_FREEZE_MAX_DRIFT_SQR) {
                owner.teleportTo(
                        owner.serverLevel(),
                        session.ownerAnchorPos.x,
                        session.ownerAnchorPos.y,
                        session.ownerAnchorPos.z,
                        Set.of(),
                        session.ownerAnchorYaw,
                        session.ownerAnchorPitch
                );
            }
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.enforceOwnerFreeze", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] enforceOwnerFreeze failed safely for player='{}'",
                        safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] enforceOwnerFreeze failed safely for player='{}': {}",
                        safePlayerName(owner), t.toString());
            }
        }
    }

    private static void sendRavenLinkState(@NotNull ServerPlayer owner,
                                           @NotNull RavenEntity raven,
                                           @NotNull LinkSession session) {
        try {
            Vec3 pos = raven.position();
            FFNetwork.sendRavenLinkState(
                    owner,
                    raven.getId(),
                    pos.x,
                    pos.y,
                    pos.z,
                    raven.getYRot(),
                    raven.getXRot(),
                    session.lastChunksSentThisTick,
                    session.lastChunksPending,
                    session.lastChunksLoaded,
                    session.lastStreamRadius
            );
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.sendRavenLinkState", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] sendRavenLinkState failed safely for owner='{}'",
                        safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] sendRavenLinkState failed safely for owner='{}': {}",
                        safePlayerName(owner),
                        t.toString());
            }
        }
    }

    private static void applyLinkedRavenState(@NotNull RavenEntity raven) {
        try {
            raven.setRavenLinkControlled(true);
            raven.forceExitRavenChestPerchForLink();
            raven.setNoGravity(true);
            // Keep vanilla AI enabled so passenger mount positioning remains stable.
            // Custom behavior is still suppressed by RavenEntity when RavenLinkControlled=true.
            raven.setNoAi(false);
            setRavenNoPhysics(raven, false);
            raven.setDeltaMovement(Vec3.ZERO);
            if (raven.getAnimMode() != RavenAnimMode.IN_AIR) {
                raven.setAnimMode(RavenAnimMode.IN_AIR);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void returnRavenToAssignedPerch(@NotNull MinecraftServer server,
                                                   @NotNull RavenEntity raven,
                                                   @NotNull LinkSession session) {
        try {
            if (session.perchDimension == null || session.perchBlockPos == null) {
                raven.setAnimMode(RavenAnimMode.AUTO);
                return;
            }

            ServerLevel targetLevel = server.getLevel(session.perchDimension);
            if (targetLevel == null) {
                raven.setAnimMode(RavenAnimMode.AUTO);
                return;
            }

            BlockPos chestPos = session.perchBlockPos;
            targetLevel.getChunk(chestPos.getX() >> 4, chestPos.getZ() >> 4);
            if (!targetLevel.getBlockState(chestPos).is(FFBlocks.RAVEN_CHEST.get())) {
                raven.setAnimMode(RavenAnimMode.AUTO);
                return;
            }

            float yaw = defaultYawFromChest(targetLevel, chestPos);
            float pitch = 0.0F;
            double x = chestPos.getX() + RAVEN_CHEST_PERCH_OFFSET_X;
            double y = chestPos.getY() + RAVEN_CHEST_PERCH_OFFSET_Y;
            double z = chestPos.getZ() + RAVEN_CHEST_PERCH_OFFSET_Z;

            playTeleportFx(levelOf(raven), raven, "raven-link-return-perch.from", false, true);
            if (raven.isPassenger()) {
                raven.stopRiding();
            }

            if (raven.level() != targetLevel) {
                raven.teleportTo(targetLevel, x, y, z, Set.of(), yaw, pitch);
            } else {
                raven.moveTo(x, y, z, yaw, pitch);
            }
            raven.setYRot(yaw);
            raven.setYHeadRot(yaw);
            raven.yBodyRot = yaw;
            raven.setXRot(pitch);
            raven.setRavenChestPerchLockRotation(yaw, pitch);
            raven.setDeltaMovement(Vec3.ZERO);
            raven.setNoGravity(true);
            raven.setAIState(RavenAIState.RAVEN_CHEST_PERCH);
            raven.setAnimMode(RavenAnimMode.NO_AIR);
            playTeleportFx(targetLevel, raven, "raven-link-return-perch.to", true, false);
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.returnRavenToAssignedPerch", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] returnRavenToAssignedPerch failed safely for raven id={}",
                        raven.getId(), t);
            } else {
                LOG.debug("[RavenLinkRuntime] returnRavenToAssignedPerch failed safely for raven id={}: {}",
                        raven.getId(), t.toString());
            }
            raven.setAnimMode(RavenAnimMode.AUTO);
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

    private static @Nullable ServerLevel levelOf(@NotNull RavenEntity raven) {
        try {
            if (raven.level() instanceof ServerLevel sl) {
                return sl;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void playTeleportFx(@Nullable ServerLevel level,
                                       @NotNull RavenEntity raven,
                                       @NotNull String reason,
                                       boolean fadeIn,
                                       boolean fadeOutInstant) {
        try {
            if (level == null) {
                return;
            }
            Teleportation tp = raven.getTeleportation();
            if (tp == null) {
                return;
            }
            if (fadeOutInstant) {
                tp.setTeleportFadeAlpha(0, raven);
            }
            Vec3 fxPos = raven.position().add(0.0D, 0.6D, 0.0D);
            long seed = raven.getUUID().getLeastSignificantBits() ^ (long) raven.tickCount ^ reason.hashCode();
            tp.spawnEnderpopBurst(level, fxPos.x, fxPos.y, fxPos.z, seed, reason, raven);
            if (fadeIn) {
                tp.startFadeInOnly(reason, raven);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void tickRemoteStream(@NotNull MinecraftServer server,
                                         @NotNull ServerPlayer owner,
                                         @NotNull RavenEntity raven,
                                         @NotNull LinkSession session,
                                         long now) {
        try {
            if (!(raven.level() instanceof ServerLevel ravenLevel)) {
                session.forcedStopReason = "invalid_raven_level";
                return;
            }
            if (owner.serverLevel() != ravenLevel) {
                session.forcedStopReason = "cross_dimension";
                return;
            }

            int viewDistance = streamViewDistance(owner);
            ChunkPos targetChunk = raven.chunkPosition();

            ensureStreamTicket(session, ravenLevel, targetChunk);
            session.streamActive = true;
            session.streamDimension = ravenLevel.dimension();
            session.streamCenter = targetChunk;
            session.streamViewDistance = viewDistance;
            session.lastPendingBefore = 0;
            session.lastChunksSentThisTick = 0;
            session.lastChunksPending = 0;
            session.lastChunksLoaded = 0;
            session.lastStreamRadius = viewDistance;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.tickRemoteStream", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] tickRemoteStream failed safely owner='{}'",
                        safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] tickRemoteStream failed safely owner='{}': {}",
                        safePlayerName(owner), t.toString());
            }
            session.forcedStopReason = "stream_failed";
        }
    }

    private static void ensureStreamTicket(@NotNull LinkSession session,
                                           @NotNull ServerLevel level,
                                           @NotNull ChunkPos targetChunk) {
        try {
            if (Objects.equals(session.ticketDimension, level.dimension())
                    && session.ticketChunk != null
                    && session.ticketChunk.equals(targetChunk)) {
                return;
            }

            clearStreamTicket(level.getServer(), session);

            // Keep raven-area chunks entity-ticking while linked.
            // UNKNOWN tickets have a 1-tick timeout and are not suitable here.
            level.getChunkSource().addRegionTicket(
                    TicketType.FORCED,
                    targetChunk,
                    STREAM_TICKET_RADIUS,
                    targetChunk,
                    true
            );
            session.ticketDimension = level.dimension();
            session.ticketChunk = targetChunk;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.ensureStreamTicket", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] ensureStreamTicket failed safely", t);
            } else {
                LOG.debug("[RavenLinkRuntime] ensureStreamTicket failed safely: {}", t.toString());
            }
        }
    }

    private static void ensureEffigyTicket(@NotNull LinkSession session,
                                           @NotNull ServerLevel level,
                                           @NotNull ChunkPos targetChunk) {
        try {
            if (Objects.equals(session.effigyTicketDimension, level.dimension())
                    && session.effigyTicketChunk != null
                    && session.effigyTicketChunk.equals(targetChunk)) {
                return;
            }

            clearEffigyTicket(level.getServer(), session);

            // Keep the effigy chunk loaded until Raven Link stops so we can always
            // discard the effigy reliably even when the owner is far away.
            level.getChunkSource().addRegionTicket(
                    TicketType.FORCED,
                    targetChunk,
                    EFFIGY_TICKET_RADIUS,
                    targetChunk,
                    true
            );
            session.effigyTicketDimension = level.dimension();
            session.effigyTicketChunk = targetChunk;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.ensureEffigyTicket", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] ensureEffigyTicket failed safely", t);
            } else {
                LOG.debug("[RavenLinkRuntime] ensureEffigyTicket failed safely: {}", t.toString());
            }
        }
    }

    private static void clearStreamTicket(@Nullable MinecraftServer server, @NotNull LinkSession session) {
        try {
            if (server == null || session.ticketDimension == null || session.ticketChunk == null) {
                return;
            }
            ServerLevel ticketLevel = server.getLevel(session.ticketDimension);
            if (ticketLevel != null) {
                ticketLevel.getChunkSource().removeRegionTicket(
                        TicketType.FORCED,
                        session.ticketChunk,
                        STREAM_TICKET_RADIUS,
                        session.ticketChunk,
                        true
                );
            }
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.clearStreamTicket", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] clearStreamTicket failed safely", t);
            } else {
                LOG.debug("[RavenLinkRuntime] clearStreamTicket failed safely: {}", t.toString());
            }
        } finally {
            session.ticketDimension = null;
            session.ticketChunk = null;
        }
    }

    private static void clearEffigyTicket(@Nullable MinecraftServer server, @NotNull LinkSession session) {
        try {
            if (server == null || session.effigyTicketDimension == null || session.effigyTicketChunk == null) {
                return;
            }
            ServerLevel ticketLevel = server.getLevel(session.effigyTicketDimension);
            if (ticketLevel != null) {
                ticketLevel.getChunkSource().removeRegionTicket(
                        TicketType.FORCED,
                        session.effigyTicketChunk,
                        EFFIGY_TICKET_RADIUS,
                        session.effigyTicketChunk,
                        true
                );
            }
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.clearEffigyTicket", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] clearEffigyTicket failed safely", t);
            } else {
                LOG.debug("[RavenLinkRuntime] clearEffigyTicket failed safely: {}", t.toString());
            }
        } finally {
            session.effigyTicketDimension = null;
            session.effigyTicketChunk = null;
        }
    }

    private static void restoreOwnerChunkStreaming(@Nullable MinecraftServer server,
                                                   @Nullable ServerPlayer owner,
                                                   @NotNull LinkSession session) {
        try {
            clearStreamTicket(server, session);
            session.streamActive = false;
            session.streamDimension = null;
            session.streamCenter = null;
            session.streamViewDistance = 0;
            session.lastPendingBefore = 0;
            session.lastChunksSentThisTick = 0;
            session.lastChunksPending = 0;
            session.lastChunksLoaded = 0;
            session.lastStreamRadius = 0;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.restoreOwnerChunkStreaming", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] restoreOwnerChunkStreaming failed safely owner={}",
                        owner == null ? "<offline>" : safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] restoreOwnerChunkStreaming failed safely owner={}: {}",
                        owner == null ? "<offline>" : safePlayerName(owner),
                        t.toString());
            }
        }
    }

    private static void tickManualChunkStream(@NotNull ServerPlayer owner,
                                              @NotNull ServerLevel level,
                                              @NotNull ChunkPos center,
                                              int requestedViewDistance,
                                              @NotNull LinkSession session,
                                              long now) {
        try {
            int radius = Math.max(MIN_STREAM_VIEW_DISTANCE, Math.min(requestedViewDistance, MANUAL_STREAM_MAX_RADIUS));
            boolean fullResync = now >= session.nextManualResyncGameTime;
            if (fullResync) {
                session.manualChunksSent.clear();
                session.manualPendingChunkSends.clear();
                session.manualStreamCenter = null;
                session.manualStreamRadius = 0;
            }

            owner.connection.send(new ClientboundSetChunkCacheRadiusPacket(radius));
            owner.connection.send(new ClientboundSetChunkCacheCenterPacket(center.x, center.z));

            boolean centerChanged = session.manualStreamCenter == null
                    || !session.manualStreamCenter.equals(center)
                    || session.manualStreamRadius != radius;

            if (centerChanged || fullResync) {
                rebuildManualChunkTargets(owner, center, radius, session);
            }

            int pendingBefore = session.manualPendingChunkSends.size();
            int sent = 0;
            while (sent < MANUAL_STREAM_CHUNKS_PER_TICK && !session.manualPendingChunkSends.isEmpty()) {
                ChunkPos next = session.manualPendingChunkSends.pollFirst();
                if (next == null) {
                    continue;
                }
                long key = next.toLong();
                if (session.manualChunksSent.contains(key)) {
                    continue;
                }

                ChunkAccess access = level.getChunkSource().getChunk(next.x, next.z, ChunkStatus.FULL, true);
                if (!(access instanceof LevelChunk chunk)) {
                    continue;
                }

                sendChunkDirect(owner, level, chunk);
                session.manualChunksSent.add(key);
                sent++;
            }

            session.lastPendingBefore = pendingBefore;
            session.lastChunksSentThisTick = sent;
            session.lastChunksPending = session.manualPendingChunkSends.size();
            session.lastChunksLoaded = session.manualChunksSent.size();
            session.lastStreamRadius = radius;
            session.nextManualResyncGameTime = now + MANUAL_STREAM_FULL_RESYNC_INTERVAL_TICKS;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.tickManualChunkStream", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] tickManualChunkStream failed safely owner='{}'",
                        safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] tickManualChunkStream failed safely owner='{}': {}",
                        safePlayerName(owner),
                        t.toString());
            }
        }
    }

    private static void rebuildManualChunkTargets(@NotNull ServerPlayer owner,
                                                  @NotNull ChunkPos center,
                                                  int radius,
                                                  @NotNull LinkSession session) {
        try {
            Set<Long> target = new HashSet<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    target.add(ChunkPos.asLong(center.x + dx, center.z + dz));
                }
            }

            List<Long> toForget = new ArrayList<>();
            for (Long existing : session.manualChunksSent) {
                if (existing == null || target.contains(existing)) {
                    continue;
                }
                toForget.add(existing);
            }
            for (Long key : toForget) {
                if (key == null) {
                    continue;
                }
                ChunkPos forgetPos = new ChunkPos(key);
                owner.connection.send(new ClientboundForgetLevelChunkPacket(forgetPos.x, forgetPos.z));
                session.manualChunksSent.remove(key);
            }

            session.manualPendingChunkSends.clear();
            for (Long key : target) {
                if (key == null || session.manualChunksSent.contains(key)) {
                    continue;
                }
                session.manualPendingChunkSends.addLast(new ChunkPos(key));
            }

            session.manualStreamCenter = center;
            session.manualStreamRadius = radius;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.rebuildManualChunkTargets", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] rebuildManualChunkTargets failed safely owner='{}'",
                        safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] rebuildManualChunkTargets failed safely owner='{}': {}",
                        safePlayerName(owner),
                        t.toString());
            }
        }
    }

    private static void clearManualChunkStream(@Nullable ServerPlayer owner, @NotNull LinkSession session) {
        try {
            if (owner != null && owner.isAlive() && !owner.isRemoved()) {
                for (Long key : session.manualChunksSent) {
                    if (key == null) {
                        continue;
                    }
                    ChunkPos forgetPos = new ChunkPos(key);
                    owner.connection.send(new ClientboundForgetLevelChunkPacket(forgetPos.x, forgetPos.z));
                }
            }
        } catch (Throwable ignored) {
        } finally {
            session.manualChunksSent.clear();
            session.manualPendingChunkSends.clear();
            session.manualStreamCenter = null;
            session.manualStreamRadius = 0;
            session.lastPendingBefore = 0;
            session.lastChunksSentThisTick = 0;
            session.lastChunksPending = 0;
            session.lastChunksLoaded = 0;
            session.lastStreamRadius = 0;
            session.nextManualResyncGameTime = 0L;
        }
    }

    private static void sendChunkDirect(@NotNull ServerPlayer owner,
                                        @NotNull ServerLevel level,
                                        @NotNull LevelChunk chunk) {
        try {
            owner.connection.send(new ClientboundLevelChunkWithLightPacket(
                    chunk,
                    level.getLightEngine(),
                    null,
                    null
            ));
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.sendChunkDirect", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] sendChunkDirect failed safely owner='{}'",
                        safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] sendChunkDirect failed safely owner='{}': {}",
                        safePlayerName(owner),
                        t.toString());
            }
        }
    }

    private static int streamViewDistance(@NotNull ServerPlayer owner) {
        try {
            int serverView = owner.server == null ? 10 : owner.server.getPlayerList().getViewDistance();
            return Mth.clamp(serverView, MIN_STREAM_VIEW_DISTANCE, MAX_STREAM_VIEW_DISTANCE);
        } catch (Throwable ignored) {
            return 10;
        }
    }

    private static @Nullable RavenEntity findBestActiveRavenForOwner(@NotNull MinecraftServer server,
                                                                      @NotNull ServerPlayer owner,
                                                                      boolean sameDimensionOnly) {
        try {
            UUID ownerId = owner.getUUID();
            Map<UUID, RavenEntity> candidates = new HashMap<>();

            collectOwnerRavensNearRegisteredChests(server, ownerId, candidates);
            collectLoadedOwnerRavens(server, ownerId, candidates);

            List<RavenEntity> active = new ArrayList<>();
            for (RavenEntity raven : candidates.values()) {
                if (raven == null || !raven.isAlive() || raven.isRemoved()) {
                    continue;
                }
                if (sameDimensionOnly && raven.level() != owner.level()) {
                    continue;
                }
                if (!isActiveRavenCandidate(raven)) {
                    continue;
                }
                active.add(raven);
            }

            if (active.isEmpty()) {
                return null;
            }

            active.sort(
                    Comparator.comparingInt((RavenEntity r) -> activePriority(r, owner))
                            .thenComparingDouble(r -> distanceToOwnerSqr(r, owner))
            );
            return active.get(0);
        } catch (Throwable t) {
            LOG.warn("[RavenLinkRuntime] findBestActiveRavenForOwner failed safely for player='{}': {}",
                    safePlayerName(owner), t.toString());
            return null;
        }
    }

    private static int activePriority(@NotNull RavenEntity raven, @NotNull ServerPlayer owner) {
        try {
            if (raven.getTags().contains(TAG_COURIER_RAVEN)) {
                return 0;
            }
            if (raven.getTags().contains(TAG_SCROLL_SUMMONED)) {
                return 1;
            }
            if (hasAssignedPerch(raven)) {
                return 2;
            }
            if (raven.level() == owner.level()) {
                return 3;
            }
        } catch (Throwable ignored) {
        }
        return 4;
    }

    private static double distanceToOwnerSqr(@NotNull RavenEntity raven, @NotNull ServerPlayer owner) {
        try {
            if (raven.level() != owner.level()) {
                return Double.MAX_VALUE;
            }
            return raven.distanceToSqr(owner);
        } catch (Throwable ignored) {
            return Double.MAX_VALUE;
        }
    }

    private static boolean isActiveRavenCandidate(@NotNull RavenEntity raven) {
        try {
            if (raven.getTags().contains(TAG_SCROLL_SUMMONED)) {
                return true;
            }
            if (raven.getTags().contains(TAG_COURIER_RAVEN)) {
                return true;
            }
            return hasAssignedPerch(raven);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasAssignedPerch(@NotNull RavenEntity raven) {
        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return false;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            return ffTag != null
                    && ffTag.getBoolean(NBT_RAVEN_CHEST_PERCH_ASSIGNED)
                    && ffTag.contains(NBT_RAVEN_CHEST_PERCH_DIMENSION)
                    && ffTag.contains(NBT_RAVEN_CHEST_PERCH_BLOCK_POS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static @Nullable RavenEntity findRavenByUuid(@NotNull MinecraftServer server, @NotNull UUID uuid) {
        try {
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(uuid);
                if (e instanceof RavenEntity raven) {
                    return raven;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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
                    AABB box = new AABB(chestPos).inflate(10.0D, 6.0D, 10.0D);
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
                double cx = level.getWorldBorder().getCenterX();
                double cz = level.getWorldBorder().getCenterZ();
                double half = Math.min(level.getWorldBorder().getSize() * 0.5D, 30_000_000D);
                AABB worldBox = new AABB(
                        cx - half,
                        level.getMinBuildHeight(),
                        cz - half,
                        cx + half,
                        level.getMaxBuildHeight(),
                        cz + half
                );
                List<RavenEntity> ravens = level.getEntitiesOfClass(
                        RavenEntity.class,
                        worldBox,
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

    private static LinkSession buildSession(@NotNull ServerPlayer owner, @NotNull RavenEntity raven) {
        LinkSession s = new LinkSession();
        s.ownerUuid = owner.getUUID();
        s.ravenUuid = raven.getUUID();
        s.linkStartedAtGameTime = 0L;
        s.endsAtGameTime = 0L;
        s.ownerAnchorDimension = owner.serverLevel().dimension();
        s.ownerAnchorPos = owner.position();
        s.ownerAnchorChunk = owner.chunkPosition();
        s.ownerAnchorYaw = owner.getYRot();
        s.ownerAnchorPitch = owner.getXRot();
        s.ownerStartPose = owner.getPose();
        s.input = new LinkInput(false, false, false, false, false, false, raven.getYRot(), raven.getXRot());
        s.lastInputGameTime = owner.serverLevel().getGameTime();

        EffigyPoseSnapshot snap = consumeFreshEffigyPoseSnapshot(owner);
        if (snap != null) {
            applySnapshotToSession(s, snap);
        }

        PerchAssignment perch = readPerchAssignment(raven);
        if (perch != null) {
            s.returnToAssignedPerch = true;
            s.perchDimension = perch.dimension;
            s.perchBlockPos = perch.blockPos;
        }
        return s;
    }

    private static @Nullable PerchAssignment readPerchAssignment(@NotNull RavenEntity raven) {
        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return null;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return null;
            }
            if (!ffTag.getBoolean(NBT_RAVEN_CHEST_PERCH_ASSIGNED)) {
                return null;
            }
            if (!ffTag.contains(NBT_RAVEN_CHEST_PERCH_DIMENSION) || !ffTag.contains(NBT_RAVEN_CHEST_PERCH_BLOCK_POS)) {
                return null;
            }

            String dim = ffTag.getString(NBT_RAVEN_CHEST_PERCH_DIMENSION);
            ResourceLocation dimLoc = ResourceLocation.tryParse(dim);
            if (dimLoc == null) {
                return null;
            }
            return new PerchAssignment(
                    ResourceKey.create(Registries.DIMENSION, dimLoc),
                    BlockPos.of(ffTag.getLong(NBT_RAVEN_CHEST_PERCH_BLOCK_POS))
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable RavenEntity respawnRavenForLink(@NotNull ServerPlayer owner, @NotNull RavenEntity raven) {
        try {
            if (!(raven.level() instanceof ServerLevel level)) {
                return null;
            }

            CompoundTag snapshot = new CompoundTag();
            raven.saveWithoutId(snapshot);
            // Force a new runtime identity and position.
            snapshot.remove("UUID");
            snapshot.remove("Pos");
            snapshot.remove("Motion");
            snapshot.remove("Rotation");
            snapshot.remove("Passengers");

            Entity created = raven.getType().create(level);
            if (!(created instanceof RavenEntity replacement)) {
                return null;
            }

            replacement.load(snapshot);
            Vec3 ownerPos = owner.position();
            replacement.moveTo(ownerPos.x, ownerPos.y + 0.20D, ownerPos.z, owner.getYRot(), owner.getXRot());
            replacement.setDeltaMovement(Vec3.ZERO);
            replacement.hurtMarked = true;

            if (!level.addFreshEntity(replacement)) {
                return null;
            }

            raven.discard();
            return replacement;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable RavenLinkEffigyEntity spawnEffigyForSession(@Nullable MinecraftServer server,
                                                                          @NotNull ServerPlayer owner,
                                                                          @NotNull LinkSession session) {
        try {
            if (server == null) {
                return null;
            }
            ServerLevel anchorLevel = server.getLevel(session.ownerAnchorDimension);
            if (anchorLevel == null) {
                return null;
            }

            RavenLinkEffigyEntity effigy = FFEntities.RAVEN_LINK_EFFIGY.get().create(anchorLevel);
            if (effigy == null) {
                return null;
            }

            Vec3 p = session.ownerAnchorPos;
            effigy.moveTo(p.x, p.y, p.z, session.ownerAnchorYaw, session.ownerAnchorPitch);
            effigy.setYRot(session.ownerAnchorYaw);
            effigy.setYHeadRot(session.ownerAnchorYaw);
            effigy.yBodyRot = session.ownerAnchorYaw;
            effigy.setXRot(session.ownerAnchorPitch);
            effigy.copyVisualsFromOwner(owner);
            effigy.setLinkedOwnerUuid(owner.getUUID());
            try {
                Pose pose = session.ownerStartPose == null ? Pose.STANDING : session.ownerStartPose;
                effigy.setPose(pose);
                effigy.setShiftKeyDown(pose == Pose.CROUCHING);
            } catch (Throwable ignored) {
            }

            try {
                if (session.effigyPoseSnapshotPresent) {
                    effigy.setPoseSnapshot(
                            session.effigyHeadXRot, session.effigyHeadYRot, session.effigyHeadZRot,
                            session.effigyBodyXRot, session.effigyBodyYRot, session.effigyBodyZRot,
                            session.effigyRightArmXRot, session.effigyRightArmYRot, session.effigyRightArmZRot,
                            session.effigyLeftArmXRot, session.effigyLeftArmYRot, session.effigyLeftArmZRot,
                            session.effigyRightLegXRot, session.effigyRightLegYRot, session.effigyRightLegZRot,
                            session.effigyLeftLegXRot, session.effigyLeftLegYRot, session.effigyLeftLegZRot
                    );
                }
            } catch (Throwable ignored) {
            }
            effigy.setDeltaMovement(Vec3.ZERO);
            effigy.hurtMarked = true;

            if (!anchorLevel.addFreshEntity(effigy)) {
                return null;
            }
            ensureEffigyTicket(session, anchorLevel, effigy.chunkPosition());
            return effigy;
        } catch (Throwable t) {
            if (FFLogThrottle.shouldLog("RavenLinkRuntime.spawnEffigyForSession", 10_000L)) {
                LOG.warn("[RavenLinkRuntime] spawnEffigyForSession failed safely owner='{}'",
                        safePlayerName(owner), t);
            } else {
                LOG.debug("[RavenLinkRuntime] spawnEffigyForSession failed safely owner='{}': {}",
                        safePlayerName(owner), t.toString());
            }
            return null;
        }
    }

    private static void discardSessionEffigy(@Nullable MinecraftServer server, @NotNull LinkSession session) {
        try {
            if (server == null || session.effigyUuid == null) {
                return;
            }
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(session.effigyUuid);
                if (e instanceof RavenLinkEffigyEntity effigy) {
                    effigy.discard();
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            session.effigyUuid = null;
            clearEffigyTicket(server, session);
        }
    }

    private static void handleEffigyHit(@NotNull RavenLinkEffigyEntity effigy) {
        try {
            if (effigy.level().isClientSide || !(effigy.level() instanceof ServerLevel serverLevel)) {
                return;
            }

            UUID ownerId = effigy.getLinkedOwnerUuid();
            effigy.discard();
            if (ownerId == null) {
                return;
            }

            LinkSession session = ACTIVE_SESSIONS.remove(ownerId);
            if (session == null) {
                return;
            }
            session.effigyUuid = null;

            MinecraftServer server = serverLevel.getServer();
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            if (owner != null && owner.isAlive() && !owner.isRemoved()) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.featheredfriend.raven_link.cancelled_effigy_hit"
                ));
            }
            RavenLogService.logForPlayerKey(
                    serverLevel,
                    ownerId,
                    RavenLogCategory.COMBAT,
                    "log.featheredfriend.raven_link.effigy_hit"
            );
            stopSession(server, owner, session, "effigy_hit");
        } catch (Throwable t) {
            LOG.warn("[RavenLinkRuntime] handleEffigyHit failed safely: {}", t.toString());
        }
    }

    private static boolean clearPerchAssignmentForLink(@NotNull RavenEntity raven) {
        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return false;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || ffTag.isEmpty()) {
                return false;
            }
            ffTag.remove(NBT_RAVEN_CHEST_PERCH_ASSIGNED);
            ffTag.remove(NBT_RAVEN_CHEST_PERCH_DIMENSION);
            ffTag.remove(NBT_RAVEN_CHEST_PERCH_BLOCK_POS);
            root.put(Constants.MOD_ID, ffTag);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void restorePerchAssignmentAfterLink(@NotNull RavenEntity raven, @NotNull LinkSession session) {
        try {
            if (!session.returnToAssignedPerch || session.perchDimension == null || session.perchBlockPos == null) {
                return;
            }
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            ffTag.putBoolean(NBT_RAVEN_CHEST_PERCH_ASSIGNED, true);
            ffTag.putString(NBT_RAVEN_CHEST_PERCH_DIMENSION, session.perchDimension.location().toString());
            ffTag.putLong(NBT_RAVEN_CHEST_PERCH_BLOCK_POS, session.perchBlockPos.asLong());
            root.put(Constants.MOD_ID, ffTag);
        } catch (Throwable ignored) {
        }
    }

    private record EffigyPoseSnapshot(
            float headXRot, float headYRot, float headZRot,
            float bodyXRot, float bodyYRot, float bodyZRot,
            float rightArmXRot, float rightArmYRot, float rightArmZRot,
            float leftArmXRot, float leftArmYRot, float leftArmZRot,
            float rightLegXRot, float rightLegYRot, float rightLegZRot,
            float leftLegXRot, float leftLegYRot, float leftLegZRot,
            long receivedAtGameTime
    ) {
    }

    private record PerchAssignment(@NotNull ResourceKey<Level> dimension, @NotNull BlockPos blockPos) {
    }

    private record LinkInput(boolean forward,
                             boolean backward,
                             boolean left,
                             boolean right,
                             boolean ascend,
                             boolean descend,
                             float yaw,
                             float pitch) {
    }

    private static final class PendingLinkStart {
        private final LinkSession session;
        private final long startAtGameTime;
        private final long abortAtGameTime;
        private volatile boolean blackoutAcked = false;

        private PendingLinkStart(@NotNull LinkSession session, long startAtGameTime, long abortAtGameTime) {
            this.session = session;
            this.startAtGameTime = startAtGameTime;
            this.abortAtGameTime = abortAtGameTime;
        }
    }

    private static final class LinkSession {
        private UUID ownerUuid;
        private UUID ravenUuid;
        private long linkStartedAtGameTime;
        private long endsAtGameTime;
        private ResourceKey<Level> ownerAnchorDimension;
        private Vec3 ownerAnchorPos;
        private ChunkPos ownerAnchorChunk;
        private float ownerAnchorYaw;
        private float ownerAnchorPitch;
        private Pose ownerStartPose;
        private boolean effigyPoseSnapshotPresent;
        private float effigyHeadXRot;
        private float effigyHeadYRot;
        private float effigyHeadZRot;
        private float effigyBodyXRot;
        private float effigyBodyYRot;
        private float effigyBodyZRot;
        private float effigyRightArmXRot;
        private float effigyRightArmYRot;
        private float effigyRightArmZRot;
        private float effigyLeftArmXRot;
        private float effigyLeftArmYRot;
        private float effigyLeftArmZRot;
        private float effigyRightLegXRot;
        private float effigyRightLegYRot;
        private float effigyRightLegZRot;
        private float effigyLeftLegXRot;
        private float effigyLeftLegYRot;
        private float effigyLeftLegZRot;
        private boolean ownerWasInvisible;
        private boolean ownerHadInvisibilityEffect;
        private boolean ownerAbilitiesInvulnerable;
        private boolean ownerAbilitiesMayfly;
        private boolean ownerAbilitiesFlying;
        private float ownerFlySpeed;
        private UUID effigyUuid;
        private ResourceKey<Level> effigyTicketDimension;
        private ChunkPos effigyTicketChunk;
        private LinkInput input;
        private boolean returnToAssignedPerch;
        private ResourceKey<Level> perchDimension;
        private BlockPos perchBlockPos;
        private boolean pendingCourierDeliveryDespawn;
        private boolean streamActive;
        private ResourceKey<Level> streamDimension;
        private ChunkPos streamCenter;
        private int streamViewDistance;
        private ResourceKey<Level> ticketDimension;
        private ChunkPos ticketChunk;
        private String forcedStopReason;
        private boolean endRequested;
        private long endRequestedAtGameTime;
        private long endForceStopAtGameTime;
        private final Set<Long> manualChunksSent = new HashSet<>();
        private final ArrayDeque<ChunkPos> manualPendingChunkSends = new ArrayDeque<>();
        private ChunkPos manualStreamCenter;
        private int manualStreamRadius;
        private int lastPendingBefore;
        private int lastChunksSentThisTick;
        private int lastChunksPending;
        private int lastChunksLoaded;
        private int lastStreamRadius;
        private long lastStreamDebugLogTime;
        private long lastInputGameTime;
        private long nextManualResyncGameTime;
    }

    private static String safePlayerName(@NotNull ServerPlayer player) {
        try {
            return player.getGameProfile().getName();
        } catch (Throwable ignored) {
            return "<unknown>";
        }
    }
}
