package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import org.slf4j.Logger;

/**
 * C2S message sent when the player confirms a raven name in the naming GUI.
 */
public record RavenNameChosenPacket(int ravenEntityId, String name) {

    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(RavenNameChosenPacket msg, FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(msg.ravenEntityId());
            buf.writeUtf(msg.name() != null ? msg.name() : "", 64);
        } catch (Throwable t) {
            LOG.error("[RavenNameChosenPacket] encode failed", t);
        }
    }

    public static RavenNameChosenPacket decode(FriendlyByteBuf buf) {
        try {
            int id = buf.readVarInt();
            String name = buf.readUtf(64);
            return new RavenNameChosenPacket(id, name);
        } catch (Throwable t) {
            LOG.error("[RavenNameChosenPacket] decode failed", t);
            return new RavenNameChosenPacket(-1, "");
        }
    }

    /**
     * SERVER handler (called from FFNetwork).
     */
    public static void handle(RavenNameChosenPacket payload, ServerPlayer serverPlayer) {
        try {
            if (serverPlayer == null) return;
            if (!(serverPlayer.level() instanceof ServerLevel serverLevel)) return;

            if (payload.ravenEntityId() <= 0) {
                LOG.warn("[RavenNameChosenPacket] handle: invalid ravenEntityId={} from player={}",
                        payload.ravenEntityId(), serverPlayer.getGameProfile().getName());
                return;
            }

            Entity e = serverLevel.getEntity(payload.ravenEntityId());
            if (!(e instanceof RavenEntity raven)) {
                LOG.warn("[RavenNameChosenPacket] handle: entityId={} is not a RavenEntity (player={})",
                        payload.ravenEntityId(), serverPlayer.getGameProfile().getName());
                return;
            }

            TamedRaven tamed = raven.getTamedRavenModule();
            if (tamed == null) {
                LOG.warn("[RavenNameChosenPacket] handle: raven id={} has no TamedRaven module (player={})",
                        raven.getId(), serverPlayer.getGameProfile().getName());
                return;
            }

            tamed.onNameChosenFromClient(serverPlayer, payload.name());

        } catch (Throwable t) {
            LOG.error("[RavenNameChosenPacket] handle failed safely for player={}",
                    serverPlayer.getGameProfile().getName(), t);
        }
    }
}
