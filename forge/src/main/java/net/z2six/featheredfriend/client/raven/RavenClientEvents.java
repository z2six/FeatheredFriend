// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/raven/RavenClientEvents.java
package net.z2six.featheredfriend.client.raven;

import com.mojang.logging.LogUtils;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.z2six.featheredfriend.client.raven.render.RavenLinkEffigyRenderer;
import net.z2six.featheredfriend.client.block.mailbox.render.MailboxBlockRenderer;
import net.z2six.featheredfriend.client.block.ravenchest.render.RavenChestBlockRenderer;
import net.z2six.featheredfriend.client.raven.render.RavenRenderer;
import net.z2six.featheredfriend.registry.FFNeoForgeBlockEntities;
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
        LOG.debug("[RavenClientEvents] Registering Raven entity renderer");
        try {
            event.registerEntityRenderer(FFNeoForgeEntities.RAVEN.get(), RavenRenderer::new);
            LOG.debug("[RavenClientEvents] Raven renderer registered");
        } catch (Throwable t) {
            LOG.error("[RavenClientEvents] Failed to register Raven renderer", t);
        }
        try {
            event.registerEntityRenderer(FFNeoForgeEntities.RAVEN_LINK_EFFIGY.get(), RavenLinkEffigyRenderer::new);
            LOG.debug("[RavenClientEvents] Raven Link effigy renderer registered");
        } catch (Throwable t) {
            LOG.error("[RavenClientEvents] Failed to register Raven Link effigy renderer", t);
        }

        try {
            event.registerBlockEntityRenderer(FFNeoForgeBlockEntities.RAVEN_CHEST.get(), RavenChestBlockRenderer::new);
            LOG.debug("[RavenClientEvents] Raven chest block entity renderer registered");
        } catch (Throwable t) {
            LOG.error("[RavenClientEvents] Failed to register raven chest block entity renderer", t);
        }

        try {
            event.registerBlockEntityRenderer(FFNeoForgeBlockEntities.MAILBOX.get(), MailboxBlockRenderer::new);
            LOG.debug("[RavenClientEvents] Mailbox block entity renderer registered");
        } catch (Throwable t) {
            LOG.error("[RavenClientEvents] Failed to register mailbox block entity renderer", t);
        }
    }
}
