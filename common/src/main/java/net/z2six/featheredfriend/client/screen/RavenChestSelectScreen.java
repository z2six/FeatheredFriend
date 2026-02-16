package net.z2six.featheredfriend.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.z2six.featheredfriend.network.RavenChestChoiceInfo;
import net.z2six.featheredfriend.network.RavenChestSelectAction;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Scrollable chest picker for Enderpack-to-Raven-Chest transfers.
 */
public final class RavenChestSelectScreen extends Screen {

    private static final int PANEL_WIDTH = 252;
    private static final int ROW_HEIGHT = 20;
    private static final int SCROLLBAR_WIDTH = 8;

    private final int ravenEntityId;
    private final RavenChestSelectAction action;
    private final List<RavenChestChoiceInfo> choices;
    private int selectedIndex = -1;
    private int scrollIndex = 0;
    private boolean draggingScrollbar = false;
    private double dragGrabOffsetY = 0.0D;

    private Button confirmButton;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;

    public RavenChestSelectScreen(int ravenEntityId,
                                  @NotNull List<RavenChestChoiceInfo> choices) {
        this(ravenEntityId, choices, RavenChestSelectAction.ENDERPACK_DEPOSIT);
    }

    public RavenChestSelectScreen(int ravenEntityId,
                                  @NotNull List<RavenChestChoiceInfo> choices,
                                  @NotNull RavenChestSelectAction action) {
        super(Component.translatable(action == null
                ? RavenChestSelectAction.ENDERPACK_DEPOSIT.titleKey()
                : action.titleKey()));
        this.ravenEntityId = ravenEntityId;
        this.action = action == null ? RavenChestSelectAction.ENDERPACK_DEPOSIT : action;
        this.choices = choices == null ? List.of() : List.copyOf(choices);
    }

    @Override
    protected void init() {
        this.panelWidth = Math.min(PANEL_WIDTH, this.width - 20);
        this.panelHeight = Math.min(210, this.height - 20);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;
        this.listX = this.panelX + 10;
        this.listY = this.panelY + 24;
        this.listWidth = this.panelWidth - 20;
        this.listHeight = this.panelHeight - 72;

        this.confirmButton = Button.builder(Component.translatable("screen.featheredfriend.raven_chest_select.confirm"), b -> confirm())
                .bounds(this.panelX + 10, this.panelY + this.panelHeight - 28, 110, 20)
                .build();
        this.addRenderableWidget(this.confirmButton);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(this.panelX + this.panelWidth - 120, this.panelY + this.panelHeight - 28, 110, 20)
                .build());

