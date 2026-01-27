package net.z2six.featheredfriend.config;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.z2six.featheredfriend.calendar.CalendarDefinition;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * forge/src/main/java/net/z2six/featheredfriend/config/FFCalendarConfig.java
 *
 * Forge-side SERVER config for FeatheredFriend (Forge 1.20.1).
 */
public final class FFCalendarConfig {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Defaults
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

    public static final int DEFAULT_DAYS_PER_MONTH = 28;

    public static final int MONTHS_PER_YEAR = DEFAULT_MONTH_NAMES.length;

    public static final int TICKS_PER_DAY = 24000;

    public static final boolean DEFAULT_CHAT_DISABLED_DEFAULT = true;

    // ---------------------------------------------------------------------
    // Spec + entries
    // ---------------------------------------------------------------------

    public static final ForgeConfigSpec SERVER_SPEC;

    // calendar
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MONTH_NAMES;
    public static final ForgeConfigSpec.ConfigValue<String> YEAR_SUFFIX;
    public static final ForgeConfigSpec.IntValue DAYS_PER_MONTH;

    // settings
    public static final ForgeConfigSpec.BooleanValue CHAT_DISABLED_DEFAULT;

    // spawning
    public static final ForgeConfigSpec.IntValue WILD_RAVENS_PER_PLAYER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // -----------------------
        // calendar
        // -----------------------
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

        // -----------------------
        // settings
        // -----------------------
        builder.push("settings");

        CHAT_DISABLED_DEFAULT = builder
                .comment(
                        "Default for FeatheredFriend's world-owned chat flag when the world SavedData has no value yet.",
                        "If true, chat is disabled by default until an admin enables it in-game.",
                        "This does NOT forcibly override the world value every boot; it is only used as a fallback default."
                )
                .define("chatDisabledDefault", DEFAULT_CHAT_DISABLED_DEFAULT);

        builder.pop();

        // -----------------------
        // spawning
        // -----------------------
        builder.push("spawning");

        WILD_RAVENS_PER_PLAYER = builder
                .comment(
                        "How many WILD (untamed) ravens FeatheredFriend may keep spawned per online (non-spectator) player.",
                        "This is only used by FeatheredFriend's own natural spawn handler (not by vanilla biome spawns).",
                        "",
                        "Examples:",
                        "- 0: disable wild raven spawning (and aggressively cull existing wild ravens near players)",
                        "- 1: at most 1 wild raven per online player (default)",
                        "- 2: allow up to 2 wild ravens per online player"
                )
                .defineInRange("wildRavensPerPlayer", 1, 0, 16);

        builder.pop();

        SERVER_SPEC = builder.build();

        LOG.debug("[FFCalendarConfig] Built SERVER config spec (calendar + settings + spawning)");
    }

    // ---------------------------------------------------------------------
    // Registration (Forge idiom)
    // ---------------------------------------------------------------------

    public static void register() {
        try {
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
            LOG.debug("[FFCalendarConfig] Registered SERVER config");
        } catch (Throwable t) {
            LOG.error("[FFCalendarConfig] Failed to register SERVER config", t);
        }
    }

    // ---------------------------------------------------------------------
    // Safe accessors
    // ---------------------------------------------------------------------

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

    public static boolean getChatDisabledDefault() {
        try {
            return CHAT_DISABLED_DEFAULT.get();
        } catch (Throwable t) {
            LOG.error("[FFCalendarConfig] getChatDisabledDefault failed, using default {}", DEFAULT_CHAT_DISABLED_DEFAULT, t);
            return DEFAULT_CHAT_DISABLED_DEFAULT;
        }
    }

    public static int getWildRavensPerPlayer() {
        try {
            int v = WILD_RAVENS_PER_PLAYER.get();
            if (v < 0) v = 0;
            if (v > 16) v = 16;
            return v;
        } catch (Throwable t) {
            LOG.error("[FFCalendarConfig] getWildRavensPerPlayer failed, using default 1", t);
            return 1;
        }
    }

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
