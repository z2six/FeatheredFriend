// neoforge/src/main/java/net/z2six/featheredfriend/client/raven/RavenVisuals.java
package net.z2six.featheredfriend.client.raven;

import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.entity.raven.RavenArmorVisual;
import net.z2six.featheredfriend.entity.raven.RavenVariant;

public final class RavenVisuals {

    private RavenVisuals() {
    }

    // Base namespace
    private static final String MODID = "featheredfriend";

    // NORMAL variant resources
    private static final ResourceLocation MODEL_NORMAL =
            new ResourceLocation(MODID, "geo/raven.geo.json");
    private static final ResourceLocation ANIM_NORMAL =
            new ResourceLocation(MODID, "animations/raven.animation.json");
    private static final ResourceLocation TEX_NORMAL =
            new ResourceLocation(MODID, "textures/entity/raven.png");
    private static final ResourceLocation TEX_LEATHER =
            new ResourceLocation(MODID, "textures/entity/raven_leather.png");
    private static final ResourceLocation TEX_COPPER =
            new ResourceLocation(MODID, "textures/entity/raven_copper.png");
    private static final ResourceLocation TEX_IRON =
            new ResourceLocation(MODID, "textures/entity/raven_iron.png");
    private static final ResourceLocation TEX_GOLD =
            new ResourceLocation(MODID, "textures/entity/raven_gold.png");
    private static final ResourceLocation TEX_DIAMOND =
            new ResourceLocation(MODID, "textures/entity/raven_diamond.png");
    private static final ResourceLocation TEX_NETHERITE =
            new ResourceLocation(MODID, "textures/entity/raven_netherite.png");

    // SCROLL variant resources
    private static final ResourceLocation MODEL_SCROLL =
            new ResourceLocation(MODID, "geo/ravenscroll.geo.json");
    private static final ResourceLocation ANIM_SCROLL =
            new ResourceLocation(MODID, "animations/ravenscroll.animation.json");
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
        // Texture is no longer selected by SCROLL/NORMAL model variant.
        return TEX_NORMAL;
    }

    public static ResourceLocation texture(RavenArmorVisual armorVisual) {
        if (armorVisual == null) {
            return TEX_NORMAL;
        }
        return switch (armorVisual) {
            case LEATHER -> TEX_LEATHER;
            case COPPER -> TEX_COPPER;
            case IRON -> TEX_IRON;
            case GOLD -> TEX_GOLD;
            case DIAMOND -> TEX_DIAMOND;
            case NETHERITE -> TEX_NETHERITE;
            default -> TEX_NORMAL;
        };
    }
}
