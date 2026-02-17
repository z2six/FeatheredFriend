// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/RavenRenderer.java
package net.z2six.featheredfriend.client.raven.render;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.z2six.featheredfriend.client.raven.render.model.RavenGeoModel;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;
import java.lang.reflect.Method;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/RavenRenderer.java
 *
 * GeckoLib renderer for Raven.
 *
 * IMPORTANT:
 * - Teleport FX should be spawned as world particles (ENDERPOP) when teleport happens.
 * - Do NOT render a billboard quad from the entity renderer:
 *     that will always be "attached" to the entity render pass and is exactly what causes
 *     the stuck/flickering missing-texture plane behavior.
 *
 * Fade fix:
 * - Force translucent RenderType so alpha is actually respected.
 * - Multiply render color alpha by RavenEntity#getTeleportFadeAlphaPublic().
 */
public class RavenRenderer extends GeoEntityRenderer<RavenEntity> {

    private static final Logger LOG = LogUtils.getLogger();
    private static final double RAVEN_LINK_RIDING_RENDER_Y_OFFSET = -0.90D;
    private static final String RAVEN_LINK_CONTROLLER_CLASS =
            "net.z2six.featheredfriend.client.raven.RavenLinkClientController";
    private static volatile boolean RAVEN_LINK_HIDE_LOOKED_UP = false;
    private static volatile Method RAVEN_LINK_HIDE_METHOD = null;

    public RavenRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new RavenGeoModel());
        try {
            // Shadow radius: small bird-ish.
            this.shadowRadius = 0.25F;
        } catch (Throwable t) {
            LOG.error("[RavenRenderer] Failed to set shadowRadius", t);
        }

        if (LOG.isInfoEnabled()) {
            LOG.debug("[RavenRenderer] Constructed (translucent+alpha override active).");
        }
    }

    @Override
    public boolean shouldRender(RavenEntity livingEntity, Frustum camera, double camX, double camY, double camZ) {
        try {
            if (livingEntity != null && shouldHideForLocalRavenLink(livingEntity.getId())) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return super.shouldRender(livingEntity, camera, camX, camY, camZ);
    }

    @Override
    public void render(RavenEntity entity,
                       float entityYaw,
                       float partialTick,
                       PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       int packedLight) {
        try {
            if (entity != null
                    && entity.isRavenLinkControlled()
                    && entity.isPassenger()
                    && entity.getVehicle() instanceof Player) {
                poseStack.pushPose();
                poseStack.translate(0.0D, RAVEN_LINK_RIDING_RENDER_Y_OFFSET, 0.0D);
                super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
                poseStack.popPose();
                return;
            }
        } catch (Throwable ignored) {
            // fall through to default render path
        }
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    /**
     * CRITICAL:
     * Cutout/opaque pipelines ignore smooth alpha fades in practice.
     * This forces the raven to render with the translucent entity pipeline.
     */
    @Override
    public RenderType getRenderType(
            RavenEntity animatable,
            ResourceLocation texture,
            MultiBufferSource bufferSource,
            float partialTick
    ) {
        try {
            // Force translucency so alpha in getRenderColor actually does something.
            return RenderType.entityTranslucent(texture);
        } catch (Throwable t) {
            // Fail safe: do not crash rendering
            if (animatable != null && animatable.tickCount % 80 == 0) {
                LOG.warn("[RavenRenderer] getRenderType failed safely; falling back to GeoEntityRenderer: {}", t.toString());
            }
            return super.getRenderType(animatable, texture, bufferSource, partialTick);
        }
    }

    // neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/RavenRenderer.java

    /**
     * Apply synced teleport fade alpha (0..255) to the render color.
     */
    @Override
    public Color getRenderColor(RavenEntity animatable, float partialTick, int packedLight) {
        try {
            if (animatable == null) {
                return Color.WHITE;
            }

            // Access module via accessor (teleportation field is private in RavenEntity).
            net.z2six.featheredfriend.entity.raven.modules.Teleportation tp = null;
            try {
                tp = animatable.getTeleportation();
            } catch (Throwable ignored) {
                tp = null;
            }

            int a = 255;
            if (tp != null) {
                try {
                    a = tp.getTeleportFadeAlphaPublic(animatable);
                } catch (Throwable t) {
                    // Fail safe: default fully visible
                    a = 255;
                    if (animatable.tickCount % 80 == 0) {
                        LOG.warn("[RavenRenderer] getRenderColor: getTeleportFadeAlphaPublic failed safely: {}", t.toString());
                    }
                }
            } else {
                // If teleportation module is unexpectedly null, keep it visible.
                if (animatable.tickCount % 200 == 0) {
                    LOG.warn("[RavenRenderer] getRenderColor: teleportation module is null for id={} pos={}",
                            animatable.getId(), animatable.position());
                }
            }

            if (a < 0) a = 0;
            if (a > 255) a = 255;

            // White RGB, dynamic alpha.
            Color c = Color.ofRGBA(255, 255, 255, a);

            // Debug: only logs while fading (not at endpoints), throttled.
            if ((a != 0 && a != 255) && animatable.tickCount % 10 == 0) {
                LOG.debug("[RavenRenderer] Fade active: id={} alpha={} pos={} phaseHint={}",
                        animatable.getId(),
                        a,
                        animatable.position(),
                        "render");
            }

            return c;
        } catch (Throwable t) {
            if (animatable != null && animatable.tickCount % 80 == 0) {
                LOG.warn("[RavenRenderer] getRenderColor failed safely: {}", t.toString());
            }
            return Color.WHITE;
        }
    }

    private static boolean shouldHideForLocalRavenLink(int entityId) {
        try {
            Method m = getRavenLinkHideMethod();
            if (m == null) {
                return false;
            }
            Object result = m.invoke(null, entityId);
            return result instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method getRavenLinkHideMethod() {
        if (RAVEN_LINK_HIDE_LOOKED_UP) {
            return RAVEN_LINK_HIDE_METHOD;
        }
        try {
            Class<?> cls = Class.forName(RAVEN_LINK_CONTROLLER_CLASS);
            Method m = cls.getDeclaredMethod("shouldHideLinkedRavenForFirstPerson", int.class);
            m.setAccessible(true);
            RAVEN_LINK_HIDE_METHOD = m;
        } catch (Throwable ignored) {
            RAVEN_LINK_HIDE_METHOD = null;
        } finally {
            RAVEN_LINK_HIDE_LOOKED_UP = true;
        }
        return RAVEN_LINK_HIDE_METHOD;
    }

}
