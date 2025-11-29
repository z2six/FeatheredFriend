// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/widget/RecipientOverlay.java
package net.z2six.featheredfriend.client.gui.widget;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.z2six.featheredfriend.client.knownplayers.KnownPlayersClientCache;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/widget/RecipientOverlay.java
 *
 * RecipientOverlay
 *
 * A modern-ish overlay for choosing a player as scroll recipient.
 *
 * Responsibilities:
 *  - Shows a list of known players (online + offline) using the client cache.
 *  - Includes a filter field ("Filter players...") using MultiLineScrollTextWidget.
 *  - Renders on top of the scroll GUI with a semi-transparent background.
 *  - Invokes a callback when a player is selected.
 *  - Can be opened/closed, and knows if the mouse is over it.
 */
public final class RecipientOverlay {

    private static final Logger LOG = LogUtils.getLogger();

    public interface SelectionCallback {
        void onPlayerSelected(UUID uuid, String name);
        void onOverlayClosedWithoutSelection();
    }

    private final Minecraft minecraft;
    private final Font font;
    private final SelectionCallback callback;

    // Overlay position/size (absolute screen coordinates)
    private int x;
    private int y;
    private final int width;
    private final int height;

    private boolean active = false;

    // Layout constants (inside overlay)
    private static final int PADDING = 6;
    private static final int HEADER_HEIGHT = 12;
    private static final int FILTER_HEIGHT = 14;
    private static final int ENTRY_HEIGHT = 14;
    private static final int ENTRY_SPACING = 2;

    private final MultiLineScrollTextWidget filterField;

    private List<KnownPlayersClientCache.KnownPlayerEntry> allPlayers = new ArrayList<>();
    private List<KnownPlayersClientCache.KnownPlayerEntry> visiblePlayers = new ArrayList<>();

    public RecipientOverlay(@NotNull Minecraft minecraft,
                            @NotNull Font font,
                            int x,
                            int y,
                            int width,
                            int height,
                            @NotNull SelectionCallback callback) {
        this.minecraft = minecraft;
        this.font = font;
        this.callback = callback;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        // Filter box uses our custom widget, single-line config.
        this.filterField = new MultiLineScrollTextWidget(
                font,
                x + PADDING,
                y + PADDING + HEADER_HEIGHT, // below header row (even if we don't draw a title, we keep spacing)
                width - PADDING * 2,
                FILTER_HEIGHT,
                64,
                1,
                Component.literal("Filter players...")
        );

        // Make the filter text white, without affecting any other widgets.
        try {
            this.filterField.setTextColor(0xFFFFFFFF);
            LOG.debug("[RecipientOverlay] Set filterField text color to 0xFFFFFFFF (white)");
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] Failed to set filterField text color", t);
        }

