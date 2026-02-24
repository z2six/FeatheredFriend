package net.z2six.featheredfriend.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.z2six.featheredfriend.client.log.RavenLogViewSettings;
import net.z2six.featheredfriend.log.RavenLogCategory;
import net.z2six.featheredfriend.log.RavenLogTextCodec;
import net.z2six.featheredfriend.network.RavenLogEntryInfo;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Placeholder Raven Log screen opened via keybind.
 */
public final class RavenLogScreen extends Screen {

    public static final int WINDOWED_PANEL_WIDTH = 332;
    public static final int WINDOWED_PANEL_HEIGHT = 230;
    private static final int ROW_HEIGHT = 14;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final List<RavenLogEntryInfo> sourceEntries;
    private final List<RavenLogEntryInfo> filteredEntries = new ArrayList<>();
    private final List<WrappedLogLine> wrappedLines = new ArrayList<>();

    private RavenLogViewSettings viewSettings;
    private int scrollIndex = 0;
    private boolean draggingScrollbar = false;
    private double dragGrabOffsetY = 0.0D;
    private boolean fullscreen = false;
    private boolean forceBottomOnInit = true;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;
    private Button fullscreenButton;

    private record WrappedLogLine(@NotNull RavenLogCategory category, @NotNull FormattedCharSequence text) {
    }

    public RavenLogScreen(@NotNull List<RavenLogEntryInfo> entries) {
        super(Component.translatable("screen.featheredfriend.raven_log.title"));
        this.sourceEntries = entries == null ? List.of() : new ArrayList<>(entries);
        this.sourceEntries.sort(Comparator.comparingLong(RavenLogEntryInfo::entryId));
        this.viewSettings = RavenLogViewSettings.loadFromPlatform();
    }

