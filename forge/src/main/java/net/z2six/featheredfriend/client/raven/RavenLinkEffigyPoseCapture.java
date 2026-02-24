package net.z2six.featheredfriend.client.raven;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Captures the local player's last-rendered humanoid limb rotations so Raven Link effigies
 * can spawn as a frozen "snapshot" (mid-walk, mid-jump, etc) instead of a generic standing pose.
 */
public final class RavenLinkEffigyPoseCapture {

    private static final Logger LOG = LogUtils.getLogger();
    private static volatile boolean registered = false;

    private static volatile boolean hasSnapshot = false;

    private static volatile float headXRot = 0.0F;
    private static volatile float headYRot = 0.0F;
    private static volatile float headZRot = 0.0F;

    private static volatile float bodyXRot = 0.0F;
    private static volatile float bodyYRot = 0.0F;
    private static volatile float bodyZRot = 0.0F;

    private static volatile float rightArmXRot = 0.0F;
    private static volatile float rightArmYRot = 0.0F;
    private static volatile float rightArmZRot = 0.0F;

    private static volatile float leftArmXRot = 0.0F;
    private static volatile float leftArmYRot = 0.0F;
    private static volatile float leftArmZRot = 0.0F;

    private static volatile float rightLegXRot = 0.0F;
    private static volatile float rightLegYRot = 0.0F;
    private static volatile float rightLegZRot = 0.0F;

    private static volatile float leftLegXRot = 0.0F;
    private static volatile float leftLegYRot = 0.0F;
    private static volatile float leftLegZRot = 0.0F;

    private RavenLinkEffigyPoseCapture() {
    }

    public static void registerGameBus() {
        try {
            if (registered) {
                return;
            }
            MinecraftForge.EVENT_BUS.register(RavenLinkEffigyPoseCapture.class);
            registered = true;
            LOG.debug("[RavenLinkEffigyPoseCapture] Registered");
        } catch (Throwable t) {
            LOG.error("[RavenLinkEffigyPoseCapture] registerGameBus failed safely", t);
        }
    }

    public static boolean hasSnapshot() {
        return hasSnapshot;
    }

    public static float headXRot() { return headXRot; }
    public static float headYRot() { return headYRot; }
    public static float headZRot() { return headZRot; }
    public static float bodyXRot() { return bodyXRot; }
    public static float bodyYRot() { return bodyYRot; }
    public static float bodyZRot() { return bodyZRot; }
    public static float rightArmXRot() { return rightArmXRot; }
    public static float rightArmYRot() { return rightArmYRot; }
    public static float rightArmZRot() { return rightArmZRot; }
    public static float leftArmXRot() { return leftArmXRot; }
    public static float leftArmYRot() { return leftArmYRot; }
    public static float leftArmZRot() { return leftArmZRot; }
    public static float rightLegXRot() { return rightLegXRot; }
    public static float rightLegYRot() { return rightLegYRot; }
    public static float rightLegZRot() { return rightLegZRot; }
    public static float leftLegXRot() { return leftLegXRot; }
    public static float leftLegYRot() { return leftLegYRot; }
    public static float leftLegZRot() { return leftLegZRot; }

    @SubscribeEvent
    public static void onRenderPlayerPost(@NotNull RenderPlayerEvent.Post event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                return;
            }
            Player local = mc.player;
            if (event.getEntity() != local) {
                return;
            }

            @Nullable HumanoidModel<?> model;
            try {
                model = (event.getRenderer() == null) ? null : event.getRenderer().getModel();
            } catch (Throwable ignored) {
                model = null;
            }
            if (model == null) {
                return;
            }

            headXRot = model.head.xRot;
            headYRot = model.head.yRot;
            headZRot = model.head.zRot;

            bodyXRot = model.body.xRot;
            bodyYRot = model.body.yRot;
            bodyZRot = model.body.zRot;

            rightArmXRot = model.rightArm.xRot;
            rightArmYRot = model.rightArm.yRot;
            rightArmZRot = model.rightArm.zRot;

            leftArmXRot = model.leftArm.xRot;
            leftArmYRot = model.leftArm.yRot;
            leftArmZRot = model.leftArm.zRot;

            rightLegXRot = model.rightLeg.xRot;
            rightLegYRot = model.rightLeg.yRot;
            rightLegZRot = model.rightLeg.zRot;

            leftLegXRot = model.leftLeg.xRot;
            leftLegYRot = model.leftLeg.yRot;
            leftLegZRot = model.leftLeg.zRot;

            hasSnapshot = true;
        } catch (Throwable ignored) {
        }
    }
}
