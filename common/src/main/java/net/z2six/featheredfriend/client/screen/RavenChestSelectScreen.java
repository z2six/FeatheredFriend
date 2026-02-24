package net.z2six.featheredfriend.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.z2six.featheredfriend.network.RavenChestChoiceInfo;
import net.z2six.featheredfriend.network.RavenChestSelectAction;
import net.z2six.featheredfriend.platform.Services;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Scrollable chest picker for Enderpack-to-Raven-Chest transfers.
 */
public final class RavenChestSelectScreen extends Screen {

    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/chestselect/chest_select_gui.png");
    private static final ResourceLocation BUTTON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/chestselect/chest_select_button.png");
    private static final ResourceLocation BUTTON_TEXTURE_HOVER =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/chestselect/chest_select_button_hover_state.png");
    private static final ResourceLocation SLIDER_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/chestselect/scroll_slider.png");
    private static final ResourceLocation SLIDER_TEXTURE_HOVER =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/chestselect/scroll_slider_hover_state.png");

    private static final int GUI_WIDTH = 250;
    private static final int GUI_HEIGHT = 250;

    private static final int TITLE_BOX_X0 = 56;
    private static final int TITLE_BOX_Y0 = 7;
    private static final int TITLE_BOX_X1 = 194;
    private static final int TITLE_BOX_Y1 = 24;

    private static final int LIST_X0 = 16;
    private static final int LIST_Y0 = 38;
    private static final int LIST_X1 = 219;
    private static final int LIST_Y1 = 196;

    private static final int TRACK_X0 = 224;
    private static final int TRACK_Y0 = 31;
    private static final int TRACK_X1 = 234;
    private static final int TRACK_Y1 = 197;

    private static final int BUTTONS_X0 = 16;
    private static final int BUTTONS_Y0 = 203;
    private static final int BUTTONS_X1 = 233;
    private static final int BUTTONS_Y1 = 233;

    private static final int BUTTON_W = 105;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 7;

    private static final int SLIDER_W = 13;
    private static final int SLIDER_H = 36;

    private static final int ROW_HEIGHT = 20;

    private final int ravenEntityId;
    private final RavenChestSelectAction action;
    private final List<RavenChestChoiceInfo> choices;
    private int selectedIndex = -1;
    private int scrollIndex = 0;
    private boolean draggingScrollbar = false;
    private double dragGrabOffsetY = 0.0D;

    private Button confirmButton;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;
    private int trackX;
    private int trackY;
    private int trackWidth;
    private int trackHeight;
    private int buttonsY;
    private int guiLeft;
    private int guiTop;

    /**
     * Screen#render(...) calls renderBackground(), and Screen#render(...) (super.render) does too.
     * We want blur exactly once per frame, before our custom texture, and never on top of it.
     */
    private boolean skipBackgroundPass = false;

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
        this.guiLeft = (this.width - GUI_WIDTH) / 2;
        this.guiTop = (this.height - GUI_HEIGHT) / 2;

        this.listX = this.guiLeft + LIST_X0;
        this.listY = this.guiTop + LIST_Y0;
        this.listWidth = (LIST_X1 - LIST_X0);
        this.listHeight = (LIST_Y1 - LIST_Y0);

        this.trackX = this.guiLeft + TRACK_X0;
        this.trackY = this.guiTop + TRACK_Y0;
        this.trackWidth = (TRACK_X1 - TRACK_X0);
        this.trackHeight = (TRACK_Y1 - TRACK_Y0);

        int buttonsAreaH = (BUTTONS_Y1 - BUTTONS_Y0);
        this.buttonsY = this.guiTop + BUTTONS_Y0 + Math.max(0, (buttonsAreaH - BUTTON_H) / 2);

        int confirmX = this.guiLeft + BUTTONS_X0;
        int cancelX = confirmX + BUTTON_W + BUTTON_GAP;

        this.confirmButton = new TexturedButton(
                confirmX,
                this.buttonsY,
                BUTTON_W,
                BUTTON_H,
                Component.translatable("screen.featheredfriend.raven_chest_select.confirm"),
                b -> confirm()
        );
        this.addRenderableWidget(this.confirmButton);

        this.addRenderableWidget(new TexturedButton(
                cancelX,
                this.buttonsY,
                BUTTON_W,
                BUTTON_H,
                Component.translatable("gui.cancel"),
                b -> onClose()
        ));

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
            if (isInsideList(mouseX, mouseY)) {
                int row = (int) ((mouseY - this.listY) / ROW_HEIGHT);
                int index = this.scrollIndex + row;
                selectIndex(index);
                return true;
            }

