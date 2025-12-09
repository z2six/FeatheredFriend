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
import net.neoforged.neoforge.network.PacketDistributor;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.content.item.SealStampItem;
import net.z2six.featheredfriend.content.seal.SealStampCarveLogic;
import net.z2six.featheredfriend.neoforge.menu.SealStampMenu;
import net.z2six.featheredfriend.network.SealStampCarveResultPacket;
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
 * This version refactors the sigil rendering into WaxSealVisualizer while preserving
 * visuals and behaviour. All GUI logic, inputs, particles, networking, and layout
 * remain unchanged.
 */
public class SealStampScreen extends AbstractContainerScreen<SealStampMenu> {

    private static final Logger LOG = LogUtils.getLogger();

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
     * Screen scale factor for the wax seal preview (forwarded to WaxSealVisualizer).
     * This ONLY affects the wax seal, not the sigil size.
     */
    private static final float WAX_SEAL_SCREEN_SCALE = 3.0f;

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
    // Sigil visual config (kept here for logging parity; visuals come from WaxSealVisualizer)
    // ---------------------------------------------------------------------

    /**
     * These values match the prior in-class constants and are applied to the
     * WaxSealVisualizer instance to preserve exact visuals.
     */
    private static final int SHAPE_FILL_COLOR = 0xFFb83e3e;
    private static final boolean SHAPE_ENABLE_HIGHLIGHT = true;
    private static final boolean SHAPE_ENABLE_SHADOW = true;
    private static final int SHAPE_HIGHLIGHT_EDGE_MAX_RADIUS = 1;
    private static final int SHAPE_SHADOW_EDGE_MAX_RADIUS = 3;
    private static final int SHAPE_HIGHLIGHT_COLOR = 0xFFd36a62;
    private static final int SHAPE_SHADOW_COLOR = 0xFF832134;
    private static final int SHAPE_HIGHLIGHT_DIRECTION = 6;
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

    // Carve orchestration + stamp slot binding
    private final SealStampCarveLogic carveLogic = new SealStampCarveLogic();
    private boolean carveButtonDisabled = false;
    private final int stampSlot;

    // NEW: centralized visualizer for wax + sigil
    private final WaxSealVisualizer visualizer = new WaxSealVisualizer()
            .setShapeFillColor(SHAPE_FILL_COLOR)
            .setShapeEnableHighlight(SHAPE_ENABLE_HIGHLIGHT)
            .setShapeEnableShadow(SHAPE_ENABLE_SHADOW)
            .setShapeHighlightEdgeMaxRadius(SHAPE_HIGHLIGHT_EDGE_MAX_RADIUS)
            .setShapeShadowEdgeMaxRadius(SHAPE_SHADOW_EDGE_MAX_RADIUS)
            .setShapeHighlightColor(SHAPE_HIGHLIGHT_COLOR)
            .setShapeShadowColor(SHAPE_SHADOW_COLOR)
            .setShapeHighlightDirection(SHAPE_HIGHLIGHT_DIRECTION)
            .setShapeShadowDirection(SHAPE_SHADOW_DIRECTION);

