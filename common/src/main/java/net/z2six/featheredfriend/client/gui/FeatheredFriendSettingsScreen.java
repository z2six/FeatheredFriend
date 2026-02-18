// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/FeatheredFriendSettingsScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings screen:
 * - Gothic font override: client-only config (FFClientConfig).
 * - Chat disabled: server-owned, synced via FFPayloads.ClientState.
 * - Sends C2S payloads only when safe.
 */
public class FeatheredFriendSettingsScreen extends Screen {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int OPTION_HEIGHT = 20;
    private static final int OPTION_SPACING = 24;

    // client-only preference
    private boolean useVanillaFontForGothicText;

    // server-owned + synced
    private boolean chatDisabled;
    private int maxRavenChestsPerPlayer;
    private int ravenLogRetentionMinutes;
    private int ravenLogMaxBytesPerPlayer;
    private int enderpackDepositCooldownSeconds;
    private int scrollDeliveryCooldownSeconds;
    private boolean hasServerSettings = false;
    private boolean canEditChat = false;

    private Button gothicFontButton;
    private Button chatDisabledButton;
    private Button ravenChestsMinusButton;
    private Button ravenChestsValueButton;
    private Button ravenChestsPlusButton;
    private Button ravenLogRetentionMinusButton;
    private Button ravenLogRetentionValueButton;
    private Button ravenLogRetentionPlusButton;
    private Button ravenLogSizeMinusButton;
    private Button ravenLogSizeValueButton;
    private Button ravenLogSizePlusButton;
    private Button enderpackCooldownMinusButton;
    private Button enderpackCooldownValueButton;
    private Button enderpackCooldownPlusButton;
    private Button scrollDeliveryCooldownMinusButton;
    private Button scrollDeliveryCooldownValueButton;
    private Button scrollDeliveryCooldownPlusButton;
    private Button doneButton;

    private final List<Button> scrollableButtons = new ArrayList<>();
    private final Map<Button, Integer> scrollBaseY = new HashMap<>();
    private int scrollOffset = 0;
    private int maxScrollOffset = 0;
    private int scrollContentTop = 0;
    private int scrollContentBottom = 0;
    private int scrollContentHeight = 0;

    public FeatheredFriendSettingsScreen() {
        super(Component.translatable("screen.featheredfriend.settings.title"));
    }

