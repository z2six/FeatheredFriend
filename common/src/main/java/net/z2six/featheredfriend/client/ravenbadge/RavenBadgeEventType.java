package net.z2six.featheredfriend.client.ravenbadge;

import org.jetbrains.annotations.NotNull;

/**
 * One-shot raven badge HUD events.
 */
public enum RavenBadgeEventType {
    NONE(0),
    HIT_KEEP_SCROLL(1),
    HIT_LOSE_SCROLL(2),
    HOSTILE_WITH_SCROLL(3),
    HOSTILE_NO_SCROLL(4),
    PLAYER_NOT_FOUND(5),
    DELIVERY_SUCCESS(6),
    HIT(7),
    DODGED(8);

    private final int id;

    RavenBadgeEventType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static @NotNull RavenBadgeEventType fromId(int id) {
        for (RavenBadgeEventType value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        return NONE;
    }
}
