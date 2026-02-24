package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public record RavenLinkEffigyPoseSnapshotPacket(
        float headXRot, float headYRot, float headZRot,
        float bodyXRot, float bodyYRot, float bodyZRot,
        float rightArmXRot, float rightArmYRot, float rightArmZRot,
        float leftArmXRot, float leftArmYRot, float leftArmZRot,
        float rightLegXRot, float rightLegYRot, float rightLegZRot,
        float leftLegXRot, float leftLegYRot, float leftLegZRot
) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull RavenLinkEffigyPoseSnapshotPacket msg, @NotNull FriendlyByteBuf buf) {
        try {
            buf.writeFloat(msg.headXRot());
            buf.writeFloat(msg.headYRot());
            buf.writeFloat(msg.headZRot());

            buf.writeFloat(msg.bodyXRot());
            buf.writeFloat(msg.bodyYRot());
            buf.writeFloat(msg.bodyZRot());

            buf.writeFloat(msg.rightArmXRot());
            buf.writeFloat(msg.rightArmYRot());
            buf.writeFloat(msg.rightArmZRot());

            buf.writeFloat(msg.leftArmXRot());
            buf.writeFloat(msg.leftArmYRot());
            buf.writeFloat(msg.leftArmZRot());

            buf.writeFloat(msg.rightLegXRot());
            buf.writeFloat(msg.rightLegYRot());
            buf.writeFloat(msg.rightLegZRot());

            buf.writeFloat(msg.leftLegXRot());
            buf.writeFloat(msg.leftLegYRot());
            buf.writeFloat(msg.leftLegZRot());
        } catch (Throwable t) {
            LOG.error("[RavenLinkEffigyPoseSnapshotPacket] encode failed", t);
        }
    }

    public static @NotNull RavenLinkEffigyPoseSnapshotPacket decode(@NotNull FriendlyByteBuf buf) {
        try {
            return new RavenLinkEffigyPoseSnapshotPacket(
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat()
            );
        } catch (Throwable t) {
            LOG.error("[RavenLinkEffigyPoseSnapshotPacket] decode failed, returning safe default", t);
            return new RavenLinkEffigyPoseSnapshotPacket(
                    0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 0.0F
            );
        }
    }
}

