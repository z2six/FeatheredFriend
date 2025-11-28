// neoforge/src/main/java/net/z2six/featheredfriend/client/data/KnownPlayersClientCache.java
package net.z2six.featheredfriend.client.data;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * KnownPlayersClientCache
 *
 * Simple client-only cache of known player names received from the server.
 * ScrollSealingScreen reads from here to populate the recipient picker.
 */
public final class KnownPlayersClientCache {

    private static final Logger LOG = LogUtils.getLogger();

    private static List<String> NAMES = Collections.emptyList();

    private KnownPlayersClientCache() {
    }

    public static synchronized void update(@NotNull Iterable<String> names) {
        List<String> copy = new ArrayList<>();
        for (String n : names) {
            if (n != null && !n.isBlank()) {
                copy.add(n);
            }
        }
        copy.sort(String.CASE_INSENSITIVE_ORDER);
        NAMES = Collections.unmodifiableList(copy);
        LOG.debug("[KnownPlayersClientCache] Updated known player names: {}", NAMES);
    }

    public static synchronized @NotNull List<String> get() {
        return NAMES;
    }
}
