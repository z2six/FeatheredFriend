package net.z2six.featheredfriend.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

/**
 * Temporary placeholder UI used immediately after placing a Raven Chest.
 */
public final class RavenChestLabelScreen extends Screen {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int MAX_LABEL_LENGTH = 48;

    private final String dimensionId;
    private final long blockPos;
    private final String initialLabel;

    private EditBox editBox;

    public RavenChestLabelScreen(@NotNull String dimensionId, long blockPos, @NotNull String currentLabel) {
        super(Component.translatable("screen.featheredfriend.raven_chest_label.title"));
        this.dimensionId = dimensionId;
        this.blockPos = blockPos;
        this.initialLabel = currentLabel == null ? "" : currentLabel;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 20;

        this.editBox = new EditBox(
                this.font,
                centerX - 110,
                y,
                220,
                20,
                Component.translatable("screen.featheredfriend.raven_chest_label.input")
        );
        this.editBox.setMaxLength(MAX_LABEL_LENGTH);
        this.editBox.setValue(this.initialLabel);
        this.editBox.setFocused(true);
        this.addRenderableWidget(this.editBox);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> submit())
                .bounds(centerX - 105, y + 28, 100, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), btn -> onClose())
                .bounds(centerX + 5, y + 28, 100, 20)
                .build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (this.editBox != null && this.editBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.editBox != null && this.editBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void submit() {
        try {
            String label = this.editBox == null ? "" : this.editBox.getValue();
            Services.PLATFORM.sendSetRavenChestLabelToServer(this.dimensionId, this.blockPos, label == null ? "" : label.trim());
        } catch (Throwable t) {
            LOG.error("[RavenChestLabelScreen] submit failed", t);
        }
        onClose();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 48, 0xFFFFFF);
        guiGraphics.drawCenteredString(
                this.font,
                Component.translatable("screen.featheredfriend.raven_chest_label.subtitle"),
                this.width / 2,
                this.height / 2 - 34,
                0xAFAFAF
        );
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
