// MainFile: common/src/main/java/net/z2six/featheredfriend/network/WaxSealPacket.java
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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * // common/src/main/java/net/z2six/featheredfriend/network/WaxSealPacket.java
 *
 * Forge 1.20.1 note:
 * - Uses FriendlyByteBuf for encode/decode.
 * - Former DataComponents.CUSTOM_DATA payload is stored under ItemStack tag sub-compound "CustomData".
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

    // Where we store the former "minecraft:custom_data" payload in 1.20.1
    private static final String STACK_CUSTOM_DATA_KEY = "CustomData";

    // ---------------------------------------------------------------------
    // Codec
    // ---------------------------------------------------------------------

    public static void encode(@NotNull FriendlyByteBuf buf, @NotNull WaxSealPacket p) {
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

    public static @NotNull WaxSealPacket decode(@NotNull FriendlyByteBuf buf) {
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

    // ---------------------------------------------------------------------
    // Server handler
    // ---------------------------------------------------------------------

    public static void handle(@NotNull WaxSealPacket p, @NotNull ServerPlayer serverPlayer) {
        try {
            // 1) Remove one unsealed scroll
            ItemStack removed = removeOneUnsealedScroll(serverPlayer);
            if (removed == null) {
                LOG.warn("[WaxSealPacket] Player {} tried sealing but has no featheredfriend:scroll_unsealed",
                        serverPlayer.getGameProfile().getName());
                return;
            }

            // 2) Resolve sealed scroll item by ID (no hard dependency on registry wrapper)
            Item sealedItem = resolveItemByPath("scroll_sealed");
            if (sealedItem == null || sealedItem == Items.AIR) {
                LOG.error("[WaxSealPacket] sealed scroll item featheredfriend:scroll_sealed not found; aborting");
                return;
            }

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

            // -----------------------------------------------------------------
            // Attachments: capture from current container if supported
            // -----------------------------------------------------------------
            try {
                if (serverPlayer.containerMenu instanceof ScrollAttachmentProvider provider) {
                    int slotCount = provider.getAttachmentSlotCount();
                    ListTag attachmentsList = new ListTag();
                    int nonEmptyCount = 0;

                    for (int i = 0; i < slotCount; i++) {
                        ItemStack stack = provider.getAttachmentStack(i);
                        if (stack == null || stack.isEmpty()) {
                            continue;
                        }

                        CompoundTag stackTag = new CompoundTag();
                        try {
                            // Explicit minimal encoding instead of ItemStack#save(...),
                            // because in 1.21+ / NeoForge that was giving us empty compounds.
                            // We store:
                            //   - "id": full item ID (e.g. "minecraft:oak_log")
                            //   - "Count": stack size
                            //   - "CustomData": copy of our custom payload (if present)
                            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                            if (itemId == null) {
                                LOG.warn("[WaxSealPacket] Attachment slot {} has item with null registry key; skipping", i);
                                continue;
                            }

                            stackTag.putString("id", itemId.toString());
                            stackTag.putInt("Count", stack.getCount());

                            try {
                                CompoundTag cd = getCustomDataCopy(stack);
                                if (cd != null && !cd.isEmpty()) {
                                    stackTag.put("CustomData", cd.copy());
                                }
                            } catch (Throwable tCd) {
                                LOG.error("[WaxSealPacket] Failed to copy CustomData for attachment slot {}", i, tCd);
                            }

                            if (stackTag.isEmpty()) {
                                // Shouldn't normally happen, but avoid adding pointless {}
                                LOG.warn("[WaxSealPacket] Attachment stackTag ended up empty for slot {}; skipping", i);
                                continue;
                            }

                            attachmentsList.add(stackTag);
                            nonEmptyCount++;

                            LOG.debug(
                                    "[WaxSealPacket] Captured attachment slot {} -> id='{}' Count={} hasCustomData={}",
                                    i,
                                    itemId,
                                    stack.getCount(),
                                    stackTag.contains("CustomData")
                            );

                            try {
                                provider.clearAttachmentSlot(i);
                            } catch (Throwable tClear) {
                                LOG.error("[WaxSealPacket] Failed to clear attachment slot {}", i, tClear);
                            }
                        } catch (Throwable tSave) {
                            LOG.error("[WaxSealPacket] Failed to serialize attachment stack at index {}", i, tSave);
                        }
                    }

                    if (nonEmptyCount > 0) {
                        seal.put("Attachments", attachmentsList);
                        try {
                            provider.setSuppressAttachmentRefundOnClose(true);
                        } catch (Throwable tFlag) {
                            LOG.error("[WaxSealPacket] Failed to set suppressAttachmentRefundOnClose on provider {}", provider.getClass().getName(), tFlag);
                        }
                        LOG.info("[WaxSealPacket] Captured {} attachment stack(s) into sealed scroll for player {}",
                                nonEmptyCount, serverPlayer.getGameProfile().getName());
                    } else {
                        LOG.debug("[WaxSealPacket] No attachments found in ScrollAttachmentProvider for player {}",
                                serverPlayer.getGameProfile().getName());
                    }
                } else {
                    LOG.debug("[WaxSealPacket] Player containerMenu is not a ScrollAttachmentProvider: {}",
                            serverPlayer.containerMenu != null
                                    ? serverPlayer.containerMenu.getClass().getName()
                                    : "null");
                }
            } catch (Throwable tAttach) {
                LOG.error("[WaxSealPacket] Failed while capturing attachments into sealed scroll NBT", tAttach);
            }

            root.put("SealedScroll", seal);

            try {
                // 1.20.1: store our custom payload under the ItemStack tag's "CustomData" compound
                setCustomData(sealed, root);
            } catch (Throwable tSet) {
                LOG.error("[WaxSealPacket] Failed to attach CustomData to sealed scroll", tSet);
            }

            // 3) Add/deliver sealed scroll
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

            LOG.info("[WaxSealPacket] Delivered sealed scroll to {} (sender='{}' seed={} slices={} style={} date='{}')",
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

            for (int i = 0; i < player.getInventory().items.size(); i++) {
                ItemStack s = player.getInventory().items.get(i);
                if (!s.isEmpty() && s.getItem() == unsealed) {
                    ItemStack taken = s.copy();
                    try {
                        taken.setCount(1);
                    } catch (Throwable tSet) {
                        LOG.error("[WaxSealPacket] Failed to set taken count to 1 at slot {}", i, tSet);
                    }

                    try {
                        s.shrink(1);
                    } catch (Throwable tShrink) {
                        LOG.error("[WaxSealPacket] Failed to shrink unsealed scroll stack at slot {}", i, tShrink);
                    }
                    return taken;
                }
            }
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] removeOneUnsealedScroll failed", t);
        }
        return null;
    }

    /**
     * Resolve an item from the built-in item registry by path within this mod's namespace.
     * Returns null or Items.AIR if the item does not exist.
     */
    private static Item resolveItemByPath(@NotNull String path) {
        try {
            ResourceLocation id = new ResourceLocation(Constants.MOD_ID, path);
            Item item = BuiltInRegistries.ITEM.get(id);
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

    // ---------------------------------------------------------------------
    // CustomData compatibility (1.20.1)
    // ---------------------------------------------------------------------

    private static @NotNull CompoundTag getCustomDataCopy(@NotNull ItemStack stack) {
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

    private static void setCustomData(@NotNull ItemStack stack, @NotNull CompoundTag customDataRoot) {
        try {
            CompoundTag tag = stack.getOrCreateTag();
            tag.put(STACK_CUSTOM_DATA_KEY, customDataRoot);
        } catch (Throwable t) {
            LOG.error("[WaxSealPacket] setCustomData failed", t);
        }
    }
}
