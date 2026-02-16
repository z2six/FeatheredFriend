package net.z2six.featheredfriend.network;

import org.jetbrains.annotations.NotNull;

/**
 * Lightweight client-safe representation of a player's registered Raven Chest.
 */
public record RavenChestChoiceInfo(
        @NotNull String dimensionId,
        long blockPos,
        @NotNull String label,
        boolean available
) {
    public RavenChestChoiceInfo(@NotNull String dimensionId, long blockPos, @NotNull String label) {
        this(dimensionId, blockPos, label, true);
    }

    public boolean matches(@NotNull String dimensionId, long blockPos) {
        return this.blockPos == blockPos && this.dimensionId.equals(dimensionId);
    }
}
