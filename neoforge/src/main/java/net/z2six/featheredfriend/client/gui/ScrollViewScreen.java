// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollViewScreen.java
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

import java.lang.reflect.Method;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollViewScreen.java
 *
 * ScrollViewScreen
 *
 * Read-only view for a sealed scroll.
 *
 * IMPORTANT (fix):
 *  - Closing via minecraft.setScreen(null) alone can leave the server-side menu open
 *    until the next inventory interaction.
 *  - We MUST close the container properly: minecraft.player.closeContainer().
 *
 * Feature addition (batch 1/2):
 *  - When the user "breaks the seal" (clicks wax area to open), the client will attempt
 *    to notify the server to convert the exact scroll_sealed stack into scroll_opened
 *    and mark the session as seal-broken so attachments are only delivered after opening.
 *
 * Note:
 *  - In this first batch, the call is done via reflection to keep the file compiling
 *    before the new packet + FFNetwork method exist. Next batch will provide the method.
 */
public class ScrollViewScreen extends AbstractContainerScreen<ScrollViewMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Textures & fonts (shared with ScrollSealingScreen)
    // ---------------------------------------------------------------------

    private static final ResourceLocation SCROLL_GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scroll_sealing.png");

    private static final ResourceLocation SCROLL_CLOSING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scrollscreen/scroll_closing.png");

    private static final ResourceLocation SCROLL_CLOSING_TEXTURE_ZOOM =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scrollscreen/scroll_closing.png");

    private static final ResourceLocation GOTHIC_FONT_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gothic12");

    // ---------------------------------------------------------------------
    // Scroll animation configuration
    // ---------------------------------------------------------------------

    private static final int SCROLL_FRAME_WIDTH = 240;
    private static final int SCROLL_FRAME_HEIGHT = 208;
    private static final int SCROLL_TOTAL_FRAMES = 7;

    private enum ViewPhase {
        CLOSED_IDLE,
        OPENING,
        OPEN_IDLE
    }

    private ViewPhase viewPhase = ViewPhase.CLOSED_IDLE;
    private int viewPhaseTicks = 0;

    private static final int OPENING_ANIM_TICKS = 20;

    // ---------------------------------------------------------------------
    // Wax gizmo / zoom configuration (mirrors ScrollSealingScreen)
    // ---------------------------------------------------------------------

    private static final boolean DEBUG_SHOW_WAX_GIZMO = false;

    private static final int WAX_BOX_X = 94;
    private static final int WAX_BOX_Y = 74;
    private static final int WAX_BOX_WIDTH = 36;
    private static final int WAX_BOX_HEIGHT = 36;
    private static final int WAX_BOX_COLOR = 0x80FF0000;
    private static final float WAX_SIGIL_RADIUS_SCALE = 0.85f;

    private static final int ZOOM_WAX_BOX_X = 50;
    private static final int ZOOM_WAX_BOX_Y = 30;
    private static final int ZOOM_WAX_BOX_WIDTH = 125;
    private static final int ZOOM_WAX_BOX_HEIGHT = 125;

    private static final int WAX_SIGIL_CENTER_OFFSET_X = 0;
    private static final int WAX_SIGIL_CENTER_OFFSET_Y = 0;

    private static final float HOVER_ZOOM_SCALE = 3.5f;

    private static final int ZOOM_SIGIL_RADIUS_PIXELS = 42;

    private boolean zoomActive = false;

    // ---------------------------------------------------------------------
    // GUI dimensions & layout
    // ---------------------------------------------------------------------

    private static final int GUI_WIDTH = SCROLL_FRAME_WIDTH;
    private static final int GUI_HEIGHT = 200;

    private static final int DATE_X = 30;
    private static final int DATE_Y = 18;
    private static final int DATE_WIDTH = 150;
    private static final int DATE_HEIGHT = 14;
    private static final int DATE_MAX_CHARS = 64;

    private static final int RECIPIENT_X = 30;
    private static final int RECIPIENT_Y = 36;
    private static final int RECIPIENT_WIDTH = 125;
    private static final int RECIPIENT_HEIGHT = 14;
    private static final int RECIPIENT_MAX_CHARS = 64;

    private static final int MESSAGE_X = 30;
    private static final int MESSAGE_Y = 62;
    private static final int MESSAGE_WIDTH = 125;
    private static final int MESSAGE_HEIGHT = 6 * 9 + 10;
    private static final int MESSAGE_MAX_CHARS = 512;
    private static final int MESSAGE_MAX_LINES = 12;

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

    private String dateText = "";
    private String recipientText = "";
    private String messageText = "";
    private String signatureText = "";
    private boolean hasAttachments = false;

    private final WaxSealVisualizer waxSealVisualizer = new WaxSealVisualizer();

    private SigilPattern smallSigilPattern = null;
    private SigilPattern zoomSigilPattern = null;

    private ItemStack sealedScrollStack = ItemStack.EMPTY;

    // Close guard: we only want to attempt container close once.
    private boolean requestedClose = false;

    // ---------------------------------------------------------------------
    // Seal-break request bookkeeping (client side)
    // ---------------------------------------------------------------------

    /**
     * We only request a seal-break once per screen open, on the click that starts OPENING.
     */
    private boolean sealBreakRequested = false;

    /**
     * Fingerprint fields parsed from SealedScroll NBT. These are used to identify the exact stack on the server.
     * (Server will still be authoritative; this is only to be specific.)
     */
    private long sealedSeed = 0L;
    private String sealedRecipientUUID = "";
    private String sealedDateText = "";
    private String sealedSenderName = "";

    public ScrollViewScreen(@NotNull ScrollViewMenu menu,
                            @NotNull Inventory playerInventory,
                            @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;

        this.titleLabelX = 10000;
        this.titleLabelY = 10000;

        try {
            LOG.debug("[ScrollViewScreen] Constructed (client). menuClass={} containerId={}",
                    menu != null ? menu.getClass().getName() : "null",
                    menu != null ? menu.containerId : -1);
        } catch (Throwable ignored) {
        }
    }

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
            LOG.debug("[ScrollViewScreen] init at leftPos={}, topPos={} (client). containerId={}",
                    this.leftPos, this.topPos, this.menu != null ? this.menu.containerId : -1);

            this.clearWidgets();

            this.viewPhase = ViewPhase.CLOSED_IDLE;
            this.viewPhaseTicks = 0;
            this.zoomActive = false;
            this.requestedClose = false;

            this.sealBreakRequested = false;
            this.sealedSeed = 0L;
            this.sealedRecipientUUID = "";
            this.sealedDateText = "";
            this.sealedSenderName = "";

            this.dateText = "";
            this.recipientText = "";
            this.messageText = "";
            this.signatureText = "";
            this.hasAttachments = false;
            this.sealedScrollStack = ItemStack.EMPTY;

            this.smallSigilPattern = null;
            this.zoomSigilPattern = null;

            loadFromHeldSealedScroll();
            initWidgetsFromCache();
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] init failed", t);
        }
    }

    private void loadFromHeldSealedScroll() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                LOG.warn("[ScrollViewScreen] loadFromHeldSealedScroll: Minecraft or player is null");
                return;
            }

            ItemStack hand = mc.player.getMainHandItem();
            if (hand == null || hand.isEmpty()) {
                hand = mc.player.getOffhandItem();
            }
            if (hand == null || hand.isEmpty()) {
                LOG.warn("[ScrollViewScreen] loadFromHeldSealedScroll: both hands empty");
                return;
            }

            this.sealedScrollStack = hand.copy();
            LOG.debug("[ScrollViewScreen] Using scroll stack from hand: item={} count={}",
                    sealedScrollStack.getItem(), sealedScrollStack.getCount());

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

            // Fingerprint fields for server identification
            try {
                this.sealedSeed = seal.contains("Seed") ? seal.getLong("Seed") : 0L;
            } catch (Throwable tSeed) {
                LOG.error("[ScrollViewScreen] Failed to read SealedScroll.Seed for fingerprint", tSeed);
                this.sealedSeed = 0L;
            }
            try {
                this.sealedRecipientUUID = safeTagString(seal, "RecipientUUID");
            } catch (Throwable tRec) {
                LOG.error("[ScrollViewScreen] Failed to read SealedScroll.RecipientUUID for fingerprint", tRec);
                this.sealedRecipientUUID = "";
            }
            try {
                this.sealedDateText = this.dateText != null ? this.dateText : "";
            } catch (Throwable tDt) {
                LOG.error("[ScrollViewScreen] Failed to set sealedDateText fingerprint", tDt);
                this.sealedDateText = "";
            }
            try {
                this.sealedSenderName = safeTagString(seal, "SenderName");
            } catch (Throwable tSn) {
                LOG.error("[ScrollViewScreen] Failed to read SealedScroll.SenderName for fingerprint", tSn);
                this.sealedSenderName = "";
            }

            if (seal.contains("Attachments", ListTag.TAG_LIST)) {
                ListTag attachments = seal.getList("Attachments", CompoundTag.TAG_COMPOUND);
                this.hasAttachments = attachments != null && !attachments.isEmpty();
            } else {
                this.hasAttachments = false;
            }

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

                LOG.debug("[ScrollViewScreen] Sigils built: seed={} slices={} style={} smallR={} zoomR={}",
                        seed, slices, style, smallRadius, zoomRadius);
            } catch (Throwable tSigil) {
                LOG.error("[ScrollViewScreen] Failed to build sigil patterns", tSigil);
                this.smallSigilPattern = null;
                this.zoomSigilPattern = null;
            }

            LOG.debug("[ScrollViewScreen] Loaded text. hasAttachments={} date='{}' recipient='{}' msgLen={} sig='{}' fingerprint(seed={}, recipientUUID='{}', sender='{}')",
                    this.hasAttachments,
                    this.dateText,
                    this.recipientText,
                    this.messageText != null ? this.messageText.length() : 0,
                    this.signatureText,
                    this.sealedSeed,
                    this.sealedRecipientUUID,
                    this.sealedSenderName);
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
                }
                case OPENING -> {
                    viewPhaseTicks++;

                    if (viewPhaseTicks >= OPENING_ANIM_TICKS) {
                        viewPhaseTicks = OPENING_ANIM_TICKS;
                        viewPhase = ViewPhase.OPEN_IDLE;
                        setWidgetsVisible(true);
                        LOG.debug("[ScrollViewScreen] Opening animation finished -> OPEN_IDLE");
                    }
                }
                case OPEN_IDLE -> {
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
            boolean inSmallWax = isMouseInWaxAreaSmall(mouseX, mouseY);
            boolean inZoomWax = isMouseInWaxAreaZoom(mouseX, mouseY);

            boolean canZoom = (this.viewPhase == ViewPhase.CLOSED_IDLE);

            if (!canZoom) {
                if (zoomActive) {
                    LOG.debug("[ScrollViewScreen] renderBg: Disabling zoomActive (viewPhase={})", viewPhase);
                }
                zoomActive = false;
            } else {
                if (!zoomActive) {
                    if (inSmallWax) {
                        zoomActive = true;
                        LOG.debug("[ScrollViewScreen] renderBg: Zoom entered via SMALL gizmo");
                    }
                } else {
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

                    renderAnimatedScroll(guiGraphics, true);
                } finally {
                    pose.popPose();
                }

                renderWaxSeal(guiGraphics, mouseX, mouseY, partialTick, true);
            } else {
                renderAnimatedScroll(guiGraphics, false);
                renderWaxSeal(guiGraphics, mouseX, mouseY, partialTick, false);
            }

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

    private void renderAnimatedScroll(@NotNull GuiGraphics guiGraphics, boolean zoomed) {
        ResourceLocation texture;
        int frameIndex;

        switch (viewPhase) {
            case CLOSED_IDLE -> {
                texture = zoomed ? SCROLL_CLOSING_TEXTURE_ZOOM : SCROLL_CLOSING_TEXTURE;
                frameIndex = SCROLL_TOTAL_FRAMES - 1;
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
                frameIndex = 0;
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
    }

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
    // Proper close handling (THE FIX)
    // ---------------------------------------------------------------------

    private void requestProperClose(@NotNull String reason) {
        try {
            if (requestedClose) {
                LOG.debug("[ScrollViewScreen] requestProperClose: already requested; ignoring (reason={})", reason);
                return;
            }
            requestedClose = true;

            Minecraft mc = this.minecraft;
            int containerId = (this.menu != null) ? this.menu.containerId : -1;

            LOG.info("[ScrollViewScreen] requestProperClose(reason='{}') containerId={} player={} (client)",
                    reason,
                    containerId,
                    mc != null && mc.player != null ? mc.player.getGameProfile().getName() : "null");

            if (mc != null && mc.player != null) {
                try {
                    // This is the key: sends close-container packet to server.
                    mc.player.closeContainer();
                    LOG.info("[ScrollViewScreen] closeContainer() called (client). containerId={}", containerId);
                } catch (Throwable tClose) {
                    LOG.error("[ScrollViewScreen] closeContainer() failed; falling back to setScreen(null)", tClose);
                }
            } else {
                LOG.warn("[ScrollViewScreen] requestProperClose: mc/player null; cannot close container properly");
            }

            // Also close the screen client-side.
            try {
                if (mc != null) {
                    mc.setScreen(null);
                    LOG.debug("[ScrollViewScreen] setScreen(null) executed (client)");
                }
            } catch (Throwable tScreen) {
                LOG.error("[ScrollViewScreen] setScreen(null) failed", tScreen);
            }
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] requestProperClose failed", t);
        }
    }

    @Override
    public void onClose() {
        // Called by vanilla close paths; ensure it also does proper container close.
        try {
            LOG.debug("[ScrollViewScreen] onClose() invoked (client). requestedClose={}", requestedClose);
        } catch (Throwable ignored) {
        }
        requestProperClose("onClose");
        // Do NOT call super.onClose() after setScreen(null) recursion risk; but it’s safe to call before:
        try {
            super.onClose();
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] super.onClose() failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
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
                    // This is the precise "seal break moment".
                    // Before starting the OPENING animation, notify the server (once) to convert the exact scroll.
                    if (!sealBreakRequested) {
                        int slotHint = resolveHeldScrollSlotHint();
                        LOG.info("[ScrollViewScreen] Seal break click detected -> requesting server seal break (slotHint={} seed={} recipientUUID='{}' date='{}' sender='{}')",
                                slotHint, sealedSeed, sealedRecipientUUID, sealedDateText, sealedSenderName);
                        attemptSendBreakSealToServer(slotHint, sealedSeed, sealedRecipientUUID, sealedDateText, sealedSenderName);
                        sealBreakRequested = true;
                    } else {
                        LOG.debug("[ScrollViewScreen] Seal break click detected but request already sent this session; ignoring duplicate click");
                    }

                    LOG.debug("[ScrollViewScreen] Wax area clicked (zoomActive={}) -> starting OPENING animation", zoomActive);
                    this.viewPhase = ViewPhase.OPENING;
                    this.viewPhaseTicks = 0;
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
            if (keyCode == GLFW.GLFW_KEY_ESCAPE ||
                    keyCode == GLFW.GLFW_KEY_E ||
                    keyCode == GLFW.GLFW_KEY_R ||
                    keyCode == GLFW.GLFW_KEY_U) {
                LOG.info("[ScrollViewScreen] keyPressed {} -> requestProperClose()", keyCode);
                requestProperClose("keyPressed:" + keyCode);
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] keyPressed failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Container slot suppression
    // ---------------------------------------------------------------------

    @Override
    protected void renderSlot(GuiGraphics guiGraphics, Slot slot) {
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, net.minecraft.world.inventory.ClickType type) {
        if (slot != null) {
            LOG.debug("[ScrollViewScreen] slotClicked ignored: slotId={} type={} button={}", slotId, type, mouseButton);
        }
    }

    // ---------------------------------------------------------------------
    // Seal-break networking (batch 1 uses reflection for compile safety)
    // ---------------------------------------------------------------------

    /**
     * Attempts to determine where the scroll is currently held.
     *
     * Slot mapping convention:
     *  - 36: main hand
     *  - 37: off hand
     *  - 0..35: player inventory indices
     *  - -1: unknown
     */
    private int resolveHeldScrollSlotHint() {
        try {
            Minecraft mc = this.minecraft;
            if (mc == null || mc.player == null) {
                LOG.warn("[ScrollViewScreen] resolveHeldScrollSlotHint: mc/player null");
                return -1;
            }

            if (this.sealedScrollStack == null || this.sealedScrollStack.isEmpty()) {
                LOG.warn("[ScrollViewScreen] resolveHeldScrollSlotHint: sealedScrollStack snapshot empty");
                return -1;
            }

            ItemStack main = mc.player.getMainHandItem();
            if (isLikelySameScroll(main, this.sealedScrollStack)) {
                return 36;
            }

            ItemStack off = mc.player.getOffhandItem();
            if (isLikelySameScroll(off, this.sealedScrollStack)) {
                return 37;
            }

            try {
                for (int i = 0; i < mc.player.getInventory().items.size(); i++) {
                    ItemStack s = mc.player.getInventory().items.get(i);
                    if (isLikelySameScroll(s, this.sealedScrollStack)) {
                        return i;
                    }
                }
            } catch (Throwable tInv) {
                LOG.error("[ScrollViewScreen] resolveHeldScrollSlotHint: inventory scan failed", tInv);
            }

            return -1;
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] resolveHeldScrollSlotHint failed", t);
            return -1;
        }
    }

    private static boolean isLikelySameScroll(ItemStack a, ItemStack b) {
        try {
            if (a == null || b == null) {
                return false;
            }
            if (a.isEmpty() || b.isEmpty()) {
                return false;
            }
            if (a.getItem() != b.getItem()) {
                return false;
            }

            // Compare CustomData tags to be specific (non-stackable, but still be precise)
            CustomData acd = a.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData bcd = b.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag at = acd.copyTag();
            CompoundTag bt = bcd.copyTag();

            if (at == null && bt == null) {
                return true;
            }
            if (at == null || bt == null) {
                return false;
            }
            return at.equals(bt);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] isLikelySameScroll failed; falling back to false", t);
            return false;
        }
    }

    /**
     * Batch-1 compile-safe call into FFNetwork. Next batch will provide a direct method call.
     *
     * Expected method signature (to be implemented in FFNetwork next batch):
     *   public static void sendBreakSealToServer(int slotHint, long seed, String recipientUUID, String dateText, String senderName)
     */
    private void attemptSendBreakSealToServer(int slotHint,
                                              long seed,
                                              @NotNull String recipientUUID,
                                              @NotNull String dateText,
                                              @NotNull String senderName) {
        try {
            Class<?> clazz = Class.forName("net.z2six.featheredfriend.network.FFNetwork");
            Method m = clazz.getDeclaredMethod(
                    "sendBreakSealToServer",
                    int.class,
                    long.class,
                    String.class,
                    String.class,
                    String.class
            );

            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
            }

            m.invoke(null, slotHint, seed, recipientUUID != null ? recipientUUID : "", dateText != null ? dateText : "", senderName != null ? senderName : "");

            LOG.info("[ScrollViewScreen] Break-seal request sent via FFNetwork.sendBreakSealToServer(slotHint={}, seed={})",
                    slotHint, seed);
        } catch (ClassNotFoundException e) {
            LOG.warn("[ScrollViewScreen] FFNetwork class not found; break-seal request not sent (will be available after next batch)");
        } catch (NoSuchMethodException e) {
            LOG.warn("[ScrollViewScreen] FFNetwork.sendBreakSealToServer(...) not found; break-seal request not sent (will be available after next batch)");
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] attemptSendBreakSealToServer failed", t);
        }
    }
}
