// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SigilEtchingParticle.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

/**

 // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SigilEtchingParticle.java

 SigilEtchingParticle

 UI particle used by SealStampScreen:

 Rendered as a textured quad using texture_etching16x.png (16x16 wood snippet).

 Factory method {@link #createForCarve(RandomSource, int, int, int)} encapsulates

 all spawn logic:

 * Spawn position within the wooden disc.

 * Initial velocity with a strong bias toward the downward half of the circle,

 but with some upward launches that quickly arc down under gravity.

 * Sideways drift so chips visibly fan left/right.

 * Random size and lifetime.

 * Random initial rotation and spin speed.


 Per-tick physics (gravity, friction, fade-out, rotation) are handled here.

 The GUI class (SealStampScreen) simply tells us where the preview circle is and

 how many particles to spawn; all motion/feel tuning lives in this class.
 */
public class SigilEtchingParticle {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation PARTICLE_TEXTURE =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/texture_etching16x.png");

    private static boolean textureCheckDone = false;
    private static boolean hasTexture = false;

// ---------------------------------------------------------------------
// Tuning constants (feel of the particles)
// ---------------------------------------------------------------------

    /**

     Base particle size in pixels.
     */
    private static final float SIZE_BASE = 8.0f;

    /**

     Size variation multiplier (relative to base).

     Final size = SIZE_BASE * (0.8 + rnd * SIZE_VARIATION).
     */
    private static final float SIZE_VARIATION = 0.6f;

    /**

     Minimum and additional lifetime in ticks.

     Final lifetime = LIFETIME_MIN + rnd * LIFETIME_RANGE.
     */
    private static final int LIFETIME_MIN = 12;
    private static final int LIFETIME_RANGE = 10; // -> 12–21 ticks

    /**

     Base horizontal & vertical speed multipliers.

     We keep them in the same ballpark so diagonals are common.
     */
    private static final double BASE_SPEED = 0.5;
    private static final double SPEED_VARIATION = 0.6;

    /**

     Probability that an initial velocity vector is in the "downward half" of the circle

     (i.e. sin(theta) >= 0 in standard math coordinates, which corresponds to downward in screen Y).

     The remaining probability goes to the "upper half" (some chips bouncing up before falling).
     */
    private static final double DOWNWARD_HEMISPHERE_PROBABILITY = 0.8;

    /**

     Gravity and friction in screen-space physics.

     Gravity is strong so arcs become visible before fade-out.
     */
    private static final double GRAVITY = 0.10;
    private static final double FRICTION = 0.94;

// ---------------------------------------------------------------------
// Instance fields
// ---------------------------------------------------------------------

    // Position & velocity in screen-space (pixels)
    private double x;
    private double y;
    private double vx;
    private double vy;

    // Lifetime
    private int age;
    private final int maxAge;

    // Visuals
    private final float size;
    private float alpha;

    // Rotation
    private float angleDeg;
    private final float angularVelocityDeg;

// ---------------------------------------------------------------------
// Construction via factory
// ---------------------------------------------------------------------

    /**

     Factory for the seal-carving effect.

     @param random Random source to use (provided by GUI, but all tuning is done here).

     @param centerX Center X of the wooden disc (screen coordinates).

     @param centerY Center Y of the wooden disc (screen coordinates).

     @param radius Radius of the disc; used to spawn chips within its area.

     @return New SigilEtchingParticle or null if radius is invalid.
     */
    public static SigilEtchingParticle createForCarve(RandomSource random,
                                                      int centerX,
                                                      int centerY,
                                                      int radius) {
        try {
            if (radius <= 0) {
                return null;
            }

            // -----------------------------------------------------------------
            // Spawn position: random point inside the circle (uniform area).
            // -----------------------------------------------------------------
            double spawnAngle = random.nextDouble() * Math.PI * 2.0;
            double spawnRadius = radius * Math.sqrt(random.nextDouble());
            double x = centerX + spawnRadius * Math.cos(spawnAngle);
            double y = centerY + spawnRadius * Math.sin(spawnAngle);

            // -----------------------------------------------------------------
            // Initial velocity:
            //  - Strong bias for downward half of the circle.
            //  - Some vectors in the upper half so chips can pop up first.
            //  - Sideways component is similar magnitude to vertical, so we see
            //    clear left/right fanning.
            // -----------------------------------------------------------------
            double dirAngle;
            double roll = random.nextDouble();
            if (roll < DOWNWARD_HEMISPHERE_PROBABILITY) {
                // Bottom half (0..π in math coordinates)
                dirAngle = random.nextDouble() * Math.PI; // 0..π -> right/down/left
            } else {
                // Top half (π..2π) -> some chips launch upward before gravity wins
                dirAngle = Math.PI + random.nextDouble() * Math.PI;
            }

            // Speed: base + variation
            double speed = BASE_SPEED + random.nextDouble() * SPEED_VARIATION;

            double vx = Math.cos(dirAngle) * speed;
            double vy = Math.sin(dirAngle) * speed;

            // -----------------------------------------------------------------
            // Lifetime & size
            // -----------------------------------------------------------------
            int lifetime = LIFETIME_MIN + random.nextInt(LIFETIME_RANGE + 1);
            float size = SIZE_BASE * (0.8f + random.nextFloat() * SIZE_VARIATION);

            // -----------------------------------------------------------------
            // Rotation: random start angle + random spin speed.
            // -----------------------------------------------------------------
            float initialAngleDeg = random.nextFloat() * 360.0f;
            float angularVelocityDeg = (random.nextFloat() - 0.5f) * 40.0f; // -20..+20 deg/tick

            return new SigilEtchingParticle(
                    x,
                    y,
                    vx,
                    vy,
                    size,
                    lifetime,
                    initialAngleDeg,
                    angularVelocityDeg
            );


        } catch (Throwable t) {
            LOG.error("[SigilEtchingParticle] createForCarve failed", t);
            return null;
        }
    }