        selectInitialEntry();
        refreshConfirmButton();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_UP) {
            moveSelection(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            moveSelection(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            moveSelection(-visibleRows());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            moveSelection(visibleRows());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            selectIndex(0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            selectIndex(this.choices.size() - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (isInsideList(mouseX, mouseY) && maxScrollIndex() > 0 && deltaY != 0.0D) {
            int step = deltaY > 0.0D ? -1 : 1;
            setScrollIndex(this.scrollIndex + step);
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
                if (mouseY >= thumbTop && mouseY <= (thumbTop + thumbHeight)) {
                    this.draggingScrollbar = true;
                    this.dragGrabOffsetY = mouseY - thumbTop;
                } else {
                    jumpScrollTo(mouseY);
                }
                return true;
            }

            if (isInsideList(mouseX, mouseY)) {
                int row = (int) ((mouseY - this.listY) / ROW_HEIGHT);
                int index = this.scrollIndex + row;
                selectIndex(index);
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

    private void selectInitialEntry() {
        if (this.choices.isEmpty()) {
            this.selectedIndex = -1;
            return;
        }
        for (int i = 0; i < this.choices.size(); i++) {
            RavenChestChoiceInfo c = this.choices.get(i);
            if (c != null && c.available()) {
                this.selectedIndex = i;
                ensureSelectedVisible();
                return;
            }
        }
        this.selectedIndex = 0;
        ensureSelectedVisible();
    }

    private void selectIndex(int index) {
        if (index < 0 || index >= this.choices.size()) {
            return;
        }
        this.selectedIndex = index;
        ensureSelectedVisible();
        refreshConfirmButton();
    }

    private void moveSelection(int delta) {
        if (this.choices.isEmpty()) {
            return;
        }
        if (this.selectedIndex < 0) {
            selectInitialEntry();
            refreshConfirmButton();
            return;
        }
        int target = Mth.clamp(this.selectedIndex + delta, 0, this.choices.size() - 1);
        selectIndex(target);
    }

    private void ensureSelectedVisible() {
        if (this.selectedIndex < 0) {
            return;
        }
        int rows = visibleRows();
        if (this.selectedIndex < this.scrollIndex) {
            this.scrollIndex = this.selectedIndex;
        } else if (this.selectedIndex >= this.scrollIndex + rows) {
            this.scrollIndex = this.selectedIndex - rows + 1;
        }
        this.scrollIndex = Mth.clamp(this.scrollIndex, 0, maxScrollIndex());
    }

    private int visibleRows() {
        return Math.max(1, this.listHeight / ROW_HEIGHT);
    }

    private int maxScrollIndex() {
        return Math.max(0, this.choices.size() - visibleRows());
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
        if (this.choices.isEmpty()) {
            return this.listHeight;
        }
        int rows = visibleRows();
        if (this.choices.size() <= rows) {
            return this.listHeight;
        }
        return Math.max(18, (int) ((this.listHeight * (double) rows) / (double) this.choices.size()));
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

    private void refreshConfirmButton() {
        if (this.confirmButton == null) {
            return;
        }
        if (this.selectedIndex < 0 || this.selectedIndex >= this.choices.size()) {
            this.confirmButton.active = false;
            return;
        }
        RavenChestChoiceInfo selected = this.choices.get(this.selectedIndex);
        this.confirmButton.active = selected != null && selected.available();
    }

    private @NotNull Component chestLabelComponent(@NotNull RavenChestChoiceInfo choice) {
        if (choice.available()) {
            return Component.literal(choice.label()).withStyle(ChatFormatting.GOLD);
        }
        return Component.literal(choice.label())
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" "))
                .append(Component.translatable("screen.featheredfriend.raven_chest_select.unavailable_suffix")
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    private void confirm() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.choices.size()) {
            onClose();
            return;
        }
        RavenChestChoiceInfo selected = this.choices.get(this.selectedIndex);
        if (selected == null || !selected.available()) {
            return;
        }
        Services.PLATFORM.sendConfirmRavenChestDepositToServer(
                this.ravenEntityId,
                selected.dimensionId(),
                selected.blockPos(),
                this.action
        );
        onClose();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + this.panelHeight, 0xCC1B1B1B);
        guiGraphics.fill(this.panelX + 1, this.panelY + 1, this.panelX + this.panelWidth - 1, this.panelY + this.panelHeight - 1, 0xCC2A2A2A);
        guiGraphics.drawCenteredString(this.font, this.title, this.panelX + (this.panelWidth / 2), this.panelY + 9, 0xFFFFFF);

        guiGraphics.fill(this.listX, this.listY, this.listX + this.listWidth, this.listY + this.listHeight, 0x8F111111);
        guiGraphics.fill(this.listX, this.listY + 1, this.listX + this.listWidth, this.listY + 2, 0x5FFFFFFF);

        if (this.choices.isEmpty()) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("screen.featheredfriend.raven_chest_select.none"),
                    this.panelX + (this.panelWidth / 2),
                    this.listY + (this.listHeight / 2) - 4,
                    0xB0B0B0
            );
            return;
        }

        int rows = visibleRows();
        int contentWidth = this.listWidth - SCROLLBAR_WIDTH - 4;
        for (int i = 0; i < rows; i++) {
            int idx = this.scrollIndex + i;
            if (idx < 0 || idx >= this.choices.size()) {
                break;
            }
            RavenChestChoiceInfo choice = this.choices.get(idx);
            int rowTop = this.listY + (i * ROW_HEIGHT);
            boolean selected = idx == this.selectedIndex;
            if (selected) {
                guiGraphics.fill(this.listX + 1, rowTop + 1, this.listX + contentWidth, rowTop + ROW_HEIGHT - 1, 0x663F2D1A);
            }
            if (i > 0) {
                guiGraphics.fill(this.listX + 1, rowTop, this.listX + contentWidth, rowTop + 1, 0x2FFFFFFF);
            }
            if (choice != null) {
                guiGraphics.drawString(
                        this.font,
                        chestLabelComponent(choice),
                        this.listX + 6,
                        rowTop + 6,
                        0xFFFFFF,
                        false
                );
            }
        }

        int sbX = scrollbarX();
        guiGraphics.fill(sbX, this.listY, sbX + SCROLLBAR_WIDTH, this.listY + this.listHeight, 0xAA101010);
        int thumbTop = scrollbarThumbTop();
        int thumbHeight = scrollbarThumbHeight();
        guiGraphics.fill(sbX + 1, thumbTop, sbX + SCROLLBAR_WIDTH - 1, thumbTop + thumbHeight, 0xCC777777);
        guiGraphics.fill(sbX + 1, thumbTop, sbX + SCROLLBAR_WIDTH - 1, thumbTop + 1, 0xCCBDBDBD);

        if (this.selectedIndex >= 0 && this.selectedIndex < this.choices.size()) {
            RavenChestChoiceInfo selected = this.choices.get(this.selectedIndex);
            if (selected != null) {
                BlockPos pos = BlockPos.of(selected.blockPos());
                guiGraphics.drawCenteredString(
                        this.font,
                        Component.translatable(
                                "screen.featheredfriend.raven_chest_select.position",
                                pos.getX(),
                                pos.getY(),
                                pos.getZ()
                        ),
                        this.panelX + (this.panelWidth / 2),
                        this.listY + this.listHeight + 8,
                        0xBFBFBF
                );
            }
        }
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
