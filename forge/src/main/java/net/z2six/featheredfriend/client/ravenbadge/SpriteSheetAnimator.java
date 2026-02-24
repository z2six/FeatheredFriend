package net.z2six.featheredfriend.client.ravenbadge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Small helper for fixed-grid spritesheet animations.
 */
public final class SpriteSheetAnimator {

    private final @NotNull ResourceLocation texture;
    private final int columns;
    private final int rows;
    private final int frameCount;
    private final int frameWidth;
    private final int frameHeight;
    private final int ticksPerFrame;
    private final boolean loop;

    public SpriteSheetAnimator(@NotNull ResourceLocation texture,
                               int columns,
                               int rows,
                               int frameCount,
                               int frameWidth,
                               int frameHeight,
                               int ticksPerFrame,
                               boolean loop) {
        this.texture = texture;
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        this.frameCount = Math.max(1, frameCount);
        this.frameWidth = Math.max(1, frameWidth);
        this.frameHeight = Math.max(1, frameHeight);
        this.ticksPerFrame = Math.max(1, ticksPerFrame);
        this.loop = loop;
    }

    public static @NotNull SpriteSheetAnimator single(@NotNull ResourceLocation texture, int frameWidth, int frameHeight) {
        return new SpriteSheetAnimator(texture, 1, 1, 1, frameWidth, frameHeight, 1, true);
    }

    public boolean isLooping() {
        return loop;
    }

    public int durationTicks() {
        return frameCount * ticksPerFrame;
    }

    public boolean isFinished(long elapsedTicks) {
        if (loop) {
            return false;
        }
        return elapsedTicks >= durationTicks();
    }

    public void render(@NotNull GuiGraphics guiGraphics,
                       int x,
                       int y,
                       int renderWidth,
                       int renderHeight,
                       long elapsedTicks) {
        int frame = frameIndex(elapsedTicks);
        int col = frame % columns;
        int row = frame / columns;
        int u = col * frameWidth;
        int v = row * frameHeight;
        int textureWidth = columns * frameWidth;
        int textureHeight = rows * frameHeight;

        guiGraphics.blit(
                texture,
                x,
                y,
                u,
                v,
                renderWidth,
                renderHeight,
                textureWidth,
                textureHeight
        );
    }

    private int frameIndex(long elapsedTicks) {
        long safeElapsed = Math.max(0L, elapsedTicks);
        int frame = (int) (safeElapsed / (long) ticksPerFrame);
        if (loop) {
            return frame % frameCount;
        }
        if (frame >= frameCount) {
            return frameCount - 1;
        }
        return frame;
    }
}
