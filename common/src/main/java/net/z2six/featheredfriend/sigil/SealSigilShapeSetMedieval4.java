package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * "Medieval 3D" set:
 * - 1 or 2 dominant motifs per slice.
 * - Very large silhouettes with layered inset details for a pseudo-3D relief look.
 * - Hard no-overlap commits between motifs.
 */
public final class SealSigilShapeSetMedieval4 implements SealSigilShapeSet {

    private static final Logger LOG = LogUtils.getLogger();

    private static final double SLICE_ANGULAR_MARGIN = Math.toRadians(4.2);
    private static final int MAX_ATTEMPTS_PER_MOTIF = 12;
    private static final double STRICT_OVERLAP_RATIO = 0.0;
    private static final int BASE_MIN_PIXELS = 70;

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
            int motifCount = 1 + (rng.nextFloat() < 0.42f ? 1 : 0);

            if (motifCount == 1) {
                AngleRange lane = slotRange(sliceStart, sliceEnd, laneT(0.50, mirrored), 0.74);
                carveAnyLargeMotif(
                        pixels,
                        cx,
                        cy,
                        radius,
                        radiusSq,
                        lane.start,
                        lane.end,
                        0.34,
                        0.84,
                        1.10,
                        rng
                );
            } else {
                AngleRange laneA = slotRange(sliceStart, sliceEnd, laneT(0.30, mirrored), 0.34);
                AngleRange laneB = slotRange(sliceStart, sliceEnd, laneT(0.74, mirrored), 0.32);

                int firstType = rng.nextInt(3);
                int secondType = (firstType + 1 + rng.nextInt(2)) % 3;

                boolean firstOk = carveMotifByType(
                        firstType,
                        pixels,
                        cx,
                        cy,
                        radius,
                        radiusSq,
                        laneA.start,
                        laneA.end,
                        0.30,
                        0.62,
                        1.00,
                        rng
                );
                if (!firstOk) {
                    carveAnyLargeMotif(
                            pixels,
                            cx,
                            cy,
                            radius,
                            radiusSq,
                            laneA.start,
                            laneA.end,
                            0.30,
                            0.62,
                            1.00,
                            rng
                    );
                }

                boolean secondOk = carveMotifByType(
                        secondType,
                        pixels,
                        cx,
                        cy,
                        radius,
                        radiusSq,
                        laneB.start,
                        laneB.end,
                        0.60,
                        0.90,
                        0.98,
                        rng
                );
                if (!secondOk) {
                    carveAnyLargeMotif(
                            pixels,
                            cx,
                            cy,
                            radius,
                            radiusSq,
                            laneB.start,
                            laneB.end,
                            0.60,
                            0.90,
                            0.98,
                            rng
                    );
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval4] applyShapesInSlice failed", t);
        }
    }

    private boolean carveAnyLargeMotif(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       double slotStart,
                                       double slotEnd,
                                       double radialMin,
                                       double radialMax,
                                       double scale,
                                       @NotNull RandomSource rng) {
        int start = rng.nextInt(3);
        for (int i = 0; i < 3; i++) {
            int type = (start + i) % 3;
            if (carveMotifByType(type, pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, rng)) {
                return true;
            }
        }
        return false;
    }

    private boolean carveMotifByType(int type,
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
                                     @NotNull RandomSource rng) {
        return switch (type) {
            case 0 -> carveGreatShieldRelief(
                    pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, rng
            );
            case 1 -> carveGreatswordRelief(
                    pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, rng
            );
            default -> carveWarhammerRelief(
                    pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, rng
            );
        };
    }

    private boolean carveGreatShieldRelief(boolean[][] base,
                                           int cx,
                                           int cy,
                                           int radius,
                                           double radiusSq,
                                           double slotStart,
                                           double slotEnd,
                                           double radialMin,
                                           double radialMax,
                                           double scale,
                                           @NotNull RandomSource rng) {
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_MOTIF; attempt++) {
                boolean[][] candidate = newMask(base.length);

                double mid = (slotStart + slotEnd) * 0.5;
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * 0.22;
                double centerNorm = randomBetween(
                        rng,
                        clamp(radialMin + 0.05, 0.16, 0.90),
                        clamp(radialMax - 0.06, 0.20, 0.93)
                );
                double centerR = radius * centerNorm;

                Vec2 c = polar(cx, cy, angle, centerR);
                double ux = Math.cos(angle);
                double uy = Math.sin(angle);
                double tx = -uy;
                double ty = ux;

                double halfW = radius * (0.16 * scale + rng.nextDouble() * 0.04 * scale);
                double topLift = radius * (0.16 * scale + rng.nextDouble() * 0.03 * scale);
                double lower = radius * (0.30 * scale + rng.nextDouble() * 0.06 * scale);

                Vec2 p0 = new Vec2(c.x - ux * topLift - tx * halfW, c.y - uy * topLift - ty * halfW);
                Vec2 p1 = new Vec2(c.x - ux * topLift + tx * halfW, c.y - uy * topLift + ty * halfW);
                Vec2 p2 = new Vec2(c.x + ux * (lower * 0.30) + tx * (halfW * 0.94), c.y + uy * (lower * 0.30) + ty * (halfW * 0.94));
                Vec2 p3 = new Vec2(c.x + ux * lower, c.y + uy * lower);
                Vec2 p4 = new Vec2(c.x + ux * (lower * 0.30) - tx * (halfW * 0.94), c.y + uy * (lower * 0.30) - ty * (halfW * 0.94));

                stampPolygon(candidate, vecXs(p0, p1, p2, p3, p4), vecYs(p0, p1, p2, p3, p4), 5, cx, cy, radiusSq);

                // Inner inset for "relief depth".
                Vec2 i0 = toward(p0, c, 0.72);
                Vec2 i1 = toward(p1, c, 0.72);
                Vec2 i2 = toward(p2, c, 0.72);
                Vec2 i3 = toward(p3, c, 0.72);
                Vec2 i4 = toward(p4, c, 0.72);
                stampPolygon(candidate, vecXs(i0, i1, i2, i3, i4), vecYs(i0, i1, i2, i3, i4), 5, cx, cy, radiusSq);

                int ridgeThickness = Math.max(1, (int) Math.round(radius * 0.014 * scale));
                double ridgeOffset = halfW * 0.22;
                Vec2 topMid = midpoint(p0, p1);
                stampLine(
                        candidate,
                        (int) Math.round(topMid.x + tx * ridgeOffset),
                        (int) Math.round(topMid.y + ty * ridgeOffset),
                        (int) Math.round(p3.x + tx * (ridgeOffset * 0.60)),
                        (int) Math.round(p3.y + ty * (ridgeOffset * 0.60)),
                        ridgeThickness,
                        cx,
                        cy,
                        radiusSq
                );
                stampLine(
                        candidate,
                        (int) Math.round(topMid.x - tx * ridgeOffset),
                        (int) Math.round(topMid.y - ty * ridgeOffset),
                        (int) Math.round(p3.x - tx * (ridgeOffset * 0.60)),
                        (int) Math.round(p3.y - ty * (ridgeOffset * 0.60)),
                        ridgeThickness,
                        cx,
                        cy,
                        radiusSq
                );

                int emblem = rng.nextInt(3);
                if (emblem == 0) {
                    int arm = Math.max(2, (int) Math.round(radius * 0.070 * scale));
                    int thick = Math.max(1, (int) Math.round(radius * 0.020 * scale));
                    stampLine(candidate, (int) Math.round(c.x), (int) Math.round(c.y - arm), (int) Math.round(c.x), (int) Math.round(c.y + arm), thick, cx, cy, radiusSq);
                    stampLine(candidate, (int) Math.round(c.x - arm), (int) Math.round(c.y), (int) Math.round(c.x + arm), (int) Math.round(c.y), thick, cx, cy, radiusSq);
                } else if (emblem == 1) {
                    Vec2 inner = toward(c, p0, 0.28);
                    Vec2 outer = toward(c, p3, 0.78);
                    int thick = Math.max(1, (int) Math.round(radius * 0.018 * scale));
                    stampLine(candidate, (int) Math.round(inner.x), (int) Math.round(inner.y), (int) Math.round(outer.x), (int) Math.round(outer.y), thick, cx, cy, radiusSq);
                    int guardHalf = Math.max(2, (int) Math.round(radius * 0.050 * scale));
                    stampLine(candidate,
                            (int) Math.round(inner.x - tx * guardHalf),
                            (int) Math.round(inner.y - ty * guardHalf),
                            (int) Math.round(inner.x + tx * guardHalf),
                            (int) Math.round(inner.y + ty * guardHalf),
                            thick,
                            cx,
                            cy,
                            radiusSq);
                } else {
                    int crownW = Math.max(5, (int) Math.round(radius * 0.13 * scale));
                    int crownH = Math.max(4, (int) Math.round(radius * 0.07 * scale));
                    int baseY = (int) Math.round(c.y + uy * (radius * 0.02));
                    int leftX = (int) Math.round(c.x - tx * crownW * 0.5);
                    int leftY = (int) Math.round(baseY - ty * crownW * 0.5);
                    int rightX = (int) Math.round(c.x + tx * crownW * 0.5);
                    int rightY = (int) Math.round(baseY + ty * crownW * 0.5);
                    stampLine(candidate, leftX, leftY, rightX, rightY, 1, cx, cy, radiusSq);
                    for (int i = -1; i <= 1; i++) {
                        double o = i * 0.33;
                        int bx = (int) Math.round(c.x + tx * crownW * o);
                        int by = (int) Math.round(baseY + ty * crownW * o);
                        int txp = (int) Math.round(bx - ux * crownH);
                        int typ = (int) Math.round(by - uy * crownH);
                        stampLine(candidate, bx, by, txp, typ, 1, cx, cy, radiusSq);
                        stampDisc(candidate, txp, typ, Math.max(1, radius / 52), cx, cy, radiusSq);
                    }
                }

                int studR = Math.max(1, radius / 48);
                stampDisc(candidate, (int) Math.round(p0.x), (int) Math.round(p0.y), studR, cx, cy, radiusSq);
                stampDisc(candidate, (int) Math.round(p1.x), (int) Math.round(p1.y), studR, cx, cy, radiusSq);
                stampDisc(candidate, (int) Math.round(p3.x), (int) Math.round(p3.y), studR, cx, cy, radiusSq);

                int minPixels = Math.max(BASE_MIN_PIXELS, (int) Math.round(radius * radius * 0.018 * scale));
                if (commitCandidate(base, candidate, STRICT_OVERLAP_RATIO, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval4] carveGreatShieldRelief failed", t);
        }
        return false;
    }

    private boolean carveGreatswordRelief(boolean[][] base,
                                          int cx,
                                          int cy,
                                          int radius,
                                          double radiusSq,
                                          double slotStart,
                                          double slotEnd,
                                          double radialMin,
                                          double radialMax,
                                          double scale,
                                          @NotNull RandomSource rng) {
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_MOTIF; attempt++) {
                boolean[][] candidate = newMask(base.length);

                double mid = (slotStart + slotEnd) * 0.5;
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * 0.26;

                double guardNormMin = clamp(radialMin + 0.08, 0.18, 0.86);
                double guardNormMax = clamp(radialMax - 0.18, guardNormMin + 0.02, 0.88);
                double guardR = radius * randomBetween(rng, guardNormMin, guardNormMax);
                double tipR = Math.min(radius * 0.95, guardR + radius * (0.34 * scale + rng.nextDouble() * 0.08 * scale));
                double pommelR = Math.max(radius * 0.13, guardR - radius * (0.22 * scale + rng.nextDouble() * 0.06 * scale));

                Vec2 guard = polar(cx, cy, angle, guardR);
                Vec2 tip = polar(cx, cy, angle, tipR);
                Vec2 pommel = polar(cx, cy, angle, pommelR);

                double ux = Math.cos(angle);
                double uy = Math.sin(angle);
                double tx = -uy;
                double ty = ux;

                int bladeThickness = Math.max(2, (int) Math.round(radius * 0.050 * scale));
                stampLine(candidate, (int) Math.round(guard.x), (int) Math.round(guard.y), (int) Math.round(tip.x), (int) Math.round(tip.y), bladeThickness, cx, cy, radiusSq);

                int edgeOffset = Math.max(1, bladeThickness / 2);
                stampLine(candidate,
                        (int) Math.round(guard.x + tx * edgeOffset),
                        (int) Math.round(guard.y + ty * edgeOffset),
                        (int) Math.round(tip.x + tx * edgeOffset),
                        (int) Math.round(tip.y + ty * edgeOffset),
                        1,
                        cx,
                        cy,
                        radiusSq);
                stampLine(candidate,
                        (int) Math.round(guard.x - tx * edgeOffset),
                        (int) Math.round(guard.y - ty * edgeOffset),
                        (int) Math.round(tip.x - tx * edgeOffset),
                        (int) Math.round(tip.y - ty * edgeOffset),
                        1,
                        cx,
                        cy,
                        radiusSq);

                Vec2 fullerStart = toward(guard, tip, 0.14);
                Vec2 fullerEnd = toward(guard, tip, 0.78);
                stampLine(candidate,
                        (int) Math.round(fullerStart.x),
                        (int) Math.round(fullerStart.y),
                        (int) Math.round(fullerEnd.x),
                        (int) Math.round(fullerEnd.y),
                        1,
                        cx,
                        cy,
                        radiusSq);

                Vec2 tipBack = toward(tip, guard, 0.16);
                Vec2 tipLeft = new Vec2(tipBack.x + tx * (radius * 0.036 * scale), tipBack.y + ty * (radius * 0.036 * scale));
                Vec2 tipRight = new Vec2(tipBack.x - tx * (radius * 0.036 * scale), tipBack.y - ty * (radius * 0.036 * scale));
                stampPolygon(candidate, vecXs(tip, tipLeft, tipRight), vecYs(tip, tipLeft, tipRight), 3, cx, cy, radiusSq);

                int guardHalf = Math.max(3, (int) Math.round(radius * (0.17 * scale + rng.nextDouble() * 0.03)));
                int guardThickness = Math.max(1, bladeThickness - 1);
                stampLine(candidate,
                        (int) Math.round(guard.x - tx * guardHalf),
                        (int) Math.round(guard.y - ty * guardHalf),
                        (int) Math.round(guard.x + tx * guardHalf),
                        (int) Math.round(guard.y + ty * guardHalf),
                        guardThickness,
                        cx,
                        cy,
                        radiusSq);

                int quillonLen = Math.max(2, (int) Math.round(radius * 0.060 * scale));
                stampLine(candidate,
                        (int) Math.round(guard.x - tx * guardHalf),
                        (int) Math.round(guard.y - ty * guardHalf),
                        (int) Math.round(guard.x - tx * guardHalf - ux * quillonLen),
                        (int) Math.round(guard.y - ty * guardHalf - uy * quillonLen),
                        1,
                        cx,
                        cy,
                        radiusSq);
                stampLine(candidate,
                        (int) Math.round(guard.x + tx * guardHalf),
                        (int) Math.round(guard.y + ty * guardHalf),
                        (int) Math.round(guard.x + tx * guardHalf - ux * quillonLen),
                        (int) Math.round(guard.y + ty * guardHalf - uy * quillonLen),
                        1,
                        cx,
                        cy,
                        radiusSq);

                int gripThickness = Math.max(1, bladeThickness - 1);
                Vec2 gripEnd = toward(pommel, guard, 0.90);
                stampLine(candidate,
                        (int) Math.round(pommel.x),
                        (int) Math.round(pommel.y),
                        (int) Math.round(gripEnd.x),
                        (int) Math.round(gripEnd.y),
                        gripThickness,
                        cx,
                        cy,
                        radiusSq);

                int pommelOuter = Math.max(2, (int) Math.round(radius * 0.040 * scale));
                stampDisc(candidate, (int) Math.round(pommel.x), (int) Math.round(pommel.y), pommelOuter, cx, cy, radiusSq);
                stampRing(candidate, (int) Math.round(pommel.x), (int) Math.round(pommel.y), Math.max(0, pommelOuter - 2), pommelOuter + 1, cx, cy, radiusSq);

                // Parallel inset edge for relief depth.
                int reliefOffset = edgeOffset + 1;
                stampLine(candidate,
                        (int) Math.round(guard.x + tx * reliefOffset),
                        (int) Math.round(guard.y + ty * reliefOffset),
                        (int) Math.round(tip.x + tx * reliefOffset),
                        (int) Math.round(tip.y + ty * reliefOffset),
                        1,
                        cx,
                        cy,
                        radiusSq);

                int minPixels = Math.max(BASE_MIN_PIXELS, (int) Math.round(radius * radius * 0.016 * scale));
                if (commitCandidate(base, candidate, STRICT_OVERLAP_RATIO, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval4] carveGreatswordRelief failed", t);
        }
        return false;
    }

    private boolean carveWarhammerRelief(boolean[][] base,
                                         int cx,
                                         int cy,
                                         int radius,
                                         double radiusSq,
                                         double slotStart,
                                         double slotEnd,
                                         double radialMin,
                                         double radialMax,
                                         double scale,
                                         @NotNull RandomSource rng) {
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_MOTIF; attempt++) {
                boolean[][] candidate = newMask(base.length);

                double mid = (slotStart + slotEnd) * 0.5;
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * 0.24;

                double gripNormMin = clamp(radialMin + 0.04, 0.16, 0.82);
                double gripNormMax = clamp(radialMax - 0.30, gripNormMin + 0.02, 0.84);
                double gripR = radius * randomBetween(rng, gripNormMin, gripNormMax);
                double headR = Math.min(radius * 0.90, gripR + radius * (0.32 * scale + rng.nextDouble() * 0.08 * scale));

                Vec2 grip = polar(cx, cy, angle, gripR);
                Vec2 headCenter = polar(cx, cy, angle, headR);

                double ux = Math.cos(angle);
                double uy = Math.sin(angle);
                double tx = -uy;
                double ty = ux;

                int handleThickness = Math.max(2, (int) Math.round(radius * 0.038 * scale));
                stampLine(candidate, (int) Math.round(grip.x), (int) Math.round(grip.y), (int) Math.round(headCenter.x), (int) Math.round(headCenter.y), handleThickness, cx, cy, radiusSq);

                // Grip wraps.
                int wrapLen = Math.max(2, (int) Math.round(radius * 0.040 * scale));
                for (int i = 1; i <= 3; i++) {
                    Vec2 wp = toward(grip, headCenter, i / 4.0);
                    stampLine(candidate,
                            (int) Math.round(wp.x - tx * wrapLen * 0.5),
                            (int) Math.round(wp.y - ty * wrapLen * 0.5),
                            (int) Math.round(wp.x + tx * wrapLen * 0.5),
                            (int) Math.round(wp.y + ty * wrapLen * 0.5),
                            1,
                            cx,
                            cy,
                            radiusSq);
                }

                double halfW = radius * (0.14 * scale + rng.nextDouble() * 0.03 * scale);
                double halfD = radius * (0.07 * scale + rng.nextDouble() * 0.02 * scale);

                Vec2 a = new Vec2(headCenter.x - ux * halfD - tx * halfW, headCenter.y - uy * halfD - ty * halfW);
                Vec2 b = new Vec2(headCenter.x - ux * halfD + tx * halfW, headCenter.y - uy * halfD + ty * halfW);
                Vec2 c = new Vec2(headCenter.x + ux * halfD + tx * halfW, headCenter.y + uy * halfD + ty * halfW);
                Vec2 d = new Vec2(headCenter.x + ux * halfD - tx * halfW, headCenter.y + uy * halfD - ty * halfW);
                stampPolygon(candidate, vecXs(a, b, c, d), vecYs(a, b, c, d), 4, cx, cy, radiusSq);

                // Offset plate to fake depth.
                double plateOffset = halfW * 0.25;
                Vec2 a2 = new Vec2(a.x + tx * plateOffset - ux * (halfD * 0.35), a.y + ty * plateOffset - uy * (halfD * 0.35));
                Vec2 b2 = new Vec2(b.x + tx * plateOffset - ux * (halfD * 0.35), b.y + ty * plateOffset - uy * (halfD * 0.35));
                Vec2 c2 = new Vec2(c.x + tx * plateOffset - ux * (halfD * 0.35), c.y + ty * plateOffset - uy * (halfD * 0.35));
                Vec2 d2 = new Vec2(d.x + tx * plateOffset - ux * (halfD * 0.35), d.y + ty * plateOffset - uy * (halfD * 0.35));
                stampPolygon(candidate, vecXs(a2, b2, c2, d2), vecYs(a2, b2, c2, d2), 4, cx, cy, radiusSq);

                // Spike.
                boolean spikeRight = rng.nextBoolean();
                double side = spikeRight ? 1.0 : -1.0;
                Vec2 spikeBase = new Vec2(headCenter.x + tx * halfW * side, headCenter.y + ty * halfW * side);
                Vec2 spikeTip = new Vec2(spikeBase.x + tx * side * (radius * (0.11 * scale + rng.nextDouble() * 0.03 * scale)),
                        spikeBase.y + ty * side * (radius * (0.11 * scale + rng.nextDouble() * 0.03 * scale)));
                Vec2 spikeL = new Vec2(spikeBase.x - ux * (halfD * 0.55), spikeBase.y - uy * (halfD * 0.55));
                Vec2 spikeR = new Vec2(spikeBase.x + ux * (halfD * 0.55), spikeBase.y + uy * (halfD * 0.55));
                stampPolygon(candidate, vecXs(spikeL, spikeR, spikeTip), vecYs(spikeL, spikeR, spikeTip), 3, cx, cy, radiusSq);

                // Counterweight cap.
                Vec2 cap = new Vec2(headCenter.x - tx * halfW * side, headCenter.y - ty * halfW * side);
                int capR = Math.max(2, (int) Math.round(radius * 0.033 * scale));
                stampDisc(candidate, (int) Math.round(cap.x), (int) Math.round(cap.y), capR, cx, cy, radiusSq);
                stampRing(candidate, (int) Math.round(cap.x), (int) Math.round(cap.y), Math.max(0, capR - 1), capR + 1, cx, cy, radiusSq);

                int pommelR = Math.max(2, (int) Math.round(radius * 0.030 * scale));
                stampDisc(candidate, (int) Math.round(grip.x), (int) Math.round(grip.y), pommelR, cx, cy, radiusSq);

                int minPixels = Math.max(BASE_MIN_PIXELS, (int) Math.round(radius * radius * 0.016 * scale));
                if (commitCandidate(base, candidate, STRICT_OVERLAP_RATIO, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval4] carveWarhammerRelief failed", t);
        }
        return false;
    }

    private AngleRange slotRange(double sliceStart, double sliceEnd, double centerT, double widthT) {
        double span = Math.max(1e-6, sliceEnd - sliceStart);
        double clampedCenter = clamp(centerT, 0.05, 0.95);
        double half = clamp(widthT * 0.5, 0.08, 0.42);
        double startT = clamp(clampedCenter - half, 0.02, 0.98);
        double endT = clamp(clampedCenter + half, 0.02, 0.98);
        if (endT - startT < 0.07) {
            double mid = (startT + endT) * 0.5;
            startT = clamp(mid - 0.035, 0.02, 0.98);
            endT = clamp(mid + 0.035, 0.02, 0.98);
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

    private static boolean[][] newMask(int size) {
        int safe = Math.max(0, size);
        return new boolean[safe][safe];
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
            LOG.error("[SealSigilShapeSetMedieval4] commitCandidate failed", t);
            return false;
        }
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

    private static Vec2 midpoint(Vec2 a, Vec2 b) {
        return new Vec2((a.x + b.x) * 0.5, (a.y + b.y) * 0.5);
    }

    private static Vec2 toward(Vec2 from, Vec2 to, double t) {
        return new Vec2(
                from.x + (to.x - from.x) * t,
                from.y + (to.y - from.y) * t
        );
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
