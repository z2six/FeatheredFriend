// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/FFNeoForgeClient.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
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

    /**
     * Previously this method registered the ScrollSealingScreen, but that caused
     * a duplicate registration because ClientScreens also subscribes to
     * RegisterMenuScreensEvent via @EventBusSubscriber.
     *
     * To avoid IllegalStateException("Duplicate attempt to register screen"),
     * we no longer register any screens here.
     *
     * If this method is still wired via:
     *   modEventBus.addListener(FFNeoForgeClient::onRegisterMenuScreens);
     * it will simply log and return without doing anything.
     */
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        LOG.debug("[FFNeoForgeClient] onRegisterMenuScreens invoked; "
                + "screen registration is handled by ClientScreens. No action taken.");
        // Intentionally left empty to avoid duplicate screen registration.
    }
}
