// neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/RavenRenderer.java
package net.z2six.featheredfriend.client.raven.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.z2six.featheredfriend.client.raven.render.model.RavenGeoModel;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/raven/render/RavenRenderer.java
 *
 * GeckoLib renderer for Raven.
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
    }
}
