// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilShapeSetFloral2.java
package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;

import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilShapeSetFloral2.java
 *
 * Shape set 2 — "Floral / Botanical"
 *
 * Visual goals:
 *  - Large, graceful floral silhouettes:
 *      * Big central rosette / blossom.
 *      * Bold petal ring around it.
 *      * Thick curving vine arcs with leaves.
 *      * Outer wreaths and leaf clusters.
 *      * Small buds / seeds as finishing touches.
 *
 * Design rules:
 *  - Quality over quantity:
 *      * Few big motifs, low noise.
 *      * Strong, smooth petal shapes, minimal speckling.
 *  - All geometry is drawn into a single base slice mask (pixels[y][x] = true).
 *    The generator then:
 *      * Restricts to 1 slice wedge.
 *      * Replicates to all slices.
 *      * Cleans up small specks/holes.
 *      * Subtracts from the wax disc.
 *
 * Mask semantics:
 *  - pixels[y][x] == true => this pixel will be carved out of the wax disc.
 */
public final class SealSigilShapeSetFloral2 implements SealSigilShapeSet {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int MIN_MOTIF_PASSES = 1;
    private static final int MAX_MOTIF_PASSES = 3;

    /**
     * Radial bands as normalized radius fractions.
     * Slightly increased from the first version to make everything larger.
     */
    private static final double BAND_INNER_FRACTION = 0.34;
    private static final double BAND_MID_FRACTION   = 0.60;
    private static final double BAND_OUTER_FRACTION = 0.88;

    /**
     * Angular margin to avoid hugging slice borders.
     */
    private static final double SLICE_ANGULAR_MARGIN = Math.toRadians(4.0);

    private static final int MIN_DETAIL_RADIUS = 1;

