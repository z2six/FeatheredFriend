package net.z2six.featheredfriend.client.raven.render;

import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.client.raven.render.model.RavenLinkEffigyModel;
import net.z2six.featheredfriend.client.raven.render.state.RavenLinkEffigyRenderState;
import net.z2six.featheredfriend.entity.ravenlink.RavenLinkEffigyEntity;

/**
 * Statue-style renderer for Raven Link effigy bodies.
 */
public class RavenLinkEffigyRenderer
        extends HumanoidMobRenderer<RavenLinkEffigyEntity, RavenLinkEffigyRenderState, HumanoidModel<RavenLinkEffigyRenderState>> {

    private static final ResourceLocation EFFIGY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("featheredfriend", "textures/entity/raven_link_effigy_stone.png");

    public RavenLinkEffigyRenderer(EntityRendererProvider.Context context) {
        super(context, new RavenLinkEffigyModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getEquipmentRenderer()
        ));
    }

    @Override
    public RavenLinkEffigyRenderState createRenderState() {
        return new RavenLinkEffigyRenderState();
    }

    @Override
    public void extractRenderState(RavenLinkEffigyEntity entity, RavenLinkEffigyRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.hasPoseSnapshot = entity.hasPoseSnapshot();
        if (!state.hasPoseSnapshot) {
            return;
        }

        state.headXRot = entity.snapshotHeadXRot();
        state.headYRot = entity.snapshotHeadYRot();
        state.headZRot = entity.snapshotHeadZRot();
        state.bodyXRot = entity.snapshotBodyXRot();
        state.bodyYRot = entity.snapshotBodyYRot();
        state.bodyZRot = entity.snapshotBodyZRot();
        state.rightArmXRot = entity.snapshotRightArmXRot();
        state.rightArmYRot = entity.snapshotRightArmYRot();
        state.rightArmZRot = entity.snapshotRightArmZRot();
        state.leftArmXRot = entity.snapshotLeftArmXRot();
        state.leftArmYRot = entity.snapshotLeftArmYRot();
        state.leftArmZRot = entity.snapshotLeftArmZRot();
        state.rightLegXRot = entity.snapshotRightLegXRot();
        state.rightLegYRot = entity.snapshotRightLegYRot();
        state.rightLegZRot = entity.snapshotRightLegZRot();
        state.leftLegXRot = entity.snapshotLeftLegXRot();
        state.leftLegYRot = entity.snapshotLeftLegYRot();
        state.leftLegZRot = entity.snapshotLeftLegZRot();
    }

    @Override
    public ResourceLocation getTextureLocation(RavenLinkEffigyRenderState state) {
        return EFFIGY_TEXTURE;
    }
}
