// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollViewScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.neoforge.menu.ScrollViewMenu;
import net.z2six.featheredfriend.sigil.SealSigilGenerator;
import net.z2six.featheredfriend.sigil.SealSigilGenerator.SigilPattern;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollViewScreen.java
 *
 * ScrollViewScreen
 *
 * Read-only view for a sealed scroll:
 *
 *  - Starts on the fully-closed scroll frame (last frame of scroll_closing).
 *  - Lets the player zoom in on the sigil using the same small/zoom wax gizmos
 *    as ScrollSealingScreen.
 *  - A left-click inside the active wax area triggers a reverse animation using
 *    scroll_closing (frames 6..0) to simulate opening.
 *  - After the animation finishes, the scroll is shown fully open and we
 *    display the date, recipient, message and signature using Gothic widgets,
 *    populated from the SealedScroll NBT compound of the held item.
 *
 * NOTE:
 *  - This screen is purely client/UI logic for now. It does NOT yet implement:
 *      * Pearl attachments view/claiming.
 *      * Destroying the sealed scroll and replacing it with scroll_opened.
 *
 * Those will be wired via server-side packets + item logic in a later step.
 */
public class ScrollViewScreen extends AbstractContainerScreen<ScrollViewMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Textures & fonts (shared with ScrollSealingScreen)
    // ---------------------------------------------------------------------

    // Fallback single-frame texture (never normally used here)
    private static final ResourceLocation SCROLL_GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scroll_sealing.png");

    // Animated scroll textures (sprite sheets: 1680x208, 7 frames horizontally)
    private static final ResourceLocation SCROLL_CLOSING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scrollscreen/scroll_closing.png");

    // Zoom variant (same layout, different last frame if you ever change it)
    private static final ResourceLocation SCROLL_CLOSING_TEXTURE_ZOOM =
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

    /**
     * View phases:
     *  - CLOSED_IDLE: scroll fully closed, sigil zoom gizmos active.
     *  - OPENING:    reverse playback of scroll_closing (frame 6..0).
     *  - OPEN_IDLE:  scroll fully open, text widgets visible; read-only mode.
     */
    private enum ViewPhase {
        CLOSED_IDLE,
        OPENING,
        OPEN_IDLE
    }

    private ViewPhase viewPhase = ViewPhase.CLOSED_IDLE;
    private int viewPhaseTicks = 0;

    /**
     * How long the reverse "opening" animation lasts (in ticks).
     * 20 ticks = 1 second.
     */
    private static final int OPENING_ANIM_TICKS = 20;

    // ---------------------------------------------------------------------
    // Wax gizmo / zoom configuration (mirrors ScrollSealingScreen)
    // ---------------------------------------------------------------------

    private static final boolean DEBUG_SHOW_WAX_GIZMO = false;

    // Small gizmo: used when NOT zoomed to trigger zoom.
    private static final int WAX_BOX_X = 94;      // relative to GUI origin (leftPos)
    private static final int WAX_BOX_Y = 74;
    private static final int WAX_BOX_WIDTH = 36;
    private static final int WAX_BOX_HEIGHT = 36;
    private static final int WAX_BOX_COLOR = 0x80FF0000; // semi-transparent red
    private static final float WAX_SIGIL_RADIUS_SCALE = 0.85f;

    // Zoom gizmo: used when zoomed in.
    private static final int ZOOM_WAX_BOX_X = 50;
    private static final int ZOOM_WAX_BOX_Y = 30;
    private static final int ZOOM_WAX_BOX_WIDTH = 125;
    private static final int ZOOM_WAX_BOX_HEIGHT = 125;

    // Center offsets within whichever box is active
    private static final int WAX_SIGIL_CENTER_OFFSET_X = 0;
    private static final int WAX_SIGIL_CENTER_OFFSET_Y = 0;

    // How much to zoom the scroll texture (not the sigil) when hovered
    private static final float HOVER_ZOOM_SCALE = 3.5f;

    // Independent control for sigil size in zoom view
    private static final int ZOOM_SIGIL_RADIUS_PIXELS = 42;

    // Tracks whether the zoomed-in closing view is currently active.
    private boolean zoomActive = false;

    // ---------------------------------------------------------------------
    // GUI dimensions & layout (mirrors ScrollSealingScreen)
    // ---------------------------------------------------------------------

    private static final int GUI_WIDTH = SCROLL_FRAME_WIDTH; // 240
    private static final int GUI_HEIGHT = 200;

    // Date field config
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

    // ---------------------------------------------------------------------
    // Widgets & scroll contents
    // ---------------------------------------------------------------------

    private MultiLineScrollTextWidget dateWidget;
    private MultiLineScrollTextWidget recipientWidget;
    private MultiLineScrollTextWidget messageWidget;
    private MultiLineScrollTextWidget signatureWidget;

    // Cached text loaded from the sealed scroll NBT.
    private String dateText = "";
    private String recipientText = "";
    private String messageText = "";
    private String signatureText = "";
    private boolean hasAttachments = false;

    // Sigil visualizer & patterns derived from the sealed scroll.
    private final WaxSealVisualizer waxSealVisualizer = new WaxSealVisualizer();

    /**
     * Small sigil pattern: used for the small gizmo on the closed scroll.
     */
    private SigilPattern smallSigilPattern = null;

    /**
     * Zoom sigil pattern: independently generated at a higher radius
     * for the zoom gizmo, so we aren't just scaling up the small one.
     */
    private SigilPattern zoomSigilPattern = null;

    // Snapshot of the sealed scroll stack when we open this screen.
    private ItemStack sealedScrollStack = ItemStack.EMPTY;

    public ScrollViewScreen(@NotNull ScrollViewMenu menu,
                            @NotNull Inventory playerInventory,
                            @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;

        // We don't render vanilla labels.
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
    }

    // Convenience: strongly typed menu accessor
    private ScrollViewMenu getViewMenu() {
        AbstractContainerMenu m = this.menu;
        if (m instanceof ScrollViewMenu vm) {
            return vm;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Init / NBT loading
    // ---------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();

        try {
            LOG.debug("[ScrollViewScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);
            this.clearWidgets();

            this.viewPhase = ViewPhase.CLOSED_IDLE;
            this.viewPhaseTicks = 0;
            this.zoomActive = false;

            // Reset cached contents
            this.dateText = "";
            this.recipientText = "";
            this.messageText = "";
            this.signatureText = "";
            this.hasAttachments = false;
            this.sealedScrollStack = ItemStack.EMPTY;

            // Reset sigil patterns
            this.smallSigilPattern = null;
            this.zoomSigilPattern = null;

            loadFromHeldSealedScroll();
            initWidgetsFromCache();
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] init failed", t);
        }
    }

    /**
     * Attempts to read SealedScroll data from the player's held item.
     * For now we use the main hand as our source – the item that was
     * right-clicked to open this view.
     */
    private void loadFromHeldSealedScroll() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                LOG.warn("[ScrollViewScreen] loadFromHeldSealedScroll: Minecraft or player is null");
                return;
            }

            ItemStack hand = mc.player.getMainHandItem();
            if (hand == null || hand.isEmpty()) {
                LOG.warn("[ScrollViewScreen] loadFromHeldSealedScroll: main-hand stack is empty");
                return;
            }

            this.sealedScrollStack = hand.copy();
            LOG.debug("[ScrollViewScreen] Using sealed scroll stack: {}", this.sealedScrollStack.getItem());

            CustomData customData = hand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag root = customData.copyTag();
            if (root == null || !root.contains("SealedScroll")) {
                LOG.warn("[ScrollViewScreen] SealedScroll NBT compound missing on held item");
                return;
            }

            CompoundTag seal = root.getCompound("SealedScroll");

            this.dateText = safeTagString(seal, "DateText");
            this.recipientText = safeTagString(seal, "RecipientText");
            this.messageText = safeTagString(seal, "MessageText");
            this.signatureText = safeTagString(seal, "SignatureText");

            // Check for attachments presence
            if (seal.contains("Attachments", ListTag.TAG_LIST)) {
                ListTag attachments = seal.getList("Attachments", CompoundTag.TAG_COMPOUND);
                this.hasAttachments = attachments != null && !attachments.isEmpty();
            } else {
                this.hasAttachments = false;
            }

            // Build sigil patterns from Seed/Slices/Style (small + zoom)
            try {
                long seed = seal.getLong("Seed");
                int slices = seal.getInt("Slices");
                int style = seal.getInt("Style");

                int smallRadius = Math.max(
                        6,
                        (int) (Math.min(WAX_BOX_WIDTH, WAX_BOX_HEIGHT) * 0.5f * WAX_SIGIL_RADIUS_SCALE)
                );
                int zoomRadius = Math.max(6, ZOOM_SIGIL_RADIUS_PIXELS);

                this.smallSigilPattern = SealSigilGenerator.generateFromSeed(seed, smallRadius, slices, style);
                this.zoomSigilPattern = SealSigilGenerator.generateFromSeed(seed, zoomRadius, slices, style);

                LOG.debug(
                        "[ScrollViewScreen] Built sigil patterns from NBT (seed={} slices={} style={} smallRadius={} zoomRadius={})",
                        seed, slices, style, smallRadius, zoomRadius
                );
            } catch (Throwable tSigil) {
                LOG.error("[ScrollViewScreen] Failed to build sigil patterns from SealedScroll NBT", tSigil);
                this.smallSigilPattern = null;
                this.zoomSigilPattern = null;
            }

            LOG.debug("[ScrollViewScreen] Loaded SealedScroll text: date='{}', recipient='{}', msgLen={}, sig='{}', hasAttachments={}",
                    this.dateText,
                    this.recipientText,
                    this.messageText != null ? this.messageText.length() : 0,
                    this.signatureText,
                    this.hasAttachments
            );
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] loadFromHeldSealedScroll failed", t);
        }
    }

    private static String safeTagString(@NotNull CompoundTag tag, @NotNull String key) {
        try {
            if (!tag.contains(key)) {
                return "";
            }
            return tag.getString(key);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] safeTagString failed for key='{}'", key, t);
            return "";
        }
    }

    private void initWidgetsFromCache() {
        try {
            // Date widget (read-only)
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
            this.dateWidget.setText(this.dateText != null ? this.dateText : "");
            this.addRenderableWidget(this.dateWidget);

            // Recipient widget (read-only)
            this.recipientWidget = new MultiLineScrollTextWidget(
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
            this.recipientWidget.setEditable(false);
            this.recipientWidget.setText(this.recipientText != null ? this.recipientText : "");
            this.addRenderableWidget(this.recipientWidget);

            // Message widget (read-only multiline)
            this.messageWidget = new MultiLineScrollTextWidget(
                    this.font,
                    this.leftPos + MESSAGE_X,
                    this.topPos + MESSAGE_Y,
                    MESSAGE_WIDTH,
                    MESSAGE_HEIGHT,
                    MESSAGE_MAX_CHARS,
                    MESSAGE_MAX_LINES,
                    Component.literal(""),
                    GOTHIC_FONT_ID,
                    true
            );
            this.messageWidget.setEditable(false);
            this.messageWidget.setText(this.messageText != null ? this.messageText : "");
            this.addRenderableWidget(this.messageWidget);

            // Signature widget (read-only)
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
            this.signatureWidget.setEditable(false);
            this.signatureWidget.setText(this.signatureText != null ? this.signatureText : "");
            this.addRenderableWidget(this.signatureWidget);

            // Text is initially hidden while scroll is closed.
            setWidgetsVisible(false);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] initWidgetsFromCache failed", t);
        }
    }

    private void setWidgetsVisible(boolean visible) {
        if (this.dateWidget != null) this.dateWidget.visible = visible;
        if (this.recipientWidget != null) this.recipientWidget.visible = visible;
        if (this.messageWidget != null) this.messageWidget.visible = visible;
        if (this.signatureWidget != null) this.signatureWidget.visible = visible;
    }

    // ---------------------------------------------------------------------
    // Gizmo hit-testing
    // ---------------------------------------------------------------------

    private boolean isMouseInWaxAreaSmall(double mouseX, double mouseY) {
        int x0 = this.leftPos + WAX_BOX_X;
        int y0 = this.topPos + WAX_BOX_Y;
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
    // Ticking
    // ---------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        try {
            if (this.dateWidget != null) this.dateWidget.tick();
            if (this.recipientWidget != null) this.recipientWidget.tick();
            if (this.messageWidget != null) this.messageWidget.tick();
            if (this.signatureWidget != null) this.signatureWidget.tick();

            tickViewPhase();
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] containerTick failed", t);
        }
    }

    private void tickViewPhase() {
        try {
            switch (viewPhase) {
                case CLOSED_IDLE -> {
                    // Nothing to do; waiting for click in wax gizmo.
                }
                case OPENING -> {
                    viewPhaseTicks++;

                    if (viewPhaseTicks >= OPENING_ANIM_TICKS) {
                        viewPhaseTicks = OPENING_ANIM_TICKS;
                        viewPhase = ViewPhase.OPEN_IDLE;
                        // Once open, reveal text widgets.
                        setWidgetsVisible(true);
                        LOG.debug("[ScrollViewScreen] Opening animation finished -> OPEN_IDLE");
                    }
                }
                case OPEN_IDLE -> {
                    // Fully open read-only view; no further ticking logic.
                }
            }
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] tickViewPhase failed", t);
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
            // Determine hover over gizmos
            boolean inSmallWax = isMouseInWaxAreaSmall(mouseX, mouseY);
            boolean inZoomWax = isMouseInWaxAreaZoom(mouseX, mouseY);

            // Zoom is only active while scroll is closed.
            boolean canZoom = (this.viewPhase == ViewPhase.CLOSED_IDLE);

            if (!canZoom) {
                if (zoomActive) {
                    LOG.debug("[ScrollViewScreen] renderBg: Disabling zoomActive (viewPhase={})", viewPhase);
                }
                zoomActive = false;
            } else {
                // Update zoom state based on gizmos.
                if (!zoomActive) {
                    // Enter zoom only via small gizmo.
                    if (inSmallWax) {
                        zoomActive = true;
                        LOG.debug("[ScrollViewScreen] renderBg: Zoom entered via SMALL gizmo");
                    }
                } else {
                    // Already zoomed: stay zoomed as long as mouse is in the zoom gizmo.
                    if (!inZoomWax) {
                        zoomActive = false;
                        LOG.debug("[ScrollViewScreen] renderBg: Zoom exited by leaving ZOOM gizmo");
                    }
                }
            }

            boolean shouldZoom = zoomActive && canZoom;

            if (shouldZoom) {
                PoseStack pose = guiGraphics.pose();
                pose.pushPose();
                try {
                    int centerX = this.leftPos + WAX_BOX_X + WAX_BOX_WIDTH / 2;
                    int centerY = this.topPos + WAX_BOX_Y + WAX_BOX_HEIGHT / 2;

                    pose.translate(centerX, centerY, 0.0f);
                    pose.scale(HOVER_ZOOM_SCALE, HOVER_ZOOM_SCALE, 1.0f);
                    pose.translate(-centerX, -centerY, 0.0f);

                    // Zoomed: use zoom texture variant
                    renderAnimatedScroll(guiGraphics, true);
                } finally {
                    pose.popPose();
                }

                // Sigil is drawn outside the scaled pose in the zoom gizmo area.
                renderWaxSeal(guiGraphics, mouseX, mouseY, partialTick, true);
            } else {
                // Normal (unscaled) rendering.
                renderAnimatedScroll(guiGraphics, false);
                renderWaxSeal(guiGraphics, mouseX, mouseY, partialTick, false);
            }

            // Optional debug gizmo outline.
            if (DEBUG_SHOW_WAX_GIZMO && this.viewPhase == ViewPhase.CLOSED_IDLE) {
                final int boxX;
                final int boxY;
                final int boxW;
                final int boxH;

                if (shouldZoom) {
                    boxX = this.leftPos + ZOOM_WAX_BOX_X;
                    boxY = this.topPos + ZOOM_WAX_BOX_Y;
                    boxW = ZOOM_WAX_BOX_WIDTH;
                    boxH = ZOOM_WAX_BOX_HEIGHT;
                } else {
                    boxX = this.leftPos + WAX_BOX_X;
                    boxY = this.topPos + WAX_BOX_Y;
                    boxW = WAX_BOX_WIDTH;
                    boxH = WAX_BOX_HEIGHT;
                }

                int x0 = boxX;
                int y0 = boxY;
                int x1 = boxX + boxW;
                int y1 = boxY + boxH;

                guiGraphics.fill(x0, y0, x1, y0 + 1, WAX_BOX_COLOR);
                guiGraphics.fill(x0, y1 - 1, x1, y1, WAX_BOX_COLOR);
                guiGraphics.fill(x0, y0, x0 + 1, y1, WAX_BOX_COLOR);
                guiGraphics.fill(x1 - 1, y0, x1, y1, WAX_BOX_COLOR);
            }
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] renderBg failed, falling back to simple fill", t);
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
     * Reverse playback of scroll_closing:
     *  - CLOSED_IDLE: always frame last (closed).
     *  - OPENING:     frames 6..0 over OPENING_ANIM_TICKS.
     *  - OPEN_IDLE:   frame 0 (fully open).
     */
    private void renderAnimatedScroll(@NotNull GuiGraphics guiGraphics, boolean zoomed) {
        ResourceLocation texture;
        int frameIndex;

        switch (viewPhase) {
            case CLOSED_IDLE -> {
                texture = zoomed ? SCROLL_CLOSING_TEXTURE_ZOOM : SCROLL_CLOSING_TEXTURE;
                frameIndex = SCROLL_TOTAL_FRAMES - 1; // last frame = fully closed
            }
            case OPENING -> {
                texture = zoomed ? SCROLL_CLOSING_TEXTURE_ZOOM : SCROLL_CLOSING_TEXTURE;

                int totalTicks = Math.max(1, OPENING_ANIM_TICKS);
                int currentTicks = Math.min(viewPhaseTicks, totalTicks);
                float progress = totalTicks == 0 ? 1.0f : (currentTicks / (float) totalTicks);

                int closedIndex = SCROLL_TOTAL_FRAMES - 1;
                frameIndex = closedIndex - (int) (progress * (SCROLL_TOTAL_FRAMES - 1));
                if (frameIndex < 0) frameIndex = 0;
                if (frameIndex >= SCROLL_TOTAL_FRAMES) frameIndex = SCROLL_TOTAL_FRAMES - 1;
            }
            case OPEN_IDLE -> {
                texture = zoomed ? SCROLL_CLOSING_TEXTURE_ZOOM : SCROLL_CLOSING_TEXTURE;
                frameIndex = 0; // first frame = fully open
            }
            default -> {
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
                texture,
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
    // Wax seal rendering
    // ---------------------------------------------------------------------

    private void renderWaxSeal(@NotNull GuiGraphics g,
                               int mouseX,
                               int mouseY,
                               float partialTick,
                               boolean zoomView) {
        try {
            // Only show sigil while scroll is still closed (CLOSED_IDLE).
            if (this.viewPhase != ViewPhase.CLOSED_IDLE) {
                return;
            }

            if (zoomView) {
                if (this.zoomSigilPattern == null) {
                    return;
                }
                renderZoomWaxSeal(g);
            } else {
                if (this.smallSigilPattern == null) {
                    return;
                }
                renderSmallWaxSeal(g);
            }
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] renderWaxSeal failed", t);
        }
    }

    private void renderSmallWaxSeal(@NotNull GuiGraphics g) {
        try {
            if (this.smallSigilPattern == null) {
                return;
            }

            final int smallClipX = this.leftPos + WAX_BOX_X;
            final int smallClipY = this.topPos + WAX_BOX_Y;
            final int smallClipW = WAX_BOX_WIDTH;
            final int smallClipH = WAX_BOX_HEIGHT;

            final int smallCenterX = smallClipX + (smallClipW / 2) + WAX_SIGIL_CENTER_OFFSET_X;
            final int smallCenterY = smallClipY + (smallClipH / 2) + WAX_SIGIL_CENTER_OFFSET_Y;

            final int smallRadiusPx = Math.max(
                    6,
                    (int) (Math.min(smallClipW, smallClipH) * 0.5f * WAX_SIGIL_RADIUS_SCALE)
            );

            waxSealVisualizer.renderShapesOnly(
                    g,
                    smallCenterX,
                    smallCenterY,
                    smallClipX,
                    smallClipY,
                    smallClipW,
                    smallClipH,
                    smallRadiusPx,
                    1.0f,
                    this.smallSigilPattern
            );
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] renderSmallWaxSeal failed", t);
        }
    }

    private void renderZoomWaxSeal(@NotNull GuiGraphics g) {
        try {
            if (this.zoomSigilPattern == null) {
                return;
            }

            final int zoomClipX = this.leftPos + ZOOM_WAX_BOX_X;
            final int zoomClipY = this.topPos + ZOOM_WAX_BOX_Y;
            final int zoomClipW = ZOOM_WAX_BOX_WIDTH;
            final int zoomClipH = ZOOM_WAX_BOX_HEIGHT;

            final int zoomCenterX = zoomClipX + (zoomClipW / 2) + WAX_SIGIL_CENTER_OFFSET_X;
            final int zoomCenterY = zoomClipY + (zoomClipH / 2) + WAX_SIGIL_CENTER_OFFSET_Y;

            final int zoomRadiusPx = Math.max(6, ZOOM_SIGIL_RADIUS_PIXELS);

            waxSealVisualizer.renderShapesOnly(
                    g,
                    zoomCenterX,
                    zoomCenterY,
                    zoomClipX,
                    zoomClipY,
                    zoomClipW,
                    zoomClipH,
                    zoomRadiusPx,
                    1.0f,
                    this.zoomSigilPattern
            );
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] renderZoomWaxSeal failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Foreground rendering
    // ---------------------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] render failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // No default labels.
    }

    // We don't want default slot hover logic interfering.
    @Override
    protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        return false;
    }

    @Override
    protected void renderTooltip(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        this.hoveredSlot = null;
        super.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            // LMB: in CLOSED_IDLE, clicking inside the appropriate gizmo
            // triggers opening (reverse animation).
            if (button == 0 && this.viewPhase == ViewPhase.CLOSED_IDLE) {
                boolean inSmallWax = isMouseInWaxAreaSmall(mouseX, mouseY);
                boolean inZoomWax = isMouseInWaxAreaZoom(mouseX, mouseY);

                boolean shouldOpen = false;

                if (!zoomActive && inSmallWax) {
                    shouldOpen = true;
                } else if (zoomActive && inZoomWax) {
                    shouldOpen = true;
                }

                if (shouldOpen) {
                    LOG.debug("[ScrollViewScreen] Wax area clicked (zoomActive={}) -> starting OPENING animation", zoomActive);
                    this.viewPhase = ViewPhase.OPENING;
                    this.viewPhaseTicks = 0;
                    // Once we start opening, lock zoom off.
                    this.zoomActive = false;
                    return true;
                }
            }

            return super.mouseClicked(mouseX, mouseY, button);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] mouseClicked failed", t);
            return false;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            // For now, treat ESC and the usual inventory keys as "just close screen".
            if (keyCode == GLFW.GLFW_KEY_ESCAPE ||
                    keyCode == GLFW.GLFW_KEY_E ||
                    keyCode == GLFW.GLFW_KEY_R ||
                    keyCode == GLFW.GLFW_KEY_U) {
                LOG.debug("[ScrollViewScreen] keyPressed {} -> closing ScrollViewScreen (no conversion yet)", keyCode);
                this.minecraft.setScreen(null);
                return true;
            }

            // Widgets are non-editable, but let super handle navigation keys safely.
            return super.keyPressed(keyCode, scanCode, modifiers);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] keyPressed failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Container slot suppression (we don't want any visible slots here)
    // ---------------------------------------------------------------------

    @Override
    protected void renderSlot(GuiGraphics guiGraphics, Slot slot) {
        // Intentionally empty: this view is purely visual; no slots shown.
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, net.minecraft.world.inventory.ClickType type) {
        if (slot != null) {
            LOG.debug("[ScrollViewScreen] slotClicked ignored: slotId={} type={} button={}", slotId, type, mouseButton);
        }
    }
}
