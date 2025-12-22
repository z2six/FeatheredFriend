// neoforge/src/main/java/net/z2six/featheredfriend/client/particle/FFClientParticles.java
package net.z2six.featheredfriend.client.particle;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
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
 * - Registers the particle *providers* (factories), not the particle types.
 *
 * Wiring:
 * - ENDERPOP uses EnderpopParticle.Factory (standard sprite-set driven).
 * - FEATHER uses FeatherParticles as a "burst" provider:
 *     Each FEATHER particle spawn on the client calls spawnFeatherBurst(...)
 *     and returns null. The helper then spawns FEATHERS_PER_BURST feather
 *     quads with custom motion and alpha fade.
 */
public final class FFClientParticles {

    private static final Logger LOG = LogUtils.getLogger();

    private FFClientParticles() {
        // no instances
    }

    public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        LOG.info("[FFClientParticles] Registering particle providers");
        try {
            // ENDERPOP provider (unchanged from your existing setup)
            event.registerSpriteSet(FFNeoForgeParticles.ENDERPOP.get(), EnderpopParticle.Factory::new);
            LOG.info("[FFClientParticles] Registered provider for ENDERPOP");

            // FEATHER provider:
            // Treat each FEATHER spawn as a "burst marker" and let FeatherParticles
            // decide how many actual feather quads to spawn (FEATHERS_PER_BURST).
            event.registerSpriteSet(FFNeoForgeParticles.FEATHER.get(), spriteSet ->
                    (type, level, x, y, z, vx, vy, vz) -> {
                        try {
                            FeatherParticles.spawnFeatherBurst(x, y, z);
                        } catch (Throwable t) {
                            if (level != null && level.getGameTime() % 100L == 0L) {
                                LOG.warn("[FFClientParticles] FEATHER provider burst spawn failed safely: {}", t.toString());
                            }
                        }
                        // We added our own particles via FeatherParticles; returning null tells
                        // the engine not to add an extra "default" particle.
                        return null;
                    }
            );
            LOG.info("[FFClientParticles] Registered provider for FEATHER (burst via FeatherParticles)");

        } catch (Throwable t) {
            LOG.error("[FFClientParticles] Failed registering particle providers", t);
        }
    }
}
