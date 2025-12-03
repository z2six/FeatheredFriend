// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SealStampScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.neoforge.menu.SealStampMenu;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SealStampScreen.java
 *
 * SealStampScreen – Step 2 GUI
 *
 * Layout:
 *  - Secret field at the top, centered.
 *  - Left column (below secret):
 *      * Etchings dropdown (slices 2–8)
 *      * Style dropdown (Medieval, Fantasy, Floral)
 *      * Carve button
 *  - Right side: Sigil preview box.
 *
 * No sigil rendering or NBT writing yet – just UI.
 */
public class SealStampScreen extends AbstractContainerScreen<SealStampMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation SEAL_STAMP_GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/seal_stamp.png");

    // Gothic font id (same as used by ScrollSealingScreen / MultiLineScrollTextWidget)
    private static final ResourceLocation GOTHIC_FONT_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gothic12");

    // ---------------------------------------------------------------------
    // GUI dimensions
    // ---------------------------------------------------------------------

    private static final int GUI_WIDTH = 300;
    private static final int GUI_HEIGHT = 200;

    // ---------------------------------------------------------------------
    // "Secret" field config (relative to GUI origin)
    // ---------------------------------------------------------------------

    // Size
    private static final int SECRET_WIDTH = 200;
    private static final int SECRET_HEIGHT = 28;
    private static final int SECRET_MAX_CHARS = 52;
    private static final int SECRET_MAX_LINES = 2;

    // Position
    // Y is absolute from top of GUI; X is centered + offset.
    private static final int SECRET_Y = 20;
    private static final int SECRET_X_OFFSET = -32; // negative -> shift left, positive -> shift right

    // ---------------------------------------------------------------------
    // Left column (Etchings / Style / Carve) config
    // ---------------------------------------------------------------------

    // Base X for the left column (relative to GUI origin).
    private static final int LEFT_COLUMN_X = 20;

    // Vertical placement for the first control in the column.
    private static final int LEFT_COLUMN_FIRST_Y = 60;

    // Common sizes
    private static final int DROPDOWN_WIDTH = 90;
    private static final int DROPDOWN_HEIGHT = 20;
    private static final int CONTROL_VERTICAL_GAP = 6; // gap between stacked controls

    // Carve button size (can differ from dropdowns if you want)
    private static final int CARVE_WIDTH = 90;
    private static final int CARVE_HEIGHT = 20;

    // ---------------------------------------------------------------------
    // Preview area config (right side of GUI)
    // ---------------------------------------------------------------------

    // Size
    private static final int PREVIEW_WIDTH = 160;
    private static final int PREVIEW_HEIGHT = 120;

    // Horizontal placement: preview box anchored to the right with a margin.
    private static final int PREVIEW_RIGHT_MARGIN = 20;

    // Vertical placement: top of preview box.
    private static final int PREVIEW_TOP_Y = 60;

    // Label relative to preview box top.
    private static final int PREVIEW_LABEL_OFFSET_Y = -12;

    // ---------------------------------------------------------------------
    // Widgets
    // ---------------------------------------------------------------------

    private MultiLineScrollTextWidget secretField;
    private CycleButton<Integer> slicesButton;
    private CycleButton<ShapesetStyle> styleButton;
    private Button carveButton;

    // Sigil preview area absolute coordinates
    private int previewX;
    private int previewY;
    private int previewWidth;
    private int previewHeight;

    public SealStampScreen(@NotNull SealStampMenu menu,
                           @NotNull Inventory playerInventory,
                           @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;

        // Hide vanilla titles for now; we draw nothing from the base labels.
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
        this.inventoryLabelX = 10000;
        this.inventoryLabelY = 10000;
    }

    // ---------------------------------------------------------------------
    // Shapeset style enum (index mapping for later sigil logic)
    // ---------------------------------------------------------------------

    private enum ShapesetStyle {
        MEDIEVAL(0, "Medieval"),
        FANTASY(1, "Fantasy"),
        FLORAL(2, "Floral");

        private final int index;
        private final String displayName;

        ShapesetStyle(int index, String displayName) {
            this.index = index;
            this.displayName = displayName;
        }

        public int index() {
            return index;
        }

        public String displayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    // ---------------------------------------------------------------------
    // Init & layout
    // ---------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();

        try {
            LOG.debug("[SealStampScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);

            this.clearWidgets();

            int guiLeft = this.leftPos;
            int guiTop = this.topPos;

            // -------------------------------------------------------------
            // Secret field (top, centered)
            // -------------------------------------------------------------
            int secretXCentered = guiLeft + (this.imageWidth - SECRET_WIDTH) / 2;
            int secretX = secretXCentered + SECRET_X_OFFSET;
            int secretY = guiTop + SECRET_Y;

            this.secretField = new MultiLineScrollTextWidget(
                    this.font,
                    secretX,
                    secretY,
                    SECRET_WIDTH,
                    SECRET_HEIGHT,
                    SECRET_MAX_CHARS,
                    SECRET_MAX_LINES,
                    gothicLiteral("Secret passphrase"),
                    GOTHIC_FONT_ID,
                    false // allowNewlines
            );
            this.secretField.setEditable(true);
            this.addRenderableWidget(this.secretField);

            // -------------------------------------------------------------
            // Left column: Etchings / Style / Carve
            // -------------------------------------------------------------
            int leftX = guiLeft + LEFT_COLUMN_X;
            int currentY = guiTop + LEFT_COLUMN_FIRST_Y;

            // Etchings (slices)
            this.slicesButton = CycleButton.<Integer>builder(value ->
                            gothicLiteral(String.valueOf(value)))
                    .withValues(2, 3, 4, 5, 6, 7, 8)
                    .withInitialValue(4)
                    .create(
                            leftX,
                            currentY,
                            DROPDOWN_WIDTH,
                            DROPDOWN_HEIGHT,
                            gothicLiteral("Etchings"),
                            (btn, value) -> {
                                try {
                                    LOG.debug("[SealStampScreen] Etchings changed to {}", value);
                                } catch (Throwable t) {
                                    LOG.error("[SealStampScreen] slicesButton onValueChange failed", t);
                                }
                            }
                    );
            this.addRenderableWidget(this.slicesButton);

            currentY += DROPDOWN_HEIGHT + CONTROL_VERTICAL_GAP;

            // Style (shapeset)
            this.styleButton = CycleButton.<ShapesetStyle>builder(style ->
                            gothicLiteral(style.displayName()))
                    .withValues(ShapesetStyle.values())
                    .withInitialValue(ShapesetStyle.MEDIEVAL)
                    .create(
                            leftX,
                            currentY,
                            DROPDOWN_WIDTH,
                            DROPDOWN_HEIGHT,
                            gothicLiteral("Style"),
                            (btn, value) -> {
                                try {
                                    LOG.debug("[SealStampScreen] Style changed to {} (index={})",
                                            value.displayName(), value.index());
                                } catch (Throwable t) {
                                    LOG.error("[SealStampScreen] styleButton onValueChange failed", t);
                                }
                            }
                    );
            this.addRenderableWidget(this.styleButton);

            currentY += DROPDOWN_HEIGHT + CONTROL_VERTICAL_GAP;

            // Carve button
            this.carveButton = Button.builder(
                            gothicLiteral("Carve"),
                            b -> onCarveClicked()
                    )
                    .bounds(leftX, currentY, CARVE_WIDTH, CARVE_HEIGHT)
                    .build();
            this.addRenderableWidget(this.carveButton);

            // -------------------------------------------------------------
            // Right side: Sigil preview reserved box
            // -------------------------------------------------------------
            this.previewWidth = PREVIEW_WIDTH;
            this.previewHeight = PREVIEW_HEIGHT;

            this.previewX = guiLeft + this.imageWidth - PREVIEW_RIGHT_MARGIN - this.previewWidth;
            this.previewY = guiTop + PREVIEW_TOP_Y;

        } catch (Throwable t) {
            LOG.error("[SealStampScreen] init failed", t);
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
            boolean hasTexture = resourceManager.getResource(SEAL_STAMP_GUI_TEXTURE).isPresent();

            if (hasTexture) {
                guiGraphics.blit(
                        SEAL_STAMP_GUI_TEXTURE,
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

            // Draw "Sigil Preview" label in gothic font above the preview box
            Component label = gothicLiteral("Sigil Preview");
            int labelWidth = this.font.width(label);
            int labelX = this.previewX + (this.previewWidth - labelWidth) / 2;
            int labelY = this.previewY + PREVIEW_LABEL_OFFSET_Y;
            guiGraphics.drawString(this.font, label, labelX, labelY, 0xFF000000, false);

            // Draw the preview box (reserved area for sigil rendering later)
            int x0 = this.previewX;
            int y0 = this.previewY;
            int x1 = this.previewX + this.previewWidth;
            int y1 = this.previewY + this.previewHeight;

            // Fill background (slightly translucent parchment-ish)
            guiGraphics.fill(x0, y0, x1, y1, 0x40F5F0D8);

            // Simple border
            int borderColor = 0xFF000000;
            guiGraphics.fill(x0, y0, x1, y0 + 1, borderColor); // top
            guiGraphics.fill(x0, y1 - 1, x1, y1, borderColor); // bottom
            guiGraphics.fill(x0, y0, x0 + 1, y1, borderColor); // left
            guiGraphics.fill(x1 - 1, y0, x1, y1, borderColor); // right

        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderBg failed, falling back to simple fill", t);
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
            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] render failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Intentionally empty (we handle text in renderBg/render()).
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            // Swallow inventory/JEI keys while this screen is open
            if (keyCode == Minecraft.getInstance().options.keyInventory.getKey().getValue()
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_R
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_U) {
                return true;
            }

            // Let our secret field handle text editing keys first
            if (this.secretField != null && this.secretField.isFocused()) {
                if (this.secretField.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] keyPressed failed", t);
            return false;
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        try {
            if (this.secretField != null && this.secretField.isFocused()) {
                if (this.secretField.charTyped(codePoint, modifiers)) {
                    return true;
                }
            }
            return super.charTyped(codePoint, modifiers);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] charTyped failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Carve button behaviour (placeholder)
    // ---------------------------------------------------------------------

    private void onCarveClicked() {
        try {
            String secret = this.secretField != null ? this.secretField.getText() : "";
            int slices = this.slicesButton != null ? this.slicesButton.getValue() : 4;
            ShapesetStyle style = this.styleButton != null ? this.styleButton.getValue() : ShapesetStyle.MEDIEVAL;

            LOG.info("[SealStampScreen] Carve clicked. secret='{}' slices={} style={} (index={})",
                    secret, slices, style.displayName(), style.index());

            // Step 3 will:
            //  - Compute a seed from (playerUUID + secret)
            //  - Store:
            //      * Seed
            //      * Slices
            //      * Shapeset index
            //      * Owner UUID
            //    into the SealStampItem's CUSTOM_DATA
            //  - Generate sigil preview graphic client-side.
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] onCarveClicked failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private Component gothicLiteral(String text) {
        try {
            return Component.literal(text)
                    .withStyle(style -> style.withFont(GOTHIC_FONT_ID));
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] gothicLiteral failed for text='{}', falling back to vanilla font", text, t);
            return Component.literal(text);
        }
    }
}
