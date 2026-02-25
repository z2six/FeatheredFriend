// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilShapeSetMedieval0.java
package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilShapeSetMedieval0.java
 *
 * Shape set 0 — "Medieval"
 *
 * This shape set is responsible for marking medieval-themed geometry in a
 * shape mask. The main generator:
 *
 *  - Starts with a filled disc inside a wavy outer boundary.
 *  - Builds a shape mask (boolean[][]) in which:
 *        true  => pixel belongs to a motif that should be carved out
 *        false => untouched wax
 *  - Selects a number of radial slices (2..8).
 *  - For one base slice, calls this shape set to mark detail.
 *  - Then mirrors/rotates the base slice into all slices.
 *
 * This class assumes it is applied to a single slice (identified by sliceIndex),
 * but it writes directly into the full [pixels] buffer:
 *   - "Mark shape" = set pixels[y][x] = true.
 *
 * The goal: heavily stylized medieval sigils with:
 *   - Heraldic cross clusters
 *   - Crenellated battlements / towers
 *   - Shield bosses, kite shields
 *   - Swords, lances, and spear motifs
 *   - Cathedral-like window arcs and tracery
 *   - Runic bands, knot rails, braided rings
 *   - Iron spikes, chained arcs and rivets
 *
 * Tweaked for:
 *   - Fewer, larger motifs (quality over quantity).
 *   - Reduced jitter and micro-detail to avoid noisy overlap.
 *
 * All methods are defensive:
 *   - No exceptions bubble out: everything is wrapped in try/catch with logging.
 *   - Out-of-bounds access is guarded.
 *   - RandomSource is deterministic and provided by the caller.
 */
public final class SealSigilShapeSetMedieval0 implements SealSigilShapeSet {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Number of high-level motif passes per slice.
     * Each pass picks a different motif recipes to mark.
     *
     * Reduced for cleaner, larger designs.
     */
    private static final int MIN_MOTIF_PASSES = 1;
    private static final int MAX_MOTIF_PASSES = 3;

    /**
     * Utility constants for radial bands, all expressed as normalized radius.
     */
    private static final double BAND_INNER_FRACTION = 0.25;
    private static final double BAND_MID_FRACTION   = 0.5;
    private static final double BAND_OUTER_FRACTION = 0.78;

    /**
     * Minimal angular padding between slice boundaries and shapes, so we
     * don't clip too harshly on the slice border.
     */
    private static final double SLICE_ANGULAR_MARGIN = Math.toRadians(3.0);

    /**
     * Small epsilon radius used when marking tiny detail dots / rivets.
     */
    private static final int MIN_DETAIL_RADIUS = 1;

