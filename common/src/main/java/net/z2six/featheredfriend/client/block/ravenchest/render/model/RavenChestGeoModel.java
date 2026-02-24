package net.z2six.featheredfriend.client.block.ravenchest.render.model;

import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.entity.RavenChestBlockEntity;
import software.bernie.geckolib.model.GeoModel;

public class RavenChestGeoModel extends GeoModel<RavenChestBlockEntity> {

    private static final ResourceLocation MODEL =
            new ResourceLocation(Constants.MOD_ID, "geo/ravenchest.geo.json");
    private static final ResourceLocation ANIMATION =
            new ResourceLocation(Constants.MOD_ID, "animations/ravenchest.animation.json");
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(Constants.MOD_ID, "textures/block/ravenchest.png");

    @Override
    public ResourceLocation getModelResource(RavenChestBlockEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(RavenChestBlockEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(RavenChestBlockEntity animatable) {
        return ANIMATION;
    }
}
