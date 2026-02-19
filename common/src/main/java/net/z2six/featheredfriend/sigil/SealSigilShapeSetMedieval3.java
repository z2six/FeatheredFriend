package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public final class SealSigilShapeSetMedieval3 implements SealSigilShapeSet {

    private static final Logger LOG = LogUtils.getLogger();

    private static final double SLICE_ANGULAR_MARGIN = Math.toRadians(3.5);
    private static final double CORE_BAND_MIN = 0.20;
    private static final double MID_BAND_MIN = 0.45;
    private static final double MID_BAND_MAX = 0.68;
    private static final double OUTER_BAND_MIN = 0.72;
    private static final double OUTER_BAND_MAX = 0.91;

    private static final int DEFAULT_MIN_CANDIDATE_PIXELS = 18;
    private static final double MAJOR_OVERLAP_STRICT = 0.0;
    private static final double MAJOR_OVERLAP_RELAXED = 0.0;
    private static final double SECONDARY_OVERLAP = 0.0;
    private static final double FILLER_OVERLAP = 0.0;
    private static final double DETAIL_OVERLAP = 0.0;

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

            // Spread motifs across angular slots so they use the whole slice and avoid overlap.
            boolean mirrored = rng.nextBoolean();
            AngleRange crossRange = slotRange(sliceStart, sliceEnd, laneT(0.16, mirrored), 0.18);
            AngleRange weaponPrimaryRange = slotRange(sliceStart, sliceEnd, laneT(0.34, mirrored), 0.18);
            AngleRange shieldRange = slotRange(sliceStart, sliceEnd, laneT(0.54, mirrored), 0.20);
            AngleRange weaponSecondaryRange = slotRange(sliceStart, sliceEnd, laneT(0.72, mirrored), 0.18);
            AngleRange crestRange = slotRange(sliceStart, sliceEnd, laneT(0.86, mirrored), 0.14);

            carveTemplarCrossPrimary(pixels, cx, cy, radius, radiusSq, crossRange.start, crossRange.end, rng);

            boolean swordPrimary = rng.nextBoolean();
            if (swordPrimary) {
                carveLongswordMotif(pixels, cx, cy, radius, radiusSq, weaponPrimaryRange.start, weaponPrimaryRange.end, rng, true);
            } else {
                carveFlailMotif(pixels, cx, cy, radius, radiusSq, weaponPrimaryRange.start, weaponPrimaryRange.end, rng, true);
            }

            carveHeraldicShieldPrimary(pixels, cx, cy, radius, radiusSq, shieldRange.start, shieldRange.end, rng);
            carveCrestCrownPrimary(pixels, cx, cy, radius, radiusSq, crestRange.start, crestRange.end, rng);

            if (rng.nextFloat() < 0.92f) {
                if (swordPrimary) {
                    carveFlailMotif(pixels, cx, cy, radius, radiusSq, weaponSecondaryRange.start, weaponSecondaryRange.end, rng, false);
                } else {
                    carveLongswordMotif(pixels, cx, cy, radius, radiusSq, weaponSecondaryRange.start, weaponSecondaryRange.end, rng, false);
                }
            }

            int bannerCount = 2 + rng.nextInt(2);
            for (int i = 0; i < bannerCount; i++) {
                double centerT = laneT(0.22 + i * 0.24, mirrored);
                AngleRange bannerRange = slotRange(sliceStart, sliceEnd, centerT, 0.26);
                carveBannerFiller(pixels, cx, cy, radius, radiusSq, bannerRange.start, bannerRange.end, rng, i);
            }

            int ornamentPasses = 4 + rng.nextInt(3);
            for (int i = 0; i < ornamentPasses; i++) {
                double t = i / (double) Math.max(1, ornamentPasses - 1);
                double centerT = laneT(0.08 + 0.84 * t, mirrored);
                AngleRange ornamentRange = slotRange(sliceStart, sliceEnd, centerT, 0.14);
                int motif = rng.nextInt(3);
                if (motif == 0) {
                    carveMiniShieldFiller(pixels, cx, cy, radius, radiusSq, ornamentRange.start, ornamentRange.end, rng);
                } else if (motif == 1) {
                    carveMiniCrossFiller(pixels, cx, cy, radius, radiusSq, ornamentRange.start, ornamentRange.end, rng);
                } else {
                    carveChainLinkFiller(pixels, cx, cy, radius, radiusSq, ornamentRange.start, ornamentRange.end, rng);
                }
            }
            carveEdgeStuds(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);

        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] applyShapesInSlice failed", t);
        }
    }

    private AngleRange slotRange(double sliceStart, double sliceEnd, double centerT, double widthT) {
        double span = Math.max(1e-6, sliceEnd - sliceStart);
        double clampedCenter = clamp(centerT, 0.05, 0.95);
        double half = clamp(widthT * 0.5, 0.03, 0.32);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void carveTemplarCrossPrimary(boolean[][] pixels,
                                          int cx,
                                          int cy,
                                          int radius,
                                          double radiusSq,
                                          double sliceStart,
                                          double sliceEnd,
                                          @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            double mid = (sliceStart + sliceEnd) * 0.5;
            double angle = mid + (rng.nextDouble() - 0.5) * (sliceEnd - sliceStart) * 0.12;

            double centerR = radius * (CORE_BAND_MIN + 0.05 + rng.nextDouble() * 0.05);
            int centerX = (int) Math.round(cx + Math.cos(angle) * centerR);
            int centerY = (int) Math.round(cy + Math.sin(angle) * centerR);

            double ux = Math.cos(angle);
            double uy = Math.sin(angle);
            double tx = -uy;
            double ty = ux;

            double armLong = radius * (0.17 + rng.nextDouble() * 0.03);
            double armShort = radius * (0.12 + rng.nextDouble() * 0.03);
            int coreThickness = Math.max(2, (int) Math.round(radius * 0.045));

            int innerX = (int) Math.round(centerX - ux * (armLong * 0.55));
            int innerY = (int) Math.round(centerY - uy * (armLong * 0.55));
            int outerX = (int) Math.round(centerX + ux * armLong);
            int outerY = (int) Math.round(centerY + uy * armLong);
            stampLine(candidate, innerX, innerY, outerX, outerY, coreThickness, cx, cy, radiusSq);

            int leftX = (int) Math.round(centerX + tx * armShort);
            int leftY = (int) Math.round(centerY + ty * armShort);
            int rightX = (int) Math.round(centerX - tx * armShort);
            int rightY = (int) Math.round(centerY - ty * armShort);
            stampLine(candidate, leftX, leftY, rightX, rightY, coreThickness, cx, cy, radiusSq);

            double flareLen = radius * 0.050;
            double flareBase = radius * 0.032;
            double flareMouth = radius * 0.016;

            stampFlaredTip(candidate, outerX, outerY, ux, uy, tx, ty, flareLen, flareBase, flareMouth, cx, cy, radiusSq);
            stampFlaredTip(candidate, innerX, innerY, -ux, -uy, tx, ty, flareLen, flareBase, flareMouth, cx, cy, radiusSq);
            stampFlaredTip(candidate, leftX, leftY, tx, ty, ux, uy, flareLen * 0.82, flareBase * 0.86, flareMouth * 0.9, cx, cy, radiusSq);
            stampFlaredTip(candidate, rightX, rightY, -tx, -ty, ux, uy, flareLen * 0.82, flareBase * 0.86, flareMouth * 0.9, cx, cy, radiusSq);

            int coreDiamondR = Math.max(2, (int) Math.round(radius * 0.030));
            int[] dx = {centerX, centerX + coreDiamondR, centerX, centerX - coreDiamondR};
            int[] dy = {centerY - coreDiamondR, centerY, centerY + coreDiamondR, centerY};
            stampPolygon(candidate, dx, dy, 4, cx, cy, radiusSq);

            commitMandatory(
                    pixels,
                    candidate,
                    MAJOR_OVERLAP_STRICT,
                    MAJOR_OVERLAP_RELAXED,
                    Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + 10, radius / 2)
            );
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveTemplarCrossPrimary failed", t);
        }
    }

    private void carveLongswordMotif(boolean[][] pixels,
                                     int cx,
                                     int cy,
                                     int radius,
                                     double radiusSq,
                                     double sliceStart,
                                     double sliceEnd,
                                     @NotNull RandomSource rng,
                                     boolean primary) {
        try {
            boolean[][] candidate = newMask(pixels.length);

            double mid = (sliceStart + sliceEnd) * 0.5;
            double angle = mid + (rng.nextDouble() - 0.5) * (sliceEnd - sliceStart) * (primary ? 0.16 : 0.22);

            double handleR = radius * (primary ? 0.36 : 0.42);
            double tipR = radius * (primary ? 0.87 : 0.80);

            int handleX = (int) Math.round(cx + Math.cos(angle) * handleR);
            int handleY = (int) Math.round(cy + Math.sin(angle) * handleR);
            int tipX = (int) Math.round(cx + Math.cos(angle) * tipR);
            int tipY = (int) Math.round(cy + Math.sin(angle) * tipR);

            double ux = Math.cos(angle);
            double uy = Math.sin(angle);
            double tx = -uy;
            double ty = ux;

            int bladeThickness = Math.max(primary ? 2 : 1, (int) Math.round(radius * (primary ? 0.038 : 0.028)));
            stampLine(candidate, handleX, handleY, tipX, tipY, bladeThickness, cx, cy, radiusSq);

            int sideOffset = Math.max(1, bladeThickness / 2);
            stampLine(candidate,
                    (int) Math.round(handleX + tx * sideOffset),
                    (int) Math.round(handleY + ty * sideOffset),
                    (int) Math.round(tipX + tx * sideOffset),
                    (int) Math.round(tipY + ty * sideOffset),
                    1, cx, cy, radiusSq);
            stampLine(candidate,
                    (int) Math.round(handleX - tx * sideOffset),
                    (int) Math.round(handleY - ty * sideOffset),
                    (int) Math.round(tipX - tx * sideOffset),
                    (int) Math.round(tipY - ty * sideOffset),
                    1, cx, cy, radiusSq);

            Vec2 handle = new Vec2(handleX, handleY);
            Vec2 tip = new Vec2(tipX, tipY);
            Vec2 fullerStart = lerp(handle, tip, 0.16);
            Vec2 fullerEnd = lerp(handle, tip, primary ? 0.78 : 0.70);
            stampLine(candidate,
                    (int) Math.round(fullerStart.x),
                    (int) Math.round(fullerStart.y),
                    (int) Math.round(fullerEnd.x),
                    (int) Math.round(fullerEnd.y),
                    1, cx, cy, radiusSq);

            double guardHalf = radius * (primary ? 0.10 : 0.08);
            int guardCx = (int) Math.round(handleX + ux * (radius * 0.01));
            int guardCy = (int) Math.round(handleY + uy * (radius * 0.01));
            int guardLx = (int) Math.round(guardCx + tx * guardHalf);
            int guardLy = (int) Math.round(guardCy + ty * guardHalf);
            int guardRx = (int) Math.round(guardCx - tx * guardHalf);
            int guardRy = (int) Math.round(guardCy - ty * guardHalf);
            stampLine(candidate, guardLx, guardLy, guardRx, guardRy, Math.max(1, bladeThickness - 1), cx, cy, radiusSq);

            int quillonLen = (int) Math.round(radius * (primary ? 0.040 : 0.030));
            stampLine(candidate, guardLx, guardLy,
                    (int) Math.round(guardLx - ux * quillonLen),
                    (int) Math.round(guardLy - uy * quillonLen),
                    1, cx, cy, radiusSq);
            stampLine(candidate, guardRx, guardRy,
                    (int) Math.round(guardRx - ux * quillonLen),
                    (int) Math.round(guardRy - uy * quillonLen),
                    1, cx, cy, radiusSq);

            int pommelX = (int) Math.round(handleX - ux * (radius * (primary ? 0.070 : 0.055)));
            int pommelY = (int) Math.round(handleY - uy * (radius * (primary ? 0.070 : 0.055)));
            int pommelR = Math.max(1, (int) Math.round(radius * (primary ? 0.032 : 0.024)));
            stampDisc(candidate, pommelX, pommelY, pommelR, cx, cy, radiusSq);

            stampLine(candidate,
                    pommelX,
                    pommelY,
                    (int) Math.round(handleX - ux * (radius * 0.01)),
                    (int) Math.round(handleY - uy * (radius * 0.01)),
                    Math.max(1, bladeThickness - 1),
                    cx, cy, radiusSq);

            if (primary) {
                commitMandatory(pixels, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + 10, radius / 2));
            } else {
                commitCandidate(pixels, candidate, SECONDARY_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS, radius / 3));
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveLongswordMotif failed", t);
        }
    }

    private void carveFlailMotif(boolean[][] pixels,
                                 int cx,
                                 int cy,
                                 int radius,
                                 double radiusSq,
                                 double sliceStart,
                                 double sliceEnd,
                                 @NotNull RandomSource rng,
                                 boolean primary) {
        try {
            boolean[][] candidate = newMask(pixels.length);

            double mid = (sliceStart + sliceEnd) * 0.5;
            double angle = mid + (rng.nextDouble() - 0.5) * (sliceEnd - sliceStart) * (primary ? 0.18 : 0.25);

            double handleStartR = radius * (primary ? 0.34 : 0.40);
            double handleEndR = radius * (primary ? 0.61 : 0.64);

            Vec2 hs = polar(cx, cy, angle, handleStartR);
            Vec2 he = polar(cx, cy, angle, handleEndR);

            int handleThickness = Math.max(primary ? 2 : 1, (int) Math.round(radius * (primary ? 0.034 : 0.025)));
            stampLine(candidate,
                    (int) Math.round(hs.x),
                    (int) Math.round(hs.y),
                    (int) Math.round(he.x),
                    (int) Math.round(he.y),
                    handleThickness,
                    cx,
                    cy,
                    radiusSq);

            int pommelR = Math.max(1, (int) Math.round(radius * (primary ? 0.028 : 0.022)));
            stampDisc(candidate, (int) Math.round(hs.x), (int) Math.round(hs.y), pommelR, cx, cy, radiusSq);

            double ballAngle = angle + (rng.nextDouble() - 0.5) * Math.toRadians(primary ? 8.0 : 12.0);
            double ballR = radius * (primary ? 0.83 : 0.78);
            Vec2 ball = polar(cx, cy, ballAngle, ballR);

            Vec2 chainStart = lerp(hs, he, 0.95);
            int linkCount = (primary ? 4 : 3) + rng.nextInt(2);
            Vec2 prev = chainStart;

            for (int i = 0; i < linkCount; i++) {
                double t = (i + 1.0) / (linkCount + 1.0);
                Vec2 link = lerp(chainStart, ball, t);
                int linkOuter = Math.max(1, (int) Math.round(radius * (primary ? 0.020 : 0.016)));
                int linkInner = Math.max(0, linkOuter - 1);
                stampRing(candidate,
                        (int) Math.round(link.x),
                        (int) Math.round(link.y),
                        linkInner,
                        linkOuter,
                        cx,
                        cy,
                        radiusSq);
                stampLine(candidate,
                        (int) Math.round(prev.x),
                        (int) Math.round(prev.y),
                        (int) Math.round(link.x),
                        (int) Math.round(link.y),
                        1,
                        cx,
                        cy,
                        radiusSq);
                prev = link;
            }

            int ballRadius = Math.max(2, (int) Math.round(radius * (primary ? 0.055 : 0.045)));
            stampDisc(candidate, (int) Math.round(ball.x), (int) Math.round(ball.y), ballRadius, cx, cy, radiusSq);

            int spikeCount = (primary ? 6 : 5) + rng.nextInt(2);
            for (int i = 0; i < spikeCount; i++) {
                double sa = (Math.PI * 2.0 * i) / spikeCount + (rng.nextDouble() - 0.5) * Math.toRadians(10.0);
                int sx = (int) Math.round(ball.x + Math.cos(sa) * (ballRadius * 0.7));
                int sy = (int) Math.round(ball.y + Math.sin(sa) * (ballRadius * 0.7));
                int tx = (int) Math.round(ball.x + Math.cos(sa) * (ballRadius + radius * (primary ? 0.050 : 0.035)));
                int ty = (int) Math.round(ball.y + Math.sin(sa) * (ballRadius + radius * (primary ? 0.050 : 0.035)));
                stampLine(candidate, sx, sy, tx, ty, 1, cx, cy, radiusSq);
            }

            if (primary) {
                commitMandatory(pixels, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + 10, radius / 2));
            } else {
                commitCandidate(pixels, candidate, SECONDARY_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS, radius / 3));
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveFlailMotif failed", t);
        }
    }

    private void carveHeraldicShieldPrimary(boolean[][] pixels,
                                            int cx,
                                            int cy,
                                            int radius,
                                            double radiusSq,
                                            double sliceStart,
                                            double sliceEnd,
                                            @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);

            double mid = (sliceStart + sliceEnd) * 0.5;
            double angle = mid + (rng.nextDouble() - 0.5) * (sliceEnd - sliceStart) * 0.10;

            double centerR = radius * (MID_BAND_MIN + 0.08 + rng.nextDouble() * 0.06);
            int scx = (int) Math.round(cx + Math.cos(angle) * centerR);
            int scy = (int) Math.round(cy + Math.sin(angle) * centerR);

            double ux = Math.cos(angle);
            double uy = Math.sin(angle);
            double tx = -uy;
            double ty = ux;

            double halfW = radius * (0.11 + rng.nextDouble() * 0.02);
            double topLift = radius * (0.10 + rng.nextDouble() * 0.02);
            double lower = radius * (0.16 + rng.nextDouble() * 0.02);

            int topLeftX = (int) Math.round(scx - ux * topLift - tx * halfW);
            int topLeftY = (int) Math.round(scy - uy * topLift - ty * halfW);
            int topRightX = (int) Math.round(scx - ux * topLift + tx * halfW);
            int topRightY = (int) Math.round(scy - uy * topLift + ty * halfW);
            int rightMidX = (int) Math.round(scx + ux * (lower * 0.32) + tx * (halfW * 0.85));
            int rightMidY = (int) Math.round(scy + uy * (lower * 0.32) + ty * (halfW * 0.85));
            int tipX = (int) Math.round(scx + ux * lower);
            int tipY = (int) Math.round(scy + uy * lower);
            int leftMidX = (int) Math.round(scx + ux * (lower * 0.32) - tx * (halfW * 0.85));
            int leftMidY = (int) Math.round(scy + uy * (lower * 0.32) - ty * (halfW * 0.85));

            int[] px = {topLeftX, topRightX, rightMidX, tipX, leftMidX};
            int[] py = {topLeftY, topRightY, rightMidY, tipY, leftMidY};
            stampPolygon(candidate, px, py, 5, cx, cy, radiusSq);

            int crestStyle = rng.nextInt(3);
            if (crestStyle == 0) {
                Vec2 topCenter = new Vec2((topLeftX + topRightX) * 0.5, (topLeftY + topRightY) * 0.5);
                Vec2 leftShoulder = new Vec2((topLeftX + leftMidX) * 0.5, (topLeftY + leftMidY) * 0.5);
                Vec2 rightShoulder = new Vec2((topRightX + rightMidX) * 0.5, (topRightY + rightMidY) * 0.5);
                Vec2 low = new Vec2((leftMidX + rightMidX + tipX) / 3.0, (leftMidY + rightMidY + tipY) / 3.0);

                int[] cxp = {
                        (int) Math.round(leftShoulder.x),
                        (int) Math.round(topCenter.x),
                        (int) Math.round(rightShoulder.x),
                        (int) Math.round((rightShoulder.x + low.x) * 0.5),
                        (int) Math.round(low.x),
                        (int) Math.round((leftShoulder.x + low.x) * 0.5)
                };
                int[] cyp = {
                        (int) Math.round(leftShoulder.y),
                        (int) Math.round(topCenter.y + radius * 0.01),
                        (int) Math.round(rightShoulder.y),
                        (int) Math.round((rightShoulder.y + low.y) * 0.5),
                        (int) Math.round(low.y),
                        (int) Math.round((leftShoulder.y + low.y) * 0.5)
                };
                stampPolygon(candidate, cxp, cyp, 6, cx, cy, radiusSq);
            } else if (crestStyle == 1) {
                int coreX = (int) Math.round((topLeftX + topRightX + tipX) / 3.0);
                int coreY = (int) Math.round((topLeftY + topRightY + tipY) / 3.0);
                int arm = Math.max(2, (int) Math.round(radius * 0.045));
                int thick = Math.max(1, (int) Math.round(radius * 0.020));
                stampLine(candidate, coreX, coreY - arm, coreX, coreY + arm, thick, cx, cy, radiusSq);
                stampLine(candidate, coreX - arm, coreY, coreX + arm, coreY, thick, cx, cy, radiusSq);
            } else {
                int coreX = (int) Math.round((topLeftX + topRightX + tipX) / 3.0);
                int coreY = (int) Math.round((topLeftY + topRightY + tipY) / 3.0);
                int h = Math.max(5, (int) Math.round(radius * 0.11));
                stampLine(candidate, coreX, coreY - h / 2, coreX, coreY + h / 2, 1, cx, cy, radiusSq);
                stampLine(candidate, coreX - h / 4, coreY + h / 4, coreX + h / 4, coreY + h / 4, 1, cx, cy, radiusSq);
                stampDisc(candidate, coreX, coreY + h / 2 + 1, Math.max(1, radius / 52), cx, cy, radiusSq);
            }

            int bossR = Math.max(1, (int) Math.round(radius * 0.018));
            stampDisc(candidate, (int) Math.round((topLeftX + topRightX) * 0.5), (int) Math.round((topLeftY + topRightY) * 0.5), bossR, cx, cy, radiusSq);

            commitMandatory(pixels, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + 10, radius / 2));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveHeraldicShieldPrimary failed", t);
        }
    }

    private void carveCrestCrownPrimary(boolean[][] pixels,
                                        int cx,
                                        int cy,
                                        int radius,
                                        double radiusSq,
                                        double sliceStart,
                                        double sliceEnd,
                                        @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);

            double mid = (sliceStart + sliceEnd) * 0.5;
            double angle = mid + (rng.nextDouble() - 0.5) * (sliceEnd - sliceStart) * 0.12;
            double baseR = radius * (OUTER_BAND_MIN - 0.03 + rng.nextDouble() * 0.04);

            int ccx = (int) Math.round(cx + Math.cos(angle) * baseR);
            int ccy = (int) Math.round(cy + Math.sin(angle) * baseR);

            double ux = Math.cos(angle);
            double uy = Math.sin(angle);
            double tx = -uy;
            double ty = ux;

            double baseHalf = radius * (0.085 + rng.nextDouble() * 0.016);
            double baseDepth = radius * (0.035 + rng.nextDouble() * 0.010);

            int p1x = (int) Math.round(ccx - tx * baseHalf - ux * baseDepth);
            int p1y = (int) Math.round(ccy - ty * baseHalf - uy * baseDepth);
            int p2x = (int) Math.round(ccx + tx * baseHalf - ux * baseDepth);
            int p2y = (int) Math.round(ccy + ty * baseHalf - uy * baseDepth);
            int p3x = (int) Math.round(ccx + tx * baseHalf + ux * baseDepth);
            int p3y = (int) Math.round(ccy + ty * baseHalf + uy * baseDepth);
            int p4x = (int) Math.round(ccx - tx * baseHalf + ux * baseDepth);
            int p4y = (int) Math.round(ccy - ty * baseHalf + uy * baseDepth);
            stampPolygon(candidate, new int[]{p1x, p2x, p3x, p4x}, new int[]{p1y, p2y, p3y, p4y}, 4, cx, cy, radiusSq);

            double spikeHeight = radius * (0.070 + rng.nextDouble() * 0.018);
            for (int i = -1; i <= 1; i++) {
                double offset = i * baseHalf * 0.62;
                double bx = ccx + tx * offset - ux * baseDepth;
                double by = ccy + ty * offset - uy * baseDepth;
                double blx = bx - tx * (baseHalf * 0.18);
                double bly = by - ty * (baseHalf * 0.18);
                double brx = bx + tx * (baseHalf * 0.18);
                double bry = by + ty * (baseHalf * 0.18);
                double tpx = bx + ux * spikeHeight;
                double tpy = by + uy * spikeHeight;

                stampPolygon(candidate,
                        new int[]{(int) Math.round(blx), (int) Math.round(brx), (int) Math.round(tpx)},
                        new int[]{(int) Math.round(bly), (int) Math.round(bry), (int) Math.round(tpy)},
                        3,
                        cx,
                        cy,
                        radiusSq);
            }

            int tabLen = (int) Math.round(radius * 0.045);
            stampLine(candidate, p1x, p1y, (int) Math.round(p1x - ux * tabLen), (int) Math.round(p1y - uy * tabLen), 1, cx, cy, radiusSq);
            stampLine(candidate, p2x, p2y, (int) Math.round(p2x - ux * tabLen), (int) Math.round(p2y - uy * tabLen), 1, cx, cy, radiusSq);

            commitMandatory(pixels, candidate, MAJOR_OVERLAP_STRICT, MAJOR_OVERLAP_RELAXED, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS + 8, radius / 2));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveCrestCrownPrimary failed", t);
        }
    }

    private void carveBannerFiller(boolean[][] pixels,
                                   int cx,
                                   int cy,
                                   int radius,
                                   double radiusSq,
                                   double sliceStart,
                                   double sliceEnd,
                                   @NotNull RandomSource rng,
                                   int passIndex) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            double span = sliceEnd - sliceStart;

            double a0 = sliceStart + span * (0.08 + rng.nextDouble() * 0.28);
            double a3 = sliceStart + span * (0.62 + rng.nextDouble() * 0.28);
            if (a3 <= a0) {
                return;
            }

            double r0 = radius * (MID_BAND_MIN + 0.03 + rng.nextDouble() * 0.05);
            double r3 = radius * (OUTER_BAND_MIN - 0.04 + rng.nextDouble() * 0.05);
            double r1 = radius * (MID_BAND_MAX - 0.06 + rng.nextDouble() * 0.06);
            double r2 = radius * (OUTER_BAND_MIN - 0.02 + rng.nextDouble() * 0.04);

            double skew = (rng.nextBoolean() ? 1.0 : -1.0) * span * (0.08 + rng.nextDouble() * 0.12);
            double a1 = a0 + skew;
            double a2 = a3 - skew;

            Vec2 p0 = polar(cx, cy, a0, r0);
            Vec2 p1 = polar(cx, cy, a1, r1);
            Vec2 p2 = polar(cx, cy, a2, r2);
            Vec2 p3 = polar(cx, cy, a3, r3);

            int thickness = Math.max(1, (int) Math.round(radius * 0.018));
            int segments = 18;
            Vec2 prev = p0;
            for (int i = 1; i <= segments; i++) {
                double t = i / (double) segments;
                Vec2 cur = cubicBezier(p0, p1, p2, p3, t);
                stampLine(candidate,
                        (int) Math.round(prev.x),
                        (int) Math.round(prev.y),
                        (int) Math.round(cur.x),
                        (int) Math.round(cur.y),
                        thickness,
                        cx,
                        cy,
                        radiusSq);
                prev = cur;
            }

            Vec2 end = p3;
            Vec2 beforeEnd = cubicBezier(p0, p1, p2, p3, 0.92);
            double vx = end.x - beforeEnd.x;
            double vy = end.y - beforeEnd.y;
            double len = Math.max(1e-3, Math.sqrt(vx * vx + vy * vy));
            vx /= len;
            vy /= len;
            double px = -vy;
            double py = vx;
            double pennantLen = radius * 0.045;
            double pennantHalf = radius * 0.020;

            int ax = (int) Math.round(end.x);
            int ay = (int) Math.round(end.y);
            int bx = (int) Math.round(end.x - vx * pennantLen + px * pennantHalf);
            int by = (int) Math.round(end.y - vy * pennantLen + py * pennantHalf);
            int cxp = (int) Math.round(end.x - vx * pennantLen - px * pennantHalf);
            int cyp = (int) Math.round(end.y - vy * pennantLen - py * pennantHalf);
            stampPolygon(candidate, new int[]{ax, bx, cxp}, new int[]{ay, by, cyp}, 3, cx, cy, radiusSq);

            commitCandidate(pixels, candidate, FILLER_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 6, 10));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveBannerFiller failed", t);
        }
    }

    private void carveEdgeStuds(boolean[][] pixels,
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
            int studCount = 5 + rng.nextInt(3);
            for (int i = 0; i < studCount; i++) {
                double t = (i + 0.5) / studCount;
                double a = sliceStart + span * t + (rng.nextDouble() - 0.5) * span * 0.08;
                double r = radius * (OUTER_BAND_MAX - 0.02 + rng.nextDouble() * 0.02);
                int sx = (int) Math.round(cx + Math.cos(a) * r);
                int sy = (int) Math.round(cy + Math.sin(a) * r);
                int sr = Math.max(1, radius / 46);
                stampDisc(candidate, sx, sy, sr, cx, cy, radiusSq);
                stampRing(candidate, sx, sy, sr + 1, sr + 2, cx, cy, radiusSq);
                int spikeLen = Math.max(1, (int) Math.round(radius * 0.018));
                stampLine(candidate, sx - spikeLen, sy, sx + spikeLen, sy, 1, cx, cy, radiusSq);
                stampLine(candidate, sx, sy - spikeLen, sx, sy + spikeLen, 1, cx, cy, radiusSq);
            }

            commitCandidate(pixels, candidate, DETAIL_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 8, 8));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveEdgeStuds failed", t);
        }
    }

    private void carveMiniShieldFiller(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       double sliceStart,
                                       double sliceEnd,
                                       @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            double mid = (sliceStart + sliceEnd) * 0.5;
            double angle = mid + (rng.nextDouble() - 0.5) * (sliceEnd - sliceStart) * 0.14;

            double centerR = radius * (MID_BAND_MAX - 0.07 + rng.nextDouble() * 0.07);
            int scx = (int) Math.round(cx + Math.cos(angle) * centerR);
            int scy = (int) Math.round(cy + Math.sin(angle) * centerR);

            double ux = Math.cos(angle);
            double uy = Math.sin(angle);
            double tx = -uy;
            double ty = ux;

            double halfW = radius * (0.050 + rng.nextDouble() * 0.014);
            double topLift = radius * (0.050 + rng.nextDouble() * 0.014);
            double lower = radius * (0.082 + rng.nextDouble() * 0.018);

            int topLeftX = (int) Math.round(scx - ux * topLift - tx * halfW);
            int topLeftY = (int) Math.round(scy - uy * topLift - ty * halfW);
            int topRightX = (int) Math.round(scx - ux * topLift + tx * halfW);
            int topRightY = (int) Math.round(scy - uy * topLift + ty * halfW);
            int rightMidX = (int) Math.round(scx + ux * (lower * 0.32) + tx * (halfW * 0.82));
            int rightMidY = (int) Math.round(scy + uy * (lower * 0.32) + ty * (halfW * 0.82));
            int tipX = (int) Math.round(scx + ux * lower);
            int tipY = (int) Math.round(scy + uy * lower);
            int leftMidX = (int) Math.round(scx + ux * (lower * 0.32) - tx * (halfW * 0.82));
            int leftMidY = (int) Math.round(scy + uy * (lower * 0.32) - ty * (halfW * 0.82));
            stampPolygon(candidate,
                    new int[]{topLeftX, topRightX, rightMidX, tipX, leftMidX},
                    new int[]{topLeftY, topRightY, rightMidY, tipY, leftMidY},
                    5,
                    cx,
                    cy,
                    radiusSq);

            int centerX = (topLeftX + topRightX + tipX) / 3;
            int centerY = (topLeftY + topRightY + tipY) / 3;
            int axisHalf = Math.max(2, (int) Math.round(radius * 0.030));
            stampLine(candidate, centerX - axisHalf, centerY, centerX + axisHalf, centerY, 1, cx, cy, radiusSq);
            stampLine(candidate, centerX, centerY - axisHalf, centerX, centerY + axisHalf, 1, cx, cy, radiusSq);
            stampDisc(candidate, centerX, centerY, Math.max(1, (int) Math.round(radius * 0.016)), cx, cy, radiusSq);

            commitCandidate(pixels, candidate, FILLER_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 4, 11));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveMiniShieldFiller failed", t);
        }
    }

    private void carveMiniCrossFiller(boolean[][] pixels,
                                      int cx,
                                      int cy,
                                      int radius,
                                      double radiusSq,
                                      double sliceStart,
                                      double sliceEnd,
                                      @NotNull RandomSource rng) {
        try {
            boolean[][] candidate = newMask(pixels.length);
            double mid = (sliceStart + sliceEnd) * 0.5;
            double angle = mid + (rng.nextDouble() - 0.5) * (sliceEnd - sliceStart) * 0.16;

            double centerR = radius * (MID_BAND_MIN + 0.06 + rng.nextDouble() * 0.10);
            int centerX = (int) Math.round(cx + Math.cos(angle) * centerR);
            int centerY = (int) Math.round(cy + Math.sin(angle) * centerR);

            double ux = Math.cos(angle);
            double uy = Math.sin(angle);
            double tx = -uy;
            double ty = ux;

            double armLong = radius * (0.075 + rng.nextDouble() * 0.015);
            double armShort = radius * (0.052 + rng.nextDouble() * 0.012);
            int thick = Math.max(1, (int) Math.round(radius * 0.018));

            int innerX = (int) Math.round(centerX - ux * (armLong * 0.5));
            int innerY = (int) Math.round(centerY - uy * (armLong * 0.5));
            int outerX = (int) Math.round(centerX + ux * armLong);
            int outerY = (int) Math.round(centerY + uy * armLong);
            stampLine(candidate, innerX, innerY, outerX, outerY, thick, cx, cy, radiusSq);

            int leftX = (int) Math.round(centerX + tx * armShort);
            int leftY = (int) Math.round(centerY + ty * armShort);
            int rightX = (int) Math.round(centerX - tx * armShort);
            int rightY = (int) Math.round(centerY - ty * armShort);
            stampLine(candidate, leftX, leftY, rightX, rightY, thick, cx, cy, radiusSq);

            stampDisc(candidate, centerX, centerY, Math.max(1, thick), cx, cy, radiusSq);
            commitCandidate(pixels, candidate, FILLER_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 6, 10));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveMiniCrossFiller failed", t);
        }
    }

    private void carveChainLinkFiller(boolean[][] pixels,
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
            int links = 2 + rng.nextInt(3);
            Vec2 prev = null;
            for (int i = 0; i < links; i++) {
                double t = (i + 0.5) / links;
                double a = sliceStart + span * t + (rng.nextDouble() - 0.5) * span * 0.10;
                double r = radius * (OUTER_BAND_MIN - 0.03 + rng.nextDouble() * 0.10);
                Vec2 link = polar(cx, cy, a, r);
                int outer = Math.max(1, (int) Math.round(radius * 0.020));
                int inner = Math.max(0, outer - 1);
                int lx = (int) Math.round(link.x);
                int ly = (int) Math.round(link.y);
                stampRing(candidate, lx, ly, inner, outer, cx, cy, radiusSq);
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
                prev = link;
            }
            commitCandidate(pixels, candidate, DETAIL_OVERLAP, Math.max(DEFAULT_MIN_CANDIDATE_PIXELS - 8, 8));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetMedieval3] carveChainLinkFiller failed", t);
        }
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
            LOG.error("[SealSigilShapeSetMedieval3] commitCandidate failed", t);
            return false;
        }
    }

    private static boolean[][] newMask(int size) {
        return new boolean[Math.max(0, size)][Math.max(0, size)];
    }

    private boolean insideCircle(int x, int y, int cx, int cy, double radiusSq) {
        int dx = x - cx;
        int dy = y - cy;
        return dx * dx + dy * dy <= radiusSq;
    }

    private void stampFlaredTip(boolean[][] pixels,
                                int tipX,
                                int tipY,
                                double dirX,
                                double dirY,
                                double perpX,
                                double perpY,
                                double tipLen,
                                double baseHalf,
                                double mouthHalf,
                                int cx,
                                int cy,
                                double radiusSq) {
        double bx = tipX - dirX * tipLen;
        double by = tipY - dirY * tipLen;

        int[] xs = {
                (int) Math.round(tipX + perpX * mouthHalf),
                (int) Math.round(tipX - perpX * mouthHalf),
                (int) Math.round(bx - perpX * baseHalf),
                (int) Math.round(bx + perpX * baseHalf)
        };
        int[] ys = {
                (int) Math.round(tipY + perpY * mouthHalf),
                (int) Math.round(tipY - perpY * mouthHalf),
                (int) Math.round(by - perpY * baseHalf),
                (int) Math.round(by + perpY * baseHalf)
        };

        stampPolygon(pixels, xs, ys, 4, cx, cy, radiusSq);
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

    private static Vec2 polar(int cx, int cy, double angle, double radius) {
        return new Vec2(cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius);
    }

    private static Vec2 lerp(Vec2 a, Vec2 b, double t) {
        return new Vec2(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t
        );
    }

    private static Vec2 cubicBezier(Vec2 p0, Vec2 p1, Vec2 p2, Vec2 p3, double t) {
        double u = 1.0 - t;
        double uu = u * u;
        double tt = t * t;
        double uuu = uu * u;
        double ttt = tt * t;

        double x = uuu * p0.x
                + 3.0 * uu * t * p1.x
                + 3.0 * u * tt * p2.x
                + ttt * p3.x;
        double y = uuu * p0.y
                + 3.0 * uu * t * p1.y
                + 3.0 * u * tt * p2.y
                + ttt * p3.y;
        return new Vec2(x, y);
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
