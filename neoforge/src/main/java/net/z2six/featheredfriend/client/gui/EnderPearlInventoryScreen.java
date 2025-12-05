// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/EnderPearlInventoryScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/EnderPearlInventoryScreen.java
 *
 * EnderPearlInventoryScreen
 *
 * Alternate view for ScrollSealingMenu that shows:
 *  - 1x9 attachment bar (top row) using the custom "inventory.png" texture.
 *  - Player inventory (3x9).
 *  - Player hotbar (1x9).
 *
 * Texture:
 *  - inventory.png (176x128) in the same folder as other scrollscreen textures.
 *
 * Behaviour:
 *  - Uses the same ScrollSealingMenu instance as ScrollSealingScreen.
 *  - ESC or clicking outside the inventory GUI returns to ScrollSealingScreen
 *    without closing the container (attachments stay in the menu).
 *  - No labels/text are rendered on top of the inventory texture.
 */
public class EnderPearlInventoryScreen extends AbstractContainerScreen<ScrollSealingMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scrollscreen/inventory.png");

    public EnderPearlInventoryScreen(@NotNull ScrollSealingMenu menu,
                                     @NotNull Inventory playerInventory,
                                     @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 128;

        // We won't render any labels, so label positions don't matter.
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
        this.inventoryLabelX = 10000;
        this.inventoryLabelY = 10000;
    }

    public static void handlePearlClicked(Minecraft mc) {
        try {
            if (mc == null) {
                LOG.warn("[EnderPearlInventoryScreen] handlePearlClicked: Minecraft instance is null");
                return;
            }
            if (mc.player == null) {
                LOG.warn("[EnderPearlInventoryScreen] handlePearlClicked: player is null");
                return;
            }

            if (!(mc.player.containerMenu instanceof ScrollSealingMenu menu)) {
                LOG.warn("[EnderPearlInventoryScreen] handlePearlClicked: current containerMenu is not ScrollSealingMenu (got: {})",
                        mc.player.containerMenu.getClass().getName());
                return;
            }

            LOG.debug("[EnderPearlInventoryScreen] Opening attachment inventory for player={}",
                    mc.player.getGameProfile().getName());

            mc.setScreen(new EnderPearlInventoryScreen(
                    menu,
                    mc.player.getInventory(),
                    Component.literal("") // title ignored, labels not drawn
            ));
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] handlePearlClicked failed", t);
        }
    }

    @Override
    protected void init() {
        super.init();
        try {
            LOG.debug("[EnderPearlInventoryScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] init failed", t);
        }
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics,
                            float partialTick,
                            int mouseX,
                            int mouseY) {
        try {
            guiGraphics.blit(
                    INVENTORY_TEXTURE,
                    this.leftPos,
                    this.topPos,
                    0.0f,
                    0.0f,
                    this.imageWidth,
                    this.imageHeight,
                    176,
                    128
            );
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] renderBg failed, falling back to simple fill", t);
            guiGraphics.fill(
                    this.leftPos,
                    this.topPos,
                    this.leftPos + this.imageWidth,
                    this.topPos + this.imageHeight,
                    0xC0_000000
            );
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] render failed", t);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            // Click outside inventory GUI -> behave like ESC: go back to ScrollSealingScreen
            if (button == 0) {
                if (mouseX < this.leftPos || mouseX >= this.leftPos + this.imageWidth ||
                        mouseY < this.topPos || mouseY >= this.topPos + this.imageHeight) {

                    LOG.debug("[EnderPearlInventoryScreen] Click outside GUI -> returning to ScrollSealingScreen");
                    this.onClose();
                    return true;
                }
            }

            return super.mouseClicked(mouseX, mouseY, button);
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] mouseClicked failed", t);
            return false;
        }
    }

    @Override
    public void onClose() {
        try {
            Minecraft mc = this.minecraft;
            if (mc == null || mc.player == null) {
                LOG.warn("[EnderPearlInventoryScreen] onClose: minecraft or player null, delegating to super");
                super.onClose();
                return;
            }

            LOG.debug("[EnderPearlInventoryScreen] onClose -> reopening ScrollSealingScreen instead of closing container");

            mc.setScreen(new ScrollSealingScreen(
                    this.menu,
                    mc.player.getInventory(),
                    Component.literal("Scroll Sealing")
            ));
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] onClose failed", t);
            super.onClose();
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Intentionally empty: no "Scroll Attachments" / "Inventory" labels.
    }
}
