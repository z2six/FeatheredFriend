// common/src/main/java/net/z2six/featheredfriend/calendar/CalendarDate.java
package net.z2six.featheredfriend.calendar;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * CalendarDate
 *
 * Immutable representation of a point in time in the FeatheredFriend calendar.
 * Computed from a world game time and a CalendarDefinition.
 *
 * Values:
 *  - gameTime: raw world ticks (as provided by Minecraft).
 *  - absoluteDay: days since world start (0-based).
 *  - yearIndex: 0-based year counting from 0 AN.
 *  - year: same as yearIndex for now (0, 1, 2, ...).
 *  - monthIndex: 0-based month index (0..7).
 *  - dayOfMonth: 1-based day in month (1..daysPerMonth).
 *  - ticksWithinDay: tick offset inside the current day (0..ticksPerDay-1).
 */
public final class CalendarDate {

    private static final Logger LOG = LogUtils.getLogger();

    private final long gameTime;
    private final long absoluteDay;
    private final long yearIndex;
    private final long year;
    private final int monthIndex;
    private final int dayOfMonth;
    private final long ticksWithinDay;

    public CalendarDate(
            long gameTime,
            long absoluteDay,
            long yearIndex,
            long year,
            int monthIndex,
            int dayOfMonth,
            long ticksWithinDay
    ) {
        this.gameTime = gameTime;
        this.absoluteDay = absoluteDay;
        this.yearIndex = yearIndex;
        this.year = year;
        this.monthIndex = monthIndex;
        this.dayOfMonth = dayOfMonth;
        this.ticksWithinDay = ticksWithinDay;

        LOG.debug(
                "[CalendarDate] Created: gameTime={} absoluteDay={} yearIndex={} monthIndex={} dayOfMonth={} ticksWithinDay={}",
                gameTime, absoluteDay, yearIndex, monthIndex, dayOfMonth, ticksWithinDay
        );
    }

    public long getGameTime() {
        return gameTime;
    }

    public long getAbsoluteDay() {
        return absoluteDay;
    }

    public long getYearIndex() {
        return yearIndex;
    }

    /**
     * Public "year number" used for display and signatures.
     * World creation starts at year 0 AN, then 1 AN, 2 AN, ...
     */
    public long getYear() {
        return year;
    }

    public int getMonthIndex() {
        return monthIndex;
    }

    public int getDayOfMonth() {
        return dayOfMonth;
    }

    public long getTicksWithinDay() {
        return ticksWithinDay;
    }

    /**
     * Formats this date as a plain string using the provided definition.
     * Example: "Day 17 of Dawnroot, 112 AN"
     */
    @NotNull
    public String format(@NotNull CalendarDefinition definition) {
        String monthName = definition.getMonthName(this.monthIndex);
        String era = definition.getEraSuffix();
        return "Day " + this.dayOfMonth + " of " + monthName + ", " + this.year + " " + era;
    }

    @Override
    public String toString() {
        return "CalendarDate{" +
                "gameTime=" + gameTime +
                ", absoluteDay=" + absoluteDay +
                ", yearIndex=" + yearIndex +
                ", year=" + year +
                ", monthIndex=" + monthIndex +
                ", dayOfMonth=" + dayOfMonth +
                ", ticksWithinDay=" + ticksWithinDay +
                '}';
    }
}
