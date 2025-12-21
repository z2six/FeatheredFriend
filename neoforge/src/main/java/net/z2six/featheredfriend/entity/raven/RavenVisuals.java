// neoforge/src/main/java/net/z2six/featheredfriend/client/raven/RavenVisuals.java
package net.z2six.featheredfriend.client.raven;

import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.entity.raven.RavenVariant;

public final class RavenVisuals {

    private RavenVisuals() {
    }

    // Base namespace
    private static final String MODID = "featheredfriend";

    // NORMAL variant resources
    private static final ResourceLocation MODEL_NORMAL =
            ResourceLocation.fromNamespaceAndPath(MODID, "geo/raven.geo.json");
    private static final ResourceLocation ANIM_NORMAL =
            ResourceLocation.fromNamespaceAndPath(MODID, "animations/raven.animation.json");
    private static final ResourceLocation TEX_NORMAL =
            ResourceLocation.fromNamespaceAndPath(MODID, "textures/entity/raven.png");

    // SCROLL variant resources
    private static final ResourceLocation MODEL_SCROLL =
            ResourceLocation.fromNamespaceAndPath(MODID, "geo/ravenscroll.geo.json");
    private static final ResourceLocation ANIM_SCROLL =
            ResourceLocation.fromNamespaceAndPath(MODID, "animations/ravenscroll.animation.json");
    private static final ResourceLocation TEX_SCROLL =
            ResourceLocation.fromNamespaceAndPath(MODID, "textures/entity/ravenscroll.png");

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