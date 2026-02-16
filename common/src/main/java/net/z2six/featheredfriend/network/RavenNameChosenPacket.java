// neoforge/src/main/java/net/z2six/featheredfriend/network/RavenNameChosenPacket.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/network/RavenNameChosenPacket.java
 *
 * C2S packet sent when the player confirms a raven name in the naming GUI.
 */
public record RavenNameChosenPacket(int ravenEntityId, String name) implements CustomPacketPayload {

    private static final Logger LOG = LogUtils.getLogger();

    public static final Type<RavenNameChosenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "raven_name_chosen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RavenNameChosenPacket> STREAM_CODEC =
            StreamCodec.of(RavenNameChosenPacket::encode, RavenNameChosenPacket::decode);

    private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                               @NotNull RavenNameChosenPacket payload) {
        try {
            buf.writeVarInt(payload.ravenEntityId());
            buf.writeUtf(payload.name() != null ? payload.name() : "", 64);
        } catch (Throwable t) {
            LOG.error("[RavenNameChosenPacket] encode failed", t);
        }
    }

    private static @NotNull RavenNameChosenPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
        try {
            int id = buf.readVarInt();
            String name = buf.readUtf(64);
            return new RavenNameChosenPacket(id, name);
        } catch (Throwable t) {
            LOG.error("[RavenNameChosenPacket] decode failed", t);
            return new RavenNameChosenPacket(-1, "");
        }
    }

    @Override
    public @NotNull Type<RavenNameChosenPacket> type() {
        return TYPE;
    }

    /**
     * SERVER handler (called from FFNetwork).
     */
    public static void handle(@NotNull RavenNameChosenPacket payload,
                              @NotNull ServerPlayer serverPlayer) {
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
