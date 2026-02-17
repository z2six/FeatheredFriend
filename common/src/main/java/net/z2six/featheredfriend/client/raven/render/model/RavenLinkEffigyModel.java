package net.z2six.featheredfriend.client.raven.render.model;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.z2six.featheredfriend.entity.ravenlink.RavenLinkEffigyEntity;

/**
 * Fully static humanoid model for Raven Link effigies.
 * Intentionally avoids vanilla idle arm bob / breathing-like motion.
 */
public class RavenLinkEffigyModel extends HumanoidModel<RavenLinkEffigyEntity> {

    public RavenLinkEffigyModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(RavenLinkEffigyEntity entity,
                          float limbSwing,
                          float limbSwingAmount,
                          float ageInTicks,
                          float netHeadYaw,
                          float headPitch) {
        this.crouching = false;
        this.swimAmount = 0.0F;

        this.head.xRot = 0.0F;
        this.head.yRot = 0.0F;
        this.head.zRot = 0.0F;
        this.head.y = 0.0F;

        this.body.xRot = 0.0F;
        this.body.yRot = 0.0F;
        this.body.zRot = 0.0F;
        this.body.y = 0.0F;

        this.rightArm.x = -5.0F;
        this.rightArm.y = 2.0F;
        this.rightArm.z = 0.0F;
        this.rightArm.xRot = 0.0F;
        this.rightArm.yRot = 0.0F;
        this.rightArm.zRot = 0.0F;

        this.leftArm.x = 5.0F;
        this.leftArm.y = 2.0F;
        this.leftArm.z = 0.0F;
        this.leftArm.xRot = 0.0F;
        this.leftArm.yRot = 0.0F;
        this.leftArm.zRot = 0.0F;

        this.rightLeg.x = -1.9F;
        this.rightLeg.y = 12.0F;
        this.rightLeg.z = 0.0F;
        this.rightLeg.xRot = 0.0F;
        this.rightLeg.yRot = 0.0F;
        this.rightLeg.zRot = 0.0F;

        this.leftLeg.x = 1.9F;
        this.leftLeg.y = 12.0F;
        this.leftLeg.z = 0.0F;
        this.leftLeg.xRot = 0.0F;
        this.leftLeg.yRot = 0.0F;
        this.leftLeg.zRot = 0.0F;

        this.hat.copyFrom(this.head);
    }
}

