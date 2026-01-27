package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.menu.ScrollAttachmentProvider;
import org.slf4j.Logger;

/**
 * common/src/main/java/net/z2six/featheredfriend/network/WaxSealPacket.java
 *
 * SINGLE CANONICAL NBT LOCATION:
 *   stack.tag[Constants.MOD_ID].SealedScroll
 * i.e.
 *   tag.featheredfriend.SealedScroll
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
) {
    private static final Logger LOG = LogUtils.getLogger();

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "wax_seal");

    // Per-item payload used by attachment stacks (keep as-is)
    private static final String STACK_CUSTOM_DATA_KEY = "CustomData";

    // ---------------------------------------------------------------------
    // Codec
    // ---------------------------------------------------------------------

    public static void encode(WaxSealPacket p, FriendlyByteBuf buf) {
        try {
            buf.writeVarInt(p.selectedStampSlot());
            buf.writeUtf(p.dateText(), 256);
            buf.writeUtf(p.recipientName(), 256);
            buf.writeUtf(p.recipientUUID(), 256);
            buf.writeUtf(p.recipientText(), 32768);
            buf.writeUtf(p.messageText(), 32768);
            buf.writeUtf(p.signatureText(), 32768);
            buf.writeLong(p.seed());
            buf.writeVarInt(p.slices());
            buf.writeVarInt(p.style());
            buf.writeUtf(p.senderName(), 256);
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] encode failed", t);
        }
    }

    public static WaxSealPacket decode(FriendlyByteBuf buf) {
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
            return new WaxSealPacket(-1, "", "", "", "", "", "", 0L, 0, 0, "");
        }
    }

    // ---------------------------------------------------------------------
    // Server handler
    // ---------------------------------------------------------------------

    public static void handle(WaxSealPacket p, ServerPlayer serverPlayer) {
        try {
            ItemStack removed = removeOneUnsealedScroll(serverPlayer);
            if (removed == null) {
                LOG.warn("[WaxSealPacket] Player {} tried sealing but has no featheredfriend:scroll_unsealed",
                        serverPlayer.getGameProfile().getName());
                return;
            }

            Item sealedItem = resolveItemByPath("scroll_sealed");
            if (sealedItem == null || sealedItem == Items.AIR) {
                LOG.error("[WaxSealPacket] sealed scroll item featheredfriend:scroll_sealed not found; aborting");
                return;
            }

            ItemStack sealed = new ItemStack(sealedItem, 1);

            // Build SealedScroll payload
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

            // Attachments
            try {
                if (serverPlayer.containerMenu instanceof ScrollAttachmentProvider provider) {
                    int slotCount = provider.getAttachmentSlotCount();
                    ListTag attachmentsList = new ListTag();
                    int nonEmptyCount = 0;

                    for (int i = 0; i < slotCount; i++) {
                        ItemStack stack = provider.getAttachmentStack(i);
                        if (stack == null || stack.isEmpty()) continue;

                        CompoundTag stackTag = new CompoundTag();

                        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                        if (itemId == null) {
                            LOG.warn("[WaxSealPacket] Attachment slot {} has item with null registry key; skipping", i);
                            continue;
                        }

                        stackTag.putString("id", itemId.toString());
                        stackTag.putInt("Count", stack.getCount());

                        // Keep per-item CustomData for attachments if present
                        CompoundTag cd = getCustomDataCopy(stack);
                        if (cd != null && !cd.isEmpty()) {
                            stackTag.put("CustomData", cd.copy());
                        }

                        attachmentsList.add(stackTag);
                        nonEmptyCount++;

                        try {
                            provider.clearAttachmentSlot(i);
                        } catch (Throwable tClear) {
                            LOG.error("[WaxSealPacket] Failed to clear attachment slot {}", i, tClear);
                        }
                    }

                    if (nonEmptyCount > 0) {
                        seal.put("Attachments", attachmentsList);
                        try {
                            provider.setSuppressAttachmentRefundOnClose(true);
                        } catch (Throwable tFlag) {
                            LOG.error("[WaxSealPacket] Failed to set suppressAttachmentRefundOnClose on provider {}", provider.getClass().getName(), tFlag);
                        }
                        LOG.debug("[WaxSealPacket] Captured {} attachment stack(s) into sealed scroll for player {}",
                                nonEmptyCount, serverPlayer.getGameProfile().getName());
                    }
                }
            } catch (Throwable tAttach) {
                LOG.error("[WaxSealPacket] Failed while capturing attachments into sealed scroll NBT", tAttach);
            }

            // ✅ SINGLE CANONICAL WRITE
            writeSealedScrollToModCompound(sealed, seal);

            boolean added = serverPlayer.getInventory().add(sealed);
            if (!added) {
                serverPlayer.drop(sealed, false);
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
    // Canonical SealedScroll writer
    // ---------------------------------------------------------------------

    private static void writeSealedScrollToModCompound(ItemStack stack, CompoundTag sealedScroll) {
        try {
            if (stack == null || stack.isEmpty() || sealedScroll == null) return;

            CompoundTag tag = stack.getOrCreateTag();
            CompoundTag ff = tag.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)
                    ? tag.getCompound(Constants.MOD_ID)
                    : new CompoundTag();

            ff.put("SealedScroll", sealedScroll.copy());
            tag.put(Constants.MOD_ID, ff);

            // IMPORTANT: do NOT write anywhere else.
            // No tag.SealedScroll, no tag.CustomData.SealedScroll.

            LOG.debug("[WaxSealPacket] Wrote SealedScroll ONLY to tag.{}.SealedScroll", Constants.MOD_ID);
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] writeSealedScrollToModCompound failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static ItemStack removeOneUnsealedScroll(ServerPlayer player) {
        try {
            Item unsealed = resolveItemByPath("scroll_unsealed");
            if (unsealed == null || unsealed == Items.AIR) {
                LOG.error("[WaxSealPacket] Unsealed scroll item featheredfriend:scroll_unsealed not found in registry");
                return null;
            }

            for (int i = 0; i < player.getInventory().items.size(); i++) {
                ItemStack s = player.getInventory().items.get(i);
                if (!s.isEmpty() && s.getItem() == unsealed) {
                    ItemStack taken = s.copy();
                    taken.setCount(1);
                    s.shrink(1);
                    return taken;
                }
            }
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] removeOneUnsealedScroll failed", t);
        }
        return null;
    }

    private static Item resolveItemByPath(String path) {
        try {
            ResourceLocation id = new ResourceLocation(Constants.MOD_ID, path);
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null) return Items.AIR;
            return item;
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] resolveItemByPath failed for path='{}'", path, t);
            return Items.AIR;
        }
    }

    private static String safeString(String input, int maxLen) {
        if (input == null) return "";
        if (input.length() <= maxLen) return input;
        return input.substring(0, Math.max(0, maxLen));
    }

    private static CompoundTag getCustomDataCopy(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            if (tag == null) return new CompoundTag();
            if (!tag.contains(STACK_CUSTOM_DATA_KEY, Tag.TAG_COMPOUND)) return new CompoundTag();
            CompoundTag cd = tag.getCompound(STACK_CUSTOM_DATA_KEY);
            return cd == null ? new CompoundTag() : cd.copy();
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] getCustomDataCopy failed", t);
            return new CompoundTag();
        }
    }
}
