package net.z2six.featheredfriend.compat.jade;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.jetbrains.annotations.NotNull;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

@WailaPlugin
public final class FeatheredFriendJadePlugin implements IWailaPlugin {

    private static final ResourceLocation RAVEN_STATS_UID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "raven_stats");

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(RavenStatsProvider.INSTANCE, RavenEntity.class);
    }

    private static final class RavenStatsProvider implements IEntityComponentProvider {

        private static final RavenStatsProvider INSTANCE = new RavenStatsProvider();

        @Override
        public @NotNull ResourceLocation getUid() {
            return RAVEN_STATS_UID;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!(accessor.getEntity() instanceof RavenEntity raven)) {
                return;
            }

            int dodgeChancePercent = Mth.clamp(Math.round(raven.getEffectiveDodgeChanceFraction() * 100.0F), 0, 100);
            int detectionRadius = Math.max(0, Mth.floor(raven.getEffectiveThreatDetectionRadiusBlocks()));
            int payloadSafetyPercent = Mth.clamp(
                    Math.round((1.0F - raven.getEffectivePayloadDropOnLandedHitChance()) * 100.0F),
                    0,
                    100
            );

            tooltip.add(Component.translatable("jade.featheredfriend.raven.stats_header").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.max_hits",
                    raven.getEffectiveMaxHits()
            ));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.dodge_chance",
                    dodgeChancePercent
            ));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.detection_radius",
                    detectionRadius
            ));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.payload_safety",
                    payloadSafetyPercent
            ));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.health_regen",
                    raven.getEffectiveHealthRegenPerMinute()
            ));
        }
    }
}
