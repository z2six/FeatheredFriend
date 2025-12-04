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
 *  - Signature field ("Signature" placeholder) using Gothic font, auto-wrapping to 2 lines.
 *  - Player list overlay using RecipientOverlay (vanilla font).
 *  - Rendered Ender Pearl icon acting as a clickable "items attachment" entry point.
 *  - "Sign" button in the bottom-left that:
 *      * Checks recipient UUID is set.
 *      * Checks recipient text is non-empty.
 *      * If both pass:
 *          - Stores the current player's UUID as signer.
 *          - Appends a signature line with current in-world date:
 *              "Signed by: <name>, Day X of Month, Y AN"
 *          - Populates the signature field.
 *      * Triggers an outro fade of all text fields.
 *  - After text fades out and a configurable delay, a "Seal" button appears (placeholder).
 *
 * Animated GUI behaviour (synchronous with timing constants):
 *  Flow:
 *    1. INTRO_DELAY_TICKS
 *       - During this period, scroll_opening.png plays from frame 0→6.
 *       - At the end, scroll is fully open and sticks on the last frame.
 *    2. INTRO_FADE_TICKS
 *       - Text fades in on top of the fully opened scroll.
 *    3. User writes message / selects recipient.
 *    4. OUTRO_FADE_TICKS
 *       - Text fades out.
 *    5. OUTRO_DELAY_TICKS
 *       - During this period, scroll_closing.png plays from frame 0→6.
 *       - At the end, scroll is fully closed and sticks on the last frame.
 *       - The "Seal" button appears exactly when the closing animation finishes.
 *
 * The PNGs must live at:
 *  assets/featheredfriend/textures/gui/scrollscreen/scroll_opening.png
 *  assets/featheredfriend/textures/gui/scrollscreen/scroll_closing.png
 */
