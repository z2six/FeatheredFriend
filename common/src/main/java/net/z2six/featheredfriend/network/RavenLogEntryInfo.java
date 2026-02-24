package net.z2six.featheredfriend.network;

import org.jetbrains.annotations.NotNull;

/**
 * Lightweight client-safe Raven Log entry.
 */
public record RavenLogEntryInfo(
        long entryId,
        long gameTime,
        long createdAtMillis,
        @NotNull String categoryId,
        @NotNull String message
) {
}

