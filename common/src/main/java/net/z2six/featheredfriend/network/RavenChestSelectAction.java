package net.z2six.featheredfriend.network;

import org.jetbrains.annotations.NotNull;

/**
 * Purpose/mode for the Raven Chest selection screen and confirm packet.
 */
public enum RavenChestSelectAction {
    ENDERPACK_DEPOSIT(0),
    PERCH_ASSIGNMENT(1);

    private final int id;

    RavenChestSelectAction(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public @NotNull String titleKey() {
        return switch (this) {
            case ENDERPACK_DEPOSIT -> "screen.featheredfriend.raven_chest_select.title";
            case PERCH_ASSIGNMENT -> "screen.featheredfriend.raven_chest_select.title.perch_assignment";
        };
    }

    public static @NotNull RavenChestSelectAction fromId(int id) {
        for (RavenChestSelectAction value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        return ENDERPACK_DEPOSIT;
    }
}
