package net.z2six.featheredfriend.log;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Encodes translatable Raven log messages into a single persisted string.
 * Backward-compatible: non-prefixed values are treated as legacy plain text.
 */
public final class RavenLogTextCodec {

    private static final String PREFIX = "@i18n:";
    private static final String SEP = "|";

    private RavenLogTextCodec() {
    }

    public record Decoded(@NotNull String key, @NotNull List<String> args) {
    }

    public static @NotNull String packTranslatable(@NotNull String key, @Nullable Object... args) {
        if (key == null || key.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(PREFIX).append(encodeB64(key));
        if (args != null) {
            for (Object arg : args) {
                String s = (arg == null) ? "" : String.valueOf(arg);
                sb.append(SEP).append(encodeB64(s));
            }
        }
        return sb.toString();
    }

    public static boolean isPackedTranslatable(@NotNull String raw) {
        return raw != null && raw.startsWith(PREFIX);
    }

    public static @Nullable Decoded decodeTranslatable(@NotNull String raw) {
        if (!isPackedTranslatable(raw)) {
            return null;
        }
        try {
            String body = raw.substring(PREFIX.length());
            if (body.isBlank()) {
                return null;
            }
            String[] parts = body.split("\\|", -1);
            if (parts.length == 0) {
                return null;
            }
            String key = decodeB64(parts[0]);
            if (key == null || key.isBlank()) {
                return null;
            }
            List<String> args = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                args.add(decodeB64(parts[i]));
            }
            return new Decoded(key, List.copyOf(args));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static @NotNull String resolveForClient(@NotNull String packedOrRaw) {
        if (packedOrRaw == null || packedOrRaw.isBlank()) {
            return "";
        }
        Decoded decoded = decodeTranslatable(packedOrRaw);
        if (decoded == null) {
            return packedOrRaw;
        }
        try {
            Object[] args = decoded.args().toArray(new Object[0]);
            return Component.translatable(decoded.key(), args).getString();
        } catch (Throwable ignored) {
            return packedOrRaw;
        }
    }

    private static @NotNull String encodeB64(@NotNull String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static @NotNull String decodeB64(@NotNull String value) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
