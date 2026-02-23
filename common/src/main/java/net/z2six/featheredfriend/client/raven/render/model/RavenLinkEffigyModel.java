package net.z2six.featheredfriend.client.raven.render.model;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Pose;
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
        boolean crouch = entity != null && entity.getPose() == Pose.CROUCHING;
        boolean hasSnapshot = entity != null && entity.hasPoseSnapshot();
        this.crouching = crouch;
        this.swimAmount = 0.0F;

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
            this.head.xRot = entity.snapshotHeadXRot();
            this.head.yRot = entity.snapshotHeadYRot();
            this.head.zRot = entity.snapshotHeadZRot();

            this.body.xRot = entity.snapshotBodyXRot();
            this.body.yRot = entity.snapshotBodyYRot();
            this.body.zRot = entity.snapshotBodyZRot();

            this.rightArm.xRot = entity.snapshotRightArmXRot();
            this.rightArm.yRot = entity.snapshotRightArmYRot();
            this.rightArm.zRot = entity.snapshotRightArmZRot();

            this.leftArm.xRot = entity.snapshotLeftArmXRot();
            this.leftArm.yRot = entity.snapshotLeftArmYRot();
            this.leftArm.zRot = entity.snapshotLeftArmZRot();

            this.rightLeg.xRot = entity.snapshotRightLegXRot();
            this.rightLeg.yRot = entity.snapshotRightLegYRot();
            this.rightLeg.zRot = entity.snapshotRightLegZRot();

            this.leftLeg.xRot = entity.snapshotLeftLegXRot();
            this.leftLeg.yRot = entity.snapshotLeftLegYRot();
            this.leftLeg.zRot = entity.snapshotLeftLegZRot();
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

        this.hat.copyFrom(this.head);
    }
}
