package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

/**
 * Forge client-only helper placeholder.
 *
 * Screen registration is handled by {@link ClientScreens}.
 */
public final class FFNeoForgeClient {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNeoForgeClient() {
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        LOG.debug("[FFNeoForgeClient] onClientSetup invoked; no action (screens handled by ClientScreens).");
    }
}
