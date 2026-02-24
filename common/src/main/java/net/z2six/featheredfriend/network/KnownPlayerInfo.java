// common/src/main/java/net/z2six/featheredfriend/network/KnownPlayerInfo.java
package net.z2six.featheredfriend.network;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record KnownPlayerInfo(@NotNull UUID uuid, @NotNull String name, int mailboxCount) {
}
