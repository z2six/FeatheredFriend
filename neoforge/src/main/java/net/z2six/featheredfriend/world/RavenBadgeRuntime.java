package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.z2six.featheredfriend.client.ravenbadge.RavenBadgeBaseState;
import net.z2six.featheredfriend.client.ravenbadge.RavenBadgeEventType;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.network.FFNetwork;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side source of truth for raven badge HUD state sync.
 */
public final class RavenBadgeRuntime {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int BASE_SYNC_INTERVAL_TICKS = 20;
    private static final long FORCED_RESYNC_TICKS = 10L * 20L;
    private static final long THREAT_EVENT_COOLDOWN_TICKS = 3L * 20L;
    private static final long REPATH_EVENT_COOLDOWN_TICKS = 2L * 20L;

    private static boolean registered = false;
    private static int tickCounter = 0;

    private static final Map<UUID, RavenBadgeBaseState> LAST_BASE_STATE = new HashMap<>();
    private static final Map<UUID, Long> LAST_BASE_SYNC_TICK = new HashMap<>();
    private static final Map<UUID, EnumMap<RavenBadgeEventType, Long>> LAST_EVENT_TICKS = new HashMap<>();

    private RavenBadgeRuntime() {
    }

    public static void register() {
        try {
            if (registered) {
                return;
            }
            NeoForge.EVENT_BUS.addListener(RavenBadgeRuntime::onServerTickPost);
            NeoForge.EVENT_BUS.addListener(RavenBadgeRuntime::onPlayerLoggedIn);
            NeoForge.EVENT_BUS.addListener(RavenBadgeRuntime::onPlayerLoggedOut);
            registered = true;
            LOG.debug("[RavenBadgeRuntime] Registered server listeners");
        } catch (Throwable t) {
            LOG.error("[RavenBadgeRuntime] register failed safely", t);
        }
    }

