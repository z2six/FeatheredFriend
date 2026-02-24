// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/FeatheredFriendSettingsScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.z2six.featheredfriend.client.font.ScrollUiFontMode;
import net.z2six.featheredfriend.client.ravenbadge.RavenStatusGuiAnchor;
import net.z2six.featheredfriend.client.ravenbadge.RavenStatusGuiVisualMode;
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
    private static final int OPTION_ROW_WIDTH = 312; // 20% wider than previous 260
    private static final int OPTION_ROW_HALF_WIDTH = OPTION_ROW_WIDTH / 2;
    private static final int STEP_BUTTON_WIDTH = 31; // 20% wider than previous 26
    private static final int STEP_GAP = 4;
    private static final int STEP_VALUE_WIDTH =
            OPTION_ROW_WIDTH - (STEP_BUTTON_WIDTH * 2) - (STEP_GAP * 2);
    private static final int SHIFT_STEP_MULTIPLIER = 5;
    private static final int OPTIONS_HOVER_SIDE_PADDING = 20;
    private static final int SCROLLBAR_X_OFFSET = OPTION_ROW_HALF_WIDTH + 8;
    private static final int RAVEN_STATUS_PREVIEW_BASE_WIDTH = 128;
    private static final int RAVEN_STATUS_PREVIEW_BASE_HEIGHT = 42;
    private static final float RAVEN_STATUS_PREVIEW_SCALE = 0.75F;

    // client-only preference
    private @NotNull ScrollUiFontMode scrollUiFontMode = ScrollUiFontMode.JACQUARD;
    private int ravenStatusGuiX;
    private int ravenStatusGuiY;
    private @NotNull RavenStatusGuiAnchor ravenStatusGuiAnchor = RavenStatusGuiAnchor.TOP_LEFT;
    private @NotNull RavenStatusGuiVisualMode ravenStatusGuiVisualMode = RavenStatusGuiVisualMode.BADGE_AND_TEXT;
    private boolean statusBadgeRepositionMode = false;
    private boolean statusBadgeDragging = false;
    private int statusBadgeDragOffsetX = 0;
    private int statusBadgeDragOffsetY = 0;

    // server-owned + synced
    private boolean chatDisabled;
    private boolean enableSuspiciousFeather;
    private boolean enableSuspiciousChest;
    private boolean enableRavenArmor;
    private boolean enableMailbox;
    private int wildRavensPerPlayer;
    private int ravenLinkDurationSeconds;
    private int maxRavenChestsPerPlayer;
    private int ravenLogRetentionMinutes;
    private int ravenLogMaxBytesPerPlayer;
    private int enderpackDepositCooldownSeconds;
    private int scrollDeliveryCooldownSeconds;
    private int courierTimeoutRetrySeconds;
    private int brushRavenCooldownSeconds;
    private boolean hasServerSettings = false;
    private boolean canEditChat = false;

    private Button gothicFontButton;
    private Button ravenStatusGuiRepositionButton;
    private Button ravenStatusGuiAnchorButton;
    private Button ravenStatusGuiVisualModeButton;
    private Button chatDisabledButton;
    private Button enableSuspiciousFeatherButton;
    private Button enableSuspiciousChestButton;
    private Button enableRavenArmorButton;
    private Button enableMailboxButton;
    private Button wildRavensMinusButton;
    private Button wildRavensValueButton;
    private Button wildRavensPlusButton;
    private Button ravenLinkDurationMinusButton;
    private Button ravenLinkDurationValueButton;
    private Button ravenLinkDurationPlusButton;
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
    private Button courierTimeoutRetryMinusButton;
    private Button courierTimeoutRetryValueButton;
    private Button courierTimeoutRetryPlusButton;
    private Button brushRavenCooldownMinusButton;
    private Button brushRavenCooldownValueButton;
    private Button brushRavenCooldownPlusButton;
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
        this.statusBadgeDragging = false;

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
                            scrollUiFontMode = safeScrollUiFontMode().next();
                            btn.setMessage(textForGothicFont());

                            try {
                                Services.PLATFORM.setScrollUiFontMode(scrollUiFontMode);
                                Services.PLATFORM.saveClientConfig();
                                LOG.debug("[FeatheredFriendSettingsScreen] Updated client config scrollUiFontMode -> {}",
                                        scrollUiFontMode);
                            } catch (Throwable t) {
                                LOG.error("[FeatheredFriendSettingsScreen] Failed to update scrollUiFontMode client config", t);
                            }
                        })
                .bounds(centerX - OPTION_ROW_HALF_WIDTH, y, OPTION_ROW_WIDTH, OPTION_HEIGHT)
                .build();
        addScrollableButton(this.gothicFontButton, y);

        y += OPTION_SPACING;

        this.ravenStatusGuiRepositionButton = Button.builder(
                        textForRavenStatusGuiReposition(),
                        btn -> {
                            this.statusBadgeRepositionMode = !this.statusBadgeRepositionMode;
                            this.statusBadgeDragging = false;
                            btn.setMessage(textForRavenStatusGuiReposition());
                        })
                .bounds(centerX - OPTION_ROW_HALF_WIDTH, y, OPTION_ROW_WIDTH, OPTION_HEIGHT)
                .build();
        addScrollableButton(this.ravenStatusGuiRepositionButton, y);
        y += OPTION_SPACING;

        this.ravenStatusGuiAnchorButton = Button.builder(
                        textForRavenStatusGuiAnchor(),
                        btn -> {
                            try {
                                int absoluteX = statusBadgePreviewX();
                                int absoluteY = statusBadgePreviewY();
                                this.ravenStatusGuiAnchor = safeRavenStatusGuiAnchor().next();
                                Services.PLATFORM.setRavenStatusGuiAnchor(this.ravenStatusGuiAnchor);
                                setStatusBadgePosition(absoluteX, absoluteY, false);
                                Services.PLATFORM.saveClientConfig();
                                btn.setMessage(textForRavenStatusGuiAnchor());
                            } catch (Throwable t) {
                                LOG.error("[FeatheredFriendSettingsScreen] Failed to set raven status GUI anchor", t);
                            }
                        })
                .bounds(centerX - OPTION_ROW_HALF_WIDTH, y, OPTION_ROW_WIDTH, OPTION_HEIGHT)
                .build();
        addScrollableButton(this.ravenStatusGuiAnchorButton, y);
        y += OPTION_SPACING;

        this.ravenStatusGuiVisualModeButton = Button.builder(
                        textForRavenStatusGuiVisualMode(),
                        btn -> {
                            try {
                                this.ravenStatusGuiVisualMode = safeRavenStatusGuiVisualMode().next();
                                Services.PLATFORM.setRavenStatusGuiVisualMode(this.ravenStatusGuiVisualMode);
                                Services.PLATFORM.saveClientConfig();
                                btn.setMessage(textForRavenStatusGuiVisualMode());
                            } catch (Throwable t) {
                                LOG.error("[FeatheredFriendSettingsScreen] Failed to set raven status GUI visual mode", t);
                            }
                        })
                .bounds(centerX - OPTION_ROW_HALF_WIDTH, y, OPTION_ROW_WIDTH, OPTION_HEIGHT)
                .build();
        addScrollableButton(this.ravenStatusGuiVisualModeButton, y);
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
                    .bounds(centerX - OPTION_ROW_HALF_WIDTH, y, OPTION_ROW_WIDTH, OPTION_HEIGHT)
                    .build();

            this.chatDisabledButton.active = hasServerSettings && isConnectionReady();
            addScrollableButton(this.chatDisabledButton, y);
            y += OPTION_SPACING;

            this.enableSuspiciousFeatherButton = Button.builder(
                            textForEnableSuspiciousFeather(),
                            btn -> {
                                boolean newValue = !enableSuspiciousFeather;
                                enableSuspiciousFeather = newValue;
                                btn.setMessage(textForEnableSuspiciousFeather());

                                try {
                                    if (!hasServerSettings) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] enableSuspiciousFeather toggled but server settings not synced yet; requesting sync instead");
                                        requestServerSettings();
                                        return;
                                    }

                                    if (!isConnectionReady()) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] Connection not ready; cannot send enableSuspiciousFeather toggle right now");
                                        return;
                                    }

                                    Services.PLATFORM.sendSetEnableSuspiciousFeather(newValue);
                                    LOG.debug("[FeatheredFriendSettingsScreen] Sent SetEnableSuspiciousFeatherPayload -> {}", newValue);
                                } catch (Throwable t) {
                                    LOG.error("[FeatheredFriendSettingsScreen] Failed to send enableSuspiciousFeather toggle", t);
                                }
                            })
                    .bounds(centerX - OPTION_ROW_HALF_WIDTH, y, OPTION_ROW_WIDTH, OPTION_HEIGHT)
                    .build();
            this.enableSuspiciousFeatherButton.active = hasServerSettings && isConnectionReady();
            addScrollableButton(this.enableSuspiciousFeatherButton, y);
            y += OPTION_SPACING;

            this.enableSuspiciousChestButton = Button.builder(
                            textForEnableSuspiciousChest(),
                            btn -> {
                                boolean newValue = !enableSuspiciousChest;
                                enableSuspiciousChest = newValue;
                                btn.setMessage(textForEnableSuspiciousChest());

                                try {
                                    if (!hasServerSettings) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] enableSuspiciousChest toggled but server settings not synced yet; requesting sync instead");
                                        requestServerSettings();
                                        return;
                                    }

                                    if (!isConnectionReady()) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] Connection not ready; cannot send enableSuspiciousChest toggle right now");
                                        return;
                                    }

                                    Services.PLATFORM.sendSetEnableSuspiciousChest(newValue);
                                    LOG.debug("[FeatheredFriendSettingsScreen] Sent SetEnableSuspiciousChestPayload -> {}", newValue);
                                } catch (Throwable t) {
                                    LOG.error("[FeatheredFriendSettingsScreen] Failed to send enableSuspiciousChest toggle", t);
                                }
                            })
                    .bounds(centerX - OPTION_ROW_HALF_WIDTH, y, OPTION_ROW_WIDTH, OPTION_HEIGHT)
                    .build();
            this.enableSuspiciousChestButton.active = hasServerSettings && isConnectionReady();
            addScrollableButton(this.enableSuspiciousChestButton, y);
            y += OPTION_SPACING;

            this.enableRavenArmorButton = Button.builder(
                            textForEnableRavenArmor(),
                            btn -> {
                                boolean newValue = !enableRavenArmor;
                                enableRavenArmor = newValue;
                                btn.setMessage(textForEnableRavenArmor());

                                try {
                                    if (!hasServerSettings) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] enableRavenArmor toggled but server settings not synced yet; requesting sync instead");
                                        requestServerSettings();
                                        return;
                                    }

                                    if (!isConnectionReady()) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] Connection not ready; cannot send enableRavenArmor toggle right now");
                                        return;
                                    }

                                    Services.PLATFORM.sendSetEnableRavenArmor(newValue);
                                    LOG.debug("[FeatheredFriendSettingsScreen] Sent SetEnableRavenArmorPayload -> {}", newValue);
                                } catch (Throwable t) {
                                    LOG.error("[FeatheredFriendSettingsScreen] Failed to send enableRavenArmor toggle", t);
                                }
                            })
                    .bounds(centerX - OPTION_ROW_HALF_WIDTH, y, OPTION_ROW_WIDTH, OPTION_HEIGHT)
                    .build();
            this.enableRavenArmorButton.active = hasServerSettings && isConnectionReady();
            addScrollableButton(this.enableRavenArmorButton, y);
            y += OPTION_SPACING;

            this.enableMailboxButton = Button.builder(
                            textForEnableMailbox(),
                            btn -> {
                                boolean newValue = !enableMailbox;
                                enableMailbox = newValue;
                                btn.setMessage(textForEnableMailbox());

                                try {
                                    if (!hasServerSettings) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] enableMailbox toggled but server settings not synced yet; requesting sync instead");
                                        requestServerSettings();
                                        return;
                                    }

                                    if (!isConnectionReady()) {
                                        LOG.warn("[FeatheredFriendSettingsScreen] Connection not ready; cannot send enableMailbox toggle right now");
                                        return;
                                    }

                                    Services.PLATFORM.sendSetEnableMailbox(newValue);
                                    LOG.debug("[FeatheredFriendSettingsScreen] Sent SetEnableMailboxPayload -> {}", newValue);
                                } catch (Throwable t) {
                                    LOG.error("[FeatheredFriendSettingsScreen] Failed to send enableMailbox toggle", t);
                                }
                            })
                    .bounds(centerX - OPTION_ROW_HALF_WIDTH, y, OPTION_ROW_WIDTH, OPTION_HEIGHT)
                    .build();
            this.enableMailboxButton.active = hasServerSettings && isConnectionReady();
            addScrollableButton(this.enableMailboxButton, y);
            y += OPTION_SPACING;

            this.wildRavensMinusButton = Button.builder(Component.literal("-"), btn -> adjustWildRavensPerPlayer(deltaWithShift(-1)))
                    .bounds(stepperMinusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();
            this.wildRavensValueButton = Button.builder(textForWildRavensPerPlayer(), btn -> {})
                    .bounds(stepperValueX(centerX), y, STEP_VALUE_WIDTH, OPTION_HEIGHT)
                    .build();
            this.wildRavensValueButton.active = false;
            this.wildRavensPlusButton = Button.builder(Component.literal("+"), btn -> adjustWildRavensPerPlayer(deltaWithShift(1)))
                    .bounds(stepperPlusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();

            boolean editActive = hasServerSettings && isConnectionReady();
            this.wildRavensMinusButton.active = editActive;
            this.wildRavensPlusButton.active = editActive;

            addScrollableButton(this.wildRavensMinusButton, y);
            addScrollableButton(this.wildRavensValueButton, y);
            addScrollableButton(this.wildRavensPlusButton, y);
            y += OPTION_SPACING;

            this.ravenLinkDurationMinusButton = Button.builder(Component.literal("-"), btn -> adjustRavenLinkDurationSeconds(deltaWithShift(-5)))
                    .bounds(stepperMinusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();
            this.ravenLinkDurationValueButton = Button.builder(textForRavenLinkDurationSeconds(), btn -> {})
                    .bounds(stepperValueX(centerX), y, STEP_VALUE_WIDTH, OPTION_HEIGHT)
                    .build();
            this.ravenLinkDurationValueButton.active = false;
            this.ravenLinkDurationPlusButton = Button.builder(Component.literal("+"), btn -> adjustRavenLinkDurationSeconds(deltaWithShift(5)))
                    .bounds(stepperPlusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();

            this.ravenLinkDurationMinusButton.active = editActive;
            this.ravenLinkDurationPlusButton.active = editActive;

            addScrollableButton(this.ravenLinkDurationMinusButton, y);
            addScrollableButton(this.ravenLinkDurationValueButton, y);
            addScrollableButton(this.ravenLinkDurationPlusButton, y);
            y += OPTION_SPACING;

            this.ravenChestsMinusButton = Button.builder(Component.literal("-"), btn -> adjustRavenChestCap(deltaWithShift(-1)))
                    .bounds(stepperMinusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();
            this.ravenChestsValueButton = Button.builder(textForRavenChestCap(), btn -> {})
                    .bounds(stepperValueX(centerX), y, STEP_VALUE_WIDTH, OPTION_HEIGHT)
                    .build();
            this.ravenChestsValueButton.active = false;
            this.ravenChestsPlusButton = Button.builder(Component.literal("+"), btn -> adjustRavenChestCap(deltaWithShift(1)))
                    .bounds(stepperPlusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();

            this.ravenChestsMinusButton.active = editActive;
            this.ravenChestsPlusButton.active = editActive;

            addScrollableButton(this.ravenChestsMinusButton, y);
            addScrollableButton(this.ravenChestsValueButton, y);
            addScrollableButton(this.ravenChestsPlusButton, y);
            y += OPTION_SPACING;

            this.ravenLogRetentionMinusButton = Button.builder(Component.literal("-"), btn -> adjustRavenLogRetentionMinutes(deltaWithShift(-60)))
                    .bounds(stepperMinusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();
            this.ravenLogRetentionValueButton = Button.builder(textForRavenLogRetention(), btn -> {})
                    .bounds(stepperValueX(centerX), y, STEP_VALUE_WIDTH, OPTION_HEIGHT)
                    .build();
            this.ravenLogRetentionValueButton.active = false;
            this.ravenLogRetentionPlusButton = Button.builder(Component.literal("+"), btn -> adjustRavenLogRetentionMinutes(deltaWithShift(60)))
                    .bounds(stepperPlusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();

            this.ravenLogRetentionMinusButton.active = editActive;
            this.ravenLogRetentionPlusButton.active = editActive;

            addScrollableButton(this.ravenLogRetentionMinusButton, y);
            addScrollableButton(this.ravenLogRetentionValueButton, y);
            addScrollableButton(this.ravenLogRetentionPlusButton, y);
            y += OPTION_SPACING;

            this.ravenLogSizeMinusButton = Button.builder(Component.literal("-"), btn -> adjustRavenLogMaxBytesPerPlayer(deltaWithShift(-(64 * 1024))))
                    .bounds(stepperMinusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();
            this.ravenLogSizeValueButton = Button.builder(textForRavenLogSize(), btn -> {})
                    .bounds(stepperValueX(centerX), y, STEP_VALUE_WIDTH, OPTION_HEIGHT)
                    .build();
            this.ravenLogSizeValueButton.active = false;
            this.ravenLogSizePlusButton = Button.builder(Component.literal("+"), btn -> adjustRavenLogMaxBytesPerPlayer(deltaWithShift(64 * 1024)))
                    .bounds(stepperPlusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();

            this.ravenLogSizeMinusButton.active = editActive;
            this.ravenLogSizePlusButton.active = editActive;

            addScrollableButton(this.ravenLogSizeMinusButton, y);
            addScrollableButton(this.ravenLogSizeValueButton, y);
            addScrollableButton(this.ravenLogSizePlusButton, y);
            y += OPTION_SPACING;

            this.enderpackCooldownMinusButton = Button.builder(Component.literal("-"), btn -> adjustEnderpackDepositCooldownSeconds(deltaWithShift(-5)))
                    .bounds(stepperMinusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();
            this.enderpackCooldownValueButton = Button.builder(textForEnderpackDepositCooldown(), btn -> {})
                    .bounds(stepperValueX(centerX), y, STEP_VALUE_WIDTH, OPTION_HEIGHT)
                    .build();
            this.enderpackCooldownValueButton.active = false;
            this.enderpackCooldownPlusButton = Button.builder(Component.literal("+"), btn -> adjustEnderpackDepositCooldownSeconds(deltaWithShift(5)))
                    .bounds(stepperPlusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();

            this.enderpackCooldownMinusButton.active = editActive;
            this.enderpackCooldownPlusButton.active = editActive;

            addScrollableButton(this.enderpackCooldownMinusButton, y);
            addScrollableButton(this.enderpackCooldownValueButton, y);
            addScrollableButton(this.enderpackCooldownPlusButton, y);
            y += OPTION_SPACING;

            this.brushRavenCooldownMinusButton = Button.builder(Component.literal("-"), btn -> adjustBrushRavenCooldownSeconds(deltaWithShift(-5)))
                    .bounds(stepperMinusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();
            this.brushRavenCooldownValueButton = Button.builder(textForBrushRavenCooldown(), btn -> {})
                    .bounds(stepperValueX(centerX), y, STEP_VALUE_WIDTH, OPTION_HEIGHT)
                    .build();
            this.brushRavenCooldownValueButton.active = false;
            this.brushRavenCooldownPlusButton = Button.builder(Component.literal("+"), btn -> adjustBrushRavenCooldownSeconds(deltaWithShift(5)))
                    .bounds(stepperPlusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();

            this.brushRavenCooldownMinusButton.active = editActive;
            this.brushRavenCooldownPlusButton.active = editActive;

            addScrollableButton(this.brushRavenCooldownMinusButton, y);
            addScrollableButton(this.brushRavenCooldownValueButton, y);
            addScrollableButton(this.brushRavenCooldownPlusButton, y);
            y += OPTION_SPACING;

            this.scrollDeliveryCooldownMinusButton = Button.builder(Component.literal("-"), btn -> adjustScrollDeliveryCooldownSeconds(deltaWithShift(-5)))
                    .bounds(stepperMinusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();
            this.scrollDeliveryCooldownValueButton = Button.builder(textForScrollDeliveryCooldown(), btn -> {})
                    .bounds(stepperValueX(centerX), y, STEP_VALUE_WIDTH, OPTION_HEIGHT)
                    .build();
            this.scrollDeliveryCooldownValueButton.active = false;
            this.scrollDeliveryCooldownPlusButton = Button.builder(Component.literal("+"), btn -> adjustScrollDeliveryCooldownSeconds(deltaWithShift(5)))
                    .bounds(stepperPlusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();

            this.scrollDeliveryCooldownMinusButton.active = editActive;
            this.scrollDeliveryCooldownPlusButton.active = editActive;

            addScrollableButton(this.scrollDeliveryCooldownMinusButton, y);
            addScrollableButton(this.scrollDeliveryCooldownValueButton, y);
            addScrollableButton(this.scrollDeliveryCooldownPlusButton, y);
            y += OPTION_SPACING;

            this.courierTimeoutRetryMinusButton = Button.builder(Component.literal("-"), btn -> adjustCourierTimeoutRetrySeconds(deltaWithShift(-5)))
                    .bounds(stepperMinusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();
            this.courierTimeoutRetryValueButton = Button.builder(textForCourierTimeoutRetry(), btn -> {})
                    .bounds(stepperValueX(centerX), y, STEP_VALUE_WIDTH, OPTION_HEIGHT)
                    .build();
            this.courierTimeoutRetryValueButton.active = false;
            this.courierTimeoutRetryPlusButton = Button.builder(Component.literal("+"), btn -> adjustCourierTimeoutRetrySeconds(deltaWithShift(5)))
                    .bounds(stepperPlusX(centerX), y, STEP_BUTTON_WIDTH, OPTION_HEIGHT)
                    .build();

            this.courierTimeoutRetryMinusButton.active = editActive;
            this.courierTimeoutRetryPlusButton.active = editActive;

            addScrollableButton(this.courierTimeoutRetryMinusButton, y);
            addScrollableButton(this.courierTimeoutRetryValueButton, y);
            addScrollableButton(this.courierTimeoutRetryPlusButton, y);
            y += OPTION_SPACING;
        } else {
            this.chatDisabledButton = null;
            this.wildRavensMinusButton = null;
            this.wildRavensValueButton = null;
            this.wildRavensPlusButton = null;
            this.ravenLinkDurationMinusButton = null;
            this.ravenLinkDurationValueButton = null;
            this.ravenLinkDurationPlusButton = null;
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
            this.courierTimeoutRetryMinusButton = null;
            this.courierTimeoutRetryValueButton = null;
            this.courierTimeoutRetryPlusButton = null;
            this.brushRavenCooldownMinusButton = null;
            this.brushRavenCooldownValueButton = null;
            this.brushRavenCooldownPlusButton = null;
        }

        this.doneButton = Button.builder(Component.translatable("gui.done"), btn -> onClose())
                .bounds(centerX - 90, this.height - 28, 180, OPTION_HEIGHT)
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

    private int stepperMinusX(int centerX) {
        return centerX - OPTION_ROW_HALF_WIDTH;
    }

    private int stepperValueX(int centerX) {
        return stepperMinusX(centerX) + STEP_BUTTON_WIDTH + STEP_GAP;
    }

    private int stepperPlusX(int centerX) {
        return stepperValueX(centerX) + STEP_VALUE_WIDTH + STEP_GAP;
    }

    private int deltaWithShift(int baseDelta) {
        return hasShiftDown() ? (baseDelta * SHIFT_STEP_MULTIPLIER) : baseDelta;
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
            if (this.ravenStatusGuiRepositionButton != null) {
                this.ravenStatusGuiRepositionButton.setMessage(textForRavenStatusGuiReposition());
            }
            if (this.ravenStatusGuiAnchorButton != null) {
                this.ravenStatusGuiAnchorButton.setMessage(textForRavenStatusGuiAnchor());
            }
            if (this.ravenStatusGuiVisualModeButton != null) {
                this.ravenStatusGuiVisualModeButton.setMessage(textForRavenStatusGuiVisualMode());
            }

            if (this.chatDisabledButton != null) {
                this.chatDisabledButton.setMessage(textForChatDisabled());
                this.chatDisabledButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.enableSuspiciousFeatherButton != null) {
                this.enableSuspiciousFeatherButton.setMessage(textForEnableSuspiciousFeather());
                this.enableSuspiciousFeatherButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.enableSuspiciousChestButton != null) {
                this.enableSuspiciousChestButton.setMessage(textForEnableSuspiciousChest());
                this.enableSuspiciousChestButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.enableRavenArmorButton != null) {
                this.enableRavenArmorButton.setMessage(textForEnableRavenArmor());
                this.enableRavenArmorButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.enableMailboxButton != null) {
                this.enableMailboxButton.setMessage(textForEnableMailbox());
                this.enableMailboxButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.wildRavensValueButton != null) {
                this.wildRavensValueButton.setMessage(textForWildRavensPerPlayer());
            }
            if (this.wildRavensMinusButton != null) {
                this.wildRavensMinusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.wildRavensPlusButton != null) {
                this.wildRavensPlusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.ravenLinkDurationValueButton != null) {
                this.ravenLinkDurationValueButton.setMessage(textForRavenLinkDurationSeconds());
            }
            if (this.ravenLinkDurationMinusButton != null) {
                this.ravenLinkDurationMinusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.ravenLinkDurationPlusButton != null) {
                this.ravenLinkDurationPlusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
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
            if (this.brushRavenCooldownValueButton != null) {
                this.brushRavenCooldownValueButton.setMessage(textForBrushRavenCooldown());
            }
            if (this.brushRavenCooldownMinusButton != null) {
                this.brushRavenCooldownMinusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.brushRavenCooldownPlusButton != null) {
                this.brushRavenCooldownPlusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
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
            if (this.courierTimeoutRetryValueButton != null) {
                this.courierTimeoutRetryValueButton.setMessage(textForCourierTimeoutRetry());
            }
            if (this.courierTimeoutRetryMinusButton != null) {
                this.courierTimeoutRetryMinusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }
            if (this.courierTimeoutRetryPlusButton != null) {
                this.courierTimeoutRetryPlusButton.active = this.hasServerSettings && this.canEditChat && isConnectionReady();
            }

            if (this.chatDisabledButton == null && this.canEditChat) {
                LOG.debug("[FeatheredFriendSettingsScreen] Chat button absent but perms now true; rebuilding widgets");
                tryRebuildWidgets();
            } else if (this.chatDisabledButton != null && !this.canEditChat) {
                LOG.debug("[FeatheredFriendSettingsScreen] Chat button present but perms now false; rebuilding widgets");
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
            ScrollUiFontMode loadedFontMode = Services.PLATFORM.getScrollUiFontMode();
            scrollUiFontMode = loadedFontMode == null ? ScrollUiFontMode.JACQUARD : loadedFontMode;
            ravenStatusGuiX = Services.PLATFORM.getRavenStatusGuiX();
            ravenStatusGuiY = Services.PLATFORM.getRavenStatusGuiY();
            RavenStatusGuiAnchor loadedAnchor = Services.PLATFORM.getRavenStatusGuiAnchor();
            ravenStatusGuiAnchor = loadedAnchor == null ? RavenStatusGuiAnchor.TOP_LEFT : loadedAnchor;
            RavenStatusGuiVisualMode loadedVisualMode = Services.PLATFORM.getRavenStatusGuiVisualMode();
            ravenStatusGuiVisualMode = loadedVisualMode == null ? RavenStatusGuiVisualMode.BADGE_AND_TEXT : loadedVisualMode;

            // server synced
            this.hasServerSettings = Services.PLATFORM.hasServerSettingsSynced();
            if (this.hasServerSettings) {
                this.chatDisabled = Services.PLATFORM.isChatDisabledClient();
                this.enableSuspiciousFeather = Services.PLATFORM.isSuspiciousFeatherEnabledClient();
                this.enableSuspiciousChest = Services.PLATFORM.isSuspiciousChestEnabledClient();
                this.enableRavenArmor = Services.PLATFORM.isRavenArmorEnabledClient();
                this.enableMailbox = Services.PLATFORM.isMailboxEnabledClient();
                this.canEditChat = Services.PLATFORM.canEditChat();
                this.wildRavensPerPlayer = Services.PLATFORM.getWildRavensPerPlayerClient();
                this.ravenLinkDurationSeconds = Services.PLATFORM.getRavenLinkDurationSecondsClient();
                this.maxRavenChestsPerPlayer = Services.PLATFORM.getMaxRavenChestsPerPlayerClient();
                this.ravenLogRetentionMinutes = Services.PLATFORM.getRavenLogRetentionMinutesClient();
                this.ravenLogMaxBytesPerPlayer = Services.PLATFORM.getRavenLogMaxBytesPerPlayerClient();
                this.enderpackDepositCooldownSeconds = Services.PLATFORM.getEnderpackDepositCooldownSecondsClient();
                this.scrollDeliveryCooldownSeconds = Services.PLATFORM.getScrollDeliveryCooldownSecondsClient();
                this.courierTimeoutRetrySeconds = Services.PLATFORM.getCourierTimeoutRetrySecondsClient();
                this.brushRavenCooldownSeconds = Services.PLATFORM.getBrushRavenCooldownSecondsClient();
            } else {
                // while syncing, default to enabled + no perms
                this.chatDisabled = false;
                this.enableSuspiciousFeather = true;
                this.enableSuspiciousChest = true;
                this.enableRavenArmor = true;
                this.enableMailbox = true;
                this.canEditChat = false;
                this.wildRavensPerPlayer = 0;
                this.ravenLinkDurationSeconds = 0;
                this.maxRavenChestsPerPlayer = 0;
                this.ravenLogRetentionMinutes = 0;
                this.ravenLogMaxBytesPerPlayer = 0;
                this.enderpackDepositCooldownSeconds = 0;
                this.scrollDeliveryCooldownSeconds = 0;
                this.courierTimeoutRetrySeconds = 0;
                this.brushRavenCooldownSeconds = 0;
            }

            LOG.debug("[FeatheredFriendSettingsScreen] refreshFromCacheOnly: hasServerSettings={} chatDisabled={} enableSuspiciousFeather={} enableSuspiciousChest={} enableRavenArmor={} enableMailbox={} wildRavensPerPlayer={} ravenLinkDurationSeconds={} maxRavenChestsPerPlayer={} ravenLogRetentionMinutes={} ravenLogMaxBytesPerPlayer={} enderpackDepositCooldownSeconds={} scrollDeliveryCooldownSeconds={} courierTimeoutRetrySeconds={} canEditChat={} scrollUiFontMode={} ravenStatusGuiX={} ravenStatusGuiY={} ravenStatusGuiAnchor={} ravenStatusGuiVisualMode={}",
                    hasServerSettings, chatDisabled, enableSuspiciousFeather, enableSuspiciousChest, enableRavenArmor, enableMailbox, wildRavensPerPlayer, ravenLinkDurationSeconds, maxRavenChestsPerPlayer, ravenLogRetentionMinutes, ravenLogMaxBytesPerPlayer, enderpackDepositCooldownSeconds, scrollDeliveryCooldownSeconds, courierTimeoutRetrySeconds, canEditChat, scrollUiFontMode, ravenStatusGuiX, ravenStatusGuiY, ravenStatusGuiAnchor, ravenStatusGuiVisualMode);

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
            enableSuspiciousFeather = true;
            enableSuspiciousChest = true;
            enableRavenArmor = true;
            enableMailbox = true;
            scrollUiFontMode = ScrollUiFontMode.JACQUARD;
            ravenStatusGuiX = 12;
            ravenStatusGuiY = 12;
            ravenStatusGuiAnchor = RavenStatusGuiAnchor.TOP_LEFT;
            ravenStatusGuiVisualMode = RavenStatusGuiVisualMode.BADGE_AND_TEXT;
            wildRavensPerPlayer = 0;
            ravenLinkDurationSeconds = 0;
            maxRavenChestsPerPlayer = 0;
            ravenLogRetentionMinutes = 0;
            ravenLogMaxBytesPerPlayer = 0;
            enderpackDepositCooldownSeconds = 0;
            scrollDeliveryCooldownSeconds = 0;
            courierTimeoutRetrySeconds = 0;
            brushRavenCooldownSeconds = 0;
            LOG.error("[FeatheredFriendSettingsScreen] loadFromCacheAndMaybeRequestSync failed safely", t);
        }
    }

    private Component textForGothicFont() {
        return Component.translatable(
                "screen.featheredfriend.settings.scroll_ui_font",
                Component.translatable(safeScrollUiFontMode().translationKey())
        );
    }

    private Component textForRavenStatusGuiReposition() {
        return Component.translatable(
                "screen.featheredfriend.settings.raven_status_gui_reposition"
        );
    }

    private Component textForRavenStatusGuiAnchor() {
        return Component.translatable(
                "screen.featheredfriend.settings.raven_status_gui_anchor",
                Component.translatable(safeRavenStatusGuiAnchor().translationKey())
        );
    }

    private Component textForRavenStatusGuiVisualMode() {
        return Component.translatable(
                "screen.featheredfriend.settings.raven_status_gui_visual_mode",
                Component.translatable(safeRavenStatusGuiVisualMode().translationKey())
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

    private Component textForEnableSuspiciousFeather() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.enable_suspicious_feather.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.enable_suspicious_feather",
                onOff(enableSuspiciousFeather)
        );
    }

    private Component textForEnableSuspiciousChest() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.enable_suspicious_chest.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.enable_suspicious_chest",
                onOff(enableSuspiciousChest)
        );
    }

    private Component textForEnableRavenArmor() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.enable_raven_armor.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.enable_raven_armor",
                onOff(enableRavenArmor)
        );
    }

    private Component textForEnableMailbox() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.enable_mailbox.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.enable_mailbox",
                onOff(enableMailbox)
        );
    }

    private Component textForWildRavensPerPlayer() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.wild_ravens_per_player.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.wild_ravens_per_player",
                Integer.valueOf(Math.max(0, Math.min(16, wildRavensPerPlayer)))
        );
    }

    private Component textForRavenLinkDurationSeconds() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.raven_link_duration_seconds.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.raven_link_duration_seconds",
                Integer.valueOf(Math.max(5, Math.min(600, ravenLinkDurationSeconds)))
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

    private Component textForBrushRavenCooldown() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.brush_raven_cooldown.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.brush_raven_cooldown",
                Integer.valueOf(brushRavenCooldownSeconds)
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

    private Component textForCourierTimeoutRetry() {
        if (!hasServerSettings) {
            return Component.translatable("screen.featheredfriend.settings.courier_timeout_retry.syncing");
        }
        return Component.translatable(
                "screen.featheredfriend.settings.courier_timeout_retry",
                Integer.valueOf(courierTimeoutRetrySeconds)
        );
    }

    private int statusBadgePreviewWidth() {
        return Math.max(1, Math.round(RAVEN_STATUS_PREVIEW_BASE_WIDTH * RAVEN_STATUS_PREVIEW_SCALE));
    }

    private int statusBadgePreviewHeight() {
        return Math.max(1, Math.round(RAVEN_STATUS_PREVIEW_BASE_HEIGHT * RAVEN_STATUS_PREVIEW_SCALE));
    }

    private int maxStatusBadgeOffsetX() {
        return Math.max(0, this.width - statusBadgePreviewWidth());
    }

    private int maxStatusBadgeOffsetY() {
        return Math.max(0, this.height - statusBadgePreviewHeight());
    }

    private int maxStoredStatusBadgeX() {
        int maxOffsetX = maxStatusBadgeOffsetX();
        return switch (safeRavenStatusGuiAnchor()) {
            case TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT -> maxOffsetX;
            case TOP_CENTER, CENTER, BOTTOM_CENTER -> Math.max(0, maxOffsetX * 2);
        };
    }

    private int maxStoredStatusBadgeY() {
        int maxOffsetY = maxStatusBadgeOffsetY();
        return switch (safeRavenStatusGuiAnchor()) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> maxOffsetY;
            case CENTER -> Math.max(0, maxOffsetY * 2);
        };
    }

    private int clampStoredStatusBadgeX(int storedX) {
        return Mth.clamp(storedX, 0, maxStoredStatusBadgeX());
    }

    private int clampStoredStatusBadgeY(int storedY) {
        return Mth.clamp(storedY, 0, maxStoredStatusBadgeY());
    }

    private int statusBadgePreviewX() {
        int storedX = clampStoredStatusBadgeX(this.ravenStatusGuiX);
        int maxOffset = maxStatusBadgeOffsetX();
        return switch (safeRavenStatusGuiAnchor()) {
            case TOP_LEFT, BOTTOM_LEFT -> storedX;
            case TOP_RIGHT, BOTTOM_RIGHT -> maxOffset - storedX;
            case TOP_CENTER, CENTER, BOTTOM_CENTER -> decodeCenteredAxis(storedX, maxOffset);
        };
    }

    private int statusBadgePreviewY() {
        int storedY = clampStoredStatusBadgeY(this.ravenStatusGuiY);
        int maxOffset = maxStatusBadgeOffsetY();
        return switch (safeRavenStatusGuiAnchor()) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> storedY;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> maxOffset - storedY;
            case CENTER -> decodeCenteredAxis(storedY, maxOffset);
        };
    }

    private int statusBadgeOffsetFromAbsoluteX(int absoluteX) {
        int maxOffset = maxStatusBadgeOffsetX();
        int clampedAbsolute = Mth.clamp(absoluteX, 0, maxOffset);
        return switch (safeRavenStatusGuiAnchor()) {
            case TOP_LEFT, BOTTOM_LEFT -> clampedAbsolute;
            case TOP_RIGHT, BOTTOM_RIGHT -> maxOffset - clampedAbsolute;
            case TOP_CENTER, CENTER, BOTTOM_CENTER -> encodeAbsoluteToCenteredAxis(clampedAbsolute, maxOffset);
        };
    }

    private int statusBadgeOffsetFromAbsoluteY(int absoluteY) {
        int maxOffset = maxStatusBadgeOffsetY();
        int clampedAbsolute = Mth.clamp(absoluteY, 0, maxOffset);
        return switch (safeRavenStatusGuiAnchor()) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> clampedAbsolute;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> maxOffset - clampedAbsolute;
            case CENTER -> encodeAbsoluteToCenteredAxis(clampedAbsolute, maxOffset);
        };
    }

    private int decodeCenteredAxis(int storedValue, int maxOffset) {
        int maxStored = Math.max(0, maxOffset * 2);
        int clampedStored = Mth.clamp(storedValue, 0, maxStored);
        int centerAbsolute = maxOffset / 2;
        int deltaFromCenter = clampedStored - maxOffset;
        return Mth.clamp(centerAbsolute + deltaFromCenter, 0, maxOffset);
    }

    private int encodeAbsoluteToCenteredAxis(int absoluteValue, int maxOffset) {
        int centerAbsolute = maxOffset / 2;
        int maxStored = Math.max(0, maxOffset * 2);
        int encoded = (absoluteValue - centerAbsolute) + maxOffset;
        return Mth.clamp(encoded, 0, maxStored);
    }

    private boolean isMouseOverStatusBadgePreview(double mouseX, double mouseY) {
        int x = statusBadgePreviewX();
        int y = statusBadgePreviewY();
        int w = statusBadgePreviewWidth();
        int h = statusBadgePreviewHeight();
        return mouseX >= x && mouseX <= (x + w) && mouseY >= y && mouseY <= (y + h);
    }

    private void setStatusBadgePosition(int x, int y, boolean persistNow) {
        try {
            int newStoredX = clampStoredStatusBadgeX(statusBadgeOffsetFromAbsoluteX(x));
            int newStoredY = clampStoredStatusBadgeY(statusBadgeOffsetFromAbsoluteY(y));
            this.ravenStatusGuiX = newStoredX;
            this.ravenStatusGuiY = newStoredY;
            Services.PLATFORM.setRavenStatusGuiX(newStoredX);
            Services.PLATFORM.setRavenStatusGuiY(newStoredY);
            if (persistNow) {
                Services.PLATFORM.saveClientConfig();
            }
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] setStatusBadgePosition failed safely", t);
        }
    }

    private @NotNull RavenStatusGuiAnchor safeRavenStatusGuiAnchor() {
        return this.ravenStatusGuiAnchor == null ? RavenStatusGuiAnchor.TOP_LEFT : this.ravenStatusGuiAnchor;
    }

    private @NotNull ScrollUiFontMode safeScrollUiFontMode() {
        return this.scrollUiFontMode == null ? ScrollUiFontMode.JACQUARD : this.scrollUiFontMode;
    }

    private @NotNull RavenStatusGuiVisualMode safeRavenStatusGuiVisualMode() {
        return this.ravenStatusGuiVisualMode == null
                ? RavenStatusGuiVisualMode.BADGE_AND_TEXT
                : this.ravenStatusGuiVisualMode;
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

    private void adjustWildRavensPerPlayer(int delta) {
        try {
            if (!hasServerSettings || !canEditChat || !isConnectionReady()) {
                return;
            }
            int newValue = Math.max(0, Math.min(16, this.wildRavensPerPlayer + delta));
            if (newValue == this.wildRavensPerPlayer) {
                return;
            }
            this.wildRavensPerPlayer = newValue;
            if (this.wildRavensValueButton != null) {
                this.wildRavensValueButton.setMessage(textForWildRavensPerPlayer());
            }
            Services.PLATFORM.sendSetWildRavensPerPlayer(newValue);
            LOG.debug("[FeatheredFriendSettingsScreen] Sent SetWildRavensPerPlayerPayload -> {}", newValue);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] adjustWildRavensPerPlayer failed safely", t);
        }
    }

    private void adjustRavenLinkDurationSeconds(int delta) {
        try {
            if (!hasServerSettings || !canEditChat || !isConnectionReady()) {
                return;
            }
            int newValue = Math.max(5, Math.min(600, this.ravenLinkDurationSeconds + delta));
            if (newValue == this.ravenLinkDurationSeconds) {
                return;
            }
            this.ravenLinkDurationSeconds = newValue;
            if (this.ravenLinkDurationValueButton != null) {
                this.ravenLinkDurationValueButton.setMessage(textForRavenLinkDurationSeconds());
            }
            Services.PLATFORM.sendSetRavenLinkDurationSeconds(newValue);
            LOG.debug("[FeatheredFriendSettingsScreen] Sent SetRavenLinkDurationSecondsPayload -> {}", newValue);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] adjustRavenLinkDurationSeconds failed safely", t);
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

    private void adjustBrushRavenCooldownSeconds(int deltaSeconds) {
        try {
            if (!hasServerSettings || !canEditChat || !isConnectionReady()) {
                return;
            }
            int newValue = Math.max(0, Math.min(86_400, this.brushRavenCooldownSeconds + deltaSeconds));
            if (newValue == this.brushRavenCooldownSeconds) {
                return;
            }
            this.brushRavenCooldownSeconds = newValue;
            if (this.brushRavenCooldownValueButton != null) {
                this.brushRavenCooldownValueButton.setMessage(textForBrushRavenCooldown());
            }
            Services.PLATFORM.sendSetBrushRavenCooldownSeconds(newValue);
            LOG.debug("[FeatheredFriendSettingsScreen] Sent SetBrushRavenCooldownSecondsPayload -> {}", newValue);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] adjustBrushRavenCooldownSeconds failed safely", t);
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

    private void adjustCourierTimeoutRetrySeconds(int deltaSeconds) {
        try {
            if (!hasServerSettings || !canEditChat || !isConnectionReady()) {
                return;
            }
            int newValue = Math.max(0, Math.min(86_400, this.courierTimeoutRetrySeconds + deltaSeconds));
            if (newValue == this.courierTimeoutRetrySeconds) {
                return;
            }
            this.courierTimeoutRetrySeconds = newValue;
            if (this.courierTimeoutRetryValueButton != null) {
                this.courierTimeoutRetryValueButton.setMessage(textForCourierTimeoutRetry());
            }
            Services.PLATFORM.sendSetCourierTimeoutRetrySeconds(newValue);
            LOG.debug("[FeatheredFriendSettingsScreen] Sent SetCourierTimeoutRetrySecondsPayload -> {}", newValue);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsScreen] adjustCourierTimeoutRetrySeconds failed safely", t);
        }
    }

    private static Component onOff(boolean enabled) {
        return Component.translatable(enabled ? "options.on" : "options.off");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.statusBadgeRepositionMode && button == 0 && isMouseOverStatusBadgePreview(mouseX, mouseY)) {
            int x = statusBadgePreviewX();
            int y = statusBadgePreviewY();
            this.statusBadgeDragging = true;
            this.statusBadgeDragOffsetX = Mth.clamp((int) Math.round(mouseX) - x, 0, statusBadgePreviewWidth());
            this.statusBadgeDragOffsetY = Mth.clamp((int) Math.round(mouseY) - y, 0, statusBadgePreviewHeight());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.statusBadgeRepositionMode && this.statusBadgeDragging && button == 0) {
            int desiredX = (int) Math.round(mouseX) - this.statusBadgeDragOffsetX;
            int desiredY = (int) Math.round(mouseY) - this.statusBadgeDragOffsetY;
            setStatusBadgePosition(desiredX, desiredY, false);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.statusBadgeDragging) {
            this.statusBadgeDragging = false;
            try {
                Services.PLATFORM.saveClientConfig();
            } catch (Throwable t) {
                LOG.error("[FeatheredFriendSettingsScreen] Failed to persist raven status GUI position", t);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.statusBadgeDragging) {
            return true;
        }
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
        int left = (this.width / 2) - (OPTION_ROW_HALF_WIDTH + OPTIONS_HOVER_SIDE_PADDING);
        int right = (this.width / 2) + (OPTION_ROW_HALF_WIDTH + OPTIONS_HOVER_SIDE_PADDING);
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

            if (this.statusBadgeRepositionMode) {
                Component hint = Component.translatable(
                        "screen.featheredfriend.settings.raven_status_gui_reposition.hint",
                        Integer.valueOf(statusBadgePreviewX()),
                        Integer.valueOf(statusBadgePreviewY())
                );
                guiGraphics.drawCenteredString(this.font, hint, this.width / 2, 54, 0xE0E0E0);
            }
        } catch (Throwable ignored) {
        }

        if (this.statusBadgeRepositionMode) {
            drawStatusBadgePreview(guiGraphics);
        }

        if (this.maxScrollOffset > 0) {
            drawScrollBar(guiGraphics);
        }
    }

    private void drawStatusBadgePreview(@NotNull GuiGraphics guiGraphics) {
        int x = statusBadgePreviewX();
        int y = statusBadgePreviewY();
        int w = statusBadgePreviewWidth();
        int h = statusBadgePreviewHeight();

        int fillColor = this.statusBadgeDragging ? 0x77A0D0FF : 0x665A5A5A;
        int borderColor = this.statusBadgeDragging ? 0xFFD5EAFF : 0xFFFFFFFF;
        guiGraphics.fill(x, y, x + w, y + h, fillColor);
        guiGraphics.fill(x, y, x + w, y + 1, borderColor);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, borderColor);
        guiGraphics.fill(x, y, x + 1, y + h, borderColor);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, borderColor);
    }

    private void drawScrollBar(@NotNull GuiGraphics guiGraphics) {
        int trackX0 = (this.width / 2) + SCROLLBAR_X_OFFSET;
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
