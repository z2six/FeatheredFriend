// MainFile: common/src/main/java/net/z2six/featheredfriend/network/WaxSealPacket.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.menu.ScrollAttachmentProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * // common/src/main/java/net/z2six/featheredfriend/network/WaxSealPacket.java
 *
 * Handles the "wax seal" action when the player clicks inside the wax area
 * on the ScrollSealingScreen with an etched Seal Stamp selected.
 *
 * Server-side behaviour:
 *  1) Remove 1x featheredfriend:scroll_unsealed from the player's inventory.
 *  2) Create 1x featheredfriend:scroll_sealed with all scroll NBT data:
 *     - DateText
 *     - RecipientName
 *     - RecipientUUID
 *     - RecipientText
 *     - MessageText
 *     - SignatureText
 *     - SenderName (from the Seal Stamp, not anything else)
 *     - Seed
 *     - Slices
 *     - Style
 *     - Attachments (if any) taken from the current ScrollAttachmentProvider
 *       container (ScrollSealingMenu attachment bar).
 *  3) Try to add the sealed scroll to the inventory; if full, drop at player.
 *
 * Attachments:
 *  - If the player's current containerMenu implements ScrollAttachmentProvider,
 *    all non-empty attachment slots are serialized into a "Attachments" ListTag
 *    in the SealedScroll compound.
 *  - The provider's slots are cleared and suppressAttachmentRefundOnClose(true)
 *    is set so the menu does not refund them again on close.
 */
