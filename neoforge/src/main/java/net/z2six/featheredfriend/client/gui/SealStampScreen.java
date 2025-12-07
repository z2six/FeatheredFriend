// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SealStampScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.neoforge.menu.SealStampMenu;
import net.z2six.featheredfriend.sigil.SealSigilGenerator;
import net.z2six.featheredfriend.sigil.SealSigilGenerator.SigilPattern;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SealStampScreen.java
 *
 * SealStampScreen
 *
 * Uses Gothic font + MultiLineScrollTextWidget for the secret passphrase.
 *
 * Left column: Etchings dropdown, Style dropdown, Carve button, GUI-scale toggle button.
 *
 * Right side: "Sigil Preview" area:
 *  - Renders wax_seal.png (scaled) in the center of the preview box.
 *  - Uses SealSigilGenerator's disc + shape masks to draw:
 *      * Shape-only sigil glyph with directional lighting:
 *          - Base fill colour.
 *          - Optional directional shadow band outside the shapes.
 *          - Optional directional highlight band outside the shapes.
 *
 * On "Carve" click:
 *  * Spawns a burst of SigilEtchingParticle chips from the sigil area.
 *  * All motion / feel is controlled inside SigilEtchingParticle via createForCarve().
 *
 * Sigil preview:
 *  * Seed is derived from secret only using SealSigilGenerator.computeSeedFromSecretOnly().
 *  * Slices and styleIndex are applied afterwards via generateFromSeed().
 *  * Pattern is regenerated when secret changes or when slices/style buttons are clicked.
 *
 * Sigil → NBT saving will be wired in a later step.
 */
