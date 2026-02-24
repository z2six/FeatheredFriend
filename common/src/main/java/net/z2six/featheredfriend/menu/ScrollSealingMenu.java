// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/ScrollSealingMenu.java
package net.z2six.featheredfriend.menu;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.z2six.featheredfriend.menu.ScrollAttachmentProvider;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/neoforge/menu/ScrollSealingMenu.java
 *
 * ScrollSealingMenu
 *
 * Backend for the Scroll Sealing GUI.
 *
 * Contains:
 *  - 9-slot "attachment" bar used by the EnderPearlInventoryScreen (indices 0..8).
 *  - Player inventory 3x9 (indices 9..35).
 *  - Player hotbar 1x9 (indices 36..44).
 *
 * Behaviour:
 *  - Items placed into the attachment bar stay in this menu while switching between
 *    ScrollSealingScreen and EnderPearlInventoryScreen.
 *  - When the container is actually closed (e.g. player ESC from ScrollSealingScreen),
 *    all attachment items are refunded to the player (or dropped if inventory is full),
 *    UNLESS suppressAttachmentRefundOnClose is set.
 *
 * It also maintains client-side text state for:
 *  - date text
 *  - recipient line
 *  - message body
 *  - signature text
 *
 * Text state is currently client-only and unsynced; it's just to preserve what the
 * player typed while swapping between screens.
 */
public class ScrollSealingMenu extends AbstractContainerMenu implements ScrollAttachmentProvider {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Attachment / inventory slot layout
    // ---------------------------------------------------------------------

    // 9 attachment slots (input items to be sent with the scroll)
    private static final int ATTACHMENT_SLOT_COUNT = 9;

    // Slot index layout
    public static final int ATTACHMENT_START = 0;
    public static final int ATTACHMENT_END = ATTACHMENT_START + ATTACHMENT_SLOT_COUNT - 1; // 0..8

    public static final int PLAYER_INV_START = ATTACHMENT_END + 1; // 9
    public static final int PLAYER_INV_END = PLAYER_INV_START + 27 - 1; // 9..35 (3x9)

    public static final int HOTBAR_START = PLAYER_INV_END + 1; // 36
    public static final int HOTBAR_END = HOTBAR_START + 9 - 1; // 36..44

    private final Container attachmentContainer;
    private final Inventory playerInventory;

    /**
     * When true, removed(Player) will NOT refund attachment items to the player.
     * This is set by server-side sealing logic (WaxSealPacket) after it has
     * already captured attachments into the sealed scroll and cleared slots.
     */
    private boolean suppressAttachmentRefundOnClose = false;
    private long lastMissingPearlMessageGameTime = Long.MIN_VALUE;

    // ---------------------------------------------------------------------
    // Client-side text state (not synced to server yet)
    // ---------------------------------------------------------------------

    private String clientDateText = "";
    private String clientRecipientText = "";
    private String clientMessageText = "";
    private String clientSignatureText = "";

    // Storing recipient UUID while Pearl menu
    private String clientRecipientUUID = "";

    // ---------------------------------------------------------------------
    // Client-side animation state hints
    // ---------------------------------------------------------------------

    /**
     * When true, the next ScrollSealingScreen that opens for this menu should
     * skip its intro animation and go straight to the "fully open" state.
     *
     * This is set by EnderPearlInventoryScreen when closing back to the scroll GUI.
     */
    private boolean clientSkipIntroAnimation = false;

    /**
     * Client-side only: whether slots should be rendered / interacted with by the screen.
     *
     * On Forge 1.20.1 we can't override AbstractContainerScreen#renderSlot (it's not overrideable),
     * so we toggle visibility via Slot#isActive() instead.
     */
    private boolean clientSlotsVisible = true;

    public ScrollSealingMenu(int containerId, Inventory playerInventory) {
        super(Services.PLATFORM.getScrollSealingMenuType(), containerId);
        this.playerInventory = playerInventory;
        this.attachmentContainer = new SimpleContainer(ATTACHMENT_SLOT_COUNT);

        LOG.debug("[ScrollSealingMenu] Creating menu id={} for player={}",
                containerId, playerInventory.player.getGameProfile().getName());

        // -----------------------------------------------------------------
        // Attachment bar slots (0..8) — 1x9 top row in inventory.png
        //
        // Left-hand item slot:
        //  - Slot 0: x = 8,  y = 17
        //  - Slot 1: x = 26, y = 17
        //  => x = 8 + 18 * i, y = 17
        // -----------------------------------------------------------------
        for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
            int x = 8 + (i * 18);
            int y = 17;
            this.addSlot(new AttachmentSlot(this.attachmentContainer, i, x, y));
        }

        // -----------------------------------------------------------------
        // Player inventory (3x9, indices 9..35) in inventory.png
        //
        // Top-left item slot in 3x9 grid:
        //  - x = 8,  y = 49
        // Second slot on 2nd row:
        //  - x = 26, y = 67
        // => rows: y = 49, 67, 85 (49 + 18 * row)
        //    cols: x = 8 + 18 * col
        // -----------------------------------------------------------------
        for (int row = 0; row < 3; ++row) {
            int y = 49 + row * 18;
            for (int col = 0; col < 9; ++col) {
                int index = col + row * 9 + 9; // player inventory index
                int x = 8 + col * 18;
                this.addSlot(new ClientVisibleSlot(playerInventory, index, x, y));
            }
        }