    private static void onServerTickPost(@NotNull ServerTickEvent.Post event) {
        try {
            MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }

            tickCounter++;
            if ((tickCounter % BASE_SYNC_INTERVAL_TICKS) != 0) {
                return;
            }

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == null || !player.isAlive() || player.isRemoved()) {
                    continue;
                }
                pushBaseState(player, false);
            }
        } catch (Throwable t) {
            LOG.error("[RavenBadgeRuntime] onServerTickPost failed safely", t);
        }
    }

    private static void onPlayerLoggedIn(@NotNull PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                pushBaseState(player, true);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeRuntime] onPlayerLoggedIn failed safely: {}", t.toString());
        }
    }

    private static void onPlayerLoggedOut(@NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                UUID id = player.getUUID();
                LAST_BASE_STATE.remove(id);
                LAST_BASE_SYNC_TICK.remove(id);
                LAST_EVENT_TICKS.remove(id);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeRuntime] onPlayerLoggedOut failed safely: {}", t.toString());
        }
    }

    public static void pushBaseState(@NotNull ServerPlayer player, boolean force) {
        try {
            if (player.isRemoved() || !player.isAlive()) {
                return;
            }
            RavenBadgeBaseState baseState = computeBaseState(player);
            long nowTick = safeNowTick(player);
            sendStatus(player, baseState, RavenBadgeEventType.NONE, force, nowTick, 0L);
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeRuntime] pushBaseState failed safely for player='{}': {}",
                    safePlayerName(player), t.toString());
        }
    }

    public static void onCourierJobUpdated(@NotNull ServerLevel level,
                                           @Nullable UUID senderUuid,
                                           @Nullable RavenBadgeEventType eventType) {
        try {
            if (level.getServer() == null || senderUuid == null) {
                return;
            }
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(senderUuid);
            if (owner == null || owner.isRemoved() || !owner.isAlive()) {
                return;
            }
            RavenBadgeBaseState baseState = computeBaseState(owner);
            RavenBadgeEventType safeEvent = eventType == null ? RavenBadgeEventType.NONE : eventType;
            long nowTick = safeNowTick(owner);
            long cooldown = eventCooldownTicks(safeEvent);
            sendStatus(owner, baseState, safeEvent, false, nowTick, cooldown);
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeRuntime] onCourierJobUpdated failed safely: {}", t.toString());
        }
    }

    public static void onRavenHit(@NotNull RavenEntity raven, boolean dodged) {
        try {
            if (!(raven.level() instanceof ServerLevel level) || level.isClientSide()) {
                return;
            }

            // Courier landed hits are resolved by RavenCourierRuntime with either:
            // - HIT_KEEP_SCROLL (payload protected retry), or
            // - HIT_LOSE_SCROLL (payload dropped).
            // Skip the generic hit event here for that specific path to avoid double one-shots.
            if (!dodged && isCourierDeliveryRaven(raven)) {
                return;
            }

            ServerPlayer owner = resolveOnlineOwner(level.getServer(), raven);
            if (owner == null) {
                return;
            }
            RavenBadgeBaseState baseState = computeBaseState(owner);
            RavenBadgeEventType hitEvent = dodged
                    ? RavenBadgeEventType.DODGED
                    : RavenBadgeEventType.HIT;
            sendStatus(
                    owner,
                    baseState,
                    hitEvent,
                    false,
                    level.getGameTime(),
                    0L
            );
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeRuntime] onRavenHit failed safely for raven id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    public static void onRavenThreatDetected(@NotNull RavenEntity raven, boolean hasScrollPayload) {
        try {
            if (!(raven.level() instanceof ServerLevel level) || level.isClientSide()) {
                return;
            }
            ServerPlayer owner = resolveOnlineOwner(level.getServer(), raven);
            if (owner == null) {
                return;
            }

            RavenBadgeEventType eventType = hasScrollPayload
                    ? RavenBadgeEventType.HOSTILE_WITH_SCROLL
                    : RavenBadgeEventType.HOSTILE_NO_SCROLL;

            RavenBadgeBaseState baseState = computeBaseState(owner);
            sendStatus(
                    owner,
                    baseState,
                    eventType,
                    false,
                    level.getGameTime(),
                    THREAT_EVENT_COOLDOWN_TICKS
            );
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeRuntime] onRavenThreatDetected failed safely for raven id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    public static void onRavenDeliveryRepath(@NotNull RavenEntity raven) {
        try {
            if (!(raven.level() instanceof ServerLevel level) || level.isClientSide()) {
                return;
            }
            ServerPlayer owner = resolveOnlineOwner(level.getServer(), raven);
            if (owner == null) {
                return;
            }
            RavenBadgeBaseState baseState = computeBaseState(owner);
            sendStatus(
                    owner,
                    baseState,
                    RavenBadgeEventType.PLAYER_NOT_FOUND,
                    false,
                    level.getGameTime(),
                    REPATH_EVENT_COOLDOWN_TICKS
            );
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeRuntime] onRavenDeliveryRepath failed safely for raven id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    private static void sendStatus(@NotNull ServerPlayer owner,
                                   @NotNull RavenBadgeBaseState baseState,
                                   @NotNull RavenBadgeEventType eventType,
                                   boolean forceBase,
                                   long nowTick,
                                   long eventCooldownTicks) {
        try {
            UUID ownerId = owner.getUUID();
            RavenBadgeBaseState lastBase = LAST_BASE_STATE.get(ownerId);
            long lastSyncTick = LAST_BASE_SYNC_TICK.getOrDefault(ownerId, 0L);

            boolean eventRequested = eventType != RavenBadgeEventType.NONE;
            if (eventRequested && eventCooldownTicks > 0L && isEventCoolingDown(ownerId, eventType, nowTick, eventCooldownTicks)) {
                eventRequested = false;
                eventType = RavenBadgeEventType.NONE;
            }

            boolean baseChanged = (lastBase != baseState);
            boolean baseResyncDue = (nowTick - lastSyncTick) >= FORCED_RESYNC_TICKS;
            boolean shouldSend = eventRequested || forceBase || baseChanged || baseResyncDue;
            if (!shouldSend) {
                return;
            }

            FFNetwork.sendRavenBadgeStatus(owner, baseState, eventType);
            LAST_BASE_STATE.put(ownerId, baseState);
            LAST_BASE_SYNC_TICK.put(ownerId, nowTick);
            if (eventRequested) {
                markEventSent(ownerId, eventType, nowTick);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeRuntime] sendStatus failed safely for player='{}': {}",
                    safePlayerName(owner), t.toString());
        }
    }

    private static boolean isEventCoolingDown(@NotNull UUID ownerId,
                                              @NotNull RavenBadgeEventType eventType,
                                              long nowTick,
                                              long cooldownTicks) {
        try {
            EnumMap<RavenBadgeEventType, Long> perOwner = LAST_EVENT_TICKS.get(ownerId);
            if (perOwner == null) {
                return false;
            }
            long lastTick = perOwner.getOrDefault(eventType, 0L);
            return (nowTick - lastTick) < cooldownTicks;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void markEventSent(@NotNull UUID ownerId,
                                      @NotNull RavenBadgeEventType eventType,
                                      long nowTick) {
        EnumMap<RavenBadgeEventType, Long> perOwner =
                LAST_EVENT_TICKS.computeIfAbsent(ownerId, k -> new EnumMap<>(RavenBadgeEventType.class));
        perOwner.put(eventType, nowTick);
    }

    private static long eventCooldownTicks(@NotNull RavenBadgeEventType eventType) {
        return switch (eventType) {
            case HOSTILE_WITH_SCROLL, HOSTILE_NO_SCROLL -> THREAT_EVENT_COOLDOWN_TICKS;
            case PLAYER_NOT_FOUND -> REPATH_EVENT_COOLDOWN_TICKS;
            default -> 0L;
        };
    }

    private static @NotNull RavenBadgeBaseState computeBaseState(@NotNull ServerPlayer owner) {
        try {
            TamedRavenPlayerData.TamedRavenInfo info = TamedRavenPlayerData.getTamedRavenInfo(owner);
            boolean hasCurrentTamedRaven = info != null && info.hasTamedRaven();
            boolean hasEverTamedRaven = TamedRavenPlayerData.hasEverTamedRaven(owner) || hasCurrentTamedRaven;

            if (!hasEverTamedRaven) {
                return RavenBadgeBaseState.HIDDEN;
            }

            if (!hasCurrentTamedRaven || (info != null && info.currentHealth() <= 0.0F)) {
                return RavenBadgeBaseState.DEAD;
            }

            RavenCourierData data = RavenCourierData.get(owner.serverLevel());
            UUID ownerId = owner.getUUID();

            RavenCourierData.DeliveryJob inFlight = data.getMostRecentInFlightJobForSender(ownerId);
            if (inFlight != null && !inFlight.failed) {
                return RavenBadgeBaseState.DELIVERING_WITH_SCROLL;
            }

            RavenCourierData.DeliveryJob queued = data.getMostRecentQueuedJobForSender(ownerId);
            RavenCourierData.DeliveryJob failed = data.getMostRecentFailedJobForSender(ownerId);
            if (queued != null || failed != null) {
                return RavenBadgeBaseState.QUEUED_WITH_SCROLL;
            }

            if (hasActiveScrollSummonedRaven(owner)) {
                return RavenBadgeBaseState.MOVING_NO_SCROLL;
            }

            return RavenBadgeBaseState.IDLE_NO_SCROLL;
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeRuntime] computeBaseState failed safely for player='{}': {}",
                    safePlayerName(owner), t.toString());
            return RavenBadgeBaseState.HIDDEN;
        }
    }

    private static boolean hasActiveScrollSummonedRaven(@NotNull ServerPlayer owner) {
        try {
            if (owner.serverLevel() == null) {
                return false;
            }

            UUID ownerId = owner.getUUID();
            ServerLevel level = owner.serverLevel();
            AABB search = owner.getBoundingBox().inflate(96.0D, 32.0D, 96.0D);
            List<RavenEntity> ravens = level.getEntitiesOfClass(
                    RavenEntity.class,
                    search,
                    raven -> raven != null
                            && raven.isAlive()
                            && !raven.isRemoved()
                            && ownerId.equals(raven.getOwnerUUID())
                            && TamedRavenScrollWatcher.isScrollSummonedRaven(raven)
                            && !raven.getTags().contains("ff_courier_raven")
            );
            return !ravens.isEmpty();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static @Nullable ServerPlayer resolveOnlineOwner(@Nullable MinecraftServer server,
                                                             @NotNull RavenEntity raven) {
        try {
            if (server == null) {
                return null;
            }
            UUID ownerId = raven.getOwnerUUID();
            if (ownerId == null) {
                return null;
            }
            return server.getPlayerList().getPlayer(ownerId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isCourierDeliveryRaven(@NotNull RavenEntity raven) {
        try {
            if (raven.getTags().contains("ff_courier_raven")) {
                return true;
            }
            return raven.getPersistentData().getLong("ff_courier_job_id") > 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long safeNowTick(@NotNull ServerPlayer player) {
        try {
            return Math.max(0L, player.serverLevel().getGameTime());
        } catch (Throwable ignored) {
            return 0L;
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
