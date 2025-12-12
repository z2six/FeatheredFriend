// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollViewScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.neoforge.menu.ScrollViewMenu;
import net.z2six.featheredfriend.network.FFNetwork;
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
 * Read-only view for a sealed scroll.
 *
 * IMPORTANT:
 *  - Closing via minecraft.setScreen(null) alone can leave the server-side menu open
 *    until the next inventory interaction.
 *  - We MUST close the container properly: minecraft.player.closeContainer().
 *
 * Seal breaking:
 *  - On the exact click that starts OPENING, we:
 *      1) Send BreakSealPacket to server (authoritative conversion scroll_sealed -> scroll_opened)
 *      2) Optimistically convert the held stack client-side immediately (UX) using the exact same rule:
 *         copy SealedScroll tags except Attachments.
 *  - Attachments are still delivered on GUI close, but ONLY if the seal was broken (server-side gating).
 */
public class ScrollViewScreen extends AbstractContainerScreen<ScrollViewMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Textures & fonts
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
    // Wax gizmo / zoom configuration
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

    /**
     * Snapshot of the held stack used to open this screen.
     * This may become stale after the client-side optimistic swap or server sync.
     */
    private ItemStack sealedScrollStack = ItemStack.EMPTY;

    // Close guard
    private boolean requestedClose = false;

    // ---------------------------------------------------------------------
    // Seal-break request bookkeeping (client side)
    // ---------------------------------------------------------------------

    /**
     * We only request a seal-break once per screen open, on the click that starts OPENING.
     */
    private boolean sealBreakRequested = false;

    /**
     * Fingerprint fields parsed from SealedScroll NBT. Used to identify the exact stack on server.
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
                    BuiltInRegistries.ITEM.getKey(sealedScrollStack.getItem()), sealedScrollStack.getCount());

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

            // Fingerprint fields
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
    // Proper close handling
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
                    mc.player.closeContainer();
                    LOG.info("[ScrollViewScreen] closeContainer() called (client). containerId={}", containerId);
                } catch (Throwable tClose) {
                    LOG.error("[ScrollViewScreen] closeContainer() failed; falling back to setScreen(null)", tClose);
                }
            } else {
                LOG.warn("[ScrollViewScreen] requestProperClose: mc/player null; cannot close container properly");
            }

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
        try {
            LOG.debug("[ScrollViewScreen] onClose() invoked (client). requestedClose={}", requestedClose);
        } catch (Throwable ignored) {
        }
        requestProperClose("onClose");
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
                    if (!sealBreakRequested) {
                        int slotHint = resolveHeldScrollSlotHint();

                        LOG.info("[ScrollViewScreen] Seal break click -> sending BreakSealPacket (slotHint={} seed={} recipientUUID='{}' date='{}' sender='{}')",
                                slotHint, sealedSeed, sealedRecipientUUID, sealedDateText, sealedSenderName);

                        // 1) Authoritative server conversion
                        FFNetwork.sendBreakSealToServer(
                                slotHint,
                                sealedSeed,
                                sealedRecipientUUID != null ? sealedRecipientUUID : "",
                                sealedDateText != null ? sealedDateText : "",
                                sealedSenderName != null ? sealedSenderName : ""
                        );

                        // 2) Immediate client-side UX conversion (server will still override if needed)
                        boolean clientSwapped = optimisticClientSwapToOpened(slotHint);
                        LOG.info("[ScrollViewScreen] Client-side optimistic swap result={} (slotHint={})", clientSwapped, slotHint);

                        sealBreakRequested = true;
                    } else {
                        LOG.debug("[ScrollViewScreen] Seal break click but request already sent; ignoring duplicate");
                    }

                    LOG.debug("[ScrollViewScreen] Wax clicked (zoomActive={}) -> starting OPENING animation", zoomActive);
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
    // Held scroll identification + immediate client-side swap
    // ---------------------------------------------------------------------

    /**
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
            if (a == null || b == null) return false;
            if (a.isEmpty() || b.isEmpty()) return false;
            if (a.getItem() != b.getItem()) return false;

            CustomData acd = a.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CustomData bcd = b.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag at = acd.copyTag();
            CompoundTag bt = bcd.copyTag();

            if (at == null && bt == null) return true;
            if (at == null || bt == null) return false;
            return at.equals(bt);
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] isLikelySameScroll failed; returning false", t);
            return false;
        }
    }

    /**
     * Client-only UX: replace the currently held scroll_sealed with scroll_opened immediately,
     * copying SealedScroll NBT EXCEPT Attachments.
     *
     * Server is still authoritative. This simply removes the "it only changes on close" feel.
     */
    private boolean optimisticClientSwapToOpened(int slotHint) {
        try {
            Minecraft mc = this.minecraft;
            if (mc == null || mc.player == null) {
                LOG.warn("[ScrollViewScreen] optimisticClientSwapToOpened: mc/player null");
                return false;
            }

            Item openedItem = resolveItemByPath("scroll_opened");
            Item sealedItem = resolveItemByPath("scroll_sealed");

            if (openedItem == null || openedItem == Items.AIR) {
                LOG.error("[ScrollViewScreen] optimisticClientSwapToOpened: scroll_opened not found");
                return false;
            }
            if (sealedItem == null || sealedItem == Items.AIR) {
                LOG.error("[ScrollViewScreen] optimisticClientSwapToOpened: scroll_sealed not found");
                return false;
            }

            // Find target stack by hint first, fallback scan
            TargetSlot target = findClientTargetSealedScrollByHintOrScan(mc, sealedItem, slotHint);
            if (target == null) {
                LOG.warn("[ScrollViewScreen] optimisticClientSwapToOpened: could not find target sealed scroll (slotHint={})", slotHint);
                return false;
            }

            if (target.stack == null || target.stack.isEmpty() || target.stack.getItem() != sealedItem) {
                LOG.warn("[ScrollViewScreen] optimisticClientSwapToOpened: target not scroll_sealed (found item={})",
                        target.stack != null ? BuiltInRegistries.ITEM.getKey(target.stack.getItem()) : "null");
                return false;
            }

            if (!matchesFingerprintClient(target.stack)) {
                LOG.warn("[ScrollViewScreen] optimisticClientSwapToOpened: target does not match fingerprint; refusing swap");
                return false;
            }

            ItemStack opened = new ItemStack(openedItem, 1);

            boolean copied = copySealedScrollDataWithoutAttachmentsClient(target.stack, opened);
            if (!copied) {
                LOG.warn("[ScrollViewScreen] optimisticClientSwapToOpened: failed to copy SealedScroll data; still swapping item type");
            }

            // Apply replacement
            switch (target.location) {
                case MAIN_HAND -> mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, opened);
                case OFF_HAND -> mc.player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, opened);
                case INVENTORY -> {
                    if (target.invIndex < 0 || target.invIndex >= mc.player.getInventory().items.size()) {
                        LOG.error("[ScrollViewScreen] optimisticClientSwapToOpened: invalid inventory index {}", target.invIndex);
                        return false;
                    }
                    mc.player.getInventory().items.set(target.invIndex, opened);
                }
            }

            LOG.info("[ScrollViewScreen] optimisticClientSwapToOpened: swapped {} -> {} at {}",
                    BuiltInRegistries.ITEM.getKey(sealedItem),
                    BuiltInRegistries.ITEM.getKey(openedItem),
                    target.description);

            return true;
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] optimisticClientSwapToOpened failed", t);
            return false;
        }
    }

    private enum TargetLocation {
        MAIN_HAND,
        OFF_HAND,
        INVENTORY
    }

    private static final class TargetSlot {
        final TargetLocation location;
        final int invIndex;
        final ItemStack stack;
        final String description;

        TargetSlot(TargetLocation location, int invIndex, ItemStack stack, String description) {
            this.location = location;
            this.invIndex = invIndex;
            this.stack = stack;
            this.description = description;
        }
    }

    private TargetSlot findClientTargetSealedScrollByHintOrScan(@NotNull Minecraft mc, @NotNull Item sealedItem, int slotHint) {
        try {
            // Hint first
            TargetSlot hinted = getClientStackByHint(mc, slotHint);
            if (hinted != null && hinted.stack != null && !hinted.stack.isEmpty() && hinted.stack.getItem() == sealedItem) {
                return hinted;
            }

            // Fallback: main/off + inventory scan by fingerprint
            ItemStack main = mc.player.getMainHandItem();
            if (main != null && !main.isEmpty() && main.getItem() == sealedItem && matchesFingerprintClient(main)) {
                return new TargetSlot(TargetLocation.MAIN_HAND, -1, main, "MAIN_HAND(scan)");
            }

            ItemStack off = mc.player.getOffhandItem();
            if (off != null && !off.isEmpty() && off.getItem() == sealedItem && matchesFingerprintClient(off)) {
                return new TargetSlot(TargetLocation.OFF_HAND, -1, off, "OFF_HAND(scan)");
            }

            for (int i = 0; i < mc.player.getInventory().items.size(); i++) {
                ItemStack s = mc.player.getInventory().items.get(i);
                if (s != null && !s.isEmpty() && s.getItem() == sealedItem && matchesFingerprintClient(s)) {
                    return new TargetSlot(TargetLocation.INVENTORY, i, s, "INVENTORY(scan:" + i + ")");
                }
            }

            return null;
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] findClientTargetSealedScrollByHintOrScan failed", t);
            return null;
        }
    }

    private TargetSlot getClientStackByHint(@NotNull Minecraft mc, int slotHint) {
        try {
            if (slotHint == 36) {
                return new TargetSlot(TargetLocation.MAIN_HAND, -1, mc.player.getMainHandItem(), "MAIN_HAND(36)");
            }
            if (slotHint == 37) {
                return new TargetSlot(TargetLocation.OFF_HAND, -1, mc.player.getOffhandItem(), "OFF_HAND(37)");
            }
            if (slotHint >= 0 && slotHint < mc.player.getInventory().items.size()) {
                return new TargetSlot(TargetLocation.INVENTORY, slotHint, mc.player.getInventory().items.get(slotHint), "INVENTORY(" + slotHint + ")");
            }
            return null;
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] getClientStackByHint failed", t);
            return null;
        }
    }

    private boolean matchesFingerprintClient(@NotNull ItemStack stack) {
        try {
            CustomData cd = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag root = cd.copyTag();
            if (root == null || root.isEmpty()) return false;
            if (!root.contains("SealedScroll", CompoundTag.TAG_COMPOUND)) return false;

            CompoundTag seal = root.getCompound("SealedScroll");

            long seed = 0L;
            try {
                if (seal.contains("Seed")) seed = seal.getLong("Seed");
            } catch (Throwable ignored) {
                seed = 0L;
            }

            String recUuid = "";
            String date = "";
            String sender = "";
            try {
                if (seal.contains("RecipientUUID")) recUuid = seal.getString("RecipientUUID");
            } catch (Throwable ignored) {
                recUuid = "";
            }
            try {
                if (seal.contains("DateText")) date = seal.getString("DateText");
            } catch (Throwable ignored) {
                date = "";
            }
            try {
                if (seal.contains("SenderName")) sender = seal.getString("SenderName");
            } catch (Throwable ignored) {
                sender = "";
            }

            boolean ok =
                    seed == this.sealedSeed
                            && safeEq(recUuid, this.sealedRecipientUUID)
                            && safeEq(date, this.sealedDateText)
                            && safeEq(sender, this.sealedSenderName);

            if (!ok) {
                LOG.debug("[ScrollViewScreen] matchesFingerprintClient mismatch: candidate(seed={} recUuid='{}' date='{}' sender='{}') vs screen(seed={} recUuid='{}' date='{}' sender='{}')",
                        seed,
                        safeLog(recUuid),
                        safeLog(date),
                        safeLog(sender),
                        this.sealedSeed,
                        safeLog(this.sealedRecipientUUID),
                        safeLog(this.sealedDateText),
                        safeLog(this.sealedSenderName));
            }

            return ok;
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] matchesFingerprintClient failed", t);
            return false;
        }
    }

    private static boolean copySealedScrollDataWithoutAttachmentsClient(@NotNull ItemStack sealed,
                                                                        @NotNull ItemStack opened) {
        try {
            CustomData sealedCd = sealed.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag sealedRoot = sealedCd.copyTag();
            if (sealedRoot == null || sealedRoot.isEmpty()) {
                return false;
            }
            if (!sealedRoot.contains("SealedScroll", CompoundTag.TAG_COMPOUND)) {
                return false;
            }

            CompoundTag sealedSeal = sealedRoot.getCompound("SealedScroll");
            if (sealedSeal == null) {
                return false;
            }

            CompoundTag openedRoot = new CompoundTag();
            CompoundTag openedSeal = sealedSeal.copy();

            if (openedSeal.contains("Attachments")) {
                try {
                    openedSeal.remove("Attachments");
                } catch (Throwable ignored) {
                }
            }

            openedRoot.put("SealedScroll", openedSeal);
            opened.set(DataComponents.CUSTOM_DATA, CustomData.of(openedRoot));
            return true;
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] copySealedScrollDataWithoutAttachmentsClient failed", t);
            return false;
        }
    }

    private static Item resolveItemByPath(@NotNull String path) {
        try {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null) {
                LOG.error("[ScrollViewScreen] resolveItemByPath: item {} is null", id);
                return Items.AIR;
            }
            if (item == Items.AIR) {
                LOG.warn("[ScrollViewScreen] resolveItemByPath: item {} returned as AIR", id);
            }
            return item;
        } catch (Throwable t) {
            LOG.error("[ScrollViewScreen] resolveItemByPath failed for path='{}'", path, t);
            return Items.AIR;
        }
    }

    private static boolean safeEq(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private static String safeLog(String s) {
        if (s == null) return "null";
        if (s.length() <= 120) return s;
        return s.substring(0, 120) + "...";
    }
}
