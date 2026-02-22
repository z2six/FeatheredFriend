package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.z2six.featheredfriend.platform.Services;
import net.z2six.featheredfriend.registry.FFItems;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.List;

/**
 * Client-only tooltip hook for server-authoritative feature toggles.
 */
public final class FFFeatureToggleTooltips {

    private static final Logger LOG = LogUtils.getLogger();

    private static volatile boolean REGISTERED = false;

    private FFFeatureToggleTooltips() {
        // no-op
    }

    public static void registerGameBus() {
        try {
            if (REGISTERED) {
                LOG.debug("[FFFeatureToggleTooltips] already registered; skipping");
                return;
            }
            NeoForge.EVENT_BUS.register(FFFeatureToggleTooltips.class);
            REGISTERED = true;
            LOG.debug("[FFFeatureToggleTooltips] Registered on NeoForge EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[FFFeatureToggleTooltips] registerGameBus failed safely", t);
        }
    }

    @SubscribeEvent
    public static void onItemTooltip(@NotNull ItemTooltipEvent event) {
        try {
            if (event.getItemStack() == null || event.getItemStack().isEmpty()) {
                return;
            }
            if (!Services.PLATFORM.hasServerSettingsSynced()) {
                return;
            }

            ItemStack stack = event.getItemStack();
            Item item = stack.getItem();

            boolean show = false;
            Component featureName = null;

            if (item == FFItems.RAVENS_EYE.get() && !Services.PLATFORM.isSuspiciousFeatherEnabledClient()) {
                show = true;
                featureName = Component.translatable("feature.featheredfriend.suspicious_feather");
            } else if (item == FFItems.RAVEN_CHEST.get() && !Services.PLATFORM.isSuspiciousChestEnabledClient()) {
                show = true;
                featureName = Component.translatable("feature.featheredfriend.suspicious_chest");
            } else if (item == FFItems.MAILBOX.get() && !Services.PLATFORM.isMailboxEnabledClient()) {
                show = true;
                featureName = Component.translatable("feature.featheredfriend.mailbox");
            } else if (FFItems.isRavenArmor(stack) && !Services.PLATFORM.isRavenArmorEnabledClient()) {
                show = true;
                featureName = Component.translatable("feature.featheredfriend.raven_armor");
            }

            if (!show || featureName == null) {
                return;
            }

            List<Component> tooltip = event.getToolTip();
            if (tooltip == null) {
                return;
            }

            Component line = Component.translatable("tooltip.featheredfriend.feature_disabled", featureName)
                    .withStyle(ChatFormatting.RED);

            int insertAt = Math.min(1, tooltip.size());
            tooltip.add(insertAt, line);
        } catch (Throwable ignored) {
        }
    }
}

