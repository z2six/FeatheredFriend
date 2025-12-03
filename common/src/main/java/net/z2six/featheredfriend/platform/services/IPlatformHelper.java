// MainFile: common/src/main/java/net/z2six/featheredfriend/platform/services/IPlatformHelper.java
package net.z2six.featheredfriend.platform.services;

import net.minecraft.server.level.ServerPlayer;
import net.z2six.featheredfriend.calendar.CalendarDefinition;

/**
 * Platform abstraction for MultiLoader.
 */
public interface IPlatformHelper {

    /**
     * Gets the name of the current platform
     *
     * @return The name of the current platform.
     */
    String getPlatformName();

    /**
     * Checks if a mod with the given id is loaded.
     *
     * @param modId The mod to check if it is loaded.
     * @return True if the mod is loaded, false otherwise.
     */
    boolean isModLoaded(String modId);

    /**
     * Check if the game is currently in a development environment.
     *
     * @return True if in a development environment, false otherwise.
     */
    boolean isDevelopmentEnvironment();

    /**
     * Gets the name of the environment type as a string.
     *
     * @return The name of the environment type.
     */
    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /**
     * Open the Scroll Sealing screen for the given player.
     * Called from UnsealedScrollItem#use on the logical server.
     */
    void openScrollSealingScreen(ServerPlayer player);

    /**
     * Open the Seal Stamp carving screen for the given player.
     * Called from SealStampItem#use on the logical server.
     */
    void openSealStampScreen(ServerPlayer player);

    /**
     * Returns the active calendar definition for this runtime.
     *
     * On NeoForge, this is backed by a SERVER config (ModConfig.Type.SERVER),
     * which is server-authoritative and synced to clients, so all sides see
     * the same month names and era suffix.
     *
     * On other platforms, this may simply return CalendarDefinition.defaultDefinition().
     */
    CalendarDefinition getCalendarDefinition();
}
