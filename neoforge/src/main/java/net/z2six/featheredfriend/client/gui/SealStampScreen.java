// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SealStampScreen.java
package net.z2six.featheredfriend.client.gui;

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
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SealStampScreen.java
 *
 * SealStampScreen
 *
 * Uses Gothic font + MultiLineScrollTextWidget for the secret passphrase.
 *
 * Left column: Etchings dropdown, Style dropdown, Carve button, GUI-scale toggle button.
 *
 * Right side: "Sigil Preview" area:
 *  - Renders a wax seal texture in the center of the preview box.
 *  - Overlays the generated sigil on top as a semi-transparent black mask.
 *
 * On "Carve" click:
 *  * Spawns a burst of SigilEtchingParticle chips from the sigil area.
 *  * All motion / feel is controlled inside SigilEtchingParticle via createForCarve().
 *
 * Sigil preview:
 *  * Seed is derived from secret only using SealSigilGenerator.computeSeedFromSecretOnly().
 *  * Slices and styleIndex are applied afterwards via generateFromSeed().
 *  * Pattern is regenerated when secret changes or when slices/style buttons are clicked.
 *  * Rendered as 25% opaque black strokes on the wax seal, fitting inside it.
 *
 * Sigil → NBT saving will be wired in a later step.
 */
public class SealStampScreen extends AbstractContainerScreen<SealStampMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    // Gothic font id (same as used by ScrollSealingScreen / MultiLineScrollTextWidget)
    private static final ResourceLocation GOTHIC_FONT_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gothic12");

    // Wax seal texture used for the sigil preview (39x38px)
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

    // Wax seal texture dimensions (matching wax_seal.png)
    private static final int WAX_SEAL_WIDTH = 39;
    private static final int WAX_SEAL_HEIGHT = 38;

    /**
     * Scale factor for rendering the wax seal.
     * Tweak this to make the seal bigger/smaller.
     */
    private static final float WAX_SEAL_SCALE = 2.0f;

    private static final int SCALED_WAX_SEAL_WIDTH = Math.round(WAX_SEAL_WIDTH * WAX_SEAL_SCALE);
    private static final int SCALED_WAX_SEAL_HEIGHT = Math.round(WAX_SEAL_HEIGHT * WAX_SEAL_SCALE);

    /**
     * Fraction of the wax seal diameter used for the sigil radius.
     * 1.0f = reaches the edge of the seal; lower values keep it inset.
     */
    private static final float SIGIL_RADIUS_SCALE = 0.85f;

    /**
     * Sigil radius inside the wax seal, in screen pixels.
     */
    private static final int SIGIL_RADIUS =
            (int) (Math.min(SCALED_WAX_SEAL_WIDTH, SCALED_WAX_SEAL_HEIGHT) * 0.5f * SIGIL_RADIUS_SCALE);

    // ---------------------------------------------------------------------
    // Etching options
    // ---------------------------------------------------------------------

    private static final int MIN_SLICES = 2;
    private static final int MAX_SLICES = 8;

    private static final String[] STYLE_NAMES = {"Medieval", "Fantasy", "Floral"};

    // ---------------------------------------------------------------------
    // Sigil overlay config
    // ---------------------------------------------------------------------

    /**
     * Alpha for the sigil strokes (0..1). 0.25 = 25% opaque black.
     */
    private static final float SIGIL_ALPHA = 0.25f;

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
    private boolean[][] currentPixels;
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
                gothic("Secret passphrase") // placeholder text; no separate label above
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

    /**
     * Regenerate the sigil pattern based on current secret, slices, and style index.
     * Seed is derived from the secret only (Secret -> SHA256 -> Seed), and then
     * slices + shapeSetIndex are fed into generateFromSeed().
     */
    private void regenerateSigilPattern() {
        try {
            String secret = "";
            if (this.secretField != null) {
                secret = safeString(this.secretField.getText());
            }

            int slices = currentSlices;
            int shapeSetIndex = Math.max(0, Math.min(currentStyleIndex, STYLE_NAMES.length - 1));

            // Secret -> SHA256 -> Seed (no slices/style in the seed)
            long seed = SealSigilGenerator.computeSeedFromSecretOnly(secret);

            int radius = SIGIL_RADIUS;

            SigilPattern pattern = SealSigilGenerator.generateFromSeed(
                    seed,
                    radius,
                    slices,
                    shapeSetIndex
            );

            this.currentPattern = pattern;
            this.currentPixels = pattern.getPixels();
            this.currentPatternSize = pattern.getSize();
            this.lastSecretForSigil = secret;

            LOG.debug(
                    "[SealStampScreen] regenerateSigilPattern: seed={} radius={} size={} slices={} shapeSetIndex={}",
                    seed,
                    radius,
                    currentPatternSize,
                    slices,
                    shapeSetIndex
            );
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] regenerateSigilPattern failed", t);
            this.currentPattern = null;
            this.currentPixels = null;
            this.currentPatternSize = 0;
        }
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

            // Lively burst; all motion/feel logic is inside SigilEtchingParticle.
            spawnCarveParticles(80);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] onCarveClicked failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Ticking (for secret field + particles + GUI scale label + sigil)
    // ---------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        try {
            if (this.secretField != null) {
                this.secretField.tick();

                // Detect secret text changes for live sigil updates
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

            // Keep GUI scale button text in sync & gothic-styled
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
        // Intentionally empty; we draw minimal labels in render().
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

            // Soft background behind the preview
            guiGraphics.fill(
                    previewX,
                    previewY,
                    previewX + PREVIEW_WIDTH,
                    previewY + PREVIEW_HEIGHT,
                    0x20FFFFFF
            );

            // Border
            guiGraphics.fill(previewX, previewY, previewX + PREVIEW_WIDTH, previewY + 1, 0x80000000);
            guiGraphics.fill(previewX, previewY + PREVIEW_HEIGHT - 1, previewX + PREVIEW_WIDTH, previewY + PREVIEW_HEIGHT, 0x80000000);
            guiGraphics.fill(previewX, previewY, previewX + 1, previewY + PREVIEW_HEIGHT, 0x80000000);
            guiGraphics.fill(previewX + PREVIEW_WIDTH - 1, previewY, previewX + PREVIEW_WIDTH, previewY + PREVIEW_HEIGHT, 0x80000000);

            // Center of the preview box
            int centerX = previewX + PREVIEW_WIDTH / 2;
            int centerY = previewY + PREVIEW_HEIGHT / 2;

            // 1) Draw wax seal texture in the center (scaled up)
            renderWaxSeal(guiGraphics, centerX, centerY);

            // 2) Render sigil as 25% opaque black on top, centered on the seal
            renderSigilGlyph(guiGraphics, centerX, centerY, previewX, previewY);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderPreviewArea failed", t);
        }
    }

    /**
     * Draw the wax seal texture centered at (centerX, centerY), scaled up.
     */
    private void renderWaxSeal(@NotNull GuiGraphics guiGraphics, int centerX, int centerY) {
        try {
            int sealX = centerX - SCALED_WAX_SEAL_WIDTH / 2;
            int sealY = centerY - SCALED_WAX_SEAL_HEIGHT / 2;

            var resourceManager = Minecraft.getInstance().getResourceManager();
            boolean hasTexture = resourceManager.getResource(WAX_SEAL_TEXTURE).isPresent();

            if (hasTexture) {
                guiGraphics.blit(
                        WAX_SEAL_TEXTURE,
                        sealX,
                        sealY,
                        SCALED_WAX_SEAL_WIDTH,
                        SCALED_WAX_SEAL_HEIGHT,
                        0,
                        0,
                        WAX_SEAL_WIDTH,
                        WAX_SEAL_HEIGHT,
                        WAX_SEAL_WIDTH,
                        WAX_SEAL_HEIGHT
                );
            } else {
                // Fallback: simple wax-colored disc approximating the scaled size
                int radius = Math.min(SCALED_WAX_SEAL_WIDTH, SCALED_WAX_SEAL_HEIGHT) / 2;
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
                            guiGraphics.fill(x, y, x + 1, y + 1, 0xFF9B2F2F);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderWaxSeal failed", t);
        }
    }

    /**
     * Render the sigil pattern as 25% opaque black strokes on top of the wax seal.
     *
     * Inverted vs previous version:
     *  - finalPixels[y][x] == true  => sigil body => we draw a black pixel here.
     *  - finalPixels[y][x] == false => background => we leave just the wax texture.
     *
     * This does NOT mask or cut the wax seal itself; it only overlays pixels.
     */
    private void renderSigilGlyph(@NotNull GuiGraphics guiGraphics,
                                  int centerX,
                                  int centerY,
                                  int previewX,
                                  int previewY) {
        try {
            if (this.currentPixels == null || this.currentPatternSize <= 0) {
                return;
            }

            int sigilRadius = SIGIL_RADIUS;
            if (sigilRadius <= 0) {
                return;
            }
            int sigilRadiusSq = sigilRadius * sigilRadius;

            int patternRadius = this.currentPatternSize / 2;

            int boxLeft = previewX;
            int boxTop = previewY;
            int boxRight = previewX + PREVIEW_WIDTH;
            int boxBottom = previewY + PREVIEW_HEIGHT;

            // Clamp alpha to [0, 1] and convert to 0..255
            float alphaF = Math.max(0.0f, Math.min(1.0f, SIGIL_ALPHA));
            int alpha = (int) (alphaF * 255.0f);
            if (alpha <= 0) {
                return;
            }

            // 0xAA000000 -> semi-transparent black; AA is alpha
            int sigilColor = (alpha << 24);

            for (int py = 0; py < currentPatternSize; py++) {
                boolean[] row;
                try {
                    row = currentPixels[py];
                } catch (Throwable t) {
                    LOG.error("[SealStampScreen] renderSigilGlyph: pixels row OOB at y={}", py, t);
                    continue;
                }

                for (int px = 0; px < currentPatternSize; px++) {
                    boolean wax;
                    try {
                        wax = row[px];
                    } catch (Throwable t) {
                        LOG.error("[SealStampScreen] renderSigilGlyph: pixels cell OOB at {},{}", px, py, t);
                        continue;
                    }

                    // Inverted: we now draw where the sigil pixels are true (body),
                    // and leave holes (false) as untouched wax.
                    if (!wax) {
                        continue;
                    }

                    int dx = px - patternRadius;
                    int dy = py - patternRadius;
                    int distSq = dx * dx + dy * dy;
                    if (distSq > sigilRadiusSq) {
                        // Outside the sigil radius: ignore. This only clips the overlay, not the wax.
                        continue;
                    }

                    int sx = centerX + dx;
                    int sy = centerY + dy;

                    if (sx < boxLeft || sy < boxTop || sx >= boxRight || sy >= boxBottom) {
                        continue;
                    }

                    guiGraphics.fill(sx, sy, sx + 1, sy + 1, sigilColor);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderSigilGlyph failed", t);
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
            // Eat 'E' (inventory), and also 'R' / 'U' (common JEI keys) while our screen is open,
            // so typing into the secret field doesn't close the GUI.
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
