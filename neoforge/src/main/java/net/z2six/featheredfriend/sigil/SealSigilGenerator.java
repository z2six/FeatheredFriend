// neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilGenerator.java
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
 * NEW sigil generator for FeatheredFriend seal stamps.
 *
 * High-level v2 design:
 *
 *  1) Random wavy boundary
 *     - Start from a circle of radius DEFAULT_RADIUS.
 *     - Perturb the radius as a function of angle with a smooth random walk.
 *     - Clamp so it never exceeds the base radius (no clipping on preview).
 *     - Fill everything inside this wavy boundary => solid “wax disc”.
 *
 *  2) Sliced symmetry engine
 *     - Choose N slices (2–8).
 *     - A “base slice” is defined as the angular wedge [0, 2π/N).
 *     - We generate shapes only once in the base slice mask.
 *
 *  3) Shape sets (modular)
 *     - A SealSigilShapeSet implementation carves shapes into a full-size mask.
 *     - For now:
 *         * index 0 => SealSigilShapeSetMedieval0  (heraldic / medieval)
 *         * index 1 => SealSigilShapeSetHighFantasy1 (high-fantasy: dragons, magic)
 *         * index 2 => SealSigilShapeSetFloral2 (floral / botanical)
 *
 *  4) Slice replication
 *     - For every pixel that is set in the base slice mask, we:
 *         * Convert to polar (r, angle).
 *         * For each slice k in [0, N):
 *               angle' = angle + k * (2π / N)
 *               x', y' = center + round(r * (cos(angle'), sin(angle')))
 *               shapeMask[y'][x'] = true.
 *
 *  5) Boolean extrusion
 *     - boundaryMask = solid wax disc.
 *     - shapeMask    = union of shapes across all slices.
 *     - finalPixels[y][x] = boundaryMask[y][x] && !shapeMask[y][x].
 *
 *  6) Determinism
 *     - All randomness is driven by a deterministic RandomSource created from a 64-bit seed.
 *     - Seed is derived either from:
 *         * (playerUUID + secretString[0..128]) via SHA-256 (legacy path), or
 *         * a one-way hash over (secret) for secret-only behavior; slices + shapeSetIndex
 *           are passed separately into the generator.
 *
 * Public entry points:
 *  - generateForPlayer(UUID, String)                     // legacy UUID+secret path
 *  - generateFromSeed(long, int, int, int)               // full control for preview/GUI
 *  - computeSeedFromSecretOnly(String, int, int)         // secret -> SHA256 -> seed (no UUID)
 */
public final class SealSigilGenerator {

    private static final Logger LOG = LogUtils.getLogger();

    public static final int DEFAULT_RADIUS = 64;
    public static final int DEFAULT_SIZE = DEFAULT_RADIUS * 2 + 1;
    private static final int MAX_SECRET_LENGTH = 128;

    /**
     * Minimum and maximum slices we allow. Outside this range we clamp.
     */
    private static final int MIN_SLICES = 2;
    private static final int MAX_SLICES = 8;

