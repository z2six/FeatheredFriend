// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/model/RavenGeoModel.java
package net.z2six.featheredfriend.client.raven.render.model;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.client.raven.RavenVisuals;
import net.z2six.featheredfriend.entity.raven.RavenArmorVisual;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.RavenVariant;
import org.slf4j.Logger;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/model/RavenGeoModel.java
 *
 * Provides GeckoLib model/texture/animation resources for the Raven,
 * selecting resources based on the RavenVariant.
 */
public class RavenGeoModel extends GeoModel<RavenEntity> {

    private static final Logger LOG = LogUtils.getLogger();

    @Override
    public ResourceLocation getModelResource(RavenEntity animatable, GeoRenderer<RavenEntity> renderer) {
        try {
            RavenVariant v = (animatable != null) ? animatable.getVariant() : RavenVariant.NORMAL;
            // Use visual helper; DYNCATCH kept so you can still breakpoint/future-proof
            return DYNCATCH(RavenVisuals.model(v), RavenVisuals.model(RavenVariant.NORMAL));
        } catch (Throwable t) {
            LOG.error("[RavenGeoModel] getModelResource failed, defaulting to NORMAL model", t);
            return RavenVisuals.model(RavenVariant.NORMAL);
        }
    }

    @Override
    public ResourceLocation getTextureResource(RavenEntity animatable, GeoRenderer<RavenEntity> renderer) {
        try {
            RavenArmorVisual armorVisual = (animatable != null) ? animatable.getRavenArmorVisual() : RavenArmorVisual.NONE;
            return DYNCATCH(RavenVisuals.texture(armorVisual), RavenVisuals.texture(RavenArmorVisual.NONE));
        } catch (Throwable t) {
            LOG.error("[RavenGeoModel] getTextureResource failed, defaulting to NORMAL texture", t);
            return RavenVisuals.texture(RavenArmorVisual.NONE);
        }
    }

    @Override
    public ResourceLocation getAnimationResource(RavenEntity animatable) {
        try {
            RavenVariant v = (animatable != null) ? animatable.getVariant() : RavenVariant.NORMAL;
            return DYNCATCH(RavenVisuals.animation(v), RavenVisuals.animation(RavenVariant.NORMAL));
        } catch (Throwable t) {
            LOG.error("[RavenGeoModel] getAnimationResource failed, defaulting to NORMAL animation", t);
            return RavenVisuals.animation(RavenVariant.NORMAL);
        }
    }

    /**
     * Tiny helper to make it easy to set a breakpoint or future hot-swap logic without touching ternaries.
     * Kept intentionally no-op.
     */
    private static ResourceLocation DYNCATCH(ResourceLocation value, ResourceLocation fallback) {
        return value != null ? value : fallback;
    }
}