            if (isInsideScrollbarTrack(mouseX, mouseY) && maxScrollIndex() > 0) {
                int thumbTop = scrollbarThumbTop();
                if (mouseY >= thumbTop && mouseY <= (thumbTop + SLIDER_H)) {
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
            double travel = this.trackHeight - SLIDER_H;
            if (travel <= 0.0D) {
                setScrollIndex(0);
                return true;
            }
            double y = mouseY - this.trackY - this.dragGrabOffsetY;
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
        double travel = this.trackHeight - SLIDER_H;
        if (travel <= 0.0D) {
            setScrollIndex(0);
            return;
        }
        double y = mouseY - this.trackY - (SLIDER_H / 2.0D);
        double ratio = Mth.clamp(y / travel, 0.0D, 1.0D);
        setScrollIndex((int) Math.round(ratio * max));
    }

    private int sliderX() {
        // Slider is wider than the track; center it horizontally over the track.
        return this.trackX + Math.round((this.trackWidth - SLIDER_W) / 2.0f);
    }

    private int scrollbarThumbTop() {
        int max = maxScrollIndex();
        if (max <= 0) {
            return this.trackY;
        }
        int travel = this.trackHeight - SLIDER_H;
        return this.trackY + (int) Math.round((travel * (double) this.scrollIndex) / (double) max);
    }

    private boolean isInsideList(double mouseX, double mouseY) {
        return mouseX >= this.listX
                && mouseX <= (this.listX + this.listWidth)
                && mouseY >= this.listY
                && mouseY <= (this.listY + this.listHeight);
    }

    private boolean isInsideScrollbarTrack(double mouseX, double mouseY) {
        return mouseX >= this.trackX
                && mouseX <= (this.trackX + this.trackWidth)
                && mouseY >= this.trackY
                && mouseY <= (this.trackY + this.trackHeight);
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

        guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        guiGraphics.blit(GUI_TEXTURE, this.guiLeft, this.guiTop, 0, 0, GUI_WIDTH, GUI_HEIGHT, GUI_WIDTH, GUI_HEIGHT);

        // Title centered inside the title box.
        int titleCenterX = this.guiLeft + ((TITLE_BOX_X0 + TITLE_BOX_X1) / 2);
        int titleY = this.guiTop + TITLE_BOX_Y0 + Math.max(0, ((TITLE_BOX_Y1 - TITLE_BOX_Y0) - 9) / 2);
        guiGraphics.drawCenteredString(this.font, this.title, titleCenterX, titleY, 0xFFFFFF);

        // List content (clipped to the list area).
        guiGraphics.enableScissor(this.listX, this.listY, this.listX + this.listWidth, this.listY + this.listHeight);

        if (this.choices.isEmpty()) {
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.translatable("screen.featheredfriend.raven_chest_select.none"),
                    this.listX + (this.listWidth / 2),
                    this.listY + (this.listHeight / 2) - 4,
                    0xB0B0B0
            );
        } else {
            int rows = visibleRows();
            for (int i = 0; i < rows; i++) {
                int idx = this.scrollIndex + i;
                if (idx < 0 || idx >= this.choices.size()) {
                    break;
                }

                RavenChestChoiceInfo choice = this.choices.get(idx);
                int rowTop = this.listY + (i * ROW_HEIGHT);
                boolean selected = idx == this.selectedIndex;

                if (selected) {
                    guiGraphics.fill(this.listX + 2, rowTop + 1, this.listX + this.listWidth - 2, rowTop + ROW_HEIGHT - 1, 0x553F2D1A);
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
        }

        guiGraphics.disableScissor();

        // Scrollbar slider (the track is part of the background image).
        if (maxScrollIndex() > 0) {
            int sliderX = sliderX();
            int sliderY = scrollbarThumbTop();
            boolean hover = this.draggingScrollbar
                    || (mouseX >= sliderX && mouseX <= (sliderX + SLIDER_W) && mouseY >= sliderY && mouseY <= (sliderY + SLIDER_H));
            ResourceLocation tex = hover ? SLIDER_TEXTURE_HOVER : SLIDER_TEXTURE;
            guiGraphics.blit(tex, sliderX, sliderY, 0, 0, SLIDER_W, SLIDER_H, SLIDER_W, SLIDER_H);
        }

        // Render widgets (buttons) without triggering another blur/background pass.
        this.skipBackgroundPass = true;
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.skipBackgroundPass = false;
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (this.skipBackgroundPass) {
            return;
        }

        // Keep vanilla menu blur, but do NOT draw the dark menu background overlay.
        // Mirrors how ScrollSealingScreen keeps blur while drawing its own background.
        try {
            this.renderBlurredBackground(partialTick);
        } catch (Throwable ignored) {
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

    private final class TexturedButton extends Button {
        private TexturedButton(int x, int y, int width, int height, @NotNull Component message, @NotNull OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            boolean hover = this.isHoveredOrFocused();
            ResourceLocation tex = hover ? BUTTON_TEXTURE_HOVER : BUTTON_TEXTURE;
            guiGraphics.blit(tex, this.getX(), this.getY(), 0, 0, this.width, this.height, BUTTON_W, BUTTON_H);

            int color = this.active ? 0xFFFFFF : 0xA0A0A0;
            int labelX = this.getX() + (this.width / 2);
            int labelY = this.getY() + Math.max(0, (this.height - 9) / 2);
            guiGraphics.drawCenteredString(RavenChestSelectScreen.this.font, this.getMessage(), labelX, labelY, color);
        }
    }
}
