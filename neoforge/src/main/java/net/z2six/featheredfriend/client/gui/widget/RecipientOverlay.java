// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/widget/RecipientOverlay.java
package net.z2six.featheredfriend.client.gui.widget;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/widget/RecipientOverlay.java
 *
 * RecipientOverlay
 *
 * Lightweight GUI overlay for picking a recipient:
 *  - Renders a small panel with:
 *      * filter text box (using MultiLineScrollTextWidget for consistent styling)
 *      * scrollable-ish list (clamped to a fixed number of lines)
 *  - Handles mouse + keyboard interaction.
 *  - Calls back into the parent screen via onRecipientSelected when the user
 *    picks a name (click or Enter).
 */
public class RecipientOverlay {

    private static final Logger LOG = LogUtils.getLogger();

    private final Minecraft minecraft;
    private final Font font;

    private final int x;
    private final int y;
    private final int width;
    private final int height;

    private static final int LIST_TOP_MARGIN = 18;
    private static final int ENTRY_HEIGHT = 10;

    private final MultiLineScrollTextWidget filterWidget;

    private final List<String> allRecipients = new ArrayList<>();
    private final List<String> filteredRecipients = new ArrayList<>();

    private boolean visible = false;
    private int hoveredIndex = -1;
    private int selectedIndex = -1;

    private final Consumer<String> onRecipientSelected;

    public RecipientOverlay(@NotNull Minecraft minecraft,
                            @NotNull Font font,
                            int x,
                            int y,
                            int width,
                            int height,
                            @NotNull Consumer<String> onRecipientSelected) {

        this.minecraft = minecraft;
        this.font = font;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.onRecipientSelected = onRecipientSelected;

        int filterHeight = this.font.lineHeight + 4;

        this.filterWidget = new MultiLineScrollTextWidget(
                this.font,
                this.x + 4,
                this.y + 4,
                this.width - 8,
                filterHeight,
                64,
                1,
                Component.literal("Filter players...")
        );
        this.filterWidget.setEditable(true);
        this.filterWidget.setFocused(false);

        LOG.debug("[RecipientOverlay] Created at ({},{}) size=({},{})", x, y, width, height);
    }

    // -------------------------------------------------------------------------
    // State management
    // -------------------------------------------------------------------------

    public void setRecipients(@NotNull Collection<String> names) {
        this.allRecipients.clear();
        this.allRecipients.addAll(names);
        updateFilteredRecipients();
        LOG.debug("[RecipientOverlay] setRecipients: {} names", allRecipients.size());
    }

    public void openIfPossible() {
        if (this.allRecipients.isEmpty()) {
            LOG.debug("[RecipientOverlay] Not opening overlay; no known recipients");
            this.visible = false;
            return;
        }
        this.visible = true;

        // Focus filter immediately so the user can type straight away.
        this.filterWidget.setText("");
        this.filterWidget.setFocused(true);
        updateFilteredRecipients();

        LOG.debug("[RecipientOverlay] Overlay opened with {} filtered entries", filteredRecipients.size());
    }

    public void close() {
        if (this.visible) {
            LOG.debug("[RecipientOverlay] Overlay closed");
        }
        this.visible = false;
        this.hoveredIndex = -1;
        this.selectedIndex = -1;
        this.filterWidget.setFocused(false);
    }

    public boolean isVisible() {
        return visible;
    }

