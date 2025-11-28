// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollSealingScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.data.KnownPlayersClientCache;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.client.gui.widget.RecipientOverlay;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollSealingScreen.java
 *
 * ScrollSealingScreen
 *
 * Visual front-end for ScrollSealingMenu.
 * For now:
 *  - Shows an Ender Pearl slot (handled by the menu).
 *  - "Dear Recipient" single-line header using the same visual style as the body.
 *  - Recipient picker overlay (filter + list of known players) in a separate class.
 *  - Multi-line message body widget.
 *  - Placeholder "Seal Scroll" button with no real sealing logic yet.
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
    private static final int RECIPIENT_X = 20;
    private static final int RECIPIENT_Y = 24;
    private static final int RECIPIENT_WIDTH = 208;
    private static final int RECIPIENT_HEIGHT = 10; // visual region; widget uses font height internally
    private static final int RECIPIENT_MAX_CHARS = 64;
    private static final int RECIPIENT_MAX_LINES = 1;

    // Message widget config
    private static final int MESSAGE_X = 20;
    private static final int MESSAGE_Y = 50;
    private static final int MESSAGE_WIDTH = 208;
    private static final int MESSAGE_HEIGHT = 6 * 9 + 6; // ~6 lines
    private static final int MESSAGE_MAX_CHARS = 512;
    private static final int MESSAGE_MAX_LINES = 6;

    // "Seal" button
    private static final int SEAL_BUTTON_WIDTH = 80;
    private static final int SEAL_BUTTON_HEIGHT = 20;

    // Recipient overlay config (relative to GUI origin)
    // NOTE: overlay drops *down* from the recipient line now.
    private static final int RECIPIENT_OVERLAY_WIDTH = 160;
    private static final int RECIPIENT_OVERLAY_HEIGHT = 96;
    private static final int RECIPIENT_OVERLAY_X = RECIPIENT_X;

    // Widgets
    private MultiLineScrollTextWidget recipientWidget;
    private MultiLineScrollTextWidget messageWidget;
    private RecipientOverlay recipientOverlay;

    /**
     * If true, we temporarily stop auto-opening the overlay when the recipient
     * line is focused + empty. This is set when the user explicitly closes it
     * (ESC or clicking outside), and reset when they click the recipient line again.
     */
    private boolean suppressRecipientOverlay = false;

    public ScrollSealingScreen(@NotNull ScrollSealingMenu menu,
                               @NotNull Inventory playerInventory,
                               @NotNull Component title) {
        super(menu, playerInventory, title);

        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;

        // We don't want the default "Scroll Sealing" title text.
        this.titleLabelX = 9999;
        this.titleLabelY = 9999;
    }

    // -------------------------------------------------------------------------
    // Init / lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();

        LOG.debug("[ScrollSealingScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);

        this.clearWidgets();

        // Recipient header widget (single logical line but using multi-line widget for consistent style)
        this.recipientWidget = new MultiLineScrollTextWidget(
                this.font,
                this.leftPos + RECIPIENT_X,
                this.topPos + RECIPIENT_Y,
                RECIPIENT_WIDTH,
                RECIPIENT_HEIGHT + this.font.lineHeight,
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

        // Seal button (placeholder)
        int sealX = this.leftPos + this.imageWidth - SEAL_BUTTON_WIDTH - 16;
        int sealY = this.topPos + this.imageHeight - SEAL_BUTTON_HEIGHT - 10;

        this.addRenderableWidget(
                net.minecraft.client.gui.components.Button.builder(
                                Component.translatable("screen.featheredfriend.scroll_sealing.seal_button"),
                                btn -> onSealClicked()
                        )
                        .pos(sealX, sealY)
                        .size(SEAL_BUTTON_WIDTH, SEAL_BUTTON_HEIGHT)
                        .build()
        );

        // Recipient overlay: absolute position is computed from GUI origin + relative constants.
        int overlayAbsX = this.leftPos + RECIPIENT_OVERLAY_X;
        int overlayAbsY = this.topPos + RECIPIENT_Y + this.font.lineHeight + 4; // drop *down* from recipient line

        this.recipientOverlay = new RecipientOverlay(
                Minecraft.getInstance(),
                this.font,
                overlayAbsX,
                overlayAbsY,
                RECIPIENT_OVERLAY_WIDTH,
                RECIPIENT_OVERLAY_HEIGHT,
                this::applySelectedRecipient
        );

        // Initialize overlay with current known players from client cache
        this.recipientOverlay.setRecipients(KnownPlayersClientCache.get());
    }

    private void onSealClicked() {
        try {
            LOG.info("[ScrollSealingScreen] Seal button clicked (placeholder only for now]");
            // Future:
            // - Validate recipient / message / pearl
            // - Send packet to server to produce sealed scroll with NBT
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] onSealClicked failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Ticking
    // -------------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();

        try {
            if (this.messageWidget != null) {
                this.messageWidget.tick();
            }
            if (this.recipientWidget != null) {
                this.recipientWidget.tick();
            }
            if (this.recipientOverlay != null) {
                this.recipientOverlay.tick();
            }

            // Auto-open overlay when recipient is focused and empty, unless the user
            // explicitly suppressed it (ESC/click outside).
            if (this.recipientOverlay != null &&
                    !this.recipientOverlay.isVisible() &&
                    !this.suppressRecipientOverlay &&
                    this.recipientWidget != null &&
                    this.recipientWidget.isFocused() &&
                    this.recipientWidget.getText().isEmpty()) {

                this.recipientOverlay.setRecipients(KnownPlayersClientCache.get());
                this.recipientOverlay.openIfPossible();

                // If overlay actually opened, let its filter own focus.
                if (this.recipientOverlay.isVisible()) {
                    this.recipientWidget.setFocused(false);
                }
            }
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] containerTick failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

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

            // Draw tooltips for vanilla stuff
            this.renderTooltip(guiGraphics, mouseX, mouseY);

            // Draw recipient overlay on top if visible
            if (this.recipientOverlay != null && this.recipientOverlay.isVisible()) {
                this.recipientOverlay.render(guiGraphics, mouseX, mouseY, partialTick);
            }
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] render failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // We deliberately don't draw title text; leave the parchment clean.
        // super.renderLabels(guiGraphics, mouseX, mouseY);
    }

    // -------------------------------------------------------------------------
    // Input handling
    // -------------------------------------------------------------------------

    private boolean isMouseInRecipientWidget(double mouseX, double mouseY) {
        if (this.recipientWidget == null) return false;
        int x = this.recipientWidget.getX();
        int y = this.recipientWidget.getY();
        int w = this.recipientWidget.getWidth();
        int h = this.recipientWidget.getHeight();
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        try {
            // If overlay is visible, let it consume clicks first
            if (this.recipientOverlay != null && this.recipientOverlay.isVisible()) {
                if (this.recipientOverlay.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }

                // Click outside overlay and outside recipient widget closes overlay and suppresses auto-open
                if (!this.recipientOverlay.isMouseInside(mouseX, mouseY) &&
                        !isMouseInRecipientWidget(mouseX, mouseY)) {
                    this.recipientOverlay.close();
                    this.suppressRecipientOverlay = true;
                }
            }

            boolean base = super.mouseClicked(mouseX, mouseY, button);

            // If clicked in recipient widget, focus it and allow overlay auto-open again
            if (isMouseInRecipientWidget(mouseX, mouseY)) {
                if (this.recipientWidget != null) {
                    this.recipientWidget.setFocused(true);
                }
                // User explicitly clicked the field; allow overlay opening again.
                this.suppressRecipientOverlay = false;

                if (this.recipientWidget != null &&
                        this.recipientWidget.getText().isEmpty() &&
                        this.recipientOverlay != null) {

                    this.recipientOverlay.setRecipients(KnownPlayersClientCache.get());
                    this.recipientOverlay.openIfPossible();

                    // If overlay opened, let it own focus
                    if (this.recipientOverlay.isVisible()) {
                        this.recipientWidget.setFocused(false);
                    }
                }
                return true;
            }

            return base;
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] mouseClicked failed", t);
            return false;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            int escKey = 256; // GLFW_KEY_ESCAPE
            int invKey = Minecraft.getInstance().options.keyInventory.getKey().getValue();

            if (this.recipientOverlay != null && this.recipientOverlay.isVisible()) {
                // While overlay is open, ESC closes overlay and suppresses auto-open
                if (keyCode == escKey) {
                    this.recipientOverlay.close();
                    this.suppressRecipientOverlay = true;
                    return true;
                }

                // While overlay is open, we *eat* the inventory key so it does NOT close the whole screen.
                if (keyCode == invKey) {
                    LOG.debug("[ScrollSealingScreen] Inventory key pressed while overlay visible - consuming to avoid closing screen");
                    return true;
                }

                // Otherwise let overlay handle navigation/enter/filter keys first
                if (this.recipientOverlay.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }

                // If overlay didn't handle it, fall back to default
                return super.keyPressed(keyCode, scanCode, modifiers);
            }

            // Overlay not visible:
            return super.keyPressed(keyCode, scanCode, modifiers);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] keyPressed failed", t);
            return false;
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        try {
            if (this.recipientOverlay != null && this.recipientOverlay.isVisible()) {
                // Characters go into the overlay filter
                if (this.recipientOverlay.charTyped(codePoint, modifiers)) {
                    return true;
                }
                return false;
            }

            // Overlay not visible: if recipient is focused & empty, and overlay
            // is not suppressed, start overlay and treat this char as filter input.
            if (this.recipientWidget != null &&
                    this.recipientWidget.isFocused() &&
                    this.recipientWidget.getText().isEmpty() &&
                    this.recipientOverlay != null &&
                    !this.suppressRecipientOverlay) {

                this.recipientOverlay.setRecipients(KnownPlayersClientCache.get());
                this.recipientOverlay.openIfPossible();

                if (this.recipientOverlay.isVisible()) {
                    // Let the overlay own focus so all chars go into the filter.
                    this.recipientWidget.setFocused(false);
                }

                if (this.recipientOverlay.charTyped(codePoint, modifiers)) {
                    return true;
                }
                return true;
            }

            // Otherwise, let the focused widget handle the char (recipient or message)
            if (this.messageWidget != null && this.messageWidget.isFocused()) {
                if (this.messageWidget.charTyped(codePoint, modifiers)) {
                    return true;
                }
            }
            if (this.recipientWidget != null && this.recipientWidget.isFocused()) {
                if (this.recipientWidget.charTyped(codePoint, modifiers)) {
                    return true;
                }
            }

            return super.charTyped(codePoint, modifiers);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] charTyped failed", t);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Recipient application callback
    // -------------------------------------------------------------------------

    private void applySelectedRecipient(@NotNull String playerName) {
        try {
            if (this.recipientWidget != null) {
                this.recipientWidget.setText("Dear " + playerName);
                this.recipientWidget.setFocused(true);
            }
            if (this.recipientOverlay != null) {
                this.recipientOverlay.close();
            }

            // No need to set suppressRecipientOverlay here; the field is no longer empty,
            // so it won't auto-open until the user clears it anyway.
            LOG.debug("[ScrollSealingScreen] Applied recipient '{}'", playerName);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] applySelectedRecipient failed", t);
        }
    }
}
