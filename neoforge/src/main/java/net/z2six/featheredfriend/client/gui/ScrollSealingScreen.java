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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.ClientCalendarEvents;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.client.gui.widget.RecipientOverlay;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollSealingScreen.java
 *
 * ScrollSealingScreen
 *
 * Visual front-end for ScrollSealingMenu.
 * For now:
 *  - "Dear Recipient" field (single-line via MultiLineScrollTextWidget) using Gothic font.
 *  - Multi-line message body widget using Gothic font + newline support.
 *  - Player list overlay using RecipientOverlay (vanilla font).
 *  - Rendered Ender Pearl icon acting as a clickable "items attachment" entry point.
 *  - "Sign" button in the bottom-left that:
 *      * Checks recipient UUID is set.
 *      * Checks recipient text is non-empty.
 *      * If both pass:
 *          - Stores the current player's UUID as signer.
 *          - Appends a signature line with current in-world date:
 *              "Signed by: <name>, Day X of Month, Y AN"
 *          - Populates a dedicated signature field below the message body.
 *
 * Signature field behaviour:
 *  - Initially shows grey placeholder text: "Signature" (like other placeholders).
 *  - After clicking Sign (and passing checks), it is replaced with:
 *      "Signed by: <name>, Day X of Month, Y AN"
 *
 * If a custom GUI texture is not found at:
 *  assets/featheredfriend/textures/gui/scroll_sealing.png
 * it will fall back to drawing a simple colored rectangle.
 */
