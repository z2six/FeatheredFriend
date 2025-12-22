// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/FeatherParticles.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/FeatherParticles.java
 *
 * Client-side feather burst FX helper.
 *
 * Usage from taming FX:
 *   - Server: send a single FEATHER particle:
 *       serverLevel.sendParticles(FFNeoForgeParticles.FEATHER.get(), x, y, z, 1, 0, 0, 0, 0);
 *   - Client: FEATHER provider (FFClientParticles) calls:
 *       FeatherParticles.spawnFeatherBurst(x, y, z);
 *
 * Behavior:
 *   - Spawns FEATHERS_PER_BURST particles around the given point.
 *   - Each particle:
 *       * Starts within SPAWN_RADIUS of the center (XZ disc).
 *       * Gets an initial outward horizontal velocity (away from the center).
 *       * Gets an initial upward kick (INITIAL_UPWARD_SPEED).
 *       * Has custom gravity & drag so it arcs downward nicely.
 *       * Rotates back and forth in roll between ±FEATHER_MAX_SWING_DEG.
 *       * Fades alpha smoothly from FEATHER_ALPHA_START to FEATHER_ALPHA_END
 *         over its lifetime.
 *
 * Texture:
 *   - Uses the sprite at: assets/featheredfriend/textures/particle/feather.png
 *   - Particle JSON: assets/featheredfriend/particles/feather.json
 *     with: "featheredfriend:feather"
 *   - Sprite is fetched from the PARTICLE atlas (TextureAtlas.LOCATION_PARTICLES).
 */
public final class FeatherParticles {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Tweakable knobs
    // ---------------------------------------------------------------------

    /** How many feathers per "burst anchor" (one FEATHER particle). */
    public static int FEATHERS_PER_BURST = 4;

    /** Horizontal radius around the center where feathers can spawn. */
    public static double SPAWN_RADIUS = 0.6D;

    /** Vertical spawn jitter around the given Y. */
    public static double SPAWN_Y_JITTER = 0.15D;

    /** Base outward (horizontal) speed from the center. */
    public static double BASE_OUTWARD_SPEED = 0.26D;

    /** Random factor on outward speed (0.0..OUTWARD_SPEED_RANDOM_FACTOR). */
    public static double OUTWARD_SPEED_RANDOM_FACTOR = 0.05D;

    /** Initial upward speed for each feather. */
    public static double INITIAL_UPWARD_SPEED = 0.08D;

    /** Per-tick artificial gravity applied to feathers. */
    public static double FEATHER_GRAVITY_PER_TICK = 0.015D;

    /** Horizontal drag factor (applied to X/Z velocity each tick). */
    public static double HORIZONTAL_DRAG = 0.92D;

    /** Vertical drag factor (applied to Y velocity each tick, after gravity). */
    public static double VERTICAL_DRAG = 0.96D;

    /** Minimum lifetime in ticks. */
    public static int FEATHER_LIFETIME_MIN_TICKS = 3;

    /** Maximum lifetime in ticks. */
    public static int FEATHER_LIFETIME_MAX_TICKS = 8;

    /** Base scale for the feather quad. */
    public static float FEATHER_BASE_SCALE = 0.75F;

    /** Random +/- variation on scale. */
    public static float FEATHER_SCALE_VARIATION = 0.05F;

    /** Max swing in degrees for roll (± this value). */
    public static float FEATHER_MAX_SWING_DEG = 90.0F;

    /** Swing speed in degrees per tick (controls how fast it rocks). */
    public static float FEATHER_SWING_SPEED_DEG_PER_TICK = 26.0F;

    /** Starting alpha for each feather (1.0 = fully visible). */
    public static float FEATHER_ALPHA_START = 1.0F;

    /** Ending alpha for each feather (0.0 = fully transparent). */
    public static float FEATHER_ALPHA_END = 0.0F;

