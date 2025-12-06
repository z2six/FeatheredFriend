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
 *      * A configurable disc overlay colour (excluding shape interiors).
 *      * A configurable, directional shadow band around the shapes, clipped to the disc.
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
    // Sigil visual config (colors & shadows)
    // ---------------------------------------------------------------------

    /**
     * Fallback golden background color if the wax texture is missing.
     */
    private static final int GOLD_DISC_COLOR = 0xFFAC3232;

    /**
     * Disc colour (overlay inside the wavy disc, excluding shape interiors).
     * Fully opaque, RGB 68/20/20 -> ARGB = 0xFF441414.
     * One variable: colour + alpha.
     */
    private static final int DISC_COLOR = 0xFFAC3232;

    // --- Shape shadow band -----------------------------------------------

    /**
     * Maximum Chebyshev radius (in pixels) for the shape shadow band.
     * This controls how thick the shadow halo is around shapes.
     * 2px => 2 rings of shadow.
     */
    private static final int SHAPE_SHADOW_MAX_RADIUS = 2;

    /**
     * Shadow colour for the shapes.
     *
     * Desired:
     *  HEX:   #5b1f3a
     *  RGB:   (91, 31, 58)
     *  ARGB:  0xFF5B1F3A
     *
     * We keep alpha dynamic (per-ring), RGB comes from here.
     */
    private static final int SHAPE_SHADOW_COLOR = 0xFF5B1F3A;

    /**
     * Alpha for the first (innermost) shadow ring, 0–255.
     * This is the starting opacity of the shadow (first pixel).
     */
    private static final int SHAPE_SHADOW_FIRST_RING_ALPHA = 0xFF; // 255 (100%)

    /**
     * Per-ring alpha falloff. For each step away from the shape, alpha is reduced
     * by this amount:
     *
     *  ring 1: FIRST_RING_ALPHA
     *  ring 2: FIRST_RING_ALPHA - FALL_OFF
     *
     * With MAX_RADIUS = 2 and FALL_OFF = 0x80, this yields:
     *  ring 1 = 255 (100%)
     *  ring 2 = 127 (~50%)
     */
    private static final int SHAPE_SHADOW_ALPHA_FALLOFF_PER_RING = 0x80;

    /**
     * Direction of the shadow halo around shapes.
     */
    private static final int SHAPE_SHADOW_DIRECTION = 1;

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
    private boolean[][] currentDiscMask;   // wavy main disc mask
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
                "[SealStampScreen] ctor: SIGIL_BASE_DIAMETER_PIXELS={} SIGIL_RADIUS_SCALE={} -> SIGIL_RADIUS={}  | WAX_SEAL_SCREEN_SCALE={} WAX_W={} WAX_H={} | shadowMaxRadius={} firstAlpha={} falloff={} dir={} | discColor=0x{} shadowColor=0x{}",
                SIGIL_BASE_DIAMETER_PIXELS,
                SIGIL_RADIUS_SCALE,
                SIGIL_RADIUS,
                WAX_SEAL_SCREEN_SCALE,
                WAX_SEAL_SCREEN_WIDTH,
                WAX_SEAL_SCREEN_HEIGHT,
                SHAPE_SHADOW_MAX_RADIUS,
                SHAPE_SHADOW_FIRST_RING_ALPHA,
                SHAPE_SHADOW_ALPHA_FALLOFF_PER_RING,
                SHAPE_SHADOW_DIRECTION,
                Integer.toHexString(DISC_COLOR),
                Integer.toHexString(SHAPE_SHADOW_COLOR & 0x00FFFFFF)
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
            guiGraphics.fill(previewX, previewY, previewX + 1, previewY + PREVIEW_HEIGHT, 0x80000000);
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
            if (this.currentDiscMask == null && this.currentShapeMask == null) {
                return;
            }

            // Ensure shader color is sane so our ARGB colours aren't multiplied to black
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

            // 1) Disc colour overlay, excluding shape interiors
            if (disc != null && (DISC_COLOR >>> 24) != 0) {
                try {
                    for (int py = 0; py < size; py++) {
                        boolean[] discRow = disc[py];
                        if (discRow == null) continue;

                        boolean[] shapeRow = (shapes != null && py >= 0 && py < size) ? shapes[py] : null;

                        for (int px = 0; px < size; px++) {
                            if (!discRow[px]) {
                                continue;
                            }

                            // Do not colour inside shapes
                            if (shapeRow != null && shapeRow.length > px && shapeRow[px]) {
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

                            guiGraphics.fill(sx, sy, sx + 1, sy + 1, DISC_COLOR);
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("[SealStampScreen] renderSigilGlyph: disc overlay failed", t);
                }
            }

            // 2) Shape shadow band (directional halo outside shapes)
            if (shapes != null && disc != null && SHAPE_SHADOW_MAX_RADIUS > 0 && SHAPE_SHADOW_FIRST_RING_ALPHA > 0) {
                try {
                    int[][] shapeShadowLevel = new int[size][size];

                    for (int py = 0; py < size; py++) {
                        boolean[] shapeRow = shapes[py];
                        if (shapeRow == null) continue;
                        for (int px = 0; px < size; px++) {
                            if (!shapeRow[px]) {
                                continue;
                            }

                            // For each shape pixel, cast shadow outward in configured direction.
                            for (int oy = -SHAPE_SHADOW_MAX_RADIUS; oy <= SHAPE_SHADOW_MAX_RADIUS; oy++) {
                                int ny = py + oy;
                                if (ny < 0 || ny >= size) continue;
                                boolean[] discRowN = disc[ny];
                                boolean[] shapeRowN = shapes[ny];
                                if (discRowN == null || shapeRowN == null) continue;

                                for (int ox = -SHAPE_SHADOW_MAX_RADIUS; ox <= SHAPE_SHADOW_MAX_RADIUS; ox++) {
                                    int nx = px + ox;
                                    if (nx < 0 || nx >= size) continue;

                                    // Skip offsets that are not in the chosen shadow direction
                                    if (!isOffsetInShadowDirection(ox, oy)) {
                                        continue;
                                    }

                                    if (!discRowN[nx]) {
                                        // Shadow must stay within main disc
                                        continue;
                                    }
                                    if (shapeRowN[nx]) {
                                        // Do not shadow inside shapes
                                        continue;
                                    }

                                    int chebyshev = Math.max(Math.abs(ox), Math.abs(oy));
                                    if (chebyshev <= 0 || chebyshev > SHAPE_SHADOW_MAX_RADIUS) {
                                        continue;
                                    }

                                    int ringIndex = chebyshev;
                                    if (shapeShadowLevel[ny][nx] == 0 || ringIndex < shapeShadowLevel[ny][nx]) {
                                        shapeShadowLevel[ny][nx] = ringIndex;
                                    }
                                }
                            }
                        }
                    }

                    // Render the shadow band
                    for (int py = 0; py < size; py++) {
                        for (int px = 0; px < size; px++) {
                            int ring = shapeShadowLevel[py][px];
                            if (ring <= 0) {
                                continue;
                            }

                            int alpha = SHAPE_SHADOW_FIRST_RING_ALPHA
                                    - (ring - 1) * SHAPE_SHADOW_ALPHA_FALLOFF_PER_RING;
                            if (alpha <= 0) {
                                continue;
                            }
                            if (alpha > 255) alpha = 255;

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

                            int color = ((alpha & 0xFF) << 24) | (SHAPE_SHADOW_COLOR & 0x00FFFFFF);
                            guiGraphics.fill(sx, sy, sx + 1, sy + 1, color);
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("[SealStampScreen] renderSigilGlyph: shape shadow failed", t);
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
     * Determine whether a given offset (ox, oy) from a shape pixel is in the active
     * shadow direction.
     *
     * Screen coordinates: origin at top-left, +X = right, +Y = down.
     *
     * If SHAPE_SHADOW_DIRECTION == 0, we treat shadow as omni-directional and
     * accept all directions.
     */
    private static boolean isOffsetInShadowDirection(int ox, int oy) {
        if (ox == 0 && oy == 0) {
            return false;
        }

        switch (SHAPE_SHADOW_DIRECTION) {
            case 0 -> {
                // Omni-directional halo
                return true;
            }
            case 1 -> {
                // NORTH: shadow extends downward (from top to bottom)
                return oy > 0;
            }
            case 2 -> {
                // EAST: shadow extends leftward
                return ox < 0;
            }
            case 3 -> {
                // SOUTH: shadow extends upward
                return oy < 0;
            }
            case 4 -> {
                // WEST: shadow extends rightward
                return ox > 0;
            }
            case 5 -> {
                // NORTH_EAST: shadow extends down-left
                return oy > 0 && ox < 0;
            }
            case 6 -> {
                // SOUTH_EAST: shadow extends up-left
                return oy < 0 && ox < 0;
            }
            case 7 -> {
                // SOUTH_WEST: shadow extends up-right
                return oy < 0 && ox > 0;
            }
            case 8 -> {
                // NORTH_WEST: shadow extends down-right
                return oy > 0 && ox > 0;
            }
            default -> {
                // Fallback: omni-directional if direction is out of range
                return true;
            }
        }
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
