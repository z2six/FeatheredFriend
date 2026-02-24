// neoforge/src/main/java/net/z2six/featheredfriend/network/RavenNameCancelledPacket.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * C2S packet sent when the player cancels the raven naming GUI.
 */
public record RavenNameCancelledPacket(int ravenEntityId) {

    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull RavenNameCancelledPacket payload, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(payload.ravenEntityId());
        } catch (Throwable t) {
            LOG.error("[RavenNameCancelledPacket] encode failed", t);
        }
    }

    public static @NotNull RavenNameCancelledPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            int id = buf.readVarInt();
            return new RavenNameCancelledPacket(id);
        } catch (Throwable t) {
            LOG.error("[RavenNameCancelledPacket] decode failed", t);
            return new RavenNameCancelledPacket(-1);
        }
    }

    /**
     * SERVER handler (called from FFNetwork).
     */
    public static void handle(@NotNull RavenNameCancelledPacket payload,
                              @NotNull ServerPlayer serverPlayer) {
        try {
            if (serverPlayer == null) return;
            if (!(serverPlayer.level() instanceof ServerLevel serverLevel)) return;

            if (payload.ravenEntityId() <= 0) {
                LOG.warn("[RavenNameCancelledPacket] handle: invalid ravenEntityId={} from player={}",
                        payload.ravenEntityId(), serverPlayer.getGameProfile().getName());
                return;
            }

            Entity e = serverLevel.getEntity(payload.ravenEntityId());
            if (!(e instanceof RavenEntity raven)) {
                LOG.warn("[RavenNameCancelledPacket] handle: entityId={} is not a RavenEntity (player={})",
                        payload.ravenEntityId(), serverPlayer.getGameProfile().getName());
                return;
            }

            TamedRaven tamed = raven.getTamedRavenModule();
            if (tamed == null) {
                LOG.warn("[RavenNameCancelledPacket] handle: raven id={} has no TamedRaven module (player={})",
                        raven.getId(), serverPlayer.getGameProfile().getName());
                return;
            }

            tamed.onNamingCancelled(serverPlayer);

        } catch (Throwable t) {
            LOG.error("[RavenNameCancelledPacket] handle failed safely for player={}",
                    serverPlayer.getGameProfile().getName(), t);
        }
    }
}
