// MainFile: common/src/main/java/net/z2six/featheredfriend/network/BreakSealPacket.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.menu.SealBreakGate;

import org.slf4j.Logger;

/**
 * // common/src/main/java/net/z2six/featheredfriend/network/BreakSealPacket.java
 *
 * C2S payload sent the moment the player "breaks the seal" by clicking the wax area
 * in ScrollViewScreen (the click that starts the OPENING animation).
 *
 * Forge 1.20.1 note:
 * - CustomPacketPayload/StreamCodec/RegistryFriendlyByteBuf do not exist.
 * - This packet keeps the exact same fields + encode/decode logic, but uses FriendlyByteBuf.
 * - Former DataComponents.CUSTOM_DATA payload is stored under ItemStack tag sub-compound "CustomData".
 */
public record BreakSealPacket(
        int slotHint,
        long seed,
        String recipientUUID,
        String dateText,
        String senderName
) {

    private static final Logger LOG = LogUtils.getLogger();

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "break_seal");

    // Where we store the former "minecraft:custom_data" payload in 1.20.1
    private static final String STACK_CUSTOM_DATA_KEY = "CustomData";

    public static void encode(FriendlyByteBuf buf, BreakSealPacket p) {
        try {
            buf.writeVarInt(p.slotHint);
            buf.writeLong(p.seed);
            buf.writeUtf(p.recipientUUID != null ? p.recipientUUID : "", 256);
            buf.writeUtf(p.dateText != null ? p.dateText : "", 512);
            buf.writeUtf(p.senderName != null ? p.senderName : "", 256);
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] encode failed", t);
        }
    }

    public static BreakSealPacket decode(FriendlyByteBuf buf) {
        try {
            int slotHint = buf.readVarInt();
            long seed = buf.readLong();
            String recUuid = buf.readUtf(256);
            String date = buf.readUtf(512);
            String sender = buf.readUtf(256);
            return new BreakSealPacket(slotHint, seed, recUuid, date, sender);
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] decode failed; returning safe defaults", t);
            return new BreakSealPacket(-1, 0L, "", "", "");
        }
    }

    // ---------------------------------------------------------------------
    // Server handler
    // ---------------------------------------------------------------------

    public static void handle(BreakSealPacket p, ServerPlayer serverPlayer) {
        try {
            if (serverPlayer == null) {
                LOG.error("[BreakSealPacket] handle: serverPlayer is null");
                return;
            }

            LOG.info("[BreakSealPacket] handle: player={} slotHint={} seed={} recipientUUID='{}' date='{}' sender='{}'",
                    serverPlayer.getGameProfile().getName(),
                    p.slotHint(),
                    p.seed(),
                    safeLog(p.recipientUUID()),
                    safeLog(p.dateText()),
                    safeLog(p.senderName()));

            // We only trust this packet while the player is in a container that opts-in to seal-break gating.
            if (!(serverPlayer.containerMenu instanceof SealBreakGate sealGate)) {
                LOG.warn("[BreakSealPacket] handle: player {} containerMenu does not implement SealBreakGate (is {}); ignoring",
                        serverPlayer.getGameProfile().getName(),
                        serverPlayer.containerMenu != null ? serverPlayer.containerMenu.getClass().getName() : "null");
                return;
            }

            // Identify sealed scroll stack
            IdentifiedStack target = findTargetSealedScroll(serverPlayer, p);
            if (target == null) {
                LOG.warn("[BreakSealPacket] handle: could not find matching sealed scroll for player {}; ignoring",
                        serverPlayer.getGameProfile().getName());
                return;
            }

            LOG.info("[BreakSealPacket] handle: matched sealed scroll at {} (item={})",
                    target.locationDescription,
                    BuiltInRegistries.ITEM.getKey(target.stack.getItem()));

            // Convert to opened scroll
            Item openedItem = resolveItemByPath("scroll_opened");
            Item sealedItem = resolveItemByPath("scroll_sealed");

            if (openedItem == null || openedItem == Items.AIR) {
                LOG.error("[BreakSealPacket] scroll_opened not found in registry; aborting conversion");
                return;
            }
            if (sealedItem == null || sealedItem == Items.AIR) {
                LOG.error("[BreakSealPacket] scroll_sealed not found in registry; aborting conversion");
                return;
            }

            if (target.stack.getItem() != sealedItem) {
                LOG.warn("[BreakSealPacket] Matched stack item is not scroll_sealed (got {}); aborting conversion",
                        BuiltInRegistries.ITEM.getKey(target.stack.getItem()));
                return;
            }

            ItemStack opened = new ItemStack(openedItem, 1);

            // Copy SealedScroll custom data but REMOVE Attachments
            boolean copied = copySealedScrollDataWithoutAttachments(target.stack, opened);
            if (!copied) {
                LOG.warn("[BreakSealPacket] Failed to copy SealedScroll data (without attachments). Proceeding with opened scroll anyway.");
            }

            // Replace in the correct location
            boolean replaced = replaceStackAtLocation(serverPlayer, target, opened);
            if (!replaced) {
                LOG.error("[BreakSealPacket] Failed to replace sealed scroll with opened scroll at {}; aborting",
                        target.locationDescription);
                return;
            }

            // Mark menu: this enables attachment delivery upon close.
            try {
                sealGate.markSealBroken("BreakSealPacket");
            } catch (Throwable tMark) {
                LOG.error("[BreakSealPacket] Failed to mark SealBreakGate seal broken; attachments may not deliver", tMark);
            }

            LOG.info("[BreakSealPacket] Conversion complete for player {}: scroll_sealed -> scroll_opened at {}",
                    serverPlayer.getGameProfile().getName(),
                    target.locationDescription);

        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] handle failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Identification + replacement helpers
    // ---------------------------------------------------------------------

    private static final class IdentifiedStack {
        final ItemStack stack;
        final Location location;
        final int invIndex; // only valid for INVENTORY
        final String locationDescription;

        IdentifiedStack(ItemStack stack, Location location, int invIndex, String desc) {
            this.stack = stack;
            this.location = location;
            this.invIndex = invIndex;
            this.locationDescription = desc;
        }
    }

    private enum Location {
        MAIN_HAND,
        OFF_HAND,
        INVENTORY
    }

    private static IdentifiedStack findTargetSealedScroll(ServerPlayer player, BreakSealPacket p) {
        try {
            Item sealedItem = resolveItemByPath("scroll_sealed");
            if (sealedItem == null || sealedItem == Items.AIR) {
                LOG.error("[BreakSealPacket] findTargetSealedScroll: scroll_sealed not found");
                return null;
            }

            // 1) Slot hint first (most exact)
            IdentifiedStack hinted = findBySlotHint(player, p, sealedItem);
            if (hinted != null) {
                return hinted;
            }

            // 2) Fallback scan (hands + inventory)
            IdentifiedStack scanned = scanAllForFingerprint(player, p, sealedItem);
            if (scanned != null) {
                return scanned;
            }

            return null;
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] findTargetSealedScroll failed", t);
            return null;
        }
    }

    private static IdentifiedStack findBySlotHint(ServerPlayer player,
                                                  BreakSealPacket p,
                                                  Item sealedItem) {
        try {
            int hint = p.slotHint();
            if (hint == 36) {
                ItemStack s = player.getMainHandItem();
                if (matchesFingerprintSealedScroll(s, p, sealedItem)) {
                    return new IdentifiedStack(s, Location.MAIN_HAND, -1, "MAIN_HAND(36)");
                }
                LOG.debug("[BreakSealPacket] Slot hint MAIN_HAND did not match fingerprint");
                return null;
            }
            if (hint == 37) {
                ItemStack s = player.getOffhandItem();
                if (matchesFingerprintSealedScroll(s, p, sealedItem)) {
                    return new IdentifiedStack(s, Location.OFF_HAND, -1, "OFF_HAND(37)");
                }
                LOG.debug("[BreakSealPacket] Slot hint OFF_HAND did not match fingerprint");
                return null;
            }
            if (hint >= 0 && hint < player.getInventory().items.size()) {
                ItemStack s = player.getInventory().items.get(hint);
                if (matchesFingerprintSealedScroll(s, p, sealedItem)) {
                    return new IdentifiedStack(s, Location.INVENTORY, hint, "INVENTORY(" + hint + ")");
                }
                LOG.debug("[BreakSealPacket] Slot hint INVENTORY({}) did not match fingerprint", hint);
                return null;
            }
            return null;
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] findBySlotHint failed", t);
            return null;
        }
    }

    private static IdentifiedStack scanAllForFingerprint(ServerPlayer player,
                                                         BreakSealPacket p,
                                                         Item sealedItem) {
        try {
            ItemStack main = player.getMainHandItem();
            if (matchesFingerprintSealedScroll(main, p, sealedItem)) {
                return new IdentifiedStack(main, Location.MAIN_HAND, -1, "MAIN_HAND(scan)");
            }

            ItemStack off = player.getOffhandItem();
            if (matchesFingerprintSealedScroll(off, p, sealedItem)) {
                return new IdentifiedStack(off, Location.OFF_HAND, -1, "OFF_HAND(scan)");
            }

            for (int i = 0; i < player.getInventory().items.size(); i++) {
                ItemStack s = player.getInventory().items.get(i);
                if (matchesFingerprintSealedScroll(s, p, sealedItem)) {
                    return new IdentifiedStack(s, Location.INVENTORY, i, "INVENTORY(scan:" + i + ")");
                }
            }

            return null;
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] scanAllForFingerprint failed", t);
            return null;
        }
    }

    private static boolean matchesFingerprintSealedScroll(ItemStack stack,
                                                          BreakSealPacket p,
                                                          Item sealedItem) {
        try {
            if (stack.isEmpty()) return false;
            if (stack.getItem() != sealedItem) return false;

            CompoundTag root = getCustomDataCopy(stack);
            if (root == null || root.isEmpty()) return false;
            if (!root.contains("SealedScroll", CompoundTag.TAG_COMPOUND)) return false;

            CompoundTag seal = root.getCompound("SealedScroll");

            long seed = 0L;
            try {
                if (seal.contains("Seed")) seed = seal.getLong("Seed");
            } catch (Throwable ignored) {
                seed = 0L;
            }

            String recUuid = "";
            String dateText = "";
            String senderName = "";
            try {
                if (seal.contains("RecipientUUID")) recUuid = seal.getString("RecipientUUID");
            } catch (Throwable ignored) {
                recUuid = "";
            }
            try {
                if (seal.contains("DateText")) dateText = seal.getString("DateText");
            } catch (Throwable ignored) {
                dateText = "";
            }
            try {
                if (seal.contains("SenderName")) senderName = seal.getString("SenderName");
            } catch (Throwable ignored) {
                senderName = "";
            }

            boolean ok =
                    seed == p.seed()
                            && safeEq(recUuid, p.recipientUUID())
                            && safeEq(dateText, p.dateText())
                            && safeEq(senderName, p.senderName());

            if (!ok) {
                LOG.debug("[BreakSealPacket] Fingerprint mismatch on candidate stack: seed={} recUuid='{}' date='{}' sender='{}' vs packet seed={} recUuid='{}' date='{}' sender='{}'",
                        seed,
                        safeLog(recUuid),
                        safeLog(dateText),
                        safeLog(senderName),
                        p.seed(),
                        safeLog(p.recipientUUID()),
                        safeLog(p.dateText()),
                        safeLog(p.senderName()));
            }

            return ok;
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] matchesFingerprintSealedScroll failed", t);
            return false;
        }
    }

    private static boolean replaceStackAtLocation(ServerPlayer player,
                                                  IdentifiedStack identified,
                                                  ItemStack replacement) {
        try {
            if (replacement.isEmpty()) {
                LOG.error("[BreakSealPacket] replaceStackAtLocation: replacement is EMPTY; refusing");
                return false;
            }

            switch (identified.location) {
                case MAIN_HAND -> {
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, replacement);
                    return true;
                }
                case OFF_HAND -> {
                    player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, replacement);
                    return true;
                }
                case INVENTORY -> {
                    int idx = identified.invIndex;
                    if (idx < 0 || idx >= player.getInventory().items.size()) {
                        LOG.error("[BreakSealPacket] replaceStackAtLocation: invalid inventory index {}", idx);
                        return false;
                    }
                    player.getInventory().items.set(idx, replacement);
                    return true;
                }
                default -> {
                    LOG.error("[BreakSealPacket] replaceStackAtLocation: unknown location {}", identified.location);
                    return false;
                }
            }
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] replaceStackAtLocation failed", t);
            return false;
        }
    }

    /**
     * Copies all SealedScroll NBT keys into opened, but strips Attachments so they can't be claimed twice.
     * Returns true if at least a SealedScroll compound was present and copied.
     */
    private static boolean copySealedScrollDataWithoutAttachments(ItemStack sealed,
                                                                  ItemStack opened) {
        try {
            CompoundTag sealedRoot = getCustomDataCopy(sealed);
            if (sealedRoot == null || sealedRoot.isEmpty()) {
                LOG.warn("[BreakSealPacket] Sealed stack has no CUSTOM_DATA; nothing to copy");
                return false;
            }

            if (!sealedRoot.contains("SealedScroll", CompoundTag.TAG_COMPOUND)) {
                LOG.warn("[BreakSealPacket] Sealed stack CUSTOM_DATA missing SealedScroll; nothing to copy");
                return false;
            }

            CompoundTag sealedSeal = sealedRoot.getCompound("SealedScroll");
            if (sealedSeal == null) {
                LOG.warn("[BreakSealPacket] SealedScroll compound is null; nothing to copy");
                return false;
            }

            CompoundTag openedRoot = new CompoundTag();
            CompoundTag openedSeal = sealedSeal.copy();

            if (openedSeal.contains("Attachments")) {
                try {
                    openedSeal.remove("Attachments");
                    LOG.info("[BreakSealPacket] Removed Attachments from opened scroll SealedScroll compound");
                } catch (Throwable tRem) {
                    LOG.error("[BreakSealPacket] Failed to remove Attachments key from SealedScroll copy", tRem);
                }
            }

            openedRoot.put("SealedScroll", openedSeal);
            setCustomData(opened, openedRoot);

            LOG.debug("[BreakSealPacket] Copied SealedScroll data (without attachments) onto opened scroll");
            return true;
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] copySealedScrollDataWithoutAttachments failed", t);
            return false;
        }
    }

    private static Item resolveItemByPath(String path) {
        try {
            ResourceLocation id = new ResourceLocation(Constants.MOD_ID, path);
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null) {
                LOG.error("[BreakSealPacket] resolveItemByPath: item {} is null", id);
                return Items.AIR;
            }
            if (item == Items.AIR) {
                LOG.warn("[BreakSealPacket] resolveItemByPath: item {} returned as AIR", id);
            }
            return item;
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] resolveItemByPath failed for path='{}'", path, t);
            return Items.AIR;
        }
    }

    private static boolean safeEq(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private static String safeLog(String s) {
        if (s == null) return "null";
        if (s.length() <= 120) return s;
        return s.substring(0, 120) + "...";
    }

    // ---------------------------------------------------------------------
    // CustomData compatibility (1.20.1)
    // ---------------------------------------------------------------------

    private static CompoundTag getCustomDataCopy(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            if (tag == null) return new CompoundTag();
            if (!tag.contains(STACK_CUSTOM_DATA_KEY, Tag.TAG_COMPOUND)) return new CompoundTag();
            CompoundTag cd = tag.getCompound(STACK_CUSTOM_DATA_KEY);
            return cd == null ? new CompoundTag() : cd.copy();
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] getCustomDataCopy failed", t);
            return new CompoundTag();
        }
    }

    private static void setCustomData(ItemStack stack, CompoundTag customDataRoot) {
        try {
            CompoundTag tag = stack.getOrCreateTag();
            tag.put(STACK_CUSTOM_DATA_KEY, customDataRoot);
        } catch (Throwable t) {
            LOG.error("[BreakSealPacket] setCustomData failed", t);
        }
    }
}
