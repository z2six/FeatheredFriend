// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilGenerator.java
package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilGenerator.java
 *
 * Sigil generator for FeatheredFriend seal stamps.
 *
 * High-level design:
 *  - Sigil fits inside a circle of radius DEFAULT_RADIUS.
 *  - Multiple layers:
 *      * Outer boundary ring.
 *      * Central motif (several possible variants).
 *      * One or more “binding” rings (full or partial arcs).
 *      * Primary glyph ring (runes around a circle).
 *      * Density passes: extra inner glyph ring, radial rays, webs, beads, nets.
 *  - After all geometry is drawn, the pattern INSIDE the seal circle is inverted:
 *      * true -> false, false -> true.
 *      * This yields a negative-style sigil where lines become voids and voids become wax.
 *
 * All randomness is driven by a deterministic RandomSource created from a 64-bit seed
 * derived from (playerUUID + secretString[0..128]).
 */
public final class SealSigilGenerator {

    private static final Logger LOG = LogUtils.getLogger();

    public static final int DEFAULT_RADIUS = 64;
    public static final int DEFAULT_SIZE = DEFAULT_RADIUS * 2 + 1;
    private static final int MAX_SECRET_LENGTH = 128;

    // High-level style knobs (tweakable later)
    private static final int MIN_GLYPHS = 5;
    private static final int MAX_GLYPHS = 12;

    private static final int MIN_RING_COUNT = 0;
    private static final int MAX_RING_COUNT = 2;

    private static final int OUTER_RING_THICKNESS_MIN = 1;
    private static final int OUTER_RING_THICKNESS_MAX = 2;

    // Density control
    private static final double TARGET_MIN_COVERAGE = 0.22; // coverage target before inversion
    private static final int MAX_DENSITY_PASSES = 4;

