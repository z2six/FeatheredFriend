// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilShapeSet.java
package net.z2six.featheredfriend.sigil;

import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/sigil/SealSigilShapeSet.java
 *
 * Interface for modular sigil shape sets.
 *
 * NEW SEMANTICS (aligned with SealSigilGenerator v2):
 *  - Implementations must MARK shape pixels with true in the given mask.
 *  - The generator will:
 *        * Build a solid wax disc (boundaryMask).
 *        * Build a shapeMask (union of all slices) where:
 *              shapeMask[y][x] == true  => "this pixel should be carved out of the wax"
 *        * Final pattern:
 *              final[y][x] = boundaryMask[y][x] and !shapeMask[y][x].
 *
 * Important:
 *  - Shape sets should NEVER set pixels[y][x] back to false as a way to
 *    "clear" shapes; just omit setting them to true in the first place.
 *  - Usually, you only set pixels[y][x] = true when a pixel is part of
 *    some motif.
 *
 * The generator usually:
 *  - Creates an empty slice mask (all false).
 *  - Calls applyShapesInSlice(...) for sliceIndex 0 (base slice).
 *  - Restricts that mask to the base slice wedge.
 *  - Replicates into all slices and subtracts from the boundary disc.
 */
public interface SealSigilShapeSet {

    /**
     * Mark shapes for one slice into the given pixels array.
     *
     * @param pixels     full sigil canvas (size x size). Implementations should
     *                   set pixels[y][x] = true where they want shapes to carve
     *                   wax away in that slice.
     * @param cx         center X of the seal
     * @param cy         center Y of the seal
     * @param radius     seal radius
     * @param radiusSq   radius squared (for inside-circle checks)
     * @param slices     total number of slices in the pattern
     * @param sliceIndex which slice we are carving (0..slices-1)
     * @param rng        deterministic RandomSource
     */
    void applyShapesInSlice(boolean[][] pixels,
                            int cx,
                            int cy,
                            int radius,
                            double radiusSq,
                            int slices,
                            int sliceIndex,
                            @NotNull RandomSource rng);
}
