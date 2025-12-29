// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/config/FFCalendarConfig.java
package net.z2six.featheredfriend.config;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.z2six.featheredfriend.calendar.CalendarDefinition;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/config/FFCalendarConfig.java
 *
 * FFCalendarConfig
 *
 * NeoForge-side SERVER config for the custom calendar system.
 *
 * - Defines:
 *   * Month names (8 entries, ordered)
 *   * Year suffix (e.g. "A.N.")
 *   * Days per month (server-authoritative, synced to clients)
 *
 * - Server-authoritative:
 *   * Only the SERVER config is defined here.
 *   * Clients should always respect the server-provided values via sync,
 *     not their local config.
 *
 * NOTE:
 * - We intentionally do NOT make ticksPerDay configurable here per your requirement.
 */
public final class FFCalendarConfig {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Defaults (also used as fallback if config is invalid)
    // ---------------------------------------------------------------------

    public static final String[] DEFAULT_MONTH_NAMES = new String[]{
            "Dawnroot",
            "Blossomwake",
            "Greengale",
            "Suncrest",
            "Stormfall",
            "Shadowmere",
            "Frostveil",
            "Darkrest"
    };

    public static final String DEFAULT_YEAR_SUFFIX = "A.N.";

    /**
     * Default days-per-month. Historically this was 28. We keep 28 as default
     * to preserve existing behavior unless the server owner changes it.
     *
     * For perfect sync with Serene Seasons sub_season_duration=16, set daysPerMonth=24 in the server config.
     */
    public static final int DEFAULT_DAYS_PER_MONTH = 28;

    public static final int MONTHS_PER_YEAR = DEFAULT_MONTH_NAMES.length;

    /**
     * Not configurable in this task; used for dayIndex calculation everywhere.
     */
    public static final int TICKS_PER_DAY = 24000;

    // ---------------------------------------------------------------------
    // Spec + entries
    // ---------------------------------------------------------------------

    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> MONTH_NAMES;
    public static final ModConfigSpec.ConfigValue<String> YEAR_SUFFIX;

    /**
     * NEW: server-authoritative days per month (synced to clients).
     */
    public static final ModConfigSpec.IntValue DAYS_PER_MONTH;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("calendar");

        MONTH_NAMES = builder
                .comment(
                        "Names of the 8 calendar months, in order.",
                        "If the list is missing or has the wrong size,",
                        "the mod will fall back to its built-in defaults."
                )
                .defineList(
                        "monthNames",
                        Arrays.asList(DEFAULT_MONTH_NAMES),
                        o -> (o instanceof String s) && !s.isBlank()
                );

        YEAR_SUFFIX = builder
                .comment(
                        "Year suffix string, e.g. \"A.N.\" for \"After Notch\".",
                        "Used when displaying dates like: Day 17 of Dawnroot, 112 A.N."
                )
                .define("yearSuffix", DEFAULT_YEAR_SUFFIX);

        DAYS_PER_MONTH = builder
                .comment(
                        "How many in-game days each month lasts before progressing to the next month.",
                        "This is server-authoritative and synced to clients.",
                        "",
                        "Examples:",
                        "- Vanilla-ish fantasy default: 28",
                        "- Perfect sync with Serene Seasons sub_season_duration=16: set this to 24",
                        "",
                        "Valid range: 1..365"
                )
                .defineInRange("daysPerMonth", DEFAULT_DAYS_PER_MONTH, 1, 365);

        builder.pop();

        SERVER_SPEC = builder.build();

