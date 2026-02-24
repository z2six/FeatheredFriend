package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Geometric:
 * - Large geometric motifs with low symbol count.
 * - Strict anti-overlap rules to keep symbols readable.
 * - Motifs: hex web, triad star, square spiral, orbital lattice.
 */
public final class SealSigilShapeSetGeometric9 implements SealSigilShapeSet {

    private static final Logger LOG = LogUtils.getLogger();

    private static final double SLICE_ANGULAR_MARGIN = Math.toRadians(3.1);
    private static final int DEFAULT_MIN_CANDIDATE_PIXELS = 16;

    private static final double MAJOR_OVERLAP_STRICT = 0.0;
    private static final double MAJOR_OVERLAP_RELAXED = 0.01;
    private static final double SECONDARY_OVERLAP = 0.016;
    private static final double ACCENT_OVERLAP = 0.03;

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
            if (pixels == null || pixels.length == 0 || slices <= 0 || sliceIndex < 0 || sliceIndex >= slices) {
                return;
            }

            double sliceSpan = (Math.PI * 2.0) / slices;
            double sliceStart = sliceIndex * sliceSpan + SLICE_ANGULAR_MARGIN;
            double sliceEnd = (sliceIndex + 1) * sliceSpan - SLICE_ANGULAR_MARGIN;
            if (sliceEnd <= sliceStart) {
                return;
            }

            boolean mirrored = rng.nextBoolean();

            AngleRange laneMain = slotRange(sliceStart, sliceEnd, laneT(0.50, mirrored), 0.44);
            AngleRange laneSideA = slotRange(sliceStart, sliceEnd, laneT(0.20, mirrored), 0.24);
            AngleRange laneSideB = slotRange(sliceStart, sliceEnd, laneT(0.80, mirrored), 0.24);

            int majorA = rng.nextInt(4);
            carveMajorByType(majorA,
                    pixels,
                    cx,
                    cy,
                    radius,
                    radiusSq,
                    laneMain.start,
                    laneMain.end,
                    0.26,
                    0.78,
                    1.28,
                    true,
                    rng);

            if (rng.nextFloat() < 0.30f) {
                int majorB = (majorA + 1 + rng.nextInt(3)) % 4;
                AngleRange lane = rng.nextBoolean() ? laneSideA : laneSideB;
                carveMajorByType(majorB,
                        pixels,
                        cx,
                        cy,
                        radius,
                        radiusSq,
                        lane.start,
                        lane.end,
                        0.14,
                        0.52,
                        1.02,
                        false,
                        rng);
            }

