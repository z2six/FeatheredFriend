// forge/src/main/java/net/z2six/featheredfriend/registry/FFForgeParticles.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

/**
 * Forge particle registry.
 */
public final class FFForgeParticles {

    private static final Logger LOG = LogUtils.getLogger();

    private static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, Constants.MOD_ID);

    public static final RegistryObject<SimpleParticleType> ENDERPOP =
            PARTICLE_TYPES.register("enderpop", () -> {
                try {
                    return new SimpleParticleType(true);
                } catch (Throwable t) {
                    LOG.error("[FFForgeParticles] Failed creating SimpleParticleType(enderpop); falling back", t);
                    return new SimpleParticleType(true);
                }
            });

    public static final RegistryObject<SimpleParticleType> FEATHER =
            PARTICLE_TYPES.register("feather", () -> {
                try {
                    return new SimpleParticleType(true);
                } catch (Throwable t) {
                    LOG.error("[FFForgeParticles] Failed creating SimpleParticleType(feather); falling back", t);
                    return new SimpleParticleType(true);
                }
            });

    private FFForgeParticles() {
        // no instances
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("[FFForgeParticles] Registering particle types");
        try {
            PARTICLE_TYPES.register(modEventBus);
        } catch (Throwable t) {
            LOG.error("[FFForgeParticles] PARTICLE_TYPES.register(modEventBus) failed", t);
        }
    }
}
