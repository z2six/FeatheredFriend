// common/src/main/java/net/z2six/featheredfriend/platform/services/IPlatformHelper.java
package net.z2six.featheredfriend.platform.services;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.z2six.featheredfriend.client.font.ScrollUiFontMode;
import net.z2six.featheredfriend.client.ravenbadge.RavenStatusGuiAnchor;
import net.z2six.featheredfriend.client.ravenbadge.RavenStatusGuiVisualMode;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.network.RavenChestSelectAction;
import net.z2six.featheredfriend.network.RavenChestChoiceInfo;
import net.z2six.featheredfriend.item.SealStampSlotEntry;
import net.z2six.featheredfriend.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.menu.ScrollViewMenu;
import net.z2six.featheredfriend.menu.SealStampMenu;
import net.z2six.featheredfriend.menu.EnderpackMenu;
import net.z2six.featheredfriend.menu.MailboxMenu;
import net.z2six.featheredfriend.menu.RavenChestMenu;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    default void openScrollSealingScreen(@NotNull ServerPlayer player) {
        // No-op on platforms without the menu.
    }

    default void openSealStampScreen(@NotNull ServerPlayer player) {
        // No-op on platforms without the menu.
    }

    default void openScrollViewScreen(@NotNull ServerPlayer player) {
        // No-op on platforms without the menu.
    }

    default void openEnderpackScreen(@NotNull ServerPlayer player) {
        // No-op on platforms without the menu.
    }

    default void openEnderpackScreen(@NotNull ServerPlayer player, @NotNull InteractionHand preferredHand) {
        // No-op on platforms without the menu.
    }

    default boolean getChatDisabledDefault() {
        return false;
    }

    default @NotNull InteractionResult handleSealedScrollInteract(@NotNull RavenEntity raven,
                                                                  @NotNull Player player,
                                                                  @NotNull InteractionHand hand) {
        return InteractionResult.PASS;
    }

    default void handleCourierRavenLandedHit(@NotNull RavenEntity raven) {
        // no-op on platforms without courier runtime wiring
    }

    default void notifyRavenBadgeHit(@NotNull RavenEntity raven, boolean dodged) {
        // no-op on platforms without raven badge runtime wiring
    }

    default void notifyRavenBadgeThreatDetected(@NotNull RavenEntity raven, boolean hasScrollPayload) {
        // no-op on platforms without raven badge runtime wiring
    }

    default void notifyRavenBadgeDeliveryRepath(@NotNull RavenEntity raven) {
        // no-op on platforms without raven badge runtime wiring
    }

    default boolean isScrollSummonedRaven(@NotNull RavenEntity raven) {
        return false;
    }

    default ServerPlayer getScrollSummonOwnerIfHoldingScroll(@NotNull ServerLevel level,
                                                             @NotNull RavenEntity raven) {
        return null;
    }

    default @NotNull CompoundTag getPlayerPersistentData(@NotNull ServerPlayer player) {
        return new CompoundTag();
    }

    default @NotNull CompoundTag getEntityPersistentData(@NotNull Entity entity) {
        return new CompoundTag();
    }

    default MenuType<ScrollSealingMenu> getScrollSealingMenuType() {
        return null;
    }

    default MenuType<SealStampMenu> getSealStampMenuType() {
        return null;
    }

    default MenuType<ScrollViewMenu> getScrollViewMenuType() {
        return null;
    }

    default MenuType<EnderpackMenu> getEnderpackMenuType() {
        return null;
    }

    default MenuType<MailboxMenu> getMailboxMenuType() {
        return null;
    }

    default MenuType<RavenChestMenu> getRavenChestMenuType() {
        return null;
    }

    default @NotNull List<SealStampSlotEntry> getExternalSealStampEntries(@NotNull Player player) {
        return List.of();
    }

    // ---------------------------------------------------------------------
    // Client config access (loader-specific storage)
    // ---------------------------------------------------------------------

    default boolean isUseVanillaFontForGothicText() {
        return false;
    }

    default void setUseVanillaFontForGothicText(boolean value) {
        // no-op
    }

    default boolean getUseVanillaFontForGothicTextDefault() {
        return false;
    }

    default @NotNull ScrollUiFontMode getScrollUiFontMode() {
        return ScrollUiFontMode.JACQUARD;
    }

    default void setScrollUiFontMode(@NotNull ScrollUiFontMode mode) {
        // no-op
    }

    default @NotNull String getFavoriteStampKey() {
        return "";
    }

    default void setFavoriteStampKey(@NotNull String value) {
        // no-op
    }

    default void saveClientConfig() {
        // no-op
    }

    default @NotNull String getRavenLogViewSettingsRaw() {
        return "";
    }

    default void setRavenLogViewSettingsRaw(@NotNull String value) {
        // no-op
    }

    default int getRavenStatusGuiX() {
        return 12;
    }

    default int getRavenStatusGuiY() {
        return 12;
    }

    default void setRavenStatusGuiX(int value) {
        // no-op
    }

    default void setRavenStatusGuiY(int value) {
        // no-op
    }

    default @NotNull RavenStatusGuiAnchor getRavenStatusGuiAnchor() {
        return RavenStatusGuiAnchor.TOP_LEFT;
    }

    default void setRavenStatusGuiAnchor(@NotNull RavenStatusGuiAnchor anchor) {
        // no-op
    }

    default @NotNull RavenStatusGuiVisualMode getRavenStatusGuiVisualMode() {
        return RavenStatusGuiVisualMode.BADGE_AND_TEXT;
    }

    default void setRavenStatusGuiVisualMode(@NotNull RavenStatusGuiVisualMode mode) {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Client <-> server payload helpers (loader-specific networking)
    // ---------------------------------------------------------------------

    default void sendOpenRavenNamingScreen(@NotNull ServerPlayer player, int ravenEntityId) {
        // no-op
    }

    default void sendRavenNameChosenToServer(int ravenEntityId, @NotNull String name) {
        // no-op
    }

    default void sendRavenNameCancelledToServer(int ravenEntityId) {
        // no-op
    }

    default void sendWaxSealToServer(int sealStampSlotIndex,
                                     @NotNull String dateText,
                                     @NotNull String recipientName,
                                     @NotNull String recipientUuid,
                                     @NotNull String recipientText,
                                     @NotNull String messageText,
                                     @NotNull String signatureText,
                                     long seed,
                                     int slices,
                                     int style,
                                     @NotNull String senderName) {
        // no-op
    }

    default void sendBreakSealToServer(int slotHint,
                                       long seed,
                                       @NotNull String recipientUuid,
                                       @NotNull String dateText,
                                       @NotNull String senderName) {
        // no-op
    }

    default void sendSealStampCarveResultToServer(int stampSlot,
                                                  long seed,
                                                  int slices,
                                                  int style,
                                                  @NotNull String ownerName) {
        // no-op
    }

    default void sendRequestKnownPlayersToServer() {
        // no-op
    }

    default void sendOpenEnderpackToServer() {
        // no-op
    }

    default void sendOpenRavenLogToServer() {
        // no-op
    }

    default void sendClearRavenLogToServer() {
        // no-op
    }

    default boolean hasAccessibleEnderpack(@NotNull ServerPlayer player) {
        return false;
    }

    /**
     * Moves items from the player's best-accessible Enderpack into the target container.
     *
     * @return moved item count, 0 if no items moved, or -1 if no Enderpack is accessible
     */
    default int transferEnderpackIntoContainer(@NotNull ServerPlayer player,
                                               @NotNull Container container,
                                               @NotNull HolderLookup.Provider registries) {
        return -1;
    }

    default void sendOpenRavenChestLabelScreen(@NotNull ServerPlayer player,
                                               @NotNull String dimensionId,
                                               long blockPos,
                                               @NotNull String currentLabel) {
        // no-op
    }

    default void sendOpenRavenChestSelectScreen(@NotNull ServerPlayer player,
                                                int ravenEntityId,
                                                @NotNull List<RavenChestChoiceInfo> choices) {
        sendOpenRavenChestSelectScreen(player, ravenEntityId, choices, RavenChestSelectAction.ENDERPACK_DEPOSIT);
    }

    default void sendOpenRavenChestSelectScreen(@NotNull ServerPlayer player,
                                                int ravenEntityId,
                                                @NotNull List<RavenChestChoiceInfo> choices,
                                                @NotNull RavenChestSelectAction action) {
        // no-op
    }

    default void sendSetRavenChestLabelToServer(@NotNull String dimensionId,
                                                long blockPos,
                                                @NotNull String label) {
        // no-op
    }

    default void sendConfirmRavenChestDepositToServer(int ravenEntityId,
                                                      @NotNull String dimensionId,
                                                      long blockPos) {
        sendConfirmRavenChestDepositToServer(
                ravenEntityId,
                dimensionId,
                blockPos,
                RavenChestSelectAction.ENDERPACK_DEPOSIT
        );
    }

    default void sendConfirmRavenChestDepositToServer(int ravenEntityId,
                                                      @NotNull String dimensionId,
                                                      long blockPos,
                                                      @NotNull RavenChestSelectAction action) {
        // no-op
    }

    default boolean hasServerSettingsSynced() {
        return false;
    }

    default boolean isChatDisabledClient() {
        return false;
    }

    default boolean isSuspiciousFeatherEnabledClient() {
        return true;
    }

    default boolean isSuspiciousChestEnabledClient() {
        return true;
    }

    default boolean isRavenArmorEnabledClient() {
        return true;
    }

    default boolean isMailboxEnabledClient() {
        return true;
    }

    default boolean canEditChat() {
        return false;
    }

    default int getWildRavensPerPlayerClient() {
        return 0;
    }

    default int getRavenLinkDurationSecondsClient() {
        return 0;
    }

    default int getMaxRavenChestsPerPlayerClient() {
        return 0;
    }

    default int getRavenLogRetentionMinutesClient() {
        return 0;
    }

    default int getRavenLogMaxBytesPerPlayerClient() {
        return 0;
    }

    default int getEnderpackDepositCooldownSecondsClient() {
        return 0;
    }

    default int getScrollDeliveryCooldownSecondsClient() {
        return 0;
    }

    default int getCourierTimeoutRetrySecondsClient() {
        return 0;
    }

    default void requestServerSettingsSync() {
        // no-op
    }

    default void sendSetChatDisabled(boolean value) {
        // no-op
    }

    default void sendSetEnableSuspiciousFeather(boolean value) {
        // no-op
    }

    default void sendSetEnableSuspiciousChest(boolean value) {
        // no-op
    }

    default void sendSetEnableRavenArmor(boolean value) {
        // no-op
    }

    default void sendSetEnableMailbox(boolean value) {
        // no-op
    }

    default void sendSetWildRavensPerPlayer(int value) {
        // no-op
    }

    default void sendSetRavenLinkDurationSeconds(int value) {
        // no-op
    }

    default void sendSetMaxRavenChestsPerPlayer(int value) {
        // no-op
    }

    default void sendSetRavenLogRetentionMinutes(int value) {
        // no-op
    }

    default void sendSetRavenLogMaxBytesPerPlayer(int value) {
        // no-op
    }

    default void sendSetEnderpackDepositCooldownSeconds(int value) {
        // no-op
    }

    default void sendSetScrollDeliveryCooldownSeconds(int value) {
        // no-op
    }

    default void sendSetCourierTimeoutRetrySeconds(int value) {
        // no-op
    }

    default boolean tryStartRavenLink(@NotNull ServerPlayer player) {
        return false;
    }

    default void sendRavenLinkInputToServer(boolean forward,
                                            boolean backward,
                                            boolean left,
                                            boolean right,
                                            boolean ascend,
                                            boolean descend,
                                            float yaw,
                                            float pitch) {
        // no-op
    }

    default void sendStopRavenLinkToServer() {
        // no-op
    }

    default void sendRavenLinkBlackoutAckToServer() {
        // no-op
    }

    default void sendRavenLinkEffigyPoseSnapshotToServer() {
        // no-op
    }

    default int getMaxRavenChestsPerPlayer() {
        return 0;
    }

    default int getRavenLogRetentionMinutes() {
        return 60 * 24 * 7;
    }

    default int getRavenLogMaxBytesPerPlayer() {
        return 262_144;
    }

    default int getEnderpackDepositCooldownSeconds() {
        return 0;
    }

    default int getScrollDeliveryCooldownSeconds() {
        return 0;
    }

    default int getCourierTimeoutRetrySeconds() {
        return 0;
    }

    // ---------------------------------------------------------------------
    // Server feature toggles
    // ---------------------------------------------------------------------

    default boolean isSuspiciousFeatherEnabled() {
        return true;
    }

    default boolean isSuspiciousChestEnabled() {
        return true;
    }

    default boolean isRavenArmorEnabled() {
        return true;
    }

    default boolean isMailboxEnabled() {
        return true;
    }
}
