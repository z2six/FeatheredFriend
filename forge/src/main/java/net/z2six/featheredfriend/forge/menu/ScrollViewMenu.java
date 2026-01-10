package net.z2six.featheredfriend.forge.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.menu.SealBreakGate;
import net.z2six.featheredfriend.registry.FFForgeMenus;
import org.slf4j.Logger;
import net.z2six.featheredfriend.Constants;

import java.util.ArrayList;
import java.util.List;

/**
 * ScrollViewMenu
 *
 * Component system removed: uses classic ItemStack NBT (stack.getTag()).
 */
public class ScrollViewMenu extends AbstractContainerMenu implements SealBreakGate {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int ATTACHMENT_SLOT_COUNT = 9;

    public static final int ATTACHMENT_START = 0;
    public static final int ATTACHMENT_END = ATTACHMENT_START + ATTACHMENT_SLOT_COUNT - 1; // 0..8

    public static final int PLAYER_INV_START = ATTACHMENT_END + 1; // 9
    public static final int PLAYER_INV_END = PLAYER_INV_START + 27 - 1; // 9..35

    public static final int HOTBAR_START = PLAYER_INV_END + 1; // 36
    public static final int HOTBAR_END = HOTBAR_START + 9 - 1; // 36..44

    private final Inventory playerInventory;

    private final List<ItemStack> attachmentSnapshot = new ArrayList<>();
    private boolean attachmentSnapshotLoaded = false;

    private final Container attachmentContainer = new SimpleContainer(ATTACHMENT_SLOT_COUNT);

    private boolean attachmentsDeliveredThisSession = false;

    private ItemStack sourceScrollStackSnapshot = ItemStack.EMPTY;

    private boolean sealBrokenThisSession = false;

