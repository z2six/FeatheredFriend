package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.log.RavenLogCategory;
import net.z2six.featheredfriend.log.RavenLogTextCodec;
import net.z2six.featheredfriend.network.RavenLogEntryInfo;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Convenience API for writing/reading persistent Raven Log entries.
 */
public final class RavenLogService {

    private static final Logger LOG = LogUtils.getLogger();

    private RavenLogService() {
    }

    public static void logForPlayer(@NotNull ServerPlayer player,
                                    @NotNull RavenLogCategory category,
                                    @NotNull String message) {
        if (player == null) {
            return;
        }
        logForPlayer(player.serverLevel(), player.getUUID(), category, message);
    }

    public static void logForPlayerKey(@NotNull ServerPlayer player,
                                       @NotNull RavenLogCategory category,
                                       @NotNull String key,
                                       @Nullable Object... args) {
        if (player == null) {
            return;
        }
        logForPlayerKey(player.serverLevel(), player.getUUID(), category, key, args);
    }

    public static void logForPlayer(@NotNull ServerLevel level,
                                    @Nullable UUID playerUuid,
                                    @NotNull RavenLogCategory category,
                                    @NotNull String message) {
        if (level == null || level.isClientSide() || playerUuid == null || category == null) {
            return;
        }
        try {
            RavenLogData data = RavenLogData.get(level);
            long nowMillis = System.currentTimeMillis();
            data.append(
                    playerUuid,
                    category,
                    message == null ? "" : message,
                    level.getGameTime(),
                    nowMillis,
                    getRetentionMinutes(),
                    getMaxBytesPerPlayer()
            );
        } catch (Throwable t) {
            LOG.warn("[RavenLogService] logForPlayer failed safely: {}", t.toString());
        }
    }

    public static void logForPlayerKey(@NotNull ServerLevel level,
                                       @Nullable UUID playerUuid,
                                       @NotNull RavenLogCategory category,
                                       @NotNull String key,
                                       @Nullable Object... args) {
        if (level == null || level.isClientSide() || playerUuid == null || category == null) {
            return;
        }
        String packed = RavenLogTextCodec.packTranslatable(key, args);
        if (packed.isBlank()) {
            return;
        }
        logForPlayer(level, playerUuid, category, packed);
    }

    public static void logForRavenOwner(@NotNull RavenEntity raven,
                                        @NotNull RavenLogCategory category,
                                        @NotNull String message) {
        if (raven == null) {
            return;
        }
        try {
            if (!(raven.level() instanceof ServerLevel level) || level.isClientSide()) {
                return;
            }
            UUID ownerUuid = raven.getOwnerUUID();
            if (ownerUuid == null) {
                return;
            }
            logForPlayer(level, ownerUuid, category, message);
        } catch (Throwable t) {
            LOG.warn("[RavenLogService] logForRavenOwner failed safely: {}", t.toString());
        }
    }

    public static void logForRavenOwnerKey(@NotNull RavenEntity raven,
                                           @NotNull RavenLogCategory category,
                                           @NotNull String key,
                                           @Nullable Object... args) {
        if (raven == null) {
            return;
        }
        try {
            if (!(raven.level() instanceof ServerLevel level) || level.isClientSide()) {
                return;
            }
            UUID ownerUuid = raven.getOwnerUUID();
            if (ownerUuid == null) {
                return;
            }
            logForPlayerKey(level, ownerUuid, category, key, args);
        } catch (Throwable t) {
            LOG.warn("[RavenLogService] logForRavenOwnerKey failed safely: {}", t.toString());
        }
    }

    public static @NotNull List<RavenLogEntryInfo> getEntriesForPlayer(@NotNull ServerPlayer player) {
        if (player == null) {
            return List.of();
        }
        try {
            RavenLogData data = RavenLogData.get(player.serverLevel());
            return data.getEntriesForPlayer(
                    player.getUUID(),
                    System.currentTimeMillis(),
                    getRetentionMinutes(),
                    getMaxBytesPerPlayer()
            );
        } catch (Throwable t) {
            LOG.warn("[RavenLogService] getEntriesForPlayer failed safely: {}", t.toString());
            return List.of();
        }
    }

    public static void pruneAll(@NotNull ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        try {
            RavenLogData data = RavenLogData.get(level);
            data.pruneAll(
                    System.currentTimeMillis(),
                    getRetentionMinutes(),
                    getMaxBytesPerPlayer()
            );
        } catch (Throwable t) {
            LOG.warn("[RavenLogService] pruneAll failed safely: {}", t.toString());
        }
    }

    public static boolean clearForPlayer(@NotNull ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            RavenLogData data = RavenLogData.get(player.serverLevel());
            return data.clearPlayer(player.getUUID());
        } catch (Throwable t) {
            LOG.warn("[RavenLogService] clearForPlayer failed safely: {}", t.toString());
            return false;
        }
    }

    private static int getRetentionMinutes() {
        try {
            return Math.max(0, Services.PLATFORM.getRavenLogRetentionMinutes());
        } catch (Throwable ignored) {
            return 60 * 24 * 7;
        }
    }

    private static int getMaxBytesPerPlayer() {
        try {
            return Math.max(0, Services.PLATFORM.getRavenLogMaxBytesPerPlayer());
        } catch (Throwable ignored) {
            return 262_144;
        }
    }
}
