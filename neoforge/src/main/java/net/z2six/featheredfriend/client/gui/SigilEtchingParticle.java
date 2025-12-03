// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SigilEtchingParticle.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

import java.util.concurrent.ThreadLocalRandom;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/SigilEtchingParticle.java
 *
 * SigilEtchingParticle
 *
 * UI particle used by SealStampScreen:
 *  - Rendered as a textured quad using texture_etching16x.png (16x16 wood snippet).
 *  - Falls downward, rotates, and fades out quickly.
 *  - Each particle has randomized:
 *      * initial angle (some "mirrored" via 180° offsets),
 *      * angular velocity (spin speed),
 *      * velocity magnitude.
 */
public class SigilEtchingParticle {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation PARTICLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/texture_etching16x.png");

    private static boolean textureCheckDone = false;
    private static boolean hasTexture = false;

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

    // Physics tuning
    private static final double GRAVITY = 0.05;
    private static final double FRICTION = 0.96;

    public SigilEtchingParticle(double x, double y, double vx, double vy, float size, int maxAge) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.size = size <= 0.0f ? 1.0f : size;
        this.maxAge = Math.max(1, maxAge);
        this.age = 0;
        this.alpha = 1.0f;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // Random initial angle; half of them "mirrored" via 180° offset.
        float baseAngle = rnd.nextFloat() * 360.0f;
        if (rnd.nextBoolean()) {
            baseAngle += 180.0f;
        }
        this.angleDeg = baseAngle;

        // Random spin speed: some slow, some fast, both directions
        this.angularVelocityDeg = (rnd.nextFloat() - 0.5f) * 40.0f; // -20 .. +20 deg/tick
    }

    public void tick() {
        try {
            this.age++;
            if (this.age >= this.maxAge) {
                this.alpha = 0.0f;
                return;
            }

            this.x += this.vx;
            this.y += this.vy;

            this.vy += GRAVITY;
            this.vx *= FRICTION;
            this.vy *= FRICTION;

            this.angleDeg += this.angularVelocityDeg;

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
                // Textured, rotated, alpha-faded quad using the pose stack & shader color.
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
