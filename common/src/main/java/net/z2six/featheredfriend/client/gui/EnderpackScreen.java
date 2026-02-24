package net.z2six.featheredfriend.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.menu.EnderpackMenu;
import org.jetbrains.annotations.NotNull;

/**
 * Simple vanilla-like container screen for EnderpackMenu.
 */
public final class EnderpackScreen extends AbstractContainerScreen<EnderpackMenu> {

    private static final ResourceLocation CONTAINER_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");
    private static final int ROWS = 3;

    public EnderpackScreen(@NotNull EnderpackMenu menu,
                           @NotNull Inventory playerInventory,
                           @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageHeight = 114 + ROWS * 18;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        guiGraphics.blit(CONTAINER_TEXTURE, x, y, 0, 0, this.imageWidth, ROWS * 18 + 17, 256, 256);
        guiGraphics.blit(CONTAINER_TEXTURE, x, y + ROWS * 18 + 17, 0, 126, this.imageWidth, 96, 256, 256);
    }
}
