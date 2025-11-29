// neoforge/src/main/java/net/z2six/featheredfriend/config/FFCalendarConfig.java
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
 *   * Year suffix (e.g. "AN" for "After Notch")
 *
 * - Server-authoritative:
 *   * Only the SERVER config is defined here.
 *   * Clients should always respect the server-provided values via sync,
 *     not their local config.
 *
 * This class is NeoForge-only and must not be referenced directly
 * from common code. Common layers should instead go through whatever
 * abstraction we wire up later (e.g. a CalendarManager / platform API).
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

    public static final String DEFAULT_YEAR_SUFFIX = "AN";

    // Simple constants for now; if you ever want 20-minute days etc.,
    // change here + in your common CalendarDefinition usage.
    public static final int DAYS_PER_MONTH = 28;
    public static final int MONTHS_PER_YEAR = DEFAULT_MONTH_NAMES.length;
    public static final int TICKS_PER_DAY = 24000;

    // ---------------------------------------------------------------------
    // Spec + entries
    // ---------------------------------------------------------------------

    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> MONTH_NAMES;
    public static final ModConfigSpec.ConfigValue<String> YEAR_SUFFIX;

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
                        "Year suffix string, e.g. \"AN\" for \"After Notch\".",
                        "Used when displaying dates like: Day 17 of Dawnroot, 112 AN."
                )
                .define("yearSuffix", DEFAULT_YEAR_SUFFIX);

        builder.pop();

        SERVER_SPEC = builder.build();

        LOG.debug("[FFCalendarConfig] Built SERVER config spec for calendar");
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
     * public FeatheredFriendNeoForge(IEventBus modBus, ModContainer container) {
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
    // Safe accessors (server-side use; client should read synced values)
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
     * Returns the configured year suffix (e.g. "AN"), or the default if
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
     * Builds a CalendarDefinition using the *server* config values.
     *
     * This is what NeoForgePlatformHelper calls.
     * It is safe to call on the logical server; if anything goes wrong
     * we fall back to defaults and log.
     *
     * NOTE: This matches the existing constructor:
     *   CalendarDefinition(@NotNull String[] monthNames,
     *                      @NotNull String yearSuffix,
     *                      int daysPerMonth,
     *                      long ticksPerDay)
     */
    public static CalendarDefinition getCalendarDefinition() {
        try {
            List<String> monthNamesList = new ArrayList<>(MONTHS_PER_YEAR);
            for (int i = 0; i < MONTHS_PER_YEAR; i++) {
                monthNamesList.add(getMonthName(i));
            }

            String suffix = getYearSuffix();

            String[] namesArray = monthNamesList.toArray(new String[0]);

            CalendarDefinition def = new CalendarDefinition(
                    namesArray,
                    suffix,
                    DAYS_PER_MONTH,
                    TICKS_PER_DAY
            );

            LOG.debug(
                    "[FFCalendarConfig] Built CalendarDefinition: months={}, daysPerMonth={}, suffix='{}', ticksPerDay={}",
                    namesArray.length,
                    DAYS_PER_MONTH,
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
                    DAYS_PER_MONTH,
                    TICKS_PER_DAY
            );
        }
    }

    private FFCalendarConfig() {
        // no-op
    }
}
