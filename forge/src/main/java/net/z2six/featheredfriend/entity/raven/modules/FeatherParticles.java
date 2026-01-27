// MainFile: forge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/FeatherParticles.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * forge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/FeatherParticles.java
 *
 * Client-side feather particle implementation and tunables.
 *
 * Design:
 * - The *server* only ever spawns the registered FEATHER particle type:
 *
 *   serverLevel.sendParticles(
 *       FFForgeParticles.FEATHER.get(),
 *       x, y, z,                                 // treated as the CENTER for this burst
 *       FeatherParticles.getFeathersPerBurst(),  // number of feathers
 *       0.0D, 0.0D, 0.0D,                        // spread: we ignore it on the client
 *       0.0D                                     // speed: we also ignore this
 *   );
 *
 * - On the *client*, the FEATHER particle provider created here:
 *     - Treats (x, y, z) as the "center location".
 *     - For each incoming FEATHER spawn, it:
 *         * Picks a random point on a small sphere around that center.
 *         * Uses that offset vector (center -> featherPos) to:
 *             - Place the feather on the sphere surface.
 *             - Compute an initial velocity pointing directly AWAY from the center,
 *               i.e. hurling "outwards" from the raven.
 *             - Adds an upward bonus so it pops out then slowly arcs downward.
 *         * Sets up lifetime, gravity/drag, rocking roll, and alpha fade.
 *
 * Extra:
 * - Each feather now spawns with a randomized initial roll and a randomized
 *   swing phase offset, so they do NOT all rock in sync or share the same
 *   initial orientation.
 */
public final class FeatherParticles {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Tweakable knobs
    // ---------------------------------------------------------------------

    /** How many feathers per burst (server should use this as the count). */
    public static int FEATHERS_PER_BURST = 12;

    /** Radius of the spawn sphere around the given center (in blocks). */
    public static double SPAWN_RADIUS = 0.45D;

    /**
     * Fractional jitter applied to SPAWN_RADIUS:
     * finalRadius = SPAWN_RADIUS * (1.0 +/- SPAWN_RADIUS_JITTER).
     */
    public static double SPAWN_RADIUS_JITTER = 0.30D;

    /** Base radial outward speed for feathers (blocks per tick). */
    public static double BASE_OUTWARD_SPEED = 0.06D;

    /** Random +/- factor on outward speed (0.0..OUTWARD_SPEED_RANDOM_FACTOR). */
    public static double OUTWARD_SPEED_RANDOM_FACTOR = 0.04D;

    /**
     * Extra constant upward bonus added to vertical velocity so
     * feathers pop up a bit before gravity pulls them down.
     */
    public static double INITIAL_UPWARD_BONUS = 0.06D;

    /** Minimum lifetime in ticks. */
    public static int FEATHER_LIFETIME_MIN_TICKS = 18;

    /** Maximum lifetime in ticks. */
    public static int FEATHER_LIFETIME_MAX_TICKS = 28;

    /** Base scale for the feather quad. */
    public static float FEATHER_BASE_SCALE = 0.11F;

    /** Random +/- variation on scale. */
    public static float FEATHER_SCALE_VARIATION = 0.04F;

    /** Max swing in degrees for roll (± this value). */
    public static float FEATHER_MAX_SWING_DEG = 45.0F;

    /** Swing speed in degrees per tick (controls how fast it rocks). */
    public static float FEATHER_SWING_SPEED_DEG_PER_TICK = 13.0F;

    /** Per-tick artificial gravity applied to feathers. */
    public static double FEATHER_GRAVITY_PER_TICK = 0.008D;

    /** Horizontal drag factor (applied to X/Z velocity each tick). */
    public static double HORIZONTAL_DRAG = 0.94D;

    /** Vertical drag factor (applied to Y velocity each tick, after gravity). */
    public static double VERTICAL_DRAG = 0.92D;

    /** Starting alpha for a feather (0..1). */
    public static float FEATHER_ALPHA_START = 1.0F;

    /** Ending alpha for a feather (0..1). */
    public static float FEATHER_ALPHA_END = 0.0F;