    @Override
    protected void init() {
        super.init();

        LOG.debug("[FeatheredFriendSettingsScreen] init()");

        loadFromCacheAndMaybeRequestSync();

        this.scrollableButtons.clear();
        this.scrollBaseY.clear();
        this.maxScrollOffset = 0;
        this.scrollContentHeight = 0;

        int centerX = this.width / 2;
        this.scrollContentTop = 62;
        this.scrollContentBottom = Math.max(this.scrollContentTop + OPTION_HEIGHT, this.height - 52);
        int y = this.scrollContentTop;

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
        addScrollableButton(this.gothicFontButton, y);

        y += OPTION_SPACING;

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
            addScrollableButton(this.chatDisabledButton, y);
            y += OPTION_SPACING;

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

            addScrollableButton(this.ravenChestsMinusButton, y);
            addScrollableButton(this.ravenChestsValueButton, y);
            addScrollableButton(this.ravenChestsPlusButton, y);
            y += OPTION_SPACING;

            this.ravenLogRetentionMinusButton = Button.builder(Component.literal("-"), btn -> adjustRavenLogRetentionMinutes(-60))
                    .bounds(centerX - 100, y, 20, 20)
                    .build();
            this.ravenLogRetentionValueButton = Button.builder(textForRavenLogRetention(), btn -> {})
                    .bounds(centerX - 76, y, 152, 20)
                    .build();
            this.ravenLogRetentionValueButton.active = false;
            this.ravenLogRetentionPlusButton = Button.builder(Component.literal("+"), btn -> adjustRavenLogRetentionMinutes(60))
                    .bounds(centerX + 80, y, 20, 20)
                    .build();

            this.ravenLogRetentionMinusButton.active = editActive;
            this.ravenLogRetentionPlusButton.active = editActive;

            addScrollableButton(this.ravenLogRetentionMinusButton, y);
            addScrollableButton(this.ravenLogRetentionValueButton, y);
            addScrollableButton(this.ravenLogRetentionPlusButton, y);
            y += OPTION_SPACING;

            this.ravenLogSizeMinusButton = Button.builder(Component.literal("-"), btn -> adjustRavenLogMaxBytesPerPlayer(-(64 * 1024)))
                    .bounds(centerX - 100, y, 20, 20)
                    .build();
            this.ravenLogSizeValueButton = Button.builder(textForRavenLogSize(), btn -> {})
                    .bounds(centerX - 76, y, 152, 20)
                    .build();
            this.ravenLogSizeValueButton.active = false;
            this.ravenLogSizePlusButton = Button.builder(Component.literal("+"), btn -> adjustRavenLogMaxBytesPerPlayer(64 * 1024))
                    .bounds(centerX + 80, y, 20, 20)
                    .build();

            this.ravenLogSizeMinusButton.active = editActive;
            this.ravenLogSizePlusButton.active = editActive;

            addScrollableButton(this.ravenLogSizeMinusButton, y);
            addScrollableButton(this.ravenLogSizeValueButton, y);
            addScrollableButton(this.ravenLogSizePlusButton, y);
            y += OPTION_SPACING;

            this.enderpackCooldownMinusButton = Button.builder(Component.literal("-"), btn -> adjustEnderpackDepositCooldownSeconds(-5))
                    .bounds(centerX - 100, y, 20, 20)
                    .build();
            this.enderpackCooldownValueButton = Button.builder(textForEnderpackDepositCooldown(), btn -> {})
                    .bounds(centerX - 76, y, 152, 20)
                    .build();
            this.enderpackCooldownValueButton.active = false;
            this.enderpackCooldownPlusButton = Button.builder(Component.literal("+"), btn -> adjustEnderpackDepositCooldownSeconds(5))
                    .bounds(centerX + 80, y, 20, 20)
                    .build();

            this.enderpackCooldownMinusButton.active = editActive;
            this.enderpackCooldownPlusButton.active = editActive;

            addScrollableButton(this.enderpackCooldownMinusButton, y);
            addScrollableButton(this.enderpackCooldownValueButton, y);
            addScrollableButton(this.enderpackCooldownPlusButton, y);
            y += OPTION_SPACING;

            this.scrollDeliveryCooldownMinusButton = Button.builder(Component.literal("-"), btn -> adjustScrollDeliveryCooldownSeconds(-5))
                    .bounds(centerX - 100, y, 20, 20)
                    .build();
            this.scrollDeliveryCooldownValueButton = Button.builder(textForScrollDeliveryCooldown(), btn -> {})
                    .bounds(centerX - 76, y, 152, 20)
                    .build();
            this.scrollDeliveryCooldownValueButton.active = false;
            this.scrollDeliveryCooldownPlusButton = Button.builder(Component.literal("+"), btn -> adjustScrollDeliveryCooldownSeconds(5))
                    .bounds(centerX + 80, y, 20, 20)
                    .build();

            this.scrollDeliveryCooldownMinusButton.active = editActive;
            this.scrollDeliveryCooldownPlusButton.active = editActive;

            addScrollableButton(this.scrollDeliveryCooldownMinusButton, y);
            addScrollableButton(this.scrollDeliveryCooldownValueButton, y);
            addScrollableButton(this.scrollDeliveryCooldownPlusButton, y);
            y += OPTION_SPACING;
        } else {
            this.chatDisabledButton = null;
            this.ravenChestsMinusButton = null;
            this.ravenChestsValueButton = null;
            this.ravenChestsPlusButton = null;
            this.ravenLogRetentionMinusButton = null;
            this.ravenLogRetentionValueButton = null;
            this.ravenLogRetentionPlusButton = null;
            this.ravenLogSizeMinusButton = null;
            this.ravenLogSizeValueButton = null;
            this.ravenLogSizePlusButton = null;
            this.enderpackCooldownMinusButton = null;
            this.enderpackCooldownValueButton = null;
            this.enderpackCooldownPlusButton = null;
            this.scrollDeliveryCooldownMinusButton = null;
            this.scrollDeliveryCooldownValueButton = null;
            this.scrollDeliveryCooldownPlusButton = null;
        }

