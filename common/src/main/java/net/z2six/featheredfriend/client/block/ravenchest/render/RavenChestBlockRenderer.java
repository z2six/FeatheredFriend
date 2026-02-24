package net.z2six.featheredfriend.client.block.ravenchest.render;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.z2six.featheredfriend.block.entity.RavenChestBlockEntity;
import net.z2six.featheredfriend.client.block.ravenchest.render.model.RavenChestGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class RavenChestBlockRenderer extends GeoBlockRenderer<RavenChestBlockEntity> {

    public RavenChestBlockRenderer(BlockEntityRendererProvider.Context ignoredContext) {
        super(new RavenChestGeoModel());
    }
}