    public ScrollViewMenu(int containerId, Inventory playerInventory) {
        super(FFForgeMenus.SCROLL_VIEW_MENU.get(), containerId);
        this.playerInventory = playerInventory;

        String playerName = "unknown";
        boolean clientSide = true;
        try {
            if (playerInventory.player != null) {
                playerName = playerInventory.player.getGameProfile().getName();
                clientSide = playerInventory.player.level().isClientSide;
            }
        } catch (Throwable ignored) {
        }

        LOG.info("[ScrollViewMenu] Constructed containerId={} player={} side={}",
                containerId,
                playerName,
                clientSide ? "CLIENT" : "SERVER");

        // Attachment slots (0..8)
        for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
            int x = 8 + (i * 18);
            int y = 17;

            this.addSlot(new Slot(this.attachmentContainer, i, x, y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return player != null && !player.level().isClientSide && sealBrokenThisSession;
                }
            });
        }

        // Player inventory (3x9) indices 9..35
        for (int row = 0; row < 3; ++row) {
            int y = 49 + row * 18;
            for (int col = 0; col < 9; ++col) {
                int index = col + row * 9 + 9;
                int x = 8 + col * 18;
                this.addSlot(new Slot(playerInventory, index, x, y));
            }
        }

        // Hotbar (1x9) indices 36..44
        for (int col = 0; col < 9; ++col) {
            int x = 8 + col * 18;
            int y = 107;
            this.addSlot(new Slot(playerInventory, col, x, y));
        }

        LOG.debug("[ScrollViewMenu] Slot layout: attachment[{}..{}], inv[{}..{}], hotbar[{}..{}]",
                ATTACHMENT_START, ATTACHMENT_END,
                PLAYER_INV_START, PLAYER_INV_END,
                HOTBAR_START, HOTBAR_END);

        // Only parse attachments on the logical server.
        try {
            if (playerInventory.player != null && !playerInventory.player.level().isClientSide) {
                loadAttachmentSnapshotFromHeldScroll(playerInventory.player);
                syncContainerFromSnapshot();
            } else {
                LOG.info("[ScrollViewMenu] Constructor: skipping snapshot load (client-side or null player)");
            }
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] Constructor snapshot load failed", t);
        }
    }

    public Inventory getPlayerInventory() {
        return playerInventory;
    }

    public Container getAttachmentContainer() {
        return attachmentContainer;
    }

    public boolean hasAnyAttachmentsInContainer() {
        try {
            for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
                ItemStack s = attachmentContainer.getItem(i);
                if (s != null && !s.isEmpty()) return true;
            }
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] hasAnyAttachmentsInContainer failed", t);
        }
        return false;
    }

    @Override
    public void markSealBroken(String reason) {
        try {
            if (sealBrokenThisSession) {
                LOG.debug("[ScrollViewMenu] markSealBroken: already true; ignoring (reason={}) containerId={}", reason, this.containerId);
                return;
            }
            sealBrokenThisSession = true;
            LOG.info("[ScrollViewMenu] Seal marked broken (reason={}) containerId={} player={}",
                    reason,
                    this.containerId,
                    playerInventory != null && playerInventory.player != null ? safePlayerName(playerInventory.player) : "null");

            try {
                syncContainerFromSnapshot();
            } catch (Throwable syncErr) {
                LOG.error("[ScrollViewMenu] markSealBroken: syncContainerFromSnapshot failed", syncErr);
            }
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] markSealBroken failed", t);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        boolean valid = player != null && !player.isRemoved();
        if (!valid) {
            LOG.info("[ScrollViewMenu] stillValid=false (player null/removed) containerId={}", this.containerId);
        }
        return valid;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;

            if (player == null) {
                LOG.warn("[ScrollViewMenu] quickMoveStack: player is null");
                return empty;
            }

            if (!sealBrokenThisSession) {
                LOG.debug("[ScrollViewMenu] quickMoveStack: seal not broken -> ignore index={}", index);
                return empty;
            }

            if (index < 0 || index >= this.slots.size()) {
                LOG.warn("[ScrollViewMenu] quickMoveStack: index {} outside slots size {}", index, this.slots.size());
                return empty;
            }

            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) {
                return empty;
            }

            ItemStack stackInSlot = slot.getItem();
            ItemStack original = stackInSlot.copy();

            if (index >= ATTACHMENT_START && index <= ATTACHMENT_END) {
                if (!this.moveItemStackTo(stackInSlot, PLAYER_INV_START, HOTBAR_END + 1, true)) {
                    return empty;
                }

                try {
                    syncSnapshotFromContainer();
                } catch (Throwable syncErr) {
                    LOG.error("[ScrollViewMenu] quickMoveStack: syncSnapshotFromContainer failed", syncErr);
                }
            } else {
                return empty;
            }

            if (stackInSlot.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            slot.onTake(player, stackInSlot);
            return original;
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] quickMoveStack failed (index={})", index, t);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);

        try {
            if (player == null) {
                LOG.warn("[ScrollViewMenu] removed: player is null (containerId={})", this.containerId);
                return;
            }

            boolean clientSide = player.level().isClientSide;
            LOG.info("[ScrollViewMenu] removed() fired containerId={} player={} side={} sealBroken={} deliveredAlready={} snapshotLoaded={} snapshotSize={} carriedEmpty={}",
                    this.containerId,
                    safePlayerName(player),
                    clientSide ? "CLIENT" : "SERVER",
                    sealBrokenThisSession,
                    attachmentsDeliveredThisSession,
                    attachmentSnapshotLoaded,
                    attachmentSnapshot.size(),
                    this.getCarried().isEmpty());

            if (clientSide) {
                return;
            }

            if (!sealBrokenThisSession) {
                LOG.info("[ScrollViewMenu] removed: seal not broken -> skipping attachment delivery containerId={} player={}",
                        this.containerId, safePlayerName(player));
                attachmentSnapshot.clear();
                clearAttachmentContainerServerSide();
                return;
            }

            if (attachmentsDeliveredThisSession) {
                LOG.info("[ScrollViewMenu] removed: already delivered this session -> skip (containerId={})", this.containerId);
                attachmentSnapshot.clear();
                clearAttachmentContainerServerSide();
                return;
            }
            attachmentsDeliveredThisSession = true;

            if (!attachmentSnapshotLoaded) {
                LOG.info("[ScrollViewMenu] removed: late snapshot load attempt (containerId={})", this.containerId);
                loadAttachmentSnapshotFromHeldScroll(player);
                syncContainerFromSnapshot();
            }

            try {
                syncSnapshotFromContainer();
            } catch (Throwable syncErr) {
                LOG.error("[ScrollViewMenu] removed: syncSnapshotFromContainer failed", syncErr);
            }

            if (attachmentSnapshot.isEmpty()) {
                LOG.info("[ScrollViewMenu] removed: no attachments to deliver (containerId={})", this.containerId);
                clearAttachmentContainerServerSide();
                return;
            }

            int attemptedStacks = 0;
            int fullyAddedStacks = 0;
            int droppedStacks = 0;

            for (int i = 0; i < attachmentSnapshot.size(); i++) {
                ItemStack stack = attachmentSnapshot.get(i);
                if (stack == null || stack.isEmpty()) continue;

                attemptedStacks++;

                ItemStack remaining = stack.copy();

                LOG.info("[ScrollViewMenu] Deliver attempt i={} -> {} x{}",
                        i,
                        BuiltInRegistries.ITEM.getKey(remaining.getItem()),
                        remaining.getCount());

                boolean addedAll = tryAddWholeStack(player, remaining);

                if (addedAll) {
                    fullyAddedStacks++;
                    LOG.info("[ScrollViewMenu] Deliver success i={} -> added to inventory: {} x{}",
                            i,
                            BuiltInRegistries.ITEM.getKey(stack.getItem()),
                            stack.getCount());
                } else {
                    if (!remaining.isEmpty()) {
                        boolean dropped = dropOrSpawnAtPlayer(player, remaining);
                        if (dropped) {
                            droppedStacks++;
                            LOG.info("[ScrollViewMenu] Deliver fallback i={} -> dropped remainder: {} x{}",
                                    i,
                                    BuiltInRegistries.ITEM.getKey(remaining.getItem()),
                                    remaining.getCount());
                        } else {
                            LOG.warn("[ScrollViewMenu] Deliver fallback i={} -> FAILED to drop remainder: {} x{}",
                                    i,
                                    BuiltInRegistries.ITEM.getKey(remaining.getItem()),
                                    remaining.getCount());
                        }
                    } else {
                        fullyAddedStacks++;
                        LOG.info("[ScrollViewMenu] Deliver partial i={} -> remainder empty; effectively fully added", i);
                    }
                }
            }

            LOG.info("[ScrollViewMenu] removed: attemptedStacks={} fullyAddedStacks={} droppedStacks={} player={} sourceScrollItem={} containerId={}",
                    attemptedStacks,
                    fullyAddedStacks,
                    droppedStacks,
                    safePlayerName(player),
                    !sourceScrollStackSnapshot.isEmpty()
                            ? BuiltInRegistries.ITEM.getKey(sourceScrollStackSnapshot.getItem())
                            : "none",
                    this.containerId);

            attachmentSnapshot.clear();
            clearAttachmentContainerServerSide();
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] removed failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Snapshot <-> Container syncing
    // ---------------------------------------------------------------------

    private void syncContainerFromSnapshot() {
        try {
            for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
                ItemStack s = ItemStack.EMPTY;
                if (i < attachmentSnapshot.size()) {
                    ItemStack snap = attachmentSnapshot.get(i);
                    if (snap != null && !snap.isEmpty()) {
                        s = snap.copy();
                    }
                }
                attachmentContainer.setItem(i, s);
            }

            if (attachmentSnapshot.size() > ATTACHMENT_SLOT_COUNT) {
                LOG.warn("[ScrollViewMenu] syncContainerFromSnapshot: snapshotSize={} > {} (extra attachments hidden but preserved for delivery-on-close)",
                        attachmentSnapshot.size(),
                        ATTACHMENT_SLOT_COUNT);
            }

        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] syncContainerFromSnapshot failed", t);
        }
    }

    private void syncSnapshotFromContainer() {
        try {
            if (!attachmentSnapshotLoaded) attachmentSnapshotLoaded = true;

            for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
                ItemStack c = attachmentContainer.getItem(i);
                ItemStack newVal = (c != null && !c.isEmpty()) ? c.copy() : ItemStack.EMPTY;

                if (i < attachmentSnapshot.size()) {
                    attachmentSnapshot.set(i, newVal);
                } else {
                    attachmentSnapshot.add(newVal);
                }
            }

            List<ItemStack> rebuilt = new ArrayList<>(attachmentSnapshot.size());
            for (int i = 0; i < attachmentSnapshot.size(); i++) {
                ItemStack s = attachmentSnapshot.get(i);
                if (s != null && !s.isEmpty()) rebuilt.add(s);
            }
            attachmentSnapshot.clear();
            attachmentSnapshot.addAll(rebuilt);

            LOG.debug("[ScrollViewMenu] syncSnapshotFromContainer: snapshotSize now {}", attachmentSnapshot.size());

        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] syncSnapshotFromContainer failed", t);
        }
    }

    private void clearAttachmentContainerServerSide() {
        try {
            for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
                attachmentContainer.setItem(i, ItemStack.EMPTY);
            }
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] clearAttachmentContainerServerSide failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Snapshot parsing (classic NBT)
    // ---------------------------------------------------------------------

    private void loadAttachmentSnapshotFromHeldScroll(Player player) {
        try {
            attachmentSnapshot.clear();
            attachmentSnapshotLoaded = true;

            ItemStack hand = player.getMainHandItem();
            if (hand == null || hand.isEmpty()) {
                hand = player.getOffhandItem();
            }

            if (hand == null || hand.isEmpty()) {
                LOG.info("[ScrollViewMenu] Snapshot load: both hands empty (containerId={})", this.containerId);
                sourceScrollStackSnapshot = ItemStack.EMPTY;
                return;
            }

            sourceScrollStackSnapshot = hand.copy();

            LOG.info("[ScrollViewMenu] Snapshot load: reading from hand item={} count={} containerId={}",
                    BuiltInRegistries.ITEM.getKey(hand.getItem()),
                    hand.getCount(),
                    this.containerId);

            // ✅ CANONICAL READ: tag.<modid>.SealedScroll
            CompoundTag tag = hand.getTag();
            if (tag == null || tag.isEmpty()) {
                LOG.info("[ScrollViewMenu] Snapshot load: root tag missing/empty");
                return;
            }

            CompoundTag ff = tag.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)
                    ? tag.getCompound(Constants.MOD_ID)
                    : null;

            if (ff == null || ff.isEmpty() || !ff.contains("SealedScroll", Tag.TAG_COMPOUND)) {
                LOG.info("[ScrollViewMenu] Snapshot load: SealedScroll compound missing (expected tag.{}.SealedScroll)", Constants.MOD_ID);
                return;
            }

            CompoundTag seal = ff.getCompound("SealedScroll");

            if (!seal.contains("Attachments", Tag.TAG_LIST)) {
                LOG.info("[ScrollViewMenu] Snapshot load: Attachments list missing");
                return;
            }

            ListTag list = seal.getList("Attachments", Tag.TAG_COMPOUND);
            if (list == null || list.isEmpty()) {
                LOG.info("[ScrollViewMenu] Snapshot load: Attachments list empty");
                return;
            }

            int parsed = 0;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag stackTag = list.getCompound(i);
                if (stackTag == null || stackTag.isEmpty()) continue;

                ItemStack rebuilt = rebuildStackFromAttachmentTag(stackTag);
                if (rebuilt.isEmpty()) continue;

                attachmentSnapshot.add(rebuilt);
                parsed++;

                LOG.info("[ScrollViewMenu] Snapshot load: parsed i={} -> {} x{} hasTag={}",
                        i,
                        BuiltInRegistries.ITEM.getKey(rebuilt.getItem()),
                        rebuilt.getCount(),
                        rebuilt.hasTag());
            }

            LOG.info("[ScrollViewMenu] Snapshot load complete: parsed={} snapshotSize={} containerId={}",
                    parsed, attachmentSnapshot.size(), this.containerId);
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] loadAttachmentSnapshotFromHeldScroll failed", t);
        }
    }

    private static ItemStack rebuildStackFromAttachmentTag(CompoundTag tag) {
        try {
            if (tag == null || tag.isEmpty()) {
                return ItemStack.EMPTY;
            }

            // Accept either:
            //  A) Vanilla ItemStack NBT: {id:"minecraft:...", Count:1b, tag:{...}}
            //  B) Your previous format: {id:"...", Count:1, CustomData:{...}}  (mapped into "tag")
            if (!tag.contains("id", Tag.TAG_STRING)) {
                return ItemStack.EMPTY;
            }

            CompoundTag std = tag.copy();

            // Map CustomData -> tag
            if (std.contains("CustomData", Tag.TAG_COMPOUND) && !std.contains("tag", Tag.TAG_COMPOUND)) {
                std.put("tag", std.getCompound("CustomData").copy());
                std.remove("CustomData");
            }

            // Ensure Count is a BYTE for ItemStack.of() (some writers use int)
            if (std.contains("Count", Tag.TAG_INT)) {
                int c = std.getInt("Count");
                if (c <= 0) c = 1;
                if (c > 64) c = 64;
                std.putByte("Count", (byte) c);
            } else if (!std.contains("Count", Tag.TAG_BYTE)) {
                std.putByte("Count", (byte) 1);
            }

            // This validates item existence internally; if invalid, it returns EMPTY.
            ItemStack rebuilt = ItemStack.of(std);
            if (rebuilt == null) return ItemStack.EMPTY;
            return rebuilt;
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] rebuildStackFromAttachmentTag failed", t);
            return ItemStack.EMPTY;
        }
    }

    // ---------------------------------------------------------------------
    // Delivery helpers (unchanged)
    // ---------------------------------------------------------------------

    private static CompoundTag getSealedScrollCompoundCanonical(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return null;

            CompoundTag tag = stack.getTag();
            if (tag == null || tag.isEmpty()) return null;

            if (!tag.contains(net.z2six.featheredfriend.Constants.MOD_ID, Tag.TAG_COMPOUND)) return null;
            CompoundTag ff = tag.getCompound(net.z2six.featheredfriend.Constants.MOD_ID);

            if (!ff.contains("SealedScroll", Tag.TAG_COMPOUND)) return null;
            return ff.getCompound("SealedScroll");
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] getSealedScrollCompoundCanonical failed", t);
            return null;
        }
    }

    private static boolean tryAddWholeStack(Player player, ItemStack stack) {
        try {
            if (stack.isEmpty()) {
                return true;
            }

            boolean addedSome = player.addItem(stack);

            if (!addedSome) {
                LOG.info("[ScrollViewMenu] tryAddWholeStack: inventory rejected entire stack: {} x{}",
                        BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        stack.getCount());
                return false;
            }

            if (stack.isEmpty()) {
                return true;
            }

            LOG.info("[ScrollViewMenu] tryAddWholeStack: partial insert, remainder now {} x{}",
                    BuiltInRegistries.ITEM.getKey(stack.getItem()),
                    stack.getCount());
            return false;
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] tryAddWholeStack failed; leaving stack as remainder to be dropped", t);
            return false;
        }
    }

    private static boolean dropOrSpawnAtPlayer(Player player, ItemStack stack) {
        try {
            if (stack.isEmpty()) {
                return true;
            }

            ItemEntity ent = null;
            try {
                ent = player.drop(stack, false);
            } catch (Throwable tDrop) {
                LOG.error("[ScrollViewMenu] player.drop failed; will attempt manual spawn", tDrop);
                ent = null;
            }

            if (ent != null) {
                LOG.info("[ScrollViewMenu] dropOrSpawnAtPlayer: dropped via player.drop entityId={} {} x{}",
                        ent.getId(),
                        BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        stack.getCount());
                return true;
            }

            return spawnItemEntityAtPlayer(player, stack);
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] dropOrSpawnAtPlayer failed", t);
            return false;
        }
    }

    private static boolean spawnItemEntityAtPlayer(Player player, ItemStack stack) {
        try {
            if (stack.isEmpty()) {
                return true;
            }
            var level = player.level();
            if (level == null) {
                return false;
            }

            ItemEntity ent = new ItemEntity(
                    level,
                    player.getX(),
                    player.getY() + 0.5,
                    player.getZ(),
                    stack
            );

            try {
                ent.setNoPickUpDelay();
            } catch (Throwable ignored) {
            }

            boolean ok = level.addFreshEntity(ent);
            if (!ok) {
                LOG.warn("[ScrollViewMenu] spawnItemEntityAtPlayer: addFreshEntity returned false for {} x{}",
                        BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        stack.getCount());
            } else {
                LOG.info("[ScrollViewMenu] spawnItemEntityAtPlayer: spawned entityId={} {} x{}",
                        ent.getId(),
                        BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        stack.getCount());
            }
            return ok;
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] spawnItemEntityAtPlayer failed", t);
            return false;
        }
    }

    private static String safePlayerName(Player player) {
        try {
            return player.getGameProfile().getName();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
