// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/WaxSealVisualizer.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.sigil.SealSigilGenerator.SigilPattern;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/gui/WaxSealVisualizer.java
 *
 * WaxSealVisualizer
 *
 * A reusable, zero-regression renderer for the wax seal + sigil glyph preview.
 * This isolates the exact visual logic previously embedded in SealStampScreen:
 *  - Draw the wax seal texture (or a golden circle fallback)
 *  - Render the sigil glyph with:
 *      * shape fill
 *      * optional directional highlight band (outside shapes)
 *      * optional directional shadow band (outside shapes)
 *  - Clip to a preview box and to the sigil radius
 *
 * Defaults are chosen to exactly match the previous SealStampScreen visuals.
 * The public render(...) method mirrors the original behaviour and should
 * produce identical output when called with the same masks and parameters.
 *
 * Safety:
 *  - Extensive try/catch + debug logs
 *  - Never throws during GUI rendering
 */
public final class WaxSealVisualizer {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Wax seal texture config (unchanged defaults)
    // ---------------------------------------------------------------------

    /** Wax seal texture used behind the glyph (same as before). */
    private static final ResourceLocation WAX_SEAL_TEXTURE =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/stampscreen/wax_seal.png");

    /** Source texture size for wax_seal.png (unchanged). */
    private static final int WAX_SEAL_TEXTURE_WIDTH = 39;
    private static final int WAX_SEAL_TEXTURE_HEIGHT = 38;

    /** Fallback golden colour (used only when texture missing). */
    private static final int GOLD_DISC_COLOR = 0xFFE0C060;

    /** Whether to draw the wax seal texture / golden disc at all. */
    private boolean waxEnabled = true;

    // ---------------------------------------------------------------------
    // Sigil visual config (identical defaults to SealStampScreen)
    // ---------------------------------------------------------------------

    /** Shape fill colour (interior of glyph shapes). HEX #B83E3E */
    private int shapeFillColor = 0xFFb83e3e;

    /** Highlight band enabled? */
    private boolean shapeEnableHighlight = true;

    /** Shadow band enabled? */
    private boolean shapeEnableShadow = true;

    /** Highlight band thickness in pixels (Chebyshev radius). */
    private int shapeHighlightEdgeMaxRadius = 1;

    /** Shadow band thickness in pixels (Chebyshev radius). */
    private int shapeShadowEdgeMaxRadius = 3;

    /** Highlight colour (outside band). HEX #D36A62 */
    private int shapeHighlightColor = 0xFFd36a62;

    /** Shadow colour (outside band). HEX #832134 */
    private int shapeShadowColor = 0xFF832134;

    /**
     * Direction of the highlight band, encoded as 1..8 (same mapping as original).
     * 1=N, 2=E, 3=S, 4=W, 5=NE, 6=SE, 7=SW, 8=NW
     * Original highlight direction: 6 (SE → band extends up-left).
     */
    private int shapeHighlightDirection = 6;

    /**
     * Direction of the shadow band, encoded as 1..8 (same mapping as original).
     * Original shadow direction: 8 (NW → band extends down-right).
     */
    private int shapeShadowDirection = 8;

    // ---------------------------------------------------------------------
    // Construction (defaults match SealStampScreen)
    // ---------------------------------------------------------------------

    public WaxSealVisualizer() {
    }

    // ---------------------------------------------------------------------
    // Optional fluent setters (kept for reuse in other screens later)
    // ---------------------------------------------------------------------

    /** Enable/disable drawing the wax seal texture / golden disc. */
    public WaxSealVisualizer setWaxEnabled(boolean enabled) {
        this.waxEnabled = enabled;
        return this;
    }

    public WaxSealVisualizer setShapeFillColor(int argb) {
        this.shapeFillColor = argb;
        return this;
    }

    public WaxSealVisualizer setShapeEnableHighlight(boolean enabled) {
        this.shapeEnableHighlight = enabled;
        return this;
    }

    public WaxSealVisualizer setShapeEnableShadow(boolean enabled) {
        this.shapeEnableShadow = enabled;
        return this;
    }

    public WaxSealVisualizer setShapeHighlightEdgeMaxRadius(int px) {
        this.shapeHighlightEdgeMaxRadius = Math.max(0, px);
        return this;
    }

    public WaxSealVisualizer setShapeShadowEdgeMaxRadius(int px) {
        this.shapeShadowEdgeMaxRadius = Math.max(0, px);
        return this;
    }

    public WaxSealVisualizer setShapeHighlightColor(int argb) {
        this.shapeHighlightColor = argb;
        return this;
    }

    public WaxSealVisualizer setShapeShadowColor(int argb) {
        this.shapeShadowColor = argb;
        return this;
    }

    public WaxSealVisualizer setShapeHighlightDirection(int dir1to8) {
        this.shapeHighlightDirection = clampDir(dir1to8);
        return this;
    }

    public WaxSealVisualizer setShapeShadowDirection(int dir1to8) {
        this.shapeShadowDirection = clampDir(dir1to8);
        return this;
    }

    private static int clampDir(int d) {
        if (d < 1) return 1;
        if (d > 8) return 8;
        return d;
    }

    // ---------------------------------------------------------------------
    // Public rendering API
    // ---------------------------------------------------------------------

    /**
     * Render the wax seal (texture or fallback circle) and the sigil glyph with
     * highlight/shadow bands. Visuals and math match the original code.
     *
     * @param gg            GuiGraphics
     * @param centerX       center X of the seal/sigil
     * @param centerY       center Y of the seal/sigil
     * @param previewX      left of preview area (clip bounds)
     * @param previewY      top of preview area (clip bounds)
     * @param previewWidth  width of preview area (clip bounds)
     * @param previewHeight height of preview area (clip bounds)
     * @param sigilRadius   radius (in screen pixels) to clip the sigil glyph
     * @param waxScale      scale factor for the wax seal texture (e.g., 3.0f)
     * @param pattern       SigilPattern (disc + shape masks) from generator
     */
    public void render(@NotNull GuiGraphics gg,
                       int centerX,
                       int centerY,
                       int previewX,
                       int previewY,
                       int previewWidth,
                       int previewHeight,
                       int sigilRadius,
                       float waxScale,
                       @NotNull SigilPattern pattern) {
        try {
            // 1) Draw wax seal behind glyph (optional)
            if (waxEnabled) {
                renderWaxSeal(gg, centerX, centerY, waxScale);
            }

            // 2) Draw the sigil glyph (shape fill + highlight/shadow)
            renderSigilGlyph(gg, centerX, centerY, previewX, previewY, previewWidth, previewHeight, sigilRadius, pattern);
        } catch (Throwable t) {
            LOG.error("[WaxSealVisualizer] render failed", t);
        }
    }