        LOG.debug("[FFCalendarConfig] Built SERVER config spec for calendar (monthNames/yearSuffix/daysPerMonth)");
    }

    // ---------------------------------------------------------------------
    // Registration (NeoForge idiom)
    // ---------------------------------------------------------------------

    /**
     * Registers the SERVER config with the active mod container.
     *
     * Call this once from your NeoForge main mod class constructor, e.g.:
     *
     * <pre>
     * public FeatheredFriend(IEventBus modBus) {
     *     FFCalendarConfig.register();
     *     ...
     * }
     * </pre>
     */
    public static void register() {
        try {
            ModLoadingContext.get()
                    .getActiveContainer()
                    .registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);

            LOG.debug("[FFCalendarConfig] Registered SERVER config with active ModContainer");
        } catch (Throwable t) {
            // Never crash game on config registration failure.
            LOG.error("[FFCalendarConfig] Failed to register SERVER config", t);
        }
    }

    // ---------------------------------------------------------------------
    // Safe accessors (server-side use; client reads synced values)
    // ---------------------------------------------------------------------

    /**
     * Returns the configured month name for the given index (0–7).
     * Falls back to built-in defaults and safe placeholders if the config
     * is misconfigured.
     */
    public static String getMonthName(int index) {
        try {
            if (index < 0 || index >= DEFAULT_MONTH_NAMES.length) {
                LOG.warn("[FFCalendarConfig] getMonthName: index {} out of range, using placeholder", index);
                return "Month" + index;
            }

            List<? extends String> names = MONTH_NAMES.get();
            if (names == null || names.size() != DEFAULT_MONTH_NAMES.length) {
                LOG.warn(
                        "[FFCalendarConfig] monthNames config invalid (got size {}), falling back to defaults",
                        names == null ? "null" : names.size()
                );
                return DEFAULT_MONTH_NAMES[index];
            }

            String value = names.get(index);
            if (value == null || value.isBlank()) {
                LOG.warn("[FFCalendarConfig] monthNames[{}] is blank/null, falling back to default", index);
                return DEFAULT_MONTH_NAMES[index];
            }

            return value;
        } catch (Throwable t) {
            LOG.error("[FFCalendarConfig] getMonthName failed for index {}, using safe fallback", index, t);
            if (index >= 0 && index < DEFAULT_MONTH_NAMES.length) {
                return DEFAULT_MONTH_NAMES[index];
            }
            return "Month" + index;
        }
    }

    /**
     * Returns the configured year suffix (e.g. "A.N."), or the default if
     * config is missing/blank.
     */
    public static String getYearSuffix() {
        try {
            String suffix = YEAR_SUFFIX.get();
            if (suffix == null || suffix.isBlank()) {
                LOG.warn("[FFCalendarConfig] yearSuffix is blank/null, using default '{}'", DEFAULT_YEAR_SUFFIX);
                return DEFAULT_YEAR_SUFFIX;
            }
            return suffix;
        } catch (Throwable t) {
            LOG.error("[FFCalendarConfig] getYearSuffix failed, using default '{}'", DEFAULT_YEAR_SUFFIX, t);
            return DEFAULT_YEAR_SUFFIX;
        }
    }

    /**
     * NEW: Returns server-authoritative days-per-month, synced to clients.
     * Defensive: clamps to [1..365] and logs on invalid values.
     */
    public static int getDaysPerMonth() {
        try {
            int v = DAYS_PER_MONTH.get();
            if (v <= 0) {
                LOG.warn("[FFCalendarConfig] daysPerMonth <= 0 ({}), using default {}", v, DEFAULT_DAYS_PER_MONTH);
                return DEFAULT_DAYS_PER_MONTH;
            }
            if (v > 365) {
                LOG.warn("[FFCalendarConfig] daysPerMonth > 365 ({}), clamping to 365", v);
                return 365;
            }
            return v;
        } catch (Throwable t) {
            LOG.error("[FFCalendarConfig] getDaysPerMonth failed, using default {}", DEFAULT_DAYS_PER_MONTH, t);
            return DEFAULT_DAYS_PER_MONTH;
        }
    }

    /**
     * Builds a CalendarDefinition using the server config values.
     *
     * This is what NeoForgePlatformHelper calls.
     * Safe to call; falls back to defaults and logs.
     *
     * NOTE: ticksPerDay is intentionally constant (24000) in this task.
     */
    public static CalendarDefinition getCalendarDefinition() {
        try {
            List<String> monthNamesList = new ArrayList<>(MONTHS_PER_YEAR);
            for (int i = 0; i < MONTHS_PER_YEAR; i++) {
                monthNamesList.add(getMonthName(i));
            }

            String suffix = getYearSuffix();
            int daysPerMonth = getDaysPerMonth();

            String[] namesArray = monthNamesList.toArray(new String[0]);

            CalendarDefinition def = new CalendarDefinition(
                    namesArray,
                    suffix,
                    daysPerMonth,
                    (long) TICKS_PER_DAY
            );

            LOG.debug(
                    "[FFCalendarConfig] Built CalendarDefinition: months={}, daysPerMonth={}, suffix='{}', ticksPerDay={}",
                    namesArray.length,
                    daysPerMonth,
                    suffix,
                    TICKS_PER_DAY
            );

            return def;
        } catch (Throwable t) {
            LOG.error("[FFCalendarConfig] getCalendarDefinition failed; falling back to hard-coded defaults", t);

            // Absolute worst-case: hard-coded safe defaults.
            return new CalendarDefinition(
                    DEFAULT_MONTH_NAMES,
                    DEFAULT_YEAR_SUFFIX,
                    DEFAULT_DAYS_PER_MONTH,
                    (long) TICKS_PER_DAY
            );
        }
    }

    private FFCalendarConfig() {
        // no-op
    }
}
