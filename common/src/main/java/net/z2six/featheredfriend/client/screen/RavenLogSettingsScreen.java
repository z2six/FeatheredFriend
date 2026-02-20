package net.z2six.featheredfriend.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.z2six.featheredfriend.client.log.RavenLogViewSettings;
import net.z2six.featheredfriend.log.RavenLogCategory;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;

/**
 * Sub-screen for Raven Log category visibility/color settings.
 */
public final class RavenLogSettingsScreen extends Screen {

    private static final int[] COLOR_PALETTE = new int[]{
            0xFFFFFF, 0xA7D5FF, 0xF6E58D, 0xFF8A80, 0xB39DDB, 0x80CBC4, 0xFFCC80, 0xD7CCC8
    };
    private static final int ROW_HEIGHT = 22;
    private static final int SCROLLBAR_WIDTH = 8;

    private final RavenLogScreen parent;
    private final RavenLogViewSettings editingSettings;
    private final Map<RavenLogCategory, Button> toggleButtons = new EnumMap<>(RavenLogCategory.class);
    private final Map<RavenLogCategory, Button> colorButtons = new EnumMap<>(RavenLogCategory.class);
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int rowsAreaX;
    private int rowsAreaY;
    private int rowsAreaWidth;
    private int rowsAreaHeight;
    private int rowToggleX;
    private int rowColorX;
    private int rowScrollIndex = 0;
    private boolean draggingScrollbar = false;
    private double dragGrabOffsetY = 0.0D;

    public RavenLogSettingsScreen(@NotNull RavenLogScreen parent,
                                  @NotNull RavenLogViewSettings initialSettings) {
        super(Component.translatable("screen.featheredfriend.raven_log_settings.title"));
        this.parent = parent;
        this.editingSettings = RavenLogViewSettings.deserialize(
                initialSettings == null ? "" : initialSettings.serialize()
        );
    }

