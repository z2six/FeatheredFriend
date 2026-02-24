// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/FFNeoForgeClient.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/FFNeoForgeClient.java
 *
 * FFNeoForgeClient
 *
 * Client-only helper for NeoForge.
 *
 * IMPORTANT:
 *  - Screen registration is now handled exclusively by {@link ClientScreens}
 *    via its @EventBusSubscriber on the MOD bus.
 *  - This class is kept as a placeholder for future client-only hooks
 *    (keybindings, particles, renderers, etc).
 */
public final class FFNeoForgeClient {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNeoForgeClient() {
        // no instances
    }

    // No-op placeholder kept for parity with the NeoForge branch.
}