    private FeatherParticles() {
        // no instances
    }

    /**
     * Expose a single knob for "how many feathers in a burst".
     * Server-side code should use this for its particle count so that
     * the amount is fully controlled from this class.
     */
    public static int getFeathersPerBurst() {
        return Math.max(1, FEATHERS_PER_BURST);
    }

    // ---------------------------------------------------------------------
    // Provider wiring
    // ---------------------------------------------------------------------

    /**
     * Factory for RegisterParticleProvidersEvent.registerSpriteSet(...).
     *
     * In FFClientParticles:
     *
     *   event.registerSpriteSet(
     *       FFForgeParticles.FEATHER.get(),
     *       FeatherParticles::createProvider
     *   );
     *
     * The incoming (x, y, z) are treated as the *center location*.
     * For each FEATHER spawn, we:
     *   - Sample a random point on a sphere around that center.
     *   - Place the feather at that point.
     *   - Give it velocity pointing directly away from the center.
     */
    public static ParticleProvider<SimpleParticleType> createProvider(SpriteSet spriteSet) {
        return (type, level, centerX, centerY, centerZ, unusedVx, unusedVy, unusedVz) -> {
            ClientLevel clientLevel = level; // level is already ClientLevel here
            RandomSource rnd = clientLevel.random;

            // Sample a random direction on a sphere (simple method).
            double theta = rnd.nextDouble() * (Math.PI * 2.0D); // azimuth [0, 2π)
            double phi = rnd.nextDouble() * Math.PI;            // polar [0, π]

            double baseRadius = SPAWN_RADIUS;
            double radiusJitterFactor = 1.0D + (rnd.nextDouble() * 2.0D - 1.0D) * SPAWN_RADIUS_JITTER;
            double radius = baseRadius * radiusJitterFactor;

            // Direction components (unit vector).
            double dirX = Math.sin(phi) * Math.cos(theta);
            double dirY = Math.cos(phi);
            double dirZ = Math.sin(phi) * Math.sin(theta);

            // Normalize just in case.
            double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            if (len < 1.0E-6D) {
                dirX = 0.0D;
                dirY = 1.0D;
                dirZ = 0.0D;
                len = 1.0D;
            }
            dirX /= len;
            dirY /= len;
            dirZ /= len;

            // Position on sphere around the center.
            double fx = centerX + dirX * radius;
            double fy = centerY + dirY * radius;
            double fz = centerZ + dirZ * radius;

            // Radial outward velocity (same direction as offset), plus upward bonus.
            double speed = BASE_OUTWARD_SPEED
                    + (rnd.nextDouble() * 2.0D - 1.0D) * OUTWARD_SPEED_RANDOM_FACTOR;
            if (speed < 0.0D) {
                speed = 0.0D;
            }

            double vx = dirX * speed;
            double vy = dirY * speed + INITIAL_UPWARD_BONUS;
            double vz = dirZ * speed;

            return FeatherParticle.create(clientLevel, fx, fy, fz, vx, vy, vz, spriteSet);
        };
    }

    // ---------------------------------------------------------------------
    // Inner particle class
    // ---------------------------------------------------------------------

    /**
     * Custom feather particle:
     *  - Uses the FEATHER sprite via SpriteSet.
     *  - Starts on a small sphere around the given center.
     *  - Initial velocity points directly AWAY from the center
     *    (hurling outwards), plus an upward bonus.
     *  - Applies manual gravity and drag.
     *  - Rolls back and forth between ±FEATHER_MAX_SWING_DEG using a sine wave.
     *  - Fades alpha from FEATHER_ALPHA_START → FEATHER_ALPHA_END over lifetime.
     *  - Has randomized initial roll and swing phase, so feathers don't rock in sync.
     */
    public static final class FeatherParticle extends TextureSheetParticle {

        private final float maxSwingRad;
        private final float swingSpeedRadPerTick;
        private final float swingPhaseOffsetRad; // per-feather random phase offset

