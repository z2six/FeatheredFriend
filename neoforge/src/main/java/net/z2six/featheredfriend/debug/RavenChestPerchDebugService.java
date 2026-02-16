package net.z2six.featheredfriend.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.z2six.featheredfriend.block.RavenChestBlock;
import net.z2six.featheredfriend.entity.raven.RavenAIState;
import net.z2six.featheredfriend.entity.raven.RavenAnimMode;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.network.RavenChestPerchDebugAdjustPacket;
import net.z2six.featheredfriend.registry.FFBlocks;
import net.z2six.featheredfriend.registry.FFEntities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side debug utility for positioning a raven on top of Raven Chests.
 */
public final class RavenChestPerchDebugService {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int SEARCH_RADIUS = 48;

    // Base spawn offset from chest block position (bottom block).
    private static final double DEFAULT_OFFSET_X = 0.5D;
    private static final double DEFAULT_OFFSET_Y = 1.6D;
    private static final double DEFAULT_OFFSET_Z = 0.5D;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, LastPose> LAST_POSES = new ConcurrentHashMap<>();
    private static volatile boolean REGISTERED = false;

    private RavenChestPerchDebugService() {
    }

    public static void register() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;
        NeoForge.EVENT_BUS.addListener(RavenChestPerchDebugService::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(RavenChestPerchDebugService::onLevelTickPost);
    }

    public static boolean startFor(@NotNull ServerPlayer player) {
        try {
            ServerLevel level = player.serverLevel();
            BlockPos chestPos = findNearestRavenChest(level, player.blockPosition(), SEARCH_RADIUS);
            if (chestPos == null) {
                return false;
            }

            stopFor(player, true);

            RavenEntity raven = FFEntities.RAVEN.get().create(level);
            if (raven == null) {
                LOG.error("[RavenChestPerchDebugService] Failed to create raven entity");
                return false;
            }

            LastPose remembered = LAST_POSES.get(player.getUUID());
            float yaw = defaultYawFromChest(level, chestPos);
            Session session = new Session(
                    player.getUUID(),
                    raven.getUUID(),
                    level.dimension().location().toString(),
                    chestPos.immutable(),
                    remembered != null ? remembered.offsetX : DEFAULT_OFFSET_X,
                    remembered != null ? remembered.offsetY : DEFAULT_OFFSET_Y,
                    remembered != null ? remembered.offsetZ : DEFAULT_OFFSET_Z,
                    yaw,
                    remembered != null ? remembered.pitch : 0.0F
            );

            applySessionPose(level, raven, session);
            raven.setPersistenceRequired();
            level.addFreshEntity(raven);
            // Re-apply right after spawn so initial rendered facing matches locked debug yaw immediately.
            applySessionPose(level, raven, session);
            SESSIONS.put(player.getUUID(), session);

            FFNetwork.sendOpenRavenChestPerchDebugScreen(
                    player,
                    raven.getId(),
                    chestPos,
                    session.offsetX,
                    session.offsetY,
                    session.offsetZ,
                    session.yaw,
                    session.pitch
            );
            return true;
        } catch (Throwable t) {
            LOG.error("[RavenChestPerchDebugService] startFor failed for player={}", player.getGameProfile().getName(), t);
            return false;
        }
    }

    public static boolean stopFor(@NotNull ServerPlayer player, boolean discardRaven) {
        Session removed = SESSIONS.remove(player.getUUID());
        if (removed == null) {
            return false;
        }

        LAST_POSES.put(player.getUUID(), LastPose.fromSession(removed));

        if (discardRaven) {
            try {
                ServerLevel level = resolveLevel(player, removed);
                if (level != null) {
                    var e = level.getEntity(removed.ravenUuid);
                    if (e instanceof RavenEntity raven) {
                        raven.discard();
                    }
                }
            } catch (Throwable t) {
                LOG.warn("[RavenChestPerchDebugService] stopFor: failed to discard debug raven for {}", player.getGameProfile().getName(), t);
            }
        }
        return true;
    }

    public static void applyAdjustment(@NotNull ServerPlayer player, @NotNull RavenChestPerchDebugAdjustPacket packet) {
        try {
            Session session = SESSIONS.get(player.getUUID());
            if (session == null) {
                return;
            }

            ServerLevel level = resolveLevel(player, session);
            if (level == null) {
                SESSIONS.remove(player.getUUID());
                return;
            }

            var e = level.getEntity(session.ravenUuid);
            if (!(e instanceof RavenEntity raven) || !raven.isAlive()) {
                SESSIONS.remove(player.getUUID());
                return;
            }

            if (packet.ravenEntityId() != raven.getId()) {
                return;
            }

            session.offsetX = clamp(packet.offsetX(), -3.0D, 3.0D);
            session.offsetY = clamp(packet.offsetY(), 0.5D, 5.0D);
            session.offsetZ = clamp(packet.offsetZ(), -3.0D, 3.0D);
            session.yaw = defaultYawFromChest(level, session.chestPos);
            session.pitch = (float) clamp(packet.pitch(), -89.9D, 89.9D);
            LAST_POSES.put(player.getUUID(), LastPose.fromSession(session));

            applySessionPose(level, raven, session);
        } catch (Throwable t) {
            LOG.error("[RavenChestPerchDebugService] applyAdjustment failed for player={}", player.getGameProfile().getName(), t);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            stopFor(sp, true);
        }
    }

