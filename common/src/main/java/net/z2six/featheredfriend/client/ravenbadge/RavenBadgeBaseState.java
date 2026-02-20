package net.z2six.featheredfriend.client.ravenbadge;

import org.jetbrains.annotations.NotNull;

/**
 * Persistent (looping) raven badge HUD states.
 */
public enum RavenBadgeBaseState {
    HIDDEN(0),
    IDLE_NO_SCROLL(1),
    MOVING_NO_SCROLL(2),
    QUEUED_WITH_SCROLL(3),
    DELIVERING_WITH_SCROLL(4),
    DEAD(5);

    private final int id;

    RavenBadgeBaseState(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static @NotNull RavenBadgeBaseState fromId(int id) {
        for (RavenBadgeBaseState value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        return HIDDEN;
    }
}
