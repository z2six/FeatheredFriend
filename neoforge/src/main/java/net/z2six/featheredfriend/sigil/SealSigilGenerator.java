// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilGenerator.java
package net.z2six.featheredfriend.sigil;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilGenerator.java
 *
 * SealSigilGenerator
 *
 * Deterministic sigil generator based on:
 *  - Player UUID
 *  - Player-chosen secret string
 *
 * Output:
 *  - A circular boolean bitmap (SigilPattern) that we can render as a wax seal.
 *
 * Design (NEW VERSION):
 *  - Work in a polar/circular space, not a square grid aesthetic.
 *  - Sigil is composed from multiple layers:
 *      * Wavy outer ring band (not a solid circle).
 *      * Radial spokes (lines) with symmetry, plus jitter.
 *      * Inner orbital arcs (ring segments at various radii).
 *      * Central motif (cross/diamond) to break up sameness.
 *
 *  - The number of spokes, arc positions, symmetry count, etc. are all
 *    driven by the 64-bit seed, so different seeds look noticeably different.
 *
 *  - We *only* store the 64-bit seed for stamps; the final bitmap is
 *    always generated on demand.
 */
public final class SealSigilGenerator {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Default radius for the sigil in "pixels".
     * Grid size will be (2 * DEFAULT_RADIUS + 1).
     */
    public static final int DEFAULT_RADIUS = 64;

    /**
     * Default square grid size, derived from radius.
     */
    public static final int DEFAULT_SIZE = DEFAULT_RADIUS * 2 + 1;

    private SealSigilGenerator() {
        // no instances
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Compute a 64-bit seed from (playerUUID + secretString) and generate a sigil pattern.
     */
    public static @NotNull SigilPattern generateForPlayer(@NotNull UUID playerUuid,
                                                          @NotNull String secret) {
        long seed = computeSeed(playerUuid, secret);
        return generateFromSeed(seed, DEFAULT_RADIUS);
    }

    /**
     * Compute a 64-bit seed from (playerUUID + secretString).
     *
     * One-way SHA-256; original secret is not practically recoverable from the seed.
     */
    public static long computeSeed(@NotNull UUID playerUuid, @NotNull String secret) {
        String input = playerUuid.toString() + "|" + secret;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));