            if (rng.nextFloat() < 0.42f) {
                AngleRange lane = rng.nextBoolean() ? laneSideA : laneSideB;
                carveNodePairAccent(pixels, cx, cy, radius, radiusSq, lane.start, lane.end, rng);
            }
            if (rng.nextFloat() < 0.34f) {
                carveRadialTickAccent(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);
            }
            if (rng.nextFloat() < 0.20f) {
                carveMicroDiamondAccent(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetGeometric9] applyShapesInSlice failed", t);
        }
    }

    private boolean carveMajorByType(int type,
                                     boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     double slotStart,
                                     double slotEnd,
                                     double radialMin,
                                     double radialMax,
                                     double scale,
                                     boolean primary,
                                     @NotNull RandomSource rng) {
        return switch (type) {
            case 0 -> carveHexWebSymbol(pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, primary, rng);
            case 1 -> carveTriadStarSymbol(pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, primary, rng);
            case 2 -> carveSquareSpiralSymbol(pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, primary, rng);
            default -> carveOrbitalLatticeSymbol(pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, primary, rng);
        };
    }

    private boolean carveHexWebSymbol(boolean[][] base,
                                      int cx,
                                      int cy,
                                      int radius,
                                      double radiusSq,
                                      double slotStart,
                                      double slotEnd,
                                      double radialMin,
                                      double radialMax,
                                      double scale,
                                      boolean primary,
                                      @NotNull RandomSource rng) {
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                boolean[][] candidate = newMask(base.length);

                double mid = (slotStart + slotEnd) * 0.5;
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * (primary ? 0.16 : 0.22);
                double centerR = radius * randomBetween(rng, radialMin, radialMax);
                Vec2 c = polar(cx, cy, angle, centerR);

                double outerR = radius * (primary ? 0.25 : 0.21) * scale;
                double innerR = outerR * 0.62;
                int edgeOuter = Math.max(1, (int) Math.round(radius * (primary ? 0.022 : 0.017) * scale));
                int edgeInner = Math.max(1, edgeOuter - 1);
                int nodeR = Math.max(1, (int) Math.round(radius * (primary ? 0.021 : 0.016) * scale));

                double rotOuter = angle + Math.PI / 6.0;
                double rotInner = rotOuter + Math.PI / 6.0;

                Vec2[] outer = regularPolygonPoints(c, 6, rotOuter, outerR);
                Vec2[] inner = regularPolygonPoints(c, 6, rotInner, innerR);

                stampPolygonEdges(candidate, outer, edgeOuter, cx, cy, radiusSq);
                stampPolygonEdges(candidate, inner, edgeInner, cx, cy, radiusSq);

                for (int i = 0; i < 6; i++) {
                    stampLine(candidate,
                            (int) Math.round(outer[i].x),
                            (int) Math.round(outer[i].y),
                            (int) Math.round(inner[i].x),
                            (int) Math.round(inner[i].y),
                            1,
                            cx,
                            cy,
                            radiusSq);
                    if ((i & 1) == 0) {
                        stampDisc(candidate,
                                (int) Math.round(outer[i].x),
                                (int) Math.round(outer[i].y),
                                nodeR,
                                cx,
                                cy,
                                radiusSq);
                    }
                }

                int core = Math.max(2, (int) Math.round(innerR * 0.28));
                stampRing(candidate, (int) Math.round(c.x), (int) Math.round(c.y), Math.max(0, core - 2), core, cx, cy, radiusSq);

                int minPixels = Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + (primary ? 18 : 8), radius / 3);
                if (primary) {
                    if (commitMandatory(base, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, minPixels)) {
                        return true;
                    }
                } else if (commitCandidate(base, candidate, SECONDARY_OVERLAP, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetGeometric9] carveHexWebSymbol failed", t);
        }
        return false;
    }

    private boolean carveTriadStarSymbol(boolean[][] base,
                                         int cx,
                                         int cy,
                                         int radius,
                                         double radiusSq,
                                         double slotStart,
                                         double slotEnd,
                                         double radialMin,
                                         double radialMax,
                                         double scale,
                                         boolean primary,
                                         @NotNull RandomSource rng) {
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                boolean[][] candidate = newMask(base.length);

                double mid = (slotStart + slotEnd) * 0.5;
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * (primary ? 0.16 : 0.22);
                double centerR = radius * randomBetween(rng, radialMin, radialMax);
                Vec2 c = polar(cx, cy, angle, centerR);

                double triR = radius * (primary ? 0.27 : 0.22) * scale;
                double innerHex = triR * 0.52;
                int edge = Math.max(1, (int) Math.round(radius * (primary ? 0.022 : 0.017) * scale));

                Vec2[] triA = regularPolygonPoints(c, 3, angle - Math.PI * 0.5, triR);
                Vec2[] triB = regularPolygonPoints(c, 3, angle - Math.PI * 0.5 + Math.PI / 3.0, triR * 0.95);

                stampPolygonEdges(candidate, triA, edge, cx, cy, radiusSq);
                stampPolygonEdges(candidate, triB, edge, cx, cy, radiusSq);

                for (int i = 0; i < 3; i++) {
                    stampLine(candidate,
                            (int) Math.round(triA[i].x),
                            (int) Math.round(triA[i].y),
                            (int) Math.round(triB[i].x),
                            (int) Math.round(triB[i].y),
                            1,
                            cx,
                            cy,
                            radiusSq);
                }

                Vec2[] hex = regularPolygonPoints(c, 6, angle + Math.PI / 6.0, innerHex);
                stampPolygonEdges(candidate, hex, 1, cx, cy, radiusSq);

                int core = Math.max(1, (int) Math.round(innerHex * 0.34));
                stampDisc(candidate, (int) Math.round(c.x), (int) Math.round(c.y), core, cx, cy, radiusSq);

                int minPixels = Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + (primary ? 18 : 8), radius / 3);
                if (primary) {
                    if (commitMandatory(base, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, minPixels)) {
                        return true;
                    }
                } else if (commitCandidate(base, candidate, SECONDARY_OVERLAP, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetGeometric9] carveTriadStarSymbol failed", t);
        }
        return false;
    }

    private boolean carveSquareSpiralSymbol(boolean[][] base,
                                            int cx,
                                            int cy,
                                            int radius,
                                            double radiusSq,
                                            double slotStart,
                                            double slotEnd,
                                            double radialMin,
                                            double radialMax,
                                            double scale,
                                            boolean primary,
                                            @NotNull RandomSource rng) {
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                boolean[][] candidate = newMask(base.length);

                double mid = (slotStart + slotEnd) * 0.5;
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * (primary ? 0.16 : 0.22);
                double centerR = radius * randomBetween(rng, radialMin, radialMax);
                Vec2 c = polar(cx, cy, angle, centerR);

                double outerHalf = radius * (primary ? 0.18 : 0.15) * scale;
                double innerHalf = outerHalf * 0.60;
                int edge = Math.max(1, (int) Math.round(radius * (primary ? 0.024 : 0.019) * scale));

                Vec2[] outer = squarePoints(c, angle + Math.PI / 4.0, outerHalf);
                Vec2[] inner = squarePoints(c, angle + Math.PI / 4.0, innerHalf);

                stampPolygonEdges(candidate, outer, edge, cx, cy, radiusSq);
                stampPolygonEdges(candidate, inner, Math.max(1, edge - 1), cx, cy, radiusSq);

                // Spiral-like connectors.
                for (int i = 0; i < 4; i++) {
                    int j = (i + 1) & 3;
                    stampLine(candidate,
                            (int) Math.round(outer[i].x),
                            (int) Math.round(outer[i].y),
                            (int) Math.round(inner[j].x),
                            (int) Math.round(inner[j].y),
                            1,
                            cx,
                            cy,
                            radiusSq);
                }

                int nodeR = Math.max(1, (int) Math.round(radius * 0.018 * scale));
                for (int i = 0; i < 4; i++) {
                    stampDisc(candidate, (int) Math.round(outer[i].x), (int) Math.round(outer[i].y), nodeR, cx, cy, radiusSq);
                }

                int core = Math.max(2, (int) Math.round(innerHalf * 0.50));
                stampRing(candidate, (int) Math.round(c.x), (int) Math.round(c.y), Math.max(0, core - 2), core, cx, cy, radiusSq);

                int minPixels = Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + (primary ? 18 : 8), radius / 3);
                if (primary) {
                    if (commitMandatory(base, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, minPixels)) {
                        return true;
                    }
                } else if (commitCandidate(base, candidate, SECONDARY_OVERLAP, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetGeometric9] carveSquareSpiralSymbol failed", t);
        }
        return false;
    }

    private boolean carveOrbitalLatticeSymbol(boolean[][] base,
                                              int cx,
                                              int cy,
                                              int radius,
                                              double radiusSq,
                                              double slotStart,
                                              double slotEnd,
                                              double radialMin,
                                              double radialMax,
                                              double scale,
                                              boolean primary,
                                              @NotNull RandomSource rng) {
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                boolean[][] candidate = newMask(base.length);

                double mid = (slotStart + slotEnd) * 0.5;
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * (primary ? 0.14 : 0.20);
                double centerR = radius * randomBetween(rng, radialMin, radialMax);
                Vec2 c = polar(cx, cy, angle, centerR);

                double orbitR = radius * (primary ? 0.22 : 0.18) * scale;
                int ringOuter = Math.max(2, (int) Math.round(orbitR * 0.40));
                int nodeR = Math.max(1, (int) Math.round(radius * (primary ? 0.020 : 0.016) * scale));
                int nodeCount = primary ? 4 : 3;

                stampRing(candidate,
                        (int) Math.round(c.x),
                        (int) Math.round(c.y),
                        Math.max(0, ringOuter - 2),
                        ringOuter,
                        cx,
                        cy,
                        radiusSq);

                Vec2[] nodes = new Vec2[nodeCount];
                for (int i = 0; i < nodeCount; i++) {
                    double a = angle + i * (Math.PI * 2.0 / nodeCount);
                    nodes[i] = polar((int) Math.round(c.x), (int) Math.round(c.y), a, orbitR);
                    stampDisc(candidate,
                            (int) Math.round(nodes[i].x),
                            (int) Math.round(nodes[i].y),
                            nodeR,
                            cx,
                            cy,
                            radiusSq);
                }

                for (int i = 0; i < nodeCount; i++) {
                    int j = (i + 1) % nodeCount;
                    stampLine(candidate,
                            (int) Math.round(nodes[i].x),
                            (int) Math.round(nodes[i].y),
                            (int) Math.round(nodes[j].x),
                            (int) Math.round(nodes[j].y),
                            1,
                            cx,
                            cy,
                            radiusSq);
                    if (primary || (i & 1) == 0) {
                        stampLine(candidate,
                                (int) Math.round(c.x),
                                (int) Math.round(c.y),
                                (int) Math.round(nodes[i].x),
                                (int) Math.round(nodes[i].y),
                                1,
                                cx,
                                cy,
                                radiusSq);
                    }
                }

                int minPixels = Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + (primary ? 16 : 7), radius / 3);
                if (primary) {
                    if (commitMandatory(base, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, minPixels)) {
                        return true;
                    }
                } else if (commitCandidate(base, candidate, SECONDARY_OVERLAP, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetGeometric9] carveOrbitalLatticeSymbol failed", t);
        }
        return false;
    }

    private void carveNodePairAccent(boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     double slotStart,
                                     double slotEnd,
                                     @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            double mid = (slotStart + slotEnd) * 0.5;
            double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * 0.30;
            double rr = radius * (0.62 + rng.nextDouble() * 0.20);
            Vec2 center = polar(cx, cy, angle, rr);

            double tx = -Math.sin(angle);
            double ty = Math.cos(angle);
            double sep = radius * (0.045 + rng.nextDouble() * 0.020);
            int nodeR = Math.max(1, radius / 34);

            Vec2 a = new Vec2(center.x - tx * sep, center.y - ty * sep);
            Vec2 b = new Vec2(center.x + tx * sep, center.y + ty * sep);

            stampDisc(candidate, (int) Math.round(a.x), (int) Math.round(a.y), nodeR, cx, cy, radiusSq);
            stampDisc(candidate, (int) Math.round(b.x), (int) Math.round(b.y), nodeR, cx, cy, radiusSq);
            stampLine(candidate,
                    (int) Math.round(a.x),
                    (int) Math.round(a.y),
                    (int) Math.round(b.x),
                    (int) Math.round(b.y),
                    1,
                    cx,
                    cy,
                    radiusSq);

            commitCandidate(pixels, candidate, ACCENT_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 8, 8));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetGeometric9] carveNodePairAccent failed", t);
        }
    }

    private void carveRadialTickAccent(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       double sliceStart,
                                       double sliceEnd,
                                       @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            int ticks = 3 + rng.nextInt(3);
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < ticks; i++) {
                double t = (i + 0.5) / ticks;
                double a = sliceStart + span * t + (rng.nextDouble() - 0.5) * span * 0.08;
                double r1 = radius * (0.72 + rng.nextDouble() * 0.15);
                double r0 = r1 - radius * (0.038 + rng.nextDouble() * 0.020);

                Vec2 p0 = polar(cx, cy, a, r0);
                Vec2 p1 = polar(cx, cy, a, r1);
                stampLine(candidate,
                        (int) Math.round(p0.x),
                        (int) Math.round(p0.y),
                        (int) Math.round(p1.x),
                        (int) Math.round(p1.y),
                        1,
                        cx,
                        cy,
                        radiusSq);
            }

            commitCandidate(pixels, candidate, ACCENT_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 8, 8));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetGeometric9] carveRadialTickAccent failed", t);
        }
    }

    private void carveMicroDiamondAccent(boolean[][] pixels,
                                         int cx,
                                         int cy,
                                         int radius,
                                         double radiusSq,
                                         double sliceStart,
                                         double sliceEnd,
                                         @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            int diamonds = 2 + rng.nextInt(2);
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < diamonds; i++) {
                double a = sliceStart + span * (0.20 + 0.60 * rng.nextDouble());
                double r = radius * (0.38 + rng.nextDouble() * 0.42);
                Vec2 c = polar(cx, cy, a, r);

                double ux = Math.cos(a);
                double uy = Math.sin(a);
                double tx = -uy;
                double ty = ux;
                double h = radius * (0.028 + rng.nextDouble() * 0.012);
                double w = h * 0.70;

                Vec2 p0 = new Vec2(c.x + ux * h, c.y + uy * h);
                Vec2 p1 = new Vec2(c.x + tx * w, c.y + ty * w);
                Vec2 p2 = new Vec2(c.x - ux * h, c.y - uy * h);
                Vec2 p3 = new Vec2(c.x - tx * w, c.y - ty * w);

                stampPolygon(candidate, vecXs(p0, p1, p2, p3), vecYs(p0, p1, p2, p3), 4, cx, cy, radiusSq);
            }

            commitCandidate(pixels, candidate, ACCENT_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 8, 8));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetGeometric9] carveMicroDiamondAccent failed", t);
        }
    }

    private AngleRange slotRange(double sliceStart, double sliceEnd, double centerT, double widthT) {
        double span = Math.max(1e-6, sliceEnd - sliceStart);
        double clampedCenter = clamp(centerT, 0.05, 0.95);
        double half = clamp(widthT * 0.5, 0.03, 0.42);

        double startT = clamp(clampedCenter - half, 0.02, 0.98);
        double endT = clamp(clampedCenter + half, 0.02, 0.98);
        if (endT - startT < 0.05) {
            double mid = (startT + endT) * 0.5;
            startT = clamp(mid - 0.025, 0.02, 0.98);
            endT = clamp(mid + 0.025, 0.02, 0.98);
        }
        return new AngleRange(sliceStart + span * startT, sliceStart + span * endT);
    }

    private static double laneT(double t, boolean mirrored) {
        return mirrored ? (1.0 - t) : t;
    }

    private static double randomBetween(@NotNull RandomSource rng, double min, double max) {
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        return lo + rng.nextDouble() * (hi - lo);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean commitMandatory(boolean[][] base,
                                    boolean[][] candidate,
                                    double strictOverlapRatio,
                                    double relaxedOverlapRatio,
                                    int minPixels) {
        if (commitCandidate(base, candidate, strictOverlapRatio, minPixels)) {
            return true;
        }
        return commitCandidate(base, candidate, relaxedOverlapRatio, Math.max(8, minPixels / 2));
    }

    private boolean commitCandidate(boolean[][] base,
                                    boolean[][] candidate,
                                    double maxOverlapRatio,
                                    int minPixels) {
        try {
            int h = Math.min(base.length, candidate.length);
            if (h <= 0) {
                return false;
            }

            int candidatePixels = 0;
            int overlapPixels = 0;
            int addedPixels = 0;

            for (int y = 0; y < h; y++) {
                boolean[] baseRow = base[y];
                boolean[] candRow = candidate[y];
                int w = Math.min(baseRow.length, candRow.length);
                for (int x = 0; x < w; x++) {
                    if (!candRow[x]) {
                        continue;
                    }
                    candidatePixels++;
                    if (baseRow[x]) {
                        overlapPixels++;
                    } else {
                        addedPixels++;
                    }
                }
            }

            if (candidatePixels < minPixels || addedPixels <= 0) {
                return false;
            }

            double overlapRatio = overlapPixels / (double) candidatePixels;
            if (overlapRatio > maxOverlapRatio) {
                return false;
            }

            for (int y = 0; y < h; y++) {
                boolean[] baseRow = base[y];
                boolean[] candRow = candidate[y];
                int w = Math.min(baseRow.length, candRow.length);
                for (int x = 0; x < w; x++) {
                    if (candRow[x]) {
                        baseRow[x] = true;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetGeometric9] commitCandidate failed", t);
            return false;
        }
    }

    private static boolean[][] newMask(int size) {
        int safe = Math.max(0, size);
        return new boolean[safe][safe];
    }

    private boolean insideCircle(int x, int y, int cx, int cy, double radiusSq) {
        int dx = x - cx;
        int dy = y - cy;
        return dx * dx + dy * dy <= radiusSq;
    }

    private void stampDisc(boolean[][] pixels,
                           int centerX,
                           int centerY,
                           int r,
                           int cx,
                           int cy,
                           double radiusSq) {
        if (r <= 0 || pixels.length == 0) {
            return;
        }

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
                if (dx * dx + dySq <= rSq && insideCircle(x, y, cx, cy, radiusSq)) {
                    pixels[y][x] = true;
                }
            }
        }
    }

    private void stampRing(boolean[][] pixels,
                           int centerX,
                           int centerY,
                           int innerRadius,
                           int outerRadius,
                           int cx,
                           int cy,
                           double radiusSq) {
        if (outerRadius <= innerRadius || outerRadius <= 0 || pixels.length == 0) {
            return;
        }

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
    }

    private void stampLine(boolean[][] pixels,
                           int x0,
                           int y0,
                           int x1,
                           int y1,
                           int thickness,
                           int cx,
                           int cy,
                           double radiusSq) {
        int size = pixels.length;
        if (size == 0) {
            return;
        }

        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int r = Math.max(0, thickness - 1);
        while (true) {
            if (x0 >= 0 && y0 >= 0 && x0 < size && y0 < size && insideCircle(x0, y0, cx, cy, radiusSq)) {
                if (r == 0) {
                    pixels[y0][x0] = true;
                } else {
                    for (int oy = -r; oy <= r; oy++) {
                        int ny = y0 + oy;
                        if (ny < 0 || ny >= size) {
                            continue;
                        }
                        for (int ox = -r; ox <= r; ox++) {
                            int nx = x0 + ox;
                            if (nx < 0 || nx >= size) {
                                continue;
                            }
                            if (insideCircle(nx, ny, cx, cy, radiusSq)) {
                                pixels[ny][nx] = true;
                            }
                        }
                    }
                }
            }

            if (x0 == x1 && y0 == y1) {
                break;
            }
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
    }

    private void stampPolygon(boolean[][] pixels,
                              int[] xs,
                              int[] ys,
                              int count,
                              int cx,
                              int cy,
                              double radiusSq) {
        if (pixels.length == 0 || xs == null || ys == null || count <= 1) {
            return;
        }

        int size = pixels.length;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }

        minY = Math.max(0, Math.min(minY, size - 1));
        maxY = Math.max(0, Math.min(maxY, size - 1));
        if (minY > maxY) {
            return;
        }

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

            if (interCount <= 0) {
                continue;
            }
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
    }

    private void stampPolygonEdges(boolean[][] pixels,
                                   Vec2[] points,
                                   int thickness,
                                   int cx,
                                   int cy,
                                   double radiusSq) {
        if (points == null || points.length < 2) {
            return;
        }
        int count = points.length;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            stampLine(pixels,
                    (int) Math.round(points[i].x),
                    (int) Math.round(points[i].y),
                    (int) Math.round(points[j].x),
                    (int) Math.round(points[j].y),
                    thickness,
                    cx,
                    cy,
                    radiusSq);
        }
    }

    private static Vec2[] regularPolygonPoints(Vec2 center, int sides, double startAngle, double polygonRadius) {
        int n = Math.max(3, sides);
        Vec2[] points = new Vec2[n];
        for (int i = 0; i < n; i++) {
            double a = startAngle + i * (Math.PI * 2.0 / n);
            points[i] = new Vec2(
                    center.x + Math.cos(a) * polygonRadius,
                    center.y + Math.sin(a) * polygonRadius
            );
        }
        return points;
    }

    private static Vec2[] squarePoints(Vec2 center, double angle, double half) {
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);
        double tx = -uy;
        double ty = ux;
        return new Vec2[]{
                new Vec2(center.x + ux * half + tx * half, center.y + uy * half + ty * half),
                new Vec2(center.x - ux * half + tx * half, center.y - uy * half + ty * half),
                new Vec2(center.x - ux * half - tx * half, center.y - uy * half - ty * half),
                new Vec2(center.x + ux * half - tx * half, center.y + uy * half - ty * half)
        };
    }

    private static int[] vecXs(Vec2... pts) {
        int[] xs = new int[pts.length];
        for (int i = 0; i < pts.length; i++) {
            xs[i] = (int) Math.round(pts[i].x);
        }
        return xs;
    }

    private static int[] vecYs(Vec2... pts) {
        int[] ys = new int[pts.length];
        for (int i = 0; i < pts.length; i++) {
            ys[i] = (int) Math.round(pts[i].y);
        }
        return ys;
    }

    private static Vec2 polar(int cx, int cy, double angle, double radius) {
        return new Vec2(cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius);
    }

    private static final class AngleRange {
        final double start;
        final double end;

        AngleRange(double start, double end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class Vec2 {
        final double x;
        final double y;

        Vec2(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