    @Override
    protected void init() {
        this.panelWidth = Math.min(RavenLogScreen.WINDOWED_PANEL_WIDTH, this.width - 20);
        this.panelHeight = Math.min(RavenLogScreen.WINDOWED_PANEL_HEIGHT, this.height - 20);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;

        int footerY = this.panelY + this.panelHeight - 26;
        this.rowsAreaX = this.panelX + 10;
        this.rowsAreaY = this.panelY + 44;
        this.rowsAreaWidth = this.panelWidth - 20;
        this.rowsAreaHeight = Math.max(ROW_HEIGHT, (footerY - 8) - this.rowsAreaY);
        this.rowToggleX = this.panelX + this.panelWidth - 156;
        this.rowColorX = this.panelX + this.panelWidth - 82;

        this.toggleButtons.clear();
        this.colorButtons.clear();

        for (RavenLogCategory category : RavenLogCategory.values()) {
            final RavenLogCategory cat = category;

            Button toggle = Button.builder(toggleText(cat), btn -> {
                        boolean now = !this.editingSettings.isVisible(cat);
                        this.editingSettings.setVisible(cat, now);
                        btn.setMessage(toggleText(cat));
                    })
                    .bounds(this.rowToggleX, this.rowsAreaY, 70, 20)
                    .build();
            this.addRenderableWidget(toggle);
            this.toggleButtons.put(cat, toggle);

            Button color = Button.builder(colorText(cat), btn -> {
                        int current = this.editingSettings.color(cat);
                        this.editingSettings.setColor(cat, nextPaletteColor(current));
                        btn.setMessage(colorText(cat));
                    })
                    .bounds(this.rowColorX, this.rowsAreaY, 74, 20)
                    .build();
            this.addRenderableWidget(color);
            this.colorButtons.put(cat, color);
        }

        int centerX = this.panelX + (this.panelWidth / 2);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> onDone())
                .bounds(centerX - 80, footerY, 76, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), btn -> onCancel())
                .bounds(centerX + 4, footerY, 76, 20)
                .build());

        layoutRowWidgets();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onCancel();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (isInsideRowsArea(mouseX, mouseY) && maxScrollIndex() > 0 && deltaY != 0.0D) {
            setRowScrollIndex(this.rowScrollIndex + (deltaY > 0.0D ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
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
            double travel = this.rowsAreaHeight - scrollbarThumbHeight();
            if (travel <= 0.0D) {
                setRowScrollIndex(0);
                return true;
            }
            double y = mouseY - this.rowsAreaY - this.dragGrabOffsetY;
            double ratio = Mth.clamp(y / travel, 0.0D, 1.0D);
            setRowScrollIndex((int) Math.round(ratio * maxScrollIndex()));
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

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.panelX + (this.panelWidth / 2);
        guiGraphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, 0xCC171717);
        guiGraphics.fill(this.panelX + 1, this.panelY + 1, this.panelX + this.panelWidth - 1, this.panelY + this.panelHeight - 1, 0xCC262626);

        int titleY = this.panelY + 8;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, titleY, 0xFFFFFF);
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("screen.featheredfriend.raven_log_settings.subtitle"),
                centerX,
                titleY + 12,
                0xAAAAAA
        );

        guiGraphics.fill(
                this.rowsAreaX,
                this.rowsAreaY,
                this.rowsAreaX + this.rowsAreaWidth,
                this.rowsAreaY + this.rowsAreaHeight,
                0x8F101010
        );
        guiGraphics.fill(
                this.rowsAreaX,
                this.rowsAreaY + 1,
                this.rowsAreaX + this.rowsAreaWidth,
                this.rowsAreaY + 2,
                0x40FFFFFF
        );

        int rows = visibleRows();
        RavenLogCategory[] categories = RavenLogCategory.values();
        for (int i = 0; i < rows; i++) {
            int idx = this.rowScrollIndex + i;
            if (idx < 0 || idx >= categories.length) {
                break;
            }
            RavenLogCategory category = categories[idx];
            int rowY = this.rowsAreaY + (i * ROW_HEIGHT);
            if ((i & 1) == 1) {
                guiGraphics.fill(
                        this.rowsAreaX + 1,
                        rowY,
                        this.rowsAreaX + this.rowsAreaWidth - SCROLLBAR_WIDTH - 2,
                        rowY + ROW_HEIGHT,
                        0x22000000
                );
            }
            int color = this.editingSettings.color(category) | 0xFF000000;
            guiGraphics.drawString(
                this.font,
                Component.translatable(category.translationKey()),
                this.panelX + 12,
                rowY + 6,
                color,
                false
            );
        }

        drawScrollbar(guiGraphics);
    }

    private void onDone() {
        RavenLogViewSettings.saveToPlatform(this.editingSettings);
        this.parent.applySettings(this.editingSettings);
        Minecraft mc = this.minecraft;
        if (mc != null) {
            mc.setScreen(this.parent);
        }
    }

    private void onCancel() {
        Minecraft mc = this.minecraft;
        if (mc != null) {
            mc.setScreen(this.parent);
        }
    }

    private @NotNull Component toggleText(@NotNull RavenLogCategory category) {
        return Component.translatable(
                "screen.featheredfriend.raven_log_settings.visible",
                Component.translatable(this.editingSettings.isVisible(category) ? "options.on" : "options.off")
        );
    }

    private @NotNull Component colorText(@NotNull RavenLogCategory category) {
        return Component.literal(String.format("#%06X", this.editingSettings.color(category) & 0xFFFFFF));
    }

    private int nextPaletteColor(int current) {
        int safe = current & 0xFFFFFF;
        for (int i = 0; i < COLOR_PALETTE.length; i++) {
            if ((COLOR_PALETTE[i] & 0xFFFFFF) == safe) {
                return COLOR_PALETTE[(i + 1) % COLOR_PALETTE.length];
            }
        }
        return COLOR_PALETTE[0];
    }

    private void layoutRowWidgets() {
        int visibleRows = visibleRows();
        this.rowScrollIndex = Mth.clamp(this.rowScrollIndex, 0, maxScrollIndex());

        RavenLogCategory[] categories = RavenLogCategory.values();
        for (int idx = 0; idx < categories.length; idx++) {
            RavenLogCategory category = categories[idx];
            int localRow = idx - this.rowScrollIndex;
            boolean visible = localRow >= 0 && localRow < visibleRows;
            int rowY = this.rowsAreaY + (localRow * ROW_HEIGHT) + 1;

            Button toggle = this.toggleButtons.get(category);
            if (toggle != null) {
                toggle.visible = visible;
                toggle.active = visible;
                toggle.setMessage(toggleText(category));
                if (visible) {
                    toggle.setX(this.rowToggleX);
                    toggle.setY(rowY);
                }
            }

            Button color = this.colorButtons.get(category);
            if (color != null) {
                color.visible = visible;
                color.active = visible;
                color.setMessage(colorText(category));
                if (visible) {
                    color.setX(this.rowColorX);
                    color.setY(rowY);
                }
            }
        }
    }

    private int visibleRows() {
        return Math.max(1, this.rowsAreaHeight / ROW_HEIGHT);
    }

    private int maxScrollIndex() {
        return Math.max(0, RavenLogCategory.values().length - visibleRows());
    }

    private void setRowScrollIndex(int value) {
        this.rowScrollIndex = Mth.clamp(value, 0, maxScrollIndex());
        layoutRowWidgets();
    }

    private int scrollbarX() {
        return this.rowsAreaX + this.rowsAreaWidth - SCROLLBAR_WIDTH;
    }

    private int scrollbarThumbHeight() {
        int count = RavenLogCategory.values().length;
        int rows = visibleRows();
        if (count <= rows) {
            return this.rowsAreaHeight;
        }
        return Math.max(18, (int) ((this.rowsAreaHeight * (double) rows) / (double) count));
    }

    private int scrollbarThumbTop() {
        int max = maxScrollIndex();
        if (max <= 0) {
            return this.rowsAreaY;
        }
        int thumbHeight = scrollbarThumbHeight();
        int travel = this.rowsAreaHeight - thumbHeight;
        return this.rowsAreaY + (int) Math.round((travel * (double) this.rowScrollIndex) / (double) max);
    }

    private void jumpScrollTo(double mouseY) {
        int max = maxScrollIndex();
        if (max <= 0) {
            setRowScrollIndex(0);
            return;
        }
        double travel = this.rowsAreaHeight - scrollbarThumbHeight();
        if (travel <= 0.0D) {
            setRowScrollIndex(0);
            return;
        }
        double y = mouseY - this.rowsAreaY - (scrollbarThumbHeight() / 2.0D);
        double ratio = Mth.clamp(y / travel, 0.0D, 1.0D);
        setRowScrollIndex((int) Math.round(ratio * max));
    }

    private boolean isInsideRowsArea(double mouseX, double mouseY) {
        return mouseX >= this.rowsAreaX
                && mouseX <= (this.rowsAreaX + this.rowsAreaWidth)
                && mouseY >= this.rowsAreaY
                && mouseY <= (this.rowsAreaY + this.rowsAreaHeight);
    }

    private boolean isInsideScrollbarTrack(double mouseX, double mouseY) {
        int x = scrollbarX();
        return mouseX >= x
                && mouseX <= (x + SCROLLBAR_WIDTH)
                && mouseY >= this.rowsAreaY
                && mouseY <= (this.rowsAreaY + this.rowsAreaHeight);
    }

    private void drawScrollbar(@NotNull GuiGraphics guiGraphics) {
        int sbX = scrollbarX();
        guiGraphics.fill(sbX, this.rowsAreaY, sbX + SCROLLBAR_WIDTH, this.rowsAreaY + this.rowsAreaHeight, 0xAA101010);
        int thumbTop = scrollbarThumbTop();
        int thumbHeight = scrollbarThumbHeight();
        guiGraphics.fill(sbX + 1, thumbTop, sbX + SCROLLBAR_WIDTH - 1, thumbTop + thumbHeight, 0xCC777777);
        guiGraphics.fill(sbX + 1, thumbTop, sbX + SCROLLBAR_WIDTH - 1, thumbTop + 1, 0xCCBDBDBD);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
