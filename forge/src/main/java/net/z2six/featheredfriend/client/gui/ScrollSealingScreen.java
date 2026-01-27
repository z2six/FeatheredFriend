// MainFile: forge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollSealingScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.ClientCalendarEvents;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.client.gui.widget.RecipientOverlay;
import net.z2six.featheredfriend.client.gui.widget.SealStampSelectionOverlay;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import net.z2six.featheredfriend.item.SealStampItem;
import net.z2six.featheredfriend.forge.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.sigil.SealSigilGenerator;
import net.z2six.featheredfriend.sigil.SealSigilGenerator.SigilPattern;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * // forge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollSealingScreen.java
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

    private static final ResourceLocation SCROLL_GUI_TEXTURE =
            ffLoc("textures/gui/scroll_sealing.png");

    private static final ResourceLocation SCROLL_OPENING_TEXTURE =
            ffLoc("textures/gui/scrollscreen/scroll_opening.png");

    private static final ResourceLocation SCROLL_CLOSING_TEXTURE =
            ffLoc("textures/gui/scrollscreen/scroll_closing.png");

    private static final ResourceLocation SCROLL_CLOSING_TEXTURE_ZOOM =
            ffLoc("textures/gui/scrollscreen/scroll_closing.png");

    private static final ResourceLocation PEARL_TEXTURE =
            ffLoc("textures/gui/scrollscreen/pearl.png");

    private static final ResourceLocation GOTHIC_FONT_ID =
            ffLoc("gothic12");

    // Forge 1.20.1: custom payload is stored under the stack tag sub-compound "CustomData"
    private static final String STACK_CUSTOM_DATA_KEY = "CustomData";
    private static final String NBT_SEAL_ROOT = "SealStamp";
    private static final String NBT_OWNER = "Owner";
    private static final String NBT_SEED = "Seed";
    private static final String NBT_SLICES = "Slices";
    private static final String NBT_SHAPESET = "ShapeSet";

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

    // Pearl animation speeds (ticks per frame)
    private static final int PEARL_PHASE_A_TICKS_PER_FRAME = 2;
    private static final int PEARL_PHASE_C_TICKS_PER_FRAME = 2;

    // Pearl placement / size (relative to GUI origin)
    private static final int PEARL_X = SCROLL_FRAME_WIDTH - PEARL_FRAME_WIDTH - 25;
    private static final int PEARL_Y = 10;
    private static final int PEARL_WIDTH = PEARL_FRAME_WIDTH;
    private static final int PEARL_HEIGHT = PEARL_FRAME_HEIGHT;

    private enum PearlPhase {
        HIDDEN,
        INSTANTIATING,
        IDLE,
        DISAPPEARING,
        GONE
    }

    private PearlPhase pearlPhase = PearlPhase.HIDDEN;
    private int pearlPhaseTicks = 0;

    // ---------------------------------------------------------------------
    // Timing constants (all in ticks; 20 ticks = 1 second)
    // ---------------------------------------------------------------------

    private static final int INTRO_DELAY_TICKS = 10;
    private static final int INTRO_FADE_TICKS = 30;
    private static final int OUTRO_FADE_TICKS = 30;
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

    // Date field config
    private static final int DATE_X = 30;
    private static final int DATE_Y = 18;
    private static final int DATE_WIDTH = 200;
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
    private static final int MESSAGE_WIDTH = 150;
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
    private final WaxSealVisualizer waxSealVisualizer =
            new WaxSealVisualizer();

    // Small (in-place) sigil preview + final pattern
    private SigilPattern hoverSigilPattern = null;      // small preview while hovering in the small gizmo
    private SigilPattern placedSigilPattern = null;     // small final seal in the small gizmo

    // Separate zoomed sigil pattern for the zoom gizmo
    private SigilPattern placedSigilPatternZoom = null; // zoom-radius final seal

    // Gentle 1s fade after placing the seal
    private static final int PLACED_FADE_TICKS_TOTAL = 20;
    private int placedFadeTicks = -1;        // -1 = not active
    private boolean placedSealShown = false; // true once we've "placed" the seal

    // ---------------------------------------------------------------------
    // Wax gizmo areas
    // ---------------------------------------------------------------------

    // Whether to render the red debug gizmo outlines/crosshairs.
    // Set to true if you ever want to visualize the wax areas again.
    private static final boolean DEBUG_SHOW_WAX_GIZMO = false;

    // Small gizmo: used when NOT zoomed (current logic)
    private static final int WAX_BOX_X = 94;      // relative to GUI origin (leftPos)
    private static final int WAX_BOX_Y = 74;
    private static final int WAX_BOX_WIDTH = 36;
    private static final int WAX_BOX_HEIGHT = 36;
    private static final int WAX_BOX_COLOR = 0x80FF0000; // semi-transparent red
    private static final float WAX_SIGIL_RADIUS_SCALE = 0.85f;

    // Tracks whether the zoomed-in closing view is currently active.
    private boolean zoomActive = false;

    // Zoom gizmo: used when zoomed in (independent tunables)
    private static final int ZOOM_WAX_BOX_X = 50;       // tweak to taste
    private static final int ZOOM_WAX_BOX_Y = 30;
    private static final int ZOOM_WAX_BOX_WIDTH = 125;
    private static final int ZOOM_WAX_BOX_HEIGHT = 125;

    // Center offsets within whichever box is active
    private static final int WAX_SIGIL_CENTER_OFFSET_X = 0;
    private static final int WAX_SIGIL_CENTER_OFFSET_Y = 0;

    // How much to zoom the scroll texture (not the sigil)
    private static final float HOVER_ZOOM_SCALE = 3.5f;

    // Independent control for sigil size in zoom view
    private static final int ZOOM_SIGIL_RADIUS_PIXELS = 42; // tweak this freely

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

    public ScrollSealingScreen(ScrollSealingMenu menu,
                               Inventory playerInventory,
                               Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
    }

    private ScrollSealingMenu getScrollMenu() {
        return (ScrollSealingMenu) this.menu;
    }

    @Override
    protected void init() {
        super.init();

        LOG.debug("[ScrollSealingScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);
        this.clearWidgets();

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

        // Initially hidden; fade in later
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
                    public void onStampSelected(int slotIndex, ItemStack stack) {
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

            m.setClientSkipIntroAnimation(false);

            LOG.debug("[ScrollSealingScreen] init: skipping intro animation (return from pearl inventory)");
        } else {
            setWidgetsAlpha(0.0f);
            setWidgetsInteractive(false);

            LOG.debug("[ScrollSealingScreen] init complete: scrollAnimPhase={}, uiPhase={}, pearlPhase={}",
                    scrollAnimPhase, uiPhase, pearlPhase);
        }
    }

    // ---------------------------------------------------------------------
    // Wax seal rendering (small vs zoom gizmos)
    // ---------------------------------------------------------------------

    // Renders the sigil in the ZOOM gizmo, using the independent zoom radius.
// Call this ONLY in your zoomed branch, e.g. when you are already using
// scroll_closing_zoom.png and hovering inside the zoom gizmo.
    private void renderZoomWaxSeal(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        try {
            // Only during sealed phase; don't interfere during intro/outro.
            if (this.uiPhase != UiPhase.SEALED) {
                return;
            }

            // When zoomed, the active area for placing/previewing the sigil
            // is the ZOOM gizmo (not the small one).
            boolean inZoomArea = isMouseInWaxAreaZoom(mouseX, mouseY);

            // Center of the zoom gizmo where the wax + sigil should sit.
            final int zoomClipX = this.leftPos + ZOOM_WAX_BOX_X;
            final int zoomClipY = this.topPos + ZOOM_WAX_BOX_Y;
            final int zoomClipW = ZOOM_WAX_BOX_WIDTH;
            final int zoomClipH = ZOOM_WAX_BOX_HEIGHT;

            final int zoomCenterX = zoomClipX + (zoomClipW / 2) + WAX_SIGIL_CENTER_OFFSET_X;
            final int zoomCenterY = zoomClipY + (zoomClipH / 2) + WAX_SIGIL_CENTER_OFFSET_Y;

            // Decide which pattern to show in the zoomed view:
            //  - placed seal (if we've already stamped)
            //  - OR a zoom preview built from the currently selected stamp
            SigilPattern patternForZoom = null;

            if (placedSealShown && placedSigilPatternZoom != null) {
                // When already stamped, show the dedicated zoom-radius sigil pattern.
                patternForZoom = placedSigilPatternZoom;
            } else if (this.sealStampTargetMode
                    && this.sealStampSlotIndex >= 0
                    && this.sealStampStackForRender != null
                    && !this.sealStampStackForRender.isEmpty()
                    && inZoomArea) {

                // Fresh zoom pattern with the independent zoom radius.
                // NOTE: this is only used for the zoom preview; the small gizmo
                // uses its own smaller pattern so it isn't just a cropped center.
                SigilPattern zoomPattern = buildZoomPatternFromStamp(this.sealStampStackForRender);
                if (zoomPattern != null) {
                    patternForZoom = zoomPattern;
                }
            }

            if (patternForZoom == null) {
                return;
            }

            // Radius is baked into the pattern via buildZoomPatternFromStamp,
            // so here we just call the visualizer with our zoom clip area.
            try {
                waxSealVisualizer.renderShapesOnly(
                        g,
                        zoomCenterX,
                        zoomCenterY,
                        zoomClipX,
                        zoomClipY,
                        zoomClipW,
                        zoomClipH,
                        ZOOM_SIGIL_RADIUS_PIXELS, // still passed here for clipping sanity
                        1.0f,                     // unused by renderShapesOnly
                        patternForZoom
                );
            } catch (Throwable drawErr) {
                LOG.error("[ScrollSealingScreen] renderZoomWaxSeal: shapes-only zoom impression failed", drawErr);
            }

        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] renderZoomWaxSeal failed", t);
        }
    }

    // Unified entry point used by renderBg:
    //  - zoomView = false -> render small sigil in the small gizmo
    //  - zoomView = true  -> render large sigil in the zoom gizmo
    private void renderWaxSeal(GuiGraphics g,
                               int mouseX,
                               int mouseY,
                               float partialTick,
                               boolean zoomView) {
        try {
            if (zoomView) {
                renderZoomWaxSeal(g, mouseX, mouseY, partialTick);
            } else {
                renderSmallWaxSeal(g, mouseX, mouseY, partialTick);
            }
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] renderWaxSeal (wrapper) failed", t);
        }
    }

    // Renders the small in-place sigil inside the small wax gizmo box.
