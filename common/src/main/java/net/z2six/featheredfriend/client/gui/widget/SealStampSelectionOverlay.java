// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/widget/SealStampSelectionOverlay.java
package net.z2six.featheredfriend.client.gui.widget;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.z2six.featheredfriend.platform.Services;
import net.z2six.featheredfriend.item.SealStampItem;
import net.z2six.featheredfriend.item.SealStampSlotEntry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/gui/widget/SealStampSelectionOverlay.java
 *
 * SealStampSelectionOverlay
 *
 * Lightweight client-side overlay that:
 *  - Scans the local player's inventory for SealStampItem stacks.
 *  - Shows up to 9 item icons in a 3x3 grid.
 *  - Supports selecting a stamp (left-click) and favouriting one stamp (right-click).
 *  - Remembers the favourite stamp client-side using a simple key derived from
 *    the stamp's SealStamp custom-data component (Owner + Seed + Slices + ShapeSet).
 *
 * Behaviour:
 *  - When opened, rebuilds its entry list from the player's inventory.
 *  - If a global favourite exists and is present in the entries, auto-selects it
 *    and fires the SelectionCallback immediately.
 *  - When the user left-clicks a slot, that stamp becomes selected and the
 *    SelectionCallback is invoked.
 *  - When the user right-clicks a slot, that stamp becomes the sole favourite.
 *    Right-clicking the current favourite again clears the favourite.
 *
 * This overlay is purely client-side; it never mutates inventory contents.
 */
public final class SealStampSelectionOverlay {

    private static final Logger LOG = LogUtils.getLogger();

    // ---------------------------------------------------------------------
    // Selection callback
    // ---------------------------------------------------------------------

    public interface SelectionCallback {
        /**
         * Called when the user selects a stamp (either via auto-select of the favourite
         * or via left-click on a slot).
         *
         * @param slotIndex logical slot index:
         *                  0..35 = player inventory
         *                  37    = offhand (for now; main-hand is addressed via 0..35)
         *                  10000+ = virtual external slots (e.g. Curios)
         * @param stack     snapshot of the selected stamp stack
         */
        void onStampSelected(int slotIndex, @NotNull ItemStack stack);

        /**
         * Called when the overlay is closed without a selection.
         */
        default void onOverlayClosedWithoutSelection() {
        }
    }

    // ---------------------------------------------------------------------
    // Favourite stamp key (client-side only)
    // ---------------------------------------------------------------------

    private static final class FavoriteStampKey {
        final String owner;
        final long seed;
        final int slices;
        final int shapeSet;

