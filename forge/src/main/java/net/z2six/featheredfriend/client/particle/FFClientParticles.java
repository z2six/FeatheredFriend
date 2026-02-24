// neoforge/src/main/java/net/z2six/featheredfriend/client/particle/FFClientParticles.java
package net.z2six.featheredfriend.client.particle;

import com.mojang.logging.LogUtils;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.z2six.featheredfriend.entity.raven.modules.FeatherParticles;
import net.z2six.featheredfriend.registry.FFNeoForgeParticles;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/particle/FFClientParticles.java
 *
 * Client-only particle provider registration (NeoForge 1.21.1 safe).
 *
 * IMPORTANT:
 * - No @EventBusSubscriber. Register this listener from FeatheredFriend.java only on Dist.CLIENT.
 * - Registers the particle *provider* (factory), not the particle type.
 */
public final class FFClientParticles {

    private static final Logger LOG = LogUtils.getLogger();

    private FFClientParticles() {
    }

    public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        LOG.debug("[FFClientParticles] Registering particle providers");
        try {
            // Enderpop (existing)
            event.registerSpriteSet(FFNeoForgeParticles.ENDERPOP.get(), EnderpopParticle.Factory::new);
            LOG.debug("[FFClientParticles] Registered provider for ENDERPOP");

            // Feather burst: use FeatherParticles as the provider factory.
            event.registerSpriteSet(
                    FFNeoForgeParticles.FEATHER.get(),
                    FeatherParticles::createProvider
            );
            LOG.debug("[FFClientParticles] Registered provider for FEATHER");

        } catch (Throwable t) {
            LOG.error("[FFClientParticles] Failed registering particle providers", t);
        }
    }
}
