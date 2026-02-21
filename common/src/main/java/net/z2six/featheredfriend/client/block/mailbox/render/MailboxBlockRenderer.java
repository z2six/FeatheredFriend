package net.z2six.featheredfriend.client.block.mailbox.render;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.z2six.featheredfriend.block.entity.MailboxBlockEntity;
import net.z2six.featheredfriend.client.block.mailbox.render.model.MailboxGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class MailboxBlockRenderer extends GeoBlockRenderer<MailboxBlockEntity> {

    public MailboxBlockRenderer(BlockEntityRendererProvider.Context ignoredContext) {
        super(new MailboxGeoModel());
    }
}

