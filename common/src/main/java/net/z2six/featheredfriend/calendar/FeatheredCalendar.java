// common/src/main/java/net/z2six/featheredfriend/calendar/FeatheredCalendar.java
package net.z2six.featheredfriend.calendar;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * FeatheredCalendar
 *
 * Static helper for converting Minecraft world time (gameTime) into our
 * custom calendar, and formatting it for use in scroll signatures or
 * daily popups.
 *
 * This is pure logic and lives in common, so any loader/platform can use it.
 */
public final class FeatheredCalendar {

    private static final Logger LOG = LogUtils.getLogger();

    private FeatheredCalendar() {
        // no instances
    }

    /**
     * Convert world game time into a CalendarDate using the provided definition.
     *
     * @param gameTime   World game time in ticks (e.g. ServerLevel.getDayTime()).
     * @param definition Calendar definition (month names, era, etc).
     */
    @NotNull
    public static CalendarDate fromGameTime(long gameTime, @NotNull CalendarDefinition definition) {
        try {
            long ticksPerDay = definition.getTicksPerDay();
            if (ticksPerDay <= 0L) {
                LOG.warn("[FeatheredCalendar] ticksPerDay <= 0 ({}), falling back to 24000", ticksPerDay);
                ticksPerDay = 24000L;
            }

            long absoluteDay = Math.floorDiv(gameTime, ticksPerDay);
            long ticksWithinDay = Math.floorMod(gameTime, ticksPerDay);

            int daysPerMonth = definition.getDaysPerMonth();
            if (daysPerMonth <= 0) {
                LOG.warn("[FeatheredCalendar] daysPerMonth <= 0 ({}), falling back to 28", daysPerMonth);
                daysPerMonth = 28;
            }

            int monthCount = definition.getMonthCount();
            int daysPerYear = daysPerMonth * monthCount;

            long yearIndex = daysPerYear <= 0 ? 0L : Math.floorDiv(absoluteDay, daysPerYear);
            long dayOfYear = daysPerYear <= 0 ? 0L : Math.floorMod(absoluteDay, daysPerYear);

            int monthIndex = daysPerMonth <= 0 ? 0 : (int) (dayOfYear / daysPerMonth);
            if (monthIndex < 0) monthIndex = 0;
            if (monthIndex >= monthCount) monthIndex = monthCount - 1;

            int dayOfMonthZeroBased = daysPerMonth <= 0 ? 0 : (int) (dayOfYear % daysPerMonth);
            int dayOfMonth = dayOfMonthZeroBased + 1;

            long yearNumber = yearIndex; // 0 AN, 1 AN, 2 AN, ...

            return new CalendarDate(
                    gameTime,
                    absoluteDay,
                    yearIndex,
                    yearNumber,
                    monthIndex,
                    dayOfMonth,
                    ticksWithinDay
            );
        } catch (Throwable t) {
            LOG.error("[FeatheredCalendar] fromGameTime failed, returning default date for 0 AN", t);
            CalendarDefinition def = CalendarDefinition.defaultDefinition();
            return new CalendarDate(
                    0L,
                    0L,
                    0L,
                    0L,
                    0,
                    1,
                    0L
            );
        }
    }

    /**
     * Convenience: get a human-readable date string for a given world time.
     */
    @NotNull
    public static String formatDate(long gameTime, @NotNull CalendarDefinition definition) {
        CalendarDate date = fromGameTime(gameTime, definition);
        return date.format(definition);
    }
}