    private SealSigilGenerator() {
        // no instances
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generate a sigil pattern for a player, using the default radius and
     * a fixed shape set + slices for now (can be exposed later).
     *
     * This is mainly intended for actual “stamp” logic, not the debug preview.
     *
     * NOTE: This path still uses the UUID+secret-based seed (legacy behavior).
     * For secret-only / RP-friendly behavior, prefer computeSeedFromSecretOnly().
     */
    public static @NotNull SigilPattern generateForPlayer(@NotNull UUID playerUuid,
                                                          @NotNull String secret) {
        long seed = computeSeed(playerUuid, secret);
        int slices = 4;
        int shapeSetIndex = 0;
        return generateFromSeed(seed, DEFAULT_RADIUS, slices, shapeSetIndex);
    }

    /**
     * Legacy: compute a deterministic 64-bit seed from (UUID + secret[0..128]) using SHA-256.
     */
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

    /**
     * Secret-only seed derivation.
     *
     * Deterministic:
     *  - Same secret => same seed.
     *
     * Slices + shapeSetIndex are NOT mixed into the seed on purpose; they are
     * supplied separately to the generator and stored separately in NBT.
     */
    // -------------------------------------------------------------------------
// Secret-only seed derivation (new v3):
// Secret -> SHA256 -> Seed
// No slices, no shapeSetIndex used in hashing.
// -------------------------------------------------------------------------
    public static long computeSeedFromSecretOnly(@NotNull String secret) {
        String trimmed = secret;
        if (trimmed.length() > MAX_SECRET_LENGTH) {
            trimmed = trimmed.substring(0, MAX_SECRET_LENGTH);
        }

        // Domain separation ensures secrets can't collide with legacy UUID+secret path.
        String input = trimmed + "|featheredfriend_secret_v3";

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.wrap(hash);
            long seed = buf.getLong();

            LOG.debug("[SealSigilGenerator] computeSeedFromSecretOnly(v3): secretPreview='{}' len={} -> seed={}",
                    safeSecretPreview(trimmed), trimmed.length(), seed);

            return seed;
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] computeSeedFromSecretOnly(v3) failed, fallback to hashCode()", t);
            return input.hashCode();
        }
    }

    /**
     * Main generator entry point for external callers (e.g. preview / GUI).
     *
     * @param seed          64-bit deterministic seed
     * @param radius        requested radius (will be clamped to >= 16)
     * @param slices        number of symmetry slices (2–8)
     * @param shapeSetIndex index of shape set (0 => Medieval0, 1 => HighFantasy1, 2 => Floral2)
     */
    public static @NotNull SigilPattern generateFromSeed(long seed,
                                                         int radius,
                                                         int slices,
                                                         int shapeSetIndex) {
        if (radius < 16) {
            radius = 16;
        }

        if (slices < MIN_SLICES) {
            LOG.warn("[SealSigilGenerator] Requested slices={} < MIN_SLICES={}, clamping",
                    slices, MIN_SLICES);
            slices = MIN_SLICES;
        } else if (slices > MAX_SLICES) {
            LOG.warn("[SealSigilGenerator] Requested slices={} > MAX_SLICES={}, clamping",
                    slices, MAX_SLICES);
            slices = MAX_SLICES;
        }

        int size = radius * 2 + 1;
        int cx = radius;
        int cy = radius;
        double radiusSq = radius * radius;

        boolean[][] finalPixels = new boolean[size][size];
        RandomSource rng = RandomSource.create(seed);

        try {
            LOG.debug("[SealSigilGenerator] generateFromSeed: seed={} radius={} size={} slices={} shapeSetIndex={}",
                    seed, radius, size, slices, shapeSetIndex);

            // 1) Build wavy boundary
            boolean[][] boundaryMask = new boolean[size][size];
            fillWavyBoundary(boundaryMask, cx, cy, radius, rng);

            // 2) Generate shapes into the base slice mask
            boolean[][] slicePixels = new boolean[size][size];

            SealSigilShapeSet shapeSet = resolveShapeSet(shapeSetIndex);
            try {
                shapeSet.applyShapesInSlice(slicePixels, cx, cy, radius, radiusSq, slices, 0, rng);
            } catch (Throwable t) {
                LOG.error("[SealSigilGenerator] ShapeSet generation failed for index={}", shapeSetIndex, t);
            }

            // 3) Restrict shapes to the base slice [0, 2π/slices)
            restrictToBaseSlice(slicePixels, cx, cy, slices);

            // 4) Replicate slice into all slices => shapeMask
            boolean[][] shapeMask = new boolean[size][size];
            replicateSliceIntoAllSlices(slicePixels, shapeMask, cx, cy, slices);

            // 4b) Cleanup mask
            cleanupShapeMask(shapeMask);

            // 5) Subtract shapes from boundary
            int carvedCount = 0;
            int insideBoundary = 0;
            for (int y = 0; y < size; y++) {
                boolean[] boundaryRow = boundaryMask[y];
                boolean[] shapeRow = shapeMask[y];
                boolean[] outRow = finalPixels[y];
                for (int x = 0; x < size; x++) {
                    if (boundaryRow[x]) {
                        insideBoundary++;
                        boolean carved = shapeRow[x];
                        if (!carved) {
                            outRow[x] = true;
                        } else {
                            carvedCount++;
                        }
                    }
                }
            }

            double coverage = insideBoundary == 0 ? 0.0 : (insideBoundary - carvedCount) / (double) insideBoundary;
            LOG.debug("[SealSigilGenerator] final coverage after extrusion: {} (inside={} carved={})",
                    coverage, insideBoundary, carvedCount);

        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] generateFromSeed failed (seed={}, radius={}, slices={}, shapeSetIndex={}), using fallback dot",
                    seed, radius, slices, shapeSetIndex, t);
            try {
                int cxSafe = Math.max(0, Math.min(size - 1, cx));
                int cySafe = Math.max(0, Math.min(size - 1, cy));
                finalPixels[cySafe][cxSafe] = true;
            } catch (Throwable ignored) {
                // swallow
            }
        }

        return new SigilPattern(size, finalPixels, seed, slices, shapeSetIndex);
    }

    // -------------------------------------------------------------------------
    // Shape set resolution
    // -------------------------------------------------------------------------

    private static @NotNull SealSigilShapeSet resolveShapeSet(int shapeSetIndex) {
        try {
            return switch (shapeSetIndex) {
                case 0 -> {
                    LOG.debug("[SealSigilGenerator] Using shape set 0: SealSigilShapeSetMedieval0");
                    yield new SealSigilShapeSetMedieval0();
                }
                case 1 -> {
                    LOG.debug("[SealSigilGenerator] Using shape set 1: SealSigilShapeSetHighFantasy1");
                    yield new SealSigilShapeSetHighFantasy1();
                }
                case 2 -> {
                    LOG.debug("[SealSigilGenerator] Using shape set 2: SealSigilShapeSetFloral2");
                    yield new SealSigilShapeSetFloral2();
                }
                default -> {
                    LOG.warn("[SealSigilGenerator] Unknown shapeSetIndex={} — falling back to Medieval0", shapeSetIndex);
                    yield new SealSigilShapeSetMedieval0();
                }
            };
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] resolveShapeSet failed for index={}, falling back to Medieval0", shapeSetIndex, t);
            return new SealSigilShapeSetMedieval0();
        }
    }

    // -------------------------------------------------------------------------
    // Wavy boundary generation
    // -------------------------------------------------------------------------

    private static void fillWavyBoundary(boolean[][] mask,
                                         int cx,
                                         int cy,
                                         int radius,
                                         @NotNull RandomSource rng) {
        try {
            int size = mask.length;

            int radialSamples = 3072;
            double[] radialR = new double[radialSamples];

            double twoPi = Math.PI * 2.0;
            double angleStep = twoPi / radialSamples;

            // Make the disc much closer to a true circle:
            //  - minR very close to radius
            //  - small random-walk step size
            // This keeps only a subtle "hand-poured wax" wobble instead of a big splatter.
            double maxR = radius - 1.0;
            double minR = radius * 0.95; // was 0.80 => too blobby / splattery
            double currentR = (maxR + minR) * 0.5;

            // Step size controls how jagged the boundary is.
            // Lower value => smoother, more circular.
            double maxStep = 0.12; // was 0.35 => significantly reduced jaggedness

            double minSeen = Double.POSITIVE_INFINITY;
            double maxSeen = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < radialSamples; i++) {
                double delta = (rng.nextDouble() - 0.5) * 2.0 * maxStep;
                currentR += delta;
                if (currentR < minR) currentR = minR;
                if (currentR > maxR) currentR = maxR;
                radialR[i] = currentR;

                if (currentR < minSeen) minSeen = currentR;
                if (currentR > maxSeen) maxSeen = currentR;
            }

            int smoothRadius = 9;
            int smoothPasses = 4;
            double[] temp = new double[radialSamples];

            for (int pass = 0; pass < smoothPasses; pass++) {
                for (int i = 0; i < radialSamples; i++) {
                    double sum = 0.0;
                    int count = 0;
                    for (int k = -smoothRadius; k <= smoothRadius; k++) {
                        int j = i + k;
                        if (j < 0) j += radialSamples;
                        if (j >= radialSamples) j -= radialSamples;
                        sum += radialR[j];
                        count++;
                    }
                    temp[i] = sum / (double) count;
                }
                double[] swap = radialR;
                radialR = temp;
                temp = swap;
            }

            for (int i = 0; i < radialSamples; i++) {
                int i0 = (i - 1 + radialSamples) % radialSamples;
                int i1 = i;
                int i2 = (i + 1) % radialSamples;
                temp[i] = 0.25 * radialR[i0] + 0.5 * radialR[i1] + 0.25 * radialR[i2];
            }
            double[] swap = radialR;
            radialR = temp;
            temp = swap;

            double finalMin = Double.POSITIVE_INFINITY;
            double finalMax = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < radialSamples; i++) {
                if (radialR[i] < finalMin) finalMin = radialR[i];
                if (radialR[i] > finalMax) finalMax = radialR[i];
            }

            double maxRSq = maxR * maxR;

            int minY = Math.max(0, cy - radius);
            int maxY = Math.min(size - 1, cy + radius);
            int minX = Math.max(0, cx - radius);
            int maxX = Math.min(size - 1, cx + radius);

            for (int y = minY; y <= maxY; y++) {
                int dy = y - cy;
                int dySq = dy * dy;
                for (int x = minX; x <= maxX; x++) {
                    int dx = x - cx;
                    int dxSq = dx * dx;
                    int d2 = dxSq + dySq;
                    if (d2 > maxRSq) {
                        continue;
                    }

                    double angle = Math.atan2(dy, dx);
                    if (angle < 0) {
                        angle += twoPi;
                    }

                    double sampleIndex = angle / angleStep;
                    int idx0 = (int) Math.floor(sampleIndex);
                    double frac = sampleIndex - idx0;
                    if (idx0 < 0) {
                        idx0 = 0;
                        frac = 0.0;
                    }
                    if (idx0 >= radialSamples) {
                        idx0 = radialSamples - 1;
                        frac = 0.0;
                    }
                    int idx1 = idx0 + 1;
                    if (idx1 >= radialSamples) {
                        idx1 = 0;
                    }

                    double r0 = radialR[idx0];
                    double r1 = radialR[idx1];
                    double allowedR = r0 + (r1 - r0) * frac;
                    double allowedRSq = allowedR * allowedR;

                    if (d2 <= allowedRSq) {
                        mask[y][x] = true;
                    }
                }
            }

            LOG.debug("[SealSigilGenerator] fillWavyBoundary: radius={} rawMinR={} rawMaxR={} finalMinR={} finalMaxR={} samples={} passes={} smoothRadius={}",
                    radius, minSeen, maxSeen, finalMin, finalMax, radialSamples, smoothPasses, smoothRadius);

        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] fillWavyBoundary failed, falling back to simple disc", t);
            try {
                fillFallbackDisc(mask, cx, cy, radius);
            } catch (Throwable ignored) {
                // swallow
            }
        }
    }

    private static void fillFallbackDisc(boolean[][] mask, int cx, int cy, int radius) {
        int size = mask.length;
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
                    mask[y][x] = true;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Slice restriction + replication
    // -------------------------------------------------------------------------

    private static void restrictToBaseSlice(boolean[][] slicePixels,
                                            int cx,
                                            int cy,
                                            int slices) {
        try {
            int size = slicePixels.length;
            double twoPi = Math.PI * 2.0;
            double sliceAngle = twoPi / Math.max(1, slices);

            int kept = 0;
            int cleared = 0;

            for (int y = 0; y < size; y++) {
                boolean[] row = slicePixels[y];
                for (int x = 0; x < size; x++) {
                    if (!row[x]) continue;

                    int dx = x - cx;
                    int dy = y - cy;
                    if (dx == 0 && dy == 0) {
                        kept++;
                        continue;
                    }

                    double angle = Math.atan2(dy, dx);
                    if (angle < 0) {
                        angle += twoPi;
                    }

                    if (angle < 0.0 || angle >= sliceAngle) {
                        row[x] = false;
                        cleared++;
                    } else {
                        kept++;
                    }
                }
            }

            LOG.debug("[SealSigilGenerator] restrictToBaseSlice: slices={} kept={} cleared={}",
                    slices, kept, cleared);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] restrictToBaseSlice failed", t);
        }
    }

    private static void replicateSliceIntoAllSlices(boolean[][] slicePixels,
                                                    boolean[][] targetMask,
                                                    int cx,
                                                    int cy,
                                                    int slices) {
        try {
            int size = slicePixels.length;
            double twoPi = Math.PI * 2.0;
            double sliceAngle = twoPi / Math.max(1, slices);

            int baseCount = 0;
            int writtenCount = 0;

            for (int y = 0; y < size; y++) {
                boolean[] row = slicePixels[y];
                for (int x = 0; x < size; x++) {
                    if (!row[x]) continue;

                    baseCount++;

                    int dx = x - cx;
                    int dy = y - cy;
                    double r = Math.sqrt(dx * dx + dy * dy);
                    if (r < 1e-3) {
                        for (int k = 0; k < slices; k++) {
                            if (cx >= 0 && cy >= 0 && cx < size && cy < size) {
                                if (!targetMask[cy][cx]) {
                                    targetMask[cy][cx] = true;
                                    writtenCount++;
                                }
                            }
                        }
                        continue;
                    }

                    double angle = Math.atan2(dy, dx);
                    if (angle < 0) {
                        angle += twoPi;
                    }

                    for (int k = 0; k < slices; k++) {
                        double a = angle + k * sliceAngle;
                        if (a >= twoPi) {
                            a -= twoPi;
                        }

                        int tx = cx + (int) Math.round(Math.cos(a) * r);
                        int ty = cy + (int) Math.round(Math.sin(a) * r);

                        if (tx >= 0 && ty >= 0 && tx < size && ty < size) {
                            if (!targetMask[ty][tx]) {
                                targetMask[ty][tx] = true;
                                writtenCount++;
                            }
                        }
                    }
                }
            }

            LOG.debug("[SealSigilGenerator] replicateSliceIntoAllSlices: slices={} basePixels={} writtenPixels={}",
                    slices, baseCount, writtenCount);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] replicateSliceIntoAllSlices failed", t);
        }
    }

    private static void cleanupShapeMask(boolean[][] mask) {
        try {
            int size = mask.length;
            if (size == 0) return;

            boolean[][] original = new boolean[size][size];
            for (int y = 0; y < size; y++) {
                System.arraycopy(mask[y], 0, original[y], 0, size);
            }

            int filled = 0;
            int removed = 0;

            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    boolean cur = original[y][x];

                    int neighborsTrue = 0;
                    for (int oy = -1; oy <= 1; oy++) {
                        int ny = y + oy;
                        if (ny < 0 || ny >= size) continue;
                        for (int ox = -1; ox <= 1; ox++) {
                            int nx = x + ox;
                            if (nx < 0 || nx >= size) continue;
                            if (ox == 0 && oy == 0) continue;
                            if (original[ny][nx]) neighborsTrue++;
                        }
                    }

                    if (cur) {
                        if (neighborsTrue <= 1) {
                            mask[y][x] = false;
                            removed++;
                        }
                    } else {
                        if (neighborsTrue >= 5) {
                            mask[y][x] = true;
                            filled++;
                        }
                    }
                }
            }

            LOG.debug("[SealSigilGenerator] cleanupShapeMask: filledHoles={} removedSpecks={}", filled, removed);
        } catch (Throwable t) {
            LOG.error("[SealSigilGenerator] cleanupShapeMask failed", t);
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
        private final int slices;
        private final int shapeSetIndex;

        public SigilPattern(int size,
                            boolean[][] pixels,
                            long seed) {
            this(size, pixels, seed, 0, 0);
        }

        public SigilPattern(int size,
                            boolean[][] pixels,
                            long seed,
                            int slices,
                            int shapeSetIndex) {
            this.size = size;
            this.pixels = pixels;
            this.seed = seed;
            this.slices = slices;
            this.shapeSetIndex = shapeSetIndex;
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

        public int getSlices() {
            return slices;
        }

        public int getShapeSetIndex() {
            return shapeSetIndex;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("SigilPattern{size=")
                    .append(size)
                    .append(", seed=")
                    .append(seed)
                    .append(", slices=")
                    .append(slices)
                    .append(", shapeSetIndex=")
                    .append(shapeSetIndex)
                    .append("}\n");
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