public class SealStampScreen extends AbstractContainerScreen<SealStampMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    // Gothic font id (same as used by ScrollSealingScreen / MultiLineScrollTextWidget)
    private static final ResourceLocation GOTHIC_FONT_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gothic12");

    // Wax seal texture used for the sigil preview
    private static final ResourceLocation WAX_SEAL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/stampscreen/wax_seal.png");

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
    private static final int SECRET_X_OFFSET = -30; // negative -> shift left, positive -> shift right

    // ---------------------------------------------------------------------
    // Left column (Etchings / Style / Carve / Scale) config
    // ---------------------------------------------------------------------

    // Base X for the left column (relative to GUI origin).
    private static final int LEFT_COLUMN_X = 20;

    // Vertical placement for the first control in the column.
    private static final int LEFT_COLUMN_FIRST_Y = 60;

    // Common sizes
    private static final int DROPDOWN_WIDTH = 90;
    private static final int DROPDOWN_HEIGHT = 20;
    private static final int CONTROL_VERTICAL_GAP = 6; // gap between stacked controls

    // Carve button size
    private static final int CARVE_WIDTH = 90;
    private static final int CARVE_HEIGHT = 20;

    // GUI scale toggle button size
    private static final int SCALE_WIDTH = 90;
    private static final int SCALE_HEIGHT = 20;

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
    // Wax seal sizing (independent of sigil size)
    // ---------------------------------------------------------------------

    /**
     * Source texture size for wax_seal.png.
     * Adjust these if you change the texture dimensions.
     */
    private static final int WAX_SEAL_TEXTURE_WIDTH = 39;
    private static final int WAX_SEAL_TEXTURE_HEIGHT = 38;

    /**
     * Screen scale factor for the wax seal preview.
     * Tweak this to make the seal bigger/smaller on screen.
     * This ONLY affects the wax seal, not the sigil size.
     */
    private static final float WAX_SEAL_SCREEN_SCALE = 3.0f;

    /**
     * Final on-screen size of the wax seal after scaling.
     * These are the main knobs you can inspect / tweak if needed.
     */
    private static final int WAX_SEAL_SCREEN_WIDTH =
            Math.round(WAX_SEAL_TEXTURE_WIDTH * WAX_SEAL_SCREEN_SCALE);
    private static final int WAX_SEAL_SCREEN_HEIGHT =
            Math.round(WAX_SEAL_TEXTURE_HEIGHT * WAX_SEAL_SCREEN_SCALE);

    // ---------------------------------------------------------------------
    // Sigil sizing (independent of wax seal)
    // ---------------------------------------------------------------------

    /**
     * Base diameter for the sigil, in screen pixels. This is completely
     * independent from the wax seal size. Increase/decrease to resize the sigil.
     */
    private static final int SIGIL_BASE_DIAMETER_PIXELS = 90;

    /**
     * Fraction of the base diameter used for the sigil radius.
     * 1.0f = full diameter; lower values keep it inset.
     */
    private static final float SIGIL_RADIUS_SCALE = 0.85f;

    /**
     * Sigil radius inside the preview area, in screen pixels.
     * Changing SIGIL_BASE_DIAMETER_PIXELS or SIGIL_RADIUS_SCALE will
     * resize the sigil without touching the wax seal.
     */
    private static final int SIGIL_RADIUS =
            (int) (SIGIL_BASE_DIAMETER_PIXELS * 0.5f * SIGIL_RADIUS_SCALE);

    // ---------------------------------------------------------------------
    // Etching options
    // ---------------------------------------------------------------------

    private static final int MIN_SLICES = 2;
    private static final int MAX_SLICES = 8;

    private static final String[] STYLE_NAMES = {"Medieval", "Fantasy", "Floral"};

    // ---------------------------------------------------------------------
    // Sigil visual config (shape-only lighting)
    // ---------------------------------------------------------------------

    /**
     * Fallback golden colour if the wax texture is missing.
     * (Used only for the wax fallback circle, *not* the sigil disc.)
     */
    private static final int GOLD_DISC_COLOR = 0xFFE0C060;

    // --- Shape fill ------------------------------------------------------

    /**
     * Base fill colour for the shape interiors.
     *
     *  HEX:  #C54750
     *  ARGB: 0xFFC54750
     */
    private static final int SHAPE_FILL_COLOR = 0xFFb83e3e;

    // --- Shape highlight / shadow configuration --------------------------

    /**
     * Enable/disable highlight band outside the shape.
     */
    private static final boolean SHAPE_ENABLE_HIGHLIGHT = true;

    /**
     * Enable/disable shadow band outside the shape.
     */
    private static final boolean SHAPE_ENABLE_SHADOW = true;

    /**
     * Thickness of the highlight band (in pixels, Chebyshev radius).
     * 1 => 1 px thick highlight band.
     */
    private static final int SHAPE_HIGHLIGHT_EDGE_MAX_RADIUS = 1;

    /**
     * Thickness of the shadow band (in pixels, Chebyshev radius).
     * 2 => 2 px thick shadow band.
     */
    private static final int SHAPE_SHADOW_EDGE_MAX_RADIUS = 3;

    /**
     * Highlight colour (outside, in the highlight direction).
     *
     *  HEX:  #D36A62
     *  ARGB: 0xFFD36A62
     */
    private static final int SHAPE_HIGHLIGHT_COLOR = 0xFFd36a62;

    /**
     * Shadow colour (outside, in the shadow direction).
     *
     *  HEX:  #832134
     *  ARGB: 0xFF832134
     */
    private static final int SHAPE_SHADOW_COLOR = 0xFF832134;

    /**
     * Direction of the highlight band relative to the shapes.
     * Uses same mapping as shadow, but independent:
     * 1 = NORTH      -> highlight extends downward (from top edge)
     * 2 = EAST       -> highlight extends leftward  (from right edge)
     * 3 = SOUTH      -> highlight extends upward   (from bottom edge)
     * 4 = WEST       -> highlight extends rightward(from left edge)
     * 5 = NORTH_EAST -> highlight extends down-left
     * 6 = SOUTH_EAST -> highlight extends up-left   (top-left of sigil)
     * 7 = SOUTH_WEST -> highlight extends up-right
     * 8 = NORTH_WEST -> highlight extends down-right
     *
     * For a band at the **top-left** of the sigil, we use 6 (up-left).
     */
    private static final int SHAPE_HIGHLIGHT_DIRECTION = 6;

    /**
     * Direction of the shadow band relative to the shapes.
     * 0 = omni-directional (unused here)
     * 1 = NORTH      -> shadow extends downward
     * 2 = EAST       -> shadow extends leftward
     * 3 = SOUTH      -> shadow extends upward
     * 4 = WEST       -> shadow extends rightward
     * 5 = NORTH_EAST -> shadow extends down-left
     * 6 = SOUTH_EAST -> shadow extends up-left
     * 7 = SOUTH_WEST -> shadow extends up-right
     * 8 = NORTH_WEST -> shadow extends down-right
     */
    private static final int SHAPE_SHADOW_DIRECTION = 8;

    // ---------------------------------------------------------------------
    // State & widgets
    // ---------------------------------------------------------------------

    private MultiLineScrollTextWidget secretField;
    private Button etchingsButton;
    private Button styleButton;
    private Button carveButton;
    private AbstractWidget scaleButton;

    private int currentSlices = 6; // default
    private int currentStyleIndex = 0; // "Medieval"

    private final RandomSource random = RandomSource.createNewThreadLocalInstance();

    // Simple client-side particle list for the "Carve" effect
    private final List<SigilEtchingParticle> particles = new ArrayList<>();

    // Sigil pattern currently shown in the preview.
    private SigilPattern currentPattern;
    private boolean[][] currentDiscMask;   // wavy main disc mask (used only as a clip)
    private boolean[][] currentShapeMask;  // replicated shape mask
    private int currentPatternSize;
    private String lastSecretForSigil = "";

    public SealStampScreen(@NotNull SealStampMenu menu,
                           @NotNull Inventory playerInventory,
                           @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;

        // Hide vanilla container labels; we draw our own (minimal).
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
        this.inventoryLabelX = 10000;
        this.inventoryLabelY = 10000;

        LOG.debug(
                "[SealStampScreen] ctor: SIGIL_BASE_DIAMETER_PIXELS={} SIGIL_RADIUS_SCALE={} -> SIGIL_RADIUS={} | WAX_SEAL_SCREEN_SCALE={} WAX_W={} WAX_H={} | highlightEnabled={} highlightRadius={} highlightDir={} | shadowEnabled={} shadowRadius={} shadowDir={}",
                SIGIL_BASE_DIAMETER_PIXELS,
                SIGIL_RADIUS_SCALE,
                SIGIL_RADIUS,
                WAX_SEAL_SCREEN_SCALE,
                WAX_SEAL_SCREEN_WIDTH,
                WAX_SEAL_SCREEN_HEIGHT,
                SHAPE_ENABLE_HIGHLIGHT,
                SHAPE_HIGHLIGHT_EDGE_MAX_RADIUS,
                SHAPE_HIGHLIGHT_DIRECTION,
                SHAPE_ENABLE_SHADOW,
                SHAPE_SHADOW_EDGE_MAX_RADIUS,
                SHAPE_SHADOW_DIRECTION
        );
    }

    // ---------------------------------------------------------------------
    // Init
    // ---------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();

        LOG.debug("[SealStampScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);

        this.clearWidgets();
        this.renderables.clear();
        this.children().clear();

        // Center of GUI for secret field placement
        int guiCenterX = this.leftPos + this.imageWidth / 2;

        // -----------------------------------------------------------------
        // Secret field (MultiLineScrollTextWidget with Gothic font)
        // -----------------------------------------------------------------
        int secretX = guiCenterX + SECRET_X_OFFSET - (SECRET_WIDTH / 2);
        int secretY = this.topPos + SECRET_Y;

        this.secretField = new MultiLineScrollTextWidget(
                this.font,
                secretX,
                secretY,
                SECRET_WIDTH,
                SECRET_HEIGHT,
                SECRET_MAX_CHARS,
                SECRET_MAX_LINES,
                gothic("Secret passphrase")
        );
        this.secretField.setCustomFontId(GOTHIC_FONT_ID);
        this.secretField.setEditable(true);

        this.addRenderableWidget(this.secretField);

        // -----------------------------------------------------------------
        // Left column controls (Etchings / Style / Carve / Scale)
        // -----------------------------------------------------------------
        int colX = this.leftPos + LEFT_COLUMN_X;
        int rowY = this.topPos + LEFT_COLUMN_FIRST_Y;

        // Etchings button
        this.etchingsButton = Button.builder(
                        Component.empty(),
                        b -> cycleSlices()
                )
                .bounds(colX, rowY, DROPDOWN_WIDTH, DROPDOWN_HEIGHT)
                .build();
        this.addRenderableWidget(this.etchingsButton);

        rowY += DROPDOWN_HEIGHT + CONTROL_VERTICAL_GAP;

        // Style button
        this.styleButton = Button.builder(
                        Component.empty(),
                        b -> cycleStyle()
                )
                .bounds(colX, rowY, DROPDOWN_WIDTH, DROPDOWN_HEIGHT)
                .build();
        this.addRenderableWidget(this.styleButton);

        rowY += DROPDOWN_HEIGHT + CONTROL_VERTICAL_GAP;

        // Carve button
        this.carveButton = Button.builder(
                        Component.empty(),
                        b -> onCarveClicked()
                )
                .bounds(colX, rowY, CARVE_WIDTH, CARVE_HEIGHT)
                .build();
        this.addRenderableWidget(this.carveButton);

        rowY += CARVE_HEIGHT + CONTROL_VERTICAL_GAP;

        // GUI scale button (vanilla OptionInstance button)
        try {
            Minecraft mc = Minecraft.getInstance();
            var options = mc.options;

            this.scaleButton = options.guiScale().createButton(
                    options,
                    colX,
                    rowY,
                    SCALE_WIDTH
            );
            this.addRenderableWidget(this.scaleButton);

            LOG.debug("[SealStampScreen] Added GUI scale button at x={}, y={}", colX, rowY);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] Failed to create GUI scale button", t);
            this.scaleButton = null;
        }

        updateButtonLabels();
        updateScaleButtonLabelFromOptions();

        // Initial sigil generation
        try {
            this.lastSecretForSigil = this.secretField != null ? safeString(this.secretField.getText()) : "";
            regenerateSigilPattern();
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] Initial sigil generation failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Button label helpers
    // ---------------------------------------------------------------------

    private void updateButtonLabels() {
        try {
            if (this.etchingsButton != null) {
                this.etchingsButton.setMessage(gothic("Etchings: " + currentSlices));
            }
            if (this.styleButton != null) {
                String styleName = STYLE_NAMES[Math.max(0, Math.min(currentStyleIndex, STYLE_NAMES.length - 1))];
                this.styleButton.setMessage(gothic("Style: " + styleName));
            }
            if (this.carveButton != null) {
                this.carveButton.setMessage(gothic("Carve"));
            }
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] updateButtonLabels failed", t);
        }
    }

    private void updateScaleButtonLabelFromOptions() {
        if (this.scaleButton == null) {
            return;
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) {
                return;
            }

            int scale = mc.options.guiScale().get(); // 0 = Auto
            String scaleLabel = (scale == 0) ? "Auto" : Integer.toString(scale);

            this.scaleButton.setMessage(gothic("Scale: " + scaleLabel));
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] updateScaleButtonLabelFromOptions failed", t);
        }
    }

    private void cycleSlices() {
        try {
            currentSlices++;
            if (currentSlices > MAX_SLICES) {
                currentSlices = MIN_SLICES;
            }
            LOG.debug("[SealStampScreen] cycleSlices -> {}", currentSlices);
            updateButtonLabels();
            regenerateSigilPattern();
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] cycleSlices failed", t);
        }
    }

    private void cycleStyle() {
        try {
            currentStyleIndex++;
            if (currentStyleIndex >= STYLE_NAMES.length) {
                currentStyleIndex = 0;
            }
            LOG.debug("[SealStampScreen] cycleStyle -> {} ({})",
                    currentStyleIndex, STYLE_NAMES[currentStyleIndex]);
            updateButtonLabels();
            regenerateSigilPattern();
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] cycleStyle failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Sigil regeneration
    // ---------------------------------------------------------------------

    private void regenerateSigilPattern() {
        try {
            String secret = "";
            if (this.secretField != null) {
                secret = safeString(this.secretField.getText());
            }

            int slices = currentSlices;
            int shapeSetIndex = Math.max(0, Math.min(currentStyleIndex, STYLE_NAMES.length - 1));

            long seed = SealSigilGenerator.computeSeedFromSecretOnly(secret);
            int radius = SIGIL_RADIUS;

            SigilPattern pattern = SealSigilGenerator.generateFromSeed(
                    seed,
                    radius,
                    slices,
                    shapeSetIndex
            );

            this.currentPattern = pattern;
            this.currentPatternSize = pattern.getSize();
            this.currentDiscMask = pattern.getDiscMask();
            this.currentShapeMask = pattern.getShapeMask();
            this.lastSecretForSigil = secret;

            int discCount = countTrue(currentDiscMask);
            int shapeCount = countTrue(currentShapeMask);

            LOG.debug(
                    "[SealStampScreen] regenerateSigilPattern: seed={} radius={} size={} slices={} shapeSetIndex={} discPixels={} shapePixels={}",
                    seed,
                    radius,
                    currentPatternSize,
                    slices,
                    shapeSetIndex,
                    discCount,
                    shapeCount
            );
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] regenerateSigilPattern failed", t);
            this.currentPattern = null;
            this.currentDiscMask = null;
            this.currentShapeMask = null;
            this.currentPatternSize = 0;
        }
    }

    private int countTrue(boolean[][] mask) {
        if (mask == null || mask.length == 0) {
            return 0;
        }
        int count = 0;
        try {
            for (boolean[] row : mask) {
                if (row == null) continue;
                for (boolean b : row) {
                    if (b) count++;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] countTrue failed", t);
        }
        return count;
    }

    // ---------------------------------------------------------------------
    // Carve button behaviour (particles only for now)
    // ---------------------------------------------------------------------

    private void onCarveClicked() {
        try {
            LOG.info("[SealStampScreen] Carve clicked. slices={} style={} secret='{}'",
                    currentSlices,
                    STYLE_NAMES[Math.max(0, Math.min(currentStyleIndex, STYLE_NAMES.length - 1))],
                    secretField != null ? safeString(secretField.getText()) : "<null>");

            spawnCarveParticles(80);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] onCarveClicked failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        try {
            if (this.secretField != null) {
                this.secretField.tick();

                String currentSecret = safeString(this.secretField.getText());
                if (!currentSecret.equals(this.lastSecretForSigil)) {
                    LOG.debug("[SealStampScreen] Secret changed; regenerating sigil");
                    regenerateSigilPattern();
                }
            }

            if (!this.particles.isEmpty()) {
                for (int i = this.particles.size() - 1; i >= 0; i--) {
                    SigilEtchingParticle p = this.particles.get(i);
                    p.tick();
                    if (!p.isAlive()) {
                        this.particles.remove(i);
                    }
                }
            }

            updateScaleButtonLabelFromOptions();
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] containerTick failed", t);
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
            guiGraphics.fill(
                    this.leftPos,
                    this.topPos,
                    this.leftPos + this.imageWidth,
                    this.topPos + this.imageHeight,
                    0xC0F5F0D8
            );
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderBg failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Foreground rendering (preview, labels, particles)
    // ---------------------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            super.render(guiGraphics, mouseX, mouseY, partialTick);

            renderPreviewArea(guiGraphics);
            renderParticles(guiGraphics, partialTick);

            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] render failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Intentionally empty.
    }

    private void renderPreviewArea(@NotNull GuiGraphics guiGraphics) {
        try {
            int previewX = this.leftPos + this.imageWidth - PREVIEW_RIGHT_MARGIN - PREVIEW_WIDTH;
            int previewY = this.topPos + PREVIEW_TOP_Y;

            Component label = gothic("Sigil Preview");
            int labelWidth = this.font.width(label);
            int labelX = previewX + (PREVIEW_WIDTH - labelWidth) / 2;
            int labelY = previewY + PREVIEW_LABEL_OFFSET_Y;

            guiGraphics.drawString(this.font, label, labelX, labelY, 0xFF000000, false);

            // Soft background
            guiGraphics.fill(
                    previewX,
                    previewY,
                    previewX + PREVIEW_WIDTH,
                    previewY + PREVIEW_HEIGHT,
                    0x20FFFFFF
            );

            // Border
            guiGraphics.fill(previewX, previewY, previewX + PREVIEW_WIDTH, previewY + 1, 0x80000000);
            guiGraphics.fill(
                    previewX,
                    previewY + PREVIEW_HEIGHT - 1,
                    previewX + PREVIEW_WIDTH,
                    previewY + PREVIEW_HEIGHT,
                    0x80000000
            );
            guiGraphics.fill(previewX, previewY, previewX + 1, previewX + PREVIEW_HEIGHT, 0x80000000);
            guiGraphics.fill(previewX + PREVIEW_WIDTH - 1, previewY, previewX + PREVIEW_WIDTH, previewY + PREVIEW_HEIGHT, 0x80000000);

            int centerX = previewX + PREVIEW_WIDTH / 2;
            int centerY = previewY + PREVIEW_HEIGHT / 2;

            renderWaxSeal(guiGraphics, centerX, centerY);
            renderSigilGlyph(guiGraphics, centerX, centerY, previewX, previewY);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderPreviewArea failed", t);
        }
    }

    private void renderWaxSeal(@NotNull GuiGraphics guiGraphics, int centerX, int centerY) {
        try {
            int sealWidth = WAX_SEAL_SCREEN_WIDTH;
            int sealHeight = WAX_SEAL_SCREEN_HEIGHT;

            int sealX = centerX - sealWidth / 2;
            int sealY = centerY - sealHeight / 2;

            var resourceManager = Minecraft.getInstance().getResourceManager();
            boolean hasTexture = false;
            try {
                hasTexture = resourceManager.getResource(WAX_SEAL_TEXTURE).isPresent();
            } catch (Throwable t) {
                LOG.error("[SealStampScreen] renderWaxSeal: resource lookup failed", t);
            }

            if (hasTexture) {
                guiGraphics.blit(
                        WAX_SEAL_TEXTURE,
                        sealX,
                        sealY,
                        sealWidth,
                        sealHeight,
                        0,
                        0,
                        WAX_SEAL_TEXTURE_WIDTH,
                        WAX_SEAL_TEXTURE_HEIGHT,
                        WAX_SEAL_TEXTURE_WIDTH,
                        WAX_SEAL_TEXTURE_HEIGHT
                );
            } else {
                // Fallback: simple golden circle if texture missing.
                int radius = Math.min(sealWidth, sealHeight) / 2;
                if (radius <= 0) {
                    return;
                }
                int rSq = radius * radius;
                for (int dy = -radius; dy <= radius; dy++) {
                    int dySq = dy * dy;
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (dx * dx + dySq <= rSq) {
                            int x = centerX + dx;
                            int y = centerY + dy;
                            guiGraphics.fill(x, y, x + 1, y + 1, GOLD_DISC_COLOR);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderWaxSeal failed", t);
        }
    }

    private void renderSigilGlyph(@NotNull GuiGraphics guiGraphics,
                                  int centerX,
                                  int centerY,
                                  int previewX,
                                  int previewY) {
        try {
            if (this.currentPatternSize <= 0) {
                return;
            }
            if (this.currentShapeMask == null) {
                return;
            }

            // Ensure shader colour is sane so our ARGB colours aren't multiplied to black.
            resetShaderColorForGui();

            int sigilRadius = SIGIL_RADIUS;
            if (sigilRadius <= 0) {
                return;
            }
            int sigilRadiusSq = sigilRadius * sigilRadius;

            int size = this.currentPatternSize;
            int patternRadius = size / 2;

            int boxLeft = previewX;
            int boxTop = previewY;
            int boxRight = previewX + PREVIEW_WIDTH;
            int boxBottom = previewY + PREVIEW_HEIGHT;

            boolean[][] disc = this.currentDiscMask;
            boolean[][] shapes = this.currentShapeMask;

            // -----------------------------------------------------------------
            // Compute highlight/shadow pixels OUTSIDE the shapes
            // -----------------------------------------------------------------

            boolean[][] highlightPixels = (SHAPE_ENABLE_HIGHLIGHT ? new boolean[size][size] : null);
            boolean[][] shadowPixels = (SHAPE_ENABLE_SHADOW ? new boolean[size][size] : null);

            int maxHighlightRadius = (SHAPE_ENABLE_HIGHLIGHT
                    ? Math.max(1, SHAPE_HIGHLIGHT_EDGE_MAX_RADIUS)
                    : 0);
            int maxShadowRadius = (SHAPE_ENABLE_SHADOW
                    ? Math.max(1, SHAPE_SHADOW_EDGE_MAX_RADIUS)
                    : 0);

            int[] shadowDir = directionToUnitOffset(SHAPE_SHADOW_DIRECTION);
            int sxDir = shadowDir[0];
            int syDir = shadowDir[1];

            int[] highlightDir = directionToUnitOffset(SHAPE_HIGHLIGHT_DIRECTION);
            int hxDir = highlightDir[0];
            int hyDir = highlightDir[1];

            try {
                for (int py = 0; py < size; py++) {
                    boolean[] shapeRow = shapes[py];
                    if (shapeRow == null) continue;

                    boolean[] discRow = (disc != null && py >= 0 && py < disc.length) ? disc[py] : null;

                    for (int px = 0; px < size; px++) {
                        if (!shapeRow[px]) {
                            continue;
                        }

                        // Optional clipping of shapes to the disc mask
                        if (discRow != null && !discRow[px]) {
                            continue;
                        }

                        // --- Highlight: outside the shape in its own configured direction (or omni) ---
                        if (SHAPE_ENABLE_HIGHLIGHT && highlightPixels != null && maxHighlightRadius > 0) {
                            if (SHAPE_HIGHLIGHT_DIRECTION == 0) {
                                // Omni-directional highlight ring around the shape (within Chebyshev radius).
                                for (int oy = -maxHighlightRadius; oy <= maxHighlightRadius; oy++) {
                                    for (int ox = -maxHighlightRadius; ox <= maxHighlightRadius; ox++) {
                                        if (ox == 0 && oy == 0) {
                                            continue;
                                        }
                                        // Chebyshev distance: square ring, not diamond.
                                        if (Math.max(Math.abs(ox), Math.abs(oy)) > maxHighlightRadius) {
                                            continue;
                                        }

                                        int nx = px + ox;
                                        int ny = py + oy;

                                        if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
                                            continue;
                                        }

                                        boolean[] shapeRowN = shapes[ny];
                                        boolean insideShapeNeighbor =
                                                shapeRowN != null && nx < shapeRowN.length && shapeRowN[nx];
                                        if (insideShapeNeighbor) {
                                            // Only want pixels OUTSIDE the shape.
                                            continue;
                                        }

                                        boolean[] discRowN = (disc != null && ny >= 0 && ny < disc.length) ? disc[ny] : null;
                                        if (discRowN != null) {
                                            if (nx < 0 || nx >= discRowN.length || !discRowN[nx]) {
                                                // Must stay inside the disc mask if present.
                                                continue;
                                            }
                                        }

                                        highlightPixels[ny][nx] = true;
                                    }
                                }
                            } else {
                                // Directional highlight: cast a ray along (hxDir, hyDir).
                                for (int r = 1; r <= maxHighlightRadius; r++) {
                                    int nx = px + hxDir * r;
                                    int ny = py + hyDir * r;

                                    if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
                                        break;
                                    }

                                    boolean[] shapeRowN = shapes[ny];
                                    boolean insideShapeNeighbor =
                                            shapeRowN != null && nx < shapeRowN.length && shapeRowN[nx];

                                    // If neighbour in highlight direction is still inside the shape,
                                    // this is not an outer edge in this direction → stop for this ray.
                                    if (insideShapeNeighbor) {
                                        break;
                                    }

                                    boolean[] discRowN = (disc != null && ny >= 0 && ny < disc.length) ? disc[ny] : null;
                                    if (discRowN != null) {
                                        if (nx < 0 || nx >= discRowN.length || !discRowN[nx]) {
                                            // Outside disc – stop extending this ray.
                                            break;
                                        }
                                    }

                                    // Outside the shape + inside disc → mark as highlight pixel.
                                    highlightPixels[ny][nx] = true;
                                }
                            }
                        }

                        // --- Shadow: outside the shape along its own configured direction (or omni) ---
                        if (SHAPE_ENABLE_SHADOW && shadowPixels != null && maxShadowRadius > 0) {
                            if (SHAPE_SHADOW_DIRECTION == 0) {
                                // Omni-directional shadow ring around the shape (within Chebyshev radius).
                                for (int oy = -maxShadowRadius; oy <= maxShadowRadius; oy++) {
                                    for (int ox = -maxShadowRadius; ox <= maxShadowRadius; ox++) {
                                        if (ox == 0 && oy == 0) {
                                            continue;
                                        }
                                        // Chebyshev distance: square ring.
                                        if (Math.max(Math.abs(ox), Math.abs(oy)) > maxShadowRadius) {
                                            continue;
                                        }

                                        int nx = px + ox;
                                        int ny = py + oy;

                                        if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
                                            continue;
                                        }

                                        boolean[] shapeRowN = shapes[ny];
                                        boolean insideShapeNeighbor =
                                                shapeRowN != null && nx < shapeRowN.length && shapeRowN[nx];
                                        if (insideShapeNeighbor) {
                                            // Only want pixels OUTSIDE the shape.
                                            continue;
                                        }

                                        boolean[] discRowN = (disc != null && ny >= 0 && ny < disc.length) ? disc[ny] : null;
                                        if (discRowN != null) {
                                            if (nx < 0 || nx >= discRowN.length || !discRowN[nx]) {
                                                // Must stay inside the disc mask if present.
                                                continue;
                                            }
                                        }

                                        shadowPixels[ny][nx] = true;
                                    }
                                }
                            } else {
                                // Directional shadow: cast a ray along (sxDir, syDir).
                                for (int r = 1; r <= maxShadowRadius; r++) {
                                    int nx = px + sxDir * r;
                                    int ny = py + syDir * r;

                                    if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
                                        break;
                                    }

                                    boolean[] shapeRowN = shapes[ny];
                                    boolean insideShapeNeighbor =
                                            shapeRowN != null && nx < shapeRowN.length && shapeRowN[nx];

                                    if (insideShapeNeighbor) {
                                        // Still inside shape in shadow direction → not an outer edge in
                                        // this direction for this pixel; stop this ray.
                                        break;
                                    }

                                    boolean[] discRowN = (disc != null && ny >= 0 && ny < disc.length) ? disc[ny] : null;
                                    if (discRowN != null) {
                                        if (nx < 0 || nx >= discRowN.length || !discRowN[nx]) {
                                            // Outside disc – stop extending this ray.
                                            break;
                                        }
                                    }

                                    // Outside shape + inside disc → mark as shadow pixel.
                                    shadowPixels[ny][nx] = true;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.error("[SealStampScreen] renderSigilGlyph: edge analysis (highlight/shadow) failed", t);
            }

            // -----------------------------------------------------------------
            // Pass 1: base fill for all shape pixels
            // -----------------------------------------------------------------
            try {
                for (int py = 0; py < size; py++) {
                    boolean[] shapeRow = shapes[py];
                    if (shapeRow == null) continue;

                    boolean[] discRow = (disc != null && py >= 0 && py < disc.length) ? disc[py] : null;

                    for (int px = 0; px < size; px++) {
                        if (!shapeRow[px]) {
                            continue;
                        }

                        // Optional clipping of interior to disc.
                        if (discRow != null && !discRow[px]) {
                            continue;
                        }

                        int dx = px - patternRadius;
                        int dy = py - patternRadius;
                        int distSq = dx * dx + dy * dy;
                        if (distSq > sigilRadiusSq) {
                            continue;
                        }

                        int sx = centerX + dx;
                        int sy = centerY + dy;
                        if (sx < boxLeft || sy < boxTop || sx >= boxRight || sy >= boxBottom) {
                            continue;
                        }

                        guiGraphics.fill(sx, sy, sx + 1, sy + 1, SHAPE_FILL_COLOR);
                    }
                }
            } catch (Throwable t) {
                LOG.error("[SealStampScreen] renderSigilGlyph: base shape fill failed", t);
            }

            // -----------------------------------------------------------------
            // Pass 2: highlight overlay (outside shapes)
            // -----------------------------------------------------------------
            if (SHAPE_ENABLE_HIGHLIGHT && highlightPixels != null) {
                try {
                    for (int py = 0; py < size; py++) {
                        boolean[] row = highlightPixels[py];
                        if (row == null) continue;

                        for (int px = 0; px < size; px++) {
                            if (!row[px]) {
                                continue;
                            }

                            int dx = px - patternRadius;
                            int dy = py - patternRadius;
                            int distSq = dx * dx + dy * dy;
                            if (distSq > sigilRadiusSq) {
                                continue;
                            }

                            int sx = centerX + dx;
                            int sy = centerY + dy;
                            if (sx < boxLeft || sy < boxTop || sx >= boxRight || sy >= boxBottom) {
                                continue;
                            }

                            guiGraphics.fill(sx, sy, sx + 1, sy + 1, SHAPE_HIGHLIGHT_COLOR);
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("[SealStampScreen] renderSigilGlyph: highlight overlay failed", t);
                }
            }

            // -----------------------------------------------------------------
            // Pass 3: shadow overlay (outside shapes)
            // -----------------------------------------------------------------
            if (SHAPE_ENABLE_SHADOW && shadowPixels != null) {
                try {
                    for (int py = 0; py < size; py++) {
                        boolean[] row = shadowPixels[py];
                        if (row == null) continue;

                        for (int px = 0; px < size; px++) {
                            if (!row[px]) {
                                continue;
                            }

                            int dx = px - patternRadius;
                            int dy = py - patternRadius;
                            int distSq = dx * dx + dy * dy;
                            if (distSq > sigilRadiusSq) {
                                continue;
                            }

                            int sx = centerX + dx;
                            int sy = centerY + dy;
                            if (sx < boxLeft || sy < boxTop || sx >= boxRight || sy >= boxBottom) {
                                continue;
                            }

                            guiGraphics.fill(sx, sy, sx + 1, sy + 1, SHAPE_SHADOW_COLOR);
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("[SealStampScreen] renderSigilGlyph: shadow overlay failed", t);
                }
            }

        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderSigilGlyph failed", t);
        }
    }

    /**
     * Ensure the GUI shader colour is reset to full white with blending enabled, so our
     * ARGB colours are not multiplied to black by whatever came before.
     */
    private static void resetShaderColorForGui() {
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable t) {
            // Never crash rendering just because reset failed.
            LOG.error("[SealStampScreen] resetShaderColorForGui failed", t);
        }
    }

    /**
     * Convert our 1..8 direction index into a unit (dx, dy) offset representing
     * the direction in which the band "extends".
     *
     * This is used for both the shadow direction and the highlight direction.
     */
    private static int[] directionToUnitOffset(int dir) {
        int dx;
        int dy;

        switch (dir) {
            case 1 -> { // NORTH: band extends downward
                dx = 0;
                dy = 1;
            }
            case 2 -> { // EAST: band extends leftward
                dx = -1;
                dy = 0;
            }
            case 3 -> { // SOUTH: band extends upward
                dx = 0;
                dy = -1;
            }
            case 4 -> { // WEST: band extends rightward
                dx = 1;
                dy = 0;
            }
            case 5 -> { // NORTH_EAST: band extends down-left
                dx = -1;
                dy = 1;
            }
            case 6 -> { // SOUTH_EAST: band extends up-left
                dx = -1;
                dy = -1;
            }
            case 7 -> { // SOUTH_WEST: band extends up-right
                dx = 1;
                dy = -1;
            }
            case 8 -> { // NORTH_WEST: band extends down-right
                dx = 1;
                dy = 1;
            }
            default -> {
                dx = 1;
                dy = 1;
            }
        }

        return new int[]{dx, dy};
    }

    // ---------------------------------------------------------------------
    // Particles
    // ---------------------------------------------------------------------

    private void spawnCarveParticles(int count) {
        try {
            if (count <= 0) {
                return;
            }

            int previewX = this.leftPos + this.imageWidth - PREVIEW_RIGHT_MARGIN - PREVIEW_WIDTH;
            int previewY = this.topPos + PREVIEW_TOP_Y;
            int centerX = previewX + PREVIEW_WIDTH / 2;
            int centerY = previewY + PREVIEW_HEIGHT / 2;
            int radius = SIGIL_RADIUS;

            if (radius <= 0) {
                return;
            }

            for (int i = 0; i < count; i++) {
                SigilEtchingParticle p = SigilEtchingParticle.createForCarve(
                        this.random,
                        centerX,
                        centerY,
                        radius
                );
                if (p != null) {
                    this.particles.add(p);
                }
            }

            LOG.debug("[SealStampScreen] spawnCarveParticles: spawned {} particles (total now {})",
                    count, this.particles.size());
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] spawnCarveParticles failed", t);
        }
    }

    private void renderParticles(@NotNull GuiGraphics guiGraphics, float partialTick) {
        try {
            if (this.particles.isEmpty()) {
                return;
            }

            for (SigilEtchingParticle p : this.particles) {
                p.render(guiGraphics, partialTick);
            }
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderParticles failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Input handling (E key eating like ScrollSealingScreen)
    // ---------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            if (keyCode == GLFW.GLFW_KEY_E ||
                    keyCode == GLFW.GLFW_KEY_R ||
                    keyCode == GLFW.GLFW_KEY_U) {
                LOG.debug("[SealStampScreen] keyPressed: swallowed keyCode={} to avoid closing GUI", keyCode);
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] keyPressed failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Gothic helper
    // ---------------------------------------------------------------------

    private Component gothic(String text) {
        try {
            MutableComponent c = Component.literal(text);
            Style style = c.getStyle().withFont(GOTHIC_FONT_ID);
            c.setStyle(style);
            return c;
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] gothic() failed, falling back to plain text", t);
            return Component.literal(text);
        }
    }

    private String safeString(String s) {
        return (s == null) ? "" : s;
    }
}
