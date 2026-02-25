// neoforge/src/main/java/net/z2six/featheredfriend/platform/NeoForgePlatformHelper.java
package net.z2six.featheredfriend.platform;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.z2six.featheredfriend.client.font.ScrollUiFontMode;
import net.z2six.featheredfriend.config.FFClientConfig;
import net.z2six.featheredfriend.config.FFServerConfig;
import net.z2six.featheredfriend.client.ravenbadge.RavenStatusGuiAnchor;
import net.z2six.featheredfriend.client.ravenbadge.RavenStatusGuiVisualMode;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.item.EnderpackSharedStorage;
import net.z2six.featheredfriend.item.SealStampItem;
import net.z2six.featheredfriend.item.SealStampSlotEntry;
import net.z2six.featheredfriend.item.EnderpackStackRef;
import net.z2six.featheredfriend.menu.EnderpackMenu;
import net.z2six.featheredfriend.menu.MailboxMenu;
import net.z2six.featheredfriend.menu.RavenChestMenu;
import net.z2six.featheredfriend.menu.ScrollSealingMenu;
import net.z2six.featheredfriend.menu.SealStampMenu;
import net.z2six.featheredfriend.menu.ScrollViewMenu;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.network.FFPayloads;
import net.z2six.featheredfriend.network.RavenChestChoiceInfo;
import net.z2six.featheredfriend.network.RavenChestSelectAction;
import net.z2six.featheredfriend.platform.services.IPlatformHelper;
import net.z2six.featheredfriend.registry.FFItems;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import net.z2six.featheredfriend.world.RavenBadgeRuntime;
import net.z2six.featheredfriend.world.RavenCourierRuntime;
import net.z2six.featheredfriend.world.RavenLinkRuntime;
import net.z2six.featheredfriend.world.TamedRavenScrollWatcher;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class NeoForgePlatformHelper implements IPlatformHelper {

    private static final Logger LOG = LogUtils.getLogger();

    public NeoForgePlatformHelper() {
        LOG.debug("[NeoForgePlatformHelper] Constructed for platform '{}'", getPlatformName());
    }

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] isModLoaded('{}') failed", modId, t);
            return false;
        }
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        try {
            return !FMLLoader.isProduction();
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] isDevelopmentEnvironment() failed, assuming production", t);
            return false;
        }
    }

    @Override
    public void openScrollSealingScreen(@NotNull ServerPlayer player) {
        try {
            player.openMenu(new SimpleMenuProvider(
                    (int containerId, Inventory inv, Player p) ->
                            new ScrollSealingMenu(containerId, inv),
                    Component.translatable("screen.featheredfriend.scroll_sealing")
            ));
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] Failed to open Scroll Sealing menu", t);
        }
    }

    @Override
    public void openSealStampScreen(@NotNull ServerPlayer player) {
        try {
            int slot = player.getMainHandItem().isEmpty() ? 37 : 36;

            player.openMenu(new SimpleMenuProvider(
                    (int containerId, Inventory inv, Player p) ->
                            new SealStampMenu(containerId, inv, slot),
                    Component.translatable("screen.featheredfriend.seal_stamp")
            ));
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] Failed to open Seal Stamp menu", t);
        }
    }

    @Override
    public void openScrollViewScreen(@NotNull ServerPlayer player) {
        try {
            player.openMenu(new SimpleMenuProvider(
                    (int containerId, Inventory inv, Player p) ->
                            new ScrollViewMenu(containerId, inv),
                    Component.translatable("screen.featheredfriend.scroll_view")
            ));
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] Failed to open Scroll View menu", t);
        }
    }

    @Override
    public void openEnderpackScreen(@NotNull ServerPlayer player) {
        openEnderpackScreen(player, InteractionHand.MAIN_HAND);
    }

    @Override
    public void openEnderpackScreen(@NotNull ServerPlayer player, @NotNull InteractionHand preferredHand) {
        try {
            EnderpackSource source = resolveEnderpackSource(player, preferredHand);
            if (source == null) {
                player.displayClientMessage(
                        Component.translatable("message.featheredfriend.enderpack.none_found"),
                        true
                );
                return;
            }

            ItemStack sourceStack = source.ref.getCurrentStack();
            if (FFItems.isEnderpack(sourceStack)) {
                EnderpackSharedStorage.migrateLegacyDataFromStackIfSharedEmpty(
                        player,
                        sourceStack,
                        player.server.registryAccess()
                );
                source.ref.setCurrentStack(sourceStack);
            }

            player.openMenu(new SimpleMenuProvider(
                    (int containerId, Inventory inv, Player p) -> {
                        EnderpackMenu menu = new EnderpackMenu(containerId, inv);
                        menu.bindServerStorage(player, player.server.registryAccess());
                        return menu;
                    },
                    Component.translatable("container.featheredfriend.enderpack")
            ));
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] Failed to open Enderpack menu", t);
        }
    }

    @Override
    public boolean getChatDisabledDefault() {
        try {
            return FFServerConfig.getChatDisabledDefault();
        } catch (Throwable t) {
            LOG.warn("[NeoForgePlatformHelper] getChatDisabledDefault failed safely: {}", t.toString());
            return false;
        }
    }

    @Override
    public @NotNull InteractionResult handleSealedScrollInteract(@NotNull RavenEntity raven,
                                                                 @NotNull Player player,
                                                                 @NotNull InteractionHand hand) {
        return TamedRavenScrollWatcher.handleSealedScrollInteract(raven, player, hand);
    }

    @Override
    public void handleCourierRavenLandedHit(@NotNull RavenEntity raven) {
        RavenCourierRuntime.handleCourierRavenLandedHit(raven);
    }

    @Override
    public void notifyRavenBadgeHit(@NotNull RavenEntity raven, boolean dodged) {
        RavenBadgeRuntime.onRavenHit(raven, dodged);
    }

    @Override
    public void notifyRavenBadgeThreatDetected(@NotNull RavenEntity raven, boolean hasScrollPayload) {
        RavenBadgeRuntime.onRavenThreatDetected(raven, hasScrollPayload);
    }

    @Override
    public void notifyRavenBadgeDeliveryRepath(@NotNull RavenEntity raven) {
        RavenBadgeRuntime.onRavenDeliveryRepath(raven);
    }

    @Override
    public boolean isScrollSummonedRaven(@NotNull RavenEntity raven) {
        return TamedRavenScrollWatcher.isScrollSummonedRaven(raven);
    }

    @Override
    public ServerPlayer getScrollSummonOwnerIfHoldingScroll(@NotNull ServerLevel level,
                                                            @NotNull RavenEntity raven) {
        return TamedRavenScrollWatcher.getScrollSummonOwnerIfHoldingScroll(level, raven);
    }

    @Override
    public @NotNull CompoundTag getPlayerPersistentData(@NotNull ServerPlayer player) {
        return player.getPersistentData();
    }

    @Override
    public @NotNull CompoundTag getEntityPersistentData(@NotNull Entity entity) {
        return entity.getPersistentData();
    }

    @Override
    public MenuType<ScrollSealingMenu> getScrollSealingMenuType() {
        return FFNeoForgeMenus.SCROLL_SEALING_MENU.get();
    }

    @Override
    public MenuType<SealStampMenu> getSealStampMenuType() {
        return FFNeoForgeMenus.SEAL_STAMP_MENU.get();
    }

    @Override
    public MenuType<ScrollViewMenu> getScrollViewMenuType() {
        return FFNeoForgeMenus.SCROLL_VIEW_MENU.get();
    }

    @Override
    public MenuType<EnderpackMenu> getEnderpackMenuType() {
        return FFNeoForgeMenus.ENDERPACK_MENU.get();
    }

    @Override
    public MenuType<MailboxMenu> getMailboxMenuType() {
        return FFNeoForgeMenus.MAILBOX_MENU.get();
    }

    @Override
    public MenuType<RavenChestMenu> getRavenChestMenuType() {
        return FFNeoForgeMenus.RAVEN_CHEST_MENU.get();
    }

    @Override
    public @NotNull List<SealStampSlotEntry> getExternalSealStampEntries(@NotNull Player player) {
        if (!isModLoaded("curios")) {
            return List.of();
        }

        try {
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Method getCuriosInventory = curiosApiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);

            @SuppressWarnings("unchecked")
            Optional<Object> curiosInventory = (Optional<Object>) getCuriosInventory.invoke(null, player);
            if (curiosInventory.isEmpty()) {
                return List.of();
            }

            Object curiosHandler = curiosInventory.get();
            Method findCurios = curiosHandler.getClass().getMethod("findCurios", Predicate.class);
            Method slotContextMethod = Class.forName("top.theillusivec4.curios.api.SlotResult").getMethod("slotContext");
            Method stackMethod = Class.forName("top.theillusivec4.curios.api.SlotResult").getMethod("stack");
            Method identifierMethod = Class.forName("top.theillusivec4.curios.api.SlotContext").getMethod("identifier");
            Method indexMethod = Class.forName("top.theillusivec4.curios.api.SlotContext").getMethod("index");

            @SuppressWarnings("unchecked")
            List<Object> results = (List<Object>) findCurios.invoke(
                    curiosHandler,
                    (Predicate<net.minecraft.world.item.ItemStack>) stack ->
                            stack != null && !stack.isEmpty() && stack.getItem() instanceof SealStampItem
            );

            if (results == null || results.isEmpty()) {
                return List.of();
            }

            List<SealStampSlotEntry> entries = new ArrayList<>();
            int virtualIndex = 0;
            for (Object result : results) {
                if (result == null) {
                    continue;
                }

                Object slotContext = slotContextMethod.invoke(result);
                net.minecraft.world.item.ItemStack stack =
                        (net.minecraft.world.item.ItemStack) stackMethod.invoke(result);

                if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof SealStampItem)) {
                    continue;
                }

                int virtualSlot = SealStampSlotEntry.VIRTUAL_SLOT_BASE + virtualIndex;
                virtualIndex++;
                entries.add(new SealStampSlotEntry(virtualSlot, stack.copy()));

                try {
                    String identifier = String.valueOf(identifierMethod.invoke(slotContext));
                    int idx = (int) indexMethod.invoke(slotContext);
                    LOG.debug("[NeoForgePlatformHelper] Added Curios seal stamp entry [{}:{}] -> virtualSlot={}",
                            identifier, idx, virtualSlot);
                } catch (Throwable ignored) {
                    // Optional debug metadata only.
                }
            }

            return entries.isEmpty() ? List.of() : entries;
        } catch (Throwable t) {
            LOG.debug("[NeoForgePlatformHelper] Curios stamp scan unavailable: {}", t.toString());
            return List.of();
        }
    }

    @Override
    public boolean isUseVanillaFontForGothicText() {
        return FFClientConfig.isUseVanillaFontForGothicText();
    }

    @Override
    public void setUseVanillaFontForGothicText(boolean value) {
        FFClientConfig.setUseVanillaFontForGothicText(value);
    }

    @Override
    public boolean getUseVanillaFontForGothicTextDefault() {
        return FFClientConfig.DEFAULT_USE_VANILLA_FONT_FOR_GOTHIC_TEXT;
    }

    @Override
    public @NotNull ScrollUiFontMode getScrollUiFontMode() {
        return FFClientConfig.getScrollUiFontMode();
    }

    @Override
    public void setScrollUiFontMode(@NotNull ScrollUiFontMode mode) {
        FFClientConfig.setScrollUiFontMode(mode);
    }

    @Override
    public @NotNull String getFavoriteStampKey() {
        return FFClientConfig.getFavoriteStampKey();
    }

    @Override
    public void setFavoriteStampKey(@NotNull String value) {
        FFClientConfig.setFavoriteStampKey(value);
    }

    @Override
    public void saveClientConfig() {
        FFClientConfig.save();
    }

    @Override
    public @NotNull String getRavenLogViewSettingsRaw() {
        return FFClientConfig.getRavenLogViewSettingsRaw();
    }

    @Override
    public void setRavenLogViewSettingsRaw(@NotNull String value) {
        FFClientConfig.setRavenLogViewSettingsRaw(value);
    }

    @Override
    public int getRavenStatusGuiX() {
        return FFClientConfig.getRavenStatusGuiX();
    }

    @Override
    public int getRavenStatusGuiY() {
        return FFClientConfig.getRavenStatusGuiY();
    }

    @Override
    public void setRavenStatusGuiX(int value) {
        FFClientConfig.setRavenStatusGuiX(value);
    }

    @Override
    public void setRavenStatusGuiY(int value) {
        FFClientConfig.setRavenStatusGuiY(value);
    }

    @Override
    public @NotNull RavenStatusGuiAnchor getRavenStatusGuiAnchor() {
        return FFClientConfig.getRavenStatusGuiAnchor();
    }

    @Override
    public void setRavenStatusGuiAnchor(@NotNull RavenStatusGuiAnchor anchor) {
        FFClientConfig.setRavenStatusGuiAnchor(anchor);
    }

    @Override
    public @NotNull RavenStatusGuiVisualMode getRavenStatusGuiVisualMode() {
        return FFClientConfig.getRavenStatusGuiVisualMode();
    }

    @Override
    public void setRavenStatusGuiVisualMode(@NotNull RavenStatusGuiVisualMode mode) {
        FFClientConfig.setRavenStatusGuiVisualMode(mode);
    }

    @Override
    public void sendOpenRavenNamingScreen(@NotNull ServerPlayer player, int ravenEntityId) {
        FFNetwork.sendOpenRavenNamingScreen(player, ravenEntityId);
    }

    @Override
    public void sendRavenNameChosenToServer(int ravenEntityId, @NotNull String name) {
        FFNetwork.sendRavenNameChosenToServer(ravenEntityId, name);
    }

    @Override
    public void sendRavenNameCancelledToServer(int ravenEntityId) {
        FFNetwork.sendRavenNameCancelledToServer(ravenEntityId);
    }

    @Override
    public void sendWaxSealToServer(int sealStampSlotIndex,
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
        FFNetwork.sendWaxSealToServer(
                sealStampSlotIndex,
                dateText,
                recipientName,
                recipientUuid,
                recipientText,
                messageText,
                signatureText,
                seed,
                slices,
                style,
                senderName
        );
    }

    @Override
    public void sendBreakSealToServer(int slotHint,
                                      long seed,
                                      @NotNull String recipientUuid,
                                      @NotNull String dateText,
                                      @NotNull String senderName) {
        FFNetwork.sendBreakSealToServer(slotHint, seed, recipientUuid, dateText, senderName);
    }

    @Override
    public void sendSealStampCarveResultToServer(int stampSlot,
                                                 long seed,
                                                 int slices,
                                                 int style,
                                                 @NotNull String ownerName) {
        PacketDistributor.sendToServer(new net.z2six.featheredfriend.network.SealStampCarveResultPacket(
                stampSlot, seed, slices, style, ownerName
        ));
    }

    @Override
    public void sendRequestKnownPlayersToServer() {
        FFNetwork.sendRequestKnownPlayersToServer();
    }

    @Override
    public void sendOpenEnderpackToServer() {
        FFNetwork.sendOpenEnderpackToServer();
    }

    @Override
    public void sendOpenRavenLogToServer() {
        FFNetwork.sendOpenRavenLogToServer();
    }

    @Override
    public void sendClearRavenLogToServer() {
        FFNetwork.sendClearRavenLogToServer();
    }

    @Override
    public boolean hasAccessibleEnderpack(@NotNull ServerPlayer player) {
        return resolveEnderpackSource(player, InteractionHand.MAIN_HAND) != null;
    }

    @Override
    public int transferEnderpackIntoContainer(@NotNull ServerPlayer player,
                                              @NotNull Container container,
                                              @NotNull HolderLookup.Provider registries) {
        try {
            EnderpackSource source = resolveEnderpackSource(player, InteractionHand.MAIN_HAND);
            if (source == null) {
                return -1;
            }

            ItemStack sourceStack = source.ref.getCurrentStack();
            if (!FFItems.isEnderpack(sourceStack)) {
                return -1;
            }

            EnderpackSharedStorage.migrateLegacyDataFromStackIfSharedEmpty(player, sourceStack, registries);
            source.ref.setCurrentStack(sourceStack);

            List<ItemStack> packStacks = new ArrayList<>(EnderpackSharedStorage.load(player, registries));
            if (packStacks.isEmpty()) {
                return 0;
            }

            int moved = moveStacksIntoContainer(packStacks, container);
            EnderpackSharedStorage.save(player, packStacks, registries);
            container.setChanged();
            return moved;
        } catch (Throwable t) {
            LOG.error("[NeoForgePlatformHelper] transferEnderpackIntoContainer failed safely", t);
            return -1;
        }
    }

    @Override
    public void sendOpenRavenChestLabelScreen(@NotNull ServerPlayer player,
                                              @NotNull String dimensionId,
                                              long blockPos,
                                              @NotNull String currentLabel) {
        FFNetwork.sendOpenRavenChestLabelScreen(player, dimensionId, blockPos, currentLabel);
    }

    @Override
    public void sendOpenRavenChestSelectScreen(@NotNull ServerPlayer player,
                                               int ravenEntityId,
                                               @NotNull List<RavenChestChoiceInfo> choices) {
        sendOpenRavenChestSelectScreen(player, ravenEntityId, choices, RavenChestSelectAction.ENDERPACK_DEPOSIT);
    }

    @Override
    public void sendOpenRavenChestSelectScreen(@NotNull ServerPlayer player,
                                               int ravenEntityId,
                                               @NotNull List<RavenChestChoiceInfo> choices,
                                               @NotNull RavenChestSelectAction action) {
        FFNetwork.sendOpenRavenChestSelectScreen(player, ravenEntityId, choices, action);
    }

    @Override
    public void sendSetRavenChestLabelToServer(@NotNull String dimensionId,
                                               long blockPos,
                                               @NotNull String label) {
        FFNetwork.sendSetRavenChestLabelToServer(dimensionId, blockPos, label);
    }

    @Override
    public void sendConfirmRavenChestDepositToServer(int ravenEntityId,
                                                     @NotNull String dimensionId,
                                                     long blockPos) {
        sendConfirmRavenChestDepositToServer(
                ravenEntityId,
                dimensionId,
                blockPos,
                RavenChestSelectAction.ENDERPACK_DEPOSIT
        );
    }

    @Override
    public void sendConfirmRavenChestDepositToServer(int ravenEntityId,
                                                     @NotNull String dimensionId,
                                                     long blockPos,
                                                     @NotNull RavenChestSelectAction action) {
        FFNetwork.sendConfirmRavenChestDepositToServer(ravenEntityId, dimensionId, blockPos, action);
    }

    @Override
    public boolean hasServerSettingsSynced() {
        return FFPayloads.ClientState.hasSynced();
    }

    @Override
    public boolean isChatDisabledClient() {
        return FFPayloads.ClientState.isChatDisabled();
    }

    @Override
    public boolean isSuspiciousFeatherEnabledClient() {
        return FFPayloads.ClientState.isSuspiciousFeatherEnabled();
    }

    @Override
    public boolean isSuspiciousChestEnabledClient() {
        return FFPayloads.ClientState.isSuspiciousChestEnabled();
    }

    @Override
    public boolean isRavenArmorEnabledClient() {
        return FFPayloads.ClientState.isRavenArmorEnabled();
    }

    @Override
    public boolean isMailboxEnabledClient() {
        return FFPayloads.ClientState.isMailboxEnabled();
    }

    @Override
    public boolean canEditChat() {
        return FFPayloads.ClientState.canEditChat();
    }

    @Override
    public int getWildRavensPerPlayerClient() {
        return FFPayloads.ClientState.wildRavensPerPlayer();
    }

    @Override
    public int getRavenLinkDurationSecondsClient() {
        return FFPayloads.ClientState.ravenLinkDurationSeconds();
    }

    @Override
    public int getMaxRavenChestsPerPlayerClient() {
        return FFPayloads.ClientState.maxRavenChestsPerPlayer();
    }

    @Override
    public int getRavenLogRetentionMinutesClient() {
        return FFPayloads.ClientState.ravenLogRetentionMinutes();
    }

    @Override
    public int getRavenLogMaxBytesPerPlayerClient() {
        return FFPayloads.ClientState.ravenLogMaxBytesPerPlayer();
    }

    @Override
    public int getEnderpackDepositCooldownSecondsClient() {
        return FFPayloads.ClientState.enderpackDepositCooldownSeconds();
    }

    @Override
    public int getScrollDeliveryCooldownSecondsClient() {
        return FFPayloads.ClientState.scrollDeliveryCooldownSeconds();
    }

    @Override
    public int getCourierTimeoutRetrySecondsClient() {
        return FFPayloads.ClientState.courierTimeoutRetrySeconds();
    }

    @Override
    public int getBrushRavenCooldownSecondsClient() {
        return FFPayloads.ClientState.brushRavenCooldownSeconds();
    }

    @Override
    public void requestServerSettingsSync() {
        PacketDistributor.sendToServer(new FFPayloads.RequestServerSettingsPayload());
    }

    @Override
    public void sendSetChatDisabled(boolean value) {
        PacketDistributor.sendToServer(new FFPayloads.SetChatDisabledPayload(value));
    }

    @Override
    public void sendSetEnableSuspiciousFeather(boolean value) {
        PacketDistributor.sendToServer(new FFPayloads.SetEnableSuspiciousFeatherPayload(value));
    }

    @Override
    public void sendSetEnableSuspiciousChest(boolean value) {
        PacketDistributor.sendToServer(new FFPayloads.SetEnableSuspiciousChestPayload(value));
    }

    @Override
    public void sendSetEnableRavenArmor(boolean value) {
        PacketDistributor.sendToServer(new FFPayloads.SetEnableRavenArmorPayload(value));
    }

    @Override
    public void sendSetEnableMailbox(boolean value) {
        PacketDistributor.sendToServer(new FFPayloads.SetEnableMailboxPayload(value));
    }

    @Override
    public void sendSetWildRavensPerPlayer(int value) {
        PacketDistributor.sendToServer(new FFPayloads.SetWildRavensPerPlayerPayload(value));
    }

    @Override
    public void sendSetRavenLinkDurationSeconds(int value) {
        PacketDistributor.sendToServer(new FFPayloads.SetRavenLinkDurationSecondsPayload(value));
    }

    @Override
    public void sendSetMaxRavenChestsPerPlayer(int value) {
        PacketDistributor.sendToServer(new FFPayloads.SetMaxRavenChestsPerPlayerPayload(value));
    }

    @Override
    public void sendSetRavenLogRetentionMinutes(int value) {
        PacketDistributor.sendToServer(new FFPayloads.SetRavenLogRetentionMinutesPayload(value));
    }

    @Override
    public void sendSetRavenLogMaxBytesPerPlayer(int value) {
        PacketDistributor.sendToServer(new FFPayloads.SetRavenLogMaxBytesPerPlayerPayload(value));
    }

    @Override
    public void sendSetEnderpackDepositCooldownSeconds(int value) {
        PacketDistributor.sendToServer(new FFPayloads.SetEnderpackDepositCooldownSecondsPayload(value));
    }

    @Override
    public void sendSetScrollDeliveryCooldownSeconds(int value) {
        PacketDistributor.sendToServer(new FFPayloads.SetScrollDeliveryCooldownSecondsPayload(value));
    }

    @Override
    public void sendSetCourierTimeoutRetrySeconds(int value) {
        PacketDistributor.sendToServer(new FFPayloads.SetCourierTimeoutRetrySecondsPayload(value));
    }

    @Override
    public void sendSetBrushRavenCooldownSeconds(int value) {
        PacketDistributor.sendToServer(new FFPayloads.SetBrushRavenCooldownSecondsPayload(value));
    }

    @Override
    public boolean tryStartRavenLink(@NotNull ServerPlayer player) {
        return RavenLinkRuntime.tryStartLink(player);
    }

    @Override
    public void sendRavenLinkInputToServer(boolean forward,
                                           boolean backward,
                                           boolean left,
                                           boolean right,
                                           boolean ascend,
                                           boolean descend,
                                           float yaw,
                                           float pitch) {
        FFNetwork.sendRavenLinkInputToServer(forward, backward, left, right, ascend, descend, yaw, pitch);
    }

    @Override
    public void sendStopRavenLinkToServer() {
        FFNetwork.sendStopRavenLinkRequestToServer();
    }

    @Override
    public void sendRavenLinkBlackoutAckToServer() {
        FFNetwork.sendRavenLinkBlackoutAckToServer();
    }

    @Override
    public void sendRavenLinkEffigyPoseSnapshotToServer() {
        FFNetwork.sendRavenLinkEffigyPoseSnapshotToServer();
    }

    @Override
    public int getMaxRavenChestsPerPlayer() {
        return FFServerConfig.getRavenChestsPerPlayer();
    }

    @Override
    public int getRavenLogRetentionMinutes() {
        return FFServerConfig.getRavenLogRetentionMinutes();
    }

    @Override
    public int getRavenLogMaxBytesPerPlayer() {
        return FFServerConfig.getRavenLogMaxBytesPerPlayer();
    }

    @Override
    public int getEnderpackDepositCooldownSeconds() {
        return FFServerConfig.getEnderpackDepositCooldownSeconds();
    }

    @Override
    public int getScrollDeliveryCooldownSeconds() {
        return FFServerConfig.getScrollDeliveryCooldownSeconds();
    }

    @Override
    public int getCourierTimeoutRetrySeconds() {
        return FFServerConfig.getCourierTimeoutRetrySeconds();
    }

    @Override
    public int getBrushRavenCooldownSeconds() {
        return FFServerConfig.getBrushRavenCooldownSeconds();
    }

    @Override
    public boolean isSuspiciousFeatherEnabled() {
        return FFServerConfig.isSuspiciousFeatherEnabled();
    }

    @Override
    public boolean isSuspiciousChestEnabled() {
        return FFServerConfig.isSuspiciousChestEnabled();
    }

    @Override
    public boolean isRavenArmorEnabled() {
        return FFServerConfig.isRavenArmorEnabled();
    }

    @Override
    public boolean isMailboxEnabled() {
        return FFServerConfig.isMailboxEnabled();
    }

    // ---------------------------------------------------------------------
    // Enderpack source resolution
    // ---------------------------------------------------------------------

    private static int moveStacksIntoContainer(@NotNull List<ItemStack> sourceStacks,
                                               @NotNull Container container) {
        int moved = 0;
        int slots = container.getContainerSize();
        for (int i = 0; i < sourceStacks.size(); i++) {
            ItemStack remaining = sourceStacks.get(i);
            if (remaining == null || remaining.isEmpty()) {
                sourceStacks.set(i, ItemStack.EMPTY);
                continue;
            }

            ItemStack work = remaining.copy();

            // Merge into compatible stacks first.
            for (int slot = 0; slot < slots && !work.isEmpty(); slot++) {
                ItemStack target = container.getItem(slot);
                if (target.isEmpty()) {
                    continue;
                }
                if (!ItemStack.isSameItemSameComponents(target, work)) {
                    continue;
                }
                int max = Math.min(target.getMaxStackSize(), container.getMaxStackSize());
                int room = max - target.getCount();
                if (room <= 0) {
                    continue;
                }
                int toMove = Math.min(room, work.getCount());
                if (toMove <= 0) {
                    continue;
                }
                target.grow(toMove);
                work.shrink(toMove);
                moved += toMove;
                container.setItem(slot, target);
            }

            // Then fill empty slots.
            for (int slot = 0; slot < slots && !work.isEmpty(); slot++) {
                ItemStack target = container.getItem(slot);
                if (!target.isEmpty()) {
                    continue;
                }
                int toMove = Math.min(work.getCount(), Math.min(work.getMaxStackSize(), container.getMaxStackSize()));
                if (toMove <= 0) {
                    continue;
                }
                ItemStack placed = work.copy();
                placed.setCount(toMove);
                container.setItem(slot, placed);
                work.shrink(toMove);
                moved += toMove;
            }

            sourceStacks.set(i, work.isEmpty() ? ItemStack.EMPTY : work);
        }
        return moved;
    }

    private record EnderpackSource(@NotNull EnderpackStackRef ref, int priority) {
    }

    private @org.jetbrains.annotations.Nullable EnderpackSource resolveEnderpackSource(@NotNull ServerPlayer player,
                                                                                       @NotNull InteractionHand preferredHand) {
        try {
            List<EnderpackSource> candidates = new ArrayList<>();

            // Preferred hand first.
            if (preferredHand == InteractionHand.MAIN_HAND) {
                if (FFItems.isEnderpack(player.getMainHandItem())) {
                    candidates.add(new EnderpackSource(new EnderpackStackRef() {
                        @Override
                        public @NotNull net.minecraft.world.item.ItemStack getCurrentStack() {
                            return player.getMainHandItem();
                        }

                        @Override
                        public void setCurrentStack(@NotNull net.minecraft.world.item.ItemStack stack) {
                            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                        }
                    }, 0));
                }
                if (FFItems.isEnderpack(player.getOffhandItem())) {
                    candidates.add(new EnderpackSource(new EnderpackStackRef() {
                        @Override
                        public @NotNull net.minecraft.world.item.ItemStack getCurrentStack() {
                            return player.getOffhandItem();
                        }

                        @Override
                        public void setCurrentStack(@NotNull net.minecraft.world.item.ItemStack stack) {
                            player.setItemInHand(InteractionHand.OFF_HAND, stack);
                        }
                    }, 1));
                }
            } else {
                if (FFItems.isEnderpack(player.getOffhandItem())) {
                    candidates.add(new EnderpackSource(new EnderpackStackRef() {
                        @Override
                        public @NotNull net.minecraft.world.item.ItemStack getCurrentStack() {
                            return player.getOffhandItem();
                        }

                        @Override
                        public void setCurrentStack(@NotNull net.minecraft.world.item.ItemStack stack) {
                            player.setItemInHand(InteractionHand.OFF_HAND, stack);
                        }
                    }, 0));
                }
                if (FFItems.isEnderpack(player.getMainHandItem())) {
                    candidates.add(new EnderpackSource(new EnderpackStackRef() {
                        @Override
                        public @NotNull net.minecraft.world.item.ItemStack getCurrentStack() {
                            return player.getMainHandItem();
                        }

                        @Override
                        public void setCurrentStack(@NotNull net.minecraft.world.item.ItemStack stack) {
                            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                        }
                    }, 1));
                }
            }

            for (int i = 0; i < player.getInventory().items.size(); i++) {
                final int slot = i;
                net.minecraft.world.item.ItemStack s = player.getInventory().items.get(i);
                if (!FFItems.isEnderpack(s)) {
                    continue;
                }
                candidates.add(new EnderpackSource(new EnderpackStackRef() {
                    @Override
                    public @NotNull net.minecraft.world.item.ItemStack getCurrentStack() {
                        return player.getInventory().items.get(slot);
                    }

                    @Override
                    public void setCurrentStack(@NotNull net.minecraft.world.item.ItemStack stack) {
                        player.getInventory().items.set(slot, stack);
                    }
                }, 2));
            }

            // Curios fallback.
            candidates.addAll(resolveCuriosEnderpacks(player, 3));

            return candidates.stream()
                    .min(Comparator.comparingInt(EnderpackSource::priority))
                    .orElse(null);
        } catch (Throwable t) {
            LOG.warn("[NeoForgePlatformHelper] resolveEnderpackSource failed safely: {}", t.toString());
            return null;
        }
    }

    private @NotNull List<EnderpackSource> resolveCuriosEnderpacks(@NotNull ServerPlayer player, int basePriority) {
        if (!isModLoaded("curios")) {
            return List.of();
        }

        try {
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Method getCuriosInventory = curiosApiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);

            @SuppressWarnings("unchecked")
            Optional<Object> curiosInventory = (Optional<Object>) getCuriosInventory.invoke(null, player);
            if (curiosInventory.isEmpty()) {
                return List.of();
            }

            Object curiosHandler = curiosInventory.get();
            Method findCurios = curiosHandler.getClass().getMethod("findCurios", Predicate.class);
            Method slotContextMethod = Class.forName("top.theillusivec4.curios.api.SlotResult").getMethod("slotContext");
            Method stackMethod = Class.forName("top.theillusivec4.curios.api.SlotResult").getMethod("stack");
            Method identifierMethod = Class.forName("top.theillusivec4.curios.api.SlotContext").getMethod("identifier");
            Method indexMethod = Class.forName("top.theillusivec4.curios.api.SlotContext").getMethod("index");
            Method setEquippedCurio = curiosHandler.getClass().getMethod("setEquippedCurio", String.class, int.class, net.minecraft.world.item.ItemStack.class);

            @SuppressWarnings("unchecked")
            List<Object> results = (List<Object>) findCurios.invoke(
                    curiosHandler,
                    (Predicate<net.minecraft.world.item.ItemStack>) stack ->
                            stack != null && !stack.isEmpty() && FFItems.isEnderpack(stack)
            );

            if (results == null || results.isEmpty()) {
                return List.of();
            }

            List<EnderpackSource> out = new ArrayList<>();
            for (Object result : results) {
                if (result == null) {
                    continue;
                }

                Object slotContext = slotContextMethod.invoke(result);
                net.minecraft.world.item.ItemStack stack =
                        (net.minecraft.world.item.ItemStack) stackMethod.invoke(result);
                if (!FFItems.isEnderpack(stack)) {
                    continue;
                }

                String identifier = String.valueOf(identifierMethod.invoke(slotContext));
                int idx = (int) indexMethod.invoke(slotContext);

                out.add(new EnderpackSource(new EnderpackStackRef() {
                    @Override
                    public @NotNull net.minecraft.world.item.ItemStack getCurrentStack() {
                        try {
                            @SuppressWarnings("unchecked")
                            List<Object> refreshed = (List<Object>) findCurios.invoke(
                                    curiosHandler,
                                    (Predicate<net.minecraft.world.item.ItemStack>) s ->
                                            s != null && !s.isEmpty() && FFItems.isEnderpack(s)
                            );
                            if (refreshed != null) {
                                for (Object r : refreshed) {
                                    Object sc = slotContextMethod.invoke(r);
                                    String id = String.valueOf(identifierMethod.invoke(sc));
                                    int i = (int) indexMethod.invoke(sc);
                                    if (identifier.equals(id) && idx == i) {
                                        return (net.minecraft.world.item.ItemStack) stackMethod.invoke(r);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return net.minecraft.world.item.ItemStack.EMPTY;
                    }

                    @Override
                    public void setCurrentStack(@NotNull net.minecraft.world.item.ItemStack newStack) {
                        try {
                            setEquippedCurio.invoke(curiosHandler, identifier, idx, newStack);
                        } catch (Throwable ignored) {
                        }
                    }
                }, basePriority));
            }
            return out;
        } catch (Throwable t) {
            LOG.debug("[NeoForgePlatformHelper] resolveCuriosEnderpacks unavailable: {}", t.toString());
            return List.of();
        }
    }
}
