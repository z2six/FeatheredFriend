// common/src/main/java/net/z2six/featheredfriend/network/WaxSealPacket.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.neoforge.menu.ScrollSealingMenu;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * // common/src/main/java/net/z2six/featheredfriend/network/WaxSealPacket.java
 *
 * Handles the "wax seal" action when the player clicks inside the wax area.
 */
public record WaxSealPacket(
        int selectedStampSlot,
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
        buf.writeVarInt(p.selectedStampSlot);
        buf.writeUtf(p.recipientName, 256);
        buf.writeUtf(p.recipientUUID, 256);
        buf.writeUtf(p.recipientText, 32768);
        buf.writeUtf(p.messageText, 32768);
        buf.writeUtf(p.signatureText, 32768);
        buf.writeLong(p.seed);
        buf.writeVarInt(p.slices);
        buf.writeVarInt(p.style);
        buf.writeUtf(p.senderName, 256);
    }

    private static @NotNull WaxSealPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
        int slot = buf.readVarInt();
        String recName = buf.readUtf(256);
        String recUUID = buf.readUtf(256);
        String recText = buf.readUtf(32768);
        String msg = buf.readUtf(32768);
        String sig = buf.readUtf(32768);
        long sd = buf.readLong();
        int sl = buf.readVarInt();
        int st = buf.readVarInt();
        String sender = buf.readUtf(256);
        return new WaxSealPacket(slot, recName, recUUID, recText, msg, sig, sd, sl, st, sender);
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
            // 1) Remove one unsealed scroll
            ItemStack removed = removeOneUnsealedScroll(serverPlayer);
            if (removed == null) {
                LOG.warn("[WaxSealPacket] Player {} tried sealing but has no scroll_unsealed",
                        serverPlayer.getGameProfile().getName());
                return;
            }

            // 2) Resolve sealed scroll item
            Item sealedItem = resolveItemByPath("scroll_sealed");
            if (sealedItem == null || sealedItem == Items.AIR) {
                LOG.error("[WaxSealPacket] sealed scroll item not found; aborting");
                return;
            }

            ItemStack sealed = new ItemStack(sealedItem, 1);

            // -----------------------------------------------------------------
            // Build NBT
            // -----------------------------------------------------------------
            CompoundTag root = new CompoundTag();
            CompoundTag seal = new CompoundTag();

            seal.putString("RecipientName", safeString(p.recipientName(), 256));
            seal.putString("RecipientUUID", safeString(p.recipientUUID(), 256));
            seal.putString("RecipientText", safeString(p.recipientText(), 32760));
            seal.putString("MessageText", safeString(p.messageText(), 32760));
            seal.putString("SignatureText", safeString(p.signatureText(), 32760));
            seal.putString("SenderName", safeString(p.senderName(), 256));
            seal.putLong("Seed", p.seed());
            seal.putInt("Slices", p.slices());
            seal.putInt("Style", p.style());

            // -----------------------------------------------------------------
            // NEW: Attachment collection
            // -----------------------------------------------------------------
            try {
                AbstractContainerMenu currentMenu = serverPlayer.containerMenu;
                if (currentMenu instanceof ScrollSealingMenu sm) {

                    ListTag attachmentsList = new ListTag();

                    for (int slot = ScrollSealingMenu.ATTACHMENT_START;
                         slot <= ScrollSealingMenu.ATTACHMENT_END;
                         slot++) {

                        ItemStack stack = sm.getAttachmentContainer().getItem(slot);

                        if (!stack.isEmpty()) {
                            CompoundTag att = new CompoundTag();
                            att.putString("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                            att.putByte("Count", (byte) stack.getCount());
                            attachmentsList.add(att);

                            // Wipe the slot so removed() does NOT refund
                            sm.getAttachmentContainer().setItem(slot, ItemStack.EMPTY);
                        }
                    }

                    if (!attachmentsList.isEmpty()) {
                        seal.put("Attachments", attachmentsList);
                        LOG.debug("[WaxSealPacket] Stored {} attachment items in sealed scroll",
                                attachmentsList.size());
                    }
                } else {
                    LOG.debug("[WaxSealPacket] No ScrollSealingMenu active; no attachments collected");
                }
            } catch (Throwable t) {
                LOG.error("[WaxSealPacket] Failed to collect attachments", t);
            }

            root.put("SealedScroll", seal);

            // Apply CustomData
            try {
                sealed.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
            } catch (Throwable tSet) {
                LOG.error("[WaxSealPacket] Failed to attach CustomData to sealed scroll", tSet);
            }

            // -----------------------------------------------------------------
            // 3) Deliver sealed scroll
            // -----------------------------------------------------------------
            boolean added = false;
            try {
                added = serverPlayer.getInventory().add(sealed);
            } catch (Throwable tAdd) {
                LOG.error("[WaxSealPacket] Error while adding sealed scroll to inventory", tAdd);
            }

            if (!added) {
                try {
                    serverPlayer.drop(sealed, false);
                } catch (Throwable tDrop) {
                    LOG.error("[WaxSealPacket] Failed to drop sealed scroll at player", tDrop);
                }
            }

            LOG.info("[WaxSealPacket] Delivered sealed scroll to {} (attachments stored)",
                    serverPlayer.getGameProfile().getName());

        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] handle failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static ItemStack removeOneUnsealedScroll(@NotNull ServerPlayer player) {
        try {
            Item unsealed = resolveItemByPath("scroll_unsealed");
            if (unsealed == null || unsealed == Items.AIR) {
                LOG.error("[WaxSealPacket] unsealed scroll item not found");
                return null;
            }

            for (int i = 0; i < player.getInventory().items.size(); i++) {
                ItemStack s = player.getInventory().items.get(i);
                if (!s.isEmpty() && s.getItem() == unsealed) {
                    ItemStack taken = s.copyWithCount(1);
                    s.shrink(1);
                    return taken;
                }
            }
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] removeOneUnsealedScroll failed", t);
        }
        return null;
    }

    private static Item resolveItemByPath(@NotNull String path) {
        try {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
            Item item = BuiltInRegistries.ITEM.get(id);
            return item != null ? item : Items.AIR;
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] resolveItemByPath failed", t);
            return Items.AIR;
        }
    }

    private static String safeString(String input, int maxLen) {
        if (input == null) return "";
        if (input.length() <= maxLen) return input;
        try {
            return input.substring(0, maxLen);
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] safeString failed", t);
            return "";
        }
    }
}
