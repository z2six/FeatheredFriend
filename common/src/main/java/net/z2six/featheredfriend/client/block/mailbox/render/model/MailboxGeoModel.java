package net.z2six.featheredfriend.client.block.mailbox.render.model;

import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.entity.MailboxBlockEntity;
import software.bernie.geckolib.model.GeoModel;

public class MailboxGeoModel extends GeoModel<MailboxBlockEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "geo/mailbox.geo.json");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "animations/mailbox.animation.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/block/mailbox.png");

    @Override
    public ResourceLocation getModelResource(MailboxBlockEntity animatable, software.bernie.geckolib.renderer.GeoRenderer<MailboxBlockEntity> renderer) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(MailboxBlockEntity animatable, software.bernie.geckolib.renderer.GeoRenderer<MailboxBlockEntity> renderer) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(MailboxBlockEntity animatable) {
        return ANIMATION;
    }
}
