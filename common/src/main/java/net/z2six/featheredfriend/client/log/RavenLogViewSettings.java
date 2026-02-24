package net.z2six.featheredfriend.client.log;

import net.z2six.featheredfriend.log.RavenLogCategory;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side filter/color preferences for Raven Log rendering.
 *
 * Storage format:
 *   categoryId,visible,colorRgb;categoryId,visible,colorRgb;...
 *
 * visible: 1 or 0
 * colorRgb: decimal RGB integer (0x000000 - 0xFFFFFF)
 */
public final class RavenLogViewSettings {

    public static final class CategoryStyle {
        public boolean visible;
        public int colorRgb;

        public CategoryStyle(boolean visible, int colorRgb) {
            this.visible = visible;
            this.colorRgb = colorRgb & 0xFFFFFF;
        }
    }

    private final EnumMap<RavenLogCategory, CategoryStyle> styles = new EnumMap<>(RavenLogCategory.class);

    public RavenLogViewSettings() {
        for (RavenLogCategory c : RavenLogCategory.values()) {
            this.styles.put(c, defaultStyle(c));
        }
    }

    public @NotNull CategoryStyle style(@NotNull RavenLogCategory category) {
        CategoryStyle style = this.styles.get(category);
        if (style == null) {
            style = defaultStyle(category);
            this.styles.put(category, style);
        }
        return style;
    }

    public boolean isVisible(@NotNull RavenLogCategory category) {
        return style(category).visible;
    }

    public int color(@NotNull RavenLogCategory category) {
        return style(category).colorRgb;
    }

    public void setVisible(@NotNull RavenLogCategory category, boolean visible) {
        style(category).visible = visible;
    }

    public void setColor(@NotNull RavenLogCategory category, int colorRgb) {
        style(category).colorRgb = colorRgb & 0xFFFFFF;
    }

    public @NotNull String serialize() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (RavenLogCategory category : RavenLogCategory.values()) {
            CategoryStyle s = style(category);
            if (!first) {
                sb.append(';');
            }
            first = false;
            sb.append(category.id())
                    .append(',')
                    .append(s.visible ? '1' : '0')
                    .append(',')
                    .append(s.colorRgb & 0xFFFFFF);
        }
        return sb.toString();
    }

    public static @NotNull RavenLogViewSettings deserialize(@NotNull String raw) {
        RavenLogViewSettings settings = new RavenLogViewSettings();
        if (raw == null || raw.isBlank()) {
            return settings;
        }

        String[] tokens = raw.split(";");
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String[] parts = token.split(",");
            if (parts.length < 3) {
                continue;
            }
            RavenLogCategory category = RavenLogCategory.fromId(parts[0].toLowerCase(Locale.ROOT));
            boolean visible = "1".equals(parts[1]) || "true".equalsIgnoreCase(parts[1]);
            int color = parseColor(parts[2], defaultStyle(category).colorRgb);
            settings.styles.put(category, new CategoryStyle(visible, color));
        }
        return settings;
    }

    public static @NotNull RavenLogViewSettings loadFromPlatform() {
        String raw = "";
        try {
            raw = Services.PLATFORM.getRavenLogViewSettingsRaw();
        } catch (Throwable ignored) {
            raw = "";
        }
        return deserialize(raw);
    }

    public static void saveToPlatform(@NotNull RavenLogViewSettings settings) {
        try {
            Services.PLATFORM.setRavenLogViewSettingsRaw(settings.serialize());
            Services.PLATFORM.saveClientConfig();
        } catch (Throwable ignored) {
        }
    }

    public @NotNull Map<RavenLogCategory, CategoryStyle> copyStyles() {
        EnumMap<RavenLogCategory, CategoryStyle> out = new EnumMap<>(RavenLogCategory.class);
        for (RavenLogCategory category : RavenLogCategory.values()) {
            CategoryStyle s = style(category);
            out.put(category, new CategoryStyle(s.visible, s.colorRgb));
        }
        return out;
    }

    private static int parseColor(@NotNull String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim()) & 0xFFFFFF;
        } catch (Throwable ignored) {
            return fallback & 0xFFFFFF;
        }
    }

    private static @NotNull CategoryStyle defaultStyle(@NotNull RavenLogCategory category) {
        return switch (category) {
            case SUMMON -> new CategoryStyle(true, 0xA7D5FF);
            case COURIER -> new CategoryStyle(true, 0xF6E58D);
            case COMBAT -> new CategoryStyle(true, 0xFF8A80);
            case PERCH -> new CategoryStyle(true, 0xB0BEC5);
            case ENDERPACK -> new CategoryStyle(true, 0xB39DDB);
            case CHEST -> new CategoryStyle(true, 0xFFCC80);
            case SYSTEM -> new CategoryStyle(true, 0xD7CCC8);
        };
    }
}