        FavoriteStampKey(String owner, long seed, int slices, int shapeSet) {
            this.owner = owner;
            this.seed = seed;
            this.slices = slices;
            this.shapeSet = shapeSet;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FavoriteStampKey other)) return false;
            return this.seed == other.seed
                    && this.slices == other.slices
                    && this.shapeSet == other.shapeSet
                    && ((this.owner == null && other.owner == null)
                    || (this.owner != null && this.owner.equals(other.owner)));
        }

        @Override
        public int hashCode() {
            int result = owner != null ? owner.hashCode() : 0;
            result = 31 * result + Long.hashCode(seed);
            result = 31 * result + slices;
            result = 31 * result + shapeSet;
            return result;
        }

        @Override
        public String toString() {
            return "FavoriteStampKey{" +
                    "owner='" + owner + '\'' +
                    ", seed=" + seed +
                    ", slices=" + slices +
                    ", shapeSet=" + shapeSet +
                    '}';
        }
    }

    /**
     * Single client-side favourite, remembered for the lifetime of the game session.
     */
    private static FavoriteStampKey GLOBAL_FAVORITE = null;
    private static boolean FAVORITE_LOADED = false;

    // ---------------------------------------------------------------------
    // Entry list
    // ---------------------------------------------------------------------

    private static final class Entry {
        final int slotIndex; // 0..35 inventory, 37 offhand, 10000+ virtual external slots
        final ItemStack stack;

        Entry(int slotIndex, @NotNull ItemStack stack) {
            this.slotIndex = slotIndex;
            this.stack = stack;
        }
    }

    private final Minecraft minecraft;
    private final Font font;
    private final SelectionCallback callback;

    private int x;
    private int y;
    private final int width;
    private final int height;

    private boolean active = false;

    private final List<Entry> entries = new ArrayList<>();
    private int selectedIndex = -1;
    private int hoverIndex = -1;

    // Layout for the 3x3 grid
    private static final int GRID_PADDING = 4;
    private static final int SLOT_SIZE = 18; // spacing
    private static final int LABEL_HEIGHT = 12;
    private static final int LABEL_MARGIN_BOTTOM = 2;

    public SealStampSelectionOverlay(@NotNull Minecraft minecraft,
                                     @NotNull Font font,
                                     int x,
                                     int y,
                                     int width,
                                     int height,
                                     @NotNull SelectionCallback callback) {
        this.minecraft = minecraft;
        this.font = font;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.callback = callback;
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getWidth() {
        return this.width;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        if (!active) {
            this.hoverIndex = -1;
        }
    }

    /**
     * Rebuilds the list of SealStampItem entries from the local player's inventory.
     * Called whenever the overlay is (re)opened.
     *
     * Also auto-selects the global favourite stamp if present and invokes the callback.
     */
    public void rebuildEntriesAndAutoSelectFavorite() {
        try {
            entries.clear();
            selectedIndex = -1;
            hoverIndex = -1;

            ensureFavoriteLoaded();

            Player player = minecraft.player;
            if (player == null) {
                LOG.warn("[SealStampSelectionOverlay] rebuildEntries: player is null");
                return;
            }

            Inventory inv = player.getInventory();

            // 1) Gather stamps from main inventory 0..35
            int added = 0;
            for (int slot = 0; slot < inv.items.size() && added < 9; slot++) {
                ItemStack stack = inv.items.get(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (!(stack.getItem() instanceof SealStampItem)) {
                    continue;
                }

                entries.add(new Entry(slot, stack.copy()));
                added++;
            }

            // 2) Offhand slot as logical index 37 (if we still have capacity)
            if (added < 9) {
                ItemStack offhand = player.getOffhandItem();
                if (offhand != null && !offhand.isEmpty() && offhand.getItem() instanceof SealStampItem) {
                    entries.add(new Entry(37, offhand.copy()));
                    added++;
                }
            }

            // 3) External stamp providers (Curios, etc.) as virtual slot indices
            if (added < 9) {
                List<SealStampSlotEntry> externalEntries = Services.PLATFORM.getExternalSealStampEntries(player);
                for (SealStampSlotEntry external : externalEntries) {
                    if (added >= 9) {
                        break;
                    }
                    if (external == null) {
                        continue;
                    }

                    ItemStack extStack = external.stack();
                    if (extStack == null || extStack.isEmpty() || !(extStack.getItem() instanceof SealStampItem)) {
                        continue;
                    }

                    entries.add(new Entry(external.slotIndex(), extStack.copy()));
                    added++;
                }
            }

            LOG.debug("[SealStampSelectionOverlay] rebuildEntries: found {} SealStampItem stacks", entries.size());

            // Auto-select favourite if present
            if (GLOBAL_FAVORITE != null && !entries.isEmpty()) {
                for (int i = 0; i < entries.size(); i++) {
                    Entry e = entries.get(i);
                    FavoriteStampKey key = extractFavoriteKey(e.stack);
                    if (key != null && key.equals(GLOBAL_FAVORITE)) {
                        selectedIndex = i;
                        LOG.debug("[SealStampSelectionOverlay] Auto-selected favourite stamp at index {}: {}", i, key);
                        if (callback != null) {
                            callback.onStampSelected(e.slotIndex, e.stack.copy());
                        }
                        return;
                    }
                }
                LOG.debug("[SealStampSelectionOverlay] Favourite stamp not present in current entries");
            }
        } catch (Throwable t) {
            LOG.error("[SealStampSelectionOverlay] rebuildEntriesAndAutoSelectFavorite failed", t);
            entries.clear();
            selectedIndex = -1;
            hoverIndex = -1;
        }
    }

    public void tick() {
        // Currently stateless; kept for parity with RecipientOverlay.
    }

    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!active) {
            return;
        }

        try {
            int x0 = this.x;
            int y0 = this.y;
            int x1 = x0 + this.width;
            int y1 = y0 + this.height;

            // Background panel
            guiGraphics.fill(x0, y0, x1, y1, 0xC0000000);

            // Border
            guiGraphics.fill(x0, y0, x1, y0 + 1, 0xFFFFFFFF);
            guiGraphics.fill(x0, y1 - 1, x1, y1, 0xFFFFFFFF);
            guiGraphics.fill(x0, y0, x0 + 1, y1, 0xFFFFFFFF);
            guiGraphics.fill(x1 - 1, y0, x1, y1, 0xFFFFFFFF);

            // Label
            String labelText = Component.translatable("screen.featheredfriend.scroll_sealing.stamp_overlay.title").getString();
            int lw = font.width(labelText);
            int labelX = x0 + (this.width - lw) / 2;
            int labelY = y0 + 2;

            guiGraphics.drawString(font, labelText, labelX, labelY, 0xFFFFFF, false);

            // Grid top-left
            int gridLeft = x0 + GRID_PADDING;
            int gridTop = y0 + LABEL_HEIGHT + LABEL_MARGIN_BOTTOM + GRID_PADDING;

            hoverIndex = -1;

            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                int row = i / 3;
                int col = i % 3;

                int sx = gridLeft + col * SLOT_SIZE;
                int sy = gridTop + row * SLOT_SIZE;

                int slotX1 = sx + 16;
                int slotY1 = sy + 16;

                boolean hovered = (mouseX >= sx && mouseX < slotX1 && mouseY >= sy && mouseY < slotY1);
                if (hovered) {
                    hoverIndex = i;
                }

                // Slot background
                int bgColor = hovered ? 0x80FFFFFF : 0x40000000;
                guiGraphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, bgColor);

                // Simple inner dark fill
                guiGraphics.fill(sx, sy, sx + 16, sy + 16, 0xFF202020);

                // Render item icon
                guiGraphics.renderItem(e.stack, sx, sy);
                guiGraphics.renderItemDecorations(font, e.stack, sx, sy);

                // Selection outline
                if (i == selectedIndex) {
                    int selColor = 0xFFFFD700; // gold
                    guiGraphics.fill(sx - 1, sy - 1, sx + 17, sy, selColor);
                    guiGraphics.fill(sx - 1, sy + 16, sx + 17, sy + 17, selColor);
                    guiGraphics.fill(sx - 1, sy, sx, sy + 16, selColor);
                    guiGraphics.fill(sx + 16, sy, sx + 17, sy + 16, selColor);
                }

                // Favourite indicator
                if (isFavoriteEntry(e)) {
                    guiGraphics.drawString(font, "★", sx + 10, sy - 2, 0xFFFFD700, false);
                }
            }
        } catch (Throwable t) {
            LOG.error("[SealStampSelectionOverlay] render failed", t);
        }
    }

    /**
     * Handles mouse clicks. Returns true if the click was consumed.
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active) {
            return false;
        }

        try {
            int x0 = this.x;
            int y0 = this.y;
            int x1 = x0 + this.width;
            int y1 = y0 + this.height;

            // If click is outside panel: we let the underlying GUI handle it.
            if (mouseX < x0 || mouseX >= x1 || mouseY < y0 || mouseY >= y1) {
                return false;
            }

            // Inside overlay → we consume the click.
            int gridLeft = x0 + GRID_PADDING;
            int gridTop = y0 + LABEL_HEIGHT + LABEL_MARGIN_BOTTOM + GRID_PADDING;

            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                int row = i / 3;
                int col = i % 3;

                int sx = gridLeft + col * SLOT_SIZE;
                int sy = gridTop + row * SLOT_SIZE;
                int slotX1 = sx + 16;
                int slotY1 = sy + 16;

                if (mouseX >= sx && mouseX < slotX1 && mouseY >= sy && mouseY < slotY1) {
                    if (button == 0) {
                        // Left-click -> select stamp
                        selectedIndex = i;
                        LOG.debug("[SealStampSelectionOverlay] Slot {} selected (logical slotIndex={})", i, e.slotIndex);
                        if (callback != null) {
                            callback.onStampSelected(e.slotIndex, e.stack.copy());
                        }
                    } else if (button == 1) {
                        // Right-click -> toggle favourite
                        FavoriteStampKey key = extractFavoriteKey(e.stack);
                        if (key == null) {
                            LOG.warn("[SealStampSelectionOverlay] Right-click favourite toggle: stamp has no SealStamp data");
                        } else {
                            if (GLOBAL_FAVORITE != null && GLOBAL_FAVORITE.equals(key)) {
                                LOG.debug("[SealStampSelectionOverlay] Favourite cleared: {}", key);
                                GLOBAL_FAVORITE = null;
                            } else {
                                LOG.debug("[SealStampSelectionOverlay] Favourite set to: {}", key);
                                GLOBAL_FAVORITE = key;
                            }
                            persistFavorite();
                        }
                    }
                    return true;
                }
            }

            return true;
        } catch (Throwable t) {
            LOG.error("[SealStampSelectionOverlay] mouseClicked failed", t);
            return true;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean isFavoriteEntry(@NotNull Entry entry) {
        if (GLOBAL_FAVORITE == null) {
            return false;
        }
        FavoriteStampKey key = extractFavoriteKey(entry.stack);
        return key != null && key.equals(GLOBAL_FAVORITE);
    }

    private void ensureFavoriteLoaded() {
        if (FAVORITE_LOADED) {
            return;
        }
        FAVORITE_LOADED = true;

        String raw = Services.PLATFORM.getFavoriteStampKey();
        if (raw == null || raw.isBlank()) {
            GLOBAL_FAVORITE = null;
            return;
        }

        FavoriteStampKey key = parseFavoriteKey(raw);
        if (key == null) {
            LOG.warn("[SealStampSelectionOverlay] Failed to parse favoriteStampKey; clearing.");
            GLOBAL_FAVORITE = null;
            Services.PLATFORM.setFavoriteStampKey("");
            Services.PLATFORM.saveClientConfig();
            return;
        }

        GLOBAL_FAVORITE = key;
    }

    private void persistFavorite() {
        try {
            String encoded = (GLOBAL_FAVORITE == null) ? "" : serializeFavoriteKey(GLOBAL_FAVORITE);
            Services.PLATFORM.setFavoriteStampKey(encoded);
            Services.PLATFORM.saveClientConfig();
        } catch (Throwable t) {
            LOG.warn("[SealStampSelectionOverlay] persistFavorite failed safely: {}", t.toString());
        }
    }

    private FavoriteStampKey parseFavoriteKey(String raw) {
        try {
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 4) {
                return null;
            }
            String owner = parts[0];
            long seed = Long.parseLong(parts[1]);
            int slices = Integer.parseInt(parts[2]);
            int shapeSet = Integer.parseInt(parts[3]);
            return new FavoriteStampKey(owner.isEmpty() ? null : owner, seed, slices, shapeSet);
        } catch (Throwable t) {
            return null;
        }
    }

    private String serializeFavoriteKey(@NotNull FavoriteStampKey key) {
        String owner = (key.owner == null) ? "" : key.owner.replace("|", "/");
        return owner + "|" + key.seed + "|" + key.slices + "|" + key.shapeSet;
    }

    private FavoriteStampKey extractFavoriteKey(@NotNull ItemStack stack) {
        try {
            if (stack.isEmpty() || !(stack.getItem() instanceof SealStampItem)) {
                return null;
            }

            CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag root = data.copyTag();
            if (root == null) {
                return null;
            }

            CompoundTag seal = root.getCompound("SealStamp");
            if (seal == null || seal.isEmpty()) {
                return null;
            }

            String owner = seal.getString("Owner");
            long seed = seal.getLong("Seed");
            int slices = seal.getInt("Slices");
            int shapeSet = seal.getInt("ShapeSet");

            return new FavoriteStampKey(owner, seed, slices, shapeSet);
        } catch (Throwable t) {
            LOG.error("[SealStampSelectionOverlay] extractFavoriteKey failed", t);
            return null;
        }
    }
}
