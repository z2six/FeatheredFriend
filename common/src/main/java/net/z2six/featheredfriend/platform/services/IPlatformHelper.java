// common/src/main/java/net/z2six/featheredfriend/platform/services/IPlatformHelper.java
package net.z2six.featheredfriend.platform.services;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    default void openScrollSealingScreen(@NotNull ServerPlayer player) {
        // No-op on platforms without the menu.
    }

    default void openSealStampScreen(@NotNull ServerPlayer player) {
        // No-op on platforms without the menu.
    }

    default void openScrollViewScreen(@NotNull ServerPlayer player) {
        // No-op on platforms without the menu.
    }
}
