package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Shape set 5 - "Fantasy 2"
 *
 * Design goals:
 * - Extremely high variety per seed.
 * - Dense layered motifs with a quasi-tileable structure inside each slice.
 * - Fantasy themes: fire, magic circles, orbs, dragons, arcane runes, void cracks.
 */
public final class SealSigilShapeSetHighFantasy5 implements SealSigilShapeSet {

    private static final Logger LOG = LogUtils.getLogger();

    private static final double SLICE_ANGULAR_MARGIN = Math.toRadians(2.8);
    private static final int BASE_MIN_PIXELS = 10;

    private static final double OVERLAP_MAJOR = 0.34;
    private static final double OVERLAP_MEDIUM = 0.44;
    private static final double OVERLAP_FINE = 0.52;

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

            int intensity = 4 + rng.nextInt(5); // 4..8 layered passes, less blob-like

            carveBorderMirrorBands(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng);
            carveRunicLattice(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, intensity);

            if (rng.nextFloat() < 0.70f) {
                carveConcentricArcana(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, intensity);
            }
            if (rng.nextFloat() < 0.62f) {
                carveOrbitalChains(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, intensity);
            }
            if (rng.nextFloat() < 0.55f) {
                carveFireWeaves(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, intensity);
            }
            if (rng.nextFloat() < 0.55f) {
                carveDragonSigils(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, intensity);
            }
            if (rng.nextFloat() < 0.58f) {
                carveChaosStars(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, intensity);
            }
            if (rng.nextFloat() < 0.50f) {
                carveVoidCracks(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, intensity);
            }
            if (rng.nextFloat() < 0.72f) {
                carveMicroGlyphField(pixels, cx, cy, radius, radiusSq, sliceStart, sliceEnd, rng, intensity);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] applyShapesInSlice failed", t);
        }
    }

    private void carveBorderMirrorBands(boolean[][] pixels,
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

            double[] edgeAngles = {
                    sliceStart + span * (0.035 + rng.nextDouble() * 0.020),
                    sliceEnd - span * (0.035 + rng.nextDouble() * 0.020)
            };

            for (double a : edgeAngles) {
                double ux = Math.cos(a);
                double uy = Math.sin(a);
                double tx = -uy;
                double ty = ux;

                int knots = 4 + rng.nextInt(3);
                for (int i = 0; i < knots; i++) {
                    double rn = 0.26 + i * 0.15 + rng.nextDouble() * 0.03;
                    Vec2 p = polar(cx, cy, a, radius * rn);
                    int k = Math.max(2, (int) Math.round(radius * 0.018));

                    int[] xs = {
                            (int) Math.round(p.x),
                            (int) Math.round(p.x + tx * k),
                            (int) Math.round(p.x),
                            (int) Math.round(p.x - tx * k)
                    };
                    int[] ys = {
                            (int) Math.round(p.y - k),
                            (int) Math.round(p.y),
                            (int) Math.round(p.y + k),
                            (int) Math.round(p.y)
                    };
                    stampPolygon(candidate, xs, ys, 4, cx, cy, radiusSq);

                    int lineLen = Math.max(2, radius / 24);
                    stampLine(candidate,
                            (int) Math.round(p.x - ux * lineLen),
                            (int) Math.round(p.y - uy * lineLen),
                            (int) Math.round(p.x + ux * lineLen),
                            (int) Math.round(p.y + uy * lineLen),
                            1,
                            cx,
                            cy,
                            radiusSq);
                }
            }

            commitCandidate(pixels, candidate, OVERLAP_MEDIUM, Math.max(BASE_MIN_PIXELS + 10, radius / 3));
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] carveBorderMirrorBands failed", t);
        }
    }

    private void carveRunicLattice(boolean[][] pixels,
                                   int cx,
                                   int cy,
                                   int radius,
                                   double radiusSq,
                                   double sliceStart,
                                   double sliceEnd,
                                   @NotNull RandomSource rng,
                                   int intensity) {
        try {
            int lanes = 2 + rng.nextInt(3) + (intensity / 6);
            lanes = Math.min(lanes, 5);
            double span = sliceEnd - sliceStart;

            for (int lane = 0; lane < lanes; lane++) {
                boolean[][] candidate = newMask(pixels.length);
                double rn = 0.24 + lane * (0.62 / Math.max(1, lanes - 1)) + (rng.nextDouble() - 0.5) * 0.03;
                rn = clamp(rn, 0.22, 0.88);
                double ringR = radius * rn;

                int runeCount = 4 + rng.nextInt(4);
                double prevA = 0.0;
                boolean hasPrev = false;
                for (int i = 0; i < runeCount; i++) {
                    double t = (i + 0.5) / runeCount;
                    double angle = sliceStart + span * t + (rng.nextDouble() - 0.5) * span * 0.07;

                    Vec2 p = polar(cx, cy, angle, ringR);
                    int gx = (int) Math.round(p.x);
                    int gy = (int) Math.round(p.y);

                    double scale = radius * (0.026 + rng.nextDouble() * 0.030);
                    int glyphType = rng.nextInt(8);
                    stampRuneGlyph(candidate, gx, gy, angle, scale, glyphType, cx, cy, radiusSq, rng);

                    if (hasPrev && rng.nextFloat() < 0.86f) {
                        int thick = Math.max(1, (int) Math.round(radius * 0.009));
                        stampArcSegment(candidate, cx, cy, ringR, prevA, angle, thick, 5 + rng.nextInt(8), cx, cy, radiusSq);
                    }
                    prevA = angle;
                    hasPrev = true;
                }

                if (rng.nextFloat() < 0.74f) {
                    int in = Math.max(1, (int) Math.round(radius * 0.010));
                    int out = in + Math.max(1, (int) Math.round(radius * 0.013));
                    stampRing(candidate, cx, cy, Math.max(0, (int) Math.round(ringR) - in), (int) Math.round(ringR) + out, cx, cy, radiusSq);
                }

                int minPixels = Math.max(BASE_MIN_PIXELS + 12, radius / 3);
                commitCandidate(pixels, candidate, OVERLAP_MEDIUM, minPixels);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] carveRunicLattice failed", t);
        }
    }

    private void carveConcentricArcana(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       double sliceStart,
                                       double sliceEnd,
                                       @NotNull RandomSource rng,
                                       int intensity) {
        try {
            int ringPasses = 1 + rng.nextInt(2) + (intensity > 6 ? 1 : 0);
            ringPasses = Math.min(ringPasses, 3);
            double span = sliceEnd - sliceStart;

            for (int pass = 0; pass < ringPasses; pass++) {
                boolean[][] candidate = newMask(pixels.length);
                double rn = 0.30 + pass * 0.12 + rng.nextDouble() * 0.07;
                rn = clamp(rn, 0.26, 0.90);
                double ringR = radius * rn;

                int segmentCount = 5 + rng.nextInt(6);
                for (int seg = 0; seg < segmentCount; seg++) {
                    double t0 = seg / (double) segmentCount;
                    double t1 = (seg + 1.0) / segmentCount;
                    if (rng.nextFloat() < 0.24f) {
                        continue;
                    }

                    double a0 = sliceStart + span * t0 + (rng.nextDouble() - 0.5) * span * 0.03;
                    double a1 = sliceStart + span * t1 + (rng.nextDouble() - 0.5) * span * 0.03;
                    int thick = Math.max(1, (int) Math.round(radius * (0.010 + rng.nextDouble() * 0.010)));
                    stampArcSegment(candidate, cx, cy, ringR, a0, a1, thick, 4 + rng.nextInt(7), cx, cy, radiusSq);

                    if (rng.nextFloat() < 0.60f) {
                        Vec2 m = polar(cx, cy, (a0 + a1) * 0.5, ringR + radius * (0.014 + rng.nextDouble() * 0.020));
                        int glyph = rng.nextInt(8);
                        stampRuneGlyph(candidate,
                                (int) Math.round(m.x),
                                (int) Math.round(m.y),
                                (a0 + a1) * 0.5,
                                radius * (0.020 + rng.nextDouble() * 0.020),
                                glyph,
                                cx,
                                cy,
                                radiusSq,
                                rng);
                    }
                }

                int minPixels = Math.max(BASE_MIN_PIXELS + 14, radius / 2);
                commitCandidate(pixels, candidate, OVERLAP_MEDIUM, minPixels);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] carveConcentricArcana failed", t);
        }
    }

    private void carveOrbitalChains(boolean[][] pixels,
                                    int cx,
                                    int cy,
                                    int radius,
                                    double radiusSq,
                                    double sliceStart,
                                    double sliceEnd,
                                    @NotNull RandomSource rng,
                                    int intensity) {
        try {
            int chains = 1 + rng.nextInt(3) + (intensity / 8);
            chains = Math.min(chains, 4);

            for (int c = 0; c < chains; c++) {
                boolean[][] candidate = newMask(pixels.length);

                double span = sliceEnd - sliceStart;
                double a0 = sliceStart + span * (0.05 + rng.nextDouble() * 0.20);
                double a3 = sliceStart + span * (0.75 + rng.nextDouble() * 0.20);
                if (a3 <= a0) {
                    continue;
                }

                double r0 = radius * (0.26 + rng.nextDouble() * 0.10);
                double r3 = radius * (0.72 + rng.nextDouble() * 0.18);

                double bend = (rng.nextBoolean() ? 1.0 : -1.0) * span * (0.10 + rng.nextDouble() * 0.20);
                double a1 = a0 + bend;
                double a2 = a3 - bend;

                double r1 = radius * (0.40 + rng.nextDouble() * 0.18);
                double r2 = radius * (0.58 + rng.nextDouble() * 0.22);

                Vec2 p0 = polar(cx, cy, a0, r0);
                Vec2 p1 = polar(cx, cy, a1, r1);
                Vec2 p2 = polar(cx, cy, a2, r2);
                Vec2 p3 = polar(cx, cy, a3, r3);

                int segments = 16 + rng.nextInt(14);
                int thick = Math.max(1, (int) Math.round(radius * (0.010 + rng.nextDouble() * 0.010)));
                stampBezierTube(candidate, p0, p1, p2, p3, segments, thick, cx, cy, radiusSq);

                int orbCount = 3 + rng.nextInt(6);
                Vec2 prev = null;
                for (int i = 0; i < orbCount; i++) {
                    double t = (i + 0.5) / orbCount;
                    Vec2 orb = cubicBezier(p0, p1, p2, p3, t);
                    int ox = (int) Math.round(orb.x);
                    int oy = (int) Math.round(orb.y);
                    int or = Math.max(1, (int) Math.round(radius * (0.012 + rng.nextDouble() * 0.012)));

                    stampDisc(candidate, ox, oy, or, cx, cy, radiusSq);
                    stampRing(candidate, ox, oy, Math.max(0, or - 1), or + 1, cx, cy, radiusSq);

                    if (prev != null && rng.nextFloat() < 0.72f) {
                        stampLine(candidate,
                                (int) Math.round(prev.x),
                                (int) Math.round(prev.y),
                                ox,
                                oy,
                                1,
                                cx,
                                cy,
                                radiusSq);
                    }
                    prev = orb;
                }

                int minPixels = Math.max(BASE_MIN_PIXELS + 10, radius / 3);
                commitCandidate(pixels, candidate, OVERLAP_MAJOR, minPixels);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] carveOrbitalChains failed", t);
        }
    }

    private void carveFireWeaves(boolean[][] pixels,
                                 int cx,
                                 int cy,
                                 int radius,
                                 double radiusSq,
                                 double sliceStart,
                                 double sliceEnd,
                                 @NotNull RandomSource rng,
                                 int intensity) {
        try {
            int ribbons = 1 + rng.nextInt(3) + (intensity > 6 ? 1 : 0);
            ribbons = Math.min(ribbons, 4);
            double span = sliceEnd - sliceStart;

            for (int r = 0; r < ribbons; r++) {
                boolean[][] candidate = newMask(pixels.length);

                double baseT = (r + 0.5) / ribbons;
                double a0 = sliceStart + span * clamp(baseT - 0.18, 0.03, 0.97) + (rng.nextDouble() - 0.5) * span * 0.08;
                double a3 = sliceStart + span * clamp(baseT + 0.18, 0.03, 0.97) + (rng.nextDouble() - 0.5) * span * 0.08;
                if (a3 <= a0) {
                    double tmp = a0;
                    a0 = a3;
                    a3 = tmp;
                }

                double r0 = radius * (0.28 + rng.nextDouble() * 0.10);
                double r3 = radius * (0.76 + rng.nextDouble() * 0.14);

                double swirl = (rng.nextBoolean() ? 1.0 : -1.0) * span * (0.12 + rng.nextDouble() * 0.20);
                double a1 = a0 + swirl;
                double a2 = a3 - swirl;

                double r1 = radius * (0.42 + rng.nextDouble() * 0.20);
                double r2 = radius * (0.58 + rng.nextDouble() * 0.20);

                Vec2 p0 = polar(cx, cy, a0, r0);
                Vec2 p1 = polar(cx, cy, a1, r1);
                Vec2 p2 = polar(cx, cy, a2, r2);
                Vec2 p3 = polar(cx, cy, a3, r3);

                int thick = Math.max(1, (int) Math.round(radius * (0.012 + rng.nextDouble() * 0.012)));
                int segments = 18 + rng.nextInt(16);
                stampBezierTube(candidate, p0, p1, p2, p3, segments, thick, cx, cy, radiusSq);

                int tongues = 2 + rng.nextInt(3);
                for (int i = 0; i < tongues; i++) {
                    double t = 0.35 + (i + rng.nextDouble()) / (tongues + 0.5) * 0.62;
                    t = clamp(t, 0.22, 0.95);

                    Vec2 cur = cubicBezier(p0, p1, p2, p3, t);
                    Vec2 prev = cubicBezier(p0, p1, p2, p3, Math.max(0.0, t - 0.04));
                    Vec2 next = cubicBezier(p0, p1, p2, p3, Math.min(1.0, t + 0.04));

                    double vx = next.x - prev.x;
                    double vy = next.y - prev.y;
                    double len = Math.max(1e-4, Math.sqrt(vx * vx + vy * vy));
                    vx /= len;
                    vy /= len;

                    double px = -vy;
                    double py = vx;

                    double tongueLen = radius * (0.040 + rng.nextDouble() * 0.045);
                    double halfBase = radius * (0.014 + rng.nextDouble() * 0.012);

                    Vec2 b1 = new Vec2(cur.x + px * halfBase, cur.y + py * halfBase);
                    Vec2 b2 = new Vec2(cur.x - px * halfBase, cur.y - py * halfBase);
                    Vec2 tip = new Vec2(cur.x + vx * tongueLen + px * (rng.nextDouble() - 0.5) * halfBase,
                            cur.y + vy * tongueLen + py * (rng.nextDouble() - 0.5) * halfBase);

                    stampPolygon(candidate, vecXs(b1, b2, tip), vecYs(b1, b2, tip), 3, cx, cy, radiusSq);
                }

                int minPixels = Math.max(BASE_MIN_PIXELS + 10, radius / 3);
                commitCandidate(pixels, candidate, OVERLAP_MAJOR, minPixels);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] carveFireWeaves failed", t);
        }
    }

    private void carveDragonSigils(boolean[][] pixels,
                                   int cx,
                                   int cy,
                                   int radius,
                                   double radiusSq,
                                   double sliceStart,
                                   double sliceEnd,
                                   @NotNull RandomSource rng,
                                   int intensity) {
        try {
            int dragons = 1 + rng.nextInt(2) + (intensity > 7 ? 1 : 0);
            dragons = Math.min(dragons, 3);
            double span = sliceEnd - sliceStart;

            for (int d = 0; d < dragons; d++) {
                boolean[][] candidate = newMask(pixels.length);

                double a0 = sliceStart + span * (0.08 + rng.nextDouble() * 0.25);
                double a3 = sliceStart + span * (0.68 + rng.nextDouble() * 0.24);
                if (a3 <= a0) {
                    continue;
                }

                double r0 = radius * (0.34 + rng.nextDouble() * 0.12);
                double r3 = radius * (0.72 + rng.nextDouble() * 0.18);

                double arcBend = (rng.nextBoolean() ? 1.0 : -1.0) * span * (0.08 + rng.nextDouble() * 0.22);
                double a1 = a0 + arcBend;
                double a2 = a3 - arcBend;

                double r1 = radius * (0.42 + rng.nextDouble() * 0.18);
                double r2 = radius * (0.60 + rng.nextDouble() * 0.20);

                Vec2 p0 = polar(cx, cy, a0, r0);
                Vec2 p1 = polar(cx, cy, a1, r1);
                Vec2 p2 = polar(cx, cy, a2, r2);
                Vec2 p3 = polar(cx, cy, a3, r3);

                int thick = Math.max(1, (int) Math.round(radius * (0.014 + rng.nextDouble() * 0.014)));
                int samples = 20 + rng.nextInt(14);
                Vec2 prev = p0;
                for (int i = 1; i <= samples; i++) {
                    double t = i / (double) samples;
                    Vec2 cur = cubicBezier(p0, p1, p2, p3, t);
                    stampLine(candidate,
                            (int) Math.round(prev.x),
                            (int) Math.round(prev.y),
                            (int) Math.round(cur.x),
                            (int) Math.round(cur.y),
                            thick,
                            cx,
                            cy,
                            radiusSq);

                    if (i % 3 == 0 && rng.nextFloat() < 0.78f) {
                        Vec2 pPrev = cubicBezier(p0, p1, p2, p3, Math.max(0.0, t - 0.05));
                        double vx = cur.x - pPrev.x;
                        double vy = cur.y - pPrev.y;
                        double len = Math.max(1e-4, Math.sqrt(vx * vx + vy * vy));
                        vx /= len;
                        vy /= len;
                        double px = -vy;
                        double py = vx;

                        double side = rng.nextBoolean() ? 1.0 : -1.0;
                        double spikeLen = radius * (0.030 + rng.nextDouble() * 0.032);
                        double spikeBase = radius * (0.010 + rng.nextDouble() * 0.010);

                        Vec2 b1 = new Vec2(cur.x + px * side * spikeBase, cur.y + py * side * spikeBase);
                        Vec2 b2 = new Vec2(cur.x + px * side * spikeBase * 0.2 - vx * spikeBase * 0.6,
                                cur.y + py * side * spikeBase * 0.2 - vy * spikeBase * 0.6);
                        Vec2 tip = new Vec2(cur.x + px * side * spikeLen, cur.y + py * side * spikeLen);
                        stampPolygon(candidate, vecXs(b1, b2, tip), vecYs(b1, b2, tip), 3, cx, cy, radiusSq);
                    }

                    prev = cur;
                }

                stampDragonHead(candidate, p3, p2, radius, cx, cy, radiusSq, rng);

                if (rng.nextFloat() < 0.62f) {
                    Vec2 mouthFrom = toward(p3, p2, 0.08);
                    double breathAngle = Math.atan2(p3.y - p2.y, p3.x - p2.x) + (rng.nextDouble() - 0.5) * Math.toRadians(30.0);
                    double br = radius * (0.16 + rng.nextDouble() * 0.08);
                    Vec2 mouthTo = new Vec2(mouthFrom.x + Math.cos(breathAngle) * br, mouthFrom.y + Math.sin(breathAngle) * br);
                    stampLine(candidate,
                            (int) Math.round(mouthFrom.x),
                            (int) Math.round(mouthFrom.y),
                            (int) Math.round(mouthTo.x),
                            (int) Math.round(mouthTo.y),
                            1,
                            cx,
                            cy,
                            radiusSq);
                    stampDisc(candidate, (int) Math.round(mouthTo.x), (int) Math.round(mouthTo.y), Math.max(1, radius / 46), cx, cy, radiusSq);
                }

                int minPixels = Math.max(BASE_MIN_PIXELS + 14, radius / 3);
                commitCandidate(pixels, candidate, OVERLAP_MAJOR, minPixels);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] carveDragonSigils failed", t);
        }
    }

    private void carveChaosStars(boolean[][] pixels,
                                 int cx,
                                 int cy,
                                 int radius,
                                 double radiusSq,
                                 double sliceStart,
                                 double sliceEnd,
                                 @NotNull RandomSource rng,
                                 int intensity) {
        try {
            int stars = 2 + rng.nextInt(3) + (intensity / 9);
            stars = Math.min(stars, 5);
            double span = sliceEnd - sliceStart;

            for (int s = 0; s < stars; s++) {
                boolean[][] candidate = newMask(pixels.length);

                double a = sliceStart + span * rng.nextDouble();
                double rn = 0.26 + rng.nextDouble() * 0.64;
                Vec2 c = polar(cx, cy, a, radius * rn);

                int points = 5 + rng.nextInt(5); // 5..9
                double outer = radius * (0.040 + rng.nextDouble() * 0.050);
                double inner = outer * (0.35 + rng.nextDouble() * 0.32);
                double rot = rng.nextDouble() * Math.PI * 2.0;
                drawStar(candidate, c, inner, outer, points, rot, cx, cy, radiusSq);

                if (rng.nextFloat() < 0.78f) {
                    drawStar(candidate, c,
                            inner * (0.52 + rng.nextDouble() * 0.18),
                            outer * (0.50 + rng.nextDouble() * 0.18),
                            points + (rng.nextBoolean() ? 1 : 0),
                            rot + Math.PI / points,
                            cx,
                            cy,
                            radiusSq);
                }

                if (rng.nextFloat() < 0.56f) {
                    int rr0 = Math.max(1, (int) Math.round(inner * 0.6));
                    int rr1 = Math.max(rr0 + 1, (int) Math.round(inner * 1.05));
                    stampRing(candidate, (int) Math.round(c.x), (int) Math.round(c.y), rr0, rr1, cx, cy, radiusSq);
                }

                int minPixels = Math.max(BASE_MIN_PIXELS + 6, radius / 4);
                commitCandidate(pixels, candidate, OVERLAP_MEDIUM, minPixels);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] carveChaosStars failed", t);
        }
    }

    private void carveVoidCracks(boolean[][] pixels,
                                 int cx,
                                 int cy,
                                 int radius,
                                 double radiusSq,
                                 double sliceStart,
                                 double sliceEnd,
                                 @NotNull RandomSource rng,
                                 int intensity) {
        try {
            int cracks = 1 + rng.nextInt(2) + (intensity > 6 ? 1 : 0);
            cracks = Math.min(cracks, 3);
            double span = sliceEnd - sliceStart;

            for (int c = 0; c < cracks; c++) {
                boolean[][] candidate = newMask(pixels.length);

                int nodes = 5 + rng.nextInt(5);
                Vec2[] path = new Vec2[nodes];
                double startA = sliceStart + span * (0.08 + rng.nextDouble() * 0.22);
                double endA = sliceStart + span * (0.68 + rng.nextDouble() * 0.28);
                double startR = radius * (0.24 + rng.nextDouble() * 0.12);
                double endR = radius * (0.76 + rng.nextDouble() * 0.18);

                for (int i = 0; i < nodes; i++) {
                    double t = i / (double) (nodes - 1);
                    double a = startA + (endA - startA) * t + (rng.nextDouble() - 0.5) * span * 0.12;
                    double r = startR + (endR - startR) * t + (rng.nextDouble() - 0.5) * radius * 0.07;
                    path[i] = polar(cx, cy, a, r);
                }

                int crackThickness = Math.max(1, (int) Math.round(radius * (0.010 + rng.nextDouble() * 0.006)));
                for (int i = 0; i < nodes - 1; i++) {
                    stampLine(candidate,
                            (int) Math.round(path[i].x),
                            (int) Math.round(path[i].y),
                            (int) Math.round(path[i + 1].x),
                            (int) Math.round(path[i + 1].y),
                            crackThickness,
                            cx,
                            cy,
                            radiusSq);

                    if (i > 0 && i < nodes - 2 && rng.nextFloat() < 0.66f) {
                        Vec2 p = path[i];
                        Vec2 n = path[i + 1];
                        double vx = n.x - p.x;
                        double vy = n.y - p.y;
                        double len = Math.max(1e-4, Math.sqrt(vx * vx + vy * vy));
                        vx /= len;
                        vy /= len;
                        double px = -vy;
                        double py = vx;

                        double side = rng.nextBoolean() ? 1.0 : -1.0;
                        double bl = radius * (0.05 + rng.nextDouble() * 0.06);
                        Vec2 bEnd = new Vec2(p.x + px * side * bl + vx * bl * (0.15 + rng.nextDouble() * 0.20),
                                p.y + py * side * bl + vy * bl * (0.15 + rng.nextDouble() * 0.20));

                        stampLine(candidate,
                                (int) Math.round(p.x),
                                (int) Math.round(p.y),
                                (int) Math.round(bEnd.x),
                                (int) Math.round(bEnd.y),
                                1,
                                cx,
                                cy,
                                radiusSq);

                        if (rng.nextFloat() < 0.62f) {
                            stampDisc(candidate, (int) Math.round(bEnd.x), (int) Math.round(bEnd.y), Math.max(1, radius / 52), cx, cy, radiusSq);
                        }
                    }
                }

                int minPixels = Math.max(BASE_MIN_PIXELS + 8, radius / 4);
                commitCandidate(pixels, candidate, OVERLAP_MAJOR, minPixels);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] carveVoidCracks failed", t);
        }
    }

    private void carveMicroGlyphField(boolean[][] pixels,
                                      int cx,
                                      int cy,
                                      int radius,
                                      double radiusSq,
                                      double sliceStart,
                                      double sliceEnd,
                                      @NotNull RandomSource rng,
                                      int intensity) {
        try {
            int passes = 1 + rng.nextInt(2);
            for (int pass = 0; pass < passes; pass++) {
                boolean[][] candidate = newMask(pixels.length);
                int count = 6 + rng.nextInt(7) + (intensity / 2);
                double span = sliceEnd - sliceStart;

                for (int i = 0; i < count; i++) {
                    double a = sliceStart + span * rng.nextDouble();
                    double r = radius * (0.23 + rng.nextDouble() * 0.68);
                    Vec2 p = polar(cx, cy, a, r);

                    int glyph = rng.nextInt(8);
                    double scale = radius * (0.012 + rng.nextDouble() * 0.020);
                    stampRuneGlyph(candidate,
                            (int) Math.round(p.x),
                            (int) Math.round(p.y),
                            a,
                            scale,
                            glyph,
                            cx,
                            cy,
                            radiusSq,
                            rng);

                    if (rng.nextFloat() < 0.26f) {
                        int rr = Math.max(1, (int) Math.round(scale * (0.8 + rng.nextDouble() * 0.7)));
                        stampRing(candidate, (int) Math.round(p.x), (int) Math.round(p.y), Math.max(0, rr - 1), rr + 1, cx, cy, radiusSq);
                    }
                }

                int minPixels = Math.max(BASE_MIN_PIXELS + 6, radius / 5);
                commitCandidate(pixels, candidate, OVERLAP_FINE, minPixels);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilShapeSetHighFantasy5] carveMicroGlyphField failed", t);
        }
    }

    private void stampRuneGlyph(boolean[][] pixels,
                                int gx,
                                int gy,
                                double angle,
                                double scale,
                                int glyphType,
                                int cx,
                                int cy,
                                double radiusSq,
                                @NotNull RandomSource rng) {
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);
        double tx = -uy;
        double ty = ux;

        double s = Math.max(2.0, scale);
        int ix = (int) Math.round(gx);
        int iy = (int) Math.round(gy);

        switch (glyphType) {
            case 0 -> {
                int d = (int) Math.round(s * 0.8);
                stampPolygon(pixels,
                        new int[]{ix, ix + d, ix, ix - d},
                        new int[]{iy - d, iy, iy + d, iy},
                        4,
                        cx,
                        cy,
                        radiusSq);
            }
            case 1 -> {
                int len = (int) Math.round(s * 1.4);
                stampLine(pixels, ix - len, iy, ix + len, iy, 1, cx, cy, radiusSq);
                stampLine(pixels, ix, iy - len, ix, iy + len, 1, cx, cy, radiusSq);
            }
            case 2 -> {
                int len = (int) Math.round(s * 1.3);
                stampLine(pixels,
                        (int) Math.round(ix - tx * len),
                        (int) Math.round(iy - ty * len),
                        (int) Math.round(ix + tx * len),
                        (int) Math.round(iy + ty * len),
                        1,
                        cx,
                        cy,
                        radiusSq);
                stampLine(pixels,
                        ix,
                        iy,
                        (int) Math.round(ix + ux * (len * 1.2)),
                        (int) Math.round(iy + uy * (len * 1.2)),
                        1,
                        cx,
                        cy,
                        radiusSq);
            }
            case 3 -> {
                int outer = Math.max(1, (int) Math.round(s));
                stampRing(pixels, ix, iy, Math.max(0, outer - 1), outer + 1, cx, cy, radiusSq);
                stampDisc(pixels,
                        (int) Math.round(ix + tx * outer * 0.7),
                        (int) Math.round(iy + ty * outer * 0.7),
                        Math.max(1, outer / 2),
                        cx,
                        cy,
                        radiusSq);
            }
            case 4 -> {
                int len = (int) Math.round(s * 1.2);
                Vec2 a = new Vec2(ix + tx * len, iy + ty * len);
                Vec2 b = new Vec2(ix - tx * len, iy - ty * len);
                Vec2 c = new Vec2(ix + ux * len * 1.2, iy + uy * len * 1.2);
                stampPolygon(pixels, vecXs(a, b, c), vecYs(a, b, c), 3, cx, cy, radiusSq);
            }
            case 5 -> {
                int len = (int) Math.round(s * 1.2);
                stampLine(pixels, ix - len, iy + len, ix + len, iy - len, 1, cx, cy, radiusSq);
                stampLine(pixels, ix - len, iy - len, ix + len, iy + len, 1, cx, cy, radiusSq);
                stampDisc(pixels, ix, iy, Math.max(1, len / 3), cx, cy, radiusSq);
            }
            case 6 -> {
                int len = (int) Math.round(s * (1.0 + rng.nextDouble() * 0.6));
                stampLine(pixels,
                        ix,
                        iy,
                        (int) Math.round(ix + ux * len),
                        (int) Math.round(iy + uy * len),
                        1,
                        cx,
                        cy,
                        radiusSq);
                stampLine(pixels,
                        (int) Math.round(ix + ux * len * 0.25),
                        (int) Math.round(iy + uy * len * 0.25),
                        (int) Math.round(ix + tx * len * 0.65),
                        (int) Math.round(iy + ty * len * 0.65),
                        1,
                        cx,
                        cy,
                        radiusSq);
                stampLine(pixels,
                        (int) Math.round(ix + ux * len * 0.25),
                        (int) Math.round(iy + uy * len * 0.25),
                        (int) Math.round(ix - tx * len * 0.65),
                        (int) Math.round(iy - ty * len * 0.65),
                        1,
                        cx,
                        cy,
                        radiusSq);
            }
            default -> {
                int outer = Math.max(1, (int) Math.round(s));
                stampDisc(pixels, ix, iy, outer, cx, cy, radiusSq);
                if (rng.nextFloat() < 0.5f) {
                    stampRing(pixels, ix, iy, Math.max(0, outer - 1), outer + 1, cx, cy, radiusSq);
                }
            }
        }
    }

    private void stampDragonHead(boolean[][] pixels,
                                 Vec2 head,
                                 Vec2 neck,
                                 int radius,
                                 int cx,
                                 int cy,
                                 double radiusSq,
                                 @NotNull RandomSource rng) {
        double vx = head.x - neck.x;
        double vy = head.y - neck.y;
        double len = Math.max(1e-4, Math.sqrt(vx * vx + vy * vy));
        vx /= len;
        vy /= len;
        double px = -vy;
        double py = vx;

        double headLen = radius * (0.060 + rng.nextDouble() * 0.030);
        double jawHalf = radius * (0.020 + rng.nextDouble() * 0.018);

        Vec2 tip = new Vec2(head.x + vx * headLen, head.y + vy * headLen);
        Vec2 b1 = new Vec2(head.x + px * jawHalf, head.y + py * jawHalf);
        Vec2 b2 = new Vec2(head.x - px * jawHalf, head.y - py * jawHalf);
        stampPolygon(pixels, vecXs(b1, b2, tip), vecYs(b1, b2, tip), 3, cx, cy, radiusSq);

        if (rng.nextFloat() < 0.82f) {
            double hornLen = radius * (0.026 + rng.nextDouble() * 0.030);
            Vec2 h1 = new Vec2(head.x + px * (jawHalf * 0.5), head.y + py * (jawHalf * 0.5));
            Vec2 h2 = new Vec2(head.x - px * (jawHalf * 0.5), head.y - py * (jawHalf * 0.5));
            Vec2 t1 = new Vec2(h1.x - px * hornLen + vx * hornLen * 0.25, h1.y - py * hornLen + vy * hornLen * 0.25);
            Vec2 t2 = new Vec2(h2.x + px * hornLen + vx * hornLen * 0.25, h2.y + py * hornLen + vy * hornLen * 0.25);
            stampLine(pixels, (int) Math.round(h1.x), (int) Math.round(h1.y), (int) Math.round(t1.x), (int) Math.round(t1.y), 1, cx, cy, radiusSq);
            stampLine(pixels, (int) Math.round(h2.x), (int) Math.round(h2.y), (int) Math.round(t2.x), (int) Math.round(t2.y), 1, cx, cy, radiusSq);
        }

        Vec2 eye = new Vec2(head.x + px * jawHalf * 0.35 + vx * headLen * 0.20,
                head.y + py * jawHalf * 0.35 + vy * headLen * 0.20);
        stampDisc(pixels, (int) Math.round(eye.x), (int) Math.round(eye.y), Math.max(1, radius / 58), cx, cy, radiusSq);
    }

    private void drawStar(boolean[][] pixels,
                          Vec2 center,
                          double innerR,
                          double outerR,
                          int points,
                          double rotation,
                          int cx,
                          int cy,
                          double radiusSq) {
        if (points < 3) {
            return;
        }
        int count = points * 2;
        int[] xs = new int[count];
        int[] ys = new int[count];

        for (int i = 0; i < count; i++) {
            double a = rotation + (Math.PI * 2.0 * i) / count;
            double r = (i % 2 == 0) ? outerR : innerR;
            xs[i] = (int) Math.round(center.x + Math.cos(a) * r);
            ys[i] = (int) Math.round(center.y + Math.sin(a) * r);
        }
        stampPolygon(pixels, xs, ys, count, cx, cy, radiusSq);
    }

    private void stampArcSegment(boolean[][] pixels,
                                 int arcCx,
                                 int arcCy,
                                 double arcR,
                                 double angleStart,
                                 double angleEnd,
                                 int thickness,
                                 int segments,
                                 int cx,
                                 int cy,
                                 double radiusSq) {
        int safeSegments = Math.max(2, segments);
        double a0 = angleStart;
        double a1 = angleEnd;
        if (a1 < a0) {
            double tmp = a0;
            a0 = a1;
            a1 = tmp;
        }

        Vec2 prev = new Vec2(arcCx + Math.cos(a0) * arcR, arcCy + Math.sin(a0) * arcR);
        for (int i = 1; i <= safeSegments; i++) {
            double t = i / (double) safeSegments;
            double a = a0 + (a1 - a0) * t;
            Vec2 cur = new Vec2(arcCx + Math.cos(a) * arcR, arcCy + Math.sin(a) * arcR);
            stampLine(pixels,
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
    }

    private void stampBezierTube(boolean[][] pixels,
                                 Vec2 p0,
                                 Vec2 p1,
                                 Vec2 p2,
                                 Vec2 p3,
                                 int segments,
                                 int thickness,
                                 int cx,
                                 int cy,
                                 double radiusSq) {
        int safeSegments = Math.max(4, segments);
        Vec2 prev = p0;
        for (int i = 1; i <= safeSegments; i++) {
            double t = i / (double) safeSegments;
            Vec2 cur = cubicBezier(p0, p1, p2, p3, t);
            stampLine(pixels,
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
            LOG.error("[SealSigilShapeSetHighFantasy5] commitCandidate failed", t);
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

    private static Vec2 polar(int cx, int cy, double angle, double radius) {
        return new Vec2(cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