    private static void onLevelTickPost(LevelTickEvent.Post event) {
        try {
            if (SESSIONS.isEmpty()) {
                return;
            }
            if (!(event.getLevel() instanceof ServerLevel level)) {
                return;
            }

            String dim = level.dimension().location().toString();
            List<UUID> staleOwners = new ArrayList<>();

            for (Session session : SESSIONS.values()) {
                if (session == null || !session.dimensionLocation.equals(dim)) {
                    continue;
                }

                var e = level.getEntity(session.ravenUuid);
                if (!(e instanceof RavenEntity raven) || !raven.isAlive()) {
                    staleOwners.add(session.ownerUuid);
                    continue;
                }

                // Enforce pose every tick so no internal idle look-around drift can occur.
                applySessionPose(level, raven, session);
            }

            for (UUID owner : staleOwners) {
                Session removed = SESSIONS.remove(owner);
                if (removed != null) {
                    LAST_POSES.put(owner, LastPose.fromSession(removed));
                }
            }
        } catch (Throwable t) {
            LOG.error("[RavenChestPerchDebugService] onLevelTickPost failed", t);
        }
    }

    private static void applySessionPose(@NotNull ServerLevel level, @NotNull RavenEntity raven, @NotNull Session session) {
        // Raven yaw is always clamped to the chest's facing so perched ravens align with chest front.
        session.yaw = defaultYawFromChest(level, session.chestPos);
        session.pitch = (float) clamp(session.pitch, -89.9D, 89.9D);

        Vec3 pos = new Vec3(
                session.chestPos.getX() + session.offsetX,
                session.chestPos.getY() + session.offsetY,
                session.chestPos.getZ() + session.offsetZ
        );

        raven.moveTo(pos.x, pos.y, pos.z, session.yaw, session.pitch);
        raven.setYRot(session.yaw);
        raven.setYHeadRot(session.yaw);
        raven.yBodyRot = session.yaw;
        raven.setXRot(session.pitch);
        raven.setRavenChestPerchLockRotation(session.yaw, session.pitch);
        raven.setDeltaMovement(Vec3.ZERO);
        raven.setNoGravity(true);
        raven.setNoAi(false);
        raven.setAIState(RavenAIState.RAVEN_CHEST_PERCH);
        raven.setAnimMode(RavenAnimMode.NO_AIR);
    }

    private static float defaultYawFromChest(@NotNull ServerLevel level, @NotNull BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            if (state.hasProperty(RavenChestBlock.FACING)) {
                Direction facing = state.getValue(RavenChestBlock.FACING);
                return normalizeYaw(facing.toYRot());
            }
        } catch (Throwable ignored) {
        }
        return 0.0F;
    }

    private static @Nullable BlockPos findNearestRavenChest(@NotNull ServerLevel level,
                                                            @NotNull BlockPos origin,
                                                            int radius) {
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        BlockPos min = origin.offset(-radius, -radius, -radius);
        BlockPos max = origin.offset(radius, radius, radius);

        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(p).is(FFBlocks.RAVEN_CHEST.get())) {
                double d = p.distSqr(origin);
                if (d < nearestDistSq) {
                    nearestDistSq = d;
                    nearest = p.immutable();
                }
            }
        }
        return nearest;
    }

    private static @Nullable ServerLevel resolveLevel(@NotNull ServerPlayer player, @NotNull Session session) {
        try {
            var server = player.getServer();
            if (server == null) {
                return null;
            }
            var key = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(session.dimensionLocation)
            );
            return server.getLevel(key);
        } catch (Throwable t) {
            LOG.warn("[RavenChestPerchDebugService] resolveLevel failed for dimension={}", session.dimensionLocation, t);
            return null;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float normalizeYaw(float yaw) {
        return Mth.wrapDegrees(yaw);
    }

    private static final class Session {
        private final UUID ownerUuid;
        private final UUID ravenUuid;
        private final String dimensionLocation;
        private final BlockPos chestPos;
        private double offsetX;
        private double offsetY;
        private double offsetZ;
        private float yaw;
        private float pitch;

        private Session(UUID ownerUuid,
                        UUID ravenUuid,
                        String dimensionLocation,
                        BlockPos chestPos,
                        double offsetX,
                        double offsetY,
                        double offsetZ,
                        float yaw,
                        float pitch) {
            this.ownerUuid = ownerUuid;
            this.ravenUuid = ravenUuid;
            this.dimensionLocation = dimensionLocation;
            this.chestPos = chestPos;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class LastPose {
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;
        private final float yaw;
        private final float pitch;

        private LastPose(double offsetX, double offsetY, double offsetZ, float yaw, float pitch) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        private static @NotNull LastPose fromSession(@NotNull Session session) {
            return new LastPose(
                    session.offsetX,
                    session.offsetY,
                    session.offsetZ,
                    normalizeYaw(session.yaw),
                    session.pitch
            );
        }
    }
}