    /**
     * Resource location for the feather sprite in the particle atlas.
     *
     * Texture file: assets/featheredfriend/textures/particle/feather.png
     * JSON:        assets/featheredfriend/particles/feather.json
     * JSON entry:  "featheredfriend:feather"
     */
    private static final ResourceLocation FEATHER_SPRITE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "particle/feather");

    private FeatherParticles() {
        // no instances
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    public static void spawnFeatherBurst(Vec3 center) {
        if (center == null) return;
        spawnFeatherBurst(center.x, center.y, center.z);
    }

    public static void spawnFeatherBurst(double x, double y, double z) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            ClientLevel level = mc.level;
            if (level == null) return;

            ParticleEngine engine = mc.particleEngine;
            if (engine == null) return;

            RandomSource rnd = level.random;

            int count = Math.max(1, FEATHERS_PER_BURST);
            for (int i = 0; i < count; i++) {
                spawnSingleFeather(engine, level, rnd, x, y, z);
            }

            // Info-level so you can actually see this in your log by default.
            LOG.info("[FeatherParticles] spawnFeatherBurst at ({}, {}, {}) count={}",
                    String.format("%.2f", x),
                    String.format("%.2f", y),
                    String.format("%.2f", z),
                    count);

        } catch (Throwable t) {
            LOG.error("[FeatherParticles] spawnFeatherBurst failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private static void spawnSingleFeather(ParticleEngine engine,
                                           ClientLevel level,
                                           RandomSource rnd,
                                           double cx,
                                           double cy,
                                           double cz) {
        try {
            // Random point in a disc around the center
            double angle = rnd.nextDouble() * (Math.PI * 2.0D);
            double radius = rnd.nextDouble() * SPAWN_RADIUS;

            double px = cx + Math.cos(angle) * radius;
            double pz = cz + Math.sin(angle) * radius;
            double py = cy + (rnd.nextDouble() * 2.0D - 1.0D) * SPAWN_Y_JITTER;

            // Outward direction from center -> spawn position
            double dirX = px - cx;
            double dirZ = pz - cz;
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len < 1.0E-4D) {
                // Degenerate case: pick a random direction
                double tmpAngle = rnd.nextDouble() * (Math.PI * 2.0D);
                dirX = Math.cos(tmpAngle);
                dirZ = Math.sin(tmpAngle);
                len = 1.0D;
            }
            dirX /= len;
            dirZ /= len;

            // Outward + upward motion
            double outwardBase = BASE_OUTWARD_SPEED;
            double outwardJitter = OUTWARD_SPEED_RANDOM_FACTOR * rnd.nextDouble();
            double outward = outwardBase + outwardJitter;

            double vx = dirX * outward;
            double vz = dirZ * outward;
            double vy = INITIAL_UPWARD_SPEED * (0.75D + rnd.nextDouble() * 0.5D);

            FeatherParticle particle = FeatherParticle.create(level, px, py, pz, vx, vy, vz);
            if (particle == null) {
                return;
            }

            addParticle(engine, particle);

        } catch (Throwable t) {
            if (level.getGameTime() % 100L == 0L) {
                LOG.warn("[FeatherParticles] spawnSingleFeather failed safely: {}", t.toString());
            }
        }
    }

    /**
     * Reflective call into ParticleEngine.add(Particle).
     * If this fails, the particle is silently dropped; the effect still works,
     * just with fewer feathers.
     */
    private static void addParticle(ParticleEngine engine, Particle particle) {
        try {
            if (engine == null || particle == null) return;

            Method m = ParticleEngine.class.getDeclaredMethod("add", Particle.class);
            m.setAccessible(true);
            m.invoke(engine, particle);

        } catch (Throwable t) {
            // Do not spam logs; keep this quiet except occasionally.
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = (mc != null) ? mc.level : null;
            long time = (level != null) ? level.getGameTime() : 0L;

            if (time % 200L == 0L) {
                LOG.warn("[FeatherParticles] Failed to add feather particle via reflection (will silently drop some feathers)", t);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Inner particle class
    // ---------------------------------------------------------------------

    /**
     * Custom feather particle:
     *  - Uses FEATHER_SPRITE from the particle atlas.
     *  - Applies manual gravity & drag.
     *  - Rolls back and forth between ±FEATHER_MAX_SWING_DEG using a sine wave.
     *  - Fades alpha smoothly from FEATHER_ALPHA_START to FEATHER_ALPHA_END.
     */
    public static final class FeatherParticle extends TextureSheetParticle {

        private final float maxSwingRad;
        private final float swingSpeedRadPerTick;

        private FeatherParticle(ClientLevel level,
                                double x, double y, double z,
                                double vx, double vy, double vz,
                                TextureAtlasSprite sprite,
                                RandomSource rnd) {
            super(level, x, y, z, vx, vy, vz);

            this.setSprite(sprite);

            this.xd = vx;
            this.yd = vy;
            this.zd = vz;

            this.quadSize = FEATHER_BASE_SCALE
                    + (float) ((rnd.nextDouble() * 2.0D - 1.0D) * FEATHER_SCALE_VARIATION);

            this.lifetime = Mth.nextInt(rnd, FEATHER_LIFETIME_MIN_TICKS, FEATHER_LIFETIME_MAX_TICKS);
            this.gravity = 0.0F; // we manage gravity ourselves

            this.roll = 0.0F;
            this.oRoll = 0.0F;

            this.maxSwingRad = (float) (FEATHER_MAX_SWING_DEG * (Math.PI / 180.0D));
            this.swingSpeedRadPerTick = (float) (FEATHER_SWING_SPEED_DEG_PER_TICK * (Math.PI / 180.0D));

            // Start fully visible (configurable).
            this.setAlpha(FEATHER_ALPHA_START);
        }

        /**
         * Factory method used by FeatherParticles helper.
         */
        @Nullable
        public static FeatherParticle create(ClientLevel level,
                                             double x, double y, double z,
                                             double vx, double vy, double vz) {
            try {
                if (level == null) return null;

                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return null;

                // Use the PARTICLE atlas, not the BLOCK atlas.
                TextureAtlasSprite sprite =
                        mc.getTextureAtlas(TextureAtlas.LOCATION_PARTICLES).apply(FEATHER_SPRITE);

                if (sprite == null) {
                    if (level.getGameTime() % 200L == 0L) {
                        LOG.warn("[FeatherParticles] Feather sprite {} not found in PARTICLE atlas", FEATHER_SPRITE);
                    }
                    return null;
                }

                RandomSource rnd = level.random;
                return new FeatherParticle(level, x, y, z, vx, vy, vz, sprite, rnd);

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

                // Swing roll back and forth using a sine wave.
                float phase = this.age * this.swingSpeedRadPerTick;
                this.roll = this.maxSwingRad * Mth.sin(phase);

                // Apply gravity & drag.
                this.xd *= HORIZONTAL_DRAG;
                this.zd *= HORIZONTAL_DRAG;

                this.yd -= FEATHER_GRAVITY_PER_TICK;
                this.yd *= VERTICAL_DRAG;

                this.move(this.xd, this.yd, this.zd);

                // Alpha fade: 0..lifetime-1 mapped to [FEATHER_ALPHA_START..FEATHER_ALPHA_END].
                float t = (float) this.age / (float) this.lifetime;
                t = Mth.clamp(t, 0.0F, 1.0F);
                float alpha = Mth.lerp(t, FEATHER_ALPHA_START, FEATHER_ALPHA_END);
                this.setAlpha(alpha);

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
