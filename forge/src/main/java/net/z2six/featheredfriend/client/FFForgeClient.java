package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

/**
 * Forge client-only helper placeholder.
 *
 * Screen registration is handled by {@link ClientScreens}.
 */
public final class FFForgeClient {

    private static final Logger LOG = LogUtils.getLogger();

    private FFForgeClient() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        LOG.debug("[FFForgeClient] onClientSetup invoked; no action (screens handled by ClientScreens).");
    }

    // Add this to satisfy FeatheredFriend.java
    public static void onRegisterMenuScreens(FMLClientSetupEvent event) {
        LOG.debug("[FFForgeClient] onRegisterMenuScreens invoked; delegating to ClientScreens.");
        ClientScreens.onClientSetup(event);
    }
}
