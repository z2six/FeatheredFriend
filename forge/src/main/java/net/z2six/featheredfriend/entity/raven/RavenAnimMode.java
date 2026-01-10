// forge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenAnimMode.java
package net.z2six.featheredfriend.entity.raven;

/**
 * forge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenAnimMode.java
 *
 * Animation mode for the Raven GeckoLib controller.
 *
 * AUTO: Choose based on entity state (airborne/grounded).
 * NO_AIR: Force "no_air" animation.
 * IN_AIR: Force "in_air" animation.
 */
public enum RavenAnimMode {
    AUTO(0),
    NO_AIR(1),
    IN_AIR(2);

    private final int id;

    RavenAnimMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static RavenAnimMode fromId(int id) {
        for (RavenAnimMode m : values()) {
            if (m.id == id) {
                return m;
            }
        }
        return AUTO;
    }
}
