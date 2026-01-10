package net.z2six.featheredfriend.client.raven;

import com.mojang.logging.LogUtils;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.z2six.featheredfriend.client.raven.render.RavenRenderer;
import net.z2six.featheredfriend.registry.FFForgeEntities; // <-- Forge registry (rename if yours differs)
import org.slf4j.Logger;

/**
 * forge/src/main/java/net/z2six/featheredfriend/client/raven/RavenClientEvents.java
 *
 * Client-only event listeners for Raven rendering (Forge 1.20.1).
 */
public final class RavenClientEvents {

    private static final Logger LOG = LogUtils.getLogger();

    private RavenClientEvents() {
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        LOG.info("[RavenClientEvents] Registering Raven entity renderer");
        try {
            event.registerEntityRenderer(FFForgeEntities.RAVEN.get(), RavenRenderer::new);
            LOG.info("[RavenClientEvents] Raven renderer registered");
        } catch (Throwable t) {
            LOG.error("[RavenClientEvents] Failed to register Raven renderer", t);
        }
    }
}
