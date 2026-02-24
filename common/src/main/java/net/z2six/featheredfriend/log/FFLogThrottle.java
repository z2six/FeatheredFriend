package net.z2six.featheredfriend.log;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny, dependency-free log throttle helper.
 *
 * Intended use:
 *  - Wrap warning/info logs that could happen inside tick loops or repeated network callbacks.
 *  - Keep important signals visible in production logs without spamming.
 *
 * This is deliberately simple: a process-local (non-persistent) time gate keyed by a small set of strings.
 */
public final class FFLogThrottle {

    private static final ConcurrentHashMap<String, Long> LAST_LOG_AT_MS = new ConcurrentHashMap<>();

    private FFLogThrottle() {
    }

    public static boolean shouldLog(@NotNull String key, long intervalMs) {
        if (key == null || key.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_LOG_AT_MS.get(key);
        if (last != null && (now - last) < intervalMs) {
            return false;
        }
        LAST_LOG_AT_MS.put(key, now);
        return true;
    }

    public static void clearAll() {
        LAST_LOG_AT_MS.clear();
    }
}