            // Take the first 8 bytes as a signed long.
            ByteBuffer buf = ByteBuffer.wrap(hash);
            long seed = buf.getLong();
            LOG.debug("[SealSigilGenerator] computeSeed: uuid={} secret='{}' -> seed={}",
                    playerUuid, safeSecretPreview(secret), seed);
            return seed;
        } catch (Throwable t) {
            // Fail-safe: fall back to a simpler hash if crypto fails for some reason.
            LOG.error("[SealSigilGenerator] computeSeed failed for uuid={} secret='{}', " +
                            "falling back to String.hashCode()",
                    playerUuid, safeSecretPreview(secret), t);
            return (playerUuid.toString() + "|" + secret).hashCode();
        }
    }

    /**
     * Generate a sigil pattern from a 64-bit seed.
     *
     * @param seed   64-bit seed (e.g. from computeSeed or any other hash).
     * @param radius desired radius in pixels; grid size will be (2*radius + 1).
     */
    public static @NotNull SigilPattern generateFromSeed(long seed, int radius) {
        try {
            if (radius < 8) {
                radius = DEFAULT_RADIUS;
            }

            int size = radius * 2 + 1;
            int center = radius;

            boolean[][] pixels = new boolean[size][size];
            RandomSource rng = RandomSource.create(seed);

            LOG.debug("[SealSigilGenerator] generateFromSeed: seed={} radius={} size={}", seed, radius, size);

            // Pick a rotational symmetry count: 3..8
            int symmetry = 3 + rng.nextInt(6); // 3,4,5,6,7,8

            // Base ring band parameters
            double outerRadius = radius - 1.5;
            double innerBase = radius * (0.45 + rng.nextDouble() * 0.08); // 0.45..0.53 R
            double bandThickness = radius * (0.18 + rng.nextDouble() * 0.10); // 0.18..0.28 R

            // Harmonic noise for ring wobble
            int freq1 = symmetry;
            int freq2 = symmetry * 2;
            int freq3 = symmetry * 3;

            double phase1 = rng.nextDouble() * Math.PI * 2.0;
            double phase2 = rng.nextDouble() * Math.PI * 2.0;
            double phase3 = rng.nextDouble() * Math.PI * 2.0;

            // Draw the wavy outer ring band
            drawWavyRingBand(pixels, center, radius,
                    innerBase, bandThickness,
                    freq1, freq2, freq3,
                    phase1, phase2, phase3,
                    rng);

            // Add symmetric radial spokes
            addRadialSpokes(pixels, center, radius, symmetry, innerBase, bandThickness, rng);

            // Add orbital ring arcs inside the main band
            addInnerOrbits(pixels, center, radius, symmetry, innerBase, bandThickness, rng);

            // Add a central motif (cross + diamond-ish)
            addCentralMotif(pixels, center, radius, rng);

            return new SigilPattern(size, pixels, seed);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] generateFromSeed failed (seed={}, radius={}), " +
                            "returning minimal pattern",
                    seed, radius, t);
            int safeRadius = Math.max(8, radius);
            int safeSize = safeRadius * 2 + 1;
            boolean[][] fallback = new boolean[safeSize][safeSize];
            fallback[safeRadius][safeRadius] = true;
            return new SigilPattern(safeSize, fallback, seed);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static String safeSecretPreview(String secret) {
        if (secret == null) return "<null>";
        String trimmed = secret.trim();
        if (trimmed.length() <= 8) return trimmed;
        return trimmed.substring(0, 8) + "...";
    }

    /**
     * Draw a wavy ring band with harmonic noise in radius and thickness.
     */
    private static void drawWavyRingBand(boolean[][] pixels,
                                         int center,
                                         int radius,
                                         double innerBase,
                                         double bandThickness,
                                         int freq1,
                                         int freq2,
                                         int freq3,
                                         double phase1,
                                         double phase2,
                                         double phase3,
                                         RandomSource rng) {
        int size = pixels.length;

        // Additional phase just for thickness variation
        double thicknessPhase = rng.nextDouble() * Math.PI * 2.0;

        // Step size in radians; smaller gives smoother circles but is more expensive.
        double step = Math.toRadians(0.6); // ~600 samples around circle

        double maxRadius = radius - 1.0;

        for (double theta = 0.0; theta < Math.PI * 2.0; theta += step) {
            // Harmonic combination for radius variation
            double n1 = Math.sin(freq1 * theta + phase1);
            double n2 = Math.sin(freq2 * theta + phase2);
            double n3 = Math.sin(freq3 * theta + phase3);

            double radialOffset = (n1 * 0.22 + n2 * 0.18 + n3 * 0.12) * (radius * 0.3);
            double innerRadius = innerBase + radialOffset * 0.45;

            double thicknessNoise = Math.sin(freq2 * theta + thicknessPhase) * 0.20;
            double localThickness = bandThickness * (0.9 + thicknessNoise);

            if (localThickness < radius * 0.10) {
                localThickness = radius * 0.10;
            }

            double outerRadius = innerRadius + localThickness;
            if (outerRadius > maxRadius) {
                outerRadius = maxRadius;
            }

            // Rasterize the band by sampling radii between inner/outer at this angle.
            double rStep = 0.5;
            for (double r = innerRadius; r <= outerRadius; r += rStep) {
                int x = center + (int) Math.round(r * Math.cos(theta));
                int y = center + (int) Math.round(r * Math.sin(theta));
                setPixelSafe(pixels, x, y, true);
            }
        }

        LOG.debug("[SealSigilGenerator] Wavy ring band drawn (innerBase={}, bandThickness={})",
                innerBase, bandThickness);
    }

    /**
     * Add symmetric radial spokes (lines) across the band and slightly inside it.
     */
    private static void addRadialSpokes(boolean[][] pixels,
                                        int center,
                                        int radius,
                                        int symmetry,
                                        double innerBase,
                                        double bandThickness,
                                        RandomSource rng) {
        int spokeSets = 1 + rng.nextInt(3); // 1..3 groups of spokes

        for (int s = 0; s < spokeSets; s++) {
            // Each set can have its own angle offset
            double setOffset = rng.nextDouble() * (Math.PI * 2.0 / symmetry);
            double innerRadius = innerBase * (0.65 + rng.nextDouble() * 0.10);
            double outerRadius = innerBase + bandThickness * (0.80 + rng.nextDouble() * 0.20);

            for (int i = 0; i < symmetry; i++) {
                double baseAngle = (Math.PI * 2.0 * i) / symmetry + setOffset;
                // Slight random jitter so they aren't perfectly regular.
                double angle = baseAngle + (rng.nextDouble() - 0.5) * (Math.PI / (symmetry * 3.0));

                int x0 = center + (int) Math.round(innerRadius * Math.cos(angle));
                int y0 = center + (int) Math.round(innerRadius * Math.sin(angle));
                int x1 = center + (int) Math.round(outerRadius * Math.cos(angle));
                int y1 = center + (int) Math.round(outerRadius * Math.sin(angle));

                drawThickLine(pixels, x0, y0, x1, y1,
                        1 + rng.nextInt(2)); // thickness 1..2
            }
        }

        LOG.debug("[SealSigilGenerator] Added {}-sym radial spokes (sets={})", symmetry, spokeSets);
    }

    /**
     * Add inner orbital arcs: partial rings at different radii, repeated with symmetry.
     */
    private static void addInnerOrbits(boolean[][] pixels,
                                       int center,
                                       int radius,
                                       int symmetry,
                                       double innerBase,
                                       double bandThickness,
                                       RandomSource rng) {
        int orbitCount = 1 + rng.nextInt(3); // 1..3 orbital radii

        for (int o = 0; o < orbitCount; o++) {
            double orbitRadius = innerBase * (0.55 + rng.nextDouble() * 0.35); // 0.55..0.90 innerBase
            double thickness = radius * (0.04 + rng.nextDouble() * 0.04);      // 4..8% of radius

            // Arc length per sector (in radians)
            double sectorAngle = (Math.PI * 2.0) / symmetry;
            double arcSpan = sectorAngle * (0.35 + rng.nextDouble() * 0.35); // 35%..70% of sector

            double baseOffset = rng.nextDouble() * sectorAngle;

            for (int i = 0; i < symmetry; i++) {
                double sectorStart = sectorAngle * i + baseOffset;
                double startAngle = sectorStart - arcSpan / 2.0;
                double endAngle = sectorStart + arcSpan / 2.0;

                drawArcBand(pixels, center, orbitRadius, thickness, startAngle, endAngle);
            }
        }

        LOG.debug("[SealSigilGenerator] Added inner orbits (count={})", orbitCount);
    }

    /**
     * Draw an arc-shaped band with given radius/thickness over angle range.
     */
    private static void drawArcBand(boolean[][] pixels,
                                    int center,
                                    double radius,
                                    double thickness,
                                    double startAngle,
                                    double endAngle) {
        if (endAngle < startAngle) {
            double tmp = startAngle;
            startAngle = endAngle;
            endAngle = tmp;
        }

        double innerR = radius - thickness / 2.0;
        double outerR = radius + thickness / 2.0;
        if (innerR < 0.0) innerR = 0.0;

        double step = Math.toRadians(0.8); // angular resolution
        double rStep = 0.6;

        for (double theta = startAngle; theta <= endAngle; theta += step) {
            for (double r = innerR; r <= outerR; r += rStep) {
                int x = center + (int) Math.round(r * Math.cos(theta));
                int y = center + (int) Math.round(r * Math.sin(theta));
                setPixelSafe(pixels, x, y, true);
            }
        }
    }

    /**
     * Central motif: a small cross + diamond-ish cluster to add visual focus.
     */
    private static void addCentralMotif(boolean[][] pixels,
                                        int center,
                                        int radius,
                                        RandomSource rng) {
        int size = pixels.length;

        // Ensure center point is always marked
        setPixelSafe(pixels, center, center, true);

        int armLength = Math.max(2, radius / 10); // small cross arms

        // Vertical line
        drawThickLine(pixels, center, center - armLength, center, center + armLength, 1);

        // Horizontal line
        drawThickLine(pixels, center - armLength, center, center + armLength, center, 1);

        // Optional diamond / rotated square
        int diamondRadius = Math.max(2, radius / 12);
        double step = Math.toRadians(4.0);
        for (double theta = 0.0; theta < Math.PI * 2.0; theta += step) {
            int x = center + (int) Math.round(diamondRadius * Math.cos(theta));
            int y = center + (int) Math.round(diamondRadius * Math.sin(theta));
            setPixelSafe(pixels, x, y, true);
        }

        // Sprinkle a few random near-center dots for variation.
        int extras = 3 + rng.nextInt(5);
        for (int i = 0; i < extras; i++) {
            double r = radius * (0.05 + rng.nextDouble() * 0.15); // within inner region
            double theta = rng.nextDouble() * Math.PI * 2.0;
            int x = center + (int) Math.round(r * Math.cos(theta));
            int y = center + (int) Math.round(r * Math.sin(theta));
            setPixelSafe(pixels, x, y, true);
        }

        LOG.debug("[SealSigilGenerator] Central motif added (armLength={} extras={})", armLength, extras);
    }

    /**
     * Draw a line with simple DDA-style stepping and a configurable thickness.
     */
    private static void drawThickLine(boolean[][] pixels,
                                      int x0,
                                      int y0,
                                      int x1,
                                      int y1,
                                      int thickness) {
        int dx = x1 - x0;
        int dy = y1 - y0;

        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            setPixelSafe(pixels, x0, y0, true);
            return;
        }

        double sx = dx / (double) steps;
        double sy = dy / (double) steps;

        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(x0 + sx * i);
            int y = (int) Math.round(y0 + sy * i);
            drawThickPoint(pixels, x, y, thickness);
        }
    }

    /**
     * Mark a small square of pixels around (x, y) to approximate line thickness.
     */
    private static void drawThickPoint(boolean[][] pixels, int x, int y, int thickness) {
        int half = thickness / 2;
        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                setPixelSafe(pixels, x + dx, y + dy, true);
            }
        }
    }

    private static void setPixelSafe(boolean[][] pixels, int x, int y, boolean value) {
        int size = pixels.length;
        if (x < 0 || y < 0 || x >= size || y >= size) {
            return;
        }
        pixels[y][x] = value;
    }

    // -------------------------------------------------------------------------
    // Data holder
    // -------------------------------------------------------------------------

    /**
     * Immutable sigil bitmap.
     */
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