    @Override
    public void applyShapesInSlice(boolean[][] pixels,
                                   int cx,
                                   int cy,
                                   int radius,
                                   double radiusSq,
                                   int slices,
                                   int sliceIndex,
                                   RandomSource rng) {
        try {
            if (pixels == null || pixels.length == 0) {
                LOG.warn("[SealSigilShapeSetFloral2] applyShapesInSlice called with null/empty pixels");
                return;
            }
            if (slices <= 0) {
                LOG.warn("[SealSigilShapeSetFloral2] slices <= 0 ({}), nothing to do", slices);
                return;
            }
            if (sliceIndex < 0 || sliceIndex >= slices) {
                LOG.warn("[SealSigilShapeSetFloral2] sliceIndex {} out of [0, {}), aborting slice carving",
                        sliceIndex, slices);
                return;
            }

            int size = pixels.length;

            double sliceAngleSpan = (Math.PI * 2.0) / slices;
            double sliceStart = sliceIndex * sliceAngleSpan;
            double sliceEnd = sliceStart + sliceAngleSpan;

            sliceStart += SLICE_ANGULAR_MARGIN;
            sliceEnd   -= SLICE_ANGULAR_MARGIN;
            if (sliceEnd <= sliceStart) {
                LOG.debug("[SealSigilShapeSetFloral2] Degenerate slice after margin: start={} end={}",
                        sliceStart, sliceEnd);
                return;
            }

            int passes = MIN_MOTIF_PASSES + rng.nextInt(MAX_MOTIF_PASSES - MIN_MOTIF_PASSES + 1);

            LOG.debug("[SealSigilShapeSetFloral2] Carving floral slice: sliceIndex={} slices={} passes={}",
                    sliceIndex, slices, passes);

            // 1) Foundational: central blossom + petal ring.
            carveCentralRosette(pixels, cx, cy, radius, radiusSq, rng);
            carveInnerPetalRing(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);

            // 2) High-level motif passes: vines, wreaths, leaf clusters.
            for (int pass = 0; pass < passes; pass++) {
                try {
                    int recipe = rng.nextInt(3);
                    switch (recipe) {
                        case 0 -> carveCurvingVineArc(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        case 1 -> carveOuterLeafWreath(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        case 2 -> carveLeafClusterMotif(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        default -> carveCurvingVineArc(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                    }
                } catch (Throwable t) {
                    LOG.error("[SealSigilShapeSetFloral2] Motif pass {} failed in slice {}", pass, sliceIndex, t);
                }
            }

            // 3) Final finishing buds / seeds.
            carveBudField(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);

            // Debug count
            try {
                int count = 0;
                for (int y = 0; y < size; y++) {
                    boolean[] row = pixels[y];
                    for (int x = 0; x < size; x++) {
                        if (row[x]) count++;
                    }
                }
                LOG.debug("[SealSigilShapeSetFloral2] applyShapesInSlice: sliceIndex={} markedPixels={}",
                        sliceIndex, count);
            } catch (Throwable t) {
                LOG.error("[SealSigilShapeSetFloral2] counting marked pixels failed", t);
            }

        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] applyShapesInSlice failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Foundational: central blossom / rosette
    // -------------------------------------------------------------------------

    private void carveCentralRosette(boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     RandomSource rng) {
        try {
            // Larger baseScale => big central flower.
            double baseScale = BAND_INNER_FRACTION * (1.10 + rng.nextDouble() * 0.40); // ~0.37–0.65 of radius
            int outerR = Math.max(6, (int) Math.round(radius * baseScale));
            int innerR = Math.max(3, (int) Math.round(outerR * 0.45));

            int petals = 7 + rng.nextInt(3); // 7–9 petals
            carvePetalRosette(pixels, cx, cy, innerR, outerR, petals, cx, cy, radiusSq);

            // Central disc (flower core) scaled up a bit.
            int coreR = Math.max(MIN_DETAIL_RADIUS + 2, outerR / 3);
            carveDisc(pixels, cx, cy, coreR, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveCentralRosette failed", t);
        }
    }

    private void carvePetalRosette(boolean[][] pixels,
                                   int cxFlower,
                                   int cyFlower,
                                   int innerR,
                                   int outerR,
                                   int petals,
                                   int cx,
                                   int cy,
                                   double radiusSq) {
        try {
            if (petals < 3) return;

            double twoPi = Math.PI * 2.0;
            double step = twoPi / petals;
            double startAngle = -Math.PI / 2.0;

            for (int i = 0; i < petals; i++) {
                double angle = startAngle + i * step;
                carveSinglePetal(pixels, cxFlower, cyFlower, innerR, outerR, angle, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carvePetalRosette failed", t);
        }
    }

    /**
     * Almond-shaped petal between innerR and outerR along a given central angle.
     * Width increased for bolder petals.
     */
    private void carveSinglePetal(boolean[][] pixels,
                                  int cxFlower,
                                  int cyFlower,
                                  int innerR,
                                  int outerR,
                                  double angle,
                                  int cx,
                                  int cy,
                                  double radiusSq) {
        try {
            int samples = 12;
            int[] xsA = new int[samples];
            int[] ysA = new int[samples];
            int[] xsB = new int[samples];
            int[] ysB = new int[samples];

            // Slight petal curvature
            double spreadAngle = Math.toRadians(16.0); // wider petal
            double halfSpread = spreadAngle * 0.5;

            for (int i = 0; i < samples; i++) {
                double t = i / (double) (samples - 1);

                double midR = innerR + (outerR - innerR) * t;
                double width = (outerR - innerR) * 0.40 * Math.sin(t * Math.PI); // thicker petals

                double offset = width;
                double angleLeft = angle - halfSpread;
                double angleRight = angle + halfSpread;

                // Left boundary
                double pxA = cxFlower + Math.cos(angleLeft) * midR - Math.sin(angle) * offset;
                double pyA = cyFlower + Math.sin(angleLeft) * midR + Math.cos(angle) * offset;

                // Right boundary
                double pxB = cxFlower + Math.cos(angleRight) * midR + Math.sin(angle) * offset;
                double pyB = cyFlower + Math.sin(angleRight) * midR - Math.cos(angle) * offset;

                xsA[i] = (int) Math.round(pxA);
                ysA[i] = (int) Math.round(pyA);
                xsB[i] = (int) Math.round(pxB);
                ysB[i] = (int) Math.round(pyB);
            }

            // Build polygon from A forward and B backward.
            int count = samples * 2;
            int[] xs = new int[count];
            int[] ys = new int[count];

            for (int i = 0; i < samples; i++) {
                xs[i] = xsA[i];
                ys[i] = ysA[i];
                xs[count - 1 - i] = xsB[i];
                ys[count - 1 - i] = ysB[i];
            }

            carvePolygon(pixels, xs, ys, count, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveSinglePetal failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Foundational: inner petal ring around the blossom
    // -------------------------------------------------------------------------

    private void carveInnerPetalRing(boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     double sliceStart,
                                     double sliceEnd,
                                     RandomSource rng) {
        try {
            double span = sliceEnd - sliceStart;

            // Push the ring further out and make the petals bigger.
            double baseR = radius * BAND_INNER_FRACTION * (1.45 + rng.nextDouble() * 0.30); // ~0.49–0.74 of radius
            int petalCount = 2 + rng.nextInt(2); // 2–3 per slice for large, readable shapes

            int innerR = Math.max(3, (int) Math.round(radius * 0.14));
            int outerR = innerR + Math.max(3, radius / 6);

            for (int i = 0; i < petalCount; i++) {
                double t = (i + 0.5) / petalCount;
                double angle = sliceStart + span * t;

                int centerX = cx + (int) Math.round(Math.cos(angle) * baseR);
                int centerY = cy + (int) Math.round(Math.sin(angle) * baseR);
                if (!insideCircle(centerX, centerY, cx, cy, radiusSq)) continue;

                carveSinglePetal(pixels, centerX, centerY, innerR, outerR, angle, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveInnerPetalRing failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Motif: curving vine through the mid band
    // -------------------------------------------------------------------------

    private void carveCurvingVineArc(boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     double sliceStart,
                                     double sliceEnd,
                                     RandomSource rng,
                                     int passIndex) {
        try {
            double span = sliceEnd - sliceStart;

            // Place the vine in a broad mid-band and make it thick.
            double baseR = radius * BAND_MID_FRACTION * (1.00 + rng.nextDouble() * 0.15);
            double vineThickness = radius * 0.045; // thicker

            // Use a large portion of the slice for a bold arc.
            double startAngle = sliceStart + span * (0.05 + rng.nextDouble() * 0.03);
            double endAngle   = sliceEnd   - span * (0.05 + rng.nextDouble() * 0.03);
            if (endAngle <= startAngle) return;

            carveThickArc(pixels, cx, cy, radiusSq, baseR, vineThickness, startAngle, endAngle, 11, cx, cy);

            // Leaves along the vine.
            if (rng.nextFloat() < 0.9f) {
                carveVineLeaves(pixels, cx, cy, radius, radiusSq, baseR, vineThickness, startAngle, endAngle, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveCurvingVineArc failed", t);
        }
    }

    private void carveThickArc(boolean[][] pixels,
                               int cx,
                               int cy,
                               double radiusSq,
                               double ringR,
                               double thickness,
                               double angleStart,
                               double angleEnd,
                               int segments,
                               int circleCx,
                               int circleCy) {
        try {
            if (segments < 2) segments = 2;
            int size = pixels.length;
            double halfT = thickness * 0.5;

            double prevAngle = angleStart;
            for (int i = 1; i <= segments; i++) {
                double t = i / (double) segments;
                double angle = angleStart + (angleEnd - angleStart) * t;

                int sx0 = cx + (int) Math.round(Math.cos(prevAngle) * ringR);
                int sy0 = cy + (int) Math.round(Math.sin(prevAngle) * ringR);
                int sx1 = cx + (int) Math.round(Math.cos(angle) * ringR);
                int sy1 = cy + (int) Math.round(Math.sin(angle) * ringR);

                int lineThickness = Math.max(2, (int) Math.round(halfT)); // ensure at least 2
                carveLine(pixels, sx0, sy0, sx1, sy1, lineThickness, circleCx, circleCy, radiusSq);

                prevAngle = angle;
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveThickArc failed", t);
        }
    }

    private void carveVineLeaves(boolean[][] pixels,
                                 int cx,
                                 int cy,
                                 int radius,
                                 double radiusSq,
                                 double baseR,
                                 double thickness,
                                 double angleStart,
                                 double angleEnd,
                                 RandomSource rng) {
        try {
            int leafCount = 3 + rng.nextInt(3); // 3–5
            double span = angleEnd - angleStart;
            double leafR = baseR + thickness * 0.8;
            int innerR = Math.max(3, radius / 10);
            int outerR = innerR + Math.max(3, radius / 7);

            for (int i = 0; i < leafCount; i++) {
                double t = (i + 0.5) / leafCount;
                double angle = angleStart + span * t;

                double normalAngle = angle + (rng.nextBoolean() ? Math.PI / 2.0 : -Math.PI / 2.0);
                int baseX = cx + (int) Math.round(Math.cos(angle) * leafR);
                int baseY = cy + (int) Math.round(Math.sin(angle) * leafR);
                if (!insideCircle(baseX, baseY, cx, cy, radiusSq)) continue;

                carveSinglePetal(pixels, baseX, baseY, innerR, outerR, normalAngle, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveVineLeaves failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Motif: outer leaf wreath
    // -------------------------------------------------------------------------

    private void carveOuterLeafWreath(boolean[][] pixels,
                                      int cx,
                                      int cy,
                                      int radius,
                                      double radiusSq,
                                      double sliceStart,
                                      double sliceEnd,
                                      RandomSource rng,
                                      int passIndex) {
        try {
            double span = sliceEnd - sliceStart;
            // Bring wreath closer to the rim.
            double baseR = radius * BAND_OUTER_FRACTION * (0.96 + rng.nextDouble() * 0.08);

            int leafPairs = 2 + rng.nextInt(2); // 2–3 leaf pairs
            int innerR = Math.max(3, radius / 10);
            int outerR = innerR + Math.max(3, radius / 7);

            for (int i = 0; i < leafPairs; i++) {
                double t = (i + 0.5) / leafPairs;
                double angle = sliceStart + span * t;

                int centerX = cx + (int) Math.round(Math.cos(angle) * baseR);
                int centerY = cy + (int) Math.round(Math.sin(angle) * baseR);
                if (!insideCircle(centerX, centerY, cx, cy, radiusSq)) continue;

                // Two leaves mirrored around the radial direction.
                double dir = angle;
                carveSinglePetal(pixels, centerX, centerY, innerR, outerR, dir + Math.toRadians(20.0), cx, cy, radiusSq);
                carveSinglePetal(pixels, centerX, centerY, innerR, outerR, dir - Math.toRadians(20.0), cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveOuterLeafWreath failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Motif: leaf clusters floating in mid / inner band
    // -------------------------------------------------------------------------

    private void carveLeafClusterMotif(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       double sliceStart,
                                       double sliceEnd,
                                       RandomSource rng,
                                       int passIndex) {
        try {
            double span = sliceEnd - sliceStart;

            int clusterCount = 1 + rng.nextInt(2); // 1–2 clusters
            for (int c = 0; c < clusterCount; c++) {
                double t = (c + 0.5) / clusterCount;
                double angle = sliceStart + span * t;

                double rNorm = BAND_INNER_FRACTION + rng.nextDouble() * (BAND_MID_FRACTION - BAND_INNER_FRACTION);
                double r = radius * rNorm;

                int baseX = cx + (int) Math.round(Math.cos(angle) * r);
                int baseY = cy + (int) Math.round(Math.sin(angle) * r);
                if (!insideCircle(baseX, baseY, cx, cy, radiusSq)) continue;

                carveLeafCluster(pixels, baseX, baseY, radius, radiusSq, angle, rng, cx, cy);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveLeafClusterMotif failed", t);
        }
    }

    private void carveLeafCluster(boolean[][] pixels,
                                  int baseX,
                                  int baseY,
                                  int radius,
                                  double radiusSq,
                                  double baseAngle,
                                  RandomSource rng,
                                  int cx,
                                  int cy) {
        try {
            int leaves = 3 + rng.nextInt(2); // 3–4 leaves clustered
            int innerR = Math.max(3, radius / 11);
            int outerR = innerR + Math.max(3, radius / 7);

            for (int i = 0; i < leaves; i++) {
                double offsetAngle = baseAngle + (rng.nextDouble() - 0.5) * Math.toRadians(32.0);
                double offsetDist = radius * 0.06 * (0.6 + rng.nextDouble() * 0.8); // more spread

                int cxLeaf = baseX + (int) Math.round(Math.cos(offsetAngle) * offsetDist);
                int cyLeaf = baseY + (int) Math.round(Math.sin(offsetAngle) * offsetDist);

                if (!insideCircle(cxLeaf, cyLeaf, cx, cy, radiusSq)) continue;
                carveSinglePetal(pixels, cxLeaf, cyLeaf, innerR, outerR, offsetAngle, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveLeafCluster failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Final: buds / seeds sprinkled across
    // -------------------------------------------------------------------------

    private void carveBudField(boolean[][] pixels,
                               int cx,
                               int cy,
                               int radius,
                               double radiusSq,
                               double sliceStart,
                               double sliceEnd,
                               RandomSource rng) {
        try {
            int budCount = 3 + rng.nextInt(3); // 3–5, keep it light
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < budCount; i++) {
                double angle = sliceStart + span * rng.nextDouble();
                double rNorm = BAND_INNER_FRACTION + rng.nextDouble() * (BAND_OUTER_FRACTION - BAND_INNER_FRACTION);
                double r = radius * rNorm;

                int x = cx + (int) Math.round(Math.cos(angle) * r);
                int y = cy + (int) Math.round(Math.sin(angle) * r);
                if (!insideCircle(x, y, cx, cy, radiusSq)) continue;

                int budR = MIN_DETAIL_RADIUS + 2; // slightly bigger buds
                carveDisc(pixels, x, y, budR, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveBudField failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers (these MARK pixels as true in the mask)
    // -------------------------------------------------------------------------

    private boolean insideCircle(int x, int y, int cx, int cy, double radiusSq) {
        int dx = x - cx;
        int dy = y - cy;
        return dx * dx + dy * dy <= radiusSq;
    }

    private void carveDisc(boolean[][] pixels,
                           int centerX,
                           int centerY,
                           int r,
                           int cx,
                           int cy,
                           double radiusSq) {
        try {
            if (r <= 0) return;
            int size = pixels.length;

            int minY = Math.max(0, centerY - r);
            int maxY = Math.min(size - 1, centerY + r);
            int minX = Math.max(0, centerX - r);
            int maxX = Math.min(size - 1, centerX + r);

            int rSq = r * r;

            for (int y = minY; y <= maxY; y++) {
                int dy = y - centerY;
                int dySq = dy * dy;
                for (int x = minX; x <= maxX; x++) {
                    int dx = x - centerX;
                    int d2 = dx * dx + dySq;
                    if (d2 <= rSq && insideCircle(x, y, cx, cy, radiusSq)) {
                        pixels[y][x] = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveDisc failed", t);
        }
    }

    private void carveRing(boolean[][] pixels,
                           int centerX,
                           int centerY,
                           int innerRadius,
                           int outerRadius,
                           int cx,
                           int cy,
                           double radiusSq) {
        try {
            if (outerRadius <= 0 || innerRadius < 0 || outerRadius <= innerRadius) return;
            int size = pixels.length;

            int minY = Math.max(0, centerY - outerRadius);
            int maxY = Math.min(size - 1, centerY + outerRadius);
            int minX = Math.max(0, centerX - outerRadius);
            int maxX = Math.min(size - 1, centerX + outerRadius);

            int outerSq = outerRadius * outerRadius;
            int innerSq = innerRadius * innerRadius;

            for (int y = minY; y <= maxY; y++) {
                int dy = y - centerY;
                int dySq = dy * dy;
                for (int x = minX; x <= maxX; x++) {
                    int dx = x - centerX;
                    int d2 = dx * dx + dySq;
                    if (d2 <= outerSq && d2 >= innerSq && insideCircle(x, y, cx, cy, radiusSq)) {
                        pixels[y][x] = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveRing failed", t);
        }
    }

    private void carveLine(boolean[][] pixels,
                           int x0,
                           int y0,
                           int x1,
                           int y1,
                           int thickness,
                           int cx,
                           int cy,
                           double radiusSq) {
        try {
            int dx = Math.abs(x1 - x0);
            int dy = Math.abs(y1 - y0);
            int sx = x0 < x1 ? 1 : -1;
            int sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;

            int size = pixels.length;
            int r = Math.max(0, thickness - 1);

            while (true) {
                if (x0 >= 0 && y0 >= 0 && x0 < size && y0 < size && insideCircle(x0, y0, cx, cy, radiusSq)) {
                    if (r == 0) {
                        pixels[y0][x0] = true;
                    } else {
                        for (int oy = -r; oy <= r; oy++) {
                            int ny = y0 + oy;
                            if (ny < 0 || ny >= size) continue;
                            for (int ox = -r; ox <= r; ox++) {
                                int nx = x0 + ox;
                                if (nx < 0 || nx >= size) continue;
                                if (insideCircle(nx, ny, cx, cy, radiusSq)) {
                                    pixels[ny][nx] = true;
                                }
                            }
                        }
                    }
                }

                if (x0 == x1 && y0 == y1) break;
                int e2 = err * 2;
                if (e2 > -dy) {
                    err -= dy;
                    x0 += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    y0 += sy;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carveLine failed", t);
        }
    }

    private void carvePolygon(boolean[][] pixels,
                              int[] xs,
                              int[] ys,
                              int count,
                              int cx,
                              int cy,
                              double radiusSq) {
        try {
            if (xs == null || ys == null || count <= 1) return;
            int size = pixels.length;

            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                int y = ys[i];
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }

            minY = Math.max(0, Math.min(minY, size - 1));
            maxY = Math.max(0, Math.min(maxY, size - 1));
            if (minY > maxY) return;

            int[] xIntersections = new int[count];

            for (int y = minY; y <= maxY; y++) {
                int interCount = 0;
                for (int i = 0; i < count; i++) {
                    int j = (i + 1) % count;
                    int x1 = xs[i];
                    int y1 = ys[i];
                    int x2 = xs[j];
                    int y2 = ys[j];

                    if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
                        double t = (y - y1) / (double) (y2 - y1);
                        int x = x1 + (int) Math.round(t * (x2 - x1));
                        xIntersections[interCount++] = x;
                    }
                }

                if (interCount <= 0) continue;
                java.util.Arrays.sort(xIntersections, 0, interCount);

                for (int k = 0; k < interCount; k += 2) {
                    int xStart = xIntersections[k];
                    int xEnd = (k + 1 < interCount) ? xIntersections[k + 1] : xStart;
                    if (xEnd < xStart) {
                        int tmp = xStart;
                        xStart = xEnd;
                        xEnd = tmp;
                    }

                    xStart = Math.max(0, xStart);
                    xEnd = Math.min(size - 1, xEnd);

                    for (int x = xStart; x <= xEnd; x++) {
                        if (insideCircle(x, y, cx, cy, radiusSq)) {
                            pixels[y][x] = true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetFloral2] carvePolygon failed", t);
        }
    }
}
