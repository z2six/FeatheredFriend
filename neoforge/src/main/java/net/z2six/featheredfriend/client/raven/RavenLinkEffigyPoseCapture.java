package net.z2six.featheredfriend.client.raven;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Captures the local player's last-rendered humanoid limb rotations so Raven Link effigies
 * can spawn as a frozen "snapshot" (mid-walk, mid-jump, etc) instead of a generic standing pose.
 */
public final class RavenLinkEffigyPoseCapture {

    private static final Logger LOG = LogUtils.getLogger();
    private static volatile boolean registered = false;

    private static volatile boolean hasSnapshot = false;
    private static volatile int lastCapturedTick = -1;

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
            NeoForge.EVENT_BUS.register(RavenLinkEffigyPoseCapture.class);
            registered = true;
            LOG.debug("[RavenLinkEffigyPoseCapture] Registered");
        } catch (Throwable t) {
            LOG.error("[RavenLinkEffigyPoseCapture] registerGameBus failed safely", t);
        }
    }

    public static boolean hasSnapshot() {
        tryCaptureNow(false);
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

    public static void captureNow() {
        tryCaptureNow(true);
    }

    private static void tryCaptureNow(boolean force) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                return;
            }

            Player local = mc.player;
            int tick = local.tickCount;
            if (!force && hasSnapshot && lastCapturedTick == tick) {
                return;
            }

            float partialTick = 1.0F;
            try {
                partialTick = mc.getTimer().getGameTimeDeltaPartialTick(true);
            } catch (Throwable ignored) {
            }

            float limbSwing;
            float limbSwingAmount;
            try {
                limbSwing = local.walkAnimation.position(partialTick);
                limbSwingAmount = local.walkAnimation.speed(partialTick);
            } catch (Throwable ignored) {
                // If anything about the animation state API changes, fall back to "static".
                limbSwing = 0.0F;
                limbSwingAmount = 0.0F;
            }

            float ageInTicks = local.tickCount + partialTick;

            float bodyYaw;
            float headYaw;
            try {
                bodyYaw = Mth.rotLerp(partialTick, local.yBodyRotO, local.yBodyRot);
                headYaw = Mth.rotLerp(partialTick, local.yHeadRotO, local.yHeadRot);
            } catch (Throwable ignored) {
                bodyYaw = local.getYRot();
                headYaw = local.getYRot();
            }
            float netHeadYaw = headYaw - bodyYaw;

            float headPitch;
            try {
                headPitch = Mth.lerp(partialTick, local.xRotO, local.getXRot());
            } catch (Throwable ignored) {
                headPitch = local.getXRot();
            }

            @Nullable HumanoidModel<?> model = null;
            try {
                EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(local);
                if (renderer instanceof PlayerRenderer playerRenderer) {
                    // Ensure model arm poses (blocking, using items, etc) are up-to-date even in first-person,
                    // where the local player body isn't necessarily rendered.
                    tryInvokePlayerRendererSetModelProperties(playerRenderer, local);
                    model = playerRenderer.getModel();
                }
            } catch (Throwable ignored) {
                model = null;
            }

            if (model == null) {
                return;
            }

            // Match the vanilla render pipeline: prepareMobModel -> setupAnim, then read the rotations.
            try {
                @SuppressWarnings("unchecked")
                HumanoidModel<LivingEntity> typedModel = (HumanoidModel<LivingEntity>) model;
                typedModel.prepareMobModel((LivingEntity) local, limbSwing, limbSwingAmount, partialTick);
                typedModel.setupAnim((LivingEntity) local, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            } catch (Throwable ignored) {
                // If we can't safely drive the model, keep the last known render-captured snapshot.
                return;
            }

            captureFromModel(model, tick);
        } catch (Throwable ignored) {
        }
    }

    private static void tryInvokePlayerRendererSetModelProperties(@NotNull PlayerRenderer renderer, @NotNull Player player) {
        try {
            // Mojmap name in 1.21.x: setModelProperties(AbstractClientPlayer)
            Method m = PlayerRenderer.class.getDeclaredMethod("setModelProperties", player.getClass().getSuperclass());
            m.setAccessible(true);
            m.invoke(renderer, player);
        } catch (Throwable ignored) {
            try {
                // Fallback: search by name + single-arg signature.
                for (Method method : PlayerRenderer.class.getDeclaredMethods()) {
                    if (!"setModelProperties".equals(method.getName()) || method.getParameterCount() != 1) {
                        continue;
                    }
                    method.setAccessible(true);
                    method.invoke(renderer, player);
                    break;
                }
            } catch (Throwable ignored2) {
            }
        }
    }

    private static void captureFromModel(@NotNull HumanoidModel<?> model, int tick) {
        try {
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
            lastCapturedTick = tick;
        } catch (Throwable ignored) {
        }
    }

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

            captureFromModel(model, local.tickCount);
        } catch (Throwable ignored) {
        }
    }
}
