// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollSealingScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.ClientCalendarEvents;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.client.gui.widget.RecipientOverlay;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.client.gui.widget.SealStampSelectionOverlay;
import net.z2six.featheredfriend.content.item.SealStampItem;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.z2six.featheredfriend.sigil.SealSigilGenerator;
import net.z2six.featheredfriend.sigil.SealSigilGenerator.SigilPattern;

import java.util.UUID;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollSealingScreen.java
 *
 * ScrollSealingScreen
 *
 * Visual front-end for ScrollSealingMenu.
 * Layout:
 *  - Date field at the top (auto-filled, non-editable).
 *  - "Dear Recipient" field (single-line via MultiLineScrollTextWidget) using Gothic font.
 *  - Multi-line message body widget using Gothic font + newline support.
 *  - Signature field ("Signature" placeholder) using Gothic font, auto-wrapping to 2 lines.
 *      * Acts as the "Sign" control: hover turns placeholder black, click performs signing.
 *      * Only auto-fills the signer name (no date).
 *  - Player list overlay using RecipientOverlay (vanilla font).
 *  - Custom animated scroll background (opening/closing) synced to UI timings.
 *  - Animated custom pearl button on the right side of the scroll:
 *      * Instantiates when text fade-in starts.
 *      * Uses a special hover frame when the mouse is over it.
 *      * Plays a disappearing animation when text fade-out starts.
 *      * On click, opens EnderPearlInventoryScreen using the same menu.
 *
 * Important:
 *  - ScrollSealingMenu holds the current text (date/recipient/message/signature).
 *    This screen saves into the menu when we leave, and restores when we come back,
 *    so text survives the pearl inventory GUI.
 *  - All container slots are hidden and disabled on this screen; only visible and
 *    usable on EnderPearlInventoryScreen.
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

    // Animated pearl texture (sprite sheet: 64x704, 11 frames vertically)
    private static final ResourceLocation PEARL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scrollscreen/pearl.png");

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
    // Pearl animation configuration
    // ---------------------------------------------------------------------

    /**
     * Pearl spritesheet: 64x704, 11 frames stacked vertically.
     * Frame indices (0-based):
     *  - 0..6  => Phase A (instantiating)
     *  - 7     => Phase B (hover)
     *  - 8..10 => Phase C (disappearing)
     */
    private static final int PEARL_FRAME_WIDTH = 32;
    private static final int PEARL_FRAME_HEIGHT = 32;
    private static final int PEARL_TOTAL_FRAMES = 11;

    private static final int PEARL_PHASE_A_START_FRAME = 0;
    private static final int PEARL_PHASE_A_END_FRAME = 6;
    private static final int PEARL_PHASE_B_HOVER_FRAME = 7;
    private static final int PEARL_PHASE_C_START_FRAME = 8;
    private static final int PEARL_PHASE_C_END_FRAME = 10;

    /**
     * Pearl animation speeds (independent of scroll timings):
     *  - These are ticks per frame for instantiating (A) and disappearing (C).
     */
    private static final int PEARL_PHASE_A_TICKS_PER_FRAME = 2;
    private static final int PEARL_PHASE_C_TICKS_PER_FRAME = 2;

    /**
     * Pearl placement / size (relative to GUI origin).
     * It sits on the right side of the scroll and is fully adjustable via these constants.
     */
    private static final int PEARL_X = SCROLL_FRAME_WIDTH - PEARL_FRAME_WIDTH - 12;
    private static final int PEARL_Y = 18;
    private static final int PEARL_WIDTH = PEARL_FRAME_WIDTH;
    private static final int PEARL_HEIGHT = PEARL_FRAME_HEIGHT;

    private enum PearlPhase {
        HIDDEN,         // not visible at all
        INSTANTIATING,  // playing frames 0..6 once
        IDLE,           // showing frame 6 (last instantiation frame)
        DISAPPEARING,   // playing frames 8..10 once
        GONE            // fully gone, nothing rendered or clickable
    }

    private PearlPhase pearlPhase = PearlPhase.HIDDEN;
    private int pearlPhaseTicks = 0;

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

    // Date field config (relative to GUI origin)
    private static final int DATE_X = 30;
    private static final int DATE_Y = 18;
    private static final int DATE_WIDTH = 150;
    private static final int DATE_HEIGHT = 14;
    private static final int DATE_MAX_CHARS = 64;

    // Recipient field config
    private static final int RECIPIENT_X = 30;
    private static final int RECIPIENT_Y = 36;
    private static final int RECIPIENT_WIDTH = 125;
    private static final int RECIPIENT_HEIGHT = 14;
    private static final int RECIPIENT_MAX_CHARS = 64;

    // Message widget config
    private static final int MESSAGE_X = 30;
    private static final int MESSAGE_Y = 62;
    private static final int MESSAGE_WIDTH = 125;
    private static final int MESSAGE_HEIGHT = 6 * 9 + 10; // ~6 lines
    private static final int MESSAGE_MAX_CHARS = 512;
    private static final int MESSAGE_MAX_LINES = 12;

    // Signature widget config
    private static final int SIGNATURE_X = 85;
    private static final int SIGNATURE_Y = GUI_HEIGHT - 20;
    private static final int SIGNATURE_WIDTH = 208;
    private static final int SIGNATURE_HEIGHT = 14;
    private static final int SIGNATURE_MAX_CHARS = 128;

    // Signature placeholder colors
    private static final int SIGNATURE_PLACEHOLDER_COLOR_DEFAULT = 0x707070;
    private static final int SIGNATURE_PLACEHOLDER_COLOR_HOVER = 0x000000;

    // Wax-seal visualizer + patterns
    private final net.z2six.featheredfriend.client.gui.WaxSealVisualizer waxSealVisualizer =
            new net.z2six.featheredfriend.client.gui.WaxSealVisualizer();

    // Live "hover" preview while cursor is in wax area with a selected stamp
    private SigilPattern hoverSigilPattern = null;

    // The placed seal (persisted during the 1s admiration fade)
    private SigilPattern placedSigilPattern = null;

    // Gentle 1s fade after placing the seal so player can admire it
    private static final int PLACED_FADE_TICKS_TOTAL = 20;
    private int placedFadeTicks = -1;        // -1 = not active
    private boolean placedSealShown = false; // true once we've "placed" the seal

    // Tunables for positioning/sizing inside the wax area (you can tweak these)
    private static final int   WAX_SIGIL_CENTER_OFFSET_X = 0;
    private static final int   WAX_SIGIL_CENTER_OFFSET_Y = 0;
    private static final float WAX_SIGIL_RADIUS_SCALE    = 0.85f; // 0.0..1.0 of min(WAX_BOX_W,H)/2

    // Zoomed sigil preview (matches SealStampScreen sizing)
    private static final int ZOOM_PREVIEW_WIDTH = 160;
    private static final int ZOOM_PREVIEW_HEIGHT = 120;

    // Same wax seal scale as SealStampScreen (WAX_SEAL_SCREEN_SCALE = 3.0f)
    private static final float ZOOM_WAX_SEAL_SCREEN_SCALE = 3.0f;

    // Same sigil radius as SealStampScreen (SIGIL_BASE_DIAMETER_PIXELS=90, SIGIL_RADIUS_SCALE=0.85)
    private static final int ZOOM_SIGIL_RADIUS = 38; // (int)(90 * 0.5f * 0.85f)

    // Widgets
    private MultiLineScrollTextWidget dateWidget;
    private MultiLineScrollTextWidget recipientField;
    private MultiLineScrollTextWidget messageWidget;
    private MultiLineScrollTextWidget signatureWidget;

    // Stamp selection / cursor state
    private SealStampSelectionOverlay sealStampOverlay;
    private boolean sealStampSelectionStarted = false;
    private boolean sealStampTargetMode = false;
    private int sealStampSlotIndex = -1;
    private ItemStack sealStampStackForRender = ItemStack.EMPTY;

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
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
    }

    // ---------------------------------------------------------------------
    // Wax seal debug bounding box (for future hit-test; drawn as gizmo lines)
    // ---------------------------------------------------------------------

    private static final int WAX_BOX_X = 94;      // relative to GUI origin (leftPos)
    private static final int WAX_BOX_Y = 74;       // adjust as needed
    private static final int WAX_BOX_WIDTH = 36;
    private static final int WAX_BOX_HEIGHT = 36;
    private static final int WAX_BOX_COLOR = 0x80FF0000; // semi-transparent red

    // Handy helper so we always treat the menu as ScrollSealingMenu,
    // even if the class header was ever generic.
    private ScrollSealingMenu getScrollMenu() {
        return (ScrollSealingMenu) this.menu;
    }

    @Override
    protected void init() {
        super.init();

        LOG.debug("[ScrollSealingScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);
        this.clearWidgets();

        // Default: assume we are playing the full intro animation
        scrollAnimPhase = ScrollAnimPhase.OPENING;
        uiPhase = UiPhase.INTRO_DELAY;
        uiPhaseTicks = 0;

        pearlPhase = PearlPhase.HIDDEN;
        pearlPhaseTicks = 0;

        // Date field
        this.dateWidget = new MultiLineScrollTextWidget(
                this.font,
                this.leftPos + DATE_X,
                this.topPos + DATE_Y,
                DATE_WIDTH,
                DATE_HEIGHT,
                DATE_MAX_CHARS,
                1,
                Component.literal("Date"),
                GOTHIC_FONT_ID,
                false
        );
        this.dateWidget.setEditable(false);
        this.dateWidget.setText("");
        this.addRenderableWidget(this.dateWidget);

        // Recipient field
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
                false
        );
        this.addRenderableWidget(this.recipientField);

        // Message widget
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
                true
        );
        this.addRenderableWidget(this.messageWidget);

        // Signature widget
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
                false
        );
        this.signatureWidget.setText("");
        this.signatureWidget.setEditable(false);
        this.signatureWidget.setPlaceholderColor(SIGNATURE_PLACEHOLDER_COLOR_DEFAULT);
        this.addRenderableWidget(this.signatureWidget);

        // Initially hidden; fade in later (unless we skip intro)
        if (this.dateWidget != null) this.dateWidget.visible = false;
        if (this.recipientField != null) this.recipientField.visible = false;
        if (this.messageWidget != null) this.messageWidget.visible = false;
        if (this.signatureWidget != null) this.signatureWidget.visible = false;

        // Recipient overlay
        int overlayWidth = 180;
        int overlayHeight = 90;
        int overlayX = this.leftPos + RECIPIENT_X;
        int overlayY = this.topPos + RECIPIENT_Y + RECIPIENT_HEIGHT + 4;

        this.recipientOverlay = new RecipientOverlay(
                Minecraft.getInstance(),
                this.font,
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
                        saveEditorStateToMenu();
                    }

                    @Override
                    public void onOverlayClosedWithoutSelection() {
                        LOG.debug("[ScrollSealingScreen] Recipient overlay closed without selection");
                    }
                }
        );

        // Seal stamp overlay (3x3 grid, 9 slots)
        int stampOverlayWidth = 9 * 18 + 8;
        int stampOverlayHeight = 3 * 18 + 8;
        int stampOverlayX = this.leftPos + (GUI_WIDTH - stampOverlayWidth) / 2;
        int stampOverlayY = this.topPos + MESSAGE_Y + MESSAGE_HEIGHT + 10;

        this.sealStampOverlay = new SealStampSelectionOverlay(
                Minecraft.getInstance(),
                this.font,
                stampOverlayX,
                stampOverlayY,
                stampOverlayWidth,
                stampOverlayHeight,
                new SealStampSelectionOverlay.SelectionCallback() {
                    @Override
                    public void onStampSelected(int slotIndex, @NotNull ItemStack stack) {
                        LOG.debug("[ScrollSealingScreen] Seal stamp selected: slotIndex={} stack={}", slotIndex, stack);
                        sealStampSlotIndex = slotIndex;
                        sealStampStackForRender = stack.copy();
                        sealStampTargetMode = !sealStampStackForRender.isEmpty();
                    }

                    @Override
                    public void onOverlayClosedWithoutSelection() {
                        LOG.debug("[ScrollSealingScreen] Seal stamp overlay closed without selection");
                        sealStampTargetMode = false;
                        sealStampSlotIndex = -1;
                        sealStampStackForRender = ItemStack.EMPTY;
                    }
                }
        );
        this.sealStampOverlay.setActive(false);
        this.sealStampSelectionStarted = false;

        restoreEditorStateFromMenu();

        ScrollSealingMenu m = getScrollMenu();
        if (m.isClientSkipIntroAnimation()) {
            // Returning from the pearl inventory screen:
            //  - Skip intro delay + fade + scroll opening animation.
            //  - Go straight to "scroll fully open, text visible, pearl instantiated".
            uiPhase = UiPhase.IDLE;
            uiPhaseTicks = 0;
            scrollAnimPhase = ScrollAnimPhase.OPEN_STILL;

            pearlPhase = PearlPhase.IDLE;
            pearlPhaseTicks = 0;

            if (this.dateWidget != null) {
                this.dateWidget.visible = true;
                fillDateWidgetIfNeeded();
            }
            if (this.recipientField != null) this.recipientField.visible = true;
            if (this.messageWidget != null) this.messageWidget.visible = true;
            if (this.signatureWidget != null) this.signatureWidget.visible = true;

            setWidgetsAlpha(1.0f);
            setWidgetsInteractive(true);

            // Consume the hint so the next fresh open (new container) plays the animation again.
            m.setClientSkipIntroAnimation(false);

            LOG.debug("[ScrollSealingScreen] init: skipping intro animation (return from pearl inventory)");
        } else {
            setWidgetsAlpha(0.0f);
            setWidgetsInteractive(false);

            LOG.debug("[ScrollSealingScreen] init complete: scrollAnimPhase={}, uiPhase={}, pearlPhase={}",
                    scrollAnimPhase, uiPhase, pearlPhase);
        }
    }

    // -- Draws the small in-place seal + optional zoomed preview on hover --
    private void renderWaxSeal(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        try {
            // Only active while we're in the sealing phase (after text faded out).
            if (this.uiPhase != UiPhase.SEALED) {
                return;
            }

            final boolean inWaxArea = isMouseInWaxArea(mouseX, mouseY);

            // Small in-place seal inside the wax gizmo box
            final int smallClipX = this.leftPos + WAX_BOX_X;
            final int smallClipY = this.topPos + WAX_BOX_Y;
            final int smallClipW = WAX_BOX_WIDTH;
            final int smallClipH = WAX_BOX_HEIGHT;

            final int smallCenterX = smallClipX + (smallClipW / 2) + WAX_SIGIL_CENTER_OFFSET_X;
            final int smallCenterY = smallClipY + (smallClipH / 2) + WAX_SIGIL_CENTER_OFFSET_Y;

            // Radius for the small in-place impression (shapes-only)
            final int smallRadiusPx = Math.max(
                    6,
                    (int) (Math.min(smallClipW, smallClipH) * 0.5f * WAX_SIGIL_RADIUS_SCALE)
            );

            // Pattern to use (placed wins over hover)
            SigilPattern patternForSmall = null;

            // 1) If we've already placed the seal, always render that impression
            if (placedSealShown && placedSigilPattern != null) {
                patternForSmall = placedSigilPattern;
            } else {
                // 2) Live preview while targeting inside wax area
                if (this.sealStampTargetMode
                        && this.sealStampSlotIndex >= 0
                        && this.sealStampStackForRender != null
                        && !this.sealStampStackForRender.isEmpty()
                        && inWaxArea) {

                    if (this.hoverSigilPattern == null) {
                        this.hoverSigilPattern = buildPatternFromStamp(this.sealStampStackForRender);
                    }
                    if (this.hoverSigilPattern != null) {
                        patternForSmall = this.hoverSigilPattern;
                    }
                }
            }

            // --- Small in-place impression: shapes only (no wax) ---
            if (patternForSmall != null) {
                try {
                    waxSealVisualizer.renderShapesOnly(
                            g,
                            smallCenterX,
                            smallCenterY,
                            smallClipX,
                            smallClipY,
                            smallClipW,
                            smallClipH,
                            smallRadiusPx,
                            1.0f, // unused by renderShapesOnly
                            patternForSmall
                    );
                } catch (Throwable t) {
                    LOG.error("[ScrollSealingScreen] renderWaxSeal: small shapes-only impression failed", t);
                }
            }

            // --- Zoomed “inspect” view on hover: wax + sigil, SealStampScreen sizing ---
            //
            // Only while the cursor is actually hovering the gizmo area,
            // AND we are NOT in the admiration fade (placedSealShown && placedFadeTicks >= 0).
            if (inWaxArea
                    && patternForSmall != null
                    && !(placedSealShown && placedFadeTicks >= 0)) {
                try {
                    // Center the zoom around the same center as the small seal
                    int zoomCenterX = smallCenterX;
                    int zoomCenterY = smallCenterY;

                    // Clip rect big enough to hold the full wax seal (same size as SealStampScreen preview)
                    int zoomClipX = zoomCenterX - ZOOM_PREVIEW_WIDTH / 2;
                    int zoomClipY = zoomCenterY - ZOOM_PREVIEW_HEIGHT / 2;
                    int zoomClipW = ZOOM_PREVIEW_WIDTH;
                    int zoomClipH = ZOOM_PREVIEW_HEIGHT;

                    waxSealVisualizer.render(
                            g,
                            zoomCenterX,
                            zoomCenterY,
                            zoomClipX,
                            zoomClipY,
                            zoomClipW,
                            zoomClipH,
                            ZOOM_SIGIL_RADIUS,
                            ZOOM_WAX_SEAL_SCREEN_SCALE,
                            patternForSmall
                    );
                } catch (Throwable t) {
                    LOG.error("[ScrollSealingScreen] renderWaxSeal: zoomed preview failed", t);
                }
            }

        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] renderWaxSeal failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Editor state save/restore against the menu
    // ---------------------------------------------------------------------

    private void saveEditorStateToMenu() {
        try {
            ScrollSealingMenu m = getScrollMenu();
            m.setClientDateText(this.dateWidget != null ? this.dateWidget.getText() : "");
            m.setClientRecipientText(this.recipientField != null ? this.recipientField.getText() : "");
            m.setClientMessageText(this.messageWidget != null ? this.messageWidget.getText() : "");
            m.setClientSignatureText(this.signatureWidget != null ? this.signatureWidget.getText() : "");
            LOG.debug("[ScrollSealingScreen] Editor state saved to menu");
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] saveEditorStateToMenu failed", t);
        }
    }

    private void restoreEditorStateFromMenu() {
        try {
            ScrollSealingMenu m = getScrollMenu();

            if (this.dateWidget != null) {
                String dt = m.getClientDateText();
                if (!dt.isEmpty()) {
                    this.dateWidget.setText(dt);
                    this.dateWidget.setCursorToEnd();
                }
            }
            if (this.recipientField != null) {
                String rt = m.getClientRecipientText();
                if (!rt.isEmpty()) {
                    this.recipientField.setText(rt);
                    this.recipientField.setCursorToEnd();
                }
            }
            if (this.messageWidget != null) {
                String mt = m.getClientMessageText();
                if (!mt.isEmpty()) {
                    this.messageWidget.setText(mt);
                    this.messageWidget.setCursorToEnd();
                }
            }
            if (this.signatureWidget != null) {
                String st = m.getClientSignatureText();
                if (!st.isEmpty()) {
                    this.signatureWidget.setText(st);
                    this.signatureWidget.setCursorToEnd();
                }
            }
            LOG.debug("[ScrollSealingScreen] Editor state restored from menu");
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] restoreEditorStateFromMenu failed", t);
        }
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

    // -- New helper to construct the preview/placed pattern from a Seal Stamp stack --
    private SigilPattern buildPatternFromStamp(@NotNull ItemStack stampStack) {
        try {
            if (stampStack == null || stampStack.isEmpty() || !(stampStack.getItem() instanceof SealStampItem)) {
                LOG.debug("[ScrollSealingScreen] buildPatternFromStamp: not a SealStampItem");
                return null;
            }

            CustomData data = stampStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag root = data.copyTag();
            if (root == null || !root.contains("SealStamp")) {
                LOG.debug("[ScrollSealingScreen] buildPatternFromStamp: missing SealStamp tag");
                return null;
            }

            CompoundTag seal = root.getCompound("SealStamp");
            long seed   = seal.getLong("Seed");
            int  slices = seal.getInt("Slices");
            int  style  = seal.getInt("ShapeSet");

            // Compute a radius that will fit nicely inside our wax box.
            final int radiusPx = Math.max(6, (int)(Math.min(WAX_BOX_WIDTH, WAX_BOX_HEIGHT) * 0.5f * WAX_SIGIL_RADIUS_SCALE));
            SigilPattern pattern = SealSigilGenerator.generateFromSeed(seed, radiusPx, slices, style);

            LOG.debug("[ScrollSealingScreen] buildPatternFromStamp -> pattern ok (seed={} slices={} style={} radius={})",
                    seed, slices, style, radiusPx);

            return pattern;
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] buildPatternFromStamp failed", t);
            return null;
        }
    }

    // -- Small helper; mirrors your existing math but reusable in multiple places --
    private boolean isMouseInWaxArea(double mouseX, double mouseY) {
        int waxX0 = this.leftPos + WAX_BOX_X;
        int waxY0 = this.topPos + WAX_BOX_Y;
        int waxX1 = waxX0 + WAX_BOX_WIDTH;
        int waxY1 = waxY0 + WAX_BOX_HEIGHT;
        return mouseX >= waxX0 && mouseX < waxX1 && mouseY >= waxY0 && mouseY < waxY1;
    }

    // ---------------------------------------------------------------------
    // Date helper
    // ---------------------------------------------------------------------

    private String computeCurrentDateString() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) {
                LOG.warn("[ScrollSealingScreen] computeCurrentDateString: Minecraft/level not ready, using fallback");
                return "Unknown Date";
            }

            long dayTime = mc.level.getDayTime();
            long ticksPerDay = FFCalendarConfig.TICKS_PER_DAY;
            if (ticksPerDay <= 0L) {
                LOG.warn("[ScrollSealingScreen] computeCurrentDateString: FFCalendarConfig.TICKS_PER_DAY <= 0 ({}), using 24000 fallback", ticksPerDay);
                ticksPerDay = 24000L;
            }
            long dayIndex = dayTime / ticksPerDay;

            // Use the exact same logic we used in the old Sign button,
            // no extra normalization or string slicing.
            Component dateComponent = ClientCalendarEvents.buildDateMessage(dayIndex);
            return dateComponent.getString();
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] computeCurrentDateString failed", t);
            return "Unknown Date";
        }
    }

    private void fillDateWidgetIfNeeded() {
        if (this.dateWidget == null) {
            return;
        }
        try {
            String current = this.dateWidget.getText();
            if (current != null && !current.isEmpty()) {
                return;
            }
            String dateString = computeCurrentDateString();
            this.dateWidget.setText(dateString);
            this.dateWidget.setCursorToEnd();
            LOG.debug("[ScrollSealingScreen] Date widget filled with '{}'", dateString);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] fillDateWidgetIfNeeded failed", t);
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

            String dateString = computeCurrentDateString();

            signerUuid = mc.player.getUUID();
            String signerName = mc.player.getGameProfile().getName();

            String fullSignature = signerName;

            if (signatureWidget != null) {
                signatureWidget.setText(fullSignature);
                signatureWidget.setCursorToEnd();
            }

            saveEditorStateToMenu();

            LOG.info("[ScrollSealingScreen] Scroll signed by {} ({}) on {}",
                    signerName, signerUuid, dateString);

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

        pearlPhase = PearlPhase.DISAPPEARING;
        pearlPhaseTicks = 0;

        LOG.debug("[ScrollSealingScreen] beginOutroFade -> uiPhase={}, pearlPhase={}", uiPhase, pearlPhase);
    }

    // ---------------------------------------------------------------------
    // UI phase ticking
    // ---------------------------------------------------------------------

    private void tickUiPhase() {
        switch (uiPhase) {
            case INTRO_DELAY: {
                uiPhaseTicks++;

                if (uiPhaseTicks >= INTRO_DELAY_TICKS) {
                    uiPhase = UiPhase.INTRO_FADE_IN;
                    uiPhaseTicks = 0;
                    scrollAnimPhase = ScrollAnimPhase.OPEN_STILL;

                    pearlPhase = PearlPhase.INSTANTIATING;
                    pearlPhaseTicks = 0;

                    LOG.debug("[ScrollSealingScreen] Intro delay finished -> INTRO_FADE_IN");
                }
                break;
            }
            case INTRO_FADE_IN: {
                uiPhaseTicks++;

                if (uiPhaseTicks == 1) {
                    setWidgetsAlpha(0.0f);

                    if (this.dateWidget != null) {
                        this.dateWidget.visible = true;
                        fillDateWidgetIfNeeded();
                    }
                    if (this.recipientField != null) this.recipientField.visible = true;
                    if (this.messageWidget != null) this.messageWidget.visible = true;
                    if (this.signatureWidget != null) this.signatureWidget.visible = true;
                }

                float t = (INTRO_FADE_TICKS <= 0)
                        ? 1.0f
                        : (uiPhaseTicks / (float) INTRO_FADE_TICKS);
                float alpha = Math.min(1.0f, Math.max(0.0f, t));
                setWidgetsAlpha(alpha);

                if (uiPhaseTicks >= INTRO_FADE_TICKS) {
                    setWidgetsAlpha(1.0f);
                    setWidgetsInteractive(true);
                    uiPhase = UiPhase.IDLE;
                    uiPhaseTicks = 0;
                    LOG.debug("[ScrollSealingScreen] Intro fade finished -> IDLE");
                }
                break;
            }
            case IDLE: {
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

                    if (this.dateWidget != null) this.dateWidget.visible = false;
                    if (this.recipientField != null) this.recipientField.visible = false;
                    if (this.messageWidget != null) this.messageWidget.visible = false;
                    if (this.signatureWidget != null) this.signatureWidget.visible = false;

                    uiPhase = UiPhase.SEALED;
                    uiPhaseTicks = 0;
                    scrollAnimPhase = ScrollAnimPhase.CLOSING;
                    LOG.debug("[ScrollSealingScreen] Outro fade finished -> SEALED");
                }
                break;
            }
            case SEALED: {
                uiPhaseTicks++;

                if (uiPhaseTicks >= OUTRO_DELAY_TICKS) {
                    if (scrollAnimPhase == ScrollAnimPhase.CLOSING) {
                        scrollAnimPhase = ScrollAnimPhase.CLOSED_STILL;
                        LOG.debug("[ScrollSealingScreen] Closing animation completed within OUTRO_DELAY_TICKS");
                    }

                    uiPhaseTicks = OUTRO_DELAY_TICKS;

                    // Begin stamp-selection phase once.
                    if (!sealStampSelectionStarted) {
                        sealStampSelectionStarted = true;

                        if (this.sealStampOverlay != null) {
                            // Re-center overlay horizontally against the current GUI position.
                            int stampOverlayX = this.leftPos + (GUI_WIDTH - this.sealStampOverlay.getWidth()) / 2;
                            int stampOverlayY = this.topPos + MESSAGE_Y + MESSAGE_HEIGHT + 10;
                            this.sealStampOverlay.setPosition(stampOverlayX, stampOverlayY);
                            this.sealStampOverlay.setActive(true);
                            this.sealStampOverlay.rebuildEntriesAndAutoSelectFavorite();
                            LOG.debug("[ScrollSealingScreen] Seal stamp overlay activated for sealing");
                        } else {
                            LOG.warn("[ScrollSealingScreen] SEALED phase reached but sealStampOverlay is null");
                        }
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

        if (this.dateWidget != null) this.dateWidget.setAlpha(a);
        if (this.recipientField != null) this.recipientField.setAlpha(a);
        if (this.messageWidget != null) this.messageWidget.setAlpha(a);
        if (this.signatureWidget != null) this.signatureWidget.setAlpha(a);
    }

    private void setWidgetsInteractive(boolean enabled) {
        if (this.recipientField != null) this.recipientField.setEditable(enabled);
        if (this.messageWidget != null) this.messageWidget.setEditable(enabled);

        if (!enabled) {
            if (this.recipientField != null) this.recipientField.setFocused(false);
            if (this.messageWidget != null) this.messageWidget.setFocused(false);
        }
    }

    private boolean isUiInteractive() {
        return uiPhase == UiPhase.IDLE;
    }

    // ---------------------------------------------------------------------
    // Pearl ticking
    // ---------------------------------------------------------------------

    private void tickPearl() {
        switch (pearlPhase) {
            case HIDDEN:
            case IDLE:
            case GONE:
                break;
            case INSTANTIATING: {
                pearlPhaseTicks++;
                int frames = PEARL_PHASE_A_END_FRAME - PEARL_PHASE_A_START_FRAME + 1;
                int totalTicks = frames * Math.max(1, PEARL_PHASE_A_TICKS_PER_FRAME);
                if (pearlPhaseTicks >= totalTicks) {
                    pearlPhase = PearlPhase.IDLE;
                    pearlPhaseTicks = 0;
                    LOG.debug("[ScrollSealingScreen] Pearl instantiation finished -> IDLE");
                }
                break;
            }
            case DISAPPEARING: {
                pearlPhaseTicks++;
                int frames = PEARL_PHASE_C_END_FRAME - PEARL_PHASE_C_START_FRAME + 1;
                int totalTicks = frames * Math.max(1, PEARL_PHASE_C_TICKS_PER_FRAME);
                if (pearlPhaseTicks >= totalTicks) {
                    pearlPhase = PearlPhase.GONE;
                    pearlPhaseTicks = 0;
                    LOG.debug("[ScrollSealingScreen] Pearl disappearing finished -> GONE");
                }
                break;
            }
        }
    }

    private boolean isPearlClickable() {
        return pearlPhase == PearlPhase.IDLE && isUiInteractive();
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        try {
            if (this.dateWidget != null) this.dateWidget.tick();
            if (this.recipientField != null) this.recipientField.tick();
            if (this.messageWidget != null) this.messageWidget.tick();
            if (this.signatureWidget != null) this.signatureWidget.tick();
            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                this.recipientOverlay.tick();
            }
            if (this.sealStampOverlay != null && this.sealStampOverlay.isActive()) {
                this.sealStampOverlay.tick();
            }

            tickUiPhase();
            tickPearl();

            // Admiration fade loop
            if (placedSealShown && placedFadeTicks >= 0) {
                if (placedFadeTicks < PLACED_FADE_TICKS_TOTAL) {
                    placedFadeTicks++;
                } else {
                    // Close once fade completed
                    try {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null) {
                            if (mc.player != null) mc.player.closeContainer();
                            mc.setScreen(null);
                        }
                    } catch (Throwable closeErr) {
                        LOG.error("[ScrollSealingScreen] Failed to close after admiration fade", closeErr);
                    } finally {
                        placedFadeTicks = -1;
                    }
                }
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
            renderAnimatedScroll(guiGraphics);
            renderPearl(guiGraphics, mouseX, mouseY);

            // Only show the wax seal gizmo while in the sealing phase (SEALED).
            if (this.uiPhase == UiPhase.SEALED) {
                int waxX0 = this.leftPos + WAX_BOX_X;
                int waxY0 = this.topPos + WAX_BOX_Y;
                int waxX1 = waxX0 + WAX_BOX_WIDTH;
                int waxY1 = waxY0 + WAX_BOX_HEIGHT;

                // Outline rectangle
                guiGraphics.fill(waxX0, waxY0, waxX1, waxY0 + 1, WAX_BOX_COLOR);           // top
                guiGraphics.fill(waxX0, waxY1 - 1, waxX1, waxY1, WAX_BOX_COLOR);           // bottom
                guiGraphics.fill(waxX0, waxY0, waxX0 + 1, waxY1, WAX_BOX_COLOR);           // left
                guiGraphics.fill(waxX1 - 1, waxY0, waxX1, waxY1, WAX_BOX_COLOR);           // right

                // Optional crosshair lines inside the box (gizmo style)
                int midX = (waxX0 + waxX1) / 2;
                int midY = (waxY0 + waxY1) / 2;
                guiGraphics.fill(midX - 1, waxY0 + 2, midX + 1, waxY1 - 2, 0x40FF0000);
                guiGraphics.fill(waxX0 + 2, midY - 1, waxX1 - 2, midY + 1, 0x40FF0000);
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

    private void renderAnimatedScroll(GuiGraphics guiGraphics) {
        ResourceLocation textureToUse;
        int frameIndex;

        switch (scrollAnimPhase) {
            case OPENING: {
                textureToUse = SCROLL_OPENING_TEXTURE;

                int totalTicks = Math.max(1, INTRO_DELAY_TICKS);
                int currentTicks = Math.min(uiPhaseTicks, totalTicks);
                float progress = totalTicks == 0 ? 1.0f : (currentTicks / (float) totalTicks);

                frameIndex = (int) (progress * (SCROLL_TOTAL_FRAMES - 1));
                if (frameIndex < 0) frameIndex = 0;
                if (frameIndex >= SCROLL_TOTAL_FRAMES) frameIndex = SCROLL_TOTAL_FRAMES - 1;
                break;
            }
            case OPEN_STILL: {
                textureToUse = SCROLL_OPENING_TEXTURE;
                frameIndex = SCROLL_TOTAL_FRAMES - 1;
                break;
            }
            case CLOSING: {
                textureToUse = SCROLL_CLOSING_TEXTURE;

                int totalTicks = Math.max(1, OUTRO_DELAY_TICKS);
                int currentTicks = Math.min(uiPhaseTicks, totalTicks);
                float progress = totalTicks == 0 ? 1.0f : (currentTicks / (float) totalTicks);

                frameIndex = (int) (progress * (SCROLL_TOTAL_FRAMES - 1));
                if (frameIndex < 0) frameIndex = 0;
                if (frameIndex >= SCROLL_TOTAL_FRAMES) frameIndex = SCROLL_TOTAL_FRAMES - 1;
                break;
            }
            case CLOSED_STILL: {
                textureToUse = SCROLL_CLOSING_TEXTURE;
                frameIndex = SCROLL_TOTAL_FRAMES - 1;
                break;
            }
            default: {
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

        int textureWidth = SCROLL_FRAME_WIDTH * SCROLL_TOTAL_FRAMES;
        int textureHeight = SCROLL_FRAME_HEIGHT;

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

    private void renderPearl(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (pearlPhase == PearlPhase.HIDDEN || pearlPhase == PearlPhase.GONE) {
            return;
        }

        boolean hovered = isMouseOverPearlIcon(mouseX, mouseY) && isUiInteractive();

        int frameIndex;

        if (hovered && pearlPhase == PearlPhase.IDLE) {
            frameIndex = PEARL_PHASE_B_HOVER_FRAME;
        } else {
            switch (pearlPhase) {
                case INSTANTIATING: {
                    int frames = PEARL_PHASE_A_END_FRAME - PEARL_PHASE_A_START_FRAME + 1;
                    int ticksPerFrame = Math.max(1, PEARL_PHASE_A_TICKS_PER_FRAME);
                    int maxTicks = frames * ticksPerFrame;
                    int clampedTicks = Math.min(pearlPhaseTicks, maxTicks - 1);
                    int offset = clampedTicks / ticksPerFrame;
                    frameIndex = PEARL_PHASE_A_START_FRAME + offset;
                    break;
                }
                case IDLE: {
                    frameIndex = PEARL_PHASE_A_END_FRAME;
                    break;
                }
                case DISAPPEARING: {
                    int frames = PEARL_PHASE_C_END_FRAME - PEARL_PHASE_C_START_FRAME + 1;
                    int ticksPerFrame = Math.max(1, PEARL_PHASE_C_TICKS_PER_FRAME);
                    int maxTicks = frames * ticksPerFrame;
                    int clampedTicks = Math.min(pearlPhaseTicks, maxTicks - 1);
                    int offset = clampedTicks / ticksPerFrame;
                    frameIndex = PEARL_PHASE_C_START_FRAME + offset;
                    break;
                }
                case HIDDEN:
                case GONE:
                default:
                    return;
            }
        }

        if (frameIndex < 0 || frameIndex >= PEARL_TOTAL_FRAMES) {
            return;
        }

        int textureWidth = PEARL_FRAME_WIDTH;
        int textureHeight = PEARL_FRAME_HEIGHT * PEARL_TOTAL_FRAMES;

        int u = 0;
        int v = frameIndex * PEARL_FRAME_HEIGHT;

        int x = this.leftPos + PEARL_X;
        int y = this.topPos + PEARL_Y;

        guiGraphics.blit(
                PEARL_TEXTURE,
                x,
                y,
                (float) u,
                (float) v,
                PEARL_WIDTH,
                PEARL_HEIGHT,
                textureWidth,
                textureHeight
        );
    }

    // ---------------------------------------------------------------------
    // Foreground rendering
    // ---------------------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            // 1) Normal dim and container
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            super.render(guiGraphics, mouseX, mouseY, partialTick);

            // 2) Draw our wax seal preview/placed version right after the parchment
            renderWaxSeal(guiGraphics, mouseX, mouseY, partialTick);

            // Keep your existing UI bits unchanged:
            if (this.signatureWidget != null && uiPhase == UiPhase.IDLE) {
                boolean hoverSignature = isMouseOverSignature(mouseX, mouseY)
                        && this.signatureWidget.getText().isEmpty();
                this.signatureWidget.setPlaceholderColor(
                        hoverSignature ? SIGNATURE_PLACEHOLDER_COLOR_HOVER : SIGNATURE_PLACEHOLDER_COLOR_DEFAULT
                );
            }

            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                this.recipientOverlay.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            if (this.sealStampOverlay != null && this.sealStampOverlay.isActive()) {
                this.sealStampOverlay.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            // Stamp cursor icon while targeting
            if (sealStampTargetMode && sealStampSlotIndex >= 0 && sealStampStackForRender != null && !sealStampStackForRender.isEmpty()) {
                try {
                    int iconX = mouseX - 8;
                    int iconY = mouseY - 8;
                    guiGraphics.renderItem(sealStampStackForRender, iconX, iconY);
                    guiGraphics.renderItemDecorations(this.font, sealStampStackForRender, iconX, iconY);
                } catch (Throwable t) {
                    LOG.error("[ScrollSealingScreen] Failed to render seal stamp cursor", t);
                }
            }

            // 3) Gentle 1s admiration fade after placing the seal
            if (placedSealShown && placedFadeTicks >= 0) {
                float t = Math.min(1.0f, placedFadeTicks / (float) PLACED_FADE_TICKS_TOTAL);
                int alpha = (int)(t * 255.0f);
                if (alpha < 0) alpha = 0;
                if (alpha > 255) alpha = 255;

                // Global black overlay (same style as your inventory fade)
                int color = (alpha << 24); // ARGB with black RGB
                guiGraphics.fill(0, 0, this.width, this.height, color);
            }

            // 4) Tooltips last
            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] render failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // No default labels.
    }

    /**
     * Make all vanilla container-slot hover checks report "not hovering" while
     * this screen is active, so attachment/inventory slots are truly inert here.
     */
    @Override
    protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        return false;
    }

    @Override
    protected void renderTooltip(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Ensure no slot tooltip pops up from the hidden inventory slots.
        this.hoveredSlot = null;
        super.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            // If we're in admiration fade, swallow input
            if (placedSealShown && placedFadeTicks >= 0) {
                return true;
            }

            // 1) Recipient overlay has highest priority if active
            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                boolean consumed = this.recipientOverlay.mouseClicked(mouseX, mouseY, button);
                if (consumed) {
                    return true;
                }
            }

            // 2) Seal stamp overlay – only interactive during SEALED phase
            if (this.sealStampOverlay != null
                    && this.sealStampOverlay.isActive()
                    && this.uiPhase == UiPhase.SEALED) {

                boolean consumedStamp = this.sealStampOverlay.mouseClicked(mouseX, mouseY, button);
                if (consumedStamp) {
                    // Rebuild hover pattern if selection changed (lazy build happens in render, too)
                    if (this.sealStampTargetMode && this.sealStampStackForRender != null && !this.sealStampStackForRender.isEmpty()) {
                        this.hoverSigilPattern = null; // force rebuild next render for freshness
                    }
                    return true;
                }
            }

            // 3) Stamp deselect / wax-click behaviour (only in SEALED phase)
            if (this.uiPhase == UiPhase.SEALED
                    && this.sealStampTargetMode
                    && this.sealStampSlotIndex >= 0
                    && this.sealStampStackForRender != null
                    && !this.sealStampStackForRender.isEmpty()) {

                boolean inWaxArea = isMouseInWaxArea(mouseX, mouseY);

                // LMB behaviour while a stamp is selected
                if (button == 0) {
                    if (inWaxArea) {
                        // This is the "stamp the wax" click.
                        LOG.info(
                                "[ScrollSealingScreen] Wax seal area clicked with selected stamp: slotIndex={} stack={}",
                                this.sealStampSlotIndex,
                                this.sealStampStackForRender
                        );

                        // -------------------------
                        // Send WaxSealPacket (same as before)
                        // -------------------------
                        try {
                            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                            if (mc == null || mc.player == null) {
                                LOG.error("[ScrollSealingScreen] Cannot seal: Minecraft or player is null");
                                return true;
                            }

                            net.minecraft.world.item.ItemStack actualStamp = net.minecraft.world.item.ItemStack.EMPTY;
                            if (this.sealStampSlotIndex >= 0 && this.sealStampSlotIndex <= 35) {
                                if (this.sealStampSlotIndex < mc.player.getInventory().items.size()) {
                                    actualStamp = mc.player.getInventory().items.get(this.sealStampSlotIndex);
                                }
                            } else if (this.sealStampSlotIndex == 37) {
                                actualStamp = mc.player.getOffhandItem();
                            } else {
                                LOG.warn("[ScrollSealingScreen] Unexpected stamp slot index {} (expected 0..35 or 37)", this.sealStampSlotIndex);
                            }

                            if (actualStamp == null || actualStamp.isEmpty() || !(actualStamp.getItem() instanceof SealStampItem)) {
                                LOG.error("[ScrollSealingScreen] Selected stack is not a valid SealStampItem; aborting seal");
                                return true;
                            }

                            if (!SealStampItem.isEtched(actualStamp)) {
                                LOG.error("[ScrollSealingScreen] Selected SealStampItem is not etched; aborting seal");
                                return true;
                            }

                            // Extract stamp NBT
                            String senderName = "";
                            long seed = 0L;
                            int  slices = 0;
                            int  style = 0;

                            try {
                                var cd = actualStamp.getOrDefault(
                                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                                        net.minecraft.world.item.component.CustomData.EMPTY
                                );
                                net.minecraft.nbt.CompoundTag root = cd.copyTag();
                                if (root == null || !root.contains("SealStamp")) {
                                    LOG.error("[ScrollSealingScreen] SealStamp CustomData missing on etched stamp; aborting");
                                    return true;
                                }
                                net.minecraft.nbt.CompoundTag seal = root.getCompound("SealStamp");
                                senderName = seal.getString("Owner");
                                seed       = seal.getLong("Seed");
                                slices     = seal.getInt("Slices");
                                style      = seal.getInt("ShapeSet");
                            } catch (Throwable te) {
                                LOG.error("[ScrollSealingScreen] Failed extracting SealStamp CustomData", te);
                                return true;
                            }

                            String recipientText  = this.recipientField != null ? this.recipientField.getText() : "";
                            String messageText    = this.messageWidget   != null ? this.messageWidget.getText()   : "";
                            String signatureText  = this.signatureWidget != null ? this.signatureWidget.getText() : "";

                            String recipientUUIDStr = this.selectedRecipientUuid != null ? this.selectedRecipientUuid.toString() : "";
                            if (recipientUUIDStr.isEmpty()) {
                                LOG.error("[ScrollSealingScreen] No recipient UUID set; aborting seal");
                                return true;
                            }

                            String recipientTextRaw = this.recipientField != null ? this.recipientField.getText() : "";
                            String recipientNameResolved = "";
                            if (recipientTextRaw != null) {
                                String t = recipientTextRaw.trim();
                                String lower = t.toLowerCase(java.util.Locale.ROOT);
                                if (lower.startsWith("dear ") && t.endsWith(",")) {
                                    String mid = t.substring(5, Math.max(5, t.length() - 1)).trim();
                                    recipientNameResolved = mid;
                                } else {
                                    recipientNameResolved = t;
                                }
                            }

                            // Clamp
                            if (recipientText.length() > 32760) recipientText = recipientText.substring(0, 32760);
                            if (messageText.length()   > 32760) messageText   = messageText.substring(0, 32760);
                            if (signatureText.length() > 32760) signatureText = signatureText.substring(0, 32760);
                            if (recipientNameResolved.length() > 250) recipientNameResolved = recipientNameResolved.substring(0, 250);

                            saveEditorStateToMenu();

                            LOG.debug("[ScrollSealingScreen] Sending WaxSealPacket (admire fade after): rec='{}' uuid={} seed={} slices={} style={} sender='{}'",
                                    recipientNameResolved, recipientUUIDStr, seed, slices, style, senderName);

                            net.z2six.featheredfriend.network.FFNetwork.sendWaxSealToServer(
                                    this.sealStampSlotIndex,
                                    recipientNameResolved,
                                    recipientUUIDStr,
                                    recipientText != null ? recipientText : "",
                                    messageText   != null ? messageText   : "",
                                    signatureText != null ? signatureText : "",
                                    seed, slices, style, senderName
                            );

                            // -------------------------
                            // NEW: place the seal for a 1s admiration fade
                            // -------------------------
                            try {
                                // Prefer using the already-built hover pattern; otherwise build from actualStamp.
                                if (this.hoverSigilPattern != null) {
                                    this.placedSigilPattern = this.hoverSigilPattern;
                                } else {
                                    this.placedSigilPattern = buildPatternFromStamp(actualStamp);
                                }
                                this.placedSealShown = (this.placedSigilPattern != null);
                                this.placedFadeTicks = 0;

                                // Deselect cursor + hide picker overlay
                                this.sealStampTargetMode = false;
                                this.sealStampSlotIndex = -1;
                                this.sealStampStackForRender = net.minecraft.world.item.ItemStack.EMPTY;
                                if (this.sealStampOverlay != null) {
                                    this.sealStampOverlay.setActive(false);
                                }
                            } catch (Throwable placeErr) {
                                LOG.error("[ScrollSealingScreen] Failed to start admiration fade", placeErr);
                            }

                            // NOTE: we DO NOT close here; containerTick will close after fade.
                        } catch (Throwable sealErr) {
                            LOG.error("[ScrollSealingScreen] Exception during wax sealing click handling", sealErr);
                        }

                        return true;
                    } else {
                        // Click anywhere else -> deselect
                        LOG.debug("[ScrollSealingScreen] Deselecting seal stamp via LMB outside wax area");
                        this.sealStampTargetMode = false;
                        this.sealStampSlotIndex = -1;
                        this.sealStampStackForRender = net.minecraft.world.item.ItemStack.EMPTY;
                        this.hoverSigilPattern = null;
                        return true;
                    }
                }

                // RMB behaviour while a stamp is selected
                if (button == 1) {
                    LOG.debug("[ScrollSealingScreen] Deselecting seal stamp via RMB");
                    this.sealStampTargetMode = false;
                    this.sealStampSlotIndex = -1;
                    this.sealStampStackForRender = net.minecraft.world.item.ItemStack.EMPTY;
                    this.hoverSigilPattern = null;
                    return true;
                }
            }

            // 4) Signature placeholder -> Sign button (unchanged)
            if (button == 0
                    && this.signatureWidget != null
                    && isUiInteractive()
                    && this.signatureWidget.getText().isEmpty()
                    && isMouseOverSignature(mouseX, mouseY)) {
                onSignButtonClicked();
                return true;
            }

            // 5) Pearl click -> save text state, then open inventory GUI (unchanged)
            if (button == 0 && isMouseOverPearlIcon(mouseX, mouseY) && isPearlClickable()) {
                saveEditorStateToMenu();
                onEnderPearlClicked();
                return true;
            }

            // 6) Let base logic handle text widgets etc.
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

            saveEditorStateToMenu();
            return result;
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] mouseClicked failed", t);
            return false;
        }
    }

    private boolean isMouseOverPearlIcon(double mouseX, double mouseY) {
        int x0 = this.leftPos + PEARL_X;
        int y0 = this.topPos + PEARL_Y;
        int x1 = x0 + PEARL_WIDTH;
        int y1 = y0 + PEARL_HEIGHT;
        return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
    }

    private boolean isMouseOverSignature(double mouseX, double mouseY) {
        if (this.signatureWidget == null) {
            return false;
        }
        int x0 = this.signatureWidget.getX();
        int y0 = this.signatureWidget.getY();
        int x1 = x0 + this.signatureWidget.getWidth();
        int y1 = y0 + this.signatureWidget.getHeight();
        return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
    }

    private void onEnderPearlClicked() {
        try {
            if (!isPearlClickable()) {
                LOG.debug("[ScrollSealingScreen] Ender pearl click ignored; pearlPhase={} uiPhase={}", pearlPhase, uiPhase);
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            EnderPearlInventoryScreen.handlePearlClicked(mc);
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

            if (handled) {
                saveEditorStateToMenu();
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
            // NEW: ignore all keys during admiration fade
            if (placedSealShown && placedFadeTicks >= 0) {
                return true;
            }

            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                if (this.recipientOverlay.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }

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
                saveEditorStateToMenu();
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] keyPressed failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Container slot suppression on this screen
    // ---------------------------------------------------------------------

    @Override
    protected void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        // Intentionally empty: slots are invisible on the scroll-writing screen.
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot != null) {
            LOG.debug("[ScrollSealingScreen] slotClicked ignored: slotId={} type={} button={}", slotId, type, mouseButton);
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
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] onSealClickedPlaceholder failed", t);
        }
    }
}
