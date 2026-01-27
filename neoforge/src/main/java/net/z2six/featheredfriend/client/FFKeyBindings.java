// neoforge/src/main/java/net/z2six/featheredfriend/client/FFKeyBindings.java
package net.z2six.featheredfriend.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.FeatheredFriendSettingsScreen;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import net.minecraft.network.chat.Component;
import net.z2six.featheredfriend.world.TamedRavenScrollWatcher;
import net.z2six.featheredfriend.network.FFNetwork;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/FFKeyBindings.java
 *
 * Client-only keybindings:
 *  - Open FeatheredFriend settings GUI.
 *  - Manual raven whistle (currently just plays the whistle sound locally).
 *
 * Registration:
 *  - Call FFKeyBindings.register(modEventBus) from the NeoForge side of your main mod class,
 *    inside the Dist.CLIENT branch.
 */
public final class FFKeyBindings {

    private static final Logger LOG = LogUtils.getLogger();

    private static KeyMapping OPEN_SETTINGS_KEY;
    private static KeyMapping WHISTLE_KEY;

    private FFKeyBindings() {
        // no-op
    }

    public static void register(@NotNull IEventBus modEventBus) {
        try {
            modEventBus.addListener(FFKeyBindings::onRegisterKeyMappings);
            NeoForge.EVENT_BUS.addListener(FFKeyBindings::onClientTick);
            LOG.debug("[FFKeyBindings] Registered key mapping + client tick listeners.");
        } catch (Throwable t) {
            LOG.error("[FFKeyBindings] register() failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Key mapping registration
    // ---------------------------------------------------------------------

    private static void onRegisterKeyMappings(@NotNull RegisterKeyMappingsEvent event) {
        try {
            // Key category for all FeatheredFriend keybinds.
            final String category = "key.categories." + Constants.MOD_ID;

            // Settings menu: default unbound (use UNKNOWN), player can bind manually.
            OPEN_SETTINGS_KEY = new KeyMapping(
                    "key." + Constants.MOD_ID + ".open_settings",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    category
            );

            // Manual whistle: default key 'K' (arbitrary but easy to reach).
            WHISTLE_KEY = new KeyMapping(
                    "key." + Constants.MOD_ID + ".whistle",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    category
            );

            event.register(OPEN_SETTINGS_KEY);
            event.register(WHISTLE_KEY);

            LOG.debug("[FFKeyBindings] Registered key mappings: open_settings, whistle.");

        } catch (Throwable t) {
            LOG.error("[FFKeyBindings] onRegisterKeyMappings failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Client tick: handle key presses
    // ---------------------------------------------------------------------

    private static void onClientTick(@NotNull ClientTickEvent.Post event) {
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
                        LOG.debug("[FFKeyBindings] Open-settings key pressed by '{}'", player.getGameProfile().getName());
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

    private static void handleWhistleKey(@NotNull Minecraft mc, @NotNull LocalPlayer player) {
        try {
            // Client should NOT decide eligibility (holding scroll vs failed jobs).
            // Always send request; server validates and will message player if denied.
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
                LOG.debug("[FFKeyBindings] Whistle key pressed: sending request to server player='{}'",
                        player.getGameProfile().getName());
            }

            try {
                FFNetwork.sendWhistleForRaven();
                LOG.debug("[FFKeyBindings] Sent whistle request packet for player='{}'",
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
