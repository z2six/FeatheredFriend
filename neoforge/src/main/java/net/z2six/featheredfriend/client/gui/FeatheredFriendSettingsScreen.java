// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/FeatheredFriendSettingsScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.z2six.featheredfriend.world.FeatheredFriendSettingsData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/gui/FeatheredFriendSettingsScreen.java
 *
 * Simple settings menu for FeatheredFriend.
 *
 * Current options:
 *  - Auto-summon raven when holding sealed scroll (global, world-owned).
 *  - Disable global player chat (global, world-owned, only visible/editable
 *    to players with high permission level, e.g. server owner / OP).
 *
 * Notes:
 *  - Settings are stored via FeatheredFriendSettingsData (SavedData) on the
 *    OVERWORLD, so they persist with the world save.
 *  - In integrated singleplayer, this GUI can both view and edit settings.
 *  - On remote servers, reading/writing world-owned settings from the
 *    client is not yet implemented; this screen will best-effort log errors.
 */
public class FeatheredFriendSettingsScreen extends Screen {

    private static final Logger LOG = LogUtils.getLogger();

    private boolean autoSummon;
    private boolean chatDisabled;

    private boolean hasServerSettings = false;
    private boolean canEditChat = false;

    private Button autoSummonButton;
    private Button chatDisabledButton;

    public FeatheredFriendSettingsScreen() {
        super(Component.literal("FeatheredFriend Settings"));
    }

    @Override
    protected void init() {
        super.init();

        LOG.info("[FeatheredFriendSettingsScreen] Opening settings screen.");

        // Load settings from world if possible (integrated server case).
        loadSettingsFromWorld();

        int centerX = this.width / 2;
        int y = this.height / 4;

        // Auto-summon toggle (always visible)
        this.autoSummonButton = Button.builder(
                        textForAutoSummon(),
                        btn -> {
                            autoSummon = !autoSummon;
                            btn.setMessage(textForAutoSummon());
                            LOG.info("[FeatheredFriendSettingsScreen] Toggled autoSummonOnScroll -> {}", autoSummon);
                        })
                .bounds(centerX - 100, y, 200, 20)
                .build();
        this.addRenderableWidget(this.autoSummonButton);

        y += 24;

        // Chat disabled toggle: only show if player has permissions (OP / server owner).
        if (canEditChat) {
            this.chatDisabledButton = Button.builder(
                            textForChatDisabled(),
                            btn -> {
                                chatDisabled = !chatDisabled;
                                btn.setMessage(textForChatDisabled());
                                LOG.info("[FeatheredFriendSettingsScreen] Toggled chatDisabled -> {}", chatDisabled);
                            })
                    .bounds(centerX - 100, y, 200, 20)
                    .build();
            this.addRenderableWidget(this.chatDisabledButton);
            y += 24;
        } else {
            this.chatDisabledButton = null;
        }

        // Done button
        Button done = Button.builder(
                        Component.literal("Done"),
                        btn -> {
                            saveSettingsToWorld();
                            onClose();
                        })
                .bounds(centerX - 75, this.height - 40, 150, 20)
                .build();
        this.addRenderableWidget(done);
    }

    private Component textForAutoSummon() {
        return Component.literal("Auto-summon raven on scroll: " + (autoSummon ? "ON" : "OFF"));
    }

    private Component textForChatDisabled() {
        return Component.literal("Disable global player chat: " + (chatDisabled ? "ON" : "OFF"));
    }

    private void loadSettingsFromWorld() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                autoSummon = FeatheredFriendSettingsData.DEFAULT_AUTO_SUMMON;
                chatDisabled = FeatheredFriendSettingsData.DEFAULT_CHAT_DISABLED;
                hasServerSettings = false;
                canEditChat = false;
                LOG.warn("[FeatheredFriendSettingsScreen] Minecraft instance null; using defaults.");
                return;
            }

            Player clientPlayer = mc.player;

            // Integrated singleplayer server: we can read world-owned settings directly.
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null) {
                ServerLevel overworld = server.overworld();
                if (overworld != null) {
                    FeatheredFriendSettingsData data = FeatheredFriendSettingsData.get(overworld);
                    autoSummon = data.isAutoSummonOnScrollEnabled();
                    chatDisabled = data.isChatDisabled();
                    hasServerSettings = true;

                    boolean hasPerms = false;
                    try {
                        if (clientPlayer != null) {
                            hasPerms = clientPlayer.hasPermissions(4);
                        }
                    } catch (Throwable ignored) {
                    }
                    canEditChat = hasPerms;

                    LOG.info("[FeatheredFriendSettingsScreen] Loaded settings from world: autoSummon={} chatDisabled={} canEditChat={}",
                            autoSummon, chatDisabled, canEditChat);
                    return;
                }
            }

            // Remote server / no accessible overworld.
            autoSummon = FeatheredFriendSettingsData.DEFAULT_AUTO_SUMMON;
            chatDisabled = FeatheredFriendSettingsData.DEFAULT_CHAT_DISABLED;
            hasServerSettings = false;
            canEditChat = false;

            LOG.warn("[FeatheredFriendSettingsScreen] No accessible ServerLevel; using defaults (remote server or menu).");

        } catch (Throwable t) {
            autoSummon = FeatheredFriendSettingsData.DEFAULT_AUTO_SUMMON;
            chatDisabled = FeatheredFriendSettingsData.DEFAULT_CHAT_DISABLED;
            hasServerSettings = false;
            canEditChat = false;
            LOG.error("[FeatheredFriendSettingsScreen] loadSettingsFromWorld failed safely", t);
        }
    }

    private void saveSettingsToWorld() {
        try {
            if (!hasServerSettings) {
                // Nothing we can save from the client side here yet.
                LOG.warn("[FeatheredFriendSettingsScreen] No server settings available; skipping save.");
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }

            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) {
                LOG.warn("[FeatheredFriendSettingsScreen] saveSettingsToWorld: no singleplayer server; skipping save.");
                return;
            }

            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                LOG.warn("[FeatheredFriendSettingsScreen] saveSettingsToWorld: overworld null; skipping save.");
                return;
            }

            FeatheredFriendSettingsData data = FeatheredFriendSettingsData.get(overworld);
            data.setAutoSummonOnScrollEnabled(autoSummon);

            if (canEditChat) {
                data.setChatDisabled(chatDisabled);
            }

            LOG.info("[FeatheredFriendSettingsScreen] Saved settings to world: autoSummon={} chatDisabled={} (canEditChat={})",
                    autoSummon, chatDisabled, canEditChat);

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] saveSettingsToWorld failed safely", t);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Fill dim background
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // Let super draw buttons, etc.
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Title at the top
        guiGraphics.drawCenteredString(
                this.font,
                this.title,
                this.width / 2,
                20,
                0xFFFFFF
        );
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        // Let this pause the game in singleplayer (typical options screen behaviour).
        return true;
    }
}
