// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/FeatheredFriendSettingsScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.z2six.featheredfriend.config.FFClientConfig;
import net.z2six.featheredfriend.network.FFPayloads;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Settings screen:
 * - Auto-summon: client-only config (FFClientConfig).
 * - Chat disabled: server-owned, synced via FFPayloads.ClientState.
 * - Sends C2S payloads only when safe.
 */
public class FeatheredFriendSettingsScreen extends Screen {

    private static final Logger LOG = LogUtils.getLogger();

    // client-only preference
    private boolean autoSummonOnScroll;

    // server-owned + synced
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

        LOG.info("[FeatheredFriendSettingsScreen] init()");

        loadFromCacheAndMaybeRequestSync();

        int centerX = this.width / 2;
        int y = this.height / 4;

        this.autoSummonButton = Button.builder(
                        textForAutoSummon(),
                        btn -> {
                            autoSummonOnScroll = !autoSummonOnScroll;
                            btn.setMessage(textForAutoSummon());

                            try {
                                FFClientConfig.setAutoSummonOnScroll(autoSummonOnScroll);
                                FFClientConfig.save();
                                LOG.info("[FeatheredFriendSettingsScreen] Updated client config autoSummonOnScroll -> {}", autoSummonOnScroll);
                            } catch (Throwable t) {
                                LOG.error("[FeatheredFriendSettingsScreen] Failed to update autoSummonOnScroll client config", t);
                            }
                        })
                .bounds(centerX - 100, y, 200, 20)
                .build();
        this.addRenderableWidget(this.autoSummonButton);

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

                                    PacketDistributor.sendToServer(new FFPayloads.SetChatDisabledPayload(newValue));
                                    LOG.info("[FeatheredFriendSettingsScreen] Sent SetChatDisabledPayload -> {}", newValue);
                                } catch (Throwable t) {
                                    LOG.error("[FeatheredFriendSettingsScreen] Failed to send chatDisabled toggle", t);
                                }
                            })
                    .bounds(centerX - 100, y, 200, 20)
                    .build();

            this.chatDisabledButton.active = hasServerSettings && isConnectionReady();
            this.addRenderableWidget(this.chatDisabledButton);
            y += 24;
        } else {
            this.chatDisabledButton = null;
        }

        Button done = Button.builder(Component.literal("Done"), btn -> onClose())
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
            LOG.info("[FeatheredFriendSettingsScreen] onServerSettingsUpdated()");

            refreshFromCacheOnly();

            if (this.autoSummonButton != null) {
                this.autoSummonButton.setMessage(textForAutoSummon());
            }

            if (this.chatDisabledButton != null) {
                this.chatDisabledButton.setMessage(textForChatDisabled());
                this.chatDisabledButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }

            if (this.chatDisabledButton == null && this.canEditChat) {
                LOG.info("[FeatheredFriendSettingsScreen] Chat button absent but perms now true; rebuilding widgets");
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

            PacketDistributor.sendToServer(new FFPayloads.RequestServerSettingsPayload());
            LOG.info("[FeatheredFriendSettingsScreen] Requested server settings sync");
        } catch (Throwable t) {
            LOG.warn("[FeatheredFriendSettingsScreen] requestServerSettings failed safely: {}", t.toString());
        }
    }

    private void refreshFromCacheOnly() {
        try {
            // client-only preference
            autoSummonOnScroll = FFClientConfig.isAutoSummonOnScroll();

            // server synced
            this.hasServerSettings = FFPayloads.ClientState.hasSynced();
            if (this.hasServerSettings) {
                this.chatDisabled = FFPayloads.ClientState.isChatDisabled();
                this.canEditChat = FFPayloads.ClientState.canEditChat();
            } else {
                // conservative while syncing: show disabled + no perms
                this.chatDisabled = true;
                this.canEditChat = false;
            }

            LOG.debug("[FeatheredFriendSettingsScreen] refreshFromCacheOnly: hasServerSettings={} autoSummon={} chatDisabled={} canEditChat={}",
                    hasServerSettings, autoSummonOnScroll, chatDisabled, canEditChat);

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
                    LOG.info("[FeatheredFriendSettingsScreen] No synced server settings yet; requesting sync");
                    requestServerSettings();
                } else {
                    LOG.info("[FeatheredFriendSettingsScreen] No synced server settings yet; connection not ready (client tick will sync)");
                }
            }

        } catch (Throwable t) {
            hasServerSettings = false;
            canEditChat = false;
            chatDisabled = true;
            autoSummonOnScroll = FFClientConfig.DEFAULT_AUTO_SUMMON_ON_SCROLL;
            LOG.error("[FeatheredFriendSettingsScreen] loadFromCacheAndMaybeRequestSync failed safely", t);
        }
    }

    private Component textForAutoSummon() {
        return Component.literal("Auto-summon raven on scroll: " + (autoSummonOnScroll ? "ON" : "OFF"));
    }

    private Component textForChatDisabled() {
        if (!hasServerSettings) {
            return Component.literal("Disable global player chat: (syncing...)");
        }
        return Component.literal("Disable global player chat: " + (chatDisabled ? "ON" : "OFF"));
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        try {
            String status = hasServerSettings ? "Server settings synced" : "Waiting for server settings...";
            guiGraphics.drawCenteredString(this.font, Component.literal(status), this.width / 2, 44, 0xAAAAAA);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
