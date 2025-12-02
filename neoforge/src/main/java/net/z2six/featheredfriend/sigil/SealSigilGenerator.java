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
 * Current approach:
 *  - Sigil fits inside a circle of radius DEFAULT_RADIUS.
 *  - Structured “runes” (glyphs) around a ring + central motif + optional binding rings.
 *  - Density feedback pass so we don’t end up with 90% empty sigils.
 *  - FINAL STEP: we invert the pattern inside the seal circle so “filled” becomes “void”
 *    and “void” becomes “wax”, giving a negative-style sigil.
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
    private static final double TARGET_MIN_COVERAGE = 0.22; // 22% of interior pixels “on”
    private static final int MAX_DENSITY_PASSES = 3;

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
            // 1) Outer ring boundary (subtle, just to define the seal edge)
            int outerThickness = OUTER_RING_THICKNESS_MIN +
                    rng.nextInt(OUTER_RING_THICKNESS_MAX - OUTER_RING_THICKNESS_MIN + 1);
            drawCircleRing(pixels, cx, cy, radius - 1, outerThickness);

            // 2) Central motif
            drawCentralMotif(pixels, cx, cy, radius, rng);

            // 3) Optional binding ring(s)
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

            // 4) Main glyph ring
            placeGlyphs(pixels, cx, cy, radius, radiusSq, rng);

            // 5) Density pass: if too empty, add more structure up to a few passes
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

            // 6) FINAL: invert the pattern inside the sigil circle
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

            int type = rng.nextInt(5);
            switch (type) {
                case 0 -> {
                    // small circle + cross
                    drawCircleRing(pixels, cx, cy, motifRadius, 1);
                    drawLine(pixels, cx - motifRadius, cy, cx + motifRadius, cy, 2);
                    drawLine(pixels, cx, cy - motifRadius, cx, cy + motifRadius, 2);
                }
                case 1 -> {
                    // diamond + spine
                    drawDiamond(pixels, cx, cy, motifRadius, 2);
                    drawLine(pixels, cx, cy - motifRadius, cx, cy + motifRadius, 2);
                }
                case 2 -> {
                    // filled disc
                    drawDisc(pixels, cx, cy, motifRadius);
                }
                case 3 -> {
                    // square-ish rune + diagonal bars
                    drawSquare(pixels, cx, cy, motifRadius, 2);
                    drawLine(pixels, cx - motifRadius, cy, cx, cy - motifRadius, 1);
                    drawLine(pixels, cx, cy - motifRadius, cx + motifRadius, cy, 1);
                    drawLine(pixels, cx + motifRadius, cy, cx, cy + motifRadius, 1);
                    drawLine(pixels, cx, cy + motifRadius, cx - motifRadius, cy, 1);
                }
                case 4 -> {
                    // disc + small inner ring (dense but still structured)
                    drawDisc(pixels, cx, cy, motifRadius);
                    if (motifRadius > 3) {
                        drawCircleRing(pixels, cx, cy, motifRadius - 2, 1);
                    }
                }
                default -> drawDisc(pixels, cx, cy, motifRadius);
            }

            LOG.debug("[SealSigilGenerator] drawCentralMotif: type={} motifRadius={}", type, motifRadius);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] drawCentralMotif failed", t);
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

                int glyphType = rng.nextInt(5); // 0..4

                switch (glyphType) {
                    case 0 -> drawGlyph_Trident(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 1 -> drawGlyph_DiamondSpine(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 2 -> drawGlyph_BoxCross(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 3 -> drawGlyph_Hook(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 4 -> drawGlyph_Chevron(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    default -> drawGlyph_Trident(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                }
            }

            LOG.debug("[SealSigilGenerator] placeGlyphs: glyphCount={} baseRingNorm={}", glyphCount, baseRingNorm);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] placeGlyphs failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Individual glyph generators
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
            int mode = rng.nextInt(3);
            switch (mode) {
                case 0 -> addInnerGlyphRing(pixels, cx, cy, radius, radiusSq, rng, passIndex);
                case 1 -> addRadialRays(pixels, cx, cy, radius, radiusSq, rng, passIndex);
                case 2 -> addWebSegments(pixels, cx, cy, radius, radiusSq, rng, passIndex);
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

                int glyphType = rng.nextInt(5);

                switch (glyphType) {
                    case 0 -> drawGlyph_Trident(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 1 -> drawGlyph_DiamondSpine(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 2 -> drawGlyph_BoxCross(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 3 -> drawGlyph_Hook(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
                    case 4 -> drawGlyph_Chevron(pixels, anchorX, anchorY, rx, ry, tx, ty, radius, radiusSq, rng);
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

    /**
     * Invert all pixels inside the seal circle: true -> false, false -> true.
     * Outside the circle remains untouched (always false in our generator).
     */
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
