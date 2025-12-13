// neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/model/RavenGeoModel.java
package net.z2six.featheredfriend.client.raven.render.model;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.RavenVariant;
import org.slf4j.Logger;
import software.bernie.geckolib.model.GeoModel;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/model/RavenGeoModel.java
 *
 * Provides GeckoLib model/texture/animation resources for the Raven,
 * selecting resources based on the RavenVariant.
 */
public class RavenGeoModel extends GeoModel<RavenEntity> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation GEO_RAVEN =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "geo/raven.geo.json");
    private static final ResourceLocation GEO_RAVEN_SCROLL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "geo/ravenscroll.geo.json");

    private static final ResourceLocation TEX_RAVEN =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/raven.png");
    private static final ResourceLocation TEX_RAVEN_SCROLL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/ravenscroll.png");

    private static final ResourceLocation ANIM_RAVEN =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "animations/raven.animation.json");
    private static final ResourceLocation ANIM_RAVEN_SCROLL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "animations/ravenscroll.animation.json");

    @Override
    public ResourceLocation getModelResource(RavenEntity animatable) {
        try {
            RavenVariant v = animatable != null ? animatable.getRavenVariant() : RavenVariant.NORMAL;
            return v == RavenVariant.SCROLL ? GEO_RAVEN_SCROLL : GEO_RAVEN;
        } catch (Throwable t) {
            LOG.error("[RavenGeoModel] getModelResource failed, defaulting to GEO_RAVEN", t);
            return GEO_RAVEN;
        }
    }

    @Override
    public ResourceLocation getTextureResource(RavenEntity animatable) {
        try {
            RavenVariant v = animatable != null ? animatable.getRavenVariant() : RavenVariant.NORMAL;
            return v == RavenVariant.SCROLL ? TEX_RAVEN_SCROLL : TEX_RAVEN;
        } catch (Throwable t) {
            LOG.error("[RavenGeoModel] getTextureResource failed, defaulting to TEX_RAVEN", t);
            return TEX_RAVEN;
        }
    }

    @Override
    public ResourceLocation getAnimationResource(RavenEntity animatable) {
        try {
            RavenVariant v = animatable != null ? animatable.getRavenVariant() : RavenVariant.NORMAL;
            return v == RavenVariant.SCROLL ? ANIM_RAVEN_SCROLL : ANIM_RAVEN;
        } catch (Throwable t) {
            LOG.error("[RavenGeoModel] getAnimationResource failed, defaulting to ANIM_RAVEN", t);
            return ANIM_RAVEN;
        }
    }
}
