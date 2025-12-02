// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SigilPreviewScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Random;

/**
 * Simple client-only debug screen that renders a sigil pattern.
 *
 * For now it's not wired to the command; the command prints ASCII so you can
 * test the algorithm. Once you're happy with the results, we can:
 *  - add a small S2C payload carrying (seed/hash/pattern)
 *  - open this screen on the client with that payload
 */
public class SigilPreviewScreen extends Screen {

    private static final Logger LOG = LogUtils.getLogger();

    private final String seed;
    private final boolean[][] pattern;
    private final int gridWidth;
    private final int gridHeight;

    // Visual parameters
    private static final int CELL_SIZE = 10;
    private static final int CELL_SPACING = 2;
    private static final int GRID_PADDING = 8;

    public SigilPreviewScreen(@NotNull String seed) {
        super(Component.literal("Sigil Preview"));
        this.seed = seed;

        int size = 11; // match the command's grid size
        long hash = computeHash(seed);
        this.pattern = generatePattern(hash, size, size);
        this.gridWidth = size;
        this.gridHeight = size;

        LOG.debug("[SigilPreviewScreen] Constructed for seed='{}', hash={}", seed, hash);
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
            // NEW SIGNATURE (1.21.x): must pass mouseX/mouseY/partialTick here
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

            // Semi-transparent dark overlay
            guiGraphics.fill(0, 0, this.width, this.height, 0xC0000000);

            // Draw title + seed
            String title = "Sigil Preview";
            String seedLabel = "Seed: " + seed;

            int titleWidth = this.font.width(title);
            int seedWidth = this.font.width(seedLabel);

            int centerX = this.width / 2;

            guiGraphics.drawString(this.font, title,
                    centerX - titleWidth / 2,
                    20,
                    0xFFFFFF00, // yellowish
                    false);

            guiGraphics.drawString(this.font, seedLabel,
                    centerX - seedWidth / 2,
                    32,
                    0xFFFFFFFF,
                    false);

            // Draw grid in the center-ish
            int totalCellSize = CELL_SIZE + CELL_SPACING;
            int gridPixelWidth = gridWidth * totalCellSize - CELL_SPACING;
            int gridPixelHeight = gridHeight * totalCellSize - CELL_SPACING;

            int gridX = centerX - gridPixelWidth / 2;
            int gridY = this.height / 2 - gridPixelHeight / 2;

            // Slight background panel behind the grid
            guiGraphics.fill(
                    gridX - GRID_PADDING,
                    gridY - GRID_PADDING,
                    gridX + gridPixelWidth + GRID_PADDING,
                    gridY + gridPixelHeight + GRID_PADDING,
                    0xFF222222
            );

            // Draw each cell
            for (int y = 0; y < gridHeight; y++) {
                for (int x = 0; x < gridWidth; x++) {
                    int cellX = gridX + x * totalCellSize;
                    int cellY = gridY + y * totalCellSize;

                    boolean filled = false;
                    try {
                        filled = pattern[y][x];
                    } catch (Throwable t) {
                        LOG.error("[SigilPreviewScreen] Pattern index out of bounds at {},{}", x, y, t);
                    }

                    int color = filled ? 0xFFFFD700 : 0xFF444444; // gold-ish vs dark gray
                    guiGraphics.fill(
                            cellX,
                            cellY,
                            cellX + CELL_SIZE,
                            cellY + CELL_SIZE,
                            color
                    );
                }
            }

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
    // Same simple algorithm as in FFSigilCommandsNeoForge so they match.
    // ---------------------------------------------------------------------

    private static long computeHash(@NotNull String seed) {
        long h = 1125899906842597L;
        for (int i = 0; i < seed.length(); i++) {
            h = 31L * h + seed.charAt(i);
        }
        return h;
    }

    private static boolean[][] generatePattern(long hash, int width, int height) {
        boolean[][] grid = new boolean[height][width];

        try {
            Random rng = new Random(hash);

            int half = width / 2;
            boolean hasMiddle = (width % 2) == 1;
            int middleCol = hasMiddle ? half : -1;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x <= half; x++) {
                    double baseChance = 0.3 + rng.nextDouble() * 0.4;

                    if (y > 0 && grid[y - 1][x]) baseChance += 0.15;
                    if (x > 0 && grid[y][x - 1]) baseChance += 0.15;

                    if (baseChance > 0.9) baseChance = 0.9;
                    if (baseChance < 0.1) baseChance = 0.1;

                    boolean value = rng.nextDouble() < baseChance;

                    grid[y][x] = value;

                    int mirrorX = width - 1 - x;
                    if (mirrorX != x && mirrorX >= 0 && mirrorX < width) {
                        grid[y][mirrorX] = value;
                    }
                }
            }

            if (hasMiddle) {
                int centerX = middleCol;
                int centerY = height / 2;
                grid[centerY][centerX] = true;
            }
        } catch (Throwable t) {
            LOG.error("[SigilPreviewScreen] generatePattern failed, returning empty pattern", t);
        }

        return grid;
    }
}