        private FeatherParticle(ClientLevel level,
                                double x, double y, double z,
                                double vx, double vy, double vz,
                                SpriteSet sprites,
                                RandomSource rnd) {
            super(level, x, y, z, vx, vy, vz);

            this.pickSprite(sprites);

            this.xd = vx;
            this.yd = vy;
            this.zd = vz;

            this.quadSize = FEATHER_BASE_SCALE
                    + (float) ((rnd.nextDouble() * 2.0D - 1.0D) * FEATHER_SCALE_VARIATION);

            this.lifetime = Mth.nextInt(rnd, FEATHER_LIFETIME_MIN_TICKS, FEATHER_LIFETIME_MAX_TICKS);
            this.gravity = 0.0F; // we manage gravity ourselves

            // Randomize initial roll so feathers don't all start at the same angle.
            float initialRollDeg = (float) (rnd.nextDouble() * 360.0D - 180.0D); // [-180, 180]
            this.roll = (float) (initialRollDeg * (Math.PI / 180.0D));
            this.oRoll = this.roll;

            this.maxSwingRad = (float) (FEATHER_MAX_SWING_DEG * (Math.PI / 180.0D));
            this.swingSpeedRadPerTick = (float) (FEATHER_SWING_SPEED_DEG_PER_TICK * (Math.PI / 180.0D));

            // Random swing phase offset so they rock out of phase.
            this.swingPhaseOffsetRad = (float) (rnd.nextDouble() * Math.PI * 2.0D); // [0, 2π)

            this.alpha = FEATHER_ALPHA_START;

            // Occasional debug so you can confirm creation without spam.
            if (level.getGameTime() % 80L == 0L) {
                LOG.debug("[FeatherParticles] Created feather particle at ({}, {}, {}) alpha={} initialRollDeg={}",
                        String.format("%.2f", x),
                        String.format("%.2f", y),
                        String.format("%.2f", z),
                        this.alpha,
                        initialRollDeg);
            }
        }

        /**
         * Factory used from the provider.
         */
        @Nullable
        public static FeatherParticle create(ClientLevel level,
                                             double x, double y, double z,
                                             double vx, double vy, double vz,
                                             SpriteSet sprites) {
            try {
                if (level == null) return null;
                RandomSource rnd = level.random;
                return new FeatherParticle(level, x, y, z, vx, vy, vz, sprites, rnd);
            } catch (Throwable t) {
                if (level != null && level.getGameTime() % 200L == 0L) {
                    LOG.warn("[FeatherParticles] FeatherParticle.create failed safely: {}", t.toString());
                }
                return null;
            }
        }

        @Override
        public void tick() {
            try {
                // Previous position
                this.xo = this.x;
                this.yo = this.y;
                this.zo = this.z;
                this.oRoll = this.roll;

                this.age++;
                if (this.age >= this.lifetime) {
                    this.remove();
                    return;
                }

                // Swing roll back and forth using a sine wave, with per-particle phase offset.
                float phase = this.age * this.swingSpeedRadPerTick + this.swingPhaseOffsetRad;
                this.roll = this.maxSwingRad * Mth.sin(phase);

                // Apply gravity & drag.
                this.xd *= HORIZONTAL_DRAG;
                this.zd *= HORIZONTAL_DRAG;

                this.yd -= FEATHER_GRAVITY_PER_TICK;
                this.yd *= VERTICAL_DRAG;

                this.move(this.xd, this.yd, this.zd);

                // Alpha fade: start → end over lifetime.
                float t = (float) this.age / (float) this.lifetime;
                t = Mth.clamp(t, 0.0F, 1.0F);
                this.alpha = Mth.lerp(t, FEATHER_ALPHA_START, FEATHER_ALPHA_END);

            } catch (Throwable t) {
                if (this.level != null && this.level.getGameTime() % 200L == 0L) {
                    LOG.warn("[FeatherParticles] FeatherParticle.tick failed safely: {}", t.toString());
                }
                this.remove();
            }
        }

        @Override
        public net.minecraft.client.particle.ParticleRenderType getRenderType() {
            // Standard translucent particle sheet.
            return net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }
    }
}
