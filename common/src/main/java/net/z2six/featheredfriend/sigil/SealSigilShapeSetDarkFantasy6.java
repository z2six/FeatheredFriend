package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Fantasy 3 (Dark Fantasy):
 * - Edgy motifs: skulls, dragon heads, ribcage/skeleton forms, crossbones, necro sigils.
 * - Readability-first layout: motifs are placed in separate angular lanes with strict overlap checks.
 */
public final class SealSigilShapeSetDarkFantasy6 implements SealSigilShapeSet {

    private static final Logger LOG = LogUtils.getLogger();

    private static final double SLICE_ANGULAR_MARGIN = Math.toRadians(3.8);
    private static final int DEFAULT_MIN_CANDIDATE_PIXELS = 16;

    private static final double MAJOR_OVERLAP_STRICT = 0.0;
    private static final double MAJOR_OVERLAP_RELAXED = 0.02;
    private static final double SECONDARY_OVERLAP = 0.03;
    private static final double ACCENT_OVERLAP = 0.05;

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

            AngleRange laneA = slotRange(sliceStart, sliceEnd, laneT(0.20, mirrored), 0.22);
            AngleRange laneB = slotRange(sliceStart, sliceEnd, laneT(0.48, mirrored), 0.24);
            AngleRange laneC = slotRange(sliceStart, sliceEnd, laneT(0.78, mirrored), 0.20);

            int motifA = rng.nextInt(4);
            int motifB = (motifA + 1 + rng.nextInt(3)) % 4;

            carveMajorByType(motifA,
                    pixels,
                    cx,
                    cy,
                    radius,
                    radiusSq,
                    laneA.start,
                    laneA.end,
                    0.26,
                    0.55,
                    1.00,
                    true,
                    rng);

            carveMajorByType(motifB,
                    pixels,
                    cx,
                    cy,
                    radius,
                    radiusSq,
                    laneB.start,
                    laneB.end,
                    0.48,
                    0.78,
                    0.96,
                    true,
                    rng);

            if (rng.nextFloat() < 0.52f) {
                int motifC = rng.nextInt(4);
                carveMajorByType(motifC,
                        pixels,
                        cx,
                        cy,
                        radius,
                        radiusSq,
                        laneC.start,
                        laneC.end,
                        0.66,
                        0.90,
                        0.78,
                        false,
                        rng);
            }

            int necroCount = 1 + rng.nextInt(2);
            for (int i = 0; i < necroCount; i++) {
                double centerT = laneT(0.18 + i * 0.36 + rng.nextDouble() * 0.08, mirrored);
                AngleRange ar = slotRange(sliceStart, sliceEnd, centerT, 0.18);
                carveNecroCircleAccent(pixels, cx, cy, radius, radiusSq, ar.start, ar.end, rng);
            }

