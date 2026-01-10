// forge/src/main/java/net/z2six/featheredfriend/client/raven/RavenVisuals.java
package net.z2six.featheredfriend.entity.raven;

import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.entity.raven.RavenVariant;

public final class RavenVisuals {

    private RavenVisuals() {
    }

    // Base namespace
    private static final String MODID = "featheredfriend";

    // NORMAL variant resources
    private static final ResourceLocation MODEL_NORMAL = new ResourceLocation(MODID, "geo/raven.geo.json");
    private static final ResourceLocation ANIM_NORMAL  = new ResourceLocation(MODID, "animations/raven.animation.json");
    private static final ResourceLocation TEX_NORMAL   = new ResourceLocation(MODID, "textures/entity/raven.png");

    private static final ResourceLocation MODEL_SCROLL = new ResourceLocation(MODID, "geo/ravenscroll.geo.json");
    private static final ResourceLocation ANIM_SCROLL  = new ResourceLocation(MODID, "animations/ravenscroll.animation.json");
    private static final ResourceLocation TEX_SCROLL   = new ResourceLocation(MODID, "textures/entity/ravenscroll.png");

    public static ResourceLocation model(RavenVariant variant) {
        if (variant == RavenVariant.SCROLL) {
            return MODEL_SCROLL;
        }
        return MODEL_NORMAL;
    }

    public static ResourceLocation animation(RavenVariant variant) {
        if (variant == RavenVariant.SCROLL) {
            return ANIM_SCROLL;
        }
        return ANIM_NORMAL;
    }

    public static ResourceLocation texture(RavenVariant variant) {
        if (variant == RavenVariant.SCROLL) {
            return TEX_SCROLL;
        }
        return TEX_NORMAL;
    }
}