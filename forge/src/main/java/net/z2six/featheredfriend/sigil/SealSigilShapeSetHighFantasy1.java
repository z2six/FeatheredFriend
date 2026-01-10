// MainFile: forge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilShapeSetHighFantasy1.java
package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;

import org.slf4j.Logger;

/**
 * forge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilShapeSetHighFantasy1.java
 *
 * Shape set 1 — "High Fantasy"
 *
 * Visual goals:
 *  - Big, readable, magical silhouettes:
 *      * Arcane starbursts and magic circles.
 *      * Dragon-like serpentine arcs (head + spine).
 *      * Flame crowns and tongues of fire.
 *      * Meteor/comet streaks.
 *      * Small sparkle stars as finishing touches.
 *
 * Design rules:
 *  - Quality over quantity:
 *      * A small number of large motifs per slice.
 *      * Strong silhouettes, low micro-noise.
 *  - All geometry is constructed in a single base slice; the generator
 *    then replicates to all slices and cleans up.
 *  - All methods are defensive: bounds checks, try/catch + logging.
 *
 * Mask semantics:
 *  - pixels[y][x] == true => this pixel will be carved out of the wax disc.
 */
public final class SealSigilShapeSetHighFantasy1 implements SealSigilShapeSet {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int MIN_MOTIF_PASSES = 1;
    private static final int MAX_MOTIF_PASSES = 3;

    /**
     * Coarse radial bands as normalized radius fractions.
     */
    private static final double BAND_INNER_FRACTION = 0.28;
    private static final double BAND_MID_FRACTION   = 0.52;
    private static final double BAND_OUTER_FRACTION = 0.80;

