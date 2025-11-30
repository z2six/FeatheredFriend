// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/CalendarDayPopupOverlay.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.client.gui.widget.MultiLineScrollTextWidget;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * CalendarDayPopupOverlay
 *
 * Lightweight, client-only overlay that shows a single-line date message like:
 *   "Day 18 of Shadowmere, 58 AN"
 *
 * Uses the existing MultiLineScrollTextWidget with a custom font, but:
 *  - Is non-interactive (no caret, no focus, no input).
 *  - Handles its own fade-in / hold / fade-out timings.
 *
 * The actual trigger and date calculation are handled by ClientCalendarEvents.
 */
public class CalendarDayPopupOverlay {

    private static final Logger LOG = LogUtils.getLogger();

    // Durations in ticks (20 ticks = 1 second)
    private static final int FADE_IN_TICKS = 20;
    private static final int HOLD_TICKS = 60;
    private static final int FADE_OUT_TICKS = 20;

    private final MultiLineScrollTextWidget widget;
    private final int totalLifetimeTicks;

    private int ageTicks = 0;
    private boolean finished = false;

    public CalendarDayPopupOverlay(
            @NotNull Font font,
            @NotNull ResourceLocation gothicFontId,
            @NotNull String text,
            int screenWidth,
            int screenHeight
    ) {
        this.totalLifetimeTicks = FADE_IN_TICKS + HOLD_TICKS + FADE_OUT_TICKS;

        // Safety: avoid crashing on empty text, but don't show anything.
        if (text.isEmpty()) {
            LOG.warn("[CalendarDayPopupOverlay] Created with empty text; overlay will immediately finish");
            this.widget = null;
            this.finished = true;
            return;
        }

        try {
            // Measure text using the provided font (vanilla metrics; Gothic may differ slightly but that's OK).
            Component comp = Component.literal(text);
            int textWidth = font.width(comp);
            int textHeight = font.lineHeight;

            int paddingX = 8;
            int paddingY = 4;

            int widgetWidth = textWidth + paddingX;
            int widgetHeight = textHeight + paddingY;

            // Center horizontally, place near top (around 1/4 of the screen height).
            int x = (screenWidth - widgetWidth) / 2;
            int y = screenHeight / 4;

            this.widget = new MultiLineScrollTextWidget(
                    font,
                    x,
                    y,
                    widgetWidth,
                    widgetHeight,
                    128, // max chars
                    1,   // single visual line
                    Component.empty(),
                    gothicFontId,
                    false // no newlines
            );

            this.widget.setEditable(false);
            this.widget.setText(text);
            this.widget.setCursorToEnd();
            // Start fully transparent; fade logic will update color on first render.
            this.widget.setTextColor(0x00FFFFFF);

            LOG.debug(
                    "[CalendarDayPopupOverlay] Created overlay for text='{}' at ({}, {}) size=({}x{})",
                    text, x, y, widgetWidth, widgetHeight
            );
        } catch (Throwable t) {
            LOG.error("[CalendarDayPopupOverlay] Failed to create internal widget", t);
            throw t;
        }
    }

    public void tick() {
        if (finished || widget == null) {
            return;
        }

        try {
            ageTicks++;
            widget.tick();
            if (ageTicks >= totalLifetimeTicks) {
                finished = true;
                LOG.debug("[CalendarDayPopupOverlay] Lifetime ended; marking overlay finished");
            }
        } catch (Throwable t) {
            LOG.error("[CalendarDayPopupOverlay] tick failed", t);
            finished = true;
        }
    }

    public void render(@NotNull GuiGraphics guiGraphics, float partialTick) {
        if (finished || widget == null) {
            return;
        }

        try {
            float alpha = computeAlpha();
            int alphaInt = Math.max(0, Math.min(255, (int) (alpha * 255.0f)));

            // White text with variable alpha.
            int color = (alphaInt << 24) | 0x00FFFFFF;
            widget.setTextColor(color);

            // Mouse coordinates are irrelevant for a non-interactive overlay.
            widget.render(guiGraphics, 0, 0, partialTick);
        } catch (Throwable t) {
            LOG.error("[CalendarDayPopupOverlay] render failed", t);
            finished = true;
        }
    }

    private float computeAlpha() {
        if (ageTicks <= 0) {
            return 0.0f;
        }

        if (ageTicks < FADE_IN_TICKS) {
            // Fade in: 0 → 1
            return ageTicks / (float) FADE_IN_TICKS;
        }

        int afterFadeIn = ageTicks - FADE_IN_TICKS;
        if (afterFadeIn < HOLD_TICKS) {
            // Hold at full opacity
            return 1.0f;
        }

        int afterHold = afterFadeIn - HOLD_TICKS;
        if (afterHold >= FADE_OUT_TICKS) {
            // Completely faded out.
            return 0.0f;
        }

        // Fade out: 1 → 0
        float remaining = (FADE_OUT_TICKS - afterHold) / (float) FADE_OUT_TICKS;
        return Math.max(0.0f, Math.min(1.0f, remaining));
    }

    public boolean isFinished() {
        return finished;
    }

    /**
     * Convenience helper for logging where we are in the overlay lifecycle.
     */
    @SuppressWarnings("unused")
    public int getAgeTicks() {
        return ageTicks;
    }
}
