package net.z2six.featheredfriend.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Small bottom overlay for live raven perch transform tweaking.
 */
public final class RavenChestPerchDebugScreen extends Screen {

    private static final double[] STEP_OPTIONS = new double[]{0.005D, 0.01D, 0.05D, 0.1D};

    private static final double MIN_OFFSET = -3.0D;
    private static final double MAX_OFFSET = 3.0D;
    private static final double MIN_Y = 0.5D;
    private static final double MAX_Y = 5.0D;
    private static final float MIN_PITCH = -89.9F;
    private static final float MAX_PITCH = 89.9F;

    private final int ravenEntityId;
    private final BlockPos chestPos;

    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private float yaw;
    private float pitch;
    private int stepIndex = 1;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    public RavenChestPerchDebugScreen(int ravenEntityId,
                                      @NotNull BlockPos chestPos,
                                      double offsetX,
                                      double offsetY,
                                      double offsetZ,
                                      float yaw,
                                      float pitch) {
        super(Component.translatable("screen.featheredfriend.raven_chest_debug.title"));
        this.ravenEntityId = ravenEntityId;
        this.chestPos = chestPos.immutable();
        this.offsetX = Mth.clamp(offsetX, MIN_OFFSET, MAX_OFFSET);
        this.offsetY = Mth.clamp(offsetY, MIN_Y, MAX_Y);
        this.offsetZ = Mth.clamp(offsetZ, MIN_OFFSET, MAX_OFFSET);
        this.yaw = Mth.clamp(yaw, -180.0F, 180.0F);
        this.pitch = Mth.clamp(pitch, MIN_PITCH, MAX_PITCH);
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        this.panelWidth = Math.min(this.width - 16, 460);
        this.panelHeight = 146;
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = this.height - this.panelHeight - 12;

        this.addRenderableWidget(Button.builder(stepButtonText(), btn -> {
                    this.stepIndex = (this.stepIndex + 1) % STEP_OPTIONS.length;
                    btn.setMessage(stepButtonText());
                })
                .bounds(this.panelLeft + this.panelWidth - 122, this.panelTop + 8, 112, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> this.onClose())
                .bounds(this.panelLeft + this.panelWidth - 122, this.panelTop + this.panelHeight - 28, 112, 20)
                .build());

        int rowY = this.panelTop + 36;
        addAdjustRow(rowY, () -> {
            this.offsetX = Mth.clamp(this.offsetX - currentStep(), MIN_OFFSET, MAX_OFFSET);
            sendAdjust();
        }, () -> {
            this.offsetX = Mth.clamp(this.offsetX + currentStep(), MIN_OFFSET, MAX_OFFSET);
            sendAdjust();
        });

        rowY += 20;
        addAdjustRow(rowY, () -> {
            this.offsetY = Mth.clamp(this.offsetY - currentStep(), MIN_Y, MAX_Y);
            sendAdjust();
        }, () -> {
            this.offsetY = Mth.clamp(this.offsetY + currentStep(), MIN_Y, MAX_Y);
            sendAdjust();
        });

        rowY += 20;
        addAdjustRow(rowY, () -> {
            this.offsetZ = Mth.clamp(this.offsetZ - currentStep(), MIN_OFFSET, MAX_OFFSET);
            sendAdjust();
        }, () -> {
            this.offsetZ = Mth.clamp(this.offsetZ + currentStep(), MIN_OFFSET, MAX_OFFSET);
            sendAdjust();
        });

        rowY += 20;
        addAdjustRow(rowY, () -> {
            this.yaw = Mth.clamp(this.yaw - (float) (currentStep() * 10.0D), -180.0F, 180.0F);
            sendAdjust();
        }, () -> {
            this.yaw = Mth.clamp(this.yaw + (float) (currentStep() * 10.0D), -180.0F, 180.0F);
            sendAdjust();
        });

        rowY += 20;
        addAdjustRow(rowY, () -> {
            this.pitch = Mth.clamp(this.pitch - (float) (currentStep() * 10.0D), MIN_PITCH, MAX_PITCH);
            sendAdjust();
        }, () -> {
            this.pitch = Mth.clamp(this.pitch + (float) (currentStep() * 10.0D), MIN_PITCH, MAX_PITCH);
            sendAdjust();
        });
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth, this.panelTop + this.panelHeight, 0xB0000000);
        guiGraphics.drawString(this.font, this.title, this.panelLeft + 10, this.panelTop + 12, 0xFFFFFF, false);
        guiGraphics.drawString(
                this.font,
                Component.translatable("screen.featheredfriend.raven_chest_debug.chest", this.chestPos.getX(), this.chestPos.getY(), this.chestPos.getZ()),
                this.panelLeft + 10,
                this.panelTop + this.panelHeight - 20,
                0xBFBFBF,
                false
        );

        int valueX = this.panelLeft + 94;
        int labelX = this.panelLeft + 10;
        int rowY = this.panelTop + 42;
        guiGraphics.drawString(this.font, Component.literal("X"), labelX, rowY, 0xD0D0D0, false);
        guiGraphics.drawString(this.font, formatValue(this.offsetX), valueX, rowY, 0xFFFFFF, false);
        rowY += 20;
        guiGraphics.drawString(this.font, Component.literal("Y"), labelX, rowY, 0xD0D0D0, false);
        guiGraphics.drawString(this.font, formatValue(this.offsetY), valueX, rowY, 0xFFFFFF, false);
        rowY += 20;
        guiGraphics.drawString(this.font, Component.literal("Z"), labelX, rowY, 0xD0D0D0, false);
        guiGraphics.drawString(this.font, formatValue(this.offsetZ), valueX, rowY, 0xFFFFFF, false);
        rowY += 20;
        guiGraphics.drawString(this.font, Component.literal("Yaw"), labelX, rowY, 0xD0D0D0, false);
        guiGraphics.drawString(this.font, formatValue(this.yaw), valueX, rowY, 0xFFFFFF, false);
        rowY += 20;
        guiGraphics.drawString(this.font, Component.literal("Pitch"), labelX, rowY, 0xD0D0D0, false);
        guiGraphics.drawString(this.font, formatValue(this.pitch), valueX, rowY, 0xFFFFFF, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft mc = this.minecraft;
        if (mc != null) {
            mc.setScreen(null);
        }
    }

    private void addAdjustRow(int rowY, @NotNull Runnable minusAction, @NotNull Runnable plusAction) {
        int buttonX = this.panelLeft + 152;
        int buttonW = 20;
        int buttonGap = 2;
        int buttonY = rowY - 4;

        this.addRenderableWidget(Button.builder(Component.literal("-"), btn -> minusAction.run())
                .bounds(buttonX, buttonY, buttonW, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), btn -> plusAction.run())
                .bounds(buttonX + buttonW + buttonGap, buttonY, buttonW, 20)
                .build());
    }

    private void sendAdjust() {
        Services.PLATFORM.sendRavenChestPerchDebugAdjustToServer(
                this.ravenEntityId,
                this.offsetX,
                this.offsetY,
                this.offsetZ,
                this.yaw,
                this.pitch
        );
    }

    private double currentStep() {
        return STEP_OPTIONS[this.stepIndex];
    }

    private @NotNull Component stepButtonText() {
        return Component.translatable("screen.featheredfriend.raven_chest_debug.step", formatValue(currentStep()));
    }

    private static @NotNull String formatValue(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static @NotNull String formatValue(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
