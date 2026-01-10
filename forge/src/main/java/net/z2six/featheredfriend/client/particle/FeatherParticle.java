// MainFile: forge/src/main/java/net/z2six/featheredfriend/client/particle/FeatherParticle.java
package net.z2six.featheredfriend.client.particle;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Client-side raven feather particle.
 *
 * Registered for: featheredfriend:feather (SimpleParticleType)
 *
 * Behavior:
 *  - Spawns around a center with a small radius.
 *  - Outward + upward initial motion.
 *  - Custom gravity + drag.
 *  - Swinging roll between ±FEATHER_MAX_SWING_DEG using a sine wave.
 */
public final class FeatherParticle extends TextureSheetParticle {

    private static final Logger LOG = LogUtils.getLogger();

    // Tweakable knobs (client-only; safe defaults)
    private static final double SPAWN_RADIUS = 0.6D;
    private static final double SPAWN_Y_JITTER = 0.15D;

    private static final double BASE_OUTWARD_SPEED = 0.06D;
    private static final double OUTWARD_SPEED_RANDOM_FACTOR = 0.05D;

    private static final double INITIAL_UPWARD_SPEED = 0.08D;

    private static final double FEATHER_GRAVITY_PER_TICK = 0.015D;
    private static final double HORIZONTAL_DRAG = 0.92D;
    private static final double VERTICAL_DRAG = 0.96D;

    private static final int FEATHER_LIFETIME_MIN_TICKS = 26;
    private static final int FEATHER_LIFETIME_MAX_TICKS = 42;

    private static final float FEATHER_BASE_SCALE = 0.18F;
    private static final float FEATHER_SCALE_VARIATION = 0.05F;

    private static final float FEATHER_MAX_SWING_DEG = 90.0F;
    private static final float FEATHER_SWING_SPEED_DEG_PER_TICK = 7.0F;

    private final SpriteSet sprites;
    private final float maxSwingRad;
    private final float swingSpeedRadPerTick;

    private FeatherParticle(ClientLevel level,
                            double x, double y, double z,
                            double dx, double dy, double dz,
                            SpriteSet sprites) {
        super(level, x, y, z, dx, dy, dz);
        this.sprites = sprites;

        // We treat (x,y,z) as the "center" of the burst and randomize around it.
        RandomSource rnd = level.random;

        // Random point in disc around center
        double angle = rnd.nextDouble() * (Math.PI * 2.0D);
        double radius = rnd.nextDouble() * SPAWN_RADIUS;

        Vec3 center = new Vec3(x, y, z);
        double px = center.x + Math.cos(angle) * radius;
        double pz = center.z + Math.sin(angle) * radius;
        double py = center.y + (rnd.nextDouble() * 2.0D - 1.0D) * SPAWN_Y_JITTER;

        this.setPos(px, py, pz);

        // Outward direction from center -> position
        double dirX = px - center.x;
        double dirZ = pz - center.z;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1.0E-4D) {
            double tmpAngle = rnd.nextDouble() * (Math.PI * 2.0D);
            dirX = Math.cos(tmpAngle);
            dirZ = Math.sin(tmpAngle);
            len = 1.0D;
        }
        dirX /= len;
        dirZ /= len;

        double outwardBase = BASE_OUTWARD_SPEED;
        double outwardJitter = OUTWARD_SPEED_RANDOM_FACTOR * rnd.nextDouble();
        double outward = outwardBase + outwardJitter;

        this.xd = dirX * outward;
        this.zd = dirZ * outward;
        this.yd = INITIAL_UPWARD_SPEED * (0.75D + rnd.nextDouble() * 0.5D);

        this.quadSize = FEATHER_BASE_SCALE
                + (float) ((rnd.nextDouble() * 2.0D - 1.0D) * FEATHER_SCALE_VARIATION);

        this.lifetime = Mth.nextInt(rnd, FEATHER_LIFETIME_MIN_TICKS, FEATHER_LIFETIME_MAX_TICKS);
        this.gravity = 0.0F; // custom gravity

        this.roll = 0.0F;
        this.oRoll = 0.0F;

        this.maxSwingRad = (float) (FEATHER_MAX_SWING_DEG * (Math.PI / 180.0D));
        this.swingSpeedRadPerTick = (float) (FEATHER_SWING_SPEED_DEG_PER_TICK * (Math.PI / 180.0D));

        // Use the first sprite in our sprite set.
        this.setSpriteFromAge(this.sprites);
    }

    @Override
    public void tick() {
        try {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            this.oRoll = this.roll;

            this.age++;
            if (this.age >= this.lifetime) {
                this.remove();
                return;
            }

            // Swing roll back and forth using a sine wave.
            float phase = this.age * this.swingSpeedRadPerTick;
            this.roll = this.maxSwingRad * Mth.sin(phase);

            // Apply gravity & drag.
            this.xd *= HORIZONTAL_DRAG;
            this.zd *= HORIZONTAL_DRAG;

            this.yd -= FEATHER_GRAVITY_PER_TICK;
            this.yd *= VERTICAL_DRAG;

            this.move(this.xd, this.yd, this.zd);

            // If you ever add multiple frames in the JSON, this will animate them:
            this.setSpriteFromAge(this.sprites);

        } catch (Throwable t) {
            if (this.level != null && this.level.getGameTime() % 200L == 0L) {
                LOG.warn("[FeatherParticle] tick failed safely: {}", t.toString());
            }
            this.remove();
        }
    }

    @Override
    public net.minecraft.client.particle.ParticleRenderType getRenderType() {
        return net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    /**
     * Factory wired from FFClientParticles via RegisterParticleProvidersEvent.
     */
    public static final class Provider implements ParticleProvider<net.minecraft.core.particles.SimpleParticleType> {

        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(net.minecraft.core.particles.SimpleParticleType type,
                                       ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            try {
                return new FeatherParticle(level, x, y, z, dx, dy, dz, this.sprites);
            } catch (Throwable t) {
                if (level != null && level.getGameTime() % 200L == 0L) {
                    LOG.warn("[FeatherParticle.Provider] createParticle failed safely: {}", t.toString());
                }
                return null;
            }
        }
    }
}
