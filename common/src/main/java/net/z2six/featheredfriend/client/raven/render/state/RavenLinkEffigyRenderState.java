package net.z2six.featheredfriend.client.raven.render.state;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * Render-state carrier for frozen Raven Link effigy pose snapshots.
 */
public class RavenLinkEffigyRenderState extends HumanoidRenderState {
    public boolean hasPoseSnapshot;

    public float headXRot;
    public float headYRot;
    public float headZRot;

    public float bodyXRot;
    public float bodyYRot;
    public float bodyZRot;

    public float rightArmXRot;
    public float rightArmYRot;
    public float rightArmZRot;

    public float leftArmXRot;
    public float leftArmYRot;
    public float leftArmZRot;

    public float rightLegXRot;
    public float rightLegYRot;
    public float rightLegZRot;

    public float leftLegXRot;
    public float leftLegYRot;
    public float leftLegZRot;
}
