// MainFile: common/src/main/java/net/z2six/featheredfriend/menu/ScrollAttachmentProvider.java
package net.z2six.featheredfriend.menu;

import net.minecraft.world.item.ItemStack;

/**
 * ScrollAttachmentProvider
 *
 * Small common interface that exposes attachment-slot contents for
 * scroll-sealing logic. Implemented by the loader-specific menu
 * (ScrollSealingMenu) so common networking code (WaxSealPacket)
 * can:
 *
 *  - Inspect which attachment ItemStacks are present.
 *  - Serialize them into NBT on the sealed scroll.
 *  - Clear those slots once they've been consumed.
 *  - Suppress server-side refund on menu close.
 */
public interface ScrollAttachmentProvider {

    /**
     * @return total number of attachment slots.
     */
    int getAttachmentSlotCount();

    /**
     * Returns the stack currently stored in the attachment slot at the given index.
     * Implementations should NEVER return null; use ItemStack.EMPTY instead.
     */
    ItemStack getAttachmentStack(int index);

    /**
     * Clears the attachment slot at the given index (usually to ItemStack.EMPTY).
     */
    void clearAttachmentSlot(int index);

    /**
     * When true, the menu should NOT refund leftover attachment items to the
     * player in its removed(Player) method. Used by WaxSealPacket after it
     * has already captured and consumed attachments into the sealed scroll.
     */
    void setSuppressAttachmentRefundOnClose(boolean suppress);

    /**
     * Current value of the "suppress refund" flag.
     */
    boolean isSuppressAttachmentRefundOnClose();
}
