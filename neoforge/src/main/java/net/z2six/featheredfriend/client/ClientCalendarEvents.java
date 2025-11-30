// neoforge/src/main/java/net/z2six/featheredfriend/client/ClientCalendarEvents.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/ClientCalendarEvents.java
 *
 * Client-side handler that:
 * - Detects when a new Minecraft day starts (based on world time).
 * - Computes the in-world calendar date (Day X of Month, Year Suffix).
 * - Shows a centered popup at the top of the screen with a fade-in/hold/fade-out.
 *
 * Uses the custom Gothic font (assets/featheredfriend/font/gothic24.json).
 *
 * This class is wired via @EventBusSubscriber on the GAME bus, client side only.
 */
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ClientCalendarEvents {

    private static final Logger LOG = LogUtils.getLogger();

    // Popup timing (ticks at 20 TPS)
    private static final int FADE_IN_TICKS = 20;   // 1.0s fade in
    private static final int HOLD_TICKS    = 60;   // 3.0s hold
    private static final int FADE_OUT_TICKS = 20;  // 1.0s fade out
    private static final int TOTAL_TICKS   = FADE_IN_TICKS + HOLD_TICKS + FADE_OUT_TICKS;

    // Keep track of last day index we saw, to detect day changes
    private static long lastSeenDayIndex = -1L;

    // Current popup state
    private static Component currentMessage = null;
    private static int popupAgeTicks = 0;
    private static boolean popupActive = false;

    // IMPORTANT: currently using featheredfriend:gothic24
    private static final ResourceLocation GOTHIC_FONT_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gothic12");

    private ClientCalendarEvents() {
        // no-op
    }

    // -------------------------------------------------------------------------
    // Event hooks (registered automatically via @EventBusSubscriber)
    // -------------------------------------------------------------------------

    /**
     * Called every client tick (POST).
     */
    @SubscribeEvent
    public static void onClientTick(@NotNull ClientTickEvent.Post event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.player == null) {
                return;
            }

            long dayTime = mc.level.getDayTime(); // absolute time in ticks
            long ticksPerDay = FFCalendarConfig.TICKS_PER_DAY;
            if (ticksPerDay <= 0L) {
                LOG.warn("[ClientCalendarEvents] FFCalendarConfig.TICKS_PER_DAY <= 0 ({}), using 24000 fallback", ticksPerDay);
                ticksPerDay = 24000L; // ultra-safe fallback
            }

            long dayIndex = dayTime / ticksPerDay; // 0-based day counter

            if (dayIndex != lastSeenDayIndex) {
                long old = lastSeenDayIndex;
                lastSeenDayIndex = dayIndex;

                LOG.info("[ClientCalendarEvents] Detected new day: oldDayIndex={} newDayIndex={}", old, dayIndex);

                Component msg = buildDateMessage(dayIndex);
                startPopup(msg);
            }

            // Advance popup animation if active
            if (popupActive) {
                popupAgeTicks++;
                if (popupAgeTicks >= TOTAL_TICKS) {
                    popupActive = false;
                    currentMessage = null;
                    popupAgeTicks = 0;
                    LOG.debug("[ClientCalendarEvents] Popup finished");
                }
            }

        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] onClientTick failed", t);
        }
    }

    /**
     * Called each frame after GUI is rendered; we draw our popup on top.
     */
    @SubscribeEvent
    public static void onRenderGui(@NotNull RenderGuiEvent.Post event) {
        try {
            if (!popupActive || currentMessage == null) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }

            if (mc.options.hideGui) {
                // Respect F1 "hide GUI"
                return;
            }

            int alpha = computeCurrentAlpha();
            if (alpha <= 0) {
                return;
            }

            GuiGraphics g = event.getGuiGraphics();
            Font font = mc.font;

            // Apply gothic font style to the message
            MutableComponent styled = currentMessage.copy()
                    .setStyle(Style.EMPTY.withFont(GOTHIC_FONT_ID));

            int screenWidth = mc.getWindow().getGuiScaledWidth();
            int textWidth = font.width(styled);
            int textHeight = font.lineHeight;

            int x = (screenWidth - textWidth) / 2;
            int y = 24; // near top, but below boss bar / title

            // Color: white with computed alpha
            int argb = (alpha << 24) | 0x00FFFFFF;

            // Simple drop shadow
            int shadowArgb = (alpha << 24) | 0x00101010;
            g.drawString(font, styled, x + 1, y + 1, shadowArgb, false);
            g.drawString(font, styled, x, y, argb, false);

            // Draw custom decorative ornament around the text (bars + small "diamond")
            drawDecorativeOrnament(g, x, y, textWidth, textHeight, alpha);

        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] onRenderGui failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Popup helpers
    // -------------------------------------------------------------------------

    private static void startPopup(@NotNull Component message) {
        try {
            currentMessage = message;
            popupAgeTicks = 0;
            popupActive = true;
            LOG.info("[ClientCalendarEvents] Starting popup with message='{}'", message.getString());
        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] startPopup failed", t);
        }
    }

    /**
     * Compute current alpha (0–255) based on popupAgeTicks.
     */
    private static int computeCurrentAlpha() {
        try {
            if (!popupActive) {
                return 0;
            }

            int t = popupAgeTicks;
            if (t < 0 || t >= TOTAL_TICKS) {
                return 0;
            }

            if (t < FADE_IN_TICKS) {
                // Fade in 0 -> 255
                float f = (float) t / (float) FADE_IN_TICKS;
                int alpha = (int) (f * 255.0f);
                return Math.max(0, Math.min(255, alpha));
            }

            if (t < FADE_IN_TICKS + HOLD_TICKS) {
                // Hold at full opacity
                return 255;
            }

            // Fade out
            int outT = t - FADE_IN_TICKS - HOLD_TICKS;
            float f = 1.0f - ((float) outT / (float) FADE_OUT_TICKS);
            int alpha = (int) (f * 255.0f);
            return Math.max(0, Math.min(255, alpha));

        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] computeCurrentAlpha failed", t);
            // Fail-safe: no popup instead of broken visuals
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Custom ornament drawing
    // -------------------------------------------------------------------------

    /**
     * Draws a small “calligraphy-ish” ornament:
     *
     *   [====\     Day X of Month, Year Suffix     /====]
     *                      ◊
     *
     * Bars are drawn using rectangles; the little “diamond” is a tiny cross of pixels.
     * All tinted by the same alpha as the main text.
     */
    private static void drawDecorativeOrnament(
            @NotNull GuiGraphics g,
            int textX,
            int textY,
            int textWidth,
            int textHeight,
            int alpha
    ) {
        try {
            if (alpha <= 0) {
                return;
            }

            // Slightly dimmer than the text itself.
            // Use same alpha channel, but a warmer-ish RGB (soft beige).
            int ornamentColor = (alpha << 24) | 0x00E0D0B0;

            // Geometry:
            // - Bars are horizontally aligned with the vertical center of the text.
            // - A gap between text and bar to avoid touching.
            int centerY = textY + textHeight / 2;

            int gap = 6;          // distance from text to start of bar
            int barLength = 40;   // length of each side bar
            int barThickness = 2; // vertical thickness of the bar

            // Left bar: [====\
            int leftBarEndX = textX - gap;
            int leftBarStartX = leftBarEndX - barLength;

            // Right bar: /====]
            int rightBarStartX = textX + textWidth + gap;
            int rightBarEndX = rightBarStartX + barLength;

            int barTop = centerY - barThickness / 2;
            int barBottom = barTop + barThickness;

            // Draw straight bars
            g.fill(leftBarStartX, barTop, leftBarEndX, barBottom, ornamentColor);
            g.fill(rightBarStartX, barTop, rightBarEndX, barBottom, ornamentColor);

            // Add a subtle “taper” at the inner ends of each bar using 1-pixel steps
            // to fake a little angled flourish.
            // Left inner tip
            g.fill(leftBarEndX, barTop - 1, leftBarEndX + 1, barTop, ornamentColor);
            g.fill(leftBarEndX, barBottom, leftBarEndX + 1, barBottom + 1, ornamentColor);

            // Right inner tip
            g.fill(rightBarStartX - 1, barTop - 1, rightBarStartX, barTop, ornamentColor);
            g.fill(rightBarStartX - 1, barBottom, rightBarStartX, barBottom + 1, ornamentColor);

            // Small “diamond” under the center of the text: a tiny cross / plus shape.
            int centerX = textX + textWidth / 2;
            int diamondY = textY + textHeight + 3; // just below the baseline

            // Vertical stroke
            g.fill(centerX, diamondY - 1, centerX + 1, diamondY + 2, ornamentColor);
            // Horizontal stroke
            g.fill(centerX - 1, diamondY, centerX + 2, diamondY + 1, ornamentColor);

        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] drawDecorativeOrnament failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Calendar math
    // -------------------------------------------------------------------------

    /**
     * Builds the display string for a given day index using FFCalendarConfig.
     *
     * Day index 0 -> "Day 1 of Dawnroot, 1 AN" (with defaults).
     */
    private static @NotNull Component buildDateMessage(long dayIndex) {
        try {
            int daysPerMonth = FFCalendarConfig.DAYS_PER_MONTH;
            int monthsPerYear = FFCalendarConfig.MONTHS_PER_YEAR;
            long ticksPerDay = FFCalendarConfig.TICKS_PER_DAY;

            if (daysPerMonth <= 0) {
                daysPerMonth = 28;
            }
            if (monthsPerYear <= 0) {
                monthsPerYear = 8;
            }
            if (ticksPerDay <= 0L) {
                ticksPerDay = 24000L;
            }

            long totalDaysPerYear = (long) daysPerMonth * (long) monthsPerYear;
            if (totalDaysPerYear <= 0L) {
                totalDaysPerYear = (long) daysPerMonth * 8L;
            }

            // Ensure non-negative
            if (dayIndex < 0L) {
                dayIndex = 0L;
            }

            long yearIndex = dayIndex / totalDaysPerYear; // 0-based
            int yearNumber = (int) (yearIndex + 1);       // 1-based

            int dayOfYear = (int) (dayIndex % totalDaysPerYear); // 0..(totalDaysPerYear-1)
            int monthIndex = dayOfYear / daysPerMonth;           // 0..monthsPerYear-1
            int dayOfMonth = (dayOfYear % daysPerMonth) + 1;     // 1..daysPerMonth

            // Clamp month index just in case
            if (monthIndex < 0) {
                monthIndex = 0;
            } else if (monthIndex >= monthsPerYear) {
                monthIndex = monthsPerYear - 1;
            }

            String monthName = FFCalendarConfig.getMonthName(monthIndex);
            String suffix = FFCalendarConfig.getYearSuffix();

            String text = "Day " + dayOfMonth + " of " + monthName + ", " + yearNumber + " " + suffix;
            return Component.literal(text);

        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] buildDateMessage failed, using fallback text", t);
            return Component.literal("A New Day Dawns");
        }
    }
}
