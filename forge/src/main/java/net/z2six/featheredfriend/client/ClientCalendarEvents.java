// ClientCalendarEvents.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import org.slf4j.Logger;

/**
 * forge/src/main/java/net/z2six/featheredfriend/client/ClientCalendarEvents.java
 *
 * Client-side handler that:
 * - Detects when a new Minecraft day starts (based on world time).
 * - Computes the in-world calendar date (Day X of Month, Year Suffix).
 * - Shows a centered popup at the top of the screen with a fade-in/hold/fade-out.
 *
 * Uses the custom Gothic font (assets/featheredfriend/font/gothic12.json).
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
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

    // IMPORTANT: currently using featheredfriend:gothic12
    private static final ResourceLocation GOTHIC_FONT_ID =
            new ResourceLocation(Constants.MOD_ID, "gothic12");

    private ClientCalendarEvents() {
        // no-op
    }

    /**
     * Called every client tick (END phase matches Neo's Post semantics).
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

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

                LOG.debug("[ClientCalendarEvents] Detected new day: oldDayIndex={} newDayIndex={}", old, dayIndex);

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
    public static void onRenderGui(RenderGuiEvent.Post event) {
        try {
            if (!popupActive || currentMessage == null) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }

            if (mc.options.hideGui) {
                return;
            }

            int alpha = computeCurrentAlpha();
            if (alpha <= 0) {
                return;
            }

            GuiGraphics g = event.getGuiGraphics();
            Font font = mc.font;

            MutableComponent styled = currentMessage.copy()
                    .setStyle(Style.EMPTY.withFont(GOTHIC_FONT_ID));

            int screenWidth = mc.getWindow().getGuiScaledWidth();
            int textWidth = font.width(styled);
            int textHeight = font.lineHeight;

            int x = (screenWidth - textWidth) / 2;
            int y = 24;

            int argb = (alpha << 24) | 0x00FFFFFF;

            int shadowArgb = (alpha << 24) | 0x00101010;
            g.drawString(font, styled, x + 1, y + 1, shadowArgb, false);
            g.drawString(font, styled, x, y, argb, false);

            drawDecorativeOrnament(g, x, y, textWidth, textHeight, alpha);

        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] onRenderGui failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Popup helpers
    // -------------------------------------------------------------------------

    private static void startPopup(Component message) {
        try {
            currentMessage = message;
            popupAgeTicks = 0;
            popupActive = true;
            LOG.debug("[ClientCalendarEvents] Starting popup with message='{}'", message.getString());
        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] startPopup failed", t);
        }
    }

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
                float f = (float) t / (float) FADE_IN_TICKS;
                int alpha = (int) (f * 255.0f);
                return Math.max(0, Math.min(255, alpha));
            }

            if (t < FADE_IN_TICKS + HOLD_TICKS) {
                return 255;
            }

            int outT = t - FADE_IN_TICKS - HOLD_TICKS;
            float f = 1.0f - ((float) outT / (float) FADE_OUT_TICKS);
            int alpha = (int) (f * 255.0f);
            return Math.max(0, Math.min(255, alpha));

        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] computeCurrentAlpha failed", t);
            return 0;
        }
    }

    private static void drawDecorativeOrnament(
            GuiGraphics g,
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

            int ornamentColor = (alpha << 24) | 0x00E0D0B0;

            int centerY = textY + textHeight / 2;

            int gap = 6;
            int barLength = 40;
            int barThickness = 2;

            int leftBarEndX = textX - gap;
            int leftBarStartX = leftBarEndX - barLength;

            int rightBarStartX = textX + textWidth + gap;
            int rightBarEndX = rightBarStartX + barLength;

            int barTop = centerY - barThickness / 2;
            int barBottom = barTop + barThickness;

            g.fill(leftBarStartX, barTop, leftBarEndX, barBottom, ornamentColor);
            g.fill(rightBarStartX, barTop, rightBarEndX, barBottom, ornamentColor);

            g.fill(leftBarEndX, barTop - 1, leftBarEndX + 1, barTop, ornamentColor);
            g.fill(leftBarEndX, barBottom, leftBarEndX + 1, barBottom + 1, ornamentColor);

            g.fill(rightBarStartX - 1, barTop - 1, rightBarStartX, barTop, ornamentColor);
            g.fill(rightBarStartX - 1, barBottom, rightBarStartX, barBottom + 1, ornamentColor);

            int centerX = textX + textWidth / 2;
            int diamondY = textY + textHeight + 3;
            g.fill(centerX, diamondY - 1, centerX + 1, diamondY + 2, ornamentColor);
            g.fill(centerX - 1, diamondY, centerX + 2, diamondY + 1, ornamentColor);

        } catch (Throwable t) {
            LOG.error("[ClientCalendarEvents] drawDecorativeOrnament failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Calendar math
    // -------------------------------------------------------------------------

    public static Component buildDateMessage(long dayIndex) {
        try {
            int daysPerMonth = FFCalendarConfig.getDaysPerMonth();
            int monthsPerYear = FFCalendarConfig.MONTHS_PER_YEAR;

            if (daysPerMonth <= 0) {
                LOG.warn("[ClientCalendarEvents] buildDateMessage: daysPerMonth <= 0 ({}), falling back to {}", daysPerMonth, FFCalendarConfig.DEFAULT_DAYS_PER_MONTH);
                daysPerMonth = FFCalendarConfig.DEFAULT_DAYS_PER_MONTH;
            }
            if (monthsPerYear <= 0) {
                LOG.warn("[ClientCalendarEvents] buildDateMessage: monthsPerYear <= 0 ({}), falling back to 8", monthsPerYear);
                monthsPerYear = 8;
            }

            long totalDaysPerYear = (long) daysPerMonth * (long) monthsPerYear;
            if (totalDaysPerYear <= 0L) {
                LOG.warn("[ClientCalendarEvents] buildDateMessage: totalDaysPerYear <= 0, forcing safe fallback");
                totalDaysPerYear = (long) daysPerMonth * 8L;
            }

            if (dayIndex < 0L) {
                LOG.warn("[ClientCalendarEvents] buildDateMessage: dayIndex < 0 ({}), clamping to 0", dayIndex);
                dayIndex = 0L;
            }

            long yearIndex = dayIndex / totalDaysPerYear;
            int yearNumber = (int) (yearIndex + 1);

            int dayOfYear = (int) (dayIndex % totalDaysPerYear);
            int monthIndex = dayOfYear / daysPerMonth;
            int dayOfMonth = (dayOfYear % daysPerMonth) + 1;

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
