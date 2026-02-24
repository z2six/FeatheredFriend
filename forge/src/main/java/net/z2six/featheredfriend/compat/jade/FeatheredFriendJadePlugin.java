package net.z2six.featheredfriend.compat.jade;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.MailboxBlock;
import net.z2six.featheredfriend.block.entity.MailboxBlockEntity;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.jetbrains.annotations.NotNull;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.Identifiers;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

import java.util.ArrayList;
import java.util.List;

@WailaPlugin
public final class FeatheredFriendJadePlugin implements IWailaPlugin {

    private static final ResourceLocation RAVEN_STATS_UID =
            new ResourceLocation(Constants.MOD_ID, "raven_stats");
    private static final ResourceLocation MAILBOX_OWNER_UID =
            new ResourceLocation(Constants.MOD_ID, "mailbox_owner");
    private static final ResourceLocation FEATHER_FILLED_SPRITE =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/sprites/jade/jadefeather.png");
    private static final ResourceLocation FEATHER_OUTLINE_SPRITE =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/sprites/jade/jadefeatheroutline.png");
    private static final int FEATHER_RENDER_SIZE = 16;
    private static final int MAX_FEATHERS_SHOWN = 20;

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(RavenStatsProvider.INSTANCE, RavenEntity.class);
        registration.registerBlockComponent(MailboxOwnerProvider.INSTANCE, MailboxBlock.class);
    }

    private static final class MailboxOwnerProvider implements IBlockComponentProvider {

        private static final MailboxOwnerProvider INSTANCE = new MailboxOwnerProvider();

        @Override
        public @NotNull ResourceLocation getUid() {
            return MAILBOX_OWNER_UID;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof MailboxBlockEntity mailbox)) {
                return;
            }

            String ownerName = "";
            try {
                ownerName = mailbox.getOwnerName();
            } catch (Throwable ignored) {
            }

            Component ownerValue;
            if (ownerName == null || ownerName.isBlank()) {
                ownerValue = Component.translatable("jade.featheredfriend.unknown").withStyle(ChatFormatting.GRAY);
            } else {
                ownerValue = Component.literal(ownerName).withStyle(ChatFormatting.YELLOW);
            }

            tooltip.add(Component.translatable("jade.featheredfriend.mailbox.owner", ownerValue)
                    .withStyle(ChatFormatting.GRAY));
        }
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

            replaceHeartsWithFeathers(tooltip, raven);

            boolean showDetailedStats = false;
            try {
                Minecraft mc = Minecraft.getInstance();
                showDetailedStats = mc.player != null && mc.player.isCrouching();
            } catch (Throwable ignored) {
            }

            if (!showDetailedStats) {
                tooltip.add(Component.translatable("jade.featheredfriend.raven.sneak_for_more")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
                return;
            }

            int dodgeChancePercent = Mth.clamp(Math.round(raven.getEffectiveDodgeChanceFraction() * 100.0F), 0, 100);
            int detectionRadius = Math.max(0, Mth.floor(raven.getEffectiveThreatDetectionRadiusBlocks()));
            int payloadSafetyPercent = Mth.clamp(
                    Math.round((1.0F - raven.getEffectivePayloadDropOnLandedHitChance()) * 100.0F),
                    0,
                    100
            );
            int healthRegenPerMinute = Math.max(0, raven.getEffectiveHealthRegenPerMinute());

            tooltip.add(Component.translatable("jade.featheredfriend.raven.stats_header").withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.max_hits",
                    styledInt(raven.getEffectiveMaxHits(), ChatFormatting.YELLOW)
            ).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.dodge_chance",
                    styledInt(dodgeChancePercent, tieredPercentColor(dodgeChancePercent))
            ).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.detection_radius",
                    styledInt(detectionRadius, ChatFormatting.AQUA)
            ).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.payload_safety",
                    styledInt(payloadSafetyPercent, tieredPercentColor(payloadSafetyPercent))
            ).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable(
                    "jade.featheredfriend.raven.health_regen",
                    styledInt(healthRegenPerMinute, tieredRegenColor(healthRegenPerMinute))
            ).withStyle(ChatFormatting.GRAY));
        }

        private static void replaceHeartsWithFeathers(@NotNull ITooltip tooltip, @NotNull RavenEntity raven) {
            try {
                List<IElement> hitsTextRow = buildHitsTextRow(raven);
                List<IElement> featherRow = buildFeatherIconRow(raven);
                try {
                    tooltip.remove(Identifiers.MC_ENTITY_HEALTH);
                } catch (Throwable ignored) {
                }
                tooltip.add(0, featherRow);
                tooltip.add(0, hitsTextRow);
            } catch (Throwable ignored) {
            }
        }

        private static @NotNull List<IElement> buildHitsTextRow(@NotNull RavenEntity raven) {
            IElementHelper helper = IElementHelper.get();
            int maxHits = Math.max(1, raven.getEffectiveMaxHits());
            int currentHits = Mth.clamp((int) Math.ceil(Math.max(0.0F, raven.getHealth())), 0, maxHits);

            List<IElement> row = new ArrayList<>();
            row.add(helper.text(Component.translatable(
                    "jade.featheredfriend.raven.hits_left_value",
                    styledInt(currentHits, tieredRatioColor(currentHits, maxHits)),
                    styledInt(maxHits, ChatFormatting.YELLOW)
            ).withStyle(ChatFormatting.GRAY)));
            return row;
        }

        private static @NotNull List<IElement> buildFeatherIconRow(@NotNull RavenEntity raven) {
            IElementHelper helper = IElementHelper.get();
            int maxHits = Math.max(1, raven.getEffectiveMaxHits());
            int currentHits = Mth.clamp((int) Math.ceil(Math.max(0.0F, raven.getHealth())), 0, maxHits);
            int shown = Math.min(maxHits, MAX_FEATHERS_SHOWN);

            List<IElement> row = new ArrayList<>();
            row.add(helper.spacer(1, 1));

            for (int i = 0; i < shown; i++) {
                boolean active = i < currentHits;
                row.add(new FeatherElement(
                        active ? FEATHER_FILLED_SPRITE : FEATHER_OUTLINE_SPRITE,
                        active ? 1.0F : 1.0F
                ));
                if (i < shown - 1) {
                    row.add(helper.spacer(1, 1));
                }
            }
            if (maxHits > shown) {
                row.add(helper.spacer(4, 1));
                row.add(helper.text(Component.literal("...")));
            }
            return row;
        }

        private static @NotNull Component styledInt(int value, @NotNull ChatFormatting color) {
            return Component.literal(Integer.toString(value)).withStyle(color);
        }

        private static @NotNull ChatFormatting tieredPercentColor(int percent) {
            if (percent >= 75) {
                return ChatFormatting.GREEN;
            }
            if (percent >= 45) {
                return ChatFormatting.YELLOW;
            }
            return ChatFormatting.RED;
        }

        private static @NotNull ChatFormatting tieredRegenColor(int regenPerMinute) {
            if (regenPerMinute >= 4) {
                return ChatFormatting.GREEN;
            }
            if (regenPerMinute >= 2) {
                return ChatFormatting.YELLOW;
            }
            return ChatFormatting.RED;
        }

        private static @NotNull ChatFormatting tieredRatioColor(int current, int max) {
            if (max <= 0) {
                return ChatFormatting.RED;
            }
            float ratio = (float) current / (float) max;
            if (ratio >= 0.66F) {
                return ChatFormatting.GREEN;
            }
            if (ratio >= 0.33F) {
                return ChatFormatting.YELLOW;
            }
            return ChatFormatting.RED;
        }
    }

    private static final class FeatherElement extends Element {

        private final ResourceLocation sprite;
        private final float alpha;

        private FeatherElement(ResourceLocation sprite, float alpha) {
            this.sprite = sprite;
            this.alpha = Mth.clamp(alpha, 0.0F, 1.0F);
        }

        @Override
        public @NotNull Vec2 getSize() {
            return new Vec2(FEATHER_RENDER_SIZE, FEATHER_RENDER_SIZE);
        }

        @Override
        public void render(@NotNull GuiGraphics guiGraphics, float x, float y, float maxX, float maxY) {
            int renderX = Math.round(x);
            int renderY = Math.round(y);
            guiGraphics.setColor(1.0F, 1.0F, 1.0F, this.alpha);
            guiGraphics.blit(this.sprite, renderX, renderY, 0, 0, FEATHER_RENDER_SIZE, FEATHER_RENDER_SIZE, FEATHER_RENDER_SIZE, FEATHER_RENDER_SIZE);
            guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
