// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenVariant.java
package net.z2six.featheredfriend.entity.raven;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenVariant.java
 *
 * Raven visual variants.
 *
 * NORMAL: standard raven model/texture/animation
 * SCROLL: raven holding a scroll in its beak
 */
public enum RavenVariant {
    NORMAL(0),
    SCROLL(1);

    private final int id;

    RavenVariant(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static RavenVariant fromId(int id) {
        for (RavenVariant v : values()) {
            if (v.id == id) {
                return v;
            }
        }
        return NORMAL;
    }
}
