package net.z2six.featheredfriend.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.menu.MailboxMenu;
import org.jetbrains.annotations.NotNull;

/**
 * Simple vanilla-like container screen for MailboxMenu.
 */
public final class MailboxScreen extends AbstractContainerScreen<MailboxMenu> {

    private static final ResourceLocation CONTAINER_TEXTURE =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/mailbox/mailbox.png");

    private static final int TEX_W = 194;
    private static final int TEX_H = 138;

    public MailboxScreen(@NotNull MailboxMenu menu,
                         @NotNull Inventory playerInventory,
                         @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = TEX_W;
        this.imageHeight = TEX_H;
        this.inventoryLabelY = 36;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(CONTAINER_TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, TEX_W, TEX_H);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Intentionally hidden (no "Mailbox" title, no "Inventory" label).
    }
}
