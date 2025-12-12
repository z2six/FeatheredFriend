// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/ScrollViewMenu.java
package net.z2six.featheredfriend.neoforge.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
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
 * Current step:
 *  - On server-side menu close (removed):
 *      1) Try to give attachment stacks to the player inventory
 *      2) If inventory is full, drop the remainder at the player's feet
 *
 * IMPORTANT:
 *  - We do NOT mutate the scroll NBT yet, so reopening the same sealed scroll
 *    will still re-give attachments until we implement “consume/clear NBT”.
 */
public class ScrollViewMenu extends AbstractContainerMenu {

    private static final Logger LOG = LogUtils.getLogger();

    private final Inventory playerInventory;

    // Snapshot of attachments parsed from the sealed scroll NBT.
    private final List<ItemStack> attachmentSnapshot = new ArrayList<>();
    private boolean attachmentSnapshotLoaded = false;

    // Prevent double-processing in edge cases.
    private boolean attachmentsDeliveredThisSession = false;

    // Debug: what scroll stack we read from.
    private ItemStack sourceScrollStackSnapshot = ItemStack.EMPTY;

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

        // Only parse attachments on the logical server.
        try {
            if (playerInventory.player != null && !playerInventory.player.level().isClientSide) {
                loadAttachmentSnapshotFromHeldScroll(playerInventory.player);
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

    @Override
    public boolean stillValid(@NotNull Player player) {
        boolean valid = player != null && !player.isRemoved();
        if (!valid) {
            LOG.info("[ScrollViewMenu] stillValid=false (player null/removed) containerId={}", this.containerId);
        }
        return valid;
    }

    @Override
    @NotNull
    public ItemStack quickMoveStack(@NotNull Player player, int index) {
        LOG.debug("[ScrollViewMenu] quickMoveStack called (index={}) but menu has no slots; returning EMPTY", index);
        return ItemStack.EMPTY;
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
            LOG.info("[ScrollViewMenu] removed() fired containerId={} player={} side={} deliveredAlready={} snapshotLoaded={} snapshotSize={} carriedEmpty={}",
                    this.containerId,
                    safePlayerName(player),
                    clientSide ? "CLIENT" : "SERVER",
                    attachmentsDeliveredThisSession,
                    attachmentSnapshotLoaded,
                    attachmentSnapshot.size(),
                    this.getCarried().isEmpty());

            if (clientSide) {
                // Never deliver items from client.
                return;
            }

            if (attachmentsDeliveredThisSession) {
                LOG.info("[ScrollViewMenu] removed: already delivered this session -> skip (containerId={})", this.containerId);
                return;
            }
            attachmentsDeliveredThisSession = true;

            // Safety: if for some reason we didn’t parse earlier, try now.
            if (!attachmentSnapshotLoaded) {
                LOG.info("[ScrollViewMenu] removed: late snapshot load attempt (containerId={})", this.containerId);
                loadAttachmentSnapshotFromHeldScroll(player);
            }

            if (attachmentSnapshot.isEmpty()) {
                LOG.info("[ScrollViewMenu] removed: no attachments to deliver (containerId={})", this.containerId);
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

                // Work on a copy so our snapshot remains stable.
                ItemStack remaining = stack.copy();

                LOG.info("[ScrollViewMenu] Deliver attempt i={} -> {} x{}",
                        i,
                        BuiltInRegistries.ITEM.getKey(remaining.getItem()),
                        remaining.getCount());

                // Try to insert the entire stack into the player inventory.
                boolean addedAll = tryAddWholeStack(player, remaining);

                if (addedAll) {
                    fullyAddedStacks++;
                    LOG.info("[ScrollViewMenu] Deliver success i={} -> added to inventory: {} x{}",
                            i,
                            BuiltInRegistries.ITEM.getKey(stack.getItem()),
                            stack.getCount());
                } else {
                    // If we couldn't add it all, whatever is left must be dropped.
                    // Note: tryAddWholeStack will have reduced 'remaining' appropriately if partial insertion happened.
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
                        // Partial insertion ended up inserting all (should be rare), but handle it.
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

            // Clear snapshot to avoid accidental reuse.
            attachmentSnapshot.clear();
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] removed failed", t);
        }
    }

    /**
     * Try to add the entire stack to the player's inventory.
     *
     * If only partially inserted, this method should reduce 'stack' to the remainder.
     *
     * @return true if the entire stack was inserted; false otherwise.
     */
    private static boolean tryAddWholeStack(@NotNull Player player, @NotNull ItemStack stack) {
        try {
            if (stack.isEmpty()) {
                return true;
            }

            // Player#addItem returns true if something was added, not necessarily all.
            // The safest “whole stack” approach is:
            //  - try addItem(stack)
            //  - if it returns false, nothing was added (inventory full)
            //  - if it returns true, stack MAY have been reduced (remainder stays in 'stack')
            boolean addedSome = player.addItem(stack);

            if (!addedSome) {
                LOG.info("[ScrollViewMenu] tryAddWholeStack: inventory rejected entire stack: {} x{}",
                        BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        stack.getCount());
                return false;
            }

            if (stack.isEmpty()) {
                // Fully inserted (stack consumed)
                return true;
            }

            // Partial insertion
            LOG.info("[ScrollViewMenu] tryAddWholeStack: partial insert, remainder now {} x{}",
                    BuiltInRegistries.ITEM.getKey(stack.getItem()),
                    stack.getCount());
            return false;
        } catch (Throwable t) {
            LOG.error("[ScrollViewMenu] tryAddWholeStack failed; leaving stack as remainder to be dropped", t);
            return false;
        }
    }

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
