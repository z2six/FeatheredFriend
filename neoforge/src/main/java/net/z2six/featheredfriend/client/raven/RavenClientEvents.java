// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/raven/RavenClientEvents.java
package net.z2six.featheredfriend.client.raven;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.z2six.featheredfriend.client.raven.render.RavenRenderer;
import net.z2six.featheredfriend.registry.FFNeoForgeEntities;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/raven/RavenClientEvents.java
 *
 * Client-only event listeners for Raven rendering.
 *
 * This class must only be referenced/loaded on physical client.
 * FeatheredFriend.java guards listener registration using FMLEnvironment.dist.
 */
public final class RavenClientEvents {

    private static final Logger LOG = LogUtils.getLogger();

    private RavenClientEvents() {
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        LOG.info("[RavenClientEvents] Registering Raven entity renderer");
        try {
            event.registerEntityRenderer(FFNeoForgeEntities.RAVEN.get(), RavenRenderer::new);
            LOG.info("[RavenClientEvents] Raven renderer registered");
        } catch (Throwable t) {
            LOG.error("[RavenClientEvents] Failed to register Raven renderer", t);
        }
    }
}
