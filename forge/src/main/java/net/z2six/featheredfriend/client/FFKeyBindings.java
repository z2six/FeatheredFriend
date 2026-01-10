// FFKeyBindings.java
package net.z2six.featheredfriend.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.FeatheredFriendSettingsScreen;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.world.TamedRavenScrollWatcher;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Client-only keybindings:
 *  - Open FeatheredFriend settings GUI.
 *  - Manual raven whistle (sends server request; server validates).
 */
public final class FFKeyBindings {

    private static final Logger LOG = LogUtils.getLogger();

    private static KeyMapping OPEN_SETTINGS_KEY;
    private static KeyMapping WHISTLE_KEY;

    private FFKeyBindings() {
        // no-op
    }

    public static void register(IEventBus modEventBus) {
        try {
            modEventBus.addListener(FFKeyBindings::onRegisterKeyMappings);
            MinecraftForge.EVENT_BUS.addListener(FFKeyBindings::onClientTick);
            LOG.info("[FFKeyBindings] Registered key mapping + client tick listeners.");
        } catch (Throwable t) {
            LOG.error("[FFKeyBindings] register() failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Key mapping registration
    // ---------------------------------------------------------------------

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        try {
            final String category = "key.categories." + Constants.MOD_ID;

            OPEN_SETTINGS_KEY = new KeyMapping(
                    "key." + Constants.MOD_ID + ".open_settings",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    category
            );

            WHISTLE_KEY = new KeyMapping(
                    "key." + Constants.MOD_ID + ".whistle",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    category
            );

            event.register(OPEN_SETTINGS_KEY);
            event.register(WHISTLE_KEY);

            LOG.info("[FFKeyBindings] Registered key mappings: open_settings, whistle.");

        } catch (Throwable t) {
            LOG.error("[FFKeyBindings] onRegisterKeyMappings failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Client tick: handle key presses
    // ---------------------------------------------------------------------

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }

            LocalPlayer player = mc.player;
            if (player == null) {
                return;
            }

            // Open settings GUI
            if (OPEN_SETTINGS_KEY != null) {
                while (OPEN_SETTINGS_KEY.consumeClick()) {
                    try {
                        LOG.info("[FFKeyBindings] Open-settings key pressed by '{}'", player.getGameProfile().getName());
                        mc.setScreen(new FeatheredFriendSettingsScreen());
                    } catch (Throwable t) {
                        LOG.error("[FFKeyBindings] Failed to open settings screen", t);
                    }
                }
            }

            // Manual whistle
            if (WHISTLE_KEY != null) {
                while (WHISTLE_KEY.consumeClick()) {
                    handleWhistleKey(mc, player);
                }
            }

        } catch (Throwable t) {
            LOG.error("[FFKeyBindings] onClientTick failed safely", t);
        }
    }

    private static void handleWhistleKey(Minecraft mc, LocalPlayer player) {
        try {
            boolean holdingClientSide = false;
            try {
                holdingClientSide = TamedRavenScrollWatcher.isHoldingSealedScroll(player);
            } catch (Throwable t) {
                LOG.warn("[FFKeyBindings] Whistle: failed to check isHoldingSealedScroll client-side for player='{}': {}",
                        player.getGameProfile().getName(), t.toString());
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("[FFKeyBindings] Whistle key pressed: sending request to server (clientHoldingSealedScroll={}) player='{}'",
                        holdingClientSide, player.getGameProfile().getName());
            } else {
                LOG.info("[FFKeyBindings] Whistle key pressed: sending request to server player='{}'",
                        player.getGameProfile().getName());
            }

            try {
                FFNetwork.sendWhistleForRaven();
                LOG.info("[FFKeyBindings] Sent whistle request packet for player='{}'",
                        player.getGameProfile().getName());
            } catch (Throwable netErr) {
                LOG.error("[FFKeyBindings] Failed to send whistle packet for player='{}' (safe): {}",
                        player.getGameProfile().getName(), netErr.toString());

                try {
                    player.displayClientMessage(
                            Component.literal("[FeatheredFriend] Failed to whistle for your raven (network error)."),
                            true
                    );
                } catch (Throwable msgErr) {
                    LOG.warn("[FFKeyBindings] Failed to show whistle error message to '{}': {}",
                            player.getGameProfile().getName(), msgErr.toString());
                }
            }

        } catch (Throwable t) {
            LOG.error("[FFKeyBindings] handleWhistleKey failed safely", t);
        }
    }
}
