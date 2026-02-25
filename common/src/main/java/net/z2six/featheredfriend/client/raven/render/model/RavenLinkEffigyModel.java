package net.z2six.featheredfriend.client.raven.render.model;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.z2six.featheredfriend.client.raven.render.state.RavenLinkEffigyRenderState;

/**
 * Fully static humanoid model for Raven Link effigies.
 * Intentionally avoids vanilla idle arm bob / breathing-like motion.
 */
public class RavenLinkEffigyModel extends HumanoidModel<RavenLinkEffigyRenderState> {

    public RavenLinkEffigyModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(RavenLinkEffigyRenderState state) {
        this.resetPose();

        boolean crouch = state != null && state.isCrouching;
        boolean hasSnapshot = state != null && state.hasPoseSnapshot;

        this.head.y = 0.0F;
        this.body.y = 0.0F;
        this.rightArm.x = -5.0F;
        this.rightArm.y = 2.0F;
        this.rightArm.z = 0.0F;
        this.leftArm.x = 5.0F;
        this.leftArm.y = 2.0F;
        this.leftArm.z = 0.0F;
        this.rightLeg.x = -1.9F;
        this.rightLeg.y = 12.0F;
        this.rightLeg.z = 0.0F;
        this.leftLeg.x = 1.9F;
        this.leftLeg.y = 12.0F;
        this.leftLeg.z = 0.0F;

        if (crouch) {
            this.head.y = 4.2F;
            this.body.y = 3.2F;

            this.rightArm.y = 4.2F;
            this.leftArm.y = 4.2F;

            this.rightLeg.y = 12.2F;
            this.leftLeg.y = 12.2F;
            this.rightLeg.z = 4.0F;
            this.leftLeg.z = 4.0F;
        }

        if (hasSnapshot) {
            this.head.xRot = state.headXRot;
            this.head.yRot = state.headYRot;
            this.head.zRot = state.headZRot;
            this.body.xRot = state.bodyXRot;
            this.body.yRot = state.bodyYRot;
            this.body.zRot = state.bodyZRot;
            this.rightArm.xRot = state.rightArmXRot;
            this.rightArm.yRot = state.rightArmYRot;
            this.rightArm.zRot = state.rightArmZRot;
            this.leftArm.xRot = state.leftArmXRot;
            this.leftArm.yRot = state.leftArmYRot;
            this.leftArm.zRot = state.leftArmZRot;
            this.rightLeg.xRot = state.rightLegXRot;
            this.rightLeg.yRot = state.rightLegYRot;
            this.rightLeg.zRot = state.rightLegZRot;
            this.leftLeg.xRot = state.leftLegXRot;
            this.leftLeg.yRot = state.leftLegYRot;
            this.leftLeg.zRot = state.leftLegZRot;
        } else {
            this.head.xRot = 0.0F;
            this.head.yRot = 0.0F;
            this.head.zRot = 0.0F;
            this.body.xRot = 0.0F;
            this.body.yRot = 0.0F;
            this.body.zRot = 0.0F;
            this.rightArm.xRot = 0.0F;
            this.rightArm.yRot = 0.0F;
            this.rightArm.zRot = 0.0F;
            this.leftArm.xRot = 0.0F;
            this.leftArm.yRot = 0.0F;
            this.leftArm.zRot = 0.0F;
            this.rightLeg.xRot = 0.0F;
            this.rightLeg.yRot = 0.0F;
            this.rightLeg.zRot = 0.0F;
            this.leftLeg.xRot = 0.0F;
            this.leftLeg.yRot = 0.0F;
            this.leftLeg.zRot = 0.0F;

            if (crouch) {
                // Static "player crouch" silhouette (no breathing / bobbing), just the pose.
                this.body.xRot = 0.5F;
                this.rightArm.xRot = -0.4F;
                this.leftArm.xRot = -0.4F;
                this.rightLeg.xRot = -0.1F;
                this.leftLeg.xRot = -0.1F;
            }
        }

        // Effigy texture is fully opaque; keep vanilla hat overlay disabled
        // so it does not appear as a second head cube.
        this.hat.visible = false;
    }
}