    /**
     * Render only the sigil glyph (shapes + highlight/shadow), without drawing
     * the wax seal background. Used by ScrollSealingScreen, while SealStampScreen
     * continues to use {@link #render} which draws wax + glyph.
     */
    public void renderShapesOnly(@NotNull GuiGraphics gg,
                                 int centerX,
                                 int centerY,
                                 int previewX,
                                 int previewY,
                                 int previewWidth,
                                 int previewHeight,
                                 int sigilRadius,
                                 float unusedScale,
                                 @NotNull SigilPattern pattern) {
        try {
            // Only draw the glyph; ignore the wax texture entirely.
            renderSigilGlyph(
                    gg,
                    centerX,
                    centerY,
                    previewX,
                    previewY,
                    previewWidth,
                    previewHeight,
                    sigilRadius,
                    pattern
            );
        } catch (Throwable t) {
            LOG.error("[WaxSealVisualizer] renderShapesOnly failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Wax seal (texture or fallback)
    // ---------------------------------------------------------------------

    private void renderWaxSeal(@NotNull GuiGraphics gg, int centerX, int centerY, float scale) {
        try {
            int sealWidth = Math.round(WAX_SEAL_TEXTURE_WIDTH * scale);
            int sealHeight = Math.round(WAX_SEAL_TEXTURE_HEIGHT * scale);

            int sealX = centerX - sealWidth / 2;
            int sealY = centerY - sealHeight / 2;

            var resourceManager = Minecraft.getInstance().getResourceManager();
            boolean hasTexture = false;
            try {
                hasTexture = resourceManager.getResource(WAX_SEAL_TEXTURE).isPresent();
            } catch (Throwable t) {
                LOG.error("[WaxSealVisualizer] renderWaxSeal: resource lookup failed", t);
            }

            if (hasTexture) {
                gg.blit(
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
                            gg.fill(x, y, x + 1, y + 1, GOLD_DISC_COLOR);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[WaxSealVisualizer] renderWaxSeal failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Sigil glyph (shape fill + highlight/shadow)
    // ---------------------------------------------------------------------

    private void renderSigilGlyph(@NotNull GuiGraphics gg,
                                  int centerX,
                                  int centerY,
                                  int previewX,
                                  int previewY,
                                  int previewWidth,
                                  int previewHeight,
                                  int sigilRadius,
                                  @NotNull SigilPattern pattern) {
        try {
            int size = pattern.getSize();
            if (size <= 0) {
                return;
            }

            boolean[][] disc = pattern.getDiscMask();
            boolean[][] shapes = pattern.getShapeMask();
            if (shapes == null) {
                return;
            }

            // Ensure shader colour is sane so our ARGB colours aren't multiplied to black.
            resetShaderColorForGui();

            final int sigilRadiusSq = sigilRadius * sigilRadius;
            final int patternRadius = size / 2;

            final int boxLeft = previewX;
            final int boxTop = previewY;
            final int boxRight = previewX + previewWidth;
            final int boxBottom = previewY + previewHeight;

            // -----------------------------------------------------------------
            // Compute highlight/shadow pixels OUTSIDE the shapes,
            // edge-based with per-edge classification (same as original).
            // -----------------------------------------------------------------
            boolean[][] highlightPixels = (shapeEnableHighlight ? new boolean[size][size] : null);
            boolean[][] shadowPixels = (shapeEnableShadow ? new boolean[size][size] : null);

            final int maxHighlightRadius = (shapeEnableHighlight ? Math.max(1, shapeHighlightEdgeMaxRadius) : 0);
            final int maxShadowRadius = (shapeEnableShadow ? Math.max(1, shapeShadowEdgeMaxRadius) : 0);

            // Integer step vectors for band casting.
            int[] shadowStep = directionToUnitOffset(shapeShadowDirection);
            int sxStep = shadowStep[0];
            int syStep = shadowStep[1];

            int[] highlightStep = directionToUnitOffset(shapeHighlightDirection);
            int hxStep = highlightStep[0];
            int hyStep = highlightStep[1];

            // Normalised float vectors for classification (dot products).
            float hxNormX = hxStep;
            float hxNormY = hyStep;
            float hxLen = (float) Math.sqrt(hxNormX * hxNormX + hxNormY * hxNormY);
            if (hxLen > 1.0e-4f) {
                hxNormX /= hxLen;
                hxNormY /= hxLen;
            } else {
                hxNormX = 0.0f;
                hxNormY = 0.0f;
            }

            float sxNormX = sxStep;
            float sxNormY = syStep;
            float sxLen = (float) Math.sqrt(sxNormX * sxNormX + sxNormY * sxNormY);
            if (sxLen > 1.0e-4f) {
                sxNormX /= sxLen;
                sxNormY /= sxLen;
            } else {
                sxNormX = 0.0f;
                sxNormY = 0.0f;
            }

            try {
                // First build an edge mask: shape pixels that touch "air" (outside shape or outside disc).
                boolean[][] edgeMask = new boolean[size][size];

                for (int py = 0; py < size; py++) {
                    boolean[] shapeRow = shapes[py];
                    if (shapeRow == null) continue;

                    boolean[] discRow = (disc != null && py >= 0 && py < disc.length) ? disc[py] : null;

                    for (int px = 0; px < size; px++) {
                        if (!shapeRow[px]) {
                            continue;
                        }

                        if (discRow != null && !discRow[px]) {
                            continue;
                        }

                        boolean isEdge = false;
                        for (int oy = -1; oy <= 1 && !isEdge; oy++) {
                            for (int ox = -1; ox <= 1 && !isEdge; ox++) {
                                if (ox == 0 && oy == 0) continue;
                                int nx = px + ox;
                                int ny = py + oy;

                                boolean insideNeighbor = false;
                                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                                    boolean[] shapeRowN = shapes[ny];
                                    boolean insideShapeNeighbor =
                                            shapeRowN != null && nx < shapeRowN.length && shapeRowN[nx];

                                    boolean insideDiscNeighbor = true;
                                    if (disc != null && ny >= 0 && ny < disc.length) {
                                        boolean[] discRowN = disc[ny];
                                        if (discRowN != null && nx >= 0 && nx < discRowN.length) {
                                            insideDiscNeighbor = discRowN[nx];
                                        } else {
                                            insideDiscNeighbor = false;
                                        }
                                    }

                                    insideNeighbor = insideShapeNeighbor && insideDiscNeighbor;
                                }

                                if (!insideNeighbor) {
                                    // Touching air (outside shape or outside disc) → edge.
                                    isEdge = true;
                                }
                            }
                        }

                        edgeMask[py][px] = isEdge;
                    }
                }

                // For each edge pixel, compute an outward vector and classify it as highlight or shadow,
                // then cast the band outward in the chosen direction.
                for (int py = 0; py < size; py++) {
                    boolean[] shapeRow = shapes[py];
                    if (shapeRow == null) continue;

                    boolean[] edgeRow = edgeMask[py];
                    if (edgeRow == null) continue;

                    boolean[] discRow = (disc != null && py >= 0 && py < disc.length) ? disc[py] : null;

                    for (int px = 0; px < size; px++) {
                        if (!shapeRow[px]) {
                            continue;
                        }
                        if (!edgeRow[px]) {
                            continue;
                        }

                        if (discRow != null && !discRow[px]) {
                            continue;
                        }

                        // Compute outward vector by aggregating offsets to "air" neighbours.
                        float outX = 0.0f;
                        float outY = 0.0f;

                        for (int oy = -1; oy <= 1; oy++) {
                            for (int ox = -1; ox <= 1; ox++) {
                                if (ox == 0 && oy == 0) continue;
                                int nx = px + ox;
                                int ny = py + oy;

                                boolean insideNeighbor = false;
                                if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                                    boolean[] shapeRowN = shapes[ny];
                                    boolean insideShapeNeighbor =
                                            shapeRowN != null && nx < shapeRowN.length && shapeRowN[nx];

                                    boolean insideDiscNeighbor = true;
                                    if (disc != null && ny >= 0 && ny < disc.length) {
                                        boolean[] discRowN = disc[ny];
                                        if (discRowN != null && nx >= 0 && nx < discRowN.length) {
                                            insideDiscNeighbor = discRowN[nx];
                                        } else {
                                            insideDiscNeighbor = false;
                                        }
                                    }

                                    insideNeighbor = insideShapeNeighbor && insideDiscNeighbor;
                                }

                                if (!insideNeighbor) {
                                    // Air neighbour: contributes to outward direction.
                                    outX += ox;
                                    outY += oy;
                                }
                            }
                        }

                        float outLen = (float) Math.sqrt(outX * outX + outY * outY);
                        if (outLen > 1.0e-4f) {
                            outX /= outLen;
                            outY /= outLen;
                        } else {
                            // Degenerate: fall back to shadow direction, or highlight if shadow disabled.
                            if (shapeEnableShadow && (sxNormX != 0.0f || sxNormY != 0.0f)) {
                                outX = sxNormX;
                                outY = sxNormY;
                            } else if (shapeEnableHighlight && (hxNormX != 0.0f || hxNormY != 0.0f)) {
                                outX = hxNormX;
                                outY = hxNormY;
                            } else {
                                // No usable direction; skip this edge pixel.
                                continue;
                            }
                        }

                        float dotHighlight = (shapeEnableHighlight ? (outX * hxNormX + outY * hxNormY) : -Float.MAX_VALUE);
                        float dotShadow = (shapeEnableShadow ? (outX * sxNormX + outY * sxNormY) : -Float.MAX_VALUE);

                        boolean useHighlight = shapeEnableHighlight &&
                                (!shapeEnableShadow || dotHighlight >= dotShadow);
                        boolean useShadow = shapeEnableShadow &&
                                (!shapeEnableHighlight || dotShadow > dotHighlight);

                        // Emit highlight band outward from this edge pixel.
                        if (useHighlight && highlightPixels != null && maxHighlightRadius > 0) {
                            for (int k = 1; k <= maxHighlightRadius; k++) {
                                int nx = px + hxStep * k;
                                int ny = py + hyStep * k;

                                if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
                                    break;
                                }

                                boolean[] shapeRowN = shapes[ny];
                                boolean insideShapeNeighbor =
                                        shapeRowN != null && nx < shapeRowN.length && shapeRowN[nx];
                                if (insideShapeNeighbor) {
                                    // Stay outside shape.
                                    break;
                                }

                                boolean insideDiscNeighbor = true;
                                if (disc != null && ny >= 0 && ny < disc.length) {
                                    boolean[] discRowN = disc[ny];
                                    if (discRowN != null && nx >= 0 && nx < discRowN.length) {
                                        insideDiscNeighbor = discRowN[nx];
                                    } else {
                                        insideDiscNeighbor = false;
                                    }
                                }
                                if (!insideDiscNeighbor) {
                                    // Do not go outside disc.
                                    break;
                                }

                                highlightPixels[ny][nx] = true;
                            }
                        }

                        // Emit shadow band outward from this edge pixel.
                        if (useShadow && shadowPixels != null && maxShadowRadius > 0) {
                            for (int k = 1; k <= maxShadowRadius; k++) {
                                int nx = px + sxStep * k;
                                int ny = py + syStep * k;

                                if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
                                    break;
                                }

                                boolean[] shapeRowN = shapes[ny];
                                boolean insideShapeNeighbor =
                                        shapeRowN != null && nx < shapeRowN.length && shapeRowN[nx];
                                if (insideShapeNeighbor) {
                                    // Stay outside shape.
                                    break;
                                }

                                boolean insideDiscNeighbor = true;
                                if (disc != null && ny >= 0 && ny < disc.length) {
                                    boolean[] discRowN = disc[ny];
                                    if (discRowN != null && nx >= 0 && nx < discRowN.length) {
                                        insideDiscNeighbor = discRowN[nx];
                                    } else {
                                        insideDiscNeighbor = false;
                                    }
                                }
                                if (!insideDiscNeighbor) {
                                    // Do not go outside disc.
                                    break;
                                }

                                shadowPixels[ny][nx] = true;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.error("[WaxSealVisualizer] renderSigilGlyph: edge-based analysis (highlight/shadow) failed", t);
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

                        gg.fill(sx, sy, sx + 1, sy + 1, shapeFillColor);
                    }
                }
            } catch (Throwable t) {
                LOG.error("[WaxSealVisualizer] renderSigilGlyph: base shape fill failed", t);
            }

            // -----------------------------------------------------------------
            // Pass 2: highlight overlay (outside shapes)
            // -----------------------------------------------------------------
            if (shapeEnableHighlight && highlightPixels != null) {
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

                            gg.fill(sx, sy, sx + 1, sy + 1, shapeHighlightColor);
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("[WaxSealVisualizer] renderSigilGlyph: highlight overlay failed", t);
                }
            }

            // -----------------------------------------------------------------
            // Pass 3: shadow overlay (outside shapes)
            // -----------------------------------------------------------------
            if (shapeEnableShadow && shadowPixels != null) {
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

                            gg.fill(sx, sy, sx + 1, sy + 1, shapeShadowColor);
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("[WaxSealVisualizer] renderSigilGlyph: shadow overlay failed", t);
                }
            }

        } catch (Throwable t) {
            LOG.error("[WaxSealVisualizer] renderSigilGlyph failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers (copied verbatim from original to ensure pixel parity)
    // ---------------------------------------------------------------------

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
            LOG.error("[WaxSealVisualizer] resetShaderColorForGui failed", t);
        }
    }

    /**
     * Convert our 1..8 direction index into a unit (dx, dy) offset representing
     * the direction in which the band "extends".
     *
     * This is used for both the shadow direction and the highlight direction.
     * Mapping matches the original SealStampScreen implementation.
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
}