    /**
     * Constructs medieval-themed geometry inside a single slice.
     *
     * The generator guarantees:
     *  - pixels is a square [size x size] array, initially all false.
     *  - We mark shape pixels by setting them to true.
     */
    @Override
    public void applyShapesInSlice(boolean[][] pixels,
                                   int cx,
                                   int cy,
                                   int radius,
                                   double radiusSq,
                                   int slices,
                                   int sliceIndex,
                                   @NotNull RandomSource rng) {
        try {
            if (pixels == null || pixels.length == 0) {
                LOG.warn("[SealSigilShapeSetMedieval0] applyShapesInSlice called with null/empty pixels");
                return;
            }
            if (slices <= 0) {
                LOG.warn("[SealSigilShapeSetMedieval0] slices <= 0 ({}), nothing to do", slices);
                return;
            }
            if (sliceIndex < 0 || sliceIndex >= slices) {
                LOG.warn("[SealSigilShapeSetMedieval0] sliceIndex {} out of [0, {}), aborting slice carving",
                        sliceIndex, slices);
                return;
            }

            double sliceAngleSpan = (Math.PI * 2.0) / (double) slices;
            double sliceStart = sliceIndex * sliceAngleSpan;
            double sliceEnd = sliceStart + sliceAngleSpan;

            // Apply a small angular margin so shapes don't hug the slice borders too tightly.
            sliceStart += SLICE_ANGULAR_MARGIN;
            sliceEnd   -= SLICE_ANGULAR_MARGIN;
            if (sliceEnd <= sliceStart) {
                // Degenerate slice — nothing to carve.
                LOG.debug("[SealSigilShapeSetMedieval0] Degenerate slice bounds after margin: start={} end={}",
                        sliceStart, sliceEnd);
                return;
            }

            int passes = MIN_MOTIF_PASSES + rng.nextInt(MAX_MOTIF_PASSES - MIN_MOTIF_PASSES + 1);

            LOG.debug("[SealSigilShapeSetMedieval0] Carving medieval slice: sliceIndex={} slices={} passes={}",
                    sliceIndex, slices, passes);

            // 1) Foundational motifs: crosses, shields, towers.
            carveHeraldicCrossCluster(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);
            carveShieldAndBossBand(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);

            // 2) Motif passes; each selects a recipes to carve more features.
            for (int pass = 0; pass < passes; pass++) {
                try {
                    int recipe = rng.nextInt(5); // drop one recipes to cut clutter
                    switch (recipe) {
                        case 0 -> carveCrenellatedTowerRim(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        case 1 -> carveCathedralArcTracery(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        case 2 -> carveSwordAndLanceMotifs(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        case 3 -> carveRunicInnerBand(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        case 4 -> carveBraidedKnotRail(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        default -> carveSwordAndLanceMotifs(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                    }
                } catch (Throwable t) {
                    LOG.error("[SealSigilShapeSetMedieval0] Motif pass {} failed in slice {}", pass, sliceIndex, t);
                }
            }

            // 3) Final small detail speckling: rivets & nicks (reduced density).
            carveRivetsAndBattleNicks(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);

            // Debug: count marked pixels in this slice mask.
            try {
                int size = pixels.length;
                int count = 0;
                for (int y = 0; y < size; y++) {
                    boolean[] row = pixels[y];
                    for (int x = 0; x < size; x++) {
                        if (row[x]) count++;
                    }
                }
                LOG.debug("[SealSigilShapeSetMedieval0] applyShapesInSlice: sliceIndex={} markedPixels={}",
                        sliceIndex, count);
            } catch (Throwable t) {
                LOG.error("[SealSigilShapeSetMedieval0] counting marked pixels failed", t);
            }

        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] applyShapesInSlice failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // High-level motif: Heraldic cross cluster
    // -------------------------------------------------------------------------

    private void carveHeraldicCrossCluster(boolean[][] pixels,
                                           int cx,
                                           int cy,
                                           int radius,
                                           double radiusSq,
                                           double sliceStart,
                                           double sliceEnd,
                                           @NotNull RandomSource rng) {
        try {
            double sliceMid = (sliceStart + sliceEnd) * 0.5;
            // Slightly more stable placement, less jitter.
            double baseRNorm = 0.26 + rng.nextDouble() * 0.04; // 0.26–0.30
            double baseR = radius * baseRNorm;

            int clusterCount = 1 + (rng.nextInt(100) < 35 ? 1 : 0); // mostly 1, sometimes 2
            for (int i = 0; i < clusterCount; i++) {
                double jitterAngle = (rng.nextDouble() - 0.5) * (sliceEnd - sliceStart) * 0.15;
                double jitterRadius = (rng.nextDouble() - 0.5) * radius * 0.03;

                double angle = sliceMid + jitterAngle;
                double r = baseR + jitterRadius;

                int centerX = cx + (int) Math.round(Math.cos(angle) * r);
                int centerY = cy + (int) Math.round(Math.sin(angle) * r);

                if (!insideCircle(centerX, centerY, cx, cy, radiusSq)) {
                    continue;
                }

                int style = rng.nextInt(3);
                switch (style) {
                    case 0 -> carveCrossPate(pixels, centerX, centerY, radius, cx, cy, radiusSq, rng);
                    case 1 -> carveCrossFleury(pixels, centerX, centerY, radius, cx, cy, radiusSq, rng);
                    case 2 -> carveWheelCross(pixels, centerX, centerY, radius, cx, cy, radiusSq, rng);
                    default -> carveCrossPate(pixels, centerX, centerY, radius, cx, cy, radiusSq, rng);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveHeraldicCrossCluster failed", t);
        }
    }

    private void carveCrossPate(boolean[][] pixels,
                                int centerX,
                                int centerY,
                                int globalRadius,
                                int cx,
                                int cy,
                                double radiusSq,
                                @NotNull RandomSource rng) {
        try {
            int armLength = Math.max(4, globalRadius / 7);
            int armWidth  = Math.max(2, globalRadius / 14);

            carveRoundedArm(pixels, centerX, centerY, 0.0, armLength, armWidth, cx, cy, radiusSq);
            carveRoundedArm(pixels, centerX, centerY, Math.PI / 2.0, armLength, armWidth, cx, cy, radiusSq);
            carveRoundedArm(pixels, centerX, centerY, Math.PI, armLength, armWidth, cx, cy, radiusSq);
            carveRoundedArm(pixels, centerX, centerY, -Math.PI / 2.0, armLength, armWidth, cx, cy, radiusSq);

            int bossR = Math.max(MIN_DETAIL_RADIUS + 1, armWidth);
            carveDisc(pixels, centerX, centerY, bossR, cx, cy, radiusSq);

        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveCrossPate failed", t);
        }
    }

    private void carveCrossFleury(boolean[][] pixels,
                                  int centerX,
                                  int centerY,
                                  int globalRadius,
                                  int cx,
                                  int cy,
                                  double radiusSq,
                                  @NotNull RandomSource rng) {
        try {
            int armLength = Math.max(5, globalRadius / 6);
            int armWidth = Math.max(3, globalRadius / 14);

            for (int i = 0; i < 4; i++) {
                double angle = i * (Math.PI / 2.0);
                carveFlaredArm(pixels, centerX, centerY, angle, armLength, armWidth, cx, cy, radiusSq, rng);
            }

            int innerR = Math.max(MIN_DETAIL_RADIUS + 1, globalRadius / 14);
            carveDisc(pixels, centerX, centerY, innerR, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveCrossFleury failed", t);
        }
    }

    private void carveWheelCross(boolean[][] pixels,
                                 int centerX,
                                 int centerY,
                                 int globalRadius,
                                 int cx,
                                 int cy,
                                 double radiusSq,
                                 @NotNull RandomSource rng) {
        try {
            int ringOuter = Math.max(5, globalRadius / 4);
            int ringInner = Math.max(2, ringOuter - 3);

            carveRing(pixels, centerX, centerY, ringInner, ringOuter, cx, cy, radiusSq);

            int spokeCount = 4 + rng.nextInt(2); // 4–5
            for (int i = 0; i < spokeCount; i++) {
                double angle = (Math.PI * 2.0 * i) / spokeCount;
                int x1 = centerX + (int) Math.round(Math.cos(angle) * ringInner);
                int y1 = centerY + (int) Math.round(Math.sin(angle) * ringInner);
                int x2 = centerX + (int) Math.round(Math.cos(angle) * ringOuter);
                int y2 = centerY + (int) Math.round(Math.sin(angle) * ringOuter);
                carveLine(pixels, x1, y1, x2, y2, 1, cx, cy, radiusSq);
            }

            int hubR = Math.max(MIN_DETAIL_RADIUS + 1, globalRadius / 16);
            carveDisc(pixels, centerX, centerY, hubR, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveWheelCross failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // High-level motif: Shields and bosses band
    // -------------------------------------------------------------------------

    private void carveShieldAndBossBand(boolean[][] pixels,
                                        int cx,
                                        int cy,
                                        int radius,
                                        double radiusSq,
                                        double sliceStart,
                                        double sliceEnd,
                                        @NotNull RandomSource rng) {
        try {
            double bandInnerNorm = BAND_MID_FRACTION;
            double bandOuterNorm = BAND_OUTER_FRACTION * 0.9;

            double bandInner = radius * bandInnerNorm;
            double bandOuter = radius * bandOuterNorm;

            int shieldCount = 1 + (rng.nextInt(100) < 50 ? 1 : 0); // 1 or 2, biased to 1
            for (int i = 0; i < shieldCount; i++) {
                double t = (i + 0.5) / shieldCount;
                double angle = sliceStart + (sliceEnd - sliceStart) * t;
                double r = bandInner + (bandOuter - bandInner) * (0.5 + rng.nextDouble() * 0.2);
                int shieldCx = cx + (int) Math.round(Math.cos(angle) * r);
                int shieldCy = cy + (int) Math.round(Math.sin(angle) * r);

                if (!insideCircle(shieldCx, shieldCy, cx, cy, radiusSq)) {
                    continue;
                }

                if (rng.nextBoolean()) {
                    carveKiteShield(pixels, shieldCx, shieldCy, radius, cx, cy, radiusSq, rng);
                } else {
                    carveHeaterShield(pixels, shieldCx, shieldCy, radius, cx, cy, radiusSq, rng);
                }

                int bossR = Math.max(MIN_DETAIL_RADIUS + 1, radius / 18);
                carveDisc(pixels, shieldCx, shieldCy, bossR, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveShieldAndBossBand failed", t);
        }
    }

    private void carveKiteShield(boolean[][] pixels,
                                 int centerX,
                                 int centerY,
                                 int globalRadius,
                                 int cx,
                                 int cy,
                                 double radiusSq,
                                 @NotNull RandomSource rng) {
        try {
            int h = Math.max(8, globalRadius / 3);
            int w = Math.max(6, globalRadius / 5);

            int topX = centerX;
            int topY = centerY - h / 2;

            int leftX = centerX - w / 2;
            int leftY = centerY - h / 4;

            int rightX = centerX + w / 2;
            int rightY = centerY - h / 4;

            int bottomX = centerX;
            int bottomY = centerY + h / 2;

            int[] xs = {topX, rightX, bottomX, leftX};
            int[] ys = {topY, rightY, bottomY, leftY};

            carvePolygon(pixels, xs, ys, 4, cx, cy, radiusSq);

            if (rng.nextFloat() < 0.6f) {
                carveLine(pixels, topX, (topY + centerY) / 2, bottomX, bottomY, 1, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveKiteShield failed", t);
        }
    }

    private void carveHeaterShield(boolean[][] pixels,
                                   int centerX,
                                   int centerY,
                                   int globalRadius,
                                   int cx,
                                   int cy,
                                   double radiusSq,
                                   @NotNull RandomSource rng) {
        try {
            int h = Math.max(7, globalRadius / 4);
            int w = Math.max(7, globalRadius / 4);

            int topLeftX = centerX - w / 2;
            int topLeftY = centerY - h / 2;

            int topRightX = centerX + w / 2;
            int topRightY = centerY - h / 2;

            int bottomX = centerX;
            int bottomY = centerY + h / 2;

            int[] xs = {topLeftX, topRightX, bottomX};
            int[] ys = {topLeftY, topRightY, bottomY};

            carvePolygon(pixels, xs, ys, 3, cx, cy, radiusSq);

            if (rng.nextFloat() < 0.5f) {
                int midTopX = (topLeftX + topRightX) / 2;
                int midTopY = topLeftY;
                carveLine(pixels, midTopX, midTopY, bottomX, bottomY, 1, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveHeaterShield failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // High-level motif: Crenellated tower rim
    // -------------------------------------------------------------------------

    private void carveCrenellatedTowerRim(boolean[][] pixels,
                                          int cx,
                                          int cy,
                                          int radius,
                                          double radiusSq,
                                          double sliceStart,
                                          double sliceEnd,
                                          @NotNull RandomSource rng,
                                          int passIndex) {
        try {
            double bandRNorm = BAND_OUTER_FRACTION * (0.86 + rng.nextDouble() * 0.05);
            double towerR = radius * bandRNorm;

            int merlons = 2 + rng.nextInt(3); // 2–4 merlons in the slice
            double sliceSpan = sliceEnd - sliceStart;
            double step = sliceSpan / (merlons * 2.0);

            for (int i = 0; i < merlons; i++) {
                double centerAngle = sliceStart + (2 * i + 1) * step;
                double halfWidth = step * 0.3;
                carveMerlon(pixels, cx, cy, towerR, centerAngle, halfWidth, radius, radiusSq, rng);
            }

            if (rng.nextFloat() < 0.6f) {
                carveArrowSlitsUnderMerlons(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, towerR, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveCrenellatedTowerRim failed", t);
        }
    }

    private void carveMerlon(boolean[][] pixels,
                             int cx,
                             int cy,
                             double ringRadius,
                             double centerAngle,
                             double halfAngularWidth,
                             int radius,
                             double radiusSq,
                             @NotNull RandomSource rng) {
        try {
            double innerR = ringRadius - radius * 0.04;
            double outerR = ringRadius + radius * 0.035;

            int samples = 5;
            int[] xs = new int[samples];
            int[] ys = new int[samples];

            double startAngle = centerAngle - halfAngularWidth;
            double endAngle = centerAngle + halfAngularWidth;

            for (int i = 0; i < samples; i++) {
                double t = i / (double) (samples - 1);
                double angle = startAngle + t * (endAngle - startAngle);
                double r = (i == 0 || i == samples - 1) ? innerR : outerR;

                xs[i] = cx + (int) Math.round(Math.cos(angle) * r);
                ys[i] = cy + (int) Math.round(Math.sin(angle) * r);
            }

            carvePolygon(pixels, xs, ys, samples, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveMerlon failed", t);
        }
    }

    private void carveArrowSlitsUnderMerlons(boolean[][] pixels,
                                             int cx,
                                             int cy,
                                             int radius,
                                             double radiusSq,
                                             double sliceStart,
                                             double sliceEnd,
                                             double baseRingR,
                                             @NotNull RandomSource rng) {
        try {
            int slitCount = 1 + rng.nextInt(2); // 1–2
            double span = sliceEnd - sliceStart;
            for (int i = 0; i < slitCount; i++) {
                double t = (i + 0.5) / slitCount;
                double angle = sliceStart + span * t;
                carveArrowSlit(pixels, cx, cy, radius, radiusSq, angle, baseRingR, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveArrowSlitsUnderMerlons failed", t);
        }
    }

    private void carveArrowSlit(boolean[][] pixels,
                                int cx,
                                int cy,
                                int radius,
                                double radiusSq,
                                double angle,
                                double baseRingR,
                                @NotNull RandomSource rng) {
        try {
            double inner = baseRingR - radius * 0.12;
            double outer = baseRingR + radius * 0.015;

            int innerX = cx + (int) Math.round(Math.cos(angle) * inner);
            int innerY = cy + (int) Math.round(Math.sin(angle) * inner);
            int outerX = cx + (int) Math.round(Math.cos(angle) * outer);
            int outerY = cy + (int) Math.round(Math.sin(angle) * outer);

            carveLine(pixels, innerX, innerY, outerX, outerY, 1, cx, cy, radiusSq);

            double sideOffset = radius * 0.02;
            int baseX = cx + (int) Math.round(Math.cos(angle) * inner);
            int baseY = cy + (int) Math.round(Math.sin(angle) * inner);

            int leftX = baseX + (int) Math.round(-Math.sin(angle) * sideOffset);
            int leftY = baseY + (int) Math.round(Math.cos(angle) * sideOffset);
            int rightX = baseX - (leftX - baseX);
            int rightY = baseY - (leftY - baseY);

            carveLine(pixels, leftX, leftY, rightX, rightY, 1, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveArrowSlit failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // High-level motif: Cathedral arcs & tracery
    // -------------------------------------------------------------------------

    private void carveCathedralArcTracery(boolean[][] pixels,
                                          int cx,
                                          int cy,
                                          int radius,
                                          double radiusSq,
                                          double sliceStart,
                                          double sliceEnd,
                                          @NotNull RandomSource rng,
                                          int passIndex) {
        try {
            int windowCount = 1; // always 1 big window per slice at most

            double baseRInner = radius * (BAND_INNER_FRACTION * 1.05);
            double baseROuter = radius * (BAND_MID_FRACTION * 0.95);

            for (int i = 0; i < windowCount; i++) {
                double t = 0.5;
                double midAngle = sliceStart + (sliceEnd - sliceStart) * t;

                carvePointedWindow(pixels, cx, cy, radius, radiusSq, midAngle, baseRInner, baseROuter, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveCathedralArcTracery failed", t);
        }
    }

    private void carvePointedWindow(boolean[][] pixels,
                                    int cx,
                                    int cy,
                                    int radius,
                                    double radiusSq,
                                    double midAngle,
                                    double rInner,
                                    double rOuter,
                                    @NotNull RandomSource rng) {
        try {
            double halfSpan = (Math.PI / 18.0) * (0.9 + rng.nextDouble() * 0.4);

            int samples = 10;
            int[] xs = new int[samples];
            int[] ys = new int[samples];

            double startAngle = midAngle - halfSpan;
            double endAngle = midAngle + halfSpan;

            for (int i = 0; i < samples; i++) {
                double t = i / (double) (samples - 1);
                double theta = startAngle + (endAngle - startAngle) * t;

                double r = rInner + (rOuter - rInner) * Math.sin(t * Math.PI);
                xs[i] = cx + (int) Math.round(Math.cos(theta) * r);
                ys[i] = cy + (int) Math.round(Math.sin(theta) * r);
            }

            carvePolygon(pixels, xs, ys, samples, cx, cy, radiusSq);

            if (rng.nextFloat() < 0.7f) {
                carveRadialMullions(pixels, cx, cy, radius, radiusSq, midAngle, rInner, rOuter, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carvePointedWindow failed", t);
        }
    }

    private void carveRadialMullions(boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     double midAngle,
                                     double rInner,
                                     double rOuter,
                                     @NotNull RandomSource rng) {
        try {
            int mullions = 2 + rng.nextInt(2); // 2–3
            double spread = Math.min(Math.PI / 12.0, (rOuter - rInner) / (double) radius);

            for (int i = 0; i < mullions; i++) {
                double t = (i + 0.5) / mullions;
                double angle = midAngle - spread / 2.0 + spread * t;

                int x0 = cx + (int) Math.round(Math.cos(angle) * rInner);
                int y0 = cy + (int) Math.round(Math.sin(angle) * rInner);
                int x1 = cx + (int) Math.round(Math.cos(angle) * rOuter);
                int y1 = cy + (int) Math.round(Math.sin(angle) * rOuter);

                carveLine(pixels, x0, y0, x1, y1, 1, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveRadialMullions failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // High-level motif: Swords & lances
    // -------------------------------------------------------------------------

    private void carveSwordAndLanceMotifs(boolean[][] pixels,
                                          int cx,
                                          int cy,
                                          int radius,
                                          double radiusSq,
                                          double sliceStart,
                                          double sliceEnd,
                                          @NotNull RandomSource rng,
                                          int passIndex) {
        try {
            int motifCount = 1 + (rng.nextInt(100) < 50 ? 1 : 0); // 1–2
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < motifCount; i++) {
                double t = (i + 0.5) / motifCount;
                double angle = sliceStart + span * t;
                double baseR = radius * (BAND_MID_FRACTION * (0.95 + rng.nextDouble() * 0.1));

                int baseX = cx + (int) Math.round(Math.cos(angle) * baseR);
                int baseY = cy + (int) Math.round(Math.sin(angle) * baseR);

                if (!insideCircle(baseX, baseY, cx, cy, radiusSq)) continue;

                if (rng.nextBoolean()) {
                    carveSword(pixels, baseX, baseY, cx, cy, radius, radiusSq, angle, rng);
                } else {
                    carveLance(pixels, baseX, baseY, cx, cy, radius, radiusSq, angle, rng);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveSwordAndLanceMotifs failed", t);
        }
    }

    private void carveSword(boolean[][] pixels,
                            int baseX,
                            int baseY,
                            int cx,
                            int cy,
                            int radius,
                            double radiusSq,
                            double directionAngle,
                            @NotNull RandomSource rng) {
        try {
            double bladeLen = radius * (0.2 + rng.nextDouble() * 0.1);
            double hiltLen  = bladeLen * 0.22;

            int tipX = baseX + (int) Math.round(Math.cos(directionAngle) * bladeLen);
            int tipY = baseY + (int) Math.round(Math.sin(directionAngle) * bladeLen);

            carveLine(pixels, baseX, baseY, tipX, tipY, 1, cx, cy, radiusSq);

            double crossSpan = bladeLen * 0.3;
            int hx1 = baseX + (int) Math.round(-Math.sin(directionAngle) * crossSpan);
            int hy1 = baseY + (int) Math.round(Math.cos(directionAngle) * crossSpan);
            int hx2 = baseX - (hx1 - baseX);
            int hy2 = baseY - (hy1 - baseY);

            carveLine(pixels, hx1, hy1, hx2, hy2, 1, cx, cy, radiusSq);

            int pommelX = baseX - (int) Math.round(Math.cos(directionAngle) * hiltLen);
            int pommelY = baseY - (int) Math.round(Math.sin(directionAngle) * hiltLen);

            carveDisc(pixels, pommelX, pommelY, MIN_DETAIL_RADIUS + 1, cx, cy, radiusSq);

            if (rng.nextFloat() < 0.5f) {
                double fullerOffset = radius * 0.01;
                int fx0 = baseX;
                int fy0 = baseY;
                int fx1 = tipX - (int) Math.round(Math.cos(directionAngle) * fullerOffset);
                int fy1 = tipY - (int) Math.round(Math.sin(directionAngle) * fullerOffset);
                carveLine(pixels, fx0, fy0, fx1, fy1, 1, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveSword failed", t);
        }
    }

    private void carveLance(boolean[][] pixels,
                            int baseX,
                            int baseY,
                            int cx,
                            int cy,
                            int radius,
                            double radiusSq,
                            double directionAngle,
                            @NotNull RandomSource rng) {
        try {
            double shaftLen = radius * (0.22 + rng.nextDouble() * 0.12);
            int tipX = baseX + (int) Math.round(Math.cos(directionAngle) * shaftLen);
            int tipY = baseY + (int) Math.round(Math.sin(directionAngle) * shaftLen);

            carveLine(pixels, baseX, baseY, tipX, tipY, 1, cx, cy, radiusSq);

            double headLen = shaftLen * 0.13;
            double halfWidth = headLen * 0.55;

            int hx1 = tipX + (int) Math.round(-Math.sin(directionAngle) * halfWidth);
            int hy1 = tipY + (int) Math.round(Math.cos(directionAngle) * halfWidth);
            int hx2 = tipX - (hx1 - tipX);
            int hy2 = tipY - (hy1 - tipY);

            int hx0 = tipX + (int) Math.round(Math.cos(directionAngle) * headLen);

            carvePolygon(pixels,
                    new int[]{hx1, hx2, hx0},
                    new int[]{hy1, hy2, tipY},
                    3,
                    cx, cy, radiusSq);

            if (rng.nextFloat() < 0.6f) {
                double flagLen = shaftLen * 0.2;
                int fx0 = baseX + (int) Math.round(Math.cos(directionAngle) * shaftLen * 0.35);
                int fy0 = baseY + (int) Math.round(Math.sin(directionAngle) * shaftLen * 0.35);

                int fx1 = fx0 + (int) Math.round(-Math.sin(directionAngle) * flagLen * 0.6);
                int fy1 = fy0 + (int) Math.round(Math.cos(directionAngle) * flagLen * 0.6);

                int fx2 = fx1 + (int) Math.round(Math.cos(directionAngle) * flagLen * 0.5);
                int fy2 = fy1 + (int) Math.round(Math.sin(directionAngle) * flagLen * 0.5);

                carvePolygon(pixels,
                        new int[]{fx0, fx1, fx2},
                        new int[]{fy0, fy1, fy2},
                        3,
                        cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveLance failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // High-level motif: Runic inner band
    // -------------------------------------------------------------------------

    private void carveRunicInnerBand(boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     double sliceStart,
                                     double sliceEnd,
                                     @NotNull RandomSource rng,
                                     int passIndex) {
        try {
            double bandRNorm = BAND_INNER_FRACTION * (1.2 + rng.nextDouble() * 0.1);
            double baseR = radius * bandRNorm;

            int runeCount = 2 + rng.nextInt(3); // 2–4 runes in slice
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < runeCount; i++) {
                double t = (i + 0.5) / runeCount;
                double angle = sliceStart + span * t;
                double jitterR = (rng.nextDouble() - 0.5) * radius * 0.03;

                int runeX = cx + (int) Math.round(Math.cos(angle) * (baseR + jitterR));
                int runeY = cy + (int) Math.round(Math.sin(angle) * (baseR + jitterR));

                if (!insideCircle(runeX, runeY, cx, cy, radiusSq)) continue;

                carveRunicGlyph(pixels, runeX, runeY, cx, cy, radius, radiusSq, angle, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveRunicInnerBand failed", t);
        }
    }

    private void carveRunicGlyph(boolean[][] pixels,
                                 int centerX,
                                 int centerY,
                                 int cx,
                                 int cy,
                                 int radius,
                                 double radiusSq,
                                 double baseAngle,
                                 @NotNull RandomSource rng) {
        try {
            double scale = radius * 0.06 * (0.9 + rng.nextDouble() * 0.2);
            int style = rng.nextInt(4);

            switch (style) {
                case 0 -> carveRunicFehuLike(pixels, centerX, centerY, cx, cy, radiusSq, baseAngle, scale);
                case 1 -> carveRunicAnsuzLike(pixels, centerX, centerY, cx, cy, radiusSq, baseAngle, scale);
                case 2 -> carveRunicRaidhoLike(pixels, centerX, centerY, cx, cy, radiusSq, baseAngle, scale);
                case 3 -> carveRunicDagazLike(pixels, centerX, centerY, cx, cy, radiusSq, baseAngle, scale);
                default -> carveRunicFehuLike(pixels, centerX, centerY, cx, cy, radiusSq, baseAngle, scale);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveRunicGlyph failed", t);
        }
    }

    private void carveRunicFehuLike(boolean[][] pixels,
                                    int cxRune,
                                    int cyRune,
                                    int cx,
                                    int cy,
                                    double radiusSq,
                                    double baseAngle,
                                    double scale) {
        try {
            int len = (int) Math.round(scale * 1.4);
            int x0 = cxRune;
            int y0 = cyRune - len;
            int x1 = cxRune;
            int y1 = cyRune + len;

            carveLine(pixels, x0, y0, x1, y1, 1, cx, cy, radiusSq);

            int armLen = (int) Math.round(scale);
            int ax1 = cxRune;
            int ay1 = cyRune - len / 3;
            int ax2 = ax1 + armLen;
            int ay2 = ay1 - armLen / 2;
            carveLine(pixels, ax1, ay1, ax2, ay2, 1, cx, cy, radiusSq);

            int bx1 = cxRune;
            int by1 = cyRune;
            int bx2 = bx1 + armLen;
            int by2 = by1 - armLen / 2;
            carveLine(pixels, bx1, by1, bx2, by2, 1, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveRunicFehuLike failed", t);
        }
    }

    private void carveRunicAnsuzLike(boolean[][] pixels,
                                     int cxRune,
                                     int cyRune,
                                     int cx,
                                     int cy,
                                     double radiusSq,
                                     double baseAngle,
                                     double scale) {
        try {
            int len = (int) Math.round(scale * 1.5);
            int x0 = cxRune;
            int y0 = cyRune - len;
            int x1 = cxRune;
            int y1 = cyRune + len;
            carveLine(pixels, x0, y0, x1, y1, 1, cx, cy, radiusSq);

            int armLen = (int) Math.round(scale * 1.2);
            int ax1 = cxRune;
            int ay1 = cyRune - len / 3;
            int ax2 = ax1 + armLen;
            int ay2 = ay1 - armLen / 3;
            carveLine(pixels, ax1, ay1, ax2, ay2, 1, cx, cy, radiusSq);

            int bx1 = cxRune;
            int by1 = cyRune + len / 3;
            int bx2 = bx1 + armLen;
            int by2 = by1 - armLen / 3;
            carveLine(pixels, bx1, by1, bx2, by2, 1, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveRunicAnsuzLike failed", t);
        }
    }

    private void carveRunicRaidhoLike(boolean[][] pixels,
                                      int cxRune,
                                      int cyRune,
                                      int cx,
                                      int cy,
                                      double radiusSq,
                                      double baseAngle,
                                      double scale) {
        try {
            int len = (int) Math.round(scale * 1.5);
            int x0 = cxRune;
            int y0 = cyRune - len;
            int x1 = cxRune;
            int y1 = cyRune + len;
            carveLine(pixels, x0, y0, x1, y1, 1, cx, cy, radiusSq);

            int r = (int) Math.round(scale);
            int arcSamples = 6;
            int[] xs = new int[arcSamples];
            int[] ys = new int[arcSamples];

            for (int i = 0; i < arcSamples; i++) {
                double t = i / (double) (arcSamples - 1);
                double theta = Math.toRadians(20 + 140 * t);
                int px = cxRune + (int) Math.round(Math.cos(theta) * r);
                int py = cyRune + (int) Math.round(Math.sin(theta) * r * 0.7);
                xs[i] = px;
                ys[i] = py;
            }

            carvePolygon(pixels, xs, ys, arcSamples, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveRunicRaidhoLike failed", t);
        }
    }

    private void carveRunicDagazLike(boolean[][] pixels,
                                     int cxRune,
                                     int cyRune,
                                     int cx,
                                     int cy,
                                     double radiusSq,
                                     double baseAngle,
                                     double scale) {
        try {
            int w = (int) Math.round(scale * 1.4);
            int h = (int) Math.round(scale * 1.0);

            int leftX  = cxRune - w / 2;
            int rightX = cxRune + w / 2;
            int topY   = cyRune - h / 2;
            int botY   = cyRune + h / 2;

            carveLine(pixels, leftX, topY, rightX, botY, 1, cx, cy, radiusSq);
            carveLine(pixels, leftX, botY, rightX, topY, 1, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveRunicDagazLike failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // High-level motif: Braided knot rail
    // -------------------------------------------------------------------------

    private void carveBraidedKnotRail(boolean[][] pixels,
                                      int cx,
                                      int cy,
                                      int radius,
                                      double radiusSq,
                                      double sliceStart,
                                      double sliceEnd,
                                      @NotNull RandomSource rng,
                                      int passIndex) {
        try {
            double innerR = radius * (BAND_MID_FRACTION * 0.86);
            double outerR = innerR + radius * 0.06;

            int knotSegments = 2 + rng.nextInt(2); // 2–3 loops
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < knotSegments; i++) {
                double segStart = sliceStart + (span * i) / knotSegments;
                double segEnd   = sliceStart + (span * (i + 1)) / knotSegments;

                carveBraidedSegment(pixels, cx, cy, radius, radiusSq, innerR, outerR, segStart, segEnd, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveBraidedKnotRail failed", t);
        }
    }

    private void carveBraidedSegment(boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     double innerR,
                                     double outerR,
                                     double angleStart,
                                     double angleEnd,
                                     @NotNull RandomSource rng) {
        try {
            int samples = 8;
            int[] xsA = new int[samples];
            int[] ysA = new int[samples];
            int[] xsB = new int[samples];
            int[] ysB = new int[samples];

            for (int i = 0; i < samples; i++) {
                double t = i / (double) (samples - 1);
                double angle = angleStart + (angleEnd - angleStart) * t;

                double phase = t * Math.PI;
                double rA = (innerR + outerR) * 0.5 + Math.sin(phase) * (outerR - innerR) * 0.5;
                double rB = (innerR + outerR) * 0.5 - Math.sin(phase) * (outerR - innerR) * 0.5;

                xsA[i] = cx + (int) Math.round(Math.cos(angle) * rA);
                ysA[i] = cy + (int) Math.round(Math.sin(angle) * rA);
                xsB[i] = cx + (int) Math.round(Math.cos(angle) * rB);
                ysB[i] = cy + (int) Math.round(Math.sin(angle) * rB);
            }

            for (int i = 0; i < samples - 1; i++) {
                carveLine(pixels, xsA[i], ysA[i], xsA[i + 1], ysA[i + 1], 1, cx, cy, radiusSq);
                carveLine(pixels, xsB[i], ysB[i], xsB[i + 1], ysB[i + 1], 1, cx, cy, radiusSq);
            }

            if (rng.nextFloat() < 0.5f) {
                for (int i = 0; i < samples - 1; i++) {
                    if ((i % 2) == 0) {
                        carveLine(pixels, xsA[i], ysA[i], xsB[i + 1], ysB[i + 1], 1, cx, cy, radiusSq);
                    } else {
                        carveLine(pixels, xsB[i], ysB[i], xsA[i + 1], ysA[i + 1], 1, cx, cy, radiusSq);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveBraidedSegment failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // High-level motif: Iron spikes & chains (kept but lower density)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    private void carveIronSpikesAndChains(boolean[][] pixels,
                                          int cx,
                                          int cy,
                                          int radius,
                                          double radiusSq,
                                          double sliceStart,
                                          double sliceEnd,
                                          @NotNull RandomSource rng,
                                          int passIndex) {
        try {
            double baseR = radius * (BAND_OUTER_FRACTION * 0.94);
            int spikeCount = 1 + rng.nextInt(2); // 1–2 spikes
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < spikeCount; i++) {
                double t = (i + 0.5) / spikeCount;
                double angle = sliceStart + span * t;
                carveSpike(pixels, cx, cy, radius, radiusSq, baseR, angle, rng);
            }

            double chainR = radius * (BAND_MID_FRACTION * 1.03);
            carveChainArc(pixels, cx, cy, radius, radiusSq, chainR, sliceStart, sliceEnd, rng);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveIronSpikesAndChains failed", t);
        }
    }

    private void carveSpike(boolean[][] pixels,
                            int cx,
                            int cy,
                            int radius,
                            double radiusSq,
                            double baseR,
                            double angle,
                            @NotNull RandomSource rng) {
        try {
            double length = radius * (0.11 + rng.nextDouble() * 0.04);
            double baseWidth = radius * 0.035;

            int baseX = cx + (int) Math.round(Math.cos(angle) * baseR);
            int baseY = cy + (int) Math.round(Math.sin(angle) * baseR);

            int tipX = baseX + (int) Math.round(Math.cos(angle) * length);
            int tipY = baseY + (int) Math.round(Math.sin(angle) * length);

            int baseLeftX = baseX + (int) Math.round(-Math.sin(angle) * baseWidth);
            int baseLeftY = baseY + (int) Math.round(Math.cos(angle) * baseWidth);
            int baseRightX = baseX - (baseLeftX - baseX);
            int baseRightY = baseY - (baseLeftY - baseY);

            carvePolygon(pixels,
                    new int[]{baseLeftX, baseRightX, tipX},
                    new int[]{baseLeftY, baseRightY, tipY},
                    3,
                    cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveSpike failed", t);
        }
    }

    private void carveChainArc(boolean[][] pixels,
                               int cx,
                               int cy,
                               int radius,
                               double radiusSq,
                               double chainR,
                               double sliceStart,
                               double sliceEnd,
                               @NotNull RandomSource rng) {
        try {
            int linkCount = 3 + rng.nextInt(3); // 3–5 links
            double span = sliceEnd - sliceStart;
            double linkSpan = span / linkCount;

            double linkRadius = radius * 0.02;

            for (int i = 0; i < linkCount; i++) {
                double angle = sliceStart + linkSpan * (i + 0.5);
                int linkX = cx + (int) Math.round(Math.cos(angle) * chainR);
                int linkY = cy + (int) Math.round(Math.sin(angle) * chainR);

                carveRing(pixels,
                        linkX, linkY,
                        (int) Math.max(linkRadius * 0.5, MIN_DETAIL_RADIUS),
                        (int) Math.round(linkRadius),
                        cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveChainArc failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Final detail motif: rivets & battle nicks (reduced)
    // -------------------------------------------------------------------------

    private void carveRivetsAndBattleNicks(boolean[][] pixels,
                                           int cx,
                                           int cy,
                                           int radius,
                                           double radiusSq,
                                           double sliceStart,
                                           double sliceEnd,
                                           @NotNull RandomSource rng) {
        try {
            int rivetCount = 3 + rng.nextInt(4); // 3–6
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < rivetCount; i++) {
                double angle = sliceStart + span * rng.nextDouble();
                double rNorm = BAND_MID_FRACTION * (0.8 + rng.nextDouble() * 0.4);
                double r = radius * rNorm;

                int x = cx + (int) Math.round(Math.cos(angle) * r);
                int y = cy + (int) Math.round(Math.sin(angle) * r);

                if (!insideCircle(x, y, cx, cy, radiusSq)) continue;

                carveDisc(pixels, x, y, MIN_DETAIL_RADIUS, cx, cy, radiusSq);
            }

            if (rng.nextFloat() < 0.6f) {
                int nickCount = 1 + rng.nextInt(2); // 1–2
                for (int i = 0; i < nickCount; i++) {
                    double angle = sliceStart + span * rng.nextDouble();
                    double r = radius * (BAND_OUTER_FRACTION * (0.9 + rng.nextDouble() * 0.08));

                    int x = cx + (int) Math.round(Math.cos(angle) * r);
                    int y = cy + (int) Math.round(Math.sin(angle) * r);

                    carveShieldNick(pixels, x, y, cx, cy, radius, radiusSq, angle + Math.PI / 2.0, rng);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveRivetsAndBattleNicks failed", t);
        }
    }

    private void carveShieldNick(boolean[][] pixels,
                                 int edgeX,
                                 int edgeY,
                                 int cx,
                                 int cy,
                                 int radius,
                                 double radiusSq,
                                 double tangentAngle,
                                 @NotNull RandomSource rng) {
        try {
            double nickDepth = radius * 0.035;
            double nickWidth = radius * 0.03;

            int innerX = edgeX - (int) Math.round(Math.cos(tangentAngle) * nickWidth * 0.5);
            int innerY = edgeY - (int) Math.round(Math.sin(tangentAngle) * nickWidth * 0.5);

            int outerX = edgeX + (int) Math.round(Math.cos(tangentAngle) * nickWidth * 0.5);
            int outerY = edgeY + (int) Math.round(Math.sin(tangentAngle) * nickWidth * 0.5);

            int notchX = edgeX - (int) Math.round(Math.cos(tangentAngle + Math.PI / 2.0) * nickDepth);
            int notchY = edgeY - (int) Math.round(Math.sin(tangentAngle + Math.PI / 2.0) * nickDepth);

            carvePolygon(pixels,
                    new int[]{innerX, outerX, notchX},
                    new int[]{innerY, outerY, notchY},
                    3,
                    cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveShieldNick failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Shared geometry helpers — all MARK SHAPES (set pixels to true)
    // -------------------------------------------------------------------------

    private boolean insideCircle(int x, int y, int cx, int cy, double radiusSq) {
        int dx = x - cx;
        int dy = y - cy;
        return dx * dx + dy * dy <= radiusSq;
    }

    /**
     * Mark a filled disc as part of the shape mask.
     */
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
            LOG.error("[SealSigilShapeSetMedieval0] carveDisc failed", t);
        }
    }

    /**
     * Mark a ring between innerRadius and outerRadius as part of the shape mask.
     */
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
            LOG.error("[SealSigilShapeSetMedieval0] carveRing failed", t);
        }
    }

    /**
     * Mark a line segment between (x0, y0) and (x1, y1) with a given thickness.
     */
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
            LOG.error("[SealSigilShapeSetMedieval0] carveLine failed", t);
        }
    }

    /**
     * Mark a polygon defined by vertices (xs[i], ys[i]) for i in [0, count).
     * Fills the polygon using a simple scanline algorithm.
     */
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
            LOG.error("[SealSigilShapeSetMedieval0] carvePolygon failed", t);
        }
    }

    /**
     * Mark an arm with rounded tip for cross pattée-like forms.
     */
    private void carveRoundedArm(boolean[][] pixels,
                                 int centerX,
                                 int centerY,
                                 double angle,
                                 int length,
                                 int width,
                                 int cx,
                                 int cy,
                                 double radiusSq) {
        try {
            int halfW = Math.max(1, width / 2);

            int baseX = centerX;
            int baseY = centerY;
            int tipX = centerX + (int) Math.round(Math.cos(angle) * length);
            int tipY = centerY + (int) Math.round(Math.sin(angle) * length);

            for (int i = -halfW; i <= halfW; i++) {
                double offsetAngle = angle + Math.PI / 2.0;
                int offsetX = (int) Math.round(Math.cos(offsetAngle) * i);
                int offsetY = (int) Math.round(Math.sin(offsetAngle) * i);

                carveLine(pixels,
                        baseX + offsetX,
                        baseY + offsetY,
                        tipX + offsetX,
                        tipY + offsetY,
                        1,
                        cx, cy, radiusSq);
            }

            int tipR = Math.max(MIN_DETAIL_RADIUS + 1, halfW);
            carveDisc(pixels, tipX, tipY, tipR, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveRoundedArm failed", t);
        }
    }

    /**
     * Mark a flared arm with subtle petal-like expansion at the outer end.
     */
    private void carveFlaredArm(boolean[][] pixels,
                                int centerX,
                                int centerY,
                                double angle,
                                int length,
                                int width,
                                int cx,
                                int cy,
                                double radiusSq,
                                @NotNull RandomSource rng) {
        try {
            int innerWidth = Math.max(1, width / 2);
            int outerWidth = Math.max(innerWidth + 1, (int) (innerWidth * 1.8));

            int midSteps = 4;
            for (int step = 0; step <= midSteps; step++) {
                double t = step / (double) midSteps;
                int w = (int) Math.round(innerWidth + (outerWidth - innerWidth) * t);
                int halfW = Math.max(1, w / 2);

                int segmentLen = length / midSteps;
                int baseLen = segmentLen * step;
                int nextLen = baseLen + segmentLen;

                int baseX = centerX + (int) Math.round(Math.cos(angle) * baseLen);
                int baseY = centerY + (int) Math.round(Math.sin(angle) * baseLen);
                int tipX = centerX + (int) Math.round(Math.cos(angle) * nextLen);
                int tipY = centerY + (int) Math.round(Math.sin(angle) * nextLen);

                for (int i = -halfW; i <= halfW; i++) {
                    double offsetAngle = angle + Math.PI / 2.0;
                    int offsetX = (int) Math.round(Math.cos(offsetAngle) * i);
                    int offsetY = (int) Math.round(Math.sin(offsetAngle) * i);

                    carveLine(pixels,
                            baseX + offsetX,
                            baseY + offsetY,
                            tipX + offsetX,
                            tipY + offsetY,
                            1,
                            cx, cy, radiusSq);
                }
            }

            int flareR = Math.max(MIN_DETAIL_RADIUS + 1, outerWidth / 2);
            int tipX = centerX + (int) Math.round(Math.cos(angle) * length);
            int tipY = centerY + (int) Math.round(Math.sin(angle) * length);
            carveDisc(pixels, tipX, tipY, flareR, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval0] carveFlaredArm failed", t);
        }
    }
}
