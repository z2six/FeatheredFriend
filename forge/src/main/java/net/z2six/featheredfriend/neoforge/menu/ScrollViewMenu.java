// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/ScrollViewMenu.java
package net.z2six.featheredfriend.neoforge.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.z2six.featheredfriend.menu.SealBreakGate;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/ScrollViewMenu.java
 *
 * ScrollViewMenu
 *
 * Updated behavior (PRESERVED + EXTENDED):
 *  - Attachments are ONLY delivered on server-side menu close if the seal was actually broken.
 *  - "Seal broken" is set by the server when handling BreakSealPacket via SealBreakGate.
 *
 * IMPORTANT (PRESERVED):
 *  - We still take a snapshot of attachments from the sealed scroll at menu creation (server side),
 *    so we can later deliver them even after the held item is converted to scroll_opened.
 *
 * NEW (to support pearl inventory):
 *  - We expose the snapshot via a 9-slot attachment Container.
 *  - Player can TAKE items out of attachment slots (and shift-click them into inventory).
 *  - Player may NOT put items into attachment slots.
 *  - Anything the player takes is removed from the snapshot/container so it will NOT be delivered again on close.
 *  - If player closes without taking everything, the remaining attachments are still delivered on close (server-side),
 *    but ONLY if the seal was broken (existing contract).
 */
public class ScrollViewMenu extends AbstractContainerMenu implements SealBreakGate {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Slot layout (mirrors your inventory.png layout)
    // ---------------------------------------------------------------------

    private static final int ATTACHMENT_SLOT_COUNT = 9;

    // Slot index layout for this menu
    public static final int ATTACHMENT_START = 0;
    public static final int ATTACHMENT_END = ATTACHMENT_START + ATTACHMENT_SLOT_COUNT - 1; // 0..8

    public static final int PLAYER_INV_START = ATTACHMENT_END + 1; // 9
    public static final int PLAYER_INV_END = PLAYER_INV_START + 27 - 1; // 9..35

    public static final int HOTBAR_START = PLAYER_INV_END + 1; // 36
    public static final int HOTBAR_END = HOTBAR_START + 9 - 1; // 36..44

    private final Inventory playerInventory;

    // Snapshot of attachments parsed from the sealed scroll NBT.
    // NOTE: This list remains the authoritative "remaining to deliver" for this session.
    private final List<ItemStack> attachmentSnapshot = new ArrayList<>();
    private boolean attachmentSnapshotLoaded = false;

    // Exposed container view (first 9 attachments mapped into slots).
    // This is what the EnderPearlInventory UI will display.
    private final Container attachmentContainer = new SimpleContainer(ATTACHMENT_SLOT_COUNT);

    // Prevent double-processing in edge cases.
    private boolean attachmentsDeliveredThisSession = false;

    // Debug: what scroll stack we read from.
    private ItemStack sourceScrollStackSnapshot = ItemStack.EMPTY;

    // Only deliver attachments after the seal was broken.
    private boolean sealBrokenThisSession = false;