    /**
     * Small angular margin so we don't hug slice borders.
     */
    private static final double SLICE_ANGULAR_MARGIN = Math.toRadians(3.0);

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
                LOG.warn("[SealSigilShapeSetHighFantasy1] applyShapesInSlice called with null/empty pixels");
                return;
            }
            if (slices <= 0) {
                LOG.warn("[SealSigilShapeSetHighFantasy1] slices <= 0 ({}), nothing to do", slices);
                return;
            }
            if (sliceIndex < 0 || sliceIndex >= slices) {
                LOG.warn("[SealSigilShapeSetHighFantasy1] sliceIndex {} out of [0, {}), aborting slice carving",
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
                LOG.debug("[SealSigilShapeSetHighFantasy1] Degenerate slice bounds after margin: start={} end={}",
                        sliceStart, sliceEnd);
                return;
            }

            int passes = MIN_MOTIF_PASSES + rng.nextInt(MAX_MOTIF_PASSES - MIN_MOTIF_PASSES + 1);

            LOG.debug("[SealSigilShapeSetHighFantasy1] Carving high-fantasy slice: sliceIndex={} slices={} passes={}",
                    sliceIndex, slices, passes);

            // 1) Foundational motifs: central starburst and magic circle band.
            carveCentralStarAndHalo(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);
            carveOuterMagicCircleBand(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);

            // 2) High-level motif passes (dragons, fire, meteors, orbiting glyphs).
            for (int pass = 0; pass < passes; pass++) {
                try {
                    int recipe = rng.nextInt(4);
                    switch (recipe) {
                        case 0 -> carveDragonArcMotif(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        case 1 -> carveFlameCrown(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        case 2 -> carveMeteorStreaks(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        case 3 -> carveOrbitingGlyphs(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                        default -> carveMeteorStreaks(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, pass);
                    }
                } catch (Throwable t) {
                    LOG.error("[SealSigilShapeSetHighFantasy1] Motif pass {} failed in slice {}", pass, sliceIndex, t);
                }
            }

            // 3) Final sparkle pass.
            carveSparkleField(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);

            // Debug: count marked pixels.
            try {
                int count = 0;
                for (int y = 0; y < size; y++) {
                    boolean[] row = pixels[y];
                    for (int x = 0; x < size; x++) {
                        if (row[x]) count++;
                    }
                }
                LOG.debug("[SealSigilShapeSetHighFantasy1] applyShapesInSlice: sliceIndex={} markedPixels={}",
                        sliceIndex, count);
            } catch (Throwable t) {
                LOG.error("[SealSigilShapeSetHighFantasy1] counting marked pixels failed", t);
            }

        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] applyShapesInSlice failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Foundational: central starburst + halo
    // -------------------------------------------------------------------------

    private void carveCentralStarAndHalo(boolean[][] pixels,
                                         int cx,
                                         int cy,
                                         int radius,
                                         double radiusSq,
                                         double sliceStart,
                                         double sliceEnd,
                                         RandomSource rng) {
        try {
            // Only place one starburst in slice 0, but because we generate only sliceIndex 0
            // in the generator, this naturally becomes a full radial motif.
            // Still: keep this robust for any sliceIndex (we don't rely on it here).
            double starRNorm = BAND_INNER_FRACTION * (0.90 + rng.nextDouble() * 0.15);
            int outerR = Math.max(4, (int) Math.round(radius * starRNorm));
            int innerR = Math.max(2, outerR / 2);

            int points = 5 + rng.nextInt(3); // 5–7 points
            carveStar(pixels, cx, cy, innerR, outerR, points, cx, cy, radiusSq);

            if (rng.nextFloat() < 0.85f) {
                int haloInner = Math.max(outerR + 1, (int) Math.round(outerR * 1.1));
                int haloOuter = haloInner + Math.max(2, radius / 32);
                carveRing(pixels, cx, cy, haloInner, haloOuter, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveCentralStarAndHalo failed", t);
        }
    }

    private void carveStar(boolean[][] pixels,
                           int cxStar,
                           int cyStar,
                           int innerR,
                           int outerR,
                           int points,
                           int cx,
                           int cy,
                           double radiusSq) {
        try {
            if (points < 3) return;
            int vertexCount = points * 2;
            int[] xs = new int[vertexCount];
            int[] ys = new int[vertexCount];

            double twoPi = Math.PI * 2.0;
            double angleStep = twoPi / vertexCount;
            double startAngle = -Math.PI / 2.0; // tip up

            for (int i = 0; i < vertexCount; i++) {
                double angle = startAngle + i * angleStep;
                double r = (i % 2 == 0) ? outerR : innerR;
                xs[i] = cxStar + (int) Math.round(Math.cos(angle) * r);
                ys[i] = cyStar + (int) Math.round(Math.sin(angle) * r);
            }

            carvePolygon(pixels, xs, ys, vertexCount, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveStar failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Foundational: outer magic circle band
    // -------------------------------------------------------------------------

    private void carveOuterMagicCircleBand(boolean[][] pixels,
                                           int cx,
                                           int cy,
                                           int radius,
                                           double radiusSq,
                                           double sliceStart,
                                           double sliceEnd,
                                           RandomSource rng) {
        try {
            double baseR = radius * BAND_MID_FRACTION * (0.95 + rng.nextDouble() * 0.05);
            int innerR = Math.max(4, (int) Math.round(baseR - radius * 0.025));
            int outerR = innerR + Math.max(2, radius / 18);

            carveRing(pixels, cx, cy, innerR, outerR, cx, cy, radiusSq);

            if (rng.nextFloat() < 0.7f) {
                carveArcaneSigilsOnRing(pixels, cx, cy, radius, radiusSq, (innerR + outerR) * 0.5, sliceStart, sliceEnd, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveOuterMagicCircleBand failed", t);
        }
    }

    private void carveArcaneSigilsOnRing(boolean[][] pixels,
                                         int cx,
                                         int cy,
                                         int radius,
                                         double radiusSq,
                                         double ringR,
                                         double sliceStart,
                                         double sliceEnd,
                                         RandomSource rng) {
        try {
            int sigilCount = 2 + rng.nextInt(3); // 2–4 sigils per slice
            double span = sliceEnd - sliceStart;
            double jitterAngleFactor = 0.12;

            for (int i = 0; i < sigilCount; i++) {
                double t = (i + 0.5) / sigilCount;
                double baseAngle = sliceStart + span * t;
                double jitter = (rng.nextDouble() - 0.5) * span * jitterAngleFactor;
                double angle = baseAngle + jitter;

                int sx = cx + (int) Math.round(Math.cos(angle) * ringR);
                int sy = cy + (int) Math.round(Math.sin(angle) * ringR);

                if (!insideCircle(sx, sy, cx, cy, radiusSq)) continue;

                carveTinySigil(pixels, sx, sy, radius, radiusSq, angle, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveArcaneSigilsOnRing failed", t);
        }
    }

    private void carveTinySigil(boolean[][] pixels,
                                int sx,
                                int sy,
                                int radius,
                                double radiusSq,
                                double angle,
                                RandomSource rng) {
        try {
            int style = rng.nextInt(3);
            double scale = radius * 0.04;

            switch (style) {
                case 0 -> carveSparklePlus(pixels, sx, sy, (int) Math.max(1, scale * 0.5), cxFrom(sx, radius), cyFrom(sy, radius), radiusSq);
                case 1 -> carveDisc(pixels, sx, sy, MIN_DETAIL_RADIUS + 1, cxFrom(sx, radius), cyFrom(sy, radius), radiusSq);
                case 2 -> {
                    int len = Math.max(2, (int) Math.round(scale));
                    int ex = sx + (int) Math.round(Math.cos(angle) * len);
                    int ey = sy + (int) Math.round(Math.sin(angle) * len);
                    carveLine(pixels, sx, sy, ex, ey, 1, cxFrom(sx, radius), cyFrom(sy, radius), radiusSq);
                }
                default -> carveDisc(pixels, sx, sy, MIN_DETAIL_RADIUS + 1, cxFrom(sx, radius), cyFrom(sy, radius), radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveTinySigil failed", t);
        }
    }

    // Helpers to keep tinySigil independent from cx, cy weights; they just use provided circle check.
    private int cxFrom(int x, int radius) {
        return radius;
    }

    private int cyFrom(int y, int radius) {
        return radius;
    }

    // -------------------------------------------------------------------------
    // Motif: dragon-like serpent arcs
    // -------------------------------------------------------------------------

    private void carveDragonArcMotif(boolean[][] pixels,
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

            // One dragon arc per slice at most.
            double startAngle = sliceStart + span * (0.15 + rng.nextDouble() * 0.15);
            double endAngle   = sliceEnd   - span * (0.15 + rng.nextDouble() * 0.15);
            if (endAngle <= startAngle) return;

            double baseR = radius * (BAND_MID_FRACTION * (0.95 + rng.nextDouble() * 0.05));
            double thickness = radius * 0.04; // consistent, chunky spine

            // Spine as a thick arc.
            carveThickArc(pixels, cx, cy, radiusSq, baseR, thickness, startAngle, endAngle, 10, cx, cy);

            // Head at one end (choose randomly).
            boolean headAtStart = rng.nextBoolean();
            double headAngle = headAtStart ? startAngle : endAngle;
            double headR = baseR + thickness * 0.25;

            carveDragonHead(pixels, cx, cy, radius, radiusSq, headR, headAngle, rng);

            // Optional small spines along back.
            if (rng.nextFloat() < 0.7f) {
                carveDragonBackSpikes(pixels, cx, cy, radius, radiusSq, baseR, thickness, startAngle, endAngle, rng);
            }

        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveDragonArcMotif failed", t);
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
            int size = pixels.length;
            if (segments < 2) segments = 2;

            double halfT = thickness * 0.5;

            double prevAngle = angleStart;
            for (int i = 1; i <= segments; i++) {
                double t = i / (double) segments;
                double angle = angleStart + (angleEnd - angleStart) * t;

                int sx0 = cx + (int) Math.round(Math.cos(prevAngle) * ringR);
                int sy0 = cy + (int) Math.round(Math.sin(prevAngle) * ringR);
                int sx1 = cx + (int) Math.round(Math.cos(angle) * ringR);
                int sy1 = cy + (int) Math.round(Math.sin(angle) * ringR);

                carveLine(pixels, sx0, sy0, sx1, sy1, Math.max(1, (int) Math.round(halfT)), circleCx, circleCy, radiusSq);
                prevAngle = angle;
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveThickArc failed", t);
        }
    }

    private void carveDragonHead(boolean[][] pixels,
                                 int cx,
                                 int cy,
                                 int radius,
                                 double radiusSq,
                                 double headR,
                                 double headAngle,
                                 RandomSource rng) {
        try {
            int hx = cx + (int) Math.round(Math.cos(headAngle) * headR);
            int hy = cy + (int) Math.round(Math.sin(headAngle) * headR);

            // Basic wedge head.
            double length = radius * 0.08;
            double halfWidth = radius * 0.04;

            int tipX = hx + (int) Math.round(Math.cos(headAngle) * length);
            int tipY = hy + (int) Math.round(Math.sin(headAngle) * length);

            int baseLeftX = hx + (int) Math.round(-Math.sin(headAngle) * halfWidth);
            int baseLeftY = hy + (int) Math.round(Math.cos(headAngle) * halfWidth);
            int baseRightX = hx - (baseLeftX - hx);
            int baseRightY = hy - (baseLeftY - hy);

            carvePolygon(pixels,
                    new int[]{baseLeftX, baseRightX, tipX},
                    new int[]{baseLeftY, baseRightY, tipY},
                    3,
                    cx, cy, radiusSq);

            // Small eye disc.
            if (rng.nextFloat() < 0.8f) {
                double eyeAngle = headAngle + (rng.nextBoolean() ? Math.PI / 2.0 : -Math.PI / 2.0);
                double eyeOffset = length * 0.3;
                int ex = hx + (int) Math.round(Math.cos(eyeAngle) * eyeOffset);
                int ey = hy + (int) Math.round(Math.sin(eyeAngle) * eyeOffset);
                carveDisc(pixels, ex, ey, MIN_DETAIL_RADIUS + 1, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveDragonHead failed", t);
        }
    }

    private void carveDragonBackSpikes(boolean[][] pixels,
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
            int spikeCount = 3 + rng.nextInt(3); // 3–5
            double span = angleEnd - angleStart;
            double offsetR = baseR + thickness * 0.7;
            double spikeLen = radius * 0.05;

            for (int i = 0; i < spikeCount; i++) {
                double t = (i + 0.5) / spikeCount;
                double angle = angleStart + span * t;

                double normalAngle = angle - Math.PI / 2.0;
                int baseX = cx + (int) Math.round(Math.cos(angle) * offsetR);
                int baseY = cy + (int) Math.round(Math.sin(angle) * offsetR);
                int tipX = baseX + (int) Math.round(Math.cos(normalAngle) * spikeLen);
                int tipY = baseY + (int) Math.round(Math.sin(normalAngle) * spikeLen);

                carveLine(pixels, baseX, baseY, tipX, tipY, 1, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveDragonBackSpikes failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Motif: flame crown around the outer band
    // -------------------------------------------------------------------------

    private void carveFlameCrown(boolean[][] pixels,
                                 int cx,
                                 int cy,
                                 int radius,
                                 double radiusSq,
                                 double sliceStart,
                                 double sliceEnd,
                                 RandomSource rng,
                                 int passIndex) {
        try {
            double baseR = radius * BAND_OUTER_FRACTION * (0.92 + rng.nextDouble() * 0.06);
            int flameCount = 2 + rng.nextInt(3); // 2–4 flames
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < flameCount; i++) {
                double t = (i + 0.5) / flameCount;
                double angle = sliceStart + span * t;

                carveFlameTongue(pixels, cx, cy, radius, radiusSq, baseR, angle, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveFlameCrown failed", t);
        }
    }

    private void carveFlameTongue(boolean[][] pixels,
                                  int cx,
                                  int cy,
                                  int radius,
                                  double radiusSq,
                                  double baseR,
                                  double angle,
                                  RandomSource rng) {
        try {
            double length = radius * (0.08 + rng.nextDouble() * 0.06);
            double halfWidth = radius * (0.02 + rng.nextDouble() * 0.01);

            int baseX = cx + (int) Math.round(Math.cos(angle) * baseR);
            int baseY = cy + (int) Math.round(Math.sin(angle) * baseR);

            double tipAngle = angle + (rng.nextDouble() - 0.5) * Math.toRadians(12.0);
            int tipX = baseX + (int) Math.round(Math.cos(tipAngle) * length);
            int tipY = baseY + (int) Math.round(Math.sin(tipAngle) * length);

            double sideAngle = angle + Math.PI / 2.0;
            int leftX = baseX + (int) Math.round(Math.cos(sideAngle) * halfWidth);
            int leftY = baseY + (int) Math.round(Math.sin(sideAngle) * halfWidth);
            int rightX = baseX - (leftX - baseX);
            int rightY = baseY - (leftY - baseY);

            carvePolygon(pixels,
                    new int[]{leftX, rightX, tipX},
                    new int[]{leftY, rightY, tipY},
                    3,
                    cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveFlameTongue failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Motif: meteors / comets
    // -------------------------------------------------------------------------

    private void carveMeteorStreaks(boolean[][] pixels,
                                    int cx,
                                    int cy,
                                    int radius,
                                    double radiusSq,
                                    double sliceStart,
                                    double sliceEnd,
                                    RandomSource rng,
                                    int passIndex) {
        try {
            int meteorCount = 1 + rng.nextInt(2); // 1–2
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < meteorCount; i++) {
                double t = (i + 0.5) / meteorCount;
                double baseAngle = sliceStart + span * t;

                double innerR = radius * BAND_INNER_FRACTION * (0.9 + rng.nextDouble() * 0.2);
                double outerR = radius * BAND_OUTER_FRACTION * (0.9 + rng.nextDouble() * 0.1);

                carveMeteor(pixels, cx, cy, radius, radiusSq, innerR, outerR, baseAngle, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveMeteorStreaks failed", t);
        }
    }

    private void carveMeteor(boolean[][] pixels,
                             int cx,
                             int cy,
                             int radius,
                             double radiusSq,
                             double innerR,
                             double outerR,
                             double baseAngle,
                             RandomSource rng) {
        try {
            double angle = baseAngle + (rng.nextDouble() - 0.5) * Math.toRadians(10.0);

            int tailX = cx + (int) Math.round(Math.cos(angle) * innerR);
            int tailY = cy + (int) Math.round(Math.sin(angle) * innerR);
            int headX = cx + (int) Math.round(Math.cos(angle) * outerR);
            int headY = cy + (int) Math.round(Math.sin(angle) * outerR);

            int thickness = Math.max(1, radius / 32);

            carveLine(pixels, tailX, tailY, headX, headY, thickness, cx, cy, radiusSq);

            int headR = Math.max(2, thickness + 1);
            carveDisc(pixels, headX, headY, headR, cx, cy, radiusSq);

            if (rng.nextFloat() < 0.6f) {
                int midR = (int) Math.round((innerR + outerR) * 0.5);
                int mx = cx + (int) Math.round(Math.cos(angle) * midR);
                int my = cy + (int) Math.round(Math.sin(angle) * midR);
                carveStar(pixels, mx, my,
                        Math.max(1, headR / 2),
                        Math.max(2, headR),
                        4,
                        cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveMeteor failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Motif: orbiting glyphs (small circles crossing the mid-band)
    // -------------------------------------------------------------------------

    private void carveOrbitingGlyphs(boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     double sliceStart,
                                     double sliceEnd,
                                     RandomSource rng,
                                     int passIndex) {
        try {
            int orbitCount = 1 + rng.nextInt(2); // 1–2 orbits
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < orbitCount; i++) {
                double t = (i + 0.5) / orbitCount;
                double midAngle = sliceStart + span * t;

                double inner = radius * BAND_INNER_FRACTION * (1.10 + rng.nextDouble() * 0.15);
                double outer = radius * BAND_MID_FRACTION * (0.90 + rng.nextDouble() * 0.10);

                carveOrbitArc(pixels, cx, cy, radius, radiusSq, inner, outer, midAngle, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveOrbitingGlyphs failed", t);
        }
    }

    private void carveOrbitArc(boolean[][] pixels,
                               int cx,
                               int cy,
                               int radius,
                               double radiusSq,
                               double innerR,
                               double outerR,
                               double midAngle,
                               RandomSource rng) {
        try {
            int samples = 9;
            int[] xs = new int[samples];
            int[] ys = new int[samples];

            double halfSpan = Math.toRadians(15 + rng.nextDouble() * 15);

            for (int i = 0; i < samples; i++) {
                double t = i / (double) (samples - 1);
                double angle = midAngle - halfSpan + (2 * halfSpan) * t;
                double r = innerR + (outerR - innerR) * Math.sin(t * Math.PI);
                xs[i] = cx + (int) Math.round(Math.cos(angle) * r);
                ys[i] = cy + (int) Math.round(Math.sin(angle) * r);
            }

            carvePolygon(pixels, xs, ys, samples, cx, cy, radiusSq);

            if (rng.nextFloat() < 0.8f) {
                int glyphCount = 2 + rng.nextInt(2); // 2–3
                for (int g = 0; g < glyphCount; g++) {
                    int idx = 1 + rng.nextInt(samples - 2);
                    carveSparklePlus(pixels, xs[idx], ys[idx], MIN_DETAIL_RADIUS + 1, cx, cy, radiusSq);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveOrbitArc failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Final sparkle field
    // -------------------------------------------------------------------------

    private void carveSparkleField(boolean[][] pixels,
                                   int cx,
                                   int cy,
                                   int radius,
                                   double radiusSq,
                                   double sliceStart,
                                   double sliceEnd,
                                   RandomSource rng) {
        try {
            int sparkleCount = 3 + rng.nextInt(4); // 3–6
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < sparkleCount; i++) {
                double angle = sliceStart + span * rng.nextDouble();
                double rNorm = BAND_INNER_FRACTION + rng.nextDouble() * (BAND_OUTER_FRACTION - BAND_INNER_FRACTION);
                double r = radius * rNorm;

                int x = cx + (int) Math.round(Math.cos(angle) * r);
                int y = cy + (int) Math.round(Math.sin(angle) * r);

                if (!insideCircle(x, y, cx, cy, radiusSq)) continue;

                carveSparklePlus(pixels, x, y, MIN_DETAIL_RADIUS + 1, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveSparkleField failed", t);
        }
    }

    private void carveSparklePlus(boolean[][] pixels,
                                  int sx,
                                  int sy,
                                  int armLength,
                                  int cx,
                                  int cy,
                                  double radiusSq) {
        try {
            carveLine(pixels, sx - armLength, sy, sx + armLength, sy, 1, cx, cy, radiusSq);
            carveLine(pixels, sx, sy - armLength, sx, sy + armLength, 1, cx, cy, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy1] carveSparklePlus failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Shared geometry helpers (mark shapes as true in mask)
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
            LOG.error("[SealSigilShapeSetHighFantasy1] carveDisc failed", t);
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
            LOG.error("[SealSigilShapeSetHighFantasy1] carveRing failed", t);
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
            LOG.error("[SealSigilShapeSetHighFantasy1] carveLine failed", t);
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
            LOG.error("[SealSigilShapeSetHighFantasy1] carvePolygon failed", t);
        }
    }
}