        LOG.debug("[RecipientOverlay] Created at ({},{}) size=({},{})", x, y, width, height);
    }

    // ---------------------------------------------------------------------
    // External API
    // ---------------------------------------------------------------------

    public boolean isActive() {
        return active;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
        // Also move filter field
        this.filterField.setX(x + PADDING);
        this.filterField.setY(y + PADDING + HEADER_HEIGHT);
    }

    public void open() {
        try {
            this.active = true;
            LOG.debug("[RecipientOverlay] open()");

            // Clear filter text and placeholder
            this.filterField.setText("");
            this.filterField.setFocused(true);

            // Refresh players from cache (also seeds from connection)
            reloadFromClientCache();
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] open() failed", t);
        }
    }

    public void close(boolean notifyCallback) {
        if (!this.active) {
            return;
        }
        try {
            this.active = false;
            LOG.debug("[RecipientOverlay] close() notifyCallback={}", notifyCallback);
            this.filterField.setFocused(false);

            if (notifyCallback && this.callback != null) {
                this.callback.onOverlayClosedWithoutSelection();
            }
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] close() failed", t);
        }
    }

    public boolean isMouseOverOverlay(double mouseX, double mouseY) {
        if (!active) return false;
        return mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }

    public void tick() {
        if (!active) return;
        try {
            this.filterField.tick();
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] tick() failed", t);
        }
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!active) return false;

        try {
            boolean handled = this.filterField.charTyped(codePoint, modifiers);
            if (handled) {
                updateFilter();
                return true;
            }
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] charTyped failed", t);
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active) return false;

        try {
            // ESC closes overlay only (screen stays open)
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                close(true);
                return true;
            }

            // Eat 'E' so it does not close the container while filtering
            if (keyCode == GLFW.GLFW_KEY_E) {
                return true;
            }

            // Basic navigation keys to filter field
            boolean handled = this.filterField.keyPressed(keyCode, scanCode, modifiers);
            if (handled) {
                updateFilter();
                return true;
            }

            return false;
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] keyPressed failed", t);
            return false;
        }
    }

    /**
     * Called from the parent screen's mouseClicked.
     *
     * @return true if the overlay consumed the click.
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || button != 0) {
            return false;
        }

        try {
            if (!isMouseOverOverlay(mouseX, mouseY)) {
                // Click outside -> close overlay and consume the click.
                close(true);
                LOG.debug("[RecipientOverlay] Click outside overlay -> closing");
                return true;
            }

            // Click inside overlay:
            // 1) Check if click landed in an entry row.
            int listTop = y + PADDING + HEADER_HEIGHT + FILTER_HEIGHT + PADDING;
            int mouseYInt = (int) mouseY;
            int index = (mouseYInt - listTop) / (ENTRY_HEIGHT + ENTRY_SPACING);

            if (index >= 0 && index < visiblePlayers.size()) {
                KnownPlayersClientCache.KnownPlayerEntry entry = visiblePlayers.get(index);
                if (callback != null) {
                    callback.onPlayerSelected(entry.getUuid(), entry.getName());
                }
                close(false);
                return true;
            }

            // 2) Otherwise, let filter field handle focus click.
            this.filterField.mouseClicked(mouseX, mouseY, button);
            return true;
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] mouseClicked failed", t);
            return false;
        }
    }

    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!active) return;

        try {
            // Semi-transparent dark background
            guiGraphics.fill(
                    x,
                    y,
                    x + width,
                    y + height,
                    0xC0000000  // ARGB: ~75% black
            );

            // Slightly lighter inner panel
            guiGraphics.fill(
                    x + 1,
                    y + 1,
                    x + width - 1,
                    y + height - 1,
                    0xC0222222
            );

            // Filter field
            this.filterField.render(guiGraphics, mouseX, mouseY, partialTick);

            // List of players
            int listTop = y + PADDING + HEADER_HEIGHT + FILTER_HEIGHT + PADDING;
            int currentY = listTop;

            for (KnownPlayersClientCache.KnownPlayerEntry entry : visiblePlayers) {
                if (currentY + ENTRY_HEIGHT > y + height - PADDING) {
                    break; // out of space
                }

                renderEntry(guiGraphics, entry, currentY);
                currentY += ENTRY_HEIGHT + ENTRY_SPACING;
            }
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] render() failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private void reloadFromClientCache() {
        try {
            KnownPlayersClientCache cache = KnownPlayersClientCache.getInstance();
            cache.refreshFromClientConnection(); // at least online players
            List<KnownPlayersClientCache.KnownPlayerEntry> players = cache.getAllPlayers();

            this.allPlayers = new ArrayList<>(players);
            // Sort by name
            this.allPlayers.sort(Comparator.comparing(
                    KnownPlayersClientCache.KnownPlayerEntry::getName,
                    String.CASE_INSENSITIVE_ORDER
            ));
            this.visiblePlayers = new ArrayList<>(this.allPlayers);

            LOG.debug("[RecipientOverlay] reloadFromClientCache: {} players", this.allPlayers.size());
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] reloadFromClientCache failed", t);
            this.allPlayers = new ArrayList<>();
            this.visiblePlayers = new ArrayList<>();
        }
    }

    private void updateFilter() {
        try {
            String filter = this.filterField.getText();
            String lower = filter == null ? "" : filter.toLowerCase(Locale.ROOT);

            if (lower.isEmpty()) {
                this.visiblePlayers = new ArrayList<>(this.allPlayers);
                return;
            }

            List<KnownPlayersClientCache.KnownPlayerEntry> filtered = new ArrayList<>();
            for (KnownPlayersClientCache.KnownPlayerEntry p : allPlayers) {
                if (p.getName().toLowerCase(Locale.ROOT).contains(lower)) {
                    filtered.add(p);
                }
            }

            this.visiblePlayers = filtered;
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] updateFilter failed", t);
        }
    }

    private void renderEntry(@NotNull GuiGraphics g,
                             @NotNull KnownPlayersClientCache.KnownPlayerEntry entry,
                             int yTop) {
        int xLeft = x + PADDING;
        int xRight = x + width - PADDING;

        // Background for the row
        g.fill(
                xLeft,
                yTop,
                xRight,
                yTop + ENTRY_HEIGHT,
                0x80222222
        );

        // "Head" placeholder: small square on left
        int headSize = ENTRY_HEIGHT - 4;
        int headX = xLeft + 2;
        int headY = yTop + 2;

        g.fill(
                headX,
                headY,
                headX + headSize,
                headY + headSize,
                0xFF555555
        );

        // Online / offline indicator: small dot on right side of the row
        int dotRadius = 3;
        int dotCx = xRight - 6;
        int dotCy = yTop + ENTRY_HEIGHT / 2;
        int dotColor = entry.isOnline() ? 0xFF00FF00 : 0xFFFF0000;

        g.fill(
                dotCx - dotRadius,
                dotCy - dotRadius,
                dotCx + dotRadius,
                dotCy + dotRadius,
                dotColor
        );

        // Player name
        g.drawString(
                font,
                entry.getName(),
                headX + headSize + 4,
                yTop + 3,
                0xFFFFFFFF,
                false
        );
    }
}