public class ScrollSealingScreen extends AbstractContainerScreen<ScrollSealingMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    // Legacy single-frame texture (kept as ultimate fallback)
    private static final ResourceLocation SCROLL_GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scroll_sealing.png");

    // Animated scroll textures (sprite sheets: 1680x208, 7 frames horizontally)
    private static final ResourceLocation SCROLL_OPENING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scrollscreen/scroll_opening.png");
    private static final ResourceLocation SCROLL_CLOSING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scrollscreen/scroll_closing.png");

    // Gothic font id (from assets/featheredfriend/font/gothic12.json)
    private static final ResourceLocation GOTHIC_FONT_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gothic12");

    // ---------------------------------------------------------------------
    // Scroll animation configuration
    // ---------------------------------------------------------------------

    /**
     * Single frame size. The PNGs are 1680x208: 7 * 240 = 1680.
     */
    private static final int SCROLL_FRAME_WIDTH = 240;
    private static final int SCROLL_FRAME_HEIGHT = 208;
    private static final int SCROLL_TOTAL_FRAMES = 7;

    private enum ScrollAnimPhase {
        OPENING,
        OPEN_STILL,
        CLOSING,
        CLOSED_STILL
    }

    private ScrollAnimPhase scrollAnimPhase = ScrollAnimPhase.OPENING;

    // ---------------------------------------------------------------------
    // Timing constants (all in ticks; 20 ticks = 1 second)
    // ---------------------------------------------------------------------

    /**
     * 1. INTRO_DELAY_TICKS:
     *    - Duration for the opening scroll animation.
     *    - During this entire period, scroll_opening.png plays.
     *    - Text is fully hidden and non-interactive.
     */
    private static final int INTRO_DELAY_TICKS = 10;

    /**
     * 2. INTRO_FADE_TICKS:
     *    - Fade-in duration for text after the scroll is fully open.
     */
    private static final int INTRO_FADE_TICKS = 30;

    /**
     * 4. OUTRO_FADE_TICKS:
     *    - Fade-out duration for text after "Sign" is clicked.
     */
    private static final int OUTRO_FADE_TICKS = 30;

    /**
     * 5. OUTRO_DELAY_TICKS:
     *    - Time window after text has fully faded out.
     *    - During this period, scroll_closing.png plays from frame 0→6.
     *    - At the end of this delay, the scroll is fully closed and the "Seal" button appears.
     */
    private static final int OUTRO_DELAY_TICKS = 10;

    private enum UiPhase {
        INTRO_DELAY,
        INTRO_FADE_IN,
        IDLE,
        OUTRO_FADE_OUT,
        SEALED
    }

    private UiPhase uiPhase = UiPhase.INTRO_DELAY;
    private int uiPhaseTicks = 0;

    // GUI dimensions
    private static final int GUI_WIDTH = SCROLL_FRAME_WIDTH; // 240
    private static final int GUI_HEIGHT = 200;

    // Recipient field config (relative to GUI origin)
    private static final int RECIPIENT_X = 20;   // aligned with message & signature
    private static final int RECIPIENT_Y = 24;
    private static final int RECIPIENT_WIDTH = 188;
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
    private static final int SIGNATURE_X = 20;
    private static final int SIGNATURE_Y = GUI_HEIGHT - 40;
    private static final int SIGNATURE_WIDTH = 208;
    private static final int SIGNATURE_HEIGHT = 14;
    private static final int SIGNATURE_MAX_CHARS = 128;

    // "Sign" button config (bottom-left, under signature line)
    private static final int SIGN_BUTTON_X = 20;
    private static final int SIGN_BUTTON_Y = GUI_HEIGHT - 22;
    private static final int SIGN_BUTTON_WIDTH = 80;
    private static final int SIGN_BUTTON_HEIGHT = 18;

    // "Seal" button config (appears after OUTRO_DELAY_TICKS)
    private static final int SEAL_BUTTON_X = 20;
    private static final int SEAL_BUTTON_Y = GUI_HEIGHT - 22;
    private static final int SEAL_BUTTON_WIDTH = 80;
    private static final int SEAL_BUTTON_HEIGHT = 18;

    // Ender pearl icon (no vanilla button) relative to GUI origin
    private static final int PEARL_ICON_X = 20;
    private static final int PEARL_ICON_Y = 10; // moved above recipient field
    private static final int PEARL_ICON_SIZE = 16;

    private static final ItemStack PEARL_STACK = new ItemStack(Items.ENDER_PEARL);

    // Widgets
    private MultiLineScrollTextWidget recipientField;
    private MultiLineScrollTextWidget messageWidget;
    private MultiLineScrollTextWidget signatureWidget;

    // Buttons
    private Button signButton;
    private Button sealButton;

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

        // --- Scroll + UI initial state ---
        scrollAnimPhase = ScrollAnimPhase.OPENING;
        uiPhase = UiPhase.INTRO_DELAY;
        uiPhaseTicks = 0;

        // Recipient field (single-line custom widget) using Gothic font, NO newlines
        this.recipientField = new MultiLineScrollTextWidget(
                this.font,
                this.leftPos + RECIPIENT_X,
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

        // Signature widget (two lines, Gothic, no newlines from user; auto-wrap by width)
        this.signatureWidget = new MultiLineScrollTextWidget(
                this.font,
                this.leftPos + SIGNATURE_X,
                this.topPos + SIGNATURE_Y,
                SIGNATURE_WIDTH,
                SIGNATURE_HEIGHT,
                SIGNATURE_MAX_CHARS,
                2,
                Component.literal("Signature"),
                GOTHIC_FONT_ID,
                false // allowNewlines
        );
        this.signatureWidget.setText("");
        this.signatureWidget.setEditable(false);
        this.addRenderableWidget(this.signatureWidget);

        // Initially: text widgets exist but are completely hidden; they'll fade in later.
        if (this.recipientField != null) {
            this.recipientField.visible = false;
        }
        if (this.messageWidget != null) {
            this.messageWidget.visible = false;
        }
        if (this.signatureWidget != null) {
            this.signatureWidget.visible = false;
        }

        // "Sign" button (bottom-left, under signature field)
        this.signButton = Button.builder(
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
        this.addRenderableWidget(this.signButton);

        // Seal button is created later after OUTRO_DELAY_TICKS
        this.sealButton = null;

        // Recipient overlay: position just under the recipient field, expanding downward
        int overlayWidth = 180;
        int overlayHeight = 90;
        int overlayX = this.leftPos + RECIPIENT_X;
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
                        recipientField.setText("Dear " + name + ",");
                        recipientField.setCursorToEnd();
                    }

                    @Override
                    public void onOverlayClosedWithoutSelection() {
                        LOG.debug("[ScrollSealingScreen] Recipient overlay closed without selection");
                    }
                }
        );

        setWidgetsAlpha(0.0f);          // fully transparent
        setWidgetsInteractive(false);   // non-editable, no typing

        if (this.signButton != null) {
            this.signButton.visible = false;
            this.signButton.active = false;
        }

        LOG.debug("[ScrollSealingScreen] init complete: scrollAnimPhase={}, uiPhase={}", scrollAnimPhase, uiPhase);
    }

    // ---------------------------------------------------------------------
    // Helper: open overlay & clear previous UUID
    // ---------------------------------------------------------------------

    private void openRecipientOverlayIfEmpty() {
        if (recipientOverlay == null) {
            return;
        }

        String txt = recipientField.getText();
        if (txt == null || txt.isEmpty()) {
            LOG.debug("[ScrollSealingScreen] Opening recipient overlay, clearing previous UUID");
            selectedRecipientUuid = null;
            recipientOverlay.setPosition(
                    this.leftPos + RECIPIENT_X,
                    this.topPos + RECIPIENT_Y + RECIPIENT_HEIGHT + 4
            );
            recipientOverlay.open();
        }
    }

    // ---------------------------------------------------------------------
    // Signing logic
    // ---------------------------------------------------------------------

    private void onSignButtonClicked() {
        try {
            if (uiPhase != UiPhase.IDLE) {
                LOG.debug("[ScrollSealingScreen] onSignButtonClicked ignored: uiPhase={}", uiPhase);
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                LOG.warn("[ScrollSealingScreen] onSignButtonClicked: Minecraft/level/player not ready");
                return;
            }

            if (selectedRecipientUuid == null) {
                LOG.info("[ScrollSealingScreen] onSignButtonClicked: No recipient UUID set, aborting sign");
                return;
            }

            String recipientText = recipientField != null ? recipientField.getText() : null;
            if (recipientText == null || recipientText.trim().isEmpty()) {
                LOG.info("[ScrollSealingScreen] onSignButtonClicked: Recipient field is empty, aborting sign");
                return;
            }

            long dayTime = mc.level.getDayTime();
            long ticksPerDay = FFCalendarConfig.TICKS_PER_DAY;
            if (ticksPerDay <= 0L) {
                LOG.warn("[ScrollSealingScreen] onSignButtonClicked: FFCalendarConfig.TICKS_PER_DAY <= 0 ({}), using 24000 fallback", ticksPerDay);
                ticksPerDay = 24000L;
            }
            long dayIndex = dayTime / ticksPerDay;

            Component dateComponent = ClientCalendarEvents.buildDateMessage(dayIndex);
            String dateString = dateComponent.getString();

            signerUuid = mc.player.getUUID();
            String signerName = mc.player.getGameProfile().getName();

            String fullSignature = "Signed by: " + signerName + ", " + dateString;

            if (signatureWidget != null) {
                signatureWidget.setText(fullSignature);
                signatureWidget.setCursorToEnd();
            }

            LOG.info("[ScrollSealingScreen] Scroll signed by {} ({}) on {}", signerName, signerUuid, dateString);

            beginOutroFade();
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] onSignButtonClicked failed", t);
        }
    }

    private void beginOutroFade() {
        if (uiPhase == UiPhase.OUTRO_FADE_OUT || uiPhase == UiPhase.SEALED) {
            return;
        }

        uiPhase = UiPhase.OUTRO_FADE_OUT;
        uiPhaseTicks = 0;

        setWidgetsInteractive(false);

        if (this.signButton != null) {
            this.signButton.active = false;
            this.signButton.visible = false;
        }

        LOG.debug("[ScrollSealingScreen] beginOutroFade -> uiPhase={}", uiPhase);
    }

    // ---------------------------------------------------------------------
    // UI phase ticking
    // ---------------------------------------------------------------------

    private void tickUiPhase() {
        switch (uiPhase) {
            case INTRO_DELAY: {
                uiPhaseTicks++;

                // During INTRO_DELAY the opening scroll animation runs
                // and text is fully hidden/inactive.
                if (uiPhaseTicks >= INTRO_DELAY_TICKS) {
                    uiPhase = UiPhase.INTRO_FADE_IN;
                    uiPhaseTicks = 0;
                    scrollAnimPhase = ScrollAnimPhase.OPEN_STILL;
                    LOG.debug("[ScrollSealingScreen] Intro delay finished -> INTRO_FADE_IN (scroll OPEN_STILL)");
                }
                break;
            }
            case INTRO_FADE_IN: {
                uiPhaseTicks++;

                if (uiPhaseTicks == 1) {
                    setWidgetsAlpha(0.0f);

                    if (this.recipientField != null) {
                        this.recipientField.visible = true;
                    }
                    if (this.messageWidget != null) {
                        this.messageWidget.visible = true;
                    }
                    if (this.signatureWidget != null) {
                        this.signatureWidget.visible = true;
                    }
                }

                float t = (INTRO_FADE_TICKS <= 0)
                        ? 1.0f
                        : (uiPhaseTicks / (float) INTRO_FADE_TICKS);
                float alpha = Math.min(1.0f, Math.max(0.0f, t));
                setWidgetsAlpha(alpha);

                if (uiPhaseTicks >= INTRO_FADE_TICKS) {
                    setWidgetsAlpha(1.0f);
                    setWidgetsInteractive(true);
                    if (this.signButton != null) {
                        this.signButton.visible = true;
                        this.signButton.active = true;
                    }
                    uiPhase = UiPhase.IDLE;
                    uiPhaseTicks = 0;
                    LOG.debug("[ScrollSealingScreen] Intro fade finished -> IDLE");
                }
                break;
            }
            case IDLE: {
                // Normal interactive state.
                break;
            }
            case OUTRO_FADE_OUT: {
                uiPhaseTicks++;

                float t = (OUTRO_FADE_TICKS <= 0)
                        ? 1.0f
                        : (uiPhaseTicks / (float) OUTRO_FADE_TICKS);
                float alpha = 1.0f - t;
                alpha = Math.min(1.0f, Math.max(0.0f, alpha));
                setWidgetsAlpha(alpha);

                if (uiPhaseTicks >= OUTRO_FADE_TICKS) {
                    setWidgetsAlpha(0.0f);
                    setWidgetsInteractive(false);

                    if (this.recipientField != null) {
                        this.recipientField.visible = false;
                    }
                    if (this.messageWidget != null) {
                        this.messageWidget.visible = false;
                    }
                    if (this.signatureWidget != null) {
                        this.signatureWidget.visible = false;
                    }

                    uiPhase = UiPhase.SEALED;
                    uiPhaseTicks = 0;
                    LOG.debug("[ScrollSealingScreen] Outro fade finished -> SEALED");

                    // Start closing animation during OUTRO_DELAY_TICKS
                    scrollAnimPhase = ScrollAnimPhase.CLOSING;
                }
                break;
            }
            case SEALED: {
                // In SEALED phase:
                //  - scrollAnimPhase == CLOSING for OUTRO_DELAY_TICKS.
                //  - After OUTRO_DELAY_TICKS, we freeze on last closing frame and show Seal button.
                uiPhaseTicks++;

                if (uiPhaseTicks >= OUTRO_DELAY_TICKS) {
                    if (scrollAnimPhase == ScrollAnimPhase.CLOSING) {
                        scrollAnimPhase = ScrollAnimPhase.CLOSED_STILL;
                        LOG.debug("[ScrollSealingScreen] Closing animation completed within OUTRO_DELAY_TICKS");
                    }

                    uiPhaseTicks = OUTRO_DELAY_TICKS; // clamp

                    if (this.sealButton == null) {
                        this.sealButton = Button.builder(
                                        Component.literal("Seal"),
                                        b -> onSealClickedPlaceholder()
                                )
                                .bounds(
                                        this.leftPos + SEAL_BUTTON_X,
                                        this.topPos + SEAL_BUTTON_Y,
                                        SEAL_BUTTON_WIDTH,
                                        SEAL_BUTTON_HEIGHT
                                )
                                .build();
                        this.addRenderableWidget(this.sealButton);
                        LOG.debug("[ScrollSealingScreen] Seal button created after OUTRO_DELAY_TICKS");
                    }
                }
                break;
            }
        }
    }

    private void setWidgetsAlpha(float alpha) {
        int a = (int) (alpha * 255.0f);
        if (a < 0) a = 0;
        if (a > 255) a = 255;

        if (this.recipientField != null) {
            this.recipientField.setAlpha(a);
        }
        if (this.messageWidget != null) {
            this.messageWidget.setAlpha(a);
        }
        if (this.signatureWidget != null) {
            this.signatureWidget.setAlpha(a);
        }
    }

    private void setWidgetsInteractive(boolean enabled) {
        if (this.recipientField != null) {
            this.recipientField.setEditable(enabled);
        }
        if (this.messageWidget != null) {
            this.messageWidget.setEditable(enabled);
        }
        // Signature is always non-editable (just shows result)
        if (!enabled) {
            if (this.recipientField != null) {
                this.recipientField.setFocused(false);
            }
            if (this.messageWidget != null) {
                this.messageWidget.setFocused(false);
            }
        }
    }

    private boolean isUiInteractive() {
        return uiPhase == UiPhase.IDLE;
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

            tickUiPhase();
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] containerTick failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Background rendering (scroll + pearl)
    // ---------------------------------------------------------------------

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics,
                            float partialTick,
                            int mouseX,
                            int mouseY) {
        try {
            renderAnimatedScroll(guiGraphics);

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

    /**
     * Renders the scroll background using the opening/closing sprite sheets.
     *
     * The animation speed is locked to:
     *  - INTRO_DELAY_TICKS for scroll_opening.png (phase OPENING).
     *  - OUTRO_DELAY_TICKS for scroll_closing.png (phase CLOSING).
     */
    private void renderAnimatedScroll(GuiGraphics guiGraphics) {
        ResourceLocation textureToUse;
        int frameIndex;

        switch (scrollAnimPhase) {
            case OPENING: {
                textureToUse = SCROLL_OPENING_TEXTURE;

                int totalTicks = Math.max(1, INTRO_DELAY_TICKS);
                int currentTicks = Math.min(uiPhaseTicks, totalTicks);
                float progress = currentTicks / (float) totalTicks;

                // Map 0..1 -> frames 0..SCROLL_TOTAL_FRAMES-1
                frameIndex = (int) (progress * (SCROLL_TOTAL_FRAMES - 1));
                if (frameIndex < 0) frameIndex = 0;
                if (frameIndex >= SCROLL_TOTAL_FRAMES) frameIndex = SCROLL_TOTAL_FRAMES - 1;
                break;
            }
            case OPEN_STILL: {
                textureToUse = SCROLL_OPENING_TEXTURE;
                frameIndex = SCROLL_TOTAL_FRAMES - 1; // fully open
                break;
            }
            case CLOSING: {
                textureToUse = SCROLL_CLOSING_TEXTURE;

                int totalTicks = Math.max(1, OUTRO_DELAY_TICKS);
                int currentTicks = Math.min(uiPhaseTicks, totalTicks);
                float progress = currentTicks / (float) totalTicks;

                frameIndex = (int) (progress * (SCROLL_TOTAL_FRAMES - 1));
                if (frameIndex < 0) frameIndex = 0;
                if (frameIndex >= SCROLL_TOTAL_FRAMES) frameIndex = SCROLL_TOTAL_FRAMES - 1;
                break;
            }
            case CLOSED_STILL: {
                textureToUse = SCROLL_CLOSING_TEXTURE;
                frameIndex = SCROLL_TOTAL_FRAMES - 1; // fully closed
                break;
            }
            default: {
                // Fallback: legacy static texture
                guiGraphics.blit(
                        SCROLL_GUI_TEXTURE,
                        this.leftPos,
                        this.topPos,
                        0,
                        0,
                        this.imageWidth,
                        this.imageHeight
                );
                return;
            }
        }

        int textureWidth = SCROLL_FRAME_WIDTH * SCROLL_TOTAL_FRAMES; // 1680
        int textureHeight = SCROLL_FRAME_HEIGHT;                     // 208

        int u = frameIndex * SCROLL_FRAME_WIDTH;
        int v = 0;

        guiGraphics.blit(
                textureToUse,
                this.leftPos,
                this.topPos,
                (float) u,
                (float) v,
                SCROLL_FRAME_WIDTH,
                SCROLL_FRAME_HEIGHT,
                textureWidth,
                textureHeight
        );
    }

    // ---------------------------------------------------------------------
    // Foreground / main rendering
    // ---------------------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            super.render(guiGraphics, mouseX, mouseY, partialTick);

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
            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                boolean consumed = this.recipientOverlay.mouseClicked(mouseX, mouseY, button);
                if (consumed) {
                    return true;
                }
            }

            if (button == 0 && isMouseOverPearlIcon(mouseX, mouseY)) {
                onEnderPearlClicked();
                return true;
            }

            boolean result = super.mouseClicked(mouseX, mouseY, button);

            if (this.recipientField != null && this.recipientField.isFocused()) {
                String txt = this.recipientField.getText();
                if (txt == null || txt.isEmpty()) {
                    openRecipientOverlayIfEmpty();
                } else {
                    if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                        this.recipientOverlay.close(true);
                    }
                }
            } else {
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
            if (!isUiInteractive()) {
                LOG.debug("[ScrollSealingScreen] Ender pearl click ignored; uiPhase={}", uiPhase);
                return;
            }

            LOG.info("[ScrollSealingScreen] Ender pearl icon clicked (placeholder – will open item sending UI later)");
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] onEnderPearlClicked failed", t);
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        try {
            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                if (this.recipientOverlay.charTyped(codePoint, modifiers)) {
                    return true;
                }
            }

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
