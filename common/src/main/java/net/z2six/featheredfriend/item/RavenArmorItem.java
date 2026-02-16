package net.z2six.featheredfriend.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Raven armor item that displays courier stat lines in the tooltip.
 */
public class RavenArmorItem extends Item {

    private static final int BASE_DODGE_CHANCE_PERCENT = 25;

    private final String descriptionKey;
    private final RavenArmorStats stats;

    public RavenArmorItem(@NotNull Properties properties,
                          @NotNull String descriptionKey,
                          @NotNull RavenArmorStats stats) {
        super(properties);
        this.descriptionKey = descriptionKey;
        this.stats = stats;
    }

    public @NotNull RavenArmorStats getStats() {
        return stats;
    }

    private static @NotNull Component withStatHelp(@NotNull Component statLine, @NotNull String helpKey) {
        return Component.empty()
                .append(statLine)
                .append(Component.literal(" "))
                .append(Component.translatable(helpKey).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack,
                                @NotNull TooltipContext context,
                                @NotNull List<Component> tooltip,
                                @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);

        tooltip.add(Component.translatable(descriptionKey).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.featheredfriend.raven_armor.stats_header").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(withStatHelp(Component.translatable(
                "tooltip.featheredfriend.raven_armor.stat.max_hits",
                stats.maxHits()
        ).withStyle(ChatFormatting.AQUA), "tooltip.featheredfriend.raven_armor.stat.help.max_hits"));
        int dodgeDelta = stats.dodgeChancePercent() - BASE_DODGE_CHANCE_PERCENT;
        String signedDodgeDelta = String.format("%+d%%", dodgeDelta);
        ChatFormatting dodgeColor = (dodgeDelta > 0)
                ? ChatFormatting.GREEN
                : (dodgeDelta < 0 ? ChatFormatting.RED : ChatFormatting.GRAY);
        tooltip.add(withStatHelp(Component.translatable(
                "tooltip.featheredfriend.raven_armor.stat.dodge_chance_delta",
                signedDodgeDelta
        ).withStyle(dodgeColor), "tooltip.featheredfriend.raven_armor.stat.help.dodge_chance"));
        tooltip.add(withStatHelp(Component.translatable(
                "tooltip.featheredfriend.raven_armor.stat.detection_radius",
                stats.detectionRadius()
        ).withStyle(ChatFormatting.AQUA), "tooltip.featheredfriend.raven_armor.stat.help.detection_radius"));
        tooltip.add(withStatHelp(Component.translatable(
                "tooltip.featheredfriend.raven_armor.stat.payload_safety",
                stats.payloadSafetyPercent()
        ).withStyle(ChatFormatting.AQUA), "tooltip.featheredfriend.raven_armor.stat.help.payload_safety"));
        tooltip.add(withStatHelp(Component.translatable(
                "tooltip.featheredfriend.raven_armor.stat.health_regen",
                stats.healthRegenPerMinute()
        ).withStyle(ChatFormatting.AQUA), "tooltip.featheredfriend.raven_armor.stat.help.health_regen"));
    }
}