            if (rng.nextFloat() < 0.60f) {
                carveBoneChainAccent(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);
            }
            if (rng.nextFloat() < 0.55f) {
                carveSpikeCrownAccent(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);
            }
            if (rng.nextFloat() < 0.48f) {
                carveRuneShardsAccent(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);
            }

        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetDarkFantasy6] applyShapesInSlice failed", t);
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
            case 0 -> carveSkullMotif(pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, primary, rng);
            case 1 -> carveDragonHeadMotif(pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, primary, rng);
            case 2 -> carveRibcageMotif(pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, primary, rng);
            default -> carveCrossbonesMotif(pixels, cx, cy, radius, radiusSq, slotStart, slotEnd, radialMin, radialMax, scale, primary, rng);
        };
    }

    private boolean carveSkullMotif(boolean[][] base,
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
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * (primary ? 0.18 : 0.25);
                double centerR = radius * randomBetween(rng, radialMin, radialMax);
                Vec2 c = polar(cx, cy, angle, centerR);

                double ux = Math.cos(angle);
                double uy = Math.sin(angle);
                double tx = -uy;
                double ty = ux;

                double w = radius * (primary ? 0.13 : 0.10) * scale;
                double h = radius * (primary ? 0.19 : 0.15) * scale;

                Vec2 topL = new Vec2(c.x - ux * (h * 0.95) - tx * (w * 0.86), c.y - uy * (h * 0.95) - ty * (w * 0.86));
                Vec2 topR = new Vec2(c.x - ux * (h * 0.95) + tx * (w * 0.86), c.y - uy * (h * 0.95) + ty * (w * 0.86));
                Vec2 cheekR = new Vec2(c.x + ux * (h * 0.05) + tx * (w * 1.06), c.y + uy * (h * 0.05) + ty * (w * 1.06));
                Vec2 jawR = new Vec2(c.x + ux * (h * 0.70) + tx * (w * 0.74), c.y + uy * (h * 0.70) + ty * (w * 0.74));
                Vec2 chin = new Vec2(c.x + ux * (h * 1.06), c.y + uy * (h * 1.06));
                Vec2 jawL = new Vec2(c.x + ux * (h * 0.70) - tx * (w * 0.74), c.y + uy * (h * 0.70) - ty * (w * 0.74));
                Vec2 cheekL = new Vec2(c.x + ux * (h * 0.05) - tx * (w * 1.06), c.y + uy * (h * 0.05) - ty * (w * 1.06));

                stampPolygon(candidate,
                        vecXs(topL, topR, cheekR, jawR, chin, jawL, cheekL),
                        vecYs(topL, topR, cheekR, jawR, chin, jawL, cheekL),
                        7,
                        cx,
                        cy,
                        radiusSq);

                for (int side = -1; side <= 1; side += 2) {
                    double hornLen = radius * (primary ? 0.09 : 0.07) * scale;
                    Vec2 hb = side < 0 ? topL : topR;
                    Vec2 h1 = new Vec2(hb.x - ux * hornLen + tx * side * hornLen * 0.22, hb.y - uy * hornLen + ty * side * hornLen * 0.22);
                    Vec2 h2 = new Vec2(hb.x - ux * hornLen * 0.45 + tx * side * hornLen * 0.45, hb.y - uy * hornLen * 0.45 + ty * side * hornLen * 0.45);
                    stampPolygon(candidate,
                            vecXs(hb, h2, h1),
                            vecYs(hb, h2, h1),
                            3,
                            cx,
                            cy,
                            radiusSq);
                }

                int teeth = primary ? 4 : 3;
                for (int i = 0; i < teeth; i++) {
                    double t = (i + 0.5) / teeth;
                    Vec2 jb = toward(jawL, jawR, t);
                    Vec2 tip = new Vec2(jb.x + ux * (h * 0.24), jb.y + uy * (h * 0.24));
                    double toothW = w * (primary ? 0.12 : 0.10);
                    Vec2 l = new Vec2(jb.x - tx * toothW, jb.y - ty * toothW);
                    Vec2 r = new Vec2(jb.x + tx * toothW, jb.y + ty * toothW);
                    stampPolygon(candidate, vecXs(l, r, tip), vecYs(l, r, tip), 3, cx, cy, radiusSq);
                }

                int slashLen = Math.max(2, (int) Math.round(radius * (primary ? 0.055 : 0.045) * scale));
                Vec2 eyeL = new Vec2(c.x - ux * (h * 0.18) - tx * (w * 0.30), c.y - uy * (h * 0.18) - ty * (w * 0.30));
                Vec2 eyeR = new Vec2(c.x - ux * (h * 0.18) + tx * (w * 0.30), c.y - uy * (h * 0.18) + ty * (w * 0.30));
                stampLine(candidate,
                        (int) Math.round(eyeL.x - tx * slashLen),
                        (int) Math.round(eyeL.y - ty * slashLen),
                        (int) Math.round(eyeL.x + tx * slashLen),
                        (int) Math.round(eyeL.y + ty * slashLen),
                        1,
                        cx,
                        cy,
                        radiusSq);
                stampLine(candidate,
                        (int) Math.round(eyeR.x - tx * slashLen),
                        (int) Math.round(eyeR.y - ty * slashLen),
                        (int) Math.round(eyeR.x + tx * slashLen),
                        (int) Math.round(eyeR.y + ty * slashLen),
                        1,
                        cx,
                        cy,
                        radiusSq);

                int minPixels = Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + (primary ? 16 : 8), radius / 3);
                if (primary) {
                    if (commitMandatory(base, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, minPixels)) {
                        return true;
                    }
                } else if (commitCandidate(base, candidate, SECONDARY_OVERLAP, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetDarkFantasy6] carveSkullMotif failed", t);
        }
        return false;
    }

    private boolean carveDragonHeadMotif(boolean[][] base,
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
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * (primary ? 0.20 : 0.28);
                double centerR = radius * randomBetween(rng, radialMin, radialMax);
                Vec2 c = polar(cx, cy, angle, centerR);

                double ux = Math.cos(angle);
                double uy = Math.sin(angle);
                double tx = -uy;
                double ty = ux;

                double headLen = radius * (primary ? 0.24 : 0.19) * scale;
                double headHalf = radius * (primary ? 0.10 : 0.08) * scale;

                Vec2 rearL = new Vec2(c.x - ux * (headLen * 0.30) - tx * headHalf, c.y - uy * (headLen * 0.30) - ty * headHalf);
                Vec2 rearR = new Vec2(c.x - ux * (headLen * 0.30) + tx * headHalf, c.y - uy * (headLen * 0.30) + ty * headHalf);
                Vec2 nose = new Vec2(c.x + ux * headLen, c.y + uy * headLen);
                Vec2 upperTip = new Vec2(nose.x + tx * (headHalf * 0.25), nose.y + ty * (headHalf * 0.25));
                Vec2 lowerTip = new Vec2(nose.x - tx * (headHalf * 0.25), nose.y - ty * (headHalf * 0.25));

                Vec2 jawMid = new Vec2(c.x + ux * (headLen * 0.48) - tx * (headHalf * 0.68), c.y + uy * (headLen * 0.48) - ty * (headHalf * 0.68));
                Vec2 skullMid = new Vec2(c.x + ux * (headLen * 0.36) + tx * (headHalf * 0.76), c.y + uy * (headLen * 0.36) + ty * (headHalf * 0.76));

                stampPolygon(candidate,
                        vecXs(rearL, rearR, skullMid, upperTip),
                        vecYs(rearL, rearR, skullMid, upperTip),
                        4,
                        cx,
                        cy,
                        radiusSq);

                stampPolygon(candidate,
                        vecXs(rearL, jawMid, lowerTip),
                        vecYs(rearL, jawMid, lowerTip),
                        3,
                        cx,
                        cy,
                        radiusSq);

                int neckThickness = Math.max(1, (int) Math.round(radius * (primary ? 0.030 : 0.022) * scale));
                Vec2 neckBack = new Vec2(c.x - ux * (headLen * 0.78), c.y - uy * (headLen * 0.78));
                stampLine(candidate,
                        (int) Math.round(neckBack.x),
                        (int) Math.round(neckBack.y),
                        (int) Math.round(c.x - ux * (headLen * 0.18)),
                        (int) Math.round(c.y - uy * (headLen * 0.18)),
                        neckThickness,
                        cx,
                        cy,
                        radiusSq);

                for (int h = 0; h < 2; h++) {
                    double side = h == 0 ? 1.0 : -1.0;
                    double hornLen = radius * (primary ? 0.11 : 0.09) * scale;
                    Vec2 hb = new Vec2(c.x - ux * (headLen * 0.18) + tx * side * (headHalf * 0.72),
                            c.y - uy * (headLen * 0.18) + ty * side * (headHalf * 0.72));
                    Vec2 ht = new Vec2(hb.x - ux * hornLen + tx * side * hornLen * 0.30,
                            hb.y - uy * hornLen + ty * side * hornLen * 0.30);
                    stampLine(candidate,
                            (int) Math.round(hb.x),
                            (int) Math.round(hb.y),
                            (int) Math.round(ht.x),
                            (int) Math.round(ht.y),
                            1,
                            cx,
                            cy,
                            radiusSq);
                }

                int crestCount = primary ? 4 : 3;
                for (int i = 0; i < crestCount; i++) {
                    double t = (i + 0.2) / (crestCount + 0.2);
                    Vec2 p = toward(rearR, upperTip, t);
                    Vec2 tip = new Vec2(p.x + tx * (headHalf * 0.34) + ux * (headLen * 0.08),
                            p.y + ty * (headHalf * 0.34) + uy * (headLen * 0.08));
                    stampLine(candidate,
                            (int) Math.round(p.x),
                            (int) Math.round(p.y),
                            (int) Math.round(tip.x),
                            (int) Math.round(tip.y),
                            1,
                            cx,
                            cy,
                            radiusSq);
                }

                Vec2 eye = new Vec2(c.x + ux * (headLen * 0.18) + tx * (headHalf * 0.30),
                        c.y + uy * (headLen * 0.18) + ty * (headHalf * 0.30));
                stampDisc(candidate, (int) Math.round(eye.x), (int) Math.round(eye.y), Math.max(1, radius / 56), cx, cy, radiusSq);

                int minPixels = Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + (primary ? 16 : 8), radius / 3);
                if (primary) {
                    if (commitMandatory(base, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, minPixels)) {
                        return true;
                    }
                } else if (commitCandidate(base, candidate, SECONDARY_OVERLAP, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetDarkFantasy6] carveDragonHeadMotif failed", t);
        }
        return false;
    }

    private boolean carveRibcageMotif(boolean[][] base,
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
                double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * (primary ? 0.20 : 0.30);
                double centerR = radius * randomBetween(rng, radialMin, radialMax);
                Vec2 c = polar(cx, cy, angle, centerR);

                double ux = Math.cos(angle);
                double uy = Math.sin(angle);
                double tx = -uy;
                double ty = ux;

                double spineLen = radius * (primary ? 0.42 : 0.34) * scale;
                Vec2 spineStart = new Vec2(c.x - ux * (spineLen * 0.42), c.y - uy * (spineLen * 0.42));
                Vec2 spineEnd = new Vec2(c.x + ux * (spineLen * 0.58), c.y + uy * (spineLen * 0.58));
                int spineThick = Math.max(1, (int) Math.round(radius * (primary ? 0.028 : 0.022) * scale));

                stampLine(candidate,
                        (int) Math.round(spineStart.x),
                        (int) Math.round(spineStart.y),
                        (int) Math.round(spineEnd.x),
                        (int) Math.round(spineEnd.y),
                        spineThick,
                        cx,
                        cy,
                        radiusSq);

                int ribCount = primary ? 4 : 3;
                for (int i = 0; i < ribCount; i++) {
                    double t = 0.14 + i * (0.66 / Math.max(1, ribCount - 1));
                    Vec2 rc = toward(spineStart, spineEnd, t);

                    double ribW = radius * (primary ? 0.11 : 0.09) * scale * (1.0 - i * 0.08);
                    double ribOut = spineLen * (0.16 + i * 0.03);

                    for (int side = -1; side <= 1; side += 2) {
                        Vec2 b = new Vec2(rc.x + tx * side * (ribW * 0.20), rc.y + ty * side * (ribW * 0.20));
                        Vec2 m = new Vec2(rc.x + tx * side * (ribW * 0.78) + ux * ribOut * 0.35,
                                rc.y + ty * side * (ribW * 0.78) + uy * ribOut * 0.35);
                        Vec2 e = new Vec2(rc.x + tx * side * (ribW * 1.05) + ux * ribOut,
                                rc.y + ty * side * (ribW * 1.05) + uy * ribOut);
                        stampLine(candidate,
                                (int) Math.round(b.x),
                                (int) Math.round(b.y),
                                (int) Math.round(m.x),
                                (int) Math.round(m.y),
                                1,
                                cx,
                                cy,
                                radiusSq);
                        stampLine(candidate,
                                (int) Math.round(m.x),
                                (int) Math.round(m.y),
                                (int) Math.round(e.x),
                                (int) Math.round(e.y),
                                1,
                                cx,
                                cy,
                                radiusSq);
                    }
                }

                int skullR = Math.max(2, (int) Math.round(radius * (primary ? 0.038 : 0.030) * scale));
                stampDisc(candidate,
                        (int) Math.round(spineStart.x - ux * (skullR * 1.2)),
                        (int) Math.round(spineStart.y - uy * (skullR * 1.2)),
                        skullR,
                        cx,
                        cy,
                        radiusSq);

                double pelvisW = radius * (primary ? 0.11 : 0.09) * scale;
                Vec2 pC = new Vec2(spineEnd.x + ux * (radius * 0.02), spineEnd.y + uy * (radius * 0.02));
                Vec2 p1 = new Vec2(pC.x - tx * pelvisW, pC.y - ty * pelvisW);
                Vec2 p2 = new Vec2(pC.x + tx * pelvisW, pC.y + ty * pelvisW);
                Vec2 p3 = new Vec2(pC.x + ux * (pelvisW * 0.90), pC.y + uy * (pelvisW * 0.90));
                Vec2 p4 = new Vec2(pC.x - ux * (pelvisW * 0.45), pC.y - uy * (pelvisW * 0.45));
                stampPolygon(candidate, vecXs(p1, p2, p3, p4), vecYs(p1, p2, p3, p4), 4, cx, cy, radiusSq);

                int minPixels = Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + (primary ? 14 : 8), radius / 3);
                if (primary) {
                    if (commitMandatory(base, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, minPixels)) {
                        return true;
                    }
                } else if (commitCandidate(base, candidate, SECONDARY_OVERLAP, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetDarkFantasy6] carveRibcageMotif failed", t);
        }
        return false;
    }

    private boolean carveCrossbonesMotif(boolean[][] base,
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

                double len = radius * (primary ? 0.30 : 0.24) * scale;
                double thick = radius * (primary ? 0.030 : 0.023) * scale;
                double a1 = angle + Math.toRadians(36 + rng.nextDouble() * 24);
                double a2 = angle - Math.toRadians(36 + rng.nextDouble() * 24);

                stampBoneShaft(candidate, c, a1, len, thick, cx, cy, radiusSq);
                stampBoneShaft(candidate, c, a2, len, thick, cx, cy, radiusSq);

                int ringIn = Math.max(1, (int) Math.round(radius * (primary ? 0.030 : 0.024) * scale));
                stampRing(candidate, (int) Math.round(c.x), (int) Math.round(c.y), ringIn, ringIn + 2, cx, cy, radiusSq);
                stampDisc(candidate, (int) Math.round(c.x), (int) Math.round(c.y), Math.max(1, ringIn / 2), cx, cy, radiusSq);

                if (rng.nextFloat() < 0.70f) {
                    int spikeCount = primary ? 5 : 4;
                    for (int i = 0; i < spikeCount; i++) {
                        double sa = (Math.PI * 2.0 * i) / spikeCount + rng.nextDouble() * 0.25;
                        int sx = (int) Math.round(c.x + Math.cos(sa) * (ringIn + 1));
                        int sy = (int) Math.round(c.y + Math.sin(sa) * (ringIn + 1));
                        int tx = (int) Math.round(c.x + Math.cos(sa) * (ringIn + radius * (primary ? 0.055 : 0.040)));
                        int ty = (int) Math.round(c.y + Math.sin(sa) * (ringIn + radius * (primary ? 0.055 : 0.040)));
                        stampLine(candidate, sx, sy, tx, ty, 1, cx, cy, radiusSq);
                    }
                }

                int minPixels = Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + (primary ? 14 : 8), radius / 3);
                if (primary) {
                    if (commitMandatory(base, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, minPixels)) {
                        return true;
                    }
                } else if (commitCandidate(base, candidate, SECONDARY_OVERLAP, minPixels)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetDarkFantasy6] carveCrossbonesMotif failed", t);
        }
        return false;
    }

    private void carveNecroCircleAccent(boolean[][] pixels,
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
            double angle = mid + (rng.nextDouble() - 0.5) * (slotEnd - slotStart) * 0.16;
            double centerR = radius * (0.50 + rng.nextDouble() * 0.30);

            int gx = (int) Math.round(cx + Math.cos(angle) * centerR);
            int gy = (int) Math.round(cy + Math.sin(angle) * centerR);

            int core = Math.max(2, (int) Math.round(radius * (0.030 + rng.nextDouble() * 0.012)));
            stampRing(candidate, gx, gy, Math.max(0, core - 2), core, cx, cy, radiusSq);
            stampRing(candidate, gx, gy, core + 2, core + 4, cx, cy, radiusSq);

            int spikes = 5 + rng.nextInt(3);
            for (int i = 0; i < spikes; i++) {
                double a = (Math.PI * 2.0 * i) / spikes + rng.nextDouble() * 0.35;
                int sx = (int) Math.round(gx + Math.cos(a) * (core + 1));
                int sy = (int) Math.round(gy + Math.sin(a) * (core + 1));
                int tx = (int) Math.round(gx + Math.cos(a) * (core + radius * 0.040));
                int ty = (int) Math.round(gy + Math.sin(a) * (core + radius * 0.040));
                stampLine(candidate, sx, sy, tx, ty, 1, cx, cy, radiusSq);
            }

            int rune = rng.nextInt(3);
            if (rune == 0) {
                stampLine(candidate, gx - core, gy, gx + core, gy, 1, cx, cy, radiusSq);
                stampLine(candidate, gx, gy - core, gx, gy + core, 1, cx, cy, radiusSq);
            } else if (rune == 1) {
                stampLine(candidate, gx - core, gy - core, gx + core, gy + core, 1, cx, cy, radiusSq);
                stampLine(candidate, gx - core, gy + core, gx + core, gy - core, 1, cx, cy, radiusSq);
            } else {
                int[] xs = {gx, gx + core, gx, gx - core};
                int[] ys = {gy - core, gy, gy + core, gy};
                stampPolygon(candidate, xs, ys, 4, cx, cy, radiusSq);
            }

            commitCandidate(pixels, candidate, ACCENT_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 4, 9));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetDarkFantasy6] carveNecroCircleAccent failed", t);
        }
    }

    private void carveBoneChainAccent(boolean[][] pixels,
                                      int cx,
                                      int cy,
                                      int radius,
                                      double radiusSq,
                                      double sliceStart,
                                      double sliceEnd,
                                      @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            int links = 2 + rng.nextInt(3);
            double span = sliceEnd - sliceStart;
            Vec2 prev = null;

            for (int i = 0; i < links; i++) {
                double t = (i + 0.5) / links;
                double a = sliceStart + span * t + (rng.nextDouble() - 0.5) * span * 0.10;
                double r = radius * (0.70 + rng.nextDouble() * 0.18);
                Vec2 p = polar(cx, cy, a, r);
                int lx = (int) Math.round(p.x);
                int ly = (int) Math.round(p.y);
                int outer = Math.max(1, (int) Math.round(radius * 0.021));
                stampRing(candidate, lx, ly, Math.max(0, outer - 1), outer + 1, cx, cy, radiusSq);

                if (prev != null) {
                    stampLine(candidate,
                            (int) Math.round(prev.x),
                            (int) Math.round(prev.y),
                            lx,
                            ly,
                            1,
                            cx,
                            cy,
                            radiusSq);
                }
                prev = p;
            }

            commitCandidate(pixels, candidate, ACCENT_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 6, 8));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetDarkFantasy6] carveBoneChainAccent failed", t);
        }
    }

    private void carveSpikeCrownAccent(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       double sliceStart,
                                       double sliceEnd,
                                       @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            double span = sliceEnd - sliceStart;
            int spikes = 3 + rng.nextInt(3);

            for (int i = 0; i < spikes; i++) {
                double t = (i + 0.5) / spikes;
                double a = sliceStart + span * t + (rng.nextDouble() - 0.5) * span * 0.06;
                double r = radius * (0.84 + rng.nextDouble() * 0.08);

                double ux = Math.cos(a);
                double uy = Math.sin(a);
                double tx = -uy;
                double ty = ux;

                Vec2 b = polar(cx, cy, a, r);
                double h = radius * (0.055 + rng.nextDouble() * 0.025);
                double w = radius * (0.018 + rng.nextDouble() * 0.010);
                Vec2 l = new Vec2(b.x - tx * w, b.y - ty * w);
                Vec2 rr = new Vec2(b.x + tx * w, b.y + ty * w);
                Vec2 tip = new Vec2(b.x + ux * h, b.y + uy * h);
                stampPolygon(candidate, vecXs(l, rr, tip), vecYs(l, rr, tip), 3, cx, cy, radiusSq);
            }

            commitCandidate(pixels, candidate, ACCENT_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 4, 9));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetDarkFantasy6] carveSpikeCrownAccent failed", t);
        }
    }

    private void carveRuneShardsAccent(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       double sliceStart,
                                       double sliceEnd,
                                       @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            int shards = 4 + rng.nextInt(4);
            double span = sliceEnd - sliceStart;

            for (int i = 0; i < shards; i++) {
                double a = sliceStart + span * rng.nextDouble();
                double r = radius * (0.36 + rng.nextDouble() * 0.52);
                Vec2 c = polar(cx, cy, a, r);
                double ux = Math.cos(a);
                double uy = Math.sin(a);
                double tx = -uy;
                double ty = ux;

                double len = radius * (0.030 + rng.nextDouble() * 0.022);
                double w = radius * (0.010 + rng.nextDouble() * 0.010);

                Vec2 p1 = new Vec2(c.x - ux * len * 0.5 - tx * w, c.y - uy * len * 0.5 - ty * w);
                Vec2 p2 = new Vec2(c.x - ux * len * 0.5 + tx * w, c.y - uy * len * 0.5 + ty * w);
                Vec2 p3 = new Vec2(c.x + ux * len, c.y + uy * len);
                stampPolygon(candidate, vecXs(p1, p2, p3), vecYs(p1, p2, p3), 3, cx, cy, radiusSq);
            }

            commitCandidate(pixels, candidate, ACCENT_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 4, 9));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetDarkFantasy6] carveRuneShardsAccent failed", t);
        }
    }

    private void stampBoneShaft(boolean[][] pixels,
                                Vec2 center,
                                double angle,
                                double length,
                                double thickness,
                                int cx,
                                int cy,
                                double radiusSq) {
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);

        Vec2 a = new Vec2(center.x - ux * (length * 0.5), center.y - uy * (length * 0.5));
        Vec2 b = new Vec2(center.x + ux * (length * 0.5), center.y + uy * (length * 0.5));

        int shaftThick = Math.max(1, (int) Math.round(thickness));
        stampLine(pixels,
                (int) Math.round(a.x),
                (int) Math.round(a.y),
                (int) Math.round(b.x),
                (int) Math.round(b.y),
                shaftThick,
                cx,
                cy,
                radiusSq);

        int jointR = Math.max(1, (int) Math.round(thickness * 1.4));
        stampDisc(pixels, (int) Math.round(a.x), (int) Math.round(a.y), jointR, cx, cy, radiusSq);
        stampDisc(pixels, (int) Math.round(b.x), (int) Math.round(b.y), jointR, cx, cy, radiusSq);
    }

    private AngleRange slotRange(double sliceStart, double sliceEnd, double centerT, double widthT) {
        double span = Math.max(1e-6, sliceEnd - sliceStart);
        double clampedCenter = clamp(centerT, 0.05, 0.95);
        double half = clamp(widthT * 0.5, 0.03, 0.34);

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
            LOG.error("[SealSigilShapeSetDarkFantasy6] commitCandidate failed", t);
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