    public void tick() {
        // Keep caret blinking, etc.
        this.filterWidget.tick();
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        int right = x + width;
        int bottom = y + height;

        // Background
        guiGraphics.fill(
                x,
                y,
                right,
                bottom,
                0xE0F0E0D0
        );

        // Border (1px outline)
        int borderColor = 0xFF000000;
        // Top
        guiGraphics.fill(x, y, right, y + 1, borderColor);
        // Bottom
        guiGraphics.fill(x, bottom - 1, right, bottom, borderColor);
        // Left
        guiGraphics.fill(x, y, x + 1, bottom, borderColor);
        // Right
        guiGraphics.fill(right - 1, y, right, bottom, borderColor);

        // Filter field (uses our own widget: placeholder disappears as soon as it's focused & you type)
        filterWidget.render(guiGraphics, mouseX, mouseY, partialTick);

        // List
        int listX = x + 4;
        int listY = y + LIST_TOP_MARGIN;
        int maxEntries = Math.min(getMaxEntries(), filteredRecipients.size());

        hoveredIndex = -1;

        for (int i = 0; i < maxEntries; i++) {
            int entryY = listY + i * ENTRY_HEIGHT;
            String name = filteredRecipients.get(i);

            boolean isHovered = mouseX >= listX && mouseX <= right - 4
                    && mouseY >= entryY && mouseY <= entryY + ENTRY_HEIGHT;

            if (isHovered) {
                hoveredIndex = i;
            }

            boolean isSelected = (i == selectedIndex);

            int bgColor = 0x00000000;
            if (isSelected) {
                bgColor = 0x802080A0;
            } else if (isHovered) {
                bgColor = 0x40C0C0C0;
            }

            if (bgColor != 0x00000000) {
                guiGraphics.fill(
                        listX,
                        entryY,
                        right - 4,
                        entryY + ENTRY_HEIGHT,
                        bgColor
                );
            }

            guiGraphics.drawString(
                    this.font,
                    name,
                    listX + 2,
                    entryY + 1,
                    0x000000,
                    false
            );
        }
    }

    private int getMaxEntries() {
        return Math.max(0, (height - LIST_TOP_MARGIN - 4) / ENTRY_HEIGHT);
    }

    // -------------------------------------------------------------------------
    // Input handling
    // -------------------------------------------------------------------------

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) {
            return false;
        }

        // Click inside overlay area?
        if (!isMouseInside(mouseX, mouseY)) {
            return false;
        }

        // First let filter field handle the click
        if (filterWidget.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // Then check list entries
        int listX = x + 4;
        int listY = y + LIST_TOP_MARGIN;
        int right = x + width;
        int maxEntries = Math.min(getMaxEntries(), filteredRecipients.size());

        for (int i = 0; i < maxEntries; i++) {
            int entryY = listY + i * ENTRY_HEIGHT;
            if (mouseX >= listX && mouseX <= right - 4
                    && mouseY >= entryY && mouseY <= entryY + ENTRY_HEIGHT) {

                selectAndApply(i);
                return true;
            }
        }

        return true; // click absorbed by overlay even if it didn't hit specific controls
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) {
            return false;
        }

        int maxEntries = Math.min(getMaxEntries(), filteredRecipients.size());

        // Up/down/enter handling
        switch (keyCode) {
            case 264 -> { // GLFW_KEY_DOWN
                if (maxEntries > 0) {
                    if (selectedIndex < 0) selectedIndex = 0;
                    else selectedIndex = Math.min(selectedIndex + 1, maxEntries - 1);
                }
                return true;
            }
            case 265 -> { // GLFW_KEY_UP
                if (maxEntries > 0) {
                    if (selectedIndex < 0) selectedIndex = 0;
                    else selectedIndex = Math.max(selectedIndex - 1, 0);
                }
                return true;
            }
            case 257, 335 -> { // GLFW_KEY_ENTER & GLFW_KEY_KP_ENTER
                if (selectedIndex >= 0 && selectedIndex < maxEntries) {
                    selectAndApply(selectedIndex);
                    return true;
                }
                return true;
            }
            default -> {
                // fall through to filter field
            }
        }

        // Other keys -> filter field
        if (filterWidget.keyPressed(keyCode, scanCode, modifiers)) {
            updateFilteredRecipients();
            return true;
        }

        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!visible) {
            return false;
        }

        if (filterWidget.charTyped(codePoint, modifiers)) {
            updateFilteredRecipients();
            return true;
        }

        return false;
    }

    public boolean isMouseInside(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void updateFilteredRecipients() {
        filteredRecipients.clear();
        String filter = filterWidget.getText().trim();
        if (filter.isEmpty()) {
            filteredRecipients.addAll(allRecipients);
        } else {
            String lower = filter.toLowerCase();
            for (String name : allRecipients) {
                if (name.toLowerCase().contains(lower)) {
                    filteredRecipients.add(name);
                }
            }
        }

        selectedIndex = filteredRecipients.isEmpty() ? -1 : 0;
        LOG.debug("[RecipientOverlay] filter='{}' -> {} entries", filter, filteredRecipients.size());
    }

    private void selectAndApply(int index) {
        if (index < 0 || index >= filteredRecipients.size()) {
            return;
        }
        String name = filteredRecipients.get(index);
        try {
            onRecipientSelected.accept(name);
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] Error applying recipient '{}'", name, t);
        }
    }
}
