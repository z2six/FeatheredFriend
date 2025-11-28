// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollSealingScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * ScrollSealingScreen
 *
 * Visual front-end for ScrollSealingMenu.
 * For now:
 *  - One Ender Pearl slot (handled by the menu).
 *  - Recipient line ("Dear Recipient") using the same custom widget style as the body.
 *  - Multi-line message body widget.
 *  - Placeholder "Seal Scroll" button with no real sealing logic (yet).
 *
 * If a custom GUI texture is not found at:
 *  assets/featheredfriend/textures/gui/scroll_sealing.png
 * it will fall back to drawing a simple colored rectangle.
 */
public class ScrollSealingScreen extends AbstractContainerScreen<ScrollSealingMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    // Background texture
    private static final ResourceLocation SCROLL_GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scroll_sealing.png");

    // GUI dimensions
    private static final int GUI_WIDTH = 248;
    private static final int GUI_HEIGHT = 200;

    // Recipient field config (relative to GUI origin)
    private static final int RECIPIENT_X = 50;
    private static final int RECIPIENT_Y = 24;
    private static final int RECIPIENT_WIDTH = 160;
    private static final int RECIPIENT_HEIGHT = 14;
    private static final int RECIPIENT_MAX_CHARS = 48;
    private static final int RECIPIENT_MAX_LINES = 1;

    // Message widget config
    private static final int MESSAGE_X = 20;
    private static final int MESSAGE_Y = 50;
    private static final int MESSAGE_WIDTH = 208;
    private static final int MESSAGE_HEIGHT = 6 * 9 + 6; // 6 lines of text roughly
    private static final int MESSAGE_MAX_CHARS = 512;
    private static final int MESSAGE_MAX_LINES = 6;

    // "Seal" button
    private static final int SEAL_BUTTON_WIDTH = 80;
    private static final int SEAL_BUTTON_HEIGHT = 20;

    private MultiLineScrollTextWidget recipientWidget;
    private MultiLineScrollTextWidget messageWidget;

    public ScrollSealingScreen(@NotNull ScrollSealingMenu menu,
                               @NotNull Inventory playerInventory,
                               @NotNull Component title) {
        super(menu, playerInventory, title);

        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;

        // We don't actually want a visible title string rendered.
        this.titleLabelX = 0;
        this.titleLabelY = 0;
    }

    @Override
    protected void init() {
        super.init();

        LOG.debug("[ScrollSealingScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);

        // Clear old widgets on re-init (e.g. after resize)
        this.clearWidgets();

        // Recipient widget: single-line text, same style as message body.
        this.recipientWidget = new MultiLineScrollTextWidget(
                this.font,
                this.leftPos + RECIPIENT_X,
                this.topPos + RECIPIENT_Y,
                RECIPIENT_WIDTH,
                RECIPIENT_HEIGHT,
                RECIPIENT_MAX_CHARS,
                RECIPIENT_MAX_LINES,
                Component.literal("Dear Recipient")
        );
        this.addRenderableWidget(this.recipientWidget);

        // Message widget
        this.messageWidget = new MultiLineScrollTextWidget(
                this.font,
                this.leftPos + MESSAGE_X,
                this.topPos + MESSAGE_Y,
                MESSAGE_WIDTH,
                MESSAGE_HEIGHT,
                MESSAGE_MAX_CHARS,
                MESSAGE_MAX_LINES,
                Component.literal("Click here to write your message...")
        );
        this.addRenderableWidget(this.messageWidget);

        // Temporary "Seal" button – no actual sealing logic yet
        int sealX = this.leftPos + this.imageWidth - SEAL_BUTTON_WIDTH - 16;
        int sealY = this.topPos + this.imageHeight - SEAL_BUTTON_HEIGHT - 10;

        this.addRenderableWidget(
                Button.builder(
                                Component.translatable("screen.featheredfriend.scroll_sealing.seal_button"),
                                btn -> onSealClicked()
                        )
                        .pos(sealX, sealY)
                        .size(SEAL_BUTTON_WIDTH, SEAL_BUTTON_HEIGHT)
                        .build()
        );
    }

    private void onSealClicked() {
        try {
            LOG.info("[ScrollSealingScreen] Seal button clicked (placeholder only for now)");
            // Future:
            // - Validate recipient/message/pearl
            // - Send packet to server to produce sealed scroll with NBT
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] onSealClicked failed", t);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        try {
            if (this.recipientWidget != null) {
                this.recipientWidget.tick();
            }
            if (this.messageWidget != null) {
                this.messageWidget.tick();
            }
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] containerTick failed", t);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            boolean handled = super.mouseClicked(mouseX, mouseY, button);

            // Manage focus so only one text widget is focused at a time.
            if (this.recipientWidget != null) {
                boolean overRecipient =
                        mouseX >= this.recipientWidget.getX()
                                && mouseX < this.recipientWidget.getX() + this.recipientWidget.getWidth()
                                && mouseY >= this.recipientWidget.getY()
                                && mouseY < this.recipientWidget.getY() + this.recipientWidget.getHeight();

                this.recipientWidget.setFocused(overRecipient);
            }

            if (this.messageWidget != null) {
                boolean overMessage =
                        mouseX >= this.messageWidget.getX()
                                && mouseX < this.messageWidget.getX() + this.messageWidget.getWidth()
                                && mouseY >= this.messageWidget.getY()
                                && mouseY < this.messageWidget.getY() + this.messageWidget.getHeight();

                this.messageWidget.setFocused(overMessage);
            }

            return handled;
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] mouseClicked failed", t);
            return false;
        }
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics,
                            float partialTick,
                            int mouseX,
                            int mouseY) {
        try {
            var resourceManager = Minecraft.getInstance().getResourceManager();
            boolean hasTexture = resourceManager.getResource(SCROLL_GUI_TEXTURE).isPresent();

            if (hasTexture) {
                guiGraphics.blit(
                        SCROLL_GUI_TEXTURE,
                        this.leftPos,
                        this.topPos,
                        0,
                        0,
                        this.imageWidth,
                        this.imageHeight
                );
            } else {
                // Fallback: simple parchment-ish rectangle
                guiGraphics.fill(
                        this.leftPos,
                        this.topPos,
                        this.leftPos + this.imageWidth,
                        this.topPos + this.imageHeight,
                        0xC0F5F0D8
                );
            }
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] renderBg failed, falling back to simple fill", t);
            guiGraphics.fill(
                    this.leftPos,
                    this.topPos,
                    this.leftPos + this.imageWidth,
                    this.topPos + this.imageHeight,
                    0xC0F5F0D8
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
            LOG.error("[ScrollSealingScreen] render failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Intentionally empty: we don't want a "Scroll Sealing" title text drawn.
    }
}