    /**

     Low-level constructor; all randomness is expected to be done by the factory methods.
     */
    private SigilEtchingParticle(double x,
                                 double y,
                                 double vx,
                                 double vy,
                                 float size,
                                 int maxAge,
                                 float initialAngleDeg,
                                 float angularVelocityDeg) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.size = size <= 0.0f ? 1.0f : size;
        this.maxAge = Math.max(1, maxAge);
        this.age = 0;
        this.alpha = 1.0f;

        this.angleDeg = initialAngleDeg;
        this.angularVelocityDeg = angularVelocityDeg;
    }

// ---------------------------------------------------------------------
// Tick & life
// ---------------------------------------------------------------------

    public void tick() {
        try {
            this.age++;
            if (this.age >= this.maxAge) {
                this.alpha = 0.0f;
                return;
            }

            // Integrate position
            this.x += this.vx;
            this.y += this.vy;

            // Physics: gravity pulls down, friction dampens
            this.vy += GRAVITY;
            this.vx *= FRICTION;
            this.vy *= FRICTION;

            // Spin
            this.angleDeg += this.angularVelocityDeg;

            // Fade out over lifetime
            float lifeFrac = this.age / (float) this.maxAge;
            this.alpha = 1.0f - lifeFrac;
            if (this.alpha < 0.0f) {
                this.alpha = 0.0f;
            }
        } catch (Throwable t) {
            LOG.error("[SigilEtchingParticle] tick failed", t);
            this.alpha = 0.0f;
        }


    }

    public boolean isAlive() {
        return this.age < this.maxAge && this.alpha > 0.01f;
    }

// ---------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------

    public void render(GuiGraphics guiGraphics, float partialTick) {
        try {
            if (!isAlive()) {
                return;
            }

            ensureTextureChecked();

            int drawSize = Math.max(1, Math.round(this.size));
            int half = drawSize / 2;

            int drawX = (int) Math.round(this.x);
            int drawY = (int) Math.round(this.y);

            float a = Math.max(0.0f, Math.min(1.0f, this.alpha));

            if (hasTexture) {
                var poseStack = guiGraphics.pose();
                poseStack.pushPose();
                poseStack.translate(drawX, drawY, 0.0f);
                poseStack.mulPose(Axis.ZP.rotationDegrees(this.angleDeg));

                RenderSystem.enableBlend();
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, a);

                guiGraphics.blit(
                        PARTICLE_TEXTURE,
                        -half,
                        -half,
                        drawSize,
                        drawSize,
                        0,
                        0,
                        16,
                        16,
                        16,
                        16
                );

                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                poseStack.popPose();
            } else {
                // Fallback: solid brown-ish square with alpha.
                int x0 = drawX - half;
                int y0 = drawY - half;
                int x1 = drawX + half;
                int y1 = drawY + half;

                int rgb = 0x8B5A2B;
                int alphaInt = (int) (a * 255.0f) & 0xFF;
                int argb = (alphaInt << 24) | (rgb & 0x00FFFFFF);

                guiGraphics.fill(x0, y0, x1, y1, argb);
            }
        } catch (Throwable t) {
            LOG.error("[SigilEtchingParticle] render failed", t);
        }


    }

// ---------------------------------------------------------------------
// Texture check
// ---------------------------------------------------------------------

    private static void ensureTextureChecked() {
        if (textureCheckDone) {
            return;
        }
        textureCheckDone = true;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getResourceManager() == null) {
                hasTexture = false;
                return;
            }
            hasTexture = mc.getResourceManager().getResource(PARTICLE_TEXTURE).isPresent();
            LOG.debug("[SigilEtchingParticle] texture_etching16x present: {}", hasTexture);
        } catch (Throwable t) {
            LOG.error("[SigilEtchingParticle] ensureTextureChecked failed", t);
            hasTexture = false;
        }
    }
}