public record WaxSealPacket(
        int selectedStampSlot,
        String dateText,
        String recipientName,
        String recipientUUID,
        String recipientText,
        String messageText,
        String signatureText,
        long seed,
        int slices,
        int style,
        String senderName
) implements CustomPacketPayload {

    private static final Logger LOG = LogUtils.getLogger();

    public static final Type<WaxSealPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "wax_seal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WaxSealPacket> STREAM_CODEC =
            StreamCodec.of(WaxSealPacket::encode, WaxSealPacket::decode);

    // ---------------------------------------------------------------------
    // Codec
    // ---------------------------------------------------------------------

    private static void encode(@NotNull RegistryFriendlyByteBuf buf, @NotNull WaxSealPacket p) {
        try {
            buf.writeVarInt(p.selectedStampSlot);
            buf.writeUtf(p.dateText, 256);
            buf.writeUtf(p.recipientName, 256);
            buf.writeUtf(p.recipientUUID, 256);
            buf.writeUtf(p.recipientText, 32768);
            buf.writeUtf(p.messageText, 32768);
            buf.writeUtf(p.signatureText, 32768);
            buf.writeLong(p.seed);
            buf.writeVarInt(p.slices);
            buf.writeVarInt(p.style);
            buf.writeUtf(p.senderName, 256);
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] encode failed", t);
        }
    }

    private static @NotNull WaxSealPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
        try {
            int slot = buf.readVarInt();
            String dateText = buf.readUtf(256);
            String recName = buf.readUtf(256);
            String recUUID = buf.readUtf(256);
            String recText = buf.readUtf(32768);
            String msg = buf.readUtf(32768);
            String sig = buf.readUtf(32768);
            long sd = buf.readLong();
            int sl = buf.readVarInt();
            int st = buf.readVarInt();
            String sender = buf.readUtf(256);
            return new WaxSealPacket(slot, dateText, recName, recUUID, recText, msg, sig, sd, sl, st, sender);
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] decode failed", t);
            // Fallback to some safe defaults if decoding explodes
            return new WaxSealPacket(
                    -1,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    0L,
                    0,
                    0,
                    ""
            );
        }
    }

    @Override
    public @NotNull Type<WaxSealPacket> type() {
        return TYPE;
    }

    // ---------------------------------------------------------------------
    // Server handler
    // ---------------------------------------------------------------------

    public static void handle(@NotNull WaxSealPacket p, @NotNull ServerPlayer serverPlayer) {
        try {
            // 1) Resolve sealed scroll item by ID (no hard dependency on registry wrapper)
            Item sealedItem = resolveItemByPath("scroll_sealed");
            if (sealedItem == null || sealedItem == Items.AIR) {
                LOG.error("[WaxSealPacket] sealed scroll item featheredfriend:scroll_sealed not found; aborting");
                return;
            }

            // 2) Snapshot attachments before mutating anything.
            ScrollAttachmentProvider provider = (serverPlayer.containerMenu instanceof ScrollAttachmentProvider pvd)
                    ? pvd
                    : null;
            List<Integer> attachmentSlotIndexes = new ArrayList<>();
            ListTag attachmentsList = new ListTag();

            if (provider != null) {
                int slotCount = provider.getAttachmentSlotCount();
                for (int i = 0; i < slotCount; i++) {
                    ItemStack stack = provider.getAttachmentStack(i);
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }

                    CompoundTag stackTag = serializeAttachmentStack(stack, i);
                    if (stackTag == null || stackTag.isEmpty()) {
                        continue;
                    }

                    attachmentsList.add(stackTag);
                    attachmentSlotIndexes.add(i);
                }
            }

            boolean hasAttachments = !attachmentSlotIndexes.isEmpty();
            if (hasAttachments && !hasEnderPearl(serverPlayer)) {
                notifyMissingEnderPearl(serverPlayer);
                LOG.warn("[WaxSealPacket] {} attempted sealing with attachments but has no Ender Pearl",
                        serverPlayer.getGameProfile().getName());
                return;
            }

            // 3) Remove one unsealed scroll.
            ItemStack removedUnsealed = removeOneUnsealedScroll(serverPlayer);
            if (removedUnsealed == null) {
                LOG.warn("[WaxSealPacket] Player {} tried sealing but has no featheredfriend:scroll_unsealed",
                        serverPlayer.getGameProfile().getName());
                return;
            }

            // 4) Reserve one Ender Pearl only if attachments are present.
            ItemStack reservedPearl = ItemStack.EMPTY;
            if (hasAttachments) {
                ItemStack removedPearl = removeOneEnderPearl(serverPlayer);
                if (removedPearl == null) {
                    refundStack(serverPlayer, removedUnsealed);
                    notifyMissingEnderPearl(serverPlayer);
                    LOG.warn("[WaxSealPacket] {} failed to reserve Ender Pearl while sealing attachments; refunded unsealed scroll",
                            serverPlayer.getGameProfile().getName());
                    return;
                }
                reservedPearl = removedPearl;
            }

            // 5) Build sealed scroll payload.
            ItemStack sealed = new ItemStack(sealedItem, 1);
            CompoundTag root = new CompoundTag();
            CompoundTag seal = new CompoundTag();

            seal.putString("DateText", safeString(p.dateText(), 256));
            seal.putString("RecipientName", safeString(p.recipientName(), 256));
            seal.putString("RecipientUUID", safeString(p.recipientUUID(), 256));
            seal.putString("RecipientText", safeString(p.recipientText(), 32760));
            seal.putString("MessageText", safeString(p.messageText(), 32760));
            seal.putString("SignatureText", safeString(p.signatureText(), 32760));
            seal.putString("SenderName", safeString(p.senderName(), 256));
            seal.putLong("Seed", p.seed());
            seal.putInt("Slices", p.slices());
            seal.putInt("Style", p.style());

            if (hasAttachments) {
                seal.put("Attachments", attachmentsList);
            }

            root.put("SealedScroll", seal);

            try {
                // Correct 1.21+ API: set CUSTOM_DATA via DataComponents
                sealed.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
            } catch (Throwable tSet) {
                LOG.error("[WaxSealPacket] Failed to attach CustomData to sealed scroll", tSet);
            }

            // 6) Add/deliver sealed scroll.
            boolean added = false;
            boolean delivered = false;
            try {
                added = serverPlayer.getInventory().add(sealed);
                delivered = added;
            } catch (Throwable tAdd) {
                LOG.error("[WaxSealPacket] Error while adding sealed scroll to inventory", tAdd);
            }

            if (!added) {
                try {
                    delivered = (serverPlayer.drop(sealed, false) != null);
                } catch (Throwable tDrop) {
                    LOG.error("[WaxSealPacket] Failed to drop sealed scroll at player", tDrop);
                }
            }

            if (!delivered) {
                refundStack(serverPlayer, removedUnsealed);
                if (!reservedPearl.isEmpty()) {
                    refundStack(serverPlayer, reservedPearl);
                }
                LOG.error("[WaxSealPacket] Failed to deliver sealed scroll; refunded inputs for player {}",
                        serverPlayer.getGameProfile().getName());
                return;
            }

            // 7) Finalize attachment side-effects only after successful sealing.
            if (hasAttachments && provider != null) {
                for (Integer slotIndex : attachmentSlotIndexes) {
                    try {
                        provider.clearAttachmentSlot(slotIndex);
                    } catch (Throwable tClear) {
                        LOG.error("[WaxSealPacket] Failed to clear attachment slot {}", slotIndex, tClear);
                    }
                }
                try {
                    provider.setSuppressAttachmentRefundOnClose(true);
                } catch (Throwable tFlag) {
                    LOG.error("[WaxSealPacket] Failed to set suppressAttachmentRefundOnClose on provider {}",
                            provider.getClass().getName(), tFlag);
                }
            }

            LOG.debug("[WaxSealPacket] Delivered sealed scroll to {} (sender='{}' seed={} slices={} style={} date='{}')",
                    serverPlayer.getGameProfile().getName(),
                    p.senderName(),
                    p.seed(),
                    p.slices(),
                    p.style(),
                    p.dateText());
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] handle failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static CompoundTag serializeAttachmentStack(@NotNull ItemStack stack, int index) {
        CompoundTag stackTag = new CompoundTag();
        try {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) {
                LOG.warn("[WaxSealPacket] Attachment slot {} has item with null registry key; skipping", index);
                return null;
            }

            stackTag.putString("id", itemId.toString());
            stackTag.putInt("Count", stack.getCount());

            try {
                CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
                if (cd != null) {
                    CompoundTag customDataTag = cd.copyTag();
                    if (customDataTag != null && !customDataTag.isEmpty()) {
                        stackTag.put("CustomData", customDataTag);
                    }
                }
            } catch (Throwable tCd) {
                LOG.error("[WaxSealPacket] Failed to copy CustomData for attachment slot {}", index, tCd);
            }

            if (stackTag.isEmpty()) {
                LOG.warn("[WaxSealPacket] Attachment stackTag ended up empty for slot {}; skipping", index);
                return null;
            }

            LOG.debug(
                    "[WaxSealPacket] Captured attachment slot {} -> id='{}' Count={} hasCustomData={}",
                    index,
                    itemId,
                    stack.getCount(),
                    stackTag.contains("CustomData")
            );
            return stackTag;
        } catch (Throwable tSave) {
            LOG.error("[WaxSealPacket] Failed to serialize attachment stack at index {}", index, tSave);
            return null;
        }
    }

    private static boolean hasEnderPearl(@NotNull ServerPlayer player) {
        try {
            for (ItemStack stack : player.getInventory().items) {
                if (stack != null && !stack.isEmpty() && stack.is(Items.ENDER_PEARL)) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().offhand) {
                if (stack != null && !stack.isEmpty() && stack.is(Items.ENDER_PEARL)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] hasEnderPearl failed", t);
        }
        return false;
    }

    private static ItemStack removeOneEnderPearl(@NotNull ServerPlayer player) {
        return removeOneItem(player, Items.ENDER_PEARL);
    }

    /**
     * Attempts to remove exactly one featheredfriend:scroll_unsealed from
     * the player's main inventory. Returns a copy of the removed stack (count=1),
     * or null if none were found.
     */
    private static ItemStack removeOneUnsealedScroll(@NotNull ServerPlayer player) {
        try {
            Item unsealed = resolveItemByPath("scroll_unsealed");
            if (unsealed == null || unsealed == Items.AIR) {
                LOG.error("[WaxSealPacket] Unsealed scroll item featheredfriend:scroll_unsealed not found in registry");
                return null;
            }
            return removeOneItem(player, unsealed);
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] removeOneUnsealedScroll failed", t);
        }
        return null;
    }

    private static ItemStack removeOneItem(@NotNull ServerPlayer player, @NotNull Item item) {
        try {
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                ItemStack s = player.getInventory().items.get(i);
                if (!s.isEmpty() && s.is(item)) {
                    ItemStack taken = s.copyWithCount(1);
                    s.shrink(1);
                    return taken;
                }
            }

            for (int i = 0; i < player.getInventory().offhand.size(); i++) {
                ItemStack s = player.getInventory().offhand.get(i);
                if (!s.isEmpty() && s.is(item)) {
                    ItemStack taken = s.copyWithCount(1);
                    s.shrink(1);
                    return taken;
                }
            }
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] removeOneItem failed for {}",
                    BuiltInRegistries.ITEM.getKey(item), t);
        }
        return null;
    }

    private static void refundStack(@NotNull ServerPlayer player, @NotNull ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        try {
            ItemStack toRefund = stack.copy();
            if (!player.getInventory().add(toRefund)) {
                player.drop(toRefund, false);
            }
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] Failed to refund stack {}", stack, t);
        }
    }

    private static void notifyMissingEnderPearl(@NotNull ServerPlayer player) {
        try {
            player.displayClientMessage(
                    Component.translatable("message.featheredfriend.scroll_sealing.attachments.requires_ender_pearl"),
                    true
            );
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] notifyMissingEnderPearl failed", t);
        }
    }

    /**
     * Resolve an item from the built-in item registry by path within this mod's namespace.
     * Returns null or Items.AIR if the item does not exist.
     */
    private static Item resolveItemByPath(@NotNull String path) {
        try {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null) {
                LOG.error("[WaxSealPacket] resolveItemByPath: item {} is null", id);
                return Items.AIR;
            }
            if (item == Items.AIR) {
                LOG.warn("[WaxSealPacket] resolveItemByPath: item {} returned as AIR", id);
            }
            return item;
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] resolveItemByPath failed for path='{}'", path, t);
            return Items.AIR;
        }
    }

    /**
     * Defensive truncation for strings before stuffing them into NBT.
     */
    private static String safeString(String input, int maxLen) {
        if (input == null) {
            return "";
        }
        if (input.length() <= maxLen) {
            return input;
        }
        try {
            return input.substring(0, Math.max(0, maxLen));
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] safeString substring failed (len={} maxLen={})", input.length(), maxLen, t);
            return "";
        }
    }
}
