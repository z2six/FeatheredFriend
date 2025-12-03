// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SealStampScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import net.z2six.featheredfriend.neoforge.menu.SealStampMenu;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SealStampScreen.java
 *
 * SealStampScreen
 *
 * - Uses Gothic font + MultiLineScrollTextWidget for the secret passphrase.
 * - Left column: Etchings dropdown, Style dropdown, Carve button, GUI-scale toggle button.
 * - Right side: "Sigil Preview" area with a wooden circular disc using vanilla oak planks.
 * - On "Carve" click:
 *      * Spawns a burst of client-side particles (based on texture_etching16x.png) that drift downward,
 *        rotate, fade out, and die quickly.
 * - Sigil generation / NBT saving will be wired in a later step.
 */
public class SealStampScreen extends AbstractContainerScreen<SealStampMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation SEAL_STAMP_GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/seal_stamp.png");

    // Gothic font id (same as used by ScrollSealingScreen / MultiLineScrollTextWidget)
    private static final ResourceLocation GOTHIC_FONT_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gothic12");

    // Wood disc background: vanilla oak planks texture
    private static final ResourceLocation DISC_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/oak_planks.png");

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
    // Etching options
    // ---------------------------------------------------------------------

    private static final int MIN_SLICES = 2;
    private static final int MAX_SLICES = 8;

    private static final String[] STYLE_NAMES = {"Medieval", "Fantasy", "Floral"};

    // ---------------------------------------------------------------------
    // Particle tuning
    // ---------------------------------------------------------------------

    /**
     * Base particle size multiplier. Increase to make chips larger.
     */
    private static final float PARTICLE_SIZE_BASE = 8.0f;

    /**
     * Multiplier applied to downward velocity, to make them fall faster.
     */
    private static final double PARTICLE_FALL_MULTIPLIER = 1.5;

    // ---------------------------------------------------------------------
    // State & widgets
    // ---------------------------------------------------------------------

    private MultiLineScrollTextWidget secretField;
    private Button etchingsButton;
    private Button styleButton;
    private Button carveButton;
    private AbstractWidget scaleButton;

    private int currentSlices = 6;       // default
    private int currentStyleIndex = 0;   // "Medieval"

    private final RandomSource random = RandomSource.createNewThreadLocalInstance();

    // Simple client-side particle list for the "Carve" effect
    private final List<SigilEtchingParticle> particles = new ArrayList<>();

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
                gothic("Secret") // placeholder text; no separate label above
        );
        this.secretField.setCustomFontId(GOTHIC_FONT_ID);
        this.secretField.setEditable(true);

        this.addRenderableWidget(this.secretField);

        // -----------------------------------------------------------------
        // Left column controls (Etchings / Style / Carve / Scale)
        // -----------------------------------------------------------------
        int colX = this.leftPos + LEFT_COLUMN_X;
        int rowY = this.topPos + LEFT_COLUMN_FIRST_Y;

        this.etchingsButton = Button.builder(
                        Component.empty(),
                        b -> cycleSlices()
                )
                .bounds(colX, rowY, DROPDOWN_WIDTH, DROPDOWN_HEIGHT)
                .build();
        this.addRenderableWidget(this.etchingsButton);

        rowY += DROPDOWN_HEIGHT + CONTROL_VERTICAL_GAP;

        this.styleButton = Button.builder(
                        Component.empty(),
                        b -> cycleStyle()
                )
                .bounds(colX, rowY, DROPDOWN_WIDTH, DROPDOWN_HEIGHT)
                .build();
        this.addRenderableWidget(this.styleButton);

        rowY += DROPDOWN_HEIGHT + CONTROL_VERTICAL_GAP;

        this.carveButton = Button.builder(
                        Component.empty(),
                        b -> onCarveClicked()
                )
                .bounds(colX, rowY, CARVE_WIDTH, CARVE_HEIGHT)
                .build();
        this.addRenderableWidget(this.carveButton);

        rowY += CARVE_HEIGHT + CONTROL_VERTICAL_GAP;

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

    private void cycleSlices() {
        try {
            currentSlices++;
            if (currentSlices > MAX_SLICES) {
                currentSlices = MIN_SLICES;
            }
            LOG.debug("[SealStampScreen] cycleSlices -> {}", currentSlices);
            updateButtonLabels();
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
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] cycleStyle failed", t);
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
                    secretField != null ? secretField.getText() : "<null>");

            // Quicker, more lively burst
            spawnCarveParticles(80);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] onCarveClicked failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Ticking (for secret field + particles)
    // ---------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        try {
            if (this.secretField != null) {
                this.secretField.tick();
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
                guiGraphics.fill(
                        this.leftPos,
                        this.topPos,
                        this.leftPos + this.imageWidth,
                        this.topPos + this.imageHeight,
                        0xC0F5F0D8
                );
            }
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

            guiGraphics.fill(
                    previewX,
                    previewY,
                    previewX + PREVIEW_WIDTH,
                    previewY + PREVIEW_HEIGHT,
                    0x20FFFFFF
            );

            guiGraphics.fill(previewX, previewY, previewX + PREVIEW_WIDTH, previewY + 1, 0x80000000);
            guiGraphics.fill(previewX, previewY + PREVIEW_HEIGHT - 1, previewX + PREVIEW_WIDTH, previewY + PREVIEW_HEIGHT, 0x80000000);
            guiGraphics.fill(previewX, previewY, previewX + 1, previewY + PREVIEW_HEIGHT, 0x80000000);
            guiGraphics.fill(previewX + PREVIEW_WIDTH - 1, previewY, previewX + PREVIEW_WIDTH, previewY + PREVIEW_HEIGHT, 0x80000000);

            renderWoodenDisc(guiGraphics, previewX, previewY);
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderPreviewArea failed", t);
        }
    }

    private void renderWoodenDisc(@NotNull GuiGraphics guiGraphics, int previewX, int previewY) {
        try {
            var resourceManager = Minecraft.getInstance().getResourceManager();
            boolean hasTexture = resourceManager.getResource(DISC_TEXTURE).isPresent();
            if (!hasTexture) {
                renderFallbackDisc(guiGraphics, previewX, previewY);
                return;
            }

            int centerX = previewX + PREVIEW_WIDTH / 2;
            int centerY = previewY + PREVIEW_HEIGHT / 2;

            int radius = Math.min(PREVIEW_WIDTH, PREVIEW_HEIGHT) / 2 - 6;
            if (radius <= 0) {
                return;
            }

            int tileSize = 4;

            for (int dy = -radius; dy <= radius; dy += tileSize) {
                for (int dx = -radius; dx <= radius; dx += tileSize) {
                    int distSq = dx * dx + dy * dy;
                    if (distSq > radius * radius) {
                        continue;
                    }

                    int drawX = centerX + dx - tileSize / 2;
                    int drawY = centerY + dy - tileSize / 2;

                    guiGraphics.blit(
                            DISC_TEXTURE,
                            drawX,
                            drawY,
                            tileSize,
                            tileSize,
                            0,
                            0,
                            16,
                            16,
                            16,
                            16
                    );
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealStampScreen] renderWoodenDisc failed; falling back to simple disc", t);
            renderFallbackDisc(guiGraphics, previewX, previewY);
        }
    }

    private void renderFallbackDisc(@NotNull GuiGraphics guiGraphics, int previewX, int previewY) {
        int centerX = previewX + PREVIEW_WIDTH / 2;
        int centerY = previewY + PREVIEW_HEIGHT / 2;

        int radius = Math.min(PREVIEW_WIDTH, PREVIEW_HEIGHT) / 2 - 6;
        if (radius <= 0) {
            return;
        }

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy <= radius * radius) {
                    int x = centerX + dx;
                    int y = centerY + dy;
                    guiGraphics.fill(x, y, x + 1, y + 1, 0xFF8B5A2B);
                }
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
            int radius = Math.min(PREVIEW_WIDTH, PREVIEW_HEIGHT) / 2 - 6;

            if (radius <= 0) {
                return;
            }

            for (int i = 0; i < count; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double r = radius * Math.sqrt(random.nextDouble());
                double px = centerX + r * Math.cos(angle);
                double py = centerY + r * Math.sin(angle);

                double vx = (random.nextDouble() - 0.5) * 0.4;

                // Faster downward motion, scaled up by PARTICLE_FALL_MULTIPLIER
                double baseVy = 0.6 + random.nextDouble() * 0.6;
                double vy = baseVy * PARTICLE_FALL_MULTIPLIER;

                // Shorter lifetime: ~10–16 ticks
                int lifetime = 10 + random.nextInt(7);

                float size = PARTICLE_SIZE_BASE * (0.8f + random.nextFloat() * 0.6f);

                SigilEtchingParticle particle = new SigilEtchingParticle(px, py, vx, vy, size, lifetime);
                this.particles.add(particle);
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
}
