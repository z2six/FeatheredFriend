// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeParticles.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.registry.FFParticles;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/registry/FFNeoForgeParticles.java
 *
 * NeoForge particle registry.
 *
 * NOTE: Registry is Registries.PARTICLE_TYPE => ParticleType
 * so DeferredRegister must be DeferredRegister ParticleType.
 */
public final class FFNeoForgeParticles {

    private static final Logger LOG = LogUtils.getLogger();

    private static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, Constants.MOD_ID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ENDERPOP =
            PARTICLE_TYPES.register(FFParticles.ENDERPOP_ID, () -> new SimpleParticleType(true));

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FEATHER =
            PARTICLE_TYPES.register(FFParticles.FEATHER_ID, () -> new SimpleParticleType(true));

    private FFNeoForgeParticles() {
        // no instances
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("[FFNeoForgeParticles] Registering particle types");
        try {
            PARTICLE_TYPES.register(modEventBus);
        } catch (Throwable t) {
            LOG.error("[FFNeoForgeParticles] PARTICLE_TYPES.register(modEventBus) failed", t);
        }
    }
}
