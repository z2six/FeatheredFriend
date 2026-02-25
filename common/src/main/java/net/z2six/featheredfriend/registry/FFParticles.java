// common/src/main/java/net/z2six/featheredfriend/registry/FFParticles.java
package net.z2six.featheredfriend.registry;

import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.particles.ParticleTypes;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

/**
 * FFParticles
 *
 * Common, loader-agnostic particle ids + lookup helpers.
 *
 * Loader-specific modules (NeoForge/Fabric) must register the particle types.
 */
public final class FFParticles {

    private static final Logger LOG = LogUtils.getLogger();

    public static final String ENDERPOP_ID = "enderpop";
    public static final String FEATHER_ID = "feather";

    public static final ResourceLocation ENDERPOP_RL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, ENDERPOP_ID);
    public static final ResourceLocation FEATHER_RL =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, FEATHER_ID);

    public static SimpleParticleType getEnderpop() {
        return resolveSimple(ENDERPOP_RL, ParticleTypes.POOF);
    }

    public static SimpleParticleType getFeather() {
        return resolveSimple(FEATHER_RL, ParticleTypes.POOF);
    }

    private static SimpleParticleType resolveSimple(ResourceLocation id, SimpleParticleType fallback) {
        try {
            ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(id);
            if (type instanceof SimpleParticleType simple) {
                return simple;
            }
            LOG.warn("[FFParticles] Particle id '{}' is not a SimpleParticleType; using fallback", id);
        } catch (Throwable t) {
            LOG.warn("[FFParticles] Failed to resolve particle id '{}': {}", id, t.toString());
        }
        return fallback;
    }

    private FFParticles() {
        // no-op
    }
}