    private SealSigilGenerator() {
        // no instances
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static @NotNull SigilPattern generateForPlayer(@NotNull UUID playerUuid,
                                                          @NotNull String secret) {
        long seed = computeSeed(playerUuid, secret);
        return generateFromSeed(seed, DEFAULT_RADIUS);
    }

    public static long computeSeed(@NotNull UUID playerUuid, @NotNull String secret) {
        String trimmedSecret = secret;
        if (trimmedSecret.length() > MAX_SECRET_LENGTH) {
            trimmedSecret = trimmedSecret.substring(0, MAX_SECRET_LENGTH);
        }

        String input = playerUuid.toString() + "|" + trimmedSecret;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.wrap(hash);
            long seed = buf.getLong();
            LOG.debug("[SealSigilGenerator] computeSeed: uuid={} secretPreview='{}' len={} -> seed={}",
                    playerUuid, safeSecretPreview(trimmedSecret), trimmedSecret.length(), seed);
            return seed;
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] computeSeed failed, falling back to String.hashCode()", t);
            return (playerUuid.toString() + "|" + trimmedSecret).hashCode();
        }
    }

    public static @NotNull SigilPattern generateFromSeed(long seed, int radius) {
        if (radius < 16) {
            radius = 16;
        }
        int size = radius * 2 + 1;
        int cx = radius;
        int cy = radius;
        double radiusSq = radius * radius;

        boolean[][] pixels = new boolean[size][size];
        RandomSource rng = RandomSource.create(seed);

        double coverage = 0.0;

        try {
            int outerThickness = OUTER_RING_THICKNESS_MIN +
                    rng.nextInt(OUTER_RING_THICKNESS_MAX - OUTER_RING_THICKNESS_MIN + 1);
            drawCircleRing(pixels, cx, cy, radius - 1, outerThickness);

            drawCentralMotif(pixels, cx, cy, radius, rng);

            int ringCount = MIN_RING_COUNT + rng.nextInt(MAX_RING_COUNT - MIN_RING_COUNT + 1);
            for (int i = 0; i < ringCount; i++) {
                double rNorm = 0.25 + rng.nextDouble() * 0.55; // 0.25–0.80
                int r = (int) Math.round(radius * rNorm);
                int thickness = 1 + rng.nextInt(2);
                boolean full = rng.nextFloat() < 0.6f;
                if (full) {
                    drawCircleRing(pixels, cx, cy, r, thickness);
                } else {
                    drawPartialRing(pixels, cx, cy, r, thickness, rng);
                }
            }

            placeGlyphs(pixels, cx, cy, radius, radiusSq, rng);

            int passes = 0;
            coverage = computeCoverage(pixels, cx, cy, radius);
            LOG.debug("[SealSigilGenerator] coverage after main pass: {}", coverage);
            while (coverage < TARGET_MIN_COVERAGE && passes < MAX_DENSITY_PASSES) {
                addDensityPass(pixels, cx, cy, radius, radiusSq, rng, passes);
                coverage = computeCoverage(pixels, cx, cy, radius);
                LOG.debug("[SealSigilGenerator] coverage after density pass {}: {}",
                        passes + 1, coverage);
                passes++;
            }

            invertInsideCircle(pixels, cx, cy, radius);
            coverage = computeCoverage(pixels, cx, cy, radius);
            LOG.debug("[SealSigilGenerator] generateFromSeed: seed={} radius={} size={} finalCoverage={} (inverted)",
                    seed, radius, size, coverage);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] generateFromSeed failed (seed={}, radius={}), using fallback dot",
                    seed, radius, t);
            pixels[cy][cx] = true;
        }

        return new SigilPattern(size, pixels, seed);
    }

    // -------------------------------------------------------------------------
    // Central motif
    // -------------------------------------------------------------------------

    private static void drawCentralMotif(boolean[][] pixels,
                                         int cx,
                                         int cy,
                                         int radius,
                                         @NotNull RandomSource rng) {
        try {
            double motifRadiusNorm = 0.14 + rng.nextDouble() * 0.18; // 0.14–0.32
            int motifRadius = Math.max(3, (int) Math.round(radius * motifRadiusNorm));

            int type = rng.nextInt(9); // 0..8
            switch (type) {
                case 0 -> {
                    drawCircleRing(pixels, cx, cy, motifRadius, 1);
                    drawLine(pixels, cx - motifRadius, cy, cx + motifRadius, cy, 2);
                    drawLine(pixels, cx, cy - motifRadius, cx, cy + motifRadius, 2);
                }
                case 1 -> {
                    drawDiamond(pixels, cx, cy, motifRadius, 2);
                    drawLine(pixels, cx, cy - motifRadius, cx, cy + motifRadius, 2);
                }
                case 2 -> {
                    drawDisc(pixels, cx, cy, motifRadius);
                }
                case 3 -> {
                    drawSquare(pixels, cx, cy, motifRadius, 2);
                    drawLine(pixels, cx - motifRadius, cy, cx, cy - motifRadius, 1);
                    drawLine(pixels, cx, cy - motifRadius, cx + motifRadius, cy, 1);
                    drawLine(pixels, cx + motifRadius, cy, cx, cy + motifRadius, 1);
                    drawLine(pixels, cx, cy + motifRadius, cx - motifRadius, cy, 1);
                }
                case 4 -> {
                    drawDisc(pixels, cx, cy, motifRadius);
                    if (motifRadius > 3) {
                        drawCircleRing(pixels, cx, cy, motifRadius - 2, 1);
                    }
                }
                case 5 -> {
                    drawCentralStar(pixels, cx, cy, motifRadius, rng);
                }
                case 6 -> {
                    drawCentralTriad(pixels, cx, cy, motifRadius, rng);
                }
                case 7 -> {
                    drawCentralSpiralRune(pixels, cx, cy, motifRadius, rng);
                }
                case 8 -> {
                    drawCentralPetalCluster(pixels, cx, cy, motifRadius, rng);
                }
                default -> drawDisc(pixels, cx, cy, motifRadius);
            }

            LOG.debug("[SealSigilGenerator] drawCentralMotif: type={} motifRadius={}", type, motifRadius);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawCentralMotif failed", t);
        }
    }

    private static void drawCentralStar(boolean[][] pixels,
                                        int cx,
                                        int cy,
                                        int r,
                                        @NotNull RandomSource rng) {
        try {
            int points = 5 + rng.nextInt(3); // 5–7
            double innerFactor = 0.4 + rng.nextDouble() * 0.2; // 0.4–0.6
            int thickness = 1 + rng.nextInt(2);
            double rotation = rng.nextDouble() * Math.PI * 2.0;

            int[] xs = new int[points * 2];
            int[] ys = new int[points * 2];

            for (int i = 0; i < points * 2; i++) {
                double angle = rotation + (Math.PI * i) / points;
                double radius = (i % 2 == 0) ? r : r * innerFactor;
                xs[i] = cx + (int) Math.round(Math.cos(angle) * radius);
                ys[i] = cy + (int) Math.round(Math.sin(angle) * radius);
            }

            for (int i = 0; i < xs.length; i++) {
                int j = (i + 1) % xs.length;
                drawLine(pixels, xs[i], ys[i], xs[j], ys[j], thickness);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawCentralStar failed", t);
        }
    }

    private static void drawCentralTriad(boolean[][] pixels,
                                         int cx,
                                         int cy,
                                         int r,
                                         @NotNull RandomSource rng) {
        try {
            int innerR = Math.max(2, (int) (r * 0.5));
            drawCircleRing(pixels, cx, cy, innerR, 1);

            double baseAngle = rng.nextDouble() * Math.PI * 2.0;
            double petalR = r;
            int thickness = 1 + rng.nextInt(2);

            for (int i = 0; i < 3; i++) {
                double angle = baseAngle + i * (2.0 * Math.PI / 3.0);
                int px = cx + (int) Math.round(Math.cos(angle) * petalR);
                int py = cy + (int) Math.round(Math.sin(angle) * petalR);
                drawLine(pixels, cx, cy, px, py, thickness);

                int offsetPX = px + (int) Math.round(Math.cos(angle + Math.PI / 2.5) * (petalR * 0.3));
                int offsetPY = py + (int) Math.round(Math.sin(angle + Math.PI / 2.5) * (petalR * 0.3));
                drawLine(pixels, px, py, offsetPX, offsetPY, 1);

                int offsetNX = px + (int) Math.round(Math.cos(angle - Math.PI / 2.5) * (petalR * 0.3));
                int offsetNY = py + (int) Math.round(Math.sin(angle - Math.PI / 2.5) * (petalR * 0.3));
                drawLine(pixels, px, py, offsetNX, offsetNY, 1);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawCentralTriad failed", t);
        }
    }

    private static void drawCentralSpiralRune(boolean[][] pixels,
                                              int cx,
                                              int cy,
                                              int r,
                                              @NotNull RandomSource rng) {
        try {
            int steps = 14 + rng.nextInt(12); // 14–25
            double angleStep = (Math.PI * 2.0) / (steps + 3);
            double radiusStep = r / (double) (steps + 3);
            double sign = rng.nextBoolean() ? 1.0 : -1.0;
            int thickness = 1 + rng.nextInt(2);

            double angle = rng.nextDouble() * Math.PI * 2.0;
            double curR = 2.0;

            int lastX = cx;
            int lastY = cy;

            for (int i = 0; i < steps; i++) {
                angle += sign * angleStep;
                curR += radiusStep;
                int x = cx + (int) Math.round(Math.cos(angle) * curR);
                int y = cy + (int) Math.round(Math.sin(angle) * curR);
                drawLine(pixels, lastX, lastY, x, y, thickness);
                lastX = x;
                lastY = y;
            }

            if (rng.nextFloat() < 0.7f) {
                drawCircleRing(pixels, cx, cy, r, 1);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawCentralSpiralRune failed", t);
        }
    }

    private static void drawCentralPetalCluster(boolean[][] pixels,
                                                int cx,
                                                int cy,
                                                int r,
                                                @NotNull RandomSource rng) {
        try {
            int petals = 4 + rng.nextInt(5); // 4–8
            double innerR = Math.max(2.0, r * 0.4);
            double outerR = r;
            double rotation = rng.nextDouble() * Math.PI * 2.0;
            int thickness = 1 + rng.nextInt(2);

            for (int i = 0; i < petals; i++) {
                double angle = rotation + (2.0 * Math.PI * i) / petals;

                int xInner = cx + (int) Math.round(Math.cos(angle) * innerR);
                int yInner = cy + (int) Math.round(Math.sin(angle) * innerR);
                int xOuter = cx + (int) Math.round(Math.cos(angle) * outerR);
                int yOuter = cy + (int) Math.round(Math.sin(angle) * outerR);

                drawLine(pixels, xInner, yInner, xOuter, yOuter, thickness);

                double sideOffset = Math.PI / (petals + 2.0);
                int side1x = cx + (int) Math.round(Math.cos(angle + sideOffset) * outerR * 0.8);
                int side1y = cy + (int) Math.round(Math.sin(angle + sideOffset) * outerR * 0.8);
                int side2x = cx + (int) Math.round(Math.cos(angle - sideOffset) * outerR * 0.8);
                int side2y = cy + (int) Math.round(Math.sin(angle - sideOffset) * outerR * 0.8);

                drawLine(pixels, xOuter, yOuter, side1x, side1y, 1);
                drawLine(pixels, xOuter, yOuter, side2x, side2y, 1);
            }

            if (rng.nextFloat() < 0.9f) {
                drawDisc(pixels, cx, cy, (int) Math.max(2, innerR * 0.5));
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawCentralPetalCluster failed", t);
        }
    }

    private static void drawDiamond(boolean[][] pixels, int cx, int cy, int r, int thickness) {
        int x0 = cx;
        int y0 = cy - r;
        int x1 = cx + r;
        int y1 = cy;
        int x2 = cx;
        int y2 = cy + r;
        int x3 = cx - r;
        int y3 = cy;

        drawLine(pixels, x0, y0, x1, y1, thickness);
        drawLine(pixels, x1, y1, x2, y2, thickness);
        drawLine(pixels, x2, y2, x3, y3, thickness);
        drawLine(pixels, x3, y3, x0, y0, thickness);
    }

    private static void drawSquare(boolean[][] pixels, int cx, int cy, int r, int thickness) {
        int x0 = cx - r;
        int y0 = cy - r;
        int x1 = cx + r;
        int y1 = cy - r;
        int x2 = cx + r;
        int y2 = cy + r;
        int x3 = cx - r;
        int y3 = cy + r;

        drawLine(pixels, x0, y0, x1, y1, thickness);
        drawLine(pixels, x1, y1, x2, y2, thickness);
        drawLine(pixels, x2, y2, x3, y3, thickness);
        drawLine(pixels, x3, y3, x0, y0, thickness);
    }

    // -------------------------------------------------------------------------
    // Glyph placement
    // -------------------------------------------------------------------------

    private static void placeGlyphs(boolean[][] pixels,
                                    int cx,
                                    int cy,
                                    int radius,
                                    double radiusSq,
                                    @NotNull RandomSource rng) {
        try {
            int glyphCount = MIN_GLYPHS + rng.nextInt(MAX_GLYPHS - MIN_GLYPHS + 1);

            double baseRingNorm = 0.45 + rng.nextDouble() * 0.3; // 0.45–0.75
            double baseRingR = radius * baseRingNorm;

            double globalRotation = rng.nextDouble() * Math.PI * 2.0;

            for (int i = 0; i < glyphCount; i++) {
                double t = (i + 0.5) / glyphCount;
                double baseAngle = t * Math.PI * 2.0 + globalRotation;

                double jitter = (rng.nextDouble() - 0.5) * (Math.PI / glyphCount);
                double angle = baseAngle + jitter;

                double rOffsetNorm = (rng.nextDouble() - 0.5) * 0.15;
                double r = baseRingR * (1.0 + rOffsetNorm);
                if (r < radius * 0.3) r = radius * 0.3;
                if (r > radius * 0.9) r = radius * 0.9;

                int anchorX = cx + (int) Math.round(Math.cos(angle) * r);
                int anchorY = cy + (int) Math.round(Math.sin(angle) * r);

                if (!insideCircle(anchorX, anchorY, cx, cy, radiusSq)) {
                    continue;
                }

                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double rx = cos;
                double ry = sin;
                double tx = -sin;
                double ty = cos;

                int glyphType = rng.nextInt(10); // 0..9

                switch (glyphType) {
                    case 0 -> drawGlyph_Trident(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 1 -> drawGlyph_DiamondSpine(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 2 -> drawGlyph_BoxCross(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 3 -> drawGlyph_Hook(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 4 -> drawGlyph_Chevron(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 5 -> drawGlyph_PillarRune(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 6 -> drawGlyph_AngledRune(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 7 -> drawGlyph_OrbCluster(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 8 -> drawGlyph_TwinnedArc(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 9 -> drawGlyph_SigilStar(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    default -> drawGlyph_Trident(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                }
            }

            LOG.debug("[SealSigilGenerator] placeGlyphs: glyphCount={} baseRingNorm={}", glyphCount, baseRingNorm);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] placeGlyphs failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Individual glyph generators (existing)
    // -------------------------------------------------------------------------

    private static void drawGlyph_Trident(boolean[][] pixels,
                                          int ax,
                                          int ay,
                                          double rx,
                                          double ry,
                                          double tx,
                                          double ty,
                                          int radius,
                                          double radiusSq,
                                          @NotNull RandomSource rng) {
        try {
            double stemLen = 8 + rng.nextDouble() * 12; // 8–20
            int thickness = 2;

            double startOffset = -4.0;
            double endOffset = startOffset + stemLen;

            int startX = ax + (int) Math.round(rx * startOffset);
            int startY = ay + (int) Math.round(ry * startOffset);
            int endX = ax + (int) Math.round(rx * endOffset);
            int endY = ay + (int) Math.round(ry * endOffset);

            drawClippedLine(pixels, startX, startY, endX, endY, thickness, radius, radius, radiusSq);

            int tines = 2 + rng.nextInt(2);
            double tineLen = 5 + rng.nextDouble() * 7; // 5–12
            for (int i = 0; i < tines; i++) {
                double t = (i - (tines - 1) / 2.0) * 0.7;
                double dirX = rx + tx * t;
                double dirY = ry + ty * t;
                double norm = Math.sqrt(dirX * dirX + dirY * dirY);
                if (norm < 1e-3) norm = 1.0;
                dirX /= norm;
                dirY /= norm;

                int tineEndX = endX + (int) Math.round(dirX * tineLen);
                int tineEndY = endY + (int) Math.round(dirY * tineLen);

                drawClippedLine(pixels, endX, endY, tineEndX, tineEndY, thickness, radius, radius, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_Trident failed", t);
        }
    }

    private static void drawGlyph_DiamondSpine(boolean[][] pixels,
                                               int ax,
                                               int ay,
                                               double rx,
                                               double ry,
                                               double tx,
                                               double ty,
                                               int radius,
                                               double radiusSq,
                                               @NotNull RandomSource rng) {
        try {
            double size = 5 + rng.nextDouble() * 7; // 5–12
            int thickness = 2;

            double upX = ax;
            double upY = ay;

            int cx = (int) Math.round(upX);
            int cy = (int) Math.round(upY);

            int r = (int) Math.round(size);
            drawDiamond(pixels, cx, cy, r, thickness);

            double spineLen = 6 + rng.nextDouble() * 10;
            int innerX = cx + (int) Math.round(-rx * spineLen);
            int innerY = cy + (int) Math.round(-ry * spineLen);

            drawClippedLine(pixels, cx, cy, innerX, innerY, thickness, radius, radius, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_DiamondSpine failed", t);
        }
    }

    private static void drawGlyph_BoxCross(boolean[][] pixels,
                                           int ax,
                                           int ay,
                                           double rx,
                                           double ry,
                                           double tx,
                                           double ty,
                                           int radius,
                                           double radiusSq,
                                           @NotNull RandomSource rng) {
        try {
            double half = 4 + rng.nextDouble() * 5; // 4–9
            int thickness = 2;

            double tilt = (rng.nextDouble() - 0.5) * (Math.PI / 4.0);
            double cosT = Math.cos(tilt);
            double sinT = Math.sin(tilt);

            int[][] localCorners = {
                    {-1, -1},
                    {1, -1},
                    {1, 1},
                    {-1, 1}
            };

            int[] worldX = new int[4];
            int[] worldY = new int[4];

            for (int i = 0; i < 4; i++) {
                double lx = localCorners[i][0] * half;
                double ly = localCorners[i][1] * half;

                double rxLocal = lx * cosT - ly * sinT;
                double ryLocal = lx * sinT + ly * cosT;

                double wx = ax + rx * rxLocal + tx * ryLocal;
                double wy = ay + ry * rxLocal + ty * ryLocal;

                worldX[i] = (int) Math.round(wx);
                worldY[i] = (int) Math.round(wy);
            }

            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                drawClippedLine(pixels, worldX[i], worldY[i], worldX[j], worldY[j], thickness, radius, radius, radiusSq);
            }

            drawClippedLine(pixels, worldX[0], worldY[0], worldX[2], worldY[2], 1, radius, radius, radiusSq);
            drawClippedLine(pixels, worldX[1], worldY[1], worldX[3], worldY[3], 1, radius, radius, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_BoxCross failed", t);
        }
    }

    private static void drawGlyph_Hook(boolean[][] pixels,
                                       int ax,
                                       int ay,
                                       double rx,
                                       double ry,
                                       double tx,
                                       double ty,
                                       int radius,
                                       double radiusSq,
                                       @NotNull RandomSource rng) {
        try {
            int thickness = 2;

            double stemLen = 6 + rng.nextDouble() * 9; // 6–15
            int stemEndX = ax + (int) Math.round(rx * stemLen);
            int stemEndY = ay + (int) Math.round(ry * stemLen);

            drawClippedLine(pixels, ax, ay, stemEndX, stemEndY, thickness, radius, radius, radiusSq);

            int segments = 6 + rng.nextInt(5); // 6–10
            double curveRadius = 5 + rng.nextDouble() * 7; // 5–12
            double sign = rng.nextBoolean() ? 1.0 : -1.0;

            int lastX = stemEndX;
            int lastY = stemEndY;

            for (int i = 1; i <= segments; i++) {
                double t = i / (double) segments;
                double angle = sign * (Math.PI * 0.75) * t;
                double offsetX = -rx * curveRadius * t + tx * curveRadius * Math.sin(angle);
                double offsetY = -ry * curveRadius * t + ty * curveRadius * Math.sin(angle);

                int hx = stemEndX + (int) Math.round(offsetX);
                int hy = stemEndY + (int) Math.round(offsetY);

                if (!insideCircle(hx, hy, radius, radius, radiusSq)) {
                    break;
                }

                drawClippedLine(pixels, lastX, lastY, hx, hy, thickness, radius, radius, radiusSq);
                lastX = hx;
                lastY = hy;
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_Hook failed", t);
        }
    }

    private static void drawGlyph_Chevron(boolean[][] pixels,
                                          int ax,
                                          int ay,
                                          double rx,
                                          double ry,
                                          double tx,
                                          double ty,
                                          int radius,
                                          double radiusSq,
                                          @NotNull RandomSource rng) {
        try {
            int thickness = 2;
            double armLen = 6 + rng.nextDouble() * 9; // 6–15

            double baseOffset = -4.0;
            int baseX = ax + (int) Math.round(rx * baseOffset);
            int baseY = ay + (int) Math.round(ry * baseOffset);

            double spread = Math.toRadians(35 + rng.nextDouble() * 25); // 35–60 deg
            double cosS = Math.cos(spread / 2.0);
            double sinS = Math.sin(spread / 2.0);

            double dirLX = rx * cosS - tx * sinS;
            double dirLY = ry * cosS - ty * sinS;
            double dirRX = rx * cosS + tx * sinS;
            double dirRY = ry * cosS + ty * sinS;

            int tipLX = baseX + (int) Math.round(dirLX * armLen);
            int tipLY = baseY + (int) Math.round(dirLY * armLen);
            int tipRX = baseX + (int) Math.round(dirRX * armLen);
            int tipRY = baseY + (int) Math.round(dirRY * armLen);

            drawClippedLine(pixels, baseX, baseY, tipLX, tipLY, thickness, radius, radius, radiusSq);
            drawClippedLine(pixels, baseX, baseY, tipRX, tipRY, thickness, radius, radius, radiusSq);

            if (rng.nextFloat() < 0.8f) {
                drawClippedLine(pixels, tipLX, tipLY, tipRX, tipRY, 1, radius, radius, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_Chevron failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // New glyph generators
    // -------------------------------------------------------------------------

    private static void drawGlyph_PillarRune(boolean[][] pixels,
                                             int ax,
                                             int ay,
                                             double rx,
                                             double ry,
                                             double tx,
                                             double ty,
                                             int radius,
                                             double radiusSq,
                                             @NotNull RandomSource rng) {
        try {
            int thickness = 2;
            double height = 10 + rng.nextDouble() * 14; // 10–24

            int baseX = ax + (int) Math.round(-rx * 2.0);
            int baseY = ay + (int) Math.round(-ry * 2.0);

            int topX = baseX + (int) Math.round(ry * height * 0.2);
            int topY = baseY + (int) Math.round(-rx * height * 0.2);

            int bottomX = baseX + (int) Math.round(-ry * height * 0.2);
            int bottomY = baseY + (int) Math.round(rx * height * 0.2);

            int stemEndX = ax + (int) Math.round(rx * height);
            int stemEndY = ay + (int) Math.round(ry * height);

            drawClippedLine(pixels, topX, topY, bottomX, bottomY, thickness, radius, radius, radiusSq);
            drawClippedLine(pixels, baseX, baseY, stemEndX, stemEndY, thickness, radius, radius, radiusSq);

            if (rng.nextFloat() < 0.7f) {
                int midX = baseX + (int) Math.round(rx * (height * 0.5));
                int midY = baseY + (int) Math.round(ry * (height * 0.5));
                int crossOffset = 3 + rng.nextInt(4);
                int cx1 = midX + (int) Math.round(tx * crossOffset);
                int cy1 = midY + (int) Math.round(ty * crossOffset);
                int cx2 = midX - (int) Math.round(tx * crossOffset);
                int cy2 = midY - (int) Math.round(ty * crossOffset);

                drawClippedLine(pixels, cx1, cy1, cx2, cy2, 1, radius, radius, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_PillarRune failed", t);
        }
    }

    private static void drawGlyph_AngledRune(boolean[][] pixels,
                                             int ax,
                                             int ay,
                                             double rx,
                                             double ry,
                                             double tx,
                                             double ty,
                                             int radius,
                                             double radiusSq,
                                             @NotNull RandomSource rng) {
        try {
            int thickness = 2;
            double baseLen = 7 + rng.nextDouble() * 10; // 7–17
            double angleOffset = (rng.nextDouble() - 0.5) * (Math.PI / 3.0);
            double lengthFactor = 0.6 + rng.nextDouble() * 0.5; // 0.6–1.1

            int x0 = ax;
            int y0 = ay;

            int x1 = x0 + (int) Math.round(rx * baseLen);
            int y1 = y0 + (int) Math.round(ry * baseLen);

            double cosA = Math.cos(angleOffset);
            double sinA = Math.sin(angleOffset);
            double brx = rx * cosA - ry * sinA;
            double bry = rx * sinA + ry * cosA;

            int x2 = x1 + (int) Math.round(brx * baseLen * lengthFactor);
            int y2 = y1 + (int) Math.round(bry * baseLen * lengthFactor);

            drawClippedLine(pixels, x0, y0, x1, y1, thickness, radius, radius, radiusSq);
            drawClippedLine(pixels, x1, y1, x2, y2, thickness, radius, radius, radiusSq);

            if (rng.nextFloat() < 0.5f) {
                int capLen = 3 + rng.nextInt(5);
                int capX = x2 + (int) Math.round(tx * capLen);
                int capY = y2 + (int) Math.round(ty * capLen);
                drawClippedLine(pixels, x2, y2, capX, capY, 1, radius, radius, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_AngledRune failed", t);
        }
    }

    private static void drawGlyph_OrbCluster(boolean[][] pixels,
                                             int ax,
                                             int ay,
                                             double rx,
                                             double ry,
                                             double tx,
                                             double ty,
                                             int radius,
                                             double radiusSq,
                                             @NotNull RandomSource rng) {
        try {
            int orbs = 2 + rng.nextInt(3); // 2–4
            int baseR = 2 + rng.nextInt(3); // 2–4
            double spread = Math.toRadians(25 + rng.nextDouble() * 25); // 25–50 deg

            double startAngle = Math.atan2(ry, rx) - spread / 2.0;
            for (int i = 0; i < orbs; i++) {
                double t = orbs == 1 ? 0.5 : i / (double) (orbs - 1);
                double angle = startAngle + spread * t;
                double dist = 4 + rng.nextDouble() * 6; // 4–10

                int ox = ax + (int) Math.round(Math.cos(angle) * dist);
                int oy = ay + (int) Math.round(Math.sin(angle) * dist);

                if (!insideCircle(ox, oy, radius, radius, radiusSq)) {
                    continue;
                }

                drawDisc(pixels, ox, oy, baseR);

                if (rng.nextFloat() < 0.5f) {
                    drawCircleRing(pixels, ox, oy, baseR + 2, 1);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_OrbCluster failed", t);
        }
    }

    private static void drawGlyph_TwinnedArc(boolean[][] pixels,
                                             int ax,
                                             int ay,
                                             double rx,
                                             double ry,
                                             double tx,
                                             double ty,
                                             int radius,
                                             double radiusSq,
                                             @NotNull RandomSource rng) {
        try {
            int thickness = 1 + rng.nextInt(2);
            double innerR = 5 + rng.nextDouble() * 8; // 5–13
            double outerR = innerR + (2 + rng.nextDouble() * 4); // +2–6
            double arcAngle = Math.toRadians(40 + rng.nextDouble() * 50); // 40–90 deg
            double orientation = Math.atan2(ry, rx) + (rng.nextBoolean() ? Math.PI / 2.0 : -Math.PI / 2.0);

            drawArcSegment(pixels, ax, ay, innerR, orientation, arcAngle, thickness, radius, radius, radiusSq);
            drawArcSegment(pixels, ax, ay, outerR, orientation, arcAngle, thickness, radius, radius, radiusSq);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_TwinnedArc failed", t);
        }
    }

    private static void drawGlyph_SigilStar(boolean[][] pixels,
                                            int ax,
                                            int ay,
                                            double rx,
                                            double ry,
                                            double tx,
                                            double ty,
                                            int radius,
                                            double radiusSq,
                                            @NotNull RandomSource rng) {
        try {
            int points = 4 + rng.nextInt(4); // 4–7
            double outerR = 6 + rng.nextDouble() * 9; // 6–15
            double innerFactor = 0.35 + rng.nextDouble() * 0.3;
            int thickness = 1 + rng.nextInt(2);
            double rotation = Math.atan2(ry, rx) + (rng.nextDouble() - 0.5) * Math.PI / 3.0;

            int[] xs = new int[points * 2];
            int[] ys = new int[points * 2];

            for (int i = 0; i < points * 2; i++) {
                double angle = rotation + (Math.PI * i) / points;
                double r = (i % 2 == 0) ? outerR : outerR * innerFactor;
                xs[i] = ax + (int) Math.round(Math.cos(angle) * r);
                ys[i] = ay + (int) Math.round(Math.sin(angle) * r);
            }

            for (int i = 0; i < xs.length; i++) {
                int j = (i + 1) % xs.length;
                drawClippedLine(pixels, xs[i], ys[i], xs[j], ys[j], thickness, radius, radius, radiusSq);
            }

            if (rng.nextFloat() < 0.6f) {
                for (int i = 0; i < points; i++) {
                    int innerIdx = i * 2 + 1;
                    if (innerIdx >= xs.length) innerIdx = xs.length - 1;
                    drawClippedLine(pixels, ax, ay, xs[innerIdx], ys[innerIdx], 1, radius, radius, radiusSq);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawGlyph_SigilStar failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Rings
    // -------------------------------------------------------------------------

    private static void drawCircleRing(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int r,
                                       int thickness) {
        try {
            if (r <= 0 || thickness <= 0) return;
            int size = pixels.length;
            double outerR = r + (thickness - 1) / 2.0;
            double innerR = r - (thickness - 1) / 2.0;
            double outerSq = outerR * outerR;
            double innerSq = innerR * innerR;

            int minY = (int) Math.max(0, Math.floor(cy - outerR - 1));
            int maxY = (int) Math.min(size - 1, Math.ceil(cy + outerR + 1));
            int minX = (int) Math.max(0, Math.floor(cx - outerR - 1));
            int maxX = (int) Math.min(size - 1, Math.ceil(cx + outerR + 1));

            for (int y = minY; y <= maxY; y++) {
                int dy = y - cy;
                int dySq = dy * dy;
                for (int x = minX; x <= maxX; x++) {
                    int dx = x - cx;
                    double d2 = dx * dx + dySq;
                    if (d2 <= outerSq && d2 >= innerSq) {
                        pixels[y][x] = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawCircleRing failed", t);
        }
    }

    private static void drawPartialRing(boolean[][] pixels,
                                        int cx,
                                        int cy,
                                        int r,
                                        int thickness,
                                        @NotNull RandomSource rng) {
        try {
            if (r <= 0 || thickness <= 0) return;

            int segments = 3 + rng.nextInt(6); // 3–8
            double totalAngle = Math.PI * 2.0;
            double usedAngle = totalAngle * (0.45 + rng.nextDouble() * 0.4); // 45–85%

            double offset = rng.nextDouble() * totalAngle;
            double angleStep = usedAngle / segments;

            for (int i = 0; i < segments; i++) {
                double segStart = offset + i * angleStep;
                double segEnd = offset + (i + 1) * angleStep;
                drawRingSegment(pixels, cx, cy, r, thickness, segStart, segEnd, 18 + rng.nextInt(22));
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawPartialRing failed", t);
        }
    }

    private static void drawRingSegment(boolean[][] pixels,
                                        int cx,
                                        int cy,
                                        int r,
                                        int thickness,
                                        double startAngle,
                                        double endAngle,
                                        int samples) {
        try {
            double step = (endAngle - startAngle) / samples;
            int lastX = Integer.MIN_VALUE;
            int lastY = Integer.MIN_VALUE;

            for (int i = 0; i <= samples; i++) {
                double angle = startAngle + step * i;
                int x = cx + (int) Math.round(Math.cos(angle) * r);
                int y = cy + (int) Math.round(Math.sin(angle) * r);

                if (lastX != Integer.MIN_VALUE) {
                    drawLine(pixels, lastX, lastY, x, y, thickness);
                }

                lastX = x;
                lastY = y;
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawRingSegment failed", t);
        }
    }

    private static void drawArcSegment(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       double r,
                                       double orientation,
                                       double arcAngle,
                                       int thickness,
                                       int centerX,
                                       int centerY,
                                       double radiusSq) {
        try {
            int segments = 20;
            double start = orientation - arcAngle / 2.0;
            double end = orientation + arcAngle / 2.0;
            double step = (end - start) / segments;

            int lastX = Integer.MIN_VALUE;
            int lastY = Integer.MIN_VALUE;

            for (int i = 0; i <= segments; i++) {
                double angle = start + i * step;
                int x = cx + (int) Math.round(Math.cos(angle) * r);
                int y = cy + (int) Math.round(Math.sin(angle) * r);

                if (!insideCircle(x, y, centerX, centerY, radiusSq)) {
                    continue;
                }

                if (lastX != Integer.MIN_VALUE) {
                    drawClippedLine(pixels, lastX, lastY, x, y, thickness, centerX, centerY, radiusSq);
                }

                lastX = x;
                lastY = y;
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawArcSegment failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Density feedback
    // -------------------------------------------------------------------------

    private static double computeCoverage(boolean[][] pixels, int cx, int cy, int radius) {
        int size = pixels.length;
        int rSq = radius * radius;

        int insideCount = 0;
        int filledCount = 0;

        int minY = Math.max(0, cy - radius);
        int maxY = Math.min(size - 1, cy + radius);
        int minX = Math.max(0, cx - radius);
        int maxX = Math.min(size - 1, cx + radius);

        for (int y = minY; y <= maxY; y++) {
            int dy = y - cy;
            int dySq = dy * dy;
            for (int x = minX; x <= maxX; x++) {
                int dx = x - cx;
                int d2 = dx * dx + dySq;
                if (d2 <= rSq) {
                    insideCount++;
                    if (pixels[y][x]) filledCount++;
                }
            }
        }

        if (insideCount == 0) return 0.0;
        return filledCount / (double) insideCount;
    }

    private static void addDensityPass(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       @NotNull RandomSource rng,
                                       int passIndex) {
        try {
            int mode = rng.nextInt(5);
            switch (mode) {
                case 0 -> addInnerGlyphRing(pixels, cx, cy, radius, radiusSq, rng, passIndex);
                case 1 -> addRadialRays(pixels, cx, cy, radius, radiusSq, rng, passIndex);
                case 2 -> addWebSegments(pixels, cx, cy, radius, radiusSq, rng, passIndex);
                case 3 -> addRadialBeads(pixels, cx, cy, radius, radiusSq, rng, passIndex);
                case 4 -> addInnerNet(pixels, cx, cy, radius, radiusSq, rng, passIndex);
                default -> addRadialRays(pixels, cx, cy, radius, radiusSq, rng, passIndex);
            }
            LOG.debug("[SealSigilGenerator] addDensityPass: mode={} passIndex={}", mode, passIndex);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] addDensityPass failed", t);
        }
    }

    private static void addInnerGlyphRing(boolean[][] pixels,
                                          int cx,
                                          int cy,
                                          int radius,
                                          double radiusSq,
                                          @NotNull RandomSource rng,
                                          int passIndex) {
        try {
            int extraGlyphs = 4 + rng.nextInt(6); // 4–9
            double baseRingNorm = 0.25 + rng.nextDouble() * 0.25; // 0.25–0.50
            double baseRingR = radius * baseRingNorm;

            double globalRotation = rng.nextDouble() * Math.PI * 2.0;

            for (int i = 0; i < extraGlyphs; i++) {
                double t = (i + 0.5) / extraGlyphs;
                double baseAngle = t * Math.PI * 2.0 + globalRotation;
                double jitter = (rng.nextDouble() - 0.5) * (Math.PI / extraGlyphs);
                double angle = baseAngle + jitter;

                double rOffsetNorm = (rng.nextDouble() - 0.5) * 0.12;
                double r = baseRingR * (1.0 + rOffsetNorm);
                if (r < radius * 0.2) r = radius * 0.2;
                if (r > radius * 0.6) r = radius * 0.6;

                int anchorX = cx + (int) Math.round(Math.cos(angle) * r);
                int anchorY = cy + (int) Math.round(Math.sin(angle) * r);

                if (!insideCircle(anchorX, anchorY, cx, cy, radiusSq)) {
                    continue;
                }

                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double rx = cos;
                double ry = sin;
                double tx = -sin;
                double ty = cos;

                int glyphType = rng.nextInt(10);

                switch (glyphType) {
                    case 0 -> drawGlyph_Trident(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 1 -> drawGlyph_DiamondSpine(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 2 -> drawGlyph_BoxCross(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 3 -> drawGlyph_Hook(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 4 -> drawGlyph_Chevron(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 5 -> drawGlyph_PillarRune(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 6 -> drawGlyph_AngledRune(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 7 -> drawGlyph_OrbCluster(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 8 -> drawGlyph_TwinnedArc(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 9 -> drawGlyph_SigilStar(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    default -> drawGlyph_Trident(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] addInnerGlyphRing failed", t);
        }
    }

    private static void addRadialRays(boolean[][] pixels,
                                      int cx,
                                      int cy,
                                      int radius,
                                      double radiusSq,
                                      @NotNull RandomSource rng,
                                      int passIndex) {
        try {
            int rayCount = 6 + rng.nextInt(8); // 6–13
            double baseInnerNorm = 0.18 + rng.nextDouble() * 0.12; // 0.18–0.30
            double baseOuterNorm = 0.55 + rng.nextDouble() * 0.25; // 0.55–0.80

            double innerR = radius * baseInnerNorm;
            double outerR = radius * baseOuterNorm;

            double rotation = rng.nextDouble() * Math.PI * 2.0;

            for (int i = 0; i < rayCount; i++) {
                double t = i / (double) rayCount;
                double angle = t * Math.PI * 2.0 + rotation;

                int x0 = cx + (int) Math.round(Math.cos(angle) * innerR);
                int y0 = cy + (int) Math.round(Math.sin(angle) * innerR);
                int x1 = cx + (int) Math.round(Math.cos(angle) * outerR);
                int y1 = cy + (int) Math.round(Math.sin(angle) * outerR);

                int thickness = 1 + rng.nextInt(2);
                drawClippedLine(pixels, x0, y0, x1, y1, thickness, cx, cy, radiusSq);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] addRadialRays failed", t);
        }
    }

    private static void addWebSegments(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       @NotNull RandomSource rng,
                                       int passIndex) {
        try {
            int points = 6 + rng.nextInt(8); // 6–13
            double baseRingNorm = 0.35 + rng.nextDouble() * 0.2; // 0.35–0.55
            double r = radius * baseRingNorm;

            double rotation = rng.nextDouble() * Math.PI * 2.0;

            int[] px = new int[points];
            int[] py = new int[points];

            for (int i = 0; i < points; i++) {
                double t = i / (double) points;
                double angle = t * Math.PI * 2.0 + rotation;
                double rJitter = r * (1.0 + (rng.nextDouble() - 0.5) * 0.2);

                px[i] = cx + (int) Math.round(Math.cos(angle) * rJitter);
                py[i] = cy + (int) Math.round(Math.sin(angle) * rJitter);
            }

            for (int i = 0; i < points; i++) {
                int j = (i + 1) % points;
                drawClippedLine(pixels, px[i], py[i], px[j], py[j], 1, cx, cy, radiusSq);
            }

            if (rng.nextFloat() < 0.7f) {
                int step = 2 + rng.nextInt(3); // 2–4
                for (int i = 0; i < points; i++) {
                    int j = (i + step) % points;
                    drawClippedLine(pixels, px[i], py[i], px[j], py[j], 1, cx, cy, radiusSq);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] addWebSegments failed", t);
        }
    }

    private static void addRadialBeads(boolean[][] pixels,
                                       int cx,
                                       int cy,
                                       int radius,
                                       double radiusSq,
                                       @NotNull RandomSource rng,
                                       int passIndex) {
        try {
            int beadCount = 10 + rng.nextInt(12); // 10–21
            double innerNorm = 0.28 + rng.nextDouble() * 0.1;  // 0.28–0.38
            double outerNorm = 0.62 + rng.nextDouble() * 0.15; // 0.62–0.77

            double innerR = radius * innerNorm;
            double outerR = radius * outerNorm;

            double rotation = rng.nextDouble() * Math.PI * 2.0;

            for (int i = 0; i < beadCount; i++) {
                double t = i / (double) beadCount;
                double angle = t * Math.PI * 2.0 + rotation;
                double r = (i % 2 == 0) ? innerR : outerR;

                int x = cx + (int) Math.round(Math.cos(angle) * r);
                int y = cy + (int) Math.round(Math.sin(angle) * r);

                if (!insideCircle(x, y, cx, cy, radiusSq)) {
                    continue;
                }

                int beadRadius = 1 + rng.nextInt(2); // 1–2
                drawDisc(pixels, x, y, beadRadius);

                if (rng.nextFloat() < 0.4f) {
                    double angle2 = angle + (rng.nextBoolean() ? Math.PI / 18.0 : -Math.PI / 18.0);
                    double r2 = r * (0.93 + rng.nextDouble() * 0.12);
                    int x2 = cx + (int) Math.round(Math.cos(angle2) * r2);
                    int y2 = cy + (int) Math.round(Math.sin(angle2) * r2);
                    if (insideCircle(x2, y2, cx, cy, radiusSq)) {
                        drawDisc(pixels, x2, y2, 1);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] addRadialBeads failed", t);
        }
    }

    private static void addInnerNet(boolean[][] pixels,
                                    int cx,
                                    int cy,
                                    int radius,
                                    double radiusSq,
                                    @NotNull RandomSource rng,
                                    int passIndex) {
        try {
            double netRadiusNorm = 0.3 + rng.nextDouble() * 0.2; // 0.3–0.5
            int netRadius = (int) Math.round(radius * netRadiusNorm);

            int radialLines = 4 + rng.nextInt(4); // 4–7
            int ringLines = 3 + rng.nextInt(3);   // 3–5

            for (int i = 0; i < radialLines; i++) {
                double angle = (2.0 * Math.PI * i) / radialLines + rng.nextDouble() * 0.1;
                int x0 = cx;
                int y0 = cy;
                int x1 = cx + (int) Math.round(Math.cos(angle) * netRadius);
                int y1 = cy + (int) Math.round(Math.sin(angle) * netRadius);
                drawClippedLine(pixels, x0, y0, x1, y1, 1, cx, cy, radiusSq);
            }

            for (int rIndex = 1; rIndex <= ringLines; rIndex++) {
                double rNorm = (rIndex / (double) (ringLines + 1));
                int r = (int) Math.round(netRadius * rNorm);
                if (r <= 1) continue;
                drawCircleRing(pixels, cx, cy, r, 1);
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] addInnerNet failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Drawing helpers
    // -------------------------------------------------------------------------

    private static void drawDisc(boolean[][] pixels, int cx, int cy, int r) {
        try {
            if (r <= 0) return;
            int size = pixels.length;
            int rSq = r * r;

            int minY = Math.max(0, cy - r);
            int maxY = Math.min(size - 1, cy + r);
            int minX = Math.max(0, cx - r);
            int maxX = Math.min(size - 1, cx + r);

            for (int y = minY; y <= maxY; y++) {
                int dy = y - cy;
                int dySq = dy * dy;
                for (int x = minX; x <= maxX; x++) {
                    int dx = x - cx;
                    int d2 = dx * dx + dySq;
                    if (d2 <= rSq) {
                        pixels[y][x] = true;
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawDisc failed", t);
        }
    }

    private static boolean insideCircle(int x, int y, int cx, int cy, double radiusSq) {
        int dx = x - cx;
        int dy = y - cy;
        return dx * dx + dy * dy <= radiusSq;
    }

    private static void drawLine(boolean[][] pixels,
                                 int x0,
                                 int y0,
                                 int x1,
                                 int y1,
                                 int thickness) {
        try {
            int dx = Math.abs(x1 - x0);
            int dy = Math.abs(y1 - y0);
            int sx = x0 < x1 ? 1 : -1;
            int sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;

            int size = pixels.length;
            int r = Math.max(0, thickness - 1);

            while (true) {
                if (x0 >= 0 && y0 >= 0 && x0 < size && y0 < size) {
                    if (r == 0) {
                        pixels[y0][x0] = true;
                    } else {
                        for (int oy = -r; oy <= r; oy++) {
                            for (int ox = -r; ox <= r; ox++) {
                                int nx = x0 + ox;
                                int ny = y0 + oy;
                                if (nx >= 0 && ny >= 0 && nx < size && ny < size) {
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
            LOG.error("[SealSigilGenerator] drawLine failed", t);
        }
    }

    private static void drawClippedLine(boolean[][] pixels,
                                        int x0,
                                        int y0,
                                        int x1,
                                        int y1,
                                        int thickness,
                                        int centerX,
                                        int centerY,
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
                if (!insideCircle(x0, y0, centerX, centerY, radiusSq)) {
                    break;
                }

                if (x0 >= 0 && y0 >= 0 && x0 < size && y0 < size) {
                    if (r == 0) {
                        pixels[y0][x0] = true;
                    } else {
                        for (int oy = -r; oy <= r; oy++) {
                            for (int ox = -r; ox <= r; ox++) {
                                int nx = x0 + ox;
                                int ny = y0 + oy;
                                if (nx >= 0 && ny >= 0 && nx < size && ny < size) {
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
            LOG.error("[SealSigilGenerator] drawClippedLine failed", t);
        }
    }

    private static void invertInsideCircle(boolean[][] pixels, int cx, int cy, int radius) {
        try {
            int size = pixels.length;
            int rSq = radius * radius;

            int minY = Math.max(0, cy - radius);
            int maxY = Math.min(size - 1, cy + radius);
            int minX = Math.max(0, cx - radius);
            int maxX = Math.min(size - 1, cx + radius);

            for (int y = minY; y <= maxY; y++) {
                int dy = y - cy;
                int dySq = dy * dy;
                for (int x = minX; x <= maxX; x++) {
                    int dx = x - cx;
                    int d2 = dx * dx + dySq;
                    if (d2 <= rSq) {
                        pixels[y][x] = !pixels[y][x];
                    }
                }
            }

            LOG.debug("[SealSigilGenerator] invertInsideCircle: radius={} center=({}, {})", radius, cx, cy);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] invertInsideCircle failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Misc
    // -------------------------------------------------------------------------

    private static String safeSecretPreview(String secret) {
        if (secret == null) return "<null>";
        String trimmed = secret.trim();
        if (trimmed.length() <= 8) return trimmed;
        return trimmed.substring(0, 8) + "...";
    }

    // -------------------------------------------------------------------------
    // Data holder
    // -------------------------------------------------------------------------

    public static final class SigilPattern {

        private final int size;
        private final boolean[][] pixels;
        private final long seed;

        public SigilPattern(int size, boolean[][] pixels, long seed) {
            this.size = size;
            this.pixels = pixels;
            this.seed = seed;
        }

        public int getSize() {
            return size;
        }

        public boolean[][] getPixels() {
            return pixels;
        }

        public long getSeed() {
            return seed;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("SigilPattern{size=").append(size)
                    .append(", seed=").append(seed).append("}\n");
            try {
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        sb.append(pixels[y][x] ? '#' : '.');
                    }
                    sb.append('\n');
                }
            } catch (Throwable t) {
                LOG.error("[SealSigilGenerator.SigilPattern] toString() failed", t);
            }
            return sb.toString();
        }
    }
}