// This is used when NOT zoomed (zoomView == false).
    private void renderSmallWaxSeal(GuiGraphics g,
                                    int mouseX,
                                    int mouseY,
                                    float partialTick) {
        try {
            // Only active while we're in the sealing phase (after text faded out).
            if (this.uiPhase != UiPhase.SEALED) {
                return;
            }

            final boolean inWaxArea = isMouseInWaxAreaSmall(mouseX, mouseY);

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

            if (placedSealShown && placedSigilPattern != null) {
                // Once we've placed the seal, always show that impression here.
                patternForSmall = placedSigilPattern;
            } else {
                // Live preview while targeting inside small wax area
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

            if (patternForSmall == null) {
                return;
            }

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
            } catch (Throwable drawErr) {
                LOG.error("[ScrollSealingScreen] renderSmallWaxSeal: shapes-only impression failed", drawErr);
            }
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] renderSmallWaxSeal failed", t);
        }
    }

    // Builds a sigil pattern for the SMALL gizmo, radius derived from the
    // WAX_BOX size + WAX_SIGIL_RADIUS_SCALE. This keeps the old tiny
    // impression behaviour.
    private SigilPattern buildPatternFromStamp(ItemStack stampStack) {
        try {
            if (stampStack == null || stampStack.isEmpty() || !(stampStack.getItem() instanceof SealStampItem)) {
                LOG.debug("[ScrollSealingScreen] buildPatternFromStamp: not a SealStampItem");
                return null;
            }

            CompoundTag seal = getSealStampTag(stampStack);
            if (seal == null) {
                LOG.debug("[ScrollSealingScreen] buildPatternFromStamp: missing SealStamp under CustomData");
                return null;
            }

            long seed = seal.getLong(NBT_SEED);
            int slices = seal.getInt(NBT_SLICES);
            int style = seal.getInt(NBT_SHAPESET);

            final int radiusPx = Math.max(
                    6,
                    (int) (Math.min(WAX_BOX_WIDTH, WAX_BOX_HEIGHT) * 0.5f * WAX_SIGIL_RADIUS_SCALE)
            );

            SigilPattern pattern = SealSigilGenerator.generateFromSeed(seed, radiusPx, slices, style);

            LOG.debug(
                    "[ScrollSealingScreen] buildPatternFromStamp -> pattern ok (seed={} slices={} style={} radius={})",
                    seed, slices, style, radiusPx
            );

            return pattern;
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] buildPatternFromStamp failed", t);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Editor state save/restore
    // ---------------------------------------------------------------------

    private void saveEditorStateToMenu() {
        try {
            ScrollSealingMenu m = getScrollMenu();

            m.setClientDateText(this.dateWidget != null ? this.dateWidget.getText() : "");
            m.setClientRecipientText(this.recipientField != null ? this.recipientField.getText() : "");
            m.setClientMessageText(this.messageWidget != null ? this.messageWidget.getText() : "");
            m.setClientSignatureText(this.signatureWidget != null ? this.signatureWidget.getText() : "");

            // NEW: also persist the selected recipient UUID so it survives
            // the detour through EnderPearlInventoryScreen.
            m.setClientRecipientUUID(
                    this.selectedRecipientUuid != null
                            ? this.selectedRecipientUuid.toString()
                            : ""
            );

            LOG.debug("[ScrollSealingScreen] Editor state saved to menu (including recipient UUID)");
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

            // NEW: restore the previously selected recipient UUID, if any.
            // This is what fixes the "UUID missing after coming back
            // from pearl inventory" problem.
            this.selectedRecipientUuid = null;
            String storedUuid = m.getClientRecipientUUID();
            if (storedUuid != null && !storedUuid.isEmpty()) {
                try {
                    this.selectedRecipientUuid = UUID.fromString(storedUuid);
                    LOG.debug("[ScrollSealingScreen] Restored recipient UUID from menu: {}", storedUuid);
                } catch (IllegalArgumentException uuidErr) {
                    LOG.error("[ScrollSealingScreen] Invalid stored recipient UUID '{}'", storedUuid, uuidErr);
                    this.selectedRecipientUuid = null;
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

            LOG.debug("[ScrollSealingScreen] Editor state restored from menu (including recipient UUID)");
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] restoreEditorStateFromMenu failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Recipient overlay helper
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
    // Pattern helpers
    // ---------------------------------------------------------------------

    // Builds a sigil pattern specifically for the ZOOM view, using an
    // independent radius knob: ZOOM_SIGIL_RADIUS_PIXELS.
    private SigilPattern buildZoomPatternFromStamp(ItemStack stampStack) {
        try {
            if (stampStack == null || stampStack.isEmpty() || !(stampStack.getItem() instanceof SealStampItem)) {
                LOG.debug("[ScrollSealingScreen] buildZoomPatternFromStamp: not a SealStampItem");
                return null;
            }

            CompoundTag seal = getSealStampTag(stampStack);
            if (seal == null) {
                LOG.debug("[ScrollSealingScreen] buildZoomPatternFromStamp: missing SealStamp under CustomData");
                return null;
            }

            long seed = seal.getLong(NBT_SEED);
            int slices = seal.getInt(NBT_SLICES);
            int style = seal.getInt(NBT_SHAPESET);

            int radiusPx = Math.max(6, ZOOM_SIGIL_RADIUS_PIXELS);

            SigilPattern pattern = SealSigilGenerator.generateFromSeed(seed, radiusPx, slices, style);

            LOG.debug(
                    "[ScrollSealingScreen] buildZoomPatternFromStamp -> pattern ok (seed={} slices={} style={} radius={})",
                    seed, slices, style, radiusPx
            );

            return pattern;
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] buildZoomPatternFromStamp failed", t);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Gizmo hit-testing
    // ---------------------------------------------------------------------

    private boolean isMouseInWaxAreaSmall(double mouseX, double mouseY) {
        int x0 = this.leftPos + WAX_BOX_X;
        int y0 = this.leftPos + 0; // dummy to remind ourselves; overridden below
        y0 = this.topPos + WAX_BOX_Y;
        int x1 = x0 + WAX_BOX_WIDTH;
        int y1 = y0 + WAX_BOX_HEIGHT;
        return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
    }

    private boolean isMouseInWaxAreaZoom(double mouseX, double mouseY) {
        int x0 = this.leftPos + ZOOM_WAX_BOX_X;
        int y0 = this.topPos + ZOOM_WAX_BOX_Y;
        int x1 = x0 + ZOOM_WAX_BOX_WIDTH;
        int y1 = y0 + ZOOM_WAX_BOX_HEIGHT;
        return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
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
                LOG.debug("[ScrollSealingScreen] onSignButtonClicked: No recipient UUID set, aborting sign");
                return;
            }

            String recipientText = recipientField != null ? recipientField.getText() : null;
            if (recipientText == null || recipientText.trim().isEmpty()) {
                LOG.debug("[ScrollSealingScreen] onSignButtonClicked: Recipient field is empty, aborting sign");
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

            LOG.debug("[ScrollSealingScreen] Scroll signed by {} ({}) on {}",
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

                    if (!sealStampSelectionStarted) {
                        sealStampSelectionStarted = true;

                        if (this.sealStampOverlay != null) {
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
    protected void renderBg(GuiGraphics guiGraphics,
                            float partialTick,
                            int mouseX,
                            int mouseY) {
        try {
            // Hit-tests for both gizmos
            boolean inSmallWax = isMouseInWaxAreaSmall(mouseX, mouseY);
            boolean inZoomWax = isMouseInWaxAreaZoom(mouseX, mouseY);

            // Are we allowed to zoom at all right now?
            boolean canZoomPhase =
                    (this.uiPhase == UiPhase.SEALED)
                            && !(placedSealShown && placedFadeTicks >= 0); // don't zoom during admiration fade

            if (!canZoomPhase) {
                // If we're not in the sealing phase or we’re admiring the seal,
                // force zoom off.
                if (zoomActive) {
                    LOG.debug("[ScrollSealingScreen] renderBg: Disabling zoomActive (phase not eligible)");
                }
                zoomActive = false;
            } else {
                // We *are* in SEALED, not admiring: update zoomActive based on gizmos.

                if (!zoomActive) {
                    // Not yet zoomed: only the SMALL gizmo can trigger zoom.
                    if (inSmallWax) {
                        zoomActive = true;
                        LOG.debug("[ScrollSealingScreen] renderBg: Zoom entered via SMALL gizmo");
                    }
                } else {
                    // Already zoomed: stay zoomed as long as the mouse is in the ZOOM gizmo.
                    if (!inZoomWax) {
                        zoomActive = false;
                        LOG.debug("[ScrollSealingScreen] renderBg: Zoom exited by leaving ZOOM gizmo");
                    }
                }
            }

            // Now we derive shouldZoom from the stateful flag.
            boolean shouldZoom = zoomActive;

            if (shouldZoom) {
                PoseStack pose = guiGraphics.pose();
                pose.pushPose();
                try {
                    // Zoom the scroll texture around the *small* wax center (entry point)
                    int centerX = this.leftPos + WAX_BOX_X + WAX_BOX_WIDTH / 2;
                    int centerY = this.topPos + WAX_BOX_Y + WAX_BOX_HEIGHT / 2;

                    pose.translate(centerX, centerY, 0.0f);
                    pose.scale(HOVER_ZOOM_SCALE, HOVER_ZOOM_SCALE, 1.0f);
                    pose.translate(-centerX, -centerY, 0.0f);

                    // Zoomed: use the zoom closing texture
                    renderAnimatedScroll(guiGraphics, true);
                    renderPearl(guiGraphics, mouseX, mouseY);
                } finally {
                    pose.popPose();
                }

                // Sigil is drawn *outside* the scaled pose, in the zoom gizmo area
                renderWaxSeal(guiGraphics, mouseX, mouseY, partialTick, true);
            } else {
                // Normal, unscaled rendering
                renderAnimatedScroll(guiGraphics, false);
                renderWaxSeal(guiGraphics, mouseX, mouseY, partialTick, false);
                renderPearl(guiGraphics, mouseX, mouseY);
            }

            // Draw gizmo overlay:
            //  - small gizmo when NOT zoomed
            //  - zoom gizmo when zoomed
            if (this.uiPhase == UiPhase.SEALED && DEBUG_SHOW_WAX_GIZMO) {
                final int boxX;
                final int boxY;
                final int boxW;
                final int boxH;

                if (shouldZoom) {
                    // Zoomed: show the big gizmo
                    boxX = this.leftPos + ZOOM_WAX_BOX_X;
                    boxY = this.topPos + ZOOM_WAX_BOX_Y;
                    boxW = ZOOM_WAX_BOX_WIDTH;
                    boxH = ZOOM_WAX_BOX_HEIGHT;
                } else {
                    // Not zoomed: show the small gizmo
                    boxX = this.leftPos + WAX_BOX_X;
                    boxY = this.topPos + WAX_BOX_Y;
                    boxW = WAX_BOX_WIDTH;
                    boxH = WAX_BOX_HEIGHT;
                }

                int x0 = boxX;
                int y0 = boxY;
                int x1 = boxX + boxW;
                int y1 = boxY + boxH;

                // Outline
                guiGraphics.fill(x0, y0, x1, y0 + 1, WAX_BOX_COLOR);
                guiGraphics.fill(x0, y1 - 1, x1, y1, WAX_BOX_COLOR);
                guiGraphics.fill(x0, y0, x0 + 1, y1, WAX_BOX_COLOR);
                guiGraphics.fill(x1 - 1, y0, x1, y1, WAX_BOX_COLOR);

                // Crosshair for centering
                int midX = (x0 + x1) / 2;
                int midY = (y0 + y1) / 2;
                guiGraphics.fill(midX - 1, y0 + 2, midX + 1, y1 - 2, 0x40FF0000);
                guiGraphics.fill(x0 + 2, midY - 1, x1 - 2, midY + 1, 0x40FF0000);
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

    // Convenience overload: legacy usage
    private void renderAnimatedScroll(GuiGraphics guiGraphics) {
        renderAnimatedScroll(guiGraphics, false);
    }

    // zoomed=false -> normal closing texture
    // zoomed=true  -> closing_zoom texture for CLOSING/CLOSED_STILL
    private void renderAnimatedScroll(GuiGraphics guiGraphics, boolean zoomed) {
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
                textureToUse = zoomed ? SCROLL_CLOSING_TEXTURE_ZOOM : SCROLL_CLOSING_TEXTURE;

                int totalTicks = Math.max(1, OUTRO_DELAY_TICKS);
                int currentTicks = Math.min(uiPhaseTicks, totalTicks);
                float progress = totalTicks == 0 ? 1.0f : (currentTicks / (float) totalTicks);

                frameIndex = (int) (progress * (SCROLL_TOTAL_FRAMES - 1));
                if (frameIndex < 0) frameIndex = 0;
                if (frameIndex >= SCROLL_TOTAL_FRAMES) frameIndex = SCROLL_TOTAL_FRAMES - 1;
                break;
            }
            case CLOSED_STILL: {
                textureToUse = zoomed ? SCROLL_CLOSING_TEXTURE_ZOOM : SCROLL_CLOSING_TEXTURE;
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
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            // Background (dirt/dim)
            this.renderBackground(guiGraphics);

            // Your scroll background, wax, pearl, etc.
            this.renderBg(guiGraphics, partialTick, mouseX, mouseY);

            // Render widgets added via addRenderableWidget()
            // (this avoids AbstractContainerScreen slot rendering entirely)
            for (var r : this.renderables) {
                r.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            // Your extra overlay renders (these are NOT necessarily in renderables)
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

            // Seal-stamp cursor item
            if (sealStampTargetMode && sealStampSlotIndex >= 0
                    && sealStampStackForRender != null && !sealStampStackForRender.isEmpty()) {
                try {
                    int iconX = mouseX - 8;
                    int iconY = mouseY - 8;
                    guiGraphics.renderItem(sealStampStackForRender, iconX, iconY);
                    guiGraphics.renderItemDecorations(this.font, sealStampStackForRender, iconX, iconY);
                } catch (Throwable t) {
                    LOG.error("[ScrollSealingScreen] Failed to render seal stamp cursor", t);
                }
            }

            // Admiration fade overlay (unchanged)
            if (placedSealShown && placedFadeTicks >= 0) {
                float t = Math.min(1.0f, placedFadeTicks / (float) PLACED_FADE_TICKS_TOTAL);
                int alpha = (int) (t * 255.0f);
                if (alpha < 0) alpha = 0;
                if (alpha > 255) alpha = 255;
                int color = (alpha << 24);
                guiGraphics.fill(0, 0, this.width, this.height, color);
            }

            // Tooltips: we intentionally do NOT call AbstractContainerScreen tooltips,
            // because that involves hoveredSlot logic. Add custom tooltips here if needed.

        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] render failed", t);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // No default labels.
    }

    // Intentionally NOT annotated with @Override to avoid signature mismatch across mappings/patches.
    protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        return false;
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        this.hoveredSlot = null;
        super.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (placedSealShown && placedFadeTicks >= 0) {
                return true;
            }

            if (this.recipientOverlay != null && this.recipientOverlay.isActive()) {
                boolean consumed = this.recipientOverlay.mouseClicked(mouseX, mouseY, button);
                if (consumed) {
                    return true;
                }
            }

            if (this.sealStampOverlay != null
                    && this.sealStampOverlay.isActive()
                    && this.uiPhase == UiPhase.SEALED) {

                boolean consumedStamp = this.sealStampOverlay.mouseClicked(mouseX, mouseY, button);
                if (consumedStamp) {
                    if (this.sealStampTargetMode && this.sealStampStackForRender != null && !this.sealStampStackForRender.isEmpty()) {
                        this.hoverSigilPattern = null;
                    }
                    return true;
                }
            }

            // SEALED phase stamping logic: only the ZOOM gizmo can place the seal
            if (this.uiPhase == UiPhase.SEALED
                    && this.sealStampTargetMode
                    && this.sealStampSlotIndex >= 0
                    && this.sealStampStackForRender != null
                    && !this.sealStampStackForRender.isEmpty()) {

                boolean inZoomWaxArea = isMouseInWaxAreaZoom(mouseX, mouseY);

                if (button == 0) {
                    if (inZoomWaxArea) {
                        LOG.debug(
                                "[ScrollSealingScreen] Zoom wax area clicked with selected stamp: slotIndex={} stack={}",
                                this.sealStampSlotIndex,
                                this.sealStampStackForRender
                        );

                        try {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc == null || mc.player == null) {
                                LOG.error("[ScrollSealingScreen] Cannot seal: Minecraft or player is null");
                                return true;
                            }

                            ItemStack actualStamp = ItemStack.EMPTY;
                            if (this.sealStampSlotIndex >= 0 && this.sealStampSlotIndex <= 35) {
                                if (this.sealStampSlotIndex < mc.player.getInventory().items.size()) {
                                    actualStamp = mc.player.getInventory().items.get(this.sealStampSlotIndex);
                                }
                            } else if (this.sealStampSlotIndex == 37) {
                                actualStamp = mc.player.getOffhandItem();
                            } else {
                                LOG.warn("[ScrollSealingScreen] Unexpected stamp slot index {} (expected 0..35 or 37)", this.sealStampSlotIndex);
                            }

                            if (actualStamp.isEmpty() || !(actualStamp.getItem() instanceof SealStampItem)) {
                                LOG.error("[ScrollSealingScreen] Selected stack is not a valid SealStampItem; aborting seal");
                                return true;
                            }

                            if (!SealStampItem.isEtched(actualStamp)) {
                                LOG.error("[ScrollSealingScreen] Selected SealStampItem is not etched; aborting seal");
                                return true;
                            }

                            String senderName = "";
                            long seed = 0L;
                            int slices = 0;
                            int style = 0;

                            try {
                                CompoundTag seal = getSealStampTag(actualStamp);
                                if (seal == null) {
                                    LOG.error("[ScrollSealingScreen] SealStamp NBT missing under CustomData on etched stamp; aborting");
                                    return true;
                                }

                                senderName = seal.getString(NBT_OWNER);
                                seed = seal.getLong(NBT_SEED);
                                slices = seal.getInt(NBT_SLICES);
                                style = seal.getInt(NBT_SHAPESET);
                            } catch (Throwable te) {
                                LOG.error("[ScrollSealingScreen] Failed extracting SealStamp CustomData", te);
                                return true;
                            }

                            // Gather scroll text fields
                            String dateText = "";
                            if (this.dateWidget != null) {
                                try {
                                    dateText = this.dateWidget.getText();
                                } catch (Throwable tDate) {
                                    LOG.error("[ScrollSealingScreen] Failed to read date widget text, falling back to computeCurrentDateString()", tDate);
                                    dateText = "";
                                }
                            }
                            if (dateText == null || dateText.isEmpty()) {
                                dateText = computeCurrentDateString();
                            }

                            String recipientText = this.recipientField != null ? this.recipientField.getText() : "";
                            String messageText = this.messageWidget != null ? this.messageWidget.getText() : "";
                            String signatureText = this.signatureWidget != null ? this.signatureWidget.getText() : "";

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

                            if (recipientText.length() > 32760) recipientText = recipientText.substring(0, 32760);
                            if (messageText.length() > 32760) messageText = messageText.substring(0, 32760);
                            if (signatureText.length() > 32760) signatureText = signatureText.substring(0, 32760);
                            if (recipientNameResolved.length() > 250) {
                                recipientNameResolved = recipientNameResolved.substring(0, 250);
                            }

                            saveEditorStateToMenu();

                            LOG.debug(
                                    "[ScrollSealingScreen] Sending WaxSealPacket (admire fade after): date='{}' rec='{}' uuid={} seed={} slices={} style={} sender='{}'",
                                    dateText,
                                    recipientNameResolved,
                                    recipientUUIDStr,
                                    seed,
                                    slices,
                                    style,
                                    senderName
                            );

                            net.z2six.featheredfriend.network.FFNetwork.sendWaxSealToServer(
                                    this.sealStampSlotIndex,
                                    dateText != null ? dateText : "",
                                    recipientNameResolved,
                                    recipientUUIDStr,
                                    recipientText != null ? recipientText : "",
                                    messageText != null ? messageText : "",
                                    signatureText != null ? signatureText : "",
                                    seed,
                                    slices,
                                    style,
                                    senderName
                            );

                            try {
                                // Build separate small + zoom patterns so the small gizmo
                                // uses a genuinely smaller sigil instead of a cropped zoom one.
                                this.placedSigilPattern = buildPatternFromStamp(actualStamp);
                                this.placedSigilPatternZoom = buildZoomPatternFromStamp(actualStamp);
                                this.hoverSigilPattern = null; // discard any preview pattern

                                this.placedSealShown = (this.placedSigilPattern != null);
                                this.placedFadeTicks = 0;

                                this.sealStampTargetMode = false;
                                this.sealStampSlotIndex = -1;
                                this.sealStampStackForRender = ItemStack.EMPTY;
                                if (this.sealStampOverlay != null) {
                                    this.sealStampOverlay.setActive(false);
                                }
                            } catch (Throwable placeErr) {
                                LOG.error("[ScrollSealingScreen] Failed to start admiration fade", placeErr);
                            }

                        } catch (Throwable sealErr) {
                            LOG.error("[ScrollSealingScreen] Exception during wax sealing click handling", sealErr);
                        }

                        return true;
                    } else {
                        LOG.debug("[ScrollSealingScreen] Deselecting seal stamp via LMB outside zoom wax area");
                        this.sealStampTargetMode = false;
                        this.sealStampSlotIndex = -1;
                        this.sealStampStackForRender = ItemStack.EMPTY;
                        this.hoverSigilPattern = null;
                        return true;
                    }
                }

                if (button == 1) {
                    LOG.debug("[ScrollSealingScreen] Deselecting seal stamp via RMB");
                    this.sealStampTargetMode = false;
                    this.sealStampSlotIndex = -1;
                    this.sealStampStackForRender = ItemStack.EMPTY;
                    this.hoverSigilPattern = null;
                    return true;
                }
            }

            if (button == 0
                    && this.signatureWidget != null
                    && isUiInteractive()
                    && this.signatureWidget.getText().isEmpty()
                    && isMouseOverSignature(mouseX, mouseY)) {
                onSignButtonClicked();
                return true;
            }

            if (button == 0 && isMouseOverPearlIcon(mouseX, mouseY) && isPearlClickable()) {
                saveEditorStateToMenu();
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
    // Container slot suppression
    // ---------------------------------------------------------------------

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot != null) {
            LOG.debug("[ScrollSealingScreen] slotClicked ignored: slotId={} type={} button={}", slotId, type, mouseButton);
        }
    }

    // ---------------------------------------------------------------------
    // Placeholder sealing hook
    // ---------------------------------------------------------------------

    @SuppressWarnings("unused")
    private void onSealClickedPlaceholder() {
        try {
            LOG.debug("[ScrollSealingScreen] Seal placeholder clicked. RecipientUUID={} SignerUUID={}",
                    selectedRecipientUuid, signerUuid);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] onSealClickedPlaceholder failed", t);
        }
    }

    private static ResourceLocation ffLoc(String path) {
        return new ResourceLocation(Constants.MOD_ID, path);
    }

    // Forge 1.20.1 helpers
    @Nullable
    private static CompoundTag getSealStampTag(ItemStack stampStack) {
        try {
            if (stampStack == null || stampStack.isEmpty()) return null;

            CompoundTag tag = stampStack.getTag();
            if (tag == null) return null;

            if (!tag.contains(STACK_CUSTOM_DATA_KEY, Tag.TAG_COMPOUND)) {
                return null;
            }

            CompoundTag customData = tag.getCompound(STACK_CUSTOM_DATA_KEY);
            if (customData == null || customData.isEmpty()) return null;

            if (!customData.contains(NBT_SEAL_ROOT, Tag.TAG_COMPOUND)) {
                return null;
            }

            CompoundTag seal = customData.getCompound(NBT_SEAL_ROOT);
            return (seal == null || seal.isEmpty()) ? null : seal;
        } catch (Throwable t) {
            LOG.error("[ScrollSealingScreen] getSealStampTag failed", t);
            return null;
        }
    }


}
