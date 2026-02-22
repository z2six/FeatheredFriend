package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-only utility/easter-egg commands.
 */
public final class FFClientCommands {

    private static final Logger LOG = LogUtils.getLogger();
    private static volatile boolean REGISTERED = false;
    private static final double RAVEN_COMMAND_RADIUS = 16.0D;

    private FFClientCommands() {
    }

    public static void registerGameBus() {
        try {
            if (REGISTERED) {
                return;
            }
            NeoForge.EVENT_BUS.addListener(FFClientCommands::onRegisterClientCommands);
            REGISTERED = true;
        } catch (Throwable t) {
            LOG.error("[FFClientCommands] registerGameBus failed safely", t);
        }
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        try {
            event.getDispatcher().register(
                    Commands.literal("archemagos")
                            .then(Commands.literal("loves")
                                    .then(Commands.literal("ravens")
                                            .executes(ctx -> runArchemagosLovesRavens(ctx.getSource()))))
            );
        } catch (Throwable t) {
            LOG.error("[FFClientCommands] onRegisterClientCommands failed safely", t);
        }
    }

    private static int runArchemagosLovesRavens(CommandSourceStack source) {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            if (player == null || level == null) {
                source.sendFailure(Component.translatable("message.featheredfriend.client_cmd.no_client_world"));
                return 0;
            }

            double radiusSqr = RAVEN_COMMAND_RADIUS * RAVEN_COMMAND_RADIUS;
            List<RavenEntity> nearbyRavens = level.getEntitiesOfClass(
                    RavenEntity.class,
                    player.getBoundingBox().inflate(RAVEN_COMMAND_RADIUS),
                    raven -> raven != null && raven.isAlive() && !raven.isRemoved()
            );
            RavenEntity closestRaven = null;
            double closestDistanceSqr = radiusSqr + 1.0D;

            for (RavenEntity raven : nearbyRavens) {
                double distSqr = raven.distanceToSqr(player);
                if (distSqr <= radiusSqr && distSqr < closestDistanceSqr) {
                    closestDistanceSqr = distSqr;
                    closestRaven = raven;
                }
            }

            if (closestRaven == null) {
                source.sendFailure(Component.translatable("message.featheredfriend.client_cmd.no_raven_nearby", Integer.valueOf((int) RAVEN_COMMAND_RADIUS)));
                return 0;
            }

            faceRavenTowardPlayer(closestRaven, player);
            spawnHeartBurst(level, closestRaven);

            source.sendSuccess(() -> Component.translatable("message.featheredfriend.client_cmd.raven_noticed"), false);
            return 1;
        } catch (Throwable t) {
            LOG.error("[FFClientCommands] runArchemagosLovesRavens failed safely", t);
            source.sendFailure(Component.translatable("message.featheredfriend.client_cmd.failed"));
            return 0;
        }
    }

    private static void faceRavenTowardPlayer(RavenEntity raven, LocalPlayer player) {
        Vec3 delta = player.getEyePosition().subtract(raven.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontal < 1.0E-4D && Math.abs(delta.y) < 1.0E-4D) {
            return;
        }

        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D));
        float pitch = Mth.clamp((float) (-Math.toDegrees(Math.atan2(delta.y, horizontal))), -89.9F, 89.9F);

        raven.setYRot(yaw);
        raven.yRotO = yaw;
        raven.setYHeadRot(yaw);
        raven.yHeadRotO = yaw;
        raven.yBodyRot = yaw;
        raven.yBodyRotO = yaw;

        raven.setXRot(pitch);
        raven.xRotO = pitch;
    }

    private static void spawnHeartBurst(ClientLevel level, RavenEntity raven) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double width = Math.max(0.6D, raven.getBbWidth());
        double centerX = raven.getX();
        double centerY = raven.getY();
        double centerZ = raven.getZ();

        for (int i = 0; i < 8; i++) {
            double x = centerX + (random.nextDouble() - 0.5D) * width;
            double y = centerY + 0.35D + random.nextDouble() * 0.9D;
            double z = centerZ + (random.nextDouble() - 0.5D) * width;
            double vx = (random.nextDouble() - 0.5D) * 0.02D;
            double vy = 0.02D + random.nextDouble() * 0.02D;
            double vz = (random.nextDouble() - 0.5D) * 0.02D;
            level.addParticle(ParticleTypes.HEART, x, y, z, vx, vy, vz);
        }
    }
}
