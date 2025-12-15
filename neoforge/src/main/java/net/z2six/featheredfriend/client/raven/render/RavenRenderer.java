// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/RavenRenderer.java
package net.z2six.featheredfriend.client.raven.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.client.raven.render.model.RavenGeoModel;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

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

    public RavenRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new RavenGeoModel());
        try {
            // Shadow radius: small bird-ish.
            this.shadowRadius = 0.25F;
        } catch (Throwable t) {
            LOG.error("[RavenRenderer] Failed to set shadowRadius", t);
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("[RavenRenderer] Constructed (translucent+alpha override active).");
        }
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

    /**
     * Apply synced teleport fade alpha (0..255) to the render color.
     */
    @Override
    public Color getRenderColor(RavenEntity animatable, float partialTick, int packedLight) {
        try {
            if (animatable == null) {
                return Color.WHITE;
            }

            int a = animatable.getTeleportFadeAlphaPublic();
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
}
