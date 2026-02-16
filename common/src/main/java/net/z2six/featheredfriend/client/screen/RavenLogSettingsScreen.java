package net.z2six.featheredfriend.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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

    private final RavenLogScreen parent;
    private final RavenLogViewSettings editingSettings;
    private final Map<RavenLogCategory, Button> toggleButtons = new EnumMap<>(RavenLogCategory.class);
    private final Map<RavenLogCategory, Button> colorButtons = new EnumMap<>(RavenLogCategory.class);
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

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
        this.panelWidth = Math.min(356, this.width - 20);
        this.panelHeight = Math.min(246, this.height - 20);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;

        int y = this.panelY + 44;
        int toggleX = this.panelX + this.panelWidth - 156;
        int colorX = this.panelX + this.panelWidth - 82;

        for (RavenLogCategory category : RavenLogCategory.values()) {
            final RavenLogCategory cat = category;

            Button toggle = Button.builder(toggleText(cat), btn -> {
                        boolean now = !this.editingSettings.isVisible(cat);
                        this.editingSettings.setVisible(cat, now);
                        btn.setMessage(toggleText(cat));
                    })
                    .bounds(toggleX, y, 70, 20)
                    .build();
            this.addRenderableWidget(toggle);
            this.toggleButtons.put(cat, toggle);

            Button color = Button.builder(colorText(cat), btn -> {
                        int current = this.editingSettings.color(cat);
                        this.editingSettings.setColor(cat, nextPaletteColor(current));
                        btn.setMessage(colorText(cat));
                    })
                    .bounds(colorX, y, 74, 20)
                    .build();
            this.addRenderableWidget(color);
            this.colorButtons.put(cat, color);

            y += 22;
        }

        int footerY = this.panelY + this.panelHeight - 26;
        int centerX = this.panelX + (this.panelWidth / 2);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> onDone())
                .bounds(centerX - 80, footerY, 76, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), btn -> onCancel())
                .bounds(centerX + 4, footerY, 76, 20)
                .build());
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

        int rowY = this.panelY + 44;
        for (RavenLogCategory category : RavenLogCategory.values()) {
            int color = this.editingSettings.color(category) | 0xFF000000;
            guiGraphics.drawString(
                this.font,
                Component.translatable(category.translationKey()),
                this.panelX + 12,
                rowY + 6,
                color,
                false
            );
            rowY += 22;
        }
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