        // -----------------------------------------------------------------
        // Player hotbar (1x9, indices 36..44) in inventory.png
        //
        // First item slot in action bar:
        //  - x = 8,  y = 107
        // Second:
        //  - x = 26, y = 107
        // => x = 8 + 18 * col, y = 107
        // -----------------------------------------------------------------
        for (int col = 0; col < 9; ++col) {
            int x = 8 + col * 18;
            int y = 107;
            this.addSlot(new ClientVisibleSlot(playerInventory, col, x, y));
        }

        LOG.debug("[ScrollSealingMenu] Slot layout: attachment[{}..{}], inv[{}..{}], hotbar[{}..{}]",
                ATTACHMENT_START, ATTACHMENT_END,
                PLAYER_INV_START, PLAYER_INV_END,
                HOTBAR_START, HOTBAR_END);
    }

    @Override
    public boolean stillValid(Player player) {
        // Item-based GUI; always valid while open.
        return true;
    }

    public void setClientRecipientUUID(String uuid) {
        this.clientRecipientUUID = uuid != null ? uuid : "";
    }

    public String getClientRecipientUUID() {
        return this.clientRecipientUUID;
    }

    public void setClientSlotsVisible(boolean visible) {
        this.clientSlotsVisible = visible;
    }

    public boolean isClientSlotsVisible() {
        return this.clientSlotsVisible;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;
            if (index < 0 || index >= this.slots.size()) {
                LOG.warn("[ScrollSealingMenu] quickMoveStack: index {} outside slots size {}", index, this.slots.size());
                return empty;
            }

            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) {
                return empty;
            }

            ItemStack stackInSlot = slot.getItem();
            ItemStack original = stackInSlot.copy();

            // From attachment bar -> move to player inventory/hotbar
            if (index >= ATTACHMENT_START && index <= ATTACHMENT_END) {
                if (!this.moveItemStackTo(stackInSlot, PLAYER_INV_START, HOTBAR_END + 1, true)) {
                    return empty;
                }
            }
            // From player inventory/hotbar -> move to attachment bar
            else if (index >= PLAYER_INV_START && index <= HOTBAR_END) {
                if (!hasEnderPearlInInventory()) {
                    notifyMissingEnderPearl(true);
                    return empty;
                }
                if (!this.moveItemStackTo(stackInSlot, ATTACHMENT_START, ATTACHMENT_END + 1, false)) {
                    return empty;
                }
            } else {
                LOG.warn("[ScrollSealingMenu] quickMoveStack: index {} outside known ranges", index);
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
            LOG.error("[ScrollSealingMenu] quickMoveStack failed for index={}", index, t);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);

        try {
            if (player == null) {
                LOG.warn("[ScrollSealingMenu] removed: player is null, skipping attachment refund");
                return;
            }

            if (player.level().isClientSide) {
                // Only refund on the logical server.
                return;
            }

            // If sealing logic has already captured and cleared attachments into
            // the sealed scroll, we should NOT refund them a second time.
            if (this.suppressAttachmentRefundOnClose) {
                LOG.debug("[ScrollSealingMenu] removed: suppressAttachmentRefundOnClose=true, skipping attachment refund");

                // As a safety measure, ensure attachment slots are empty server-side.
                for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
                    this.attachmentContainer.setItem(i, ItemStack.EMPTY);
                }
                return;
            }

            LOG.debug("[ScrollSealingMenu] removed: refunding attachment items to player={}",
                    player.getGameProfile().getName());

            for (int i = 0; i < ATTACHMENT_SLOT_COUNT; i++) {
                ItemStack stack = this.attachmentContainer.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }

                this.attachmentContainer.setItem(i, ItemStack.EMPTY);

                boolean added = player.addItem(stack);
                if (!added) {
                    // Inventory full, drop safely at player's feet.
                    player.drop(stack, false);
                }
            }
        } catch (Throwable t) {
            LOG.error("[ScrollSealingMenu] removed failed while refunding attachments", t);
        }
    }

    // ---------------------------------------------------------------------
    // ScrollAttachmentProvider implementation
    // ---------------------------------------------------------------------

    @Override
    public int getAttachmentSlotCount() {
        return ATTACHMENT_SLOT_COUNT;
    }

    @Override
    public @NotNull ItemStack getAttachmentStack(int index) {
        try {
            if (index < 0 || index >= ATTACHMENT_SLOT_COUNT) {
                LOG.warn("[ScrollSealingMenu] getAttachmentStack: index {} out of range 0..{}", index, ATTACHMENT_SLOT_COUNT - 1);
                return ItemStack.EMPTY;
            }
            ItemStack stack = this.attachmentContainer.getItem(index);
            return (stack != null) ? stack : ItemStack.EMPTY;
        } catch (Throwable t) {
            LOG.error("[ScrollSealingMenu] getAttachmentStack failed for index={}", index, t);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void clearAttachmentSlot(int index) {
        try {
            if (index < 0 || index >= ATTACHMENT_SLOT_COUNT) {
                LOG.warn("[ScrollSealingMenu] clearAttachmentSlot: index {} out of range 0..{}", index, ATTACHMENT_SLOT_COUNT - 1);
                return;
            }
            this.attachmentContainer.setItem(index, ItemStack.EMPTY);
        } catch (Throwable t) {
            LOG.error("[ScrollSealingMenu] clearAttachmentSlot failed for index={}", index, t);
        }
    }

    @Override
    public void setSuppressAttachmentRefundOnClose(boolean suppress) {
        if (this.suppressAttachmentRefundOnClose != suppress) {
            LOG.debug("[ScrollSealingMenu] setSuppressAttachmentRefundOnClose: {} -> {}",
                    this.suppressAttachmentRefundOnClose, suppress);
        }
        this.suppressAttachmentRefundOnClose = suppress;
    }

    @Override
    public boolean isSuppressAttachmentRefundOnClose() {
        return this.suppressAttachmentRefundOnClose;
    }

    // ---------------------------------------------------------------------
    // Accessors for containers
    // ---------------------------------------------------------------------

    public Container getAttachmentContainer() {
        return this.attachmentContainer;
    }

    public Inventory getPlayerInventory() {
        return this.playerInventory;
    }

    // ---------------------------------------------------------------------
    // Client text state accessors
    // ---------------------------------------------------------------------

    public String getClientDateText() {
        return clientDateText;
    }

    public void setClientDateText(String clientDateText) {
        String safe = clientDateText != null ? clientDateText : "";
        if (!safe.equals(this.clientDateText)) {
            LOG.debug("[ScrollSealingMenu] setClientDateText '{}'", safe);
        }
        this.clientDateText = safe;
    }

    public String getClientRecipientText() {
        return clientRecipientText;
    }

    public void setClientRecipientText(String clientRecipientText) {
        String safe = clientRecipientText != null ? clientRecipientText : "";
        if (!safe.equals(this.clientRecipientText)) {
            LOG.debug("[ScrollSealingMenu] setClientRecipientText '{}'", safe);
        }
        this.clientRecipientText = safe;
    }

    public String getClientMessageText() {
        return clientMessageText;
    }

    public void setClientMessageText(String clientMessageText) {
        String safe = clientMessageText != null ? clientMessageText : "";
        if (!safe.equals(this.clientMessageText)) {
            LOG.debug("[ScrollSealingMenu] setClientMessageText (len={})", safe.length());
        }
        this.clientMessageText = safe;
    }

    public String getClientSignatureText() {
        return clientSignatureText;
    }

    public void setClientSignatureText(String clientSignatureText) {
        String safe = clientSignatureText != null ? clientSignatureText : "";
        if (!safe.equals(this.clientSignatureText)) {
            LOG.debug("[ScrollSealingMenu] setClientSignatureText '{}'", safe);
        }
        this.clientSignatureText = safe;
    }

    // ---------------------------------------------------------------------
    // Client animation state accessors
    // ---------------------------------------------------------------------

    public boolean isClientSkipIntroAnimation() {
        return clientSkipIntroAnimation;
    }

    public void setClientSkipIntroAnimation(boolean clientSkipIntroAnimation) {
        if (this.clientSkipIntroAnimation != clientSkipIntroAnimation) {
            LOG.debug("[ScrollSealingMenu] setClientSkipIntroAnimation {}", clientSkipIntroAnimation);
        }
        this.clientSkipIntroAnimation = clientSkipIntroAnimation;
    }

    private boolean hasEnderPearlInInventory() {
        try {
            Player player = this.playerInventory != null ? this.playerInventory.player : null;
            if (player == null) {
                return false;
            }

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
            LOG.error("[ScrollSealingMenu] hasEnderPearlInInventory failed", t);
        }
        return false;
    }

    private void notifyMissingEnderPearl(boolean force) {
        try {
            Player player = this.playerInventory != null ? this.playerInventory.player : null;
            if (player == null || player.level().isClientSide) {
                return;
            }

            long now = player.level().getGameTime();
            if (!force && (now - this.lastMissingPearlMessageGameTime) < 20L) {
                return;
            }
            this.lastMissingPearlMessageGameTime = now;

            player.displayClientMessage(
                    Component.translatable("message.featheredfriend.scroll_sealing.attachments.requires_ender_pearl"),
                    true
            );
        } catch (Throwable t) {
            LOG.error("[ScrollSealingMenu] notifyMissingEnderPearl failed", t);
        }
    }

    private final class AttachmentSlot extends Slot {
        private AttachmentSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean isActive() {
            return ScrollSealingMenu.this.clientSlotsVisible;
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            if (!hasEnderPearlInInventory()) {
                notifyMissingEnderPearl(false);
                return false;
            }
            return super.mayPlace(stack);
        }
    }

    private final class ClientVisibleSlot extends Slot {
        private ClientVisibleSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean isActive() {
            return ScrollSealingMenu.this.clientSlotsVisible;
        }
    }
}