public class ScrollSealingScreen extends AbstractContainerScreen<ScrollSealingMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation SCROLL_GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scroll_sealing.png");

    // Our custom Gothic TTF font id (from assets/featheredfriend/font/gothic12.json)
    private static final ResourceLocation GOTHIC_FONT_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gothic12");

    // GUI dimensions
    private static final int GUI_WIDTH = 248;
    private static final int GUI_HEIGHT = 200;

    // Recipient field config (relative to GUI origin)
    private static final int RECIPIENT_Y = 24;
    private static final int RECIPIENT_WIDTH = 168;
    private static final int RECIPIENT_HEIGHT = 14;
    private static final int RECIPIENT_MAX_CHARS = 64;

    // Message widget config
    private static final int MESSAGE_X = 20;
    private static final int MESSAGE_Y = 50;
    private static final int MESSAGE_WIDTH = 208;
    private static final int MESSAGE_HEIGHT = 6 * 9 + 10; // about 6 lines
    private static final int MESSAGE_MAX_CHARS = 512;
    private static final int MESSAGE_MAX_LINES = 10;

    // Signature widget config (below the message, near bottom-left)
    // Adjusted so it DOES NOT overlap with the Sign button.
    private static final int SIGNATURE_X = MESSAGE_X;
    private static final int SIGNATURE_Y = GUI_HEIGHT - 52;  // 148
    private static final int SIGNATURE_WIDTH = 208;
    // Enough for 2 wrapped lines (2*9 + a bit of padding).
    private static final int SIGNATURE_HEIGHT = 24;          // 148..172
    private static final int SIGNATURE_MAX_CHARS = 128;
    private static final int SIGNATURE_MAX_LINES = 2;

    // "Sign" button config (bottom-left, under signature line)
    // Starts below signature area with a clear gap (no overlap).
    private static final int SIGN_BUTTON_X = MESSAGE_X;
    private static final int SIGN_BUTTON_Y = GUI_HEIGHT - 22; // 178
    private static final int SIGN_BUTTON_WIDTH = 80;
    private static final int SIGN_BUTTON_HEIGHT = 18;         // 178..196

    // Ender pearl icon (no vanilla button) relative to GUI origin
    // Moved clearly above the recipient field.
    private static final int PEARL_ICON_X = MESSAGE_X;
    private static final int PEARL_ICON_Y = 10;
    private static final int PEARL_ICON_SIZE = 16;

    private static final ItemStack PEARL_STACK = new ItemStack(Items.ENDER_PEARL);

    // Widgets
    private MultiLineScrollTextWidget recipientField;
    private MultiLineScrollTextWidget messageWidget;
    private MultiLineScrollTextWidget signatureWidget;

    // Recipient player selection (UUID is our ground truth)
    private UUID selectedRecipientUuid = null;

    // Signer (current player) UUID when the scroll is signed
    private UUID signerUuid = null;

    // Player overlay (uses vanilla font)
    private RecipientOverlay recipientOverlay;

    public ScrollSealingScreen(@NotNull ScrollSealingMenu menu,
                               @NotNull Inventory playerInventory,
                               @NotNull Component title) {
        super(menu, playerInventory, title);

        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        // Suppress vanilla title rendering.
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();

        LOG.debug("[ScrollSealingScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);
        this.clearWidgets();

        // Recipient field (single-line custom widget) using Gothic font, NO newlines
        // NOTE: X uses MESSAGE_X so "Dear Dev" lines up with the main text block.
        this.recipientField = new MultiLineScrollTextWidget(
                this.font,
                this.leftPos + MESSAGE_X,
                this.topPos + RECIPIENT_Y,
                RECIPIENT_WIDTH,
                RECIPIENT_HEIGHT,
                RECIPIENT_MAX_CHARS,
                1,
                Component.literal("Dear Recipient"),
                GOTHIC_FONT_ID,
                false // allowNewlines
        );
        this.addRenderableWidget(this.recipientField);

        // Message widget using Gothic font + newline support
        this.messageWidget = new MultiLineScrollTextWidget(
                this.font,
                this.leftPos + MESSAGE_X,
                this.topPos + MESSAGE_Y,
                MESSAGE_WIDTH,
                MESSAGE_HEIGHT,
                MESSAGE_MAX_CHARS,
                MESSAGE_MAX_LINES,
                Component.literal("Click here to write your message..."),
                GOTHIC_FONT_ID,
                true // allowNewlines
        );
        this.addRenderableWidget(this.messageWidget);

        // Signature widget (Gothic, up to 2 wrapped lines, non-editable)
        // IMPORTANT:
        //  - Placeholder "Signature" is passed as the placeholder component.
        //  - We DO NOT setText("Signature") here.
        //    That way it behaves like other placeholders, rendered in grey.
        this.signatureWidget = new MultiLineScrollTextWidget(
                this.font,
                this.leftPos + SIGNATURE_X,
                this.topPos + SIGNATURE_Y,
                SIGNATURE_WIDTH,
                SIGNATURE_HEIGHT,
                SIGNATURE_MAX_CHARS,
                SIGNATURE_MAX_LINES,
                Component.literal("Signature"),
                GOTHIC_FONT_ID,
                true // allowNewlines for internal wrapping; user can't edit anyway
        );
        this.signatureWidget.setEditable(false);
        this.addRenderableWidget(this.signatureWidget);

        // "Sign" button (bottom-left, under signature field)
        Button signButton = Button.builder(
                        Component.literal("Sign"),
                        b -> onSignButtonClicked()
                )
                .bounds(
                        this.leftPos + SIGN_BUTTON_X,
                        this.topPos + SIGN_BUTTON_Y,
                        SIGN_BUTTON_WIDTH,
                        SIGN_BUTTON_HEIGHT
                )
                .build();
        this.addRenderableWidget(signButton);

        // Recipient overlay: position just under the recipient field, expanding downward
        int overlayWidth = 180;
        int overlayHeight = 90;
        int overlayX = this.leftPos + MESSAGE_X;
        int overlayY = this.topPos + RECIPIENT_Y + RECIPIENT_HEIGHT + 4;

        this.recipientOverlay = new RecipientOverlay(
                Minecraft.getInstance(),
                this.font, // vanilla font here – no Gothic
                overlayX,
                overlayY,
                overlayWidth,
                overlayHeight,
                new RecipientOverlay.SelectionCallback() {
                    @Override
                    public void onPlayerSelected(UUID uuid, String name) {
                        LOG.debug("[ScrollSealingScreen] Recipient selected: {} ({})", name, uuid);
                        selectedRecipientUuid = uuid;
                        // Autofill text and move caret to the end ("Dear Dev,|")
                        recipientField.setText("Dear " + name + ",");
                        recipientField.setCursorToEnd();
                    }

                    @Override
                    public void onOverlayClosedWithoutSelection() {
                        LOG.debug("[ScrollSealingScreen] Recipient overlay closed without selection");
                        // Do not change text or UUID here.
                    }
                }
        );
    }

    // ---------------------------------------------------------------------
    // Helper: open overlay & clear previous UUID
    // ---------------------------------------------------------------------

    private void openRecipientOverlayIfEmpty() {
        if (recipientOverlay == null) return;

        String txt = recipientField.getText();
        if (txt == null || txt.isEmpty()) {
            LOG.debug("[ScrollSealingScreen] Opening recipient overlay, clearing previous UUID");
            selectedRecipientUuid = null;
            recipientOverlay.setPosition(
                    this.leftPos + MESSAGE_X,
                    this.topPos + RECIPIENT_Y + RECIPIENT_HEIGHT + 4
            );
            recipientOverlay.open();
        }
    }

    // ---------------------------------------------------------------------
    // Signing logic
    // ---------------------------------------------------------------------

    /**
     * Called when the "Sign" button is clicked.
     *
     * Behaviour:
     *  - If no recipient UUID is set, log and do nothing.
     *  - If recipient text is empty/blank, log and do nothing.
     *  - Otherwise:
     *      * Store current player's UUID as signerUuid.
     *      * Compute current in-world date index via FFCalendarConfig.
     *      * Use ClientCalendarEvents.buildDateMessage(...) to format date.
     *      * Populate the signature field:
     *          "Signed by: <name>, Day X of Month, Y AN"
     *
     * The placeholder "Signature" is replaced entirely.
     */
    private void onSignButtonClicked() {
        try {
            LOG.debug("[ScrollSealingScreen] onSignButtonClicked invoked");
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                LOG.warn("[ScrollSealingScreen] onSignButtonClicked: Minecraft/level/player not ready");
                return;
            }

            // Check recipient UUID
            if (selectedRecipientUuid == null) {
                LOG.info("[ScrollSealingScreen] onSignButtonClicked: No recipient UUID set, aborting sign");
                return;
            }

            // Check recipient text (user might have deleted it)
            String recipientText = recipientField != null ? recipientField.getText() : null;
            if (recipientText == null || recipientText.trim().isEmpty()) {
                LOG.info("[ScrollSealingScreen] onSignButtonClicked: Recipient field is empty, aborting sign");
                return;
            }

            // Determine the current day index from world time + config
            long dayTime = mc.level.getDayTime();
            long ticksPerDay = FFCalendarConfig.TICKS_PER_DAY;
            if (ticksPerDay <= 0L) {
                LOG.warn("[ScrollSealingScreen] onSignButtonClicked: FFCalendarConfig.TICKS_PER_DAY <= 0 ({}), using 24000 fallback", ticksPerDay);
                ticksPerDay = 24000L;
            }
            long dayIndex = dayTime / ticksPerDay;

            // Use shared date formatter from ClientCalendarEvents
            Component dateComponent = ClientCalendarEvents.buildDateMessage(dayIndex);
            String dateString = dateComponent.getString();

            // Store signer UUID
            signerUuid = mc.player.getUUID();
            String signerName = mc.player.getGameProfile().getName();

            String fullSignature = "Signed by: " + signerName + ", " + dateString;

            if (signatureWidget != null) {
                // Temporarily mark widget editable so setText definitely applies,
                // then lock it again to keep it user-read-only.
                signatureWidget.setEditable(true);
                signatureWidget.setText(fullSignature);
                signatureWidget.setCursorToEnd();
                signatureWidget.setEditable(false);
            }

            LOG.info("[ScrollSealingScreen] Scroll signed by {} ({}) on {}", signerName, signerUuid, dateString);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] onSignButtonClicked failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        try {
            if (this.recipientField != null) {
                this.recipientField.tick();
            }
            if (this.messageWidget != null) {
                this.messageWidget.tick();
            }
            if (this.signatureWidget != null) {
                this.signatureWidget.tick();
            }
            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                this.recipientOverlay.tick();
            }
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] containerTick failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Background rendering
    // ---------------------------------------------------------------------

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

            // Render the ender pearl icon (no button background)
            guiGraphics.renderItem(
                    PEARL_STACK,
                    this.leftPos + PEARL_ICON_X,
                    this.topPos + PEARL_ICON_Y
            );
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

    // ---------------------------------------------------------------------
    // Foreground / main rendering
    // ---------------------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            super.render(guiGraphics, mouseX, mouseY, partialTick);

            // Render overlay on top
            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                this.recipientOverlay.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] render failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Intentionally empty to avoid default "Scroll Sealing" title text
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            // Let overlay handle clicks first (including closing when clicking outside)
            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                boolean consumed = this.recipientOverlay.mouseClicked(mouseX, mouseY, button);
                if (consumed) {
                    return true;
                }
            }

            // Handle clicks on the ender pearl icon
            if (button == 0 && isMouseOverPearlIcon(mouseX, mouseY)) {
                onEnderPearlClicked();
                return true;
            }

            // Normal screen handling (buttons, widgets)
            boolean result = super.mouseClicked(mouseX, mouseY, button);

            // After vanilla click handling, if recipient field is focused and empty, open overlay
            if (this.recipientField != null && this.recipientField.isFocused()) {
                String txt = this.recipientField.getText();
                if (txt == null || txt.isEmpty()) {
                    openRecipientOverlayIfEmpty();
                } else {
                    // If the user has typed something manually, close overlay (if open)
                    if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                        this.recipientOverlay.close(true);
                    }
                }
            } else {
                // Clicking other parts of the screen closes overlay
                if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                    this.recipientOverlay.close(true);
                }
            }

            return result;
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] mouseClicked failed", t);
            return false;
        }
    }

    private boolean isMouseOverPearlIcon(double mouseX, double mouseY) {
        int x0 = this.leftPos + PEARL_ICON_X;
        int y0 = this.topPos + PEARL_ICON_Y;
        int x1 = x0 + PEARL_ICON_SIZE;
        int y1 = y0 + PEARL_ICON_SIZE;
        return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
    }

    private void onEnderPearlClicked() {
        try {
            LOG.info("[ScrollSealingScreen] Ender pearl icon clicked (placeholder – will open item sending UI later)");
            // Future:
            //  - Check if player has a pearl in inventory.
            //  - Open combined inventory + "attachment" view for items to send.
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] onEnderPearlClicked failed", t);
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        try {
            // Overlay gets first crack
            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                if (this.recipientOverlay.charTyped(codePoint, modifiers)) {
                    return true;
                }
            }

            // Then message / recipient fields (signature is non-editable)
            boolean handled = false;

            if (this.recipientField != null && this.recipientField.isFocused()) {
                handled |= this.recipientField.charTyped(codePoint, modifiers);
            }
            if (this.messageWidget != null && this.messageWidget.isFocused()) {
                handled |= this.messageWidget.charTyped(codePoint, modifiers);
            }

            return handled || super.charTyped(codePoint, modifiers);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] charTyped failed", t);
            return false;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            // Overlay first
            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                if (this.recipientOverlay.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }

            // Eat 'E' (inventory), 'R' and 'U' (JEI keys) while our screen is open
            if (keyCode == GLFW.GLFW_KEY_E ||
                    keyCode == GLFW.GLFW_KEY_R ||
                    keyCode == GLFW.GLFW_KEY_U) {
                return true;
            }

            // Let our text widgets consume keys
            boolean handled = false;
            if (this.recipientField != null && this.recipientField.isFocused()) {
                handled |= this.recipientField.keyPressed(keyCode, scanCode, modifiers);
            }
            if (this.messageWidget != null && this.messageWidget.isFocused()) {
                handled |= this.messageWidget.keyPressed(keyCode, scanCode, modifiers);
            }
            if (handled) {
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] keyPressed failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Placeholder sealing logic hook
    // ---------------------------------------------------------------------

    @SuppressWarnings("unused")
    private void onSealClickedPlaceholder() {
        try {
            LOG.info("[ScrollSealingScreen] Seal placeholder clicked. RecipientUUID={} SignerUUID={}",
                    selectedRecipientUuid, signerUuid);
            // Future sealing logic will:
            //  - Require selectedRecipientUuid != null
            //  - Use messageWidget.getText()
            //  - Use signatureWidget.getText()
            //  - Verify player has Ender Pearl and consume it
            //  - Build sealed scroll with NBT and hand it to player
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] onSealClickedPlaceholder failed", t);
        }
    }
}
