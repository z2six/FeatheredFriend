// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/FeatheredFriendSettingsScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Settings screen:
 * - Gothic font override: client-only config (FFClientConfig).
 * - Chat disabled: server-owned, synced via FFPayloads.ClientState.
 * - Sends C2S payloads only when safe.
 */
public class FeatheredFriendSettingsScreen extends Screen {

    private static final Logger LOG = LogUtils.getLogger();

    // client-only preference
    private boolean useVanillaFontForGothicText;

    // server-owned + synced
    private boolean chatDisabled;
    private int maxRavenChestsPerPlayer;
    private boolean hasServerSettings = false;
    private boolean canEditChat = false;

    private Button gothicFontButton;
    private Button chatDisabledButton;
    private Button ravenChestsMinusButton;
    private Button ravenChestsValueButton;
    private Button ravenChestsPlusButton;

    public FeatheredFriendSettingsScreen() {
        super(Component.translatable("screen.featheredfriend.settings.title"));
    }

    @Override
    protected void init() {
        super.init();

        LOG.debug("[FeatheredFriendSettingsScreen] init()");

        loadFromCacheAndMaybeRequestSync();

        int centerX = this.width / 2;
        int y = this.height / 4;

        this.gothicFontButton = Button.builder(
                        textForGothicFont(),
                        btn -> {
                            useVanillaFontForGothicText = !useVanillaFontForGothicText;
                            btn.setMessage(textForGothicFont());

                            try {
                                Services.PLATFORM.setUseVanillaFontForGothicText(useVanillaFontForGothicText);
                                Services.PLATFORM.saveClientConfig();
                                LOG.debug("[FeatheredFriendSettingsScreen] Updated client config useVanillaFontForGothicText -> {}",
                                        useVanillaFontForGothicText);
                            } catch (Throwable t) {
                                LOG.error("[FeatheredFriendSettingsScreen] Failed to update useVanillaFontForGothicText client config", t);
                            }
                        })
                .bounds(centerX - 100, y, 200, 20)
                .build();
        this.addRenderableWidget(this.gothicFontButton);

        y += 24;

        if (canEditChat) {
            this.chatDisabledButton = Button.builder(
                            textForChatDisabled(),
                            btn -> {
                                boolean newValue = !chatDisabled;
                                chatDisabled = newValue;
                                btn.setMessage(textForChatDisabled());

                                try {
                                    if (!hasServerSettings) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] Chat toggled but server settings not synced yet; requesting sync instead");
                                        requestServerSettings();
                                        return;
                                    }

                                    if (!isConnectionReady()) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] Connection not ready; cannot send chat toggle right now");
                                        return;
                                    }

                                    Services.PLATFORM.sendSetChatDisabled(newValue);
                                    LOG.debug("[FeatheredFriendSettingsScreen] Sent SetChatDisabledPayload -> {}", newValue);
                                } catch (Throwable t) {
                                    LOG.error("[FeatheredFriendSettingsScreen] Failed to send chatDisabled toggle", t);
                                }
                            })
                    .bounds(centerX - 100, y, 200, 20)
                    .build();

            this.chatDisabledButton.active = hasServerSettings && isConnectionReady();
            this.addRenderableWidget(this.chatDisabledButton);
            y += 24;

            this.ravenChestsMinusButton = Button.builder(Component.literal("-"), btn -> adjustRavenChestCap(-1))
                    .bounds(centerX - 100, y, 20, 20)
                    .build();
            this.ravenChestsValueButton = Button.builder(textForRavenChestCap(), btn -> {})
                    .bounds(centerX - 76, y, 152, 20)
                    .build();
            this.ravenChestsValueButton.active = false;
            this.ravenChestsPlusButton = Button.builder(Component.literal("+"), btn -> adjustRavenChestCap(1))
                    .bounds(centerX + 80, y, 20, 20)
                    .build();

            boolean editActive = hasServerSettings && isConnectionReady();
            this.ravenChestsMinusButton.active = editActive;
            this.ravenChestsPlusButton.active = editActive;

            this.addRenderableWidget(this.ravenChestsMinusButton);
            this.addRenderableWidget(this.ravenChestsValueButton);
            this.addRenderableWidget(this.ravenChestsPlusButton);
            y += 24;
        } else {
            this.chatDisabledButton = null;
            this.ravenChestsMinusButton = null;
            this.ravenChestsValueButton = null;
            this.ravenChestsPlusButton = null;
        }

        Button done = Button.builder(Component.translatable("gui.done"), btn -> onClose())
                .bounds(centerX - 75, this.height - 40, 150, 20)
                .build();
        this.addRenderableWidget(done);
    }

    /**
     * Call from wherever you receive the server settings payload (or if you have a polling refresh).
     * Safe to call multiple times.
     */
    public void onServerSettingsUpdated() {
        try {
            LOG.debug("[FeatheredFriendSettingsScreen] onServerSettingsUpdated()");

            refreshFromCacheOnly();

            if (this.gothicFontButton != null) {
                this.gothicFontButton.setMessage(textForGothicFont());
            }

            if (this.chatDisabledButton != null) {
                this.chatDisabledButton.setMessage(textForChatDisabled());
                this.chatDisabledButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.ravenChestsValueButton != null) {
                this.ravenChestsValueButton.setMessage(textForRavenChestCap());
            }
            if (this.ravenChestsMinusButton != null) {
                this.ravenChestsMinusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.ravenChestsPlusButton != null) {
                this.ravenChestsPlusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }

            if (this.chatDisabledButton == null && this.canEditChat) {
                LOG.debug("[FeatheredFriendSettingsScreen] Chat button absent but perms now true; rebuilding widgets");
                tryRebuildWidgets();
            }

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] onServerSettingsUpdated failed safely", t);
        }
    }

    private void tryRebuildWidgets() {
        try {
            this.clearWidgets();
            this.init();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] tryRebuildWidgets failed safely", t);
        }
    }

    private boolean isConnectionReady() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return false;
            if (mc.player == null) return false;
            if (mc.level == null) return false;
            return mc.getConnection() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestServerSettings() {
        try {
            if (!isConnectionReady()) {
                LOG.warn("[FeatheredFriendSettingsScreen] requestServerSettings skipped: connection not ready");
                return;
            }

            Services.PLATFORM.requestServerSettingsSync();
            LOG.debug("[FeatheredFriendSettingsScreen] Requested server settings sync");
        } catch (Throwable t) {
            LOG.warn("[FeatheredFriendSettingsScreen] requestServerSettings failed safely: {}", t.toString());
        }
    }

    private void refreshFromCacheOnly() {
        try {
            // client-only preference
            useVanillaFontForGothicText = Services.PLATFORM.isUseVanillaFontForGothicText();

            // server synced
            this.hasServerSettings = Services.PLATFORM.hasServerSettingsSynced();
            if (this.hasServerSettings) {
                this.chatDisabled = Services.PLATFORM.isChatDisabledClient();
                this.canEditChat = Services.PLATFORM.canEditChat();
                this.maxRavenChestsPerPlayer = Services.PLATFORM.getMaxRavenChestsPerPlayerClient();
            } else {
                // while syncing, default to enabled + no perms
                this.chatDisabled = false;
                this.canEditChat = false;
                this.maxRavenChestsPerPlayer = 0;
            }

            LOG.debug("[FeatheredFriendSettingsScreen] refreshFromCacheOnly: hasServerSettings={} chatDisabled={} maxRavenChestsPerPlayer={} canEditChat={}",
                    hasServerSettings, chatDisabled, maxRavenChestsPerPlayer, canEditChat);

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] refreshFromCacheOnly failed safely", t);
        }
    }

    private void loadFromCacheAndMaybeRequestSync() {
        try {
            refreshFromCacheOnly();

            if (!hasServerSettings) {
                // Request sync if we're actually in-world.
                if (isConnectionReady()) {
                    LOG.debug("[FeatheredFriendSettingsScreen] No synced server settings yet; requesting sync");
                    requestServerSettings();
                } else {
                    LOG.debug("[FeatheredFriendSettingsScreen] No synced server settings yet; connection not ready (client tick will sync)");
                }
            }

        } catch (Throwable t) {
            hasServerSettings = false;
            canEditChat = false;
            chatDisabled = false;
            maxRavenChestsPerPlayer = 0;
            LOG.error("[FeatheredFriendSettingsScreen] loadFromCacheAndMaybeRequestSync failed safely", t);
        }
    }

    private Component textForGothicFont() {
        return Component.translatable(
                "screen.featheredfriend.settings.vanilla_font",
                onOff(useVanillaFontForGothicText)
        );
    }

    private Component textForChatDisabled() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.chat_disabled.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.chat_disabled",
                onOff(chatDisabled)
        );
    }

    private Component textForRavenChestCap() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.raven_chests_per_player.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.raven_chests_per_player",
                Integer.valueOf(maxRavenChestsPerPlayer)
        );
    }

    private void adjustRavenChestCap(int delta) {
        try {
            if (!hasServerSettings || !canEditChat || !isConnectionReady()) {
                return;
            }
            int newValue = Math.max(0, Math.min(64, this.maxRavenChestsPerPlayer + delta));
            if (newValue == this.maxRavenChestsPerPlayer) {
                return;
            }
            this.maxRavenChestsPerPlayer = newValue;
            if (this.ravenChestsValueButton != null) {
                this.ravenChestsValueButton.setMessage(textForRavenChestCap());
            }
            Services.PLATFORM.sendSetMaxRavenChestsPerPlayer(newValue);
            LOG.debug("[FeatheredFriendSettingsScreen] Sent SetMaxRavenChestsPerPlayerPayload -> {}", newValue);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] adjustRavenChestCap failed safely", t);
        }
    }

    private static Component onOff(boolean enabled) {
        return Component.translatable(enabled ? "options.on" : "options.off");
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        try {
            Component status = hasServerSettings
                    ? Component.translatable("screen.featheredfriend.settings.status.synced")
                    : Component.translatable("screen.featheredfriend.settings.status.waiting");
            guiGraphics.drawCenteredString(this.font, status, this.width / 2, 44, 0xAAAAAA);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
