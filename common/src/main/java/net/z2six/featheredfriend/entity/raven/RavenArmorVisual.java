package net.z2six.featheredfriend.entity.raven;

/**
 * Visual armor state for raven texture selection.
 */
public enum RavenArmorVisual {
    NONE(0),
    LEATHER(1),
    COPPER(2),
    IRON(3),
    GOLD(4),
    DIAMOND(5),
    NETHERITE(6);

    private final int id;

    RavenArmorVisual(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static RavenArmorVisual fromId(int id) {
        for (RavenArmorVisual v : values()) {
            if (v.id == id) {
                return v;
            }
        }
        return NONE;
    }
}
