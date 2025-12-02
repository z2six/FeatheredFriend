// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SigilPreviewScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.z2six.featheredfriend.sigil.SealSigilGenerator;
import net.z2six.featheredfriend.sigil.SealSigilGenerator.SigilPattern;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SigilPreviewScreen.java
 *
 * SigilPreviewScreen
 *
 * Simple client-only debug screen that renders a sigil pattern as a filled,
 * circular glyph (no visible grid / disconnected cells).
 *
 * This screen is opened via SigilPreviewPayload -> FFNetwork.handleSigilPreviewOnClient.
 */
public class SigilPreviewScreen extends Screen {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Maximum seed length we honour in the preview.
     * Anything longer is truncated before hashing.
     */
    private static final int MAX_SEED_LENGTH = 128;

    /**
     * Radius used for preview rendering; must match what we pass to SealSigilGenerator.
     */
    private static final int PREVIEW_RADIUS = SealSigilGenerator.DEFAULT_RADIUS;

    private final String seed;
    private final SigilPattern pattern;
    private final boolean[][] pixels;
    private final int patternSize;

    public SigilPreviewScreen(@NotNull String rawSeed) {
        super(Component.literal("Sigil Preview"));

        this.seed = clampSeed(rawSeed);

        long hash = computeHash(this.seed);
        this.pattern = SealSigilGenerator.generateFromSeed(hash, PREVIEW_RADIUS);
        this.pixels = pattern.getPixels();
        this.patternSize = pattern.getSize();

        LOG.debug("[SigilPreviewScreen] Constructed for seed='{}' (hash={}) size={}",
                this.seed, hash, this.patternSize);
    }

    /**
     * Convenience opener for client-side usage.
     * Safe-guards against null Minecraft instance.
     */
    public static void open(@NotNull String seed) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                LOG.warn("[SigilPreviewScreen] Minecraft instance is null; cannot open screen");
                return;
            }
            mc.setScreen(new SigilPreviewScreen(seed));
        } catch (Throwable t) {
            LOG.error("[SigilPreviewScreen] Failed to open screen for seed '{}'", seed, t);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

            // Semi-transparent dark overlay
            guiGraphics.fill(0, 0, this.width, this.height, 0xC0000000);

            // Draw title + seed (truncated version)
            String title = "Sigil Preview";
            String seedLabel = "Seed: " + seed;

            int titleWidth = this.font.width(title);
            int seedWidth = this.font.width(seedLabel);

            int centerX = this.width / 2;

            guiGraphics.drawString(
                    this.font,
                    title,
                    centerX - titleWidth / 2,
                    20,
                    0xFFFFFF00, // yellowish
                    false
            );

            guiGraphics.drawString(
                    this.font,
                    seedLabel,
                    centerX - seedWidth / 2,
                    32,
                    0xFFFFFFFF,
                    false
            );

            // Draw the sigil as a filled glyph (no cell spacing, no grid)
            drawSigil(guiGraphics);

            super.render(guiGraphics, mouseX, mouseY, partialTick);
        } catch (Throwable t) {
            LOG.error("[SigilPreviewScreen] render() failed", t);
        }
    }

    @Override
    public boolean isPauseScreen() {
        // Don't pause the game for this debug preview.
        return false;
    }

    // ---------------------------------------------------------------------
    // Rendering helpers
    // ---------------------------------------------------------------------

    private void drawSigil(@NotNull GuiGraphics guiGraphics) {
        try {
            if (pixels == null || patternSize <= 0) {
                return;
            }

            // Compute maximum square we want to use for the sigil in the center.
            int maxAvailable = Math.min(this.width, this.height) - 80;
            if (maxAvailable <= 0) {
                return;
            }

            // Each "pixel" in the pattern will be scaled by this factor.
            int pixelScale = Math.max(1, maxAvailable / patternSize);
            int sigilPixelSize = patternSize * pixelScale;

            int centerX = this.width / 2;
            int centerY = this.height / 2 + 20; // bias slightly downward vs title

            int topLeftX = centerX - sigilPixelSize / 2;
            int topLeftY = centerY - sigilPixelSize / 2;

            // Slight background panel behind the sigil
            int padding = 10;
            guiGraphics.fill(
                    topLeftX - padding,
                    topLeftY - padding,
                    topLeftX + sigilPixelSize + padding,
                    topLeftY + sigilPixelSize + padding,
                    0xFF222222
            );

            // Actual sigil glyph (filled shape).
            // We'll use a gold-ish color for "wax impression".
            int fillColor = 0xFFFFD700;

            for (int y = 0; y < patternSize; y++) {
                boolean[] row;
                try {
                    row = pixels[y];
                } catch (Throwable t) {
                    LOG.error("[SigilPreviewScreen] pixels row OOB at y={}", y, t);
                    continue;
                }

                for (int x = 0; x < patternSize; x++) {
                    boolean filled;
                    try {
                        filled = row[x];
                    } catch (Throwable t) {
                        LOG.error("[SigilPreviewScreen] pixels cell OOB at {},{}", x, y, t);
                        continue;
                    }

                    if (!filled) {
                        continue;
                    }

                    int px = topLeftX + x * pixelScale;
                    int py = topLeftY + y * pixelScale;

                    guiGraphics.fill(
                            px,
                            py,
                            px + pixelScale,
                            py + pixelScale,
                            fillColor
                    );
                }
            }
        } catch (Throwable t) {
            LOG.error("[SigilPreviewScreen] drawSigil() failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Hash + seed clamp
    // ---------------------------------------------------------------------

    private static @NotNull String clampSeed(@NotNull String raw) {
        String s = raw;
        if (s.length() > MAX_SEED_LENGTH) {
            s = s.substring(0, MAX_SEED_LENGTH);
        }
        return s;
    }

    /**
     * Simple deterministic hash for the preview command/network.
     * This is independent of the UUID+secret crypto seed used for real stamps.
     */
    private static long computeHash(@NotNull String seed) {
        long h = 1125899906842597L; // prime-ish
        for (int i = 0; i < seed.length(); i++) {
            h = 31L * h + seed.charAt(i);
        }
        LOG.debug("[SigilPreviewScreen] computeHash('{}') -> {}", seed, h);
        return h;
    }
}
