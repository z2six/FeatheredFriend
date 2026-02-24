package net.z2six.featheredfriend.client.raven.render;

import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.client.raven.render.model.RavenLinkEffigyModel;
import net.z2six.featheredfriend.entity.ravenlink.RavenLinkEffigyEntity;

/**
 * Statue-style renderer for Raven Link effigy bodies.
 */
public class RavenLinkEffigyRenderer extends HumanoidMobRenderer<RavenLinkEffigyEntity, HumanoidModel<RavenLinkEffigyEntity>> {

    private static final ResourceLocation EFFIGY_TEXTURE =
            new ResourceLocation("featheredfriend", "textures/entity/raven_link_effigy_stone.png");

    public RavenLinkEffigyRenderer(EntityRendererProvider.Context context) {
        super(context, new RavenLinkEffigyModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()
        ));
    }

    @Override
    public ResourceLocation getTextureLocation(RavenLinkEffigyEntity entity) {
        return EFFIGY_TEXTURE;
    }
}
