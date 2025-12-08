package net.z2six.featheredfriend.content.seal;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * // common/src/main/java/net/z2six/featheredfriend/content/seal/SealStampCarveLogic.java
 *
 * SealStampCarveLogic
 *
 * Small client-side helper that orchestrates a short "carve" animation:
 *  - Tracks ticks since the carve started.
 *  - Fires one or more "burst" callbacks during the animation.
 *  - When finished, calls onFinished(seed, slices, style).
 *
 * Purely client-side; server only sees the final SealStampCarveResultPacket.
 */
public class SealStampCarveLogic {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Total duration of the carve animation in ticks.
     * ~1 second at 20 TPS.
     */
    private static final int TOTAL_TICKS = 20;

    /**
     * Ticks at which to fire particle bursts.
     * Adjust as desired.
     */
    private static final int[] BURST_TICKS = {2, 6, 12, 18};

    // ---------------------------------------------------------------------
    // Callback interface
    // ---------------------------------------------------------------------

    public interface Callback {
        /**
         * Called for each scheduled particle burst.
         *
         * @param burstIndex 0-based index into BURST_TICKS
         */
        void onBurst(int burstIndex);

        /**
         * Called once when the carve animation finishes.
         *
         * @param seed   sigil seed
         * @param slices number of slices
         * @param style  style index
         */
        void onFinished(long seed, int slices, int style);
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private boolean carving = false;
    private int elapsedTicks = 0;
    private int burstsFired = 0;

    private long seed = 0L;
    private int slices = 0;
    private int style = 0;

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Start a new carve animation.
     */
    public void beginCarve(long seed, int slices, int style) {
        try {
            this.carving = true;
            this.elapsedTicks = 0;
            this.burstsFired = 0;
            this.seed = seed;
            this.slices = slices;
            this.style = style;

            LOG.debug("[SealStampCarveLogic] beginCarve: seed={} slices={} style={}", seed, slices, style);
        } catch (Throwable t) {
            LOG.error("[SealStampCarveLogic] beginCarve failed", t);
            this.carving = false;
        }
    }

    /**
     * Tick the carve animation.
     *
     * @param callback callback to receive burst/finished events
     */
    public void tick(Callback callback) {
        if (!carving) {
            return;
        }
        if (callback == null) {
            LOG.warn("[SealStampCarveLogic] tick called with null callback; aborting carve");
            carving = false;
            return;
        }

        try {
            elapsedTicks++;

            // Fire bursts at the scheduled ticks
            while (burstsFired < BURST_TICKS.length && elapsedTicks >= BURST_TICKS[burstsFired]) {
                int index = burstsFired;
                burstsFired++;
                try {
                    callback.onBurst(index);
                } catch (Throwable t) {
                    LOG.error("[SealStampCarveLogic] onBurst callback failed for index={}", index, t);
                }
            }

            // End of animation
            if (elapsedTicks >= TOTAL_TICKS) {
                carving = false;
                try {
                    callback.onFinished(seed, slices, style);
                } catch (Throwable t) {
                    LOG.error("[SealStampCarveLogic] onFinished callback failed", t);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealStampCarveLogic] tick failed", t);
            carving = false;
        }
    }

    public boolean isCarving() {
        return carving;
    }

    public int getElapsedTicks() {
        return elapsedTicks;
    }
}
