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
import software.bernie.geckolib.core.object.Color; // <-- FIX

/**
 * forge/src/main/java/net/z2six/featheredfriend/client/raven/render/RavenRenderer.java
 *
 * GeckoLib renderer for Raven (Forge 1.20.1).
 */
public class RavenRenderer extends GeoEntityRenderer<RavenEntity> {

    private static final Logger LOG = LogUtils.getLogger();

    public RavenRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new RavenGeoModel());
        try {
            this.shadowRadius = 0.25F;
        } catch (Throwable t) {
            LOG.error("[RavenRenderer] Failed to set shadowRadius", t);
        }
    }

    @Override
    public RenderType getRenderType(
            RavenEntity animatable,
            ResourceLocation texture,
            MultiBufferSource bufferSource,
            float partialTick
    ) {
        try {
            return RenderType.entityTranslucent(texture);
        } catch (Throwable t) {
            if (animatable != null && animatable.tickCount % 80 == 0) {
                LOG.warn("[RavenRenderer] getRenderType failed safely; falling back to GeoEntityRenderer: {}", t.toString());
            }
            return super.getRenderType(animatable, texture, bufferSource, partialTick);
        }
    }

    @Override
    public Color getRenderColor(RavenEntity animatable, float partialTick, int packedLight) {
        try {
            if (animatable == null) {
                return Color.WHITE;
            }

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
                    a = 255;
                    if (animatable.tickCount % 80 == 0) {
                        LOG.warn("[RavenRenderer] getRenderColor: getTeleportFadeAlphaPublic failed safely: {}", t.toString());
                    }
                }
            }

            if (a < 0) a = 0;
            if (a > 255) a = 255;

            return Color.ofRGBA(255, 255, 255, a);
        } catch (Throwable t) {
            if (animatable != null && animatable.tickCount % 80 == 0) {
                LOG.warn("[RavenRenderer] getRenderColor failed safely: {}", t.toString());
            }
            return Color.WHITE;
        }
    }
}
