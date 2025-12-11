// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollViewScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.registry.FFItems;
import net.z2six.featheredfriend.neoforge.menu.ScrollViewMenu;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollViewScreen.java
 *
 * ScrollViewScreen
 *
 * Placeholder GUI for both scroll_sealed and scroll_opened.
 *
 * - Uses a simple colored background.
 * - Shows which item opened it ("Sealed Scroll" vs "Opened Scroll") by checking
 *   the player's currently held items on the client side.
 */
public class ScrollViewScreen extends AbstractContainerScreen<ScrollViewMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    public ScrollViewScreen(@NotNull ScrollViewMenu menu,
                            @NotNull Inventory playerInventory,
                            @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = this.imageHeight - 94;
        LOG.debug("[ScrollViewScreen] Constructed");
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics,
                       int mouseX,
                       int mouseY,
                       float partialTick) {
        try {
            // NOTE: 1.21.1 requires the full signature with mouseX, mouseY, partialTick.
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] render() failed", t);
        }
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics,
                            float partialTick,
                            int mouseX,
                            int mouseY) {
        try {
            // Simple solid rectangle background as placeholder.
            int left = this.leftPos;
            int top = this.topPos;
            int right = left + this.imageWidth;
            int bottom = top + this.imageHeight;

            // Semi-dark background color ARGB: 0xFF202020
            guiGraphics.fill(left, top, right, bottom, 0xFF202020);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] renderBg() failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics,
                                int mouseX,
                                int mouseY) {
        try {
            String variantLabel = determineScrollVariantLabel();
            String titleText = "Scroll Viewer - " + variantLabel;

            guiGraphics.drawString(
                    this.font,
                    titleText,
                    8,
                    6,
                    0xFFFFFF,
                    false
            );

            guiGraphics.drawString(
                    this.font,
                    this.playerInventoryTitle,
                    this.inventoryLabelX,
                    this.inventoryLabelY,
                    0xFFFFFF,
                    false
            );
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] renderLabels() failed", t);
        }
    }

    /**
     * Determines which scroll variant opened this GUI by inspecting the player's
     * currently held main hand and offhand items.
     */
    @NotNull
    private String determineScrollVariantLabel() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                LOG.debug("[ScrollViewScreen] determineScrollVariantLabel: player is null");
                return "Unknown";
            }

            ItemStack main = mc.player.getMainHandItem();
            ItemStack off = mc.player.getOffhandItem();

            boolean mainSealed = !main.isEmpty() && main.is(FFItems.SCROLL_SEALED.get());
            boolean mainOpened = !main.isEmpty() && main.is(FFItems.SCROLL_OPENED.get());
            boolean offSealed = !off.isEmpty() && off.is(FFItems.SCROLL_SEALED.get());
            boolean offOpened = !off.isEmpty() && off.is(FFItems.SCROLL_OPENED.get());

            if (mainSealed || offSealed) {
                LOG.debug("[ScrollViewScreen] Scroll variant detected: Sealed");
                return "Sealed Scroll";
            }

            if (mainOpened || offOpened) {
                LOG.debug("[ScrollViewScreen] Scroll variant detected: Opened");
                return "Opened Scroll";
            }

            LOG.debug("[ScrollViewScreen] Scroll variant unknown (no matching item in hands)");
            return "Unknown";
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] determineScrollVariantLabel() failed", t);
            return "Unknown";
        }
    }
}
