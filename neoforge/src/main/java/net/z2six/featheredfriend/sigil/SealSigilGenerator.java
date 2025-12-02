// neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilGenerator.java
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
 * SealSigilGenerator
 *
 * Deterministic "sigil" generator based on:
 *  - Player UUID
 *  - A player-chosen secret string (seed)
 *
 * Output is a small symmetric boolean grid ("pixels") that we can render as
 * an abstract seal / glyph.
 *
 * For now the pattern is:
 *  - Square grid (odd size, default 17x17)
 *  - Radial + horizontal symmetry:
 *      * Concentric "rings" approximated by discrete samples
 *      * "Spikes" drawn as radial lines
 *      * Some inner dots for extra flavor
 *
 * The visual design can be iterated later without breaking the storage model,
 * because we only store the *seed*, not the final bitmap.
 */
public final class SealSigilGenerator {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Default sigil grid size. Must be odd so we have a perfect center.
     */
    public static final int DEFAULT_SIZE = 17;

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
        return generateFromSeed(seed, DEFAULT_SIZE);
    }

    /**
     * Compute a 64-bit seed from (playerUUID + secretString).
     *
     * This is one-way (SHA-256); the original secret string is not recoverable
     * from the seed in any practical sense.
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
     * Generate a sigil pattern from a 64-bit seed. This is the core procedural generator.
     */
    public static @NotNull SigilPattern generateFromSeed(long seed, int size) {
        if (size <= 3) {
            size = DEFAULT_SIZE;
        }
        if (size % 2 == 0) {
            size += 1; // ensure odd
        }

        boolean[][] pixels = new boolean[size][size];
        RandomSource rng = RandomSource.create(seed);

        int center = size / 2;
        double maxRadius = center - 1;

        LOG.debug("[SealSigilGenerator] generateFromSeed: seed={} size={}", seed, size);

        try {
            // 1) Base circular "frame"
            drawOuterFrame(pixels, center, maxRadius);

            // 2) Concentric "rings"
            int ringCount = 2 + rng.nextInt(3); // 2..4
            for (int i = 0; i < ringCount; i++) {
                double t = (i + 1) / (double) (ringCount + 1);
                double radius = 1.5 + t * (maxRadius - 2.0);
                drawRing(pixels, center, radius, rng);
            }

            // 3) Spikes / rays
            int spikeCount = 4 + rng.nextInt(9); // 4..12
            drawSpikes(pixels, center, maxRadius, spikeCount, rng);

            // 4) Inner motif dots
            drawInnerMotif(pixels, center, rng);

        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] generateFromSeed failed (seed={}, size={}), " +
                            "pattern may be sparse but game will continue",
                    seed, size, t);
        }

        return new SigilPattern(size, pixels, seed);
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

    private static void drawOuterFrame(boolean[][] pixels, int center, double maxRadius) {
        int size = pixels.length;
        double r = maxRadius;

        // Approximate a circle with a handful of samples, then mirror via symmetry.
        int samples = 96;
        for (int i = 0; i < samples; i++) {
            double angle = (2.0 * Math.PI * i) / samples;
            int x = center + (int) Math.round(r * Math.cos(angle));
            int y = center + (int) Math.round(r * Math.sin(angle));
            setPixelSafe(pixels, x, y, true);
        }

        // Also thicken frame slightly by marking a smaller radius.
        double inner = r - 1.0;
        for (int i = 0; i < samples; i++) {
            double angle = (2.0 * Math.PI * i) / samples;
            int x = center + (int) Math.round(inner * Math.cos(angle));
            int y = center + (int) Math.round(inner * Math.sin(angle));
            setPixelSafe(pixels, x, y, true);
        }
    }

    private static void drawRing(boolean[][] pixels, int center, double radius, RandomSource rng) {
        int samples = 64;
        int size = pixels.length;

        for (int i = 0; i < samples; i++) {
            double angle = (2.0 * Math.PI * i) / samples;

            // Slight random jitter in radius for a less "perfect" look.
            double jitter = (rng.nextDouble() - 0.5) * 0.4;
            double r = Math.max(1.0, radius + jitter);

            int x = center + (int) Math.round(r * Math.cos(angle));
            int y = center + (int) Math.round(r * Math.sin(angle));
            if (x < 0 || y < 0 || x >= size || y >= size) {
                continue;
            }

            // Enforce horizontal & vertical symmetry explicitly.
            setSymmetricPixels(pixels, center, x, y);
        }
    }

    private static void drawSpikes(boolean[][] pixels,
                                   int center,
                                   double maxRadius,
                                   int spikeCount,
                                   RandomSource rng) {
        int size = pixels.length;

        for (int i = 0; i < spikeCount; i++) {
            // Spread spikes around the circle.
            double baseAngle = (2.0 * Math.PI * i) / spikeCount;
            double angle = baseAngle + (rng.nextDouble() - 0.5) * (Math.PI / 12.0); // slight random
            double length = maxRadius * (0.5 + rng.nextDouble() * 0.5); // between 50% and 100%

            int steps = (int) (length * 2.0);
            for (int s = 1; s <= steps; s++) {
                double r = (length * s) / steps;
                int x = center + (int) Math.round(r * Math.cos(angle));
                int y = center + (int) Math.round(r * Math.sin(angle));
                if (x < 0 || y < 0 || x >= size || y >= size) {
                    break;
                }
                setSymmetricPixels(pixels, center, x, y);
            }
        }
    }

    private static void drawInnerMotif(boolean[][] pixels, int center, RandomSource rng) {
        int size = pixels.length;

        // Simple "flower" in the center
        setPixelSafe(pixels, center, center, true);

        int petals = 4 + rng.nextInt(5); // 4..8
        double innerRadius = 2.0 + rng.nextDouble() * 2.0;
        for (int i = 0; i < petals; i++) {
            double angle = (2.0 * Math.PI * i) / petals;
            int x = center + (int) Math.round(innerRadius * Math.cos(angle));
            int y = center + (int) Math.round(innerRadius * Math.sin(angle));
            setSymmetricPixels(pixels, center, x, y);
        }

        // Optionally sprinkle a few random "stars" near the center.
        int extras = 3 + rng.nextInt(5);
        for (int i = 0; i < extras; i++) {
            double r = 1.5 + rng.nextDouble() * 3.0;
            double angle = rng.nextDouble() * 2.0 * Math.PI;
            int x = center + (int) Math.round(r * Math.cos(angle));
            int y = center + (int) Math.round(r * Math.sin(angle));
            setSymmetricPixels(pixels, center, x, y);
        }

        LOG.debug("[SealSigilGenerator] drawInnerMotif: size={} center={} extras={}",
                size, center, extras);
    }

    private static void setSymmetricPixels(boolean[][] pixels, int center, int x, int y) {
        int size = pixels.length;

        // Primary pixel.
        setPixelSafe(pixels, x, y, true);

        // Mirror horizontally
        int mx = 2 * center - x;
        int my = y;
        setPixelSafe(pixels, mx, my, true);

        // Mirror vertically
        mx = x;
        my = 2 * center - y;
        setPixelSafe(pixels, mx, my, true);

        // Mirror both
        mx = 2 * center - x;
        my = 2 * center - y;
        setPixelSafe(pixels, mx, my, true);
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
