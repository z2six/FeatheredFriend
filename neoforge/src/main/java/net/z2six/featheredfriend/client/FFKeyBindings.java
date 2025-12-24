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
            LOG.info("[FFKeyBindings] Registered key mapping + client tick listeners.");
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

            LOG.info("[FFKeyBindings] Registered key mappings: open_settings, whistle.");

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

    private static void handleWhistleKey(@NotNull Minecraft mc, @NotNull LocalPlayer player) {
        try {
            LOG.info("[FFKeyBindings] Whistle key pressed by '{}'", player.getGameProfile().getName());

            if (mc.level == null) {
                return;
            }

            // Play the raven whistle sound locally at the player's position.
            try {
                ResourceLocation soundId = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "raven.whistle");
                SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(soundId);
                if (event != null) {
                    mc.level.playLocalSound(
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            event,
                            SoundSource.PLAYERS,
                            0.6F,
                            1.0F,
                            false
                    );
                    LOG.debug("[FFKeyBindings] Played local raven whistle sound at player position.");
                } else {
                    LOG.warn("[FFKeyBindings] Whistle sound '{}' not found in registry.", soundId);
                }
            } catch (Throwable soundErr) {
                LOG.warn("[FFKeyBindings] Failed to play whistle sound safely: {}", soundErr.toString());
            }

            // Future: send a packet to the server to actually command the raven(s).

        } catch (Throwable t) {
            LOG.error("[FFKeyBindings] handleWhistleKey failed safely", t);
        }
    }
}
