// MainFile: common/src/main/java/net/z2six/featheredfriend/network/WaxSealPacket.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * // common/src/main/java/net/z2six/featheredfriend/network/WaxSealPacket.java
 *
 * Handles the "wax seal" action when the player clicks inside the wax area
 * on the ScrollSealingScreen with an etched Seal Stamp selected.
 *
 * Server-side behaviour:
 *  1) Remove 1x featheredfriend:scroll_unsealed from the player's inventory.
 *  2) Create 1x featheredfriend:scroll_sealed with all scroll NBT data:
 *     - RecipientName
 *     - RecipientUUID
 *     - RecipientText
 *     - MessageText
 *     - SignatureText
 *     - SenderName (from the Seal Stamp, not anything else)
 *     - Seed
 *     - Slices
 *     - Style
 *  3) Try to add the sealed scroll to the inventory; if full, drop at player.
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

            seal.putString("RecipientName", safeString(p.recipientName(), 256));
            seal.putString("RecipientUUID", safeString(p.recipientUUID(), 256));
            seal.putString("RecipientText", safeString(p.recipientText(), 32760));
            seal.putString("MessageText", safeString(p.messageText(), 32760));
            seal.putString("SignatureText", safeString(p.signatureText(), 32760));
            seal.putString("SenderName", safeString(p.senderName(), 256));
            seal.putLong("Seed", p.seed());
            seal.putInt("Slices", p.slices());
            seal.putInt("Style", p.style());

            root.put("SealedScroll", seal);

            try {
                // Correct 1.21+ API: set CUSTOM_DATA via DataComponents
                sealed.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
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

            LOG.info("[WaxSealPacket] Delivered sealed scroll to {} (sender='{}' seed={} slices={} style={})",
                    serverPlayer.getGameProfile().getName(),
                    p.senderName(),
                    p.seed(),
                    p.slices(),
                    p.style());
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
                    ItemStack taken = s.copyWithCount(1);
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
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
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
}