        this.doneButton = Button.builder(Component.translatable("gui.done"), btn -> onClose())
                .bounds(centerX - 75, this.height - 28, 150, OPTION_HEIGHT)
                .build();
        this.addRenderableWidget(this.doneButton);

        recalculateScrollBounds();
        applyScrollToWidgets();
    }

    private void addScrollableButton(@NotNull Button button, int baseY) {
        this.addRenderableWidget(button);
        this.scrollableButtons.add(button);
        this.scrollBaseY.put(button, Integer.valueOf(baseY));
    }

    private void recalculateScrollBounds() {
        int maxBottom = this.scrollContentTop;
        for (Button button : this.scrollableButtons) {
            Integer baseY = this.scrollBaseY.get(button);
            if (baseY == null) {
                continue;
            }
            maxBottom = Math.max(maxBottom, baseY.intValue() + OPTION_HEIGHT);
        }

        int viewportHeight = Math.max(1, this.scrollContentBottom - this.scrollContentTop);
        this.scrollContentHeight = Math.max(0, maxBottom - this.scrollContentTop);
        this.maxScrollOffset = Math.max(0, this.scrollContentHeight - viewportHeight);
        this.scrollOffset = Mth.clamp(this.scrollOffset, 0, this.maxScrollOffset);
    }

    private void applyScrollToWidgets() {
        for (Button button : this.scrollableButtons) {
            Integer baseY = this.scrollBaseY.get(button);
            if (baseY == null) {
                continue;
            }
            int y = baseY.intValue() - this.scrollOffset;
            button.setY(y);
            button.visible = y >= this.scrollContentTop && (y + OPTION_HEIGHT) <= this.scrollContentBottom;
        }
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
            if (this.ravenLogRetentionValueButton != null) {
                this.ravenLogRetentionValueButton.setMessage(textForRavenLogRetention());
            }
            if (this.ravenLogRetentionMinusButton != null) {
                this.ravenLogRetentionMinusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.ravenLogRetentionPlusButton != null) {
                this.ravenLogRetentionPlusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.ravenLogSizeValueButton != null) {
                this.ravenLogSizeValueButton.setMessage(textForRavenLogSize());
            }
            if (this.ravenLogSizeMinusButton != null) {
                this.ravenLogSizeMinusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.ravenLogSizePlusButton != null) {
                this.ravenLogSizePlusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.enderpackCooldownValueButton != null) {
                this.enderpackCooldownValueButton.setMessage(textForEnderpackDepositCooldown());
            }
            if (this.enderpackCooldownMinusButton != null) {
                this.enderpackCooldownMinusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.enderpackCooldownPlusButton != null) {
                this.enderpackCooldownPlusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.scrollDeliveryCooldownValueButton != null) {
                this.scrollDeliveryCooldownValueButton.setMessage(textForScrollDeliveryCooldown());
            }
            if (this.scrollDeliveryCooldownMinusButton != null) {
                this.scrollDeliveryCooldownMinusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.scrollDeliveryCooldownPlusButton != null) {
                this.scrollDeliveryCooldownPlusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
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
                this.ravenLogRetentionMinutes = Services.PLATFORM.getRavenLogRetentionMinutesClient();
                this.ravenLogMaxBytesPerPlayer = Services.PLATFORM.getRavenLogMaxBytesPerPlayerClient();
                this.enderpackDepositCooldownSeconds = Services.PLATFORM.getEnderpackDepositCooldownSecondsClient();
                this.scrollDeliveryCooldownSeconds = Services.PLATFORM.getScrollDeliveryCooldownSecondsClient();
            } else {
                // while syncing, default to enabled + no perms
                this.chatDisabled = false;
                this.canEditChat = false;
                this.maxRavenChestsPerPlayer = 0;
                this.ravenLogRetentionMinutes = 0;
                this.ravenLogMaxBytesPerPlayer = 0;
                this.enderpackDepositCooldownSeconds = 0;
                this.scrollDeliveryCooldownSeconds = 0;
            }

            LOG.debug("[FeatheredFriendSettingsScreen] refreshFromCacheOnly: hasServerSettings={} chatDisabled={} maxRavenChestsPerPlayer={} ravenLogRetentionMinutes={} ravenLogMaxBytesPerPlayer={} enderpackDepositCooldownSeconds={} scrollDeliveryCooldownSeconds={} canEditChat={}",
                    hasServerSettings, chatDisabled, maxRavenChestsPerPlayer, ravenLogRetentionMinutes, ravenLogMaxBytesPerPlayer, enderpackDepositCooldownSeconds, scrollDeliveryCooldownSeconds, canEditChat);

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
            ravenLogRetentionMinutes = 0;
            ravenLogMaxBytesPerPlayer = 0;
            enderpackDepositCooldownSeconds = 0;
            scrollDeliveryCooldownSeconds = 0;
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

    private Component textForRavenLogRetention() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.raven_log_retention.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.raven_log_retention",
                Integer.valueOf(ravenLogRetentionMinutes)
        );
    }

    private Component textForRavenLogSize() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.raven_log_size.syncing");
        }
        int kb = Math.max(0, ravenLogMaxBytesPerPlayer / 1024);
        return Component.translatable(
                "screen.featheredfriend.settings.raven_log_size",
                Integer.valueOf(kb)
        );
    }

    private Component textForEnderpackDepositCooldown() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.enderpack_deposit_cooldown.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.enderpack_deposit_cooldown",
                Integer.valueOf(enderpackDepositCooldownSeconds)
        );
    }

    private Component textForScrollDeliveryCooldown() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.scroll_delivery_cooldown.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.scroll_delivery_cooldown",
                Integer.valueOf(scrollDeliveryCooldownSeconds)
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

    private void adjustRavenLogRetentionMinutes(int deltaMinutes) {
        try {
            if (!hasServerSettings || !canEditChat || !isConnectionReady()) {
                return;
            }
            int newValue = Math.max(0, Math.min(60 * 24 * 90, this.ravenLogRetentionMinutes + deltaMinutes));
            if (newValue == this.ravenLogRetentionMinutes) {
                return;
            }
            this.ravenLogRetentionMinutes = newValue;
            if (this.ravenLogRetentionValueButton != null) {
                this.ravenLogRetentionValueButton.setMessage(textForRavenLogRetention());
            }
            Services.PLATFORM.sendSetRavenLogRetentionMinutes(newValue);
            LOG.debug("[FeatheredFriendSettingsScreen] Sent SetRavenLogRetentionMinutesPayload -> {}", newValue);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] adjustRavenLogRetentionMinutes failed safely", t);
        }
    }

    private void adjustRavenLogMaxBytesPerPlayer(int deltaBytes) {
        try {
            if (!hasServerSettings || !canEditChat || !isConnectionReady()) {
                return;
            }
            int newValue = Math.max(0, Math.min(4 * 1024 * 1024, this.ravenLogMaxBytesPerPlayer + deltaBytes));
            if (newValue == this.ravenLogMaxBytesPerPlayer) {
                return;
            }
            this.ravenLogMaxBytesPerPlayer = newValue;
            if (this.ravenLogSizeValueButton != null) {
                this.ravenLogSizeValueButton.setMessage(textForRavenLogSize());
            }
            Services.PLATFORM.sendSetRavenLogMaxBytesPerPlayer(newValue);
            LOG.debug("[FeatheredFriendSettingsScreen] Sent SetRavenLogMaxBytesPerPlayerPayload -> {}", newValue);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] adjustRavenLogMaxBytesPerPlayer failed safely", t);
        }
    }

    private void adjustEnderpackDepositCooldownSeconds(int deltaSeconds) {
        try {
            if (!hasServerSettings || !canEditChat || !isConnectionReady()) {
                return;
            }
            int newValue = Math.max(0, Math.min(86_400, this.enderpackDepositCooldownSeconds + deltaSeconds));
            if (newValue == this.enderpackDepositCooldownSeconds) {
                return;
            }
            this.enderpackDepositCooldownSeconds = newValue;
            if (this.enderpackCooldownValueButton != null) {
                this.enderpackCooldownValueButton.setMessage(textForEnderpackDepositCooldown());
            }
            Services.PLATFORM.sendSetEnderpackDepositCooldownSeconds(newValue);
            LOG.debug("[FeatheredFriendSettingsScreen] Sent SetEnderpackDepositCooldownSecondsPayload -> {}", newValue);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] adjustEnderpackDepositCooldownSeconds failed safely", t);
        }
    }

    private void adjustScrollDeliveryCooldownSeconds(int deltaSeconds) {
        try {
            if (!hasServerSettings || !canEditChat || !isConnectionReady()) {
                return;
            }
            int newValue = Math.max(0, Math.min(86_400, this.scrollDeliveryCooldownSeconds + deltaSeconds));
            if (newValue == this.scrollDeliveryCooldownSeconds) {
                return;
            }
            this.scrollDeliveryCooldownSeconds = newValue;
            if (this.scrollDeliveryCooldownValueButton != null) {
                this.scrollDeliveryCooldownValueButton.setMessage(textForScrollDeliveryCooldown());
            }
            Services.PLATFORM.sendSetScrollDeliveryCooldownSeconds(newValue);
            LOG.debug("[FeatheredFriendSettingsScreen] Sent SetScrollDeliveryCooldownSecondsPayload -> {}", newValue);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] adjustScrollDeliveryCooldownSeconds failed safely", t);
        }
    }

    private static Component onOff(boolean enabled) {
        return Component.translatable(enabled ? "options.on" : "options.off");
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.maxScrollOffset > 0 && isMouseOverOptionsArea(mouseX, mouseY)) {
            int delta = (int) Math.round(scrollY * 18.0D);
            if (delta != 0) {
                this.scrollOffset = Mth.clamp(this.scrollOffset - delta, 0, this.maxScrollOffset);
                applyScrollToWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isMouseOverOptionsArea(double mouseX, double mouseY) {
        int left = (this.width / 2) - 120;
        int right = (this.width / 2) + 120;
        return mouseX >= left
                && mouseX <= right
                && mouseY >= this.scrollContentTop
                && mouseY <= this.scrollContentBottom;
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

        if (this.maxScrollOffset > 0) {
            drawScrollBar(guiGraphics);
        }
    }

    private void drawScrollBar(@NotNull GuiGraphics guiGraphics) {
        int trackX0 = (this.width / 2) + 108;
        int trackX1 = trackX0 + 4;
        int trackY0 = this.scrollContentTop;
        int trackY1 = this.scrollContentBottom;
        int trackHeight = Math.max(1, trackY1 - trackY0);

        guiGraphics.fill(trackX0, trackY0, trackX1, trackY1, 0x55333333);

        int viewportHeight = Math.max(1, this.scrollContentBottom - this.scrollContentTop);
        int thumbHeight = Mth.clamp(
                Math.round((float) viewportHeight * ((float) viewportHeight / (float) Math.max(viewportHeight, this.scrollContentHeight))),
                14,
                trackHeight
        );
        int thumbTravel = Math.max(0, trackHeight - thumbHeight);
        int thumbY = trackY0;
        if (this.maxScrollOffset > 0 && thumbTravel > 0) {
            thumbY = trackY0 + Math.round((this.scrollOffset / (float) this.maxScrollOffset) * thumbTravel);
        }

        guiGraphics.fill(trackX0, thumbY, trackX1, thumbY + thumbHeight, 0xCCBBBBBB);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