    public ScrollViewMenu(int containerId, @NotNull Inventory playerInventory) {
        super(FFNeoForgeMenus.SCROLL_VIEW_MENU.get(), containerId);
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

        // -----------------------------------------------------------------
        // Slot layout:
        //  - Attachment bar: 1x9 at (x=8 + 18*i, y=17)
        //  - Player inventory 3x9 at (x=8 + 18*col, y=49 + 18*row)
        //  - Hotbar 1x9 at (x=8 + 18*col, y=107)
        //
        // ScrollViewScreen can still hide slots by overriding renderSlot/slotClicked (screen-side),
        // just like your ScrollSealingScreen does.
        // -----------------------------------------------------------------

        // Attachment slots (0..8)
        for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
            int x = 8 + (i * 18);
            int y = 17;

            this.addSlot(new Slot(this.attachmentContainer, i, x, y) {
                @Override
                public boolean mayPlace(@NotNull ItemStack stack) {
                    // View-only: disallow inserting into scroll attachments.
                    return false;
                }

                @Override
                public boolean mayPickup(@NotNull Player player) {
                    // We keep the original design constraint: attachments are only valid once seal is broken.
                    // This prevents taking items before break (and matches "deliver only if broken").
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

    /**
     * Client/UI helper: show pearl button only if we have any attachment in the container.
     * Safe on client too because container state gets synced with the menu.
     */
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
    public void markSealBroken(@NotNull String reason) {
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

            // Once seal is broken, ensure container has what snapshot says (paranoia sync)
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
    public boolean stillValid(@NotNull Player player) {
        boolean valid = player != null && !player.isRemoved();
        if (!valid) {
            LOG.info("[ScrollViewMenu] stillValid=false (player null/removed) containerId={}", this.containerId);
        }
        return valid;
    }

    /**
     * Shift-click behavior:
     *  - From attachment slots -> move to player inventory/hotbar
     *  - From player inventory/hotbar -> do nothing (cannot insert into attachment slots)
     */
    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;

            if (player == null) {
                LOG.warn("[ScrollViewMenu] quickMoveStack: player is null");
                return empty;
            }

            // Only allow shifting attachments once seal is broken (matches the design contract)
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

            // Attachments -> player inventory/hotbar
            if (index >= ATTACHMENT_START && index <= ATTACHMENT_END) {
                if (!this.moveItemStackTo(stackInSlot, PLAYER_INV_START, HOTBAR_END + 1, true)) {
                    return empty;
                }

                // If we moved anything out, reflect the container change into the snapshot
                // so we don't deliver it again on close.
                try {
                    syncSnapshotFromContainer();
                } catch (Throwable syncErr) {
                    LOG.error("[ScrollViewMenu] quickMoveStack: syncSnapshotFromContainer failed", syncErr);
                }
            } else {
                // Player -> attachments is not allowed
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
    public void removed(@NotNull Player player) {
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

            // If seal not broken -> do NOT deliver attachments.
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

            // Safety: if we didn’t parse earlier, try now.
            if (!attachmentSnapshotLoaded) {
                LOG.info("[ScrollViewMenu] removed: late snapshot load attempt (containerId={})", this.containerId);
                loadAttachmentSnapshotFromHeldScroll(player);
                syncContainerFromSnapshot();
            }

            // VERY IMPORTANT: before delivering on close, sync snapshot from the container
            // so anything the player already took via pearl inventory does NOT get delivered again.
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
                if (stack == null || stack.isEmpty()) {
                    continue;
                }

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
                            LOG.warn("[ScrollViewMenu] Deliver fallback i={} -> FAILED to drop remainder: {} x{} (lost unless another system recovers it)",
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

    /**
     * Push snapshot -> attachmentContainer slots (first 9 items).
     * Server-only intended; client will receive slot sync via menu.
     */
    private void syncContainerFromSnapshot() {
        try {
            // Fill up to 9 slots from snapshot
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

            // If snapshot has more than 9, we preserve it (don’t delete), but it won't be visible.
            if (attachmentSnapshot.size() > ATTACHMENT_SLOT_COUNT) {
                LOG.warn("[ScrollViewMenu] syncContainerFromSnapshot: snapshotSize={} > {} (extra attachments hidden but preserved for delivery-on-close)",
                        attachmentSnapshot.size(),
                        ATTACHMENT_SLOT_COUNT);
            }

        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] syncContainerFromSnapshot failed", t);
        }
    }

    /**
     * Pull attachmentContainer slots -> snapshot (first 9 become authoritative).
     * This is the key anti-dupe: items removed by the player are removed from snapshot too.
     *
     * We intentionally keep any "overflow" snapshot entries (>9) untouched, since they are not
     * interactable in UI anyway and are still meant for delivery-on-close.
     */
    private void syncSnapshotFromContainer() {
        try {
            // Ensure snapshotLoaded flag stays consistent with original semantics
            if (!attachmentSnapshotLoaded) {
                attachmentSnapshotLoaded = true;
            }

            // Shrink snapshot first 9 slots to exactly match container
            for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
                ItemStack c = attachmentContainer.getItem(i);
                ItemStack newVal = (c != null && !c.isEmpty()) ? c.copy() : ItemStack.EMPTY;

                if (i < attachmentSnapshot.size()) {
                    attachmentSnapshot.set(i, newVal);
                } else {
                    attachmentSnapshot.add(newVal);
                }
            }

            // Clean empties in the first 9 so delivery loop is cleaner
            // (but keep ordering stable enough for logs).
            // We'll do a conservative compaction:
            List<ItemStack> rebuilt = new ArrayList<>(attachmentSnapshot.size());
            for (int i = 0; i < attachmentSnapshot.size(); i++) {
                ItemStack s = attachmentSnapshot.get(i);
                if (s != null && !s.isEmpty()) {
                    rebuilt.add(s);
                } else {
                    // preserve overflow indices? no strong need; but keep it simple:
                    // skip empties so delivery doesn’t waste time.
                }
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
    // Original snapshot parsing logic (PRESERVED)
    // ---------------------------------------------------------------------

    private void loadAttachmentSnapshotFromHeldScroll(@NotNull Player player) {
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

            CustomData customData = hand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag root = customData.copyTag();
            if (root == null || root.isEmpty()) {
                LOG.info("[ScrollViewMenu] Snapshot load: CUSTOM_DATA root missing/empty");
                return;
            }

            if (!root.contains("SealedScroll", CompoundTag.TAG_COMPOUND)) {
                LOG.info("[ScrollViewMenu] Snapshot load: SealedScroll compound missing");
                return;
            }

            CompoundTag seal = root.getCompound("SealedScroll");
            if (!seal.contains("Attachments", ListTag.TAG_LIST)) {
                LOG.info("[ScrollViewMenu] Snapshot load: Attachments list missing");
                return;
            }

            ListTag list = seal.getList("Attachments", CompoundTag.TAG_COMPOUND);
            if (list == null || list.isEmpty()) {
                LOG.info("[ScrollViewMenu] Snapshot load: Attachments list empty");
                return;
            }

            int parsed = 0;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag stackTag = list.getCompound(i);
                if (stackTag == null || stackTag.isEmpty()) {
                    continue;
                }

                ItemStack rebuilt = rebuildStackFromAttachmentTag(stackTag);
                if (rebuilt.isEmpty()) {
                    continue;
                }

                attachmentSnapshot.add(rebuilt);
                parsed++;

                LOG.info("[ScrollViewMenu] Snapshot load: parsed i={} -> {} x{} hasCustomData={}",
                        i,
                        BuiltInRegistries.ITEM.getKey(rebuilt.getItem()),
                        rebuilt.getCount(),
                        rebuilt.has(DataComponents.CUSTOM_DATA));
            }

            LOG.info("[ScrollViewMenu] Snapshot load complete: parsed={} snapshotSize={} containerId={}",
                    parsed, attachmentSnapshot.size(), this.containerId);
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] loadAttachmentSnapshotFromHeldScroll failed", t);
        }
    }

    private static @NotNull ItemStack rebuildStackFromAttachmentTag(@NotNull CompoundTag tag) {
        try {
            if (!tag.contains("id")) {
                return ItemStack.EMPTY;
            }

            String idStr = tag.getString("id");
            if (idStr == null || idStr.isBlank()) {
                return ItemStack.EMPTY;
            }

            ResourceLocation id = ResourceLocation.tryParse(idStr);
            if (id == null) {
                LOG.warn("[ScrollViewMenu] rebuildStackFromAttachmentTag: invalid item id '{}'", idStr);
                return ItemStack.EMPTY;
            }

            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                LOG.warn("[ScrollViewMenu] rebuildStackFromAttachmentTag: item '{}' not found (AIR); skipping", id);
                return ItemStack.EMPTY;
            }

            int count = 1;
            try {
                if (tag.contains("Count")) {
                    count = tag.getInt("Count");
                }
            } catch (Throwable ignored) {
                count = 1;
            }
            if (count <= 0) count = 1;

            ItemStack stack = new ItemStack(item, count);

            try {
                if (tag.contains("CustomData", CompoundTag.TAG_COMPOUND)) {
                    CompoundTag cd = tag.getCompound("CustomData");
                    if (cd != null && !cd.isEmpty()) {
                        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(cd.copy()));
                    }
                }
            } catch (Throwable tCd) {
                LOG.error("[ScrollViewMenu] rebuildStackFromAttachmentTag: failed to apply CustomData for item '{}'", id, tCd);
            }

            return stack;
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] rebuildStackFromAttachmentTag failed", t);
            return ItemStack.EMPTY;
        }
    }

    // ---------------------------------------------------------------------
    // Original delivery helpers (PRESERVED)
    // ---------------------------------------------------------------------

    private static boolean tryAddWholeStack(@NotNull Player player, @NotNull ItemStack stack) {
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

    private static boolean dropOrSpawnAtPlayer(@NotNull Player player, @NotNull ItemStack stack) {
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

    private static boolean spawnItemEntityAtPlayer(@NotNull Player player, @NotNull ItemStack stack) {
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

    private static @NotNull String safePlayerName(@NotNull Player player) {
        try {
            return player.getGameProfile().getName();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
