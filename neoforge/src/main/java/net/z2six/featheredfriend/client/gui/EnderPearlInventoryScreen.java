// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/EnderPearlInventoryScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/EnderPearlInventoryScreen.java
 *
 * Helper for handling the "ender pearl" button logic from ScrollSealingScreen.
 *
 * For now it only logs when the pearl is clicked; later this class will own
 * the inventory GUI logic that pops up when the pearl is pressed.
 */
public final class EnderPearlInventoryScreen {

    private static final Logger LOG = LogUtils.getLogger();

    private EnderPearlInventoryScreen() {
        // Utility class
    }

    public static void handlePearlClicked(Minecraft minecraft) {
        try {
            if (minecraft == null) {
                LOG.warn("[EnderPearlInventoryScreen] handlePearlClicked called with null Minecraft instance");
                return;
            }

            LOG.info("[EnderPearlInventoryScreen] Ender pearl clicked – inventory GUI hook placeholder");
            // Future:
            //  - Open a custom inventory screen for attachments.
            //  - Pass through ScrollSealingScreen / menu context as needed.
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] handlePearlClicked failed", t);
        }
    }
}