    public SealStampScreen(@NotNull SealStampMenu menu,
                           @NotNull Inventory playerInventory,
                           @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;

        // Bind stamp slot from menu so we know which stack to update on server.
        this.stampSlot = menu.getStampSlotIndex();

        // Hide vanilla container labels; we draw our own (minimal).
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
        this.inventoryLabelX = 10000;
        this.inventoryLabelY = 10000;

        LOG.debug(
                "[SealStampScreen] ctor: SIGIL_BASE_DIAMETER_PIXELS={} SIGIL_RADIUS_SCALE={} -> SIGIL_RADIUS={} | WAX_SEAL_SCREEN_SCALE={} | highlightEnabled={} highlightRadius={} highlightDir={} | shadowEnabled={} shadowRadius={} shadowDir={} | stampSlot={}",
                SIGIL_BASE_DIAMETER_PIXELS,
                SIGIL_RADIUS_SCALE,
                SIGIL_RADIUS,
                WAX_SEAL_SCREEN_SCALE,
                SHAPE_ENABLE_HIGHLIGHT,
                SHAPE_HIGHLIGHT_EDGE_MAX_RADIUS,
                SHAPE_HIGHLIGHT_DIRECTION,
                SHAPE_ENABLE_SHADOW,
                SHAPE_SHADOW_EDGE_MAX_RADIUS,
                SHAPE_SHADOW_DIRECTION,
                this.stampSlot
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
                this.carveButton.active = !carveButtonDisabled;
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
    // Carve button behaviour (animation + networking)
    // ---------------------------------------------------------------------

    private void onCarveClicked() {
        try {
            if (carveLogic.isCarving()) {
                LOG.debug("[SealStampScreen] Carve click ignored: carve animation already active");
                return;
            }

            if (this.carveButton == null) {
                LOG.warn("[SealStampScreen] Carve clicked but carveButton is null");
                return;
            }

            String secret = safeString(secretField != null ? secretField.getText() : "");
            long seed = SealSigilGenerator.computeSeedFromSecretOnly(secret);
            int slices = currentSlices;
            int style = currentStyleIndex;

            // Compute the actual slot that currently holds a SealStampItem.
            // This is what will actually be sent to the server when the carve finishes.
            int effectiveSlot = computeSealStampSlot(Minecraft.getInstance());

            LOG.info("[SealStampScreen] Carve START → menuStampSlot={} effectiveStampSlot={} seed={} slices={} style={} secret='{}'",
                    this.stampSlot, effectiveSlot, seed, slices, style, secret);

            // Disable button for the duration of the carve animation.
            carveButtonDisabled = true;
            this.carveButton.active = false;

            carveLogic.beginCarve(seed, slices, style);
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

            // Drive GUI-scale label
            updateScaleButtonLabelFromOptions();

            // Drive carve animation + particle bursts + final packet
            carveLogic.tick(new SealStampCarveLogic.Callback() {
                @Override
                public void onBurst(int burstIndex) {
                    try {
                        LOG.debug("[SealStampScreen] Carve burst #{} at tick={}", burstIndex, carveLogic.getElapsedTicks());
                        spawnCarveParticles(80);
                    } catch (Throwable t) {
                        LOG.error("[SealStampScreen] carve burst callback failed", t);
                    }
                }

                @Override
                public void onFinished(long seed, int slices, int style) {
                    try {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc == null || mc.player == null) {
                            LOG.error("[SealStampScreen] onFinished: Minecraft or player is null; aborting packet send");
                            return;
                        }

                        int effectiveSlot = computeSealStampSlot(mc);

                        if (effectiveSlot < 0) {
                            LOG.error("[SealStampScreen] Carve FINISHED but could not locate a SealStampItem in player hands/inventory; aborting write");
                            // Optionally re-enable the button so the user can try again
                            carveButtonDisabled = false;
                            if (carveButton != null) {
                                carveButton.active = true;
                            }
                            return;
                        }

                        String ownerName = mc.player.getGameProfile().getName();

                        LOG.info("[SealStampScreen] Carve FINISHED → sending SealStampCarveResultPacket (stampSlot={} owner='{}')",
                                effectiveSlot, ownerName);

                        // Send the result to the server for actual NBT write (payload-based)
                        PacketDistributor.sendToServer(
                                new SealStampCarveResultPacket(effectiveSlot, seed, slices, style, ownerName)
                        );

                        // Close the container / GUI after a successful carve
                        try {
                            mc.player.closeContainer();
                            mc.setScreen(null);
                        } catch (Throwable closeError) {
                            LOG.error("[SealStampScreen] Failed to close container after carve", closeError);
                        }

                    } catch (Throwable t) {
                        LOG.error("[SealStampScreen] carve finished callback failed", t);
                    }
                }
            });

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

            // === Refactored call: draw wax + sigil glyph exactly as before ===
            if (this.currentPattern != null) {
                visualizer.render(
                        guiGraphics,
                        centerX,
                        centerY,
                        previewX,
                        previewY,
                        PREVIEW_WIDTH,
                        PREVIEW_HEIGHT,
                        SIGIL_RADIUS,
                        WAX_SEAL_SCREEN_SCALE,
                        this.currentPattern
                );
            }
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderPreviewArea failed", t);
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

    // ---------------------------------------------------------------------
    // Other helpers
    // ---------------------------------------------------------------------

    /**
     * Determine which slot currently holds a SealStampItem.
     *
     * Slot mapping:
     *  - 36 = main hand
     *  - 37 = offhand
     *  - 0..35 = main inventory list
     *
     * Returns -1 if no SealStampItem is found.
     */
    private static int computeSealStampSlot(Minecraft mc) {
        try {
            if (mc == null || mc.player == null) {
                return -1;
            }

            var player = mc.player;

            // 1) Check main hand
            if (player.getMainHandItem().getItem() instanceof SealStampItem) {
                return 36;
            }

            // 2) Check offhand
            if (player.getOffhandItem().getItem() instanceof SealStampItem) {
                return 37;
            }

            // 3) Scan main inventory (0..35)
            var inv = player.getInventory();
            for (int i = 0; i < inv.items.size(); i++) {
                var stack = inv.items.get(i);
                if (!stack.isEmpty() && stack.getItem() instanceof SealStampItem) {
                    return i;
                }
            }

            return -1;
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] computeSealStampSlot failed", t);
            return -1;
        }
    }
}