    @Override
    protected void init() {
        if (this.fullscreen) {
            this.panelX = 10;
            this.panelY = 10;
            this.panelWidth = Math.max(220, this.width - 20);
            this.panelHeight = Math.max(160, this.height - 20);
        } else {
            this.panelWidth = Math.min(WINDOWED_PANEL_WIDTH, this.width - 20);
            this.panelHeight = Math.min(WINDOWED_PANEL_HEIGHT, this.height - 20);
            this.panelX = (this.width - this.panelWidth) / 2;
            this.panelY = (this.height - this.panelHeight) / 2;
        }
        this.listX = this.panelX + 10;
        this.listY = this.panelY + 24;
        this.listWidth = this.panelWidth - 20;
        this.listHeight = this.panelHeight - 58;

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.featheredfriend.raven_log.settings"),
                        btn -> openSettings())
                .bounds(this.panelX + 10, this.panelY + this.panelHeight - 24, 78, 20)
                .build());

        this.fullscreenButton = this.addRenderableWidget(Button.builder(
                        Component.translatable(this.fullscreen
                                ? "screen.featheredfriend.raven_log.windowed"
                                : "screen.featheredfriend.raven_log.fullscreen"),
                        btn -> toggleFullscreen())
                .bounds(this.panelX + 92, this.panelY + this.panelHeight - 24, 78, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.featheredfriend.raven_log.clear"),
                        btn -> Services.PLATFORM.sendClearRavenLogToServer())
                .bounds(this.panelX + this.panelWidth - 90, this.panelY + this.panelHeight - 24, 80, 20)
                .build());

        rebuildFilteredEntries();
        rebuildWrappedLines();
        if (this.forceBottomOnInit) {
            setScrollIndex(maxScrollIndex());
            this.forceBottomOnInit = false;
        } else {
            clampScroll();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            setScrollIndex(this.scrollIndex - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            setScrollIndex(this.scrollIndex + 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            setScrollIndex(this.scrollIndex - visibleRows());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            setScrollIndex(this.scrollIndex + visibleRows());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            setScrollIndex(0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            setScrollIndex(maxScrollIndex());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isInsideList(mouseX, mouseY) && maxScrollIndex() > 0 && delta != 0.0D) {
            setScrollIndex(this.scrollIndex + (delta > 0.0D ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (isInsideScrollbarTrack(mouseX, mouseY) && maxScrollIndex() > 0) {
                int thumbTop = scrollbarThumbTop();
                int thumbHeight = scrollbarThumbHeight();
                if (mouseY >= thumbTop && mouseY <= thumbTop + thumbHeight) {
                    this.draggingScrollbar = true;
                    this.dragGrabOffsetY = mouseY - thumbTop;
                } else {
                    jumpScrollTo(mouseY);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && maxScrollIndex() > 0) {
            double travel = this.listHeight - scrollbarThumbHeight();
            if (travel <= 0.0D) {
                setScrollIndex(0);
                return true;
            }
            double y = mouseY - this.listY - this.dragGrabOffsetY;
            double ratio = Mth.clamp(y / travel, 0.0D, 1.0D);
            setScrollIndex((int) Math.round(ratio * maxScrollIndex()));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            this.draggingScrollbar = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public void applySettings(@NotNull RavenLogViewSettings settings) {
        boolean wasAtBottom = this.scrollIndex >= maxScrollIndex();
        this.viewSettings = settings == null ? new RavenLogViewSettings() : settings;
        rebuildFilteredEntries();
        rebuildWrappedLines();
        if (wasAtBottom) {
            setScrollIndex(maxScrollIndex());
        } else {
            clampScroll();
        }
    }

    private void openSettings() {
        Minecraft mc = this.minecraft;
        if (mc == null) {
            return;
        }
        mc.setScreen(new RavenLogSettingsScreen(this, this.viewSettings));
    }

    private void toggleFullscreen() {
        boolean wasAtBottom = this.scrollIndex >= maxScrollIndex();
        this.fullscreen = !this.fullscreen;
        this.forceBottomOnInit = wasAtBottom;
        this.clearWidgets();
        this.init();
    }

    private void rebuildFilteredEntries() {
        this.filteredEntries.clear();
        for (RavenLogEntryInfo entry : this.sourceEntries) {
            if (entry == null) {
                continue;
            }
            RavenLogCategory category = RavenLogCategory.fromId(entry.categoryId());
            if (!this.viewSettings.isVisible(category)) {
                continue;
            }
            this.filteredEntries.add(entry);
        }
    }

    private void rebuildWrappedLines() {
        this.wrappedLines.clear();
        int contentWidth = Math.max(40, this.listWidth - SCROLLBAR_WIDTH - 10);
        for (RavenLogEntryInfo entry : this.filteredEntries) {
            RavenLogCategory category = RavenLogCategory.fromId(entry.categoryId());
            String line = buildLine(entry, category);
            List<FormattedCharSequence> split = this.font.split(Component.literal(line), contentWidth);
            if (split == null || split.isEmpty()) {
                this.wrappedLines.add(new WrappedLogLine(category, FormattedCharSequence.forward(line, net.minecraft.network.chat.Style.EMPTY)));
                continue;
            }
            for (FormattedCharSequence seq : split) {
                this.wrappedLines.add(new WrappedLogLine(category, seq));
            }
        }
    }

    private int visibleRows() {
        return Math.max(1, this.listHeight / ROW_HEIGHT);
    }

    private int maxScrollIndex() {
        return Math.max(0, this.wrappedLines.size() - visibleRows());
    }

    private void clampScroll() {
        this.scrollIndex = Mth.clamp(this.scrollIndex, 0, maxScrollIndex());
    }

    private void setScrollIndex(int value) {
        this.scrollIndex = Mth.clamp(value, 0, maxScrollIndex());
    }

    private void jumpScrollTo(double mouseY) {
        int max = maxScrollIndex();
        if (max <= 0) {
            setScrollIndex(0);
            return;
        }
        double travel = this.listHeight - scrollbarThumbHeight();
        if (travel <= 0.0D) {
            setScrollIndex(0);
            return;
        }
        double y = mouseY - this.listY - (scrollbarThumbHeight() / 2.0D);
        double ratio = Mth.clamp(y / travel, 0.0D, 1.0D);
        setScrollIndex((int) Math.round(ratio * max));
    }

    private int scrollbarX() {
        return this.listX + this.listWidth - SCROLLBAR_WIDTH;
    }

    private int scrollbarThumbHeight() {
        if (this.wrappedLines.isEmpty()) {
            return this.listHeight;
        }
        int rows = visibleRows();
        if (this.wrappedLines.size() <= rows) {
            return this.listHeight;
        }
        return Math.max(18, (int) ((this.listHeight * (double) rows) / (double) this.wrappedLines.size()));
    }

    private int scrollbarThumbTop() {
        int max = maxScrollIndex();
        if (max <= 0) {
            return this.listY;
        }
        int thumbHeight = scrollbarThumbHeight();
        int travel = this.listHeight - thumbHeight;
        return this.listY + (int) Math.round((travel * (double) this.scrollIndex) / (double) max);
    }

    private boolean isInsideList(double mouseX, double mouseY) {
        return mouseX >= this.listX
                && mouseX <= (this.listX + this.listWidth)
                && mouseY >= this.listY
                && mouseY <= (this.listY + this.listHeight);
    }

    private boolean isInsideScrollbarTrack(double mouseX, double mouseY) {
        int x = scrollbarX();
        return mouseX >= x
                && mouseX <= (x + SCROLLBAR_WIDTH)
                && mouseY >= this.listY
                && mouseY <= (this.listY + this.listHeight);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, 0xCC1B1B1B);
        guiGraphics.fill(this.panelX + 1, this.panelY + 1, this.panelX + this.panelWidth - 1, this.panelY + this.panelHeight - 1, 0xCC2A2A2A);
        guiGraphics.drawCenteredString(this.font, this.title, this.panelX + (this.panelWidth / 2), this.panelY + 8, 0xFFFFFF);

        guiGraphics.fill(this.listX, this.listY, this.listX + this.listWidth, this.listY + this.listHeight, 0x8F101010);
        guiGraphics.fill(this.listX, this.listY + 1, this.listX + this.listWidth, this.listY + 2, 0x40FFFFFF);

        if (this.wrappedLines.isEmpty()) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("screen.featheredfriend.raven_log.none"),
                    this.panelX + (this.panelWidth / 2),
                    this.listY + (this.listHeight / 2) - 4,
                    0xB0B0B0
            );
        } else {
            int rows = visibleRows();
            int contentWidth = this.listWidth - SCROLLBAR_WIDTH - 4;
            for (int i = 0; i < rows; i++) {
                int idx = this.scrollIndex + i;
                if (idx < 0 || idx >= this.wrappedLines.size()) {
                    break;
                }
                WrappedLogLine line = this.wrappedLines.get(idx);
                int rowTop = this.listY + (i * ROW_HEIGHT);
                if ((i & 1) == 1) {
                    guiGraphics.fill(this.listX + 1, rowTop, this.listX + contentWidth, rowTop + ROW_HEIGHT, 0x22000000);
                }

                int color = this.viewSettings.color(line.category()) | 0xFF000000;
                guiGraphics.drawString(this.font, line.text(), this.listX + 4, rowTop + 3, color, false);
            }
        }

        int sbX = scrollbarX();
        guiGraphics.fill(sbX, this.listY, sbX + SCROLLBAR_WIDTH, this.listY + this.listHeight, 0xAA101010);
        int thumbTop = scrollbarThumbTop();
        int thumbHeight = scrollbarThumbHeight();
        guiGraphics.fill(sbX + 1, thumbTop, sbX + SCROLLBAR_WIDTH - 1, thumbTop + thumbHeight, 0xCC777777);
        guiGraphics.fill(sbX + 1, thumbTop, sbX + SCROLLBAR_WIDTH - 1, thumbTop + 1, 0xCCBDBDBD);
    }

    private @NotNull String buildLine(@NotNull RavenLogEntryInfo entry, @NotNull RavenLogCategory category) {
        String ts;
        try {
            ts = TS_FORMAT.format(Instant.ofEpochMilli(entry.createdAtMillis()));
        } catch (Throwable ignored) {
            ts = "--:--:--";
        }
        String cat = Component.translatable(category.translationKey()).getString();
        String msg = RavenLogTextCodec.resolveForClient(entry.message());
        return "[" + ts + "] [" + cat + "] " + msg;
    }

    @Override
    public void onClose() {
        Minecraft mc = this.minecraft;
        if (mc != null) {
            mc.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
