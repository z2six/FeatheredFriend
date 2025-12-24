// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/particle/EnderpopParticle.java
package net.z2six.featheredfriend.client.particle;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/particle/EnderpopParticle.java
 *
 * Enderpop particle (ONE-SHOT):
 * - Uses SpriteSet animation (requires assets/modid/particles/enderpop.json listing multiple textures).
 * - Fullbright so it's visible even in shadows.
 * - Fades in/out + slight scale pop.
 *
 * NOTE:
 * SpriteSet animation is NOT a vertical spritesheet by itself.
 * It cycles sprites provided by the particle JSON (multiple textures).
 */
public class EnderpopParticle extends TextureSheetParticle {

    private static final Logger LOG = LogUtils.getLogger();

    private final SpriteSet sprites;

    // Always initialized
    private final float rollRad;

    private final int totalLifetime;
    private final float baseSize;
    private final float sizeJitter;

    protected EnderpopParticle(
            ClientLevel level,
            double x,
            double y,
            double z,
            double xd,
            double yd,
            double zd,
            int lifetimeTicks,
            SpriteSet sprites
    ) {
        super(level, x, y, z, xd, yd, zd);
        this.sprites = sprites;

        int lt = lifetimeTicks > 0 ? lifetimeTicks : 10;
        this.totalLifetime = lt;
        this.lifetime = lt;

        // Keep the particle near where it spawned (you generally want teleport pops to "stick" in space).
        // We'll still allow a tiny drift, but heavily damp it in tick().
        this.xd = xd * 0.15;
        this.yd = yd * 0.15;
        this.zd = zd * 0.15;

        this.gravity = 0.0F;
        this.friction = 0.85F;

        // Make it more visible than before
        this.baseSize = 0.55F;
        this.sizeJitter = 0.25F;

        this.quadSize = this.baseSize;

        RandomSource r = this.random != null ? this.random : RandomSource.create();
        this.rollRad = (r.nextFloat() * (float) (Math.PI * 2.0));

        // Start invisible; fade in/out
        this.alpha = 0.0F;

        try {
            this.setSpriteFromAge(this.sprites);
        } catch (Throwable t) {
            LOG.warn("[EnderpopParticle] setSpriteFromAge failed safely: {}", t.toString());
        }
    }

    @Override
    public void tick() {
        super.tick();

        try {
            // Animate sprite
            this.setSpriteFromAge(this.sprites);

            // Stable roll
            this.oRoll = this.roll;
            this.roll = this.rollRad;

            float p = (this.age) / (float) Math.max(1, this.totalLifetime);
            p = Mth.clamp(p, 0.0F, 1.0F);

            // Fade in/out
            float fadeEdge = 0.18F;
            float a;
            if (p < fadeEdge) a = p / fadeEdge;
            else if (p > 1.0F - fadeEdge) a = (1.0F - p) / fadeEdge;
            else a = 1.0F;

            // Slight “pop” scale early then settle
            // scale curve: peak around p~0.25
            float pop = 1.0F + (float) Math.sin(Mth.clamp(p / 0.5F, 0.0F, 1.0F) * (float) Math.PI) * this.sizeJitter;
            this.quadSize = this.baseSize * pop;

            this.alpha = Mth.clamp(a, 0.0F, 1.0F);

            // Hard damp motion so it stays at the teleport spot
            this.xd *= 0.60;
            this.yd *= 0.60;
            this.zd *= 0.60;

        } catch (Throwable t) {
            if (this.age % 10 == 0) {
                LOG.warn("[EnderpopParticle] tick failed safely: {}", t.toString());
            }
        }
    }

    @Override
    public int getLightColor(float partialTick) {
        // Fullbright, always.
        return LightTexture.FULL_BRIGHT;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    // ----------------------------
    // Factory
    // ----------------------------

    public static class Factory implements net.minecraft.client.particle.ParticleProvider<net.minecraft.core.particles.SimpleParticleType> {

        private final SpriteSet sprites;

        public Factory(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(
                net.minecraft.core.particles.SimpleParticleType type,
                ClientLevel level,
                double x,
                double y,
                double z,
                double xd,
                double yd,
                double zd
        ) {
            int lifetimeTicks = 10; // ~0.5s feel
            return new EnderpopParticle(level, x, y, z, xd, yd, zd, lifetimeTicks, this.sprites);
        }
    }
}
