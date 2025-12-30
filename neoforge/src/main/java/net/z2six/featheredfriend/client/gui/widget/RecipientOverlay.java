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

public final class RecipientOverlay {

    private static final Logger LOG = LogUtils.getLogger();

    public interface SelectionCallback {
        void onPlayerSelected(UUID uuid, String name);
        void onOverlayClosedWithoutSelection();
    }

    private final Minecraft minecraft;
    private final Font font;
    private final SelectionCallback callback;

    private int x;
    private int y;
    private final int width;
    private final int height;

    private boolean active = false;

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

        this.filterField = new MultiLineScrollTextWidget(
                font,
                x + PADDING,
                y + PADDING + HEADER_HEIGHT,
                width - PADDING * 2,
                FILTER_HEIGHT,
                64,
                1,
                Component.literal("Filter players...")
        );

        try {
            this.filterField.setTextColor(0xFFFFFFFF);
            LOG.debug("[RecipientOverlay] Set filterField text color to 0xFFFFFFFF (white)");
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] Failed to set filterField text color", t);
        }

        LOG.debug("[RecipientOverlay] Created at ({},{}) size=({},{})", x, y, width, height);
    }

    public boolean isActive() {
        return active;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
        this.filterField.setX(x + PADDING);
        this.filterField.setY(y + PADDING + HEADER_HEIGHT);
    }

    public void open() {
        try {
            this.active = true;
            LOG.debug("[RecipientOverlay] open()");

            this.filterField.setText("");
            this.filterField.setFocused(true);

            // Request authoritative server list (UUID+name) in case we missed a broadcast / opened too early.
            try {
                KnownPlayersClientCache.getInstance().requestRefreshFromServer();
                LOG.debug("[RecipientOverlay] Requested known player refresh from server");
            } catch (Throwable t) {
                LOG.error("[RecipientOverlay] Failed to request known players from server", t);
            }

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

            // Keep online/offline status fresh and catch any newly received server list updates.
            // This is cheap.
            reloadFromClientCache();
            updateFilter();
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
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                close(true);
                return true;
            }

            // prevent inventory from opening while typing/selecting
            if (keyCode == GLFW.GLFW_KEY_E) {
                return true;
            }

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

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || button != 0) {
            return false;
        }

        try {
            if (!isMouseOverOverlay(mouseX, mouseY)) {
                close(true);
                LOG.debug("[RecipientOverlay] Click outside overlay -> closing");
                return true;
            }

            int listTop = y + PADDING + HEADER_HEIGHT + FILTER_HEIGHT + PADDING;
            int mouseYInt = (int) mouseY;
            int index = (mouseYInt - listTop) / (ENTRY_HEIGHT + ENTRY_SPACING);

            if (index >= 0 && index < visiblePlayers.size()) {
                KnownPlayersClientCache.KnownPlayerEntry entry = visiblePlayers.get(index);

                if (entry != null && entry.getUuid() != null && entry.getName() != null && !entry.getName().isBlank()) {
                    if (callback != null) {
                        callback.onPlayerSelected(entry.getUuid(), entry.getName());
                    }
                    close(false);
                    return true;
                } else {
                    LOG.warn("[RecipientOverlay] Clicked invalid entry index={} entry={}", index, entry);
                }
            }

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
            guiGraphics.fill(
                    x,
                    y,
                    x + width,
                    y + height,
                    0xC0000000
            );

            guiGraphics.fill(
                    x + 1,
                    y + 1,
                    x + width - 1,
                    y + height - 1,
                    0xC0222222
            );

            this.filterField.render(guiGraphics, mouseX, mouseY, partialTick);

            int listTop = y + PADDING + HEADER_HEIGHT + FILTER_HEIGHT + PADDING;
            int currentY = listTop;

            for (KnownPlayersClientCache.KnownPlayerEntry entry : visiblePlayers) {
                if (currentY + ENTRY_HEIGHT > y + height - PADDING) {
                    break;
                }

                renderEntry(guiGraphics, entry, currentY);
                currentY += ENTRY_HEIGHT + ENTRY_SPACING;
            }
        } catch (Throwable t) {
            LOG.error("[RecipientOverlay] render() failed", t);
        }
    }

    private void reloadFromClientCache() {
        try {
            KnownPlayersClientCache cache = KnownPlayersClientCache.getInstance();

            // Defensive: ensure online players get inserted even if server list packet hasn’t arrived yet.
            cache.refreshFromClientConnection();

            List<KnownPlayersClientCache.KnownPlayerEntry> players = cache.getAllPlayersSorted();

            this.allPlayers = new ArrayList<>(players);
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
                if (p == null || p.getName() == null) continue;
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

        g.fill(
                xLeft,
                yTop,
                xRight,
                yTop + ENTRY_HEIGHT,
                0x80222222
        );

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
