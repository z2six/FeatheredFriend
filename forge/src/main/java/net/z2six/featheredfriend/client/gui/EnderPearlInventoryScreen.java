package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.forge.menu.ScrollSealingMenu; // <-- IMPORTANT: Forge menu package
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public class EnderPearlInventoryScreen extends AbstractContainerScreen<ScrollSealingMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation INVENTORY_TEXTURE =
            ffLoc("textures/gui/scrollscreen/inventory.png");

    private static final int FADE_TICKS_TOTAL = 10;

    private int fadeTicks = 0;
    private boolean closing = false;
    private boolean closeActionPerformed = false;

    public EnderPearlInventoryScreen(ScrollSealingMenu menu,
                                     Inventory playerInventory,
                                     Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 128;

        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
        this.inventoryLabelX = 10000;
        this.inventoryLabelY = 10000;
    }

    public static void handlePearlClicked(Minecraft mc) {
        try {
            if (mc == null || mc.player == null) {
                LOG.warn("[EnderPearlInventoryScreen] handlePearlClicked: minecraft or player null");
                return;
            }

            if (!(mc.player.containerMenu instanceof ScrollSealingMenu menu)) {
                LOG.warn("[EnderPearlInventoryScreen] handlePearlClicked: containerMenu not ScrollSealingMenu (got: {})",
                        mc.player.containerMenu.getClass().getName());
                return;
            }

            mc.setScreen(new EnderPearlInventoryScreen(
                    menu,
                    mc.player.getInventory(),
                    Component.literal("")
            ));
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] handlePearlClicked failed", t);
        }
    }

    @Override
    protected void init() {
        super.init();
        this.fadeTicks = 0;
        this.closing = false;
        this.closeActionPerformed = false;
    }

    private float getFadeProgress() {
        if (FADE_TICKS_TOTAL <= 0) return 1.0f;
        float t = this.fadeTicks / (float) FADE_TICKS_TOTAL;
        if (t < 0.0f) t = 0.0f;
        if (t > 1.0f) t = 1.0f;
        return t;
    }

    private void startClosing() {
        if (!this.closing) {
            this.closing = true;
        }
    }

    private void performCloseToScroll() {
        if (this.closeActionPerformed) return;
        this.closeActionPerformed = true;

        try {
            Minecraft mc = this.minecraft;
            if (mc == null || mc.player == null) {
                super.onClose();
                return;
            }

            ScrollSealingMenu menu = this.menu;
            if (menu != null) {
                menu.setClientSkipIntroAnimation(true);
            }

            mc.setScreen(new ScrollSealingScreen(
                    this.menu,
                    mc.player.getInventory(),
                    Component.literal("Scroll Sealing")
            ));
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] performCloseToScroll failed; falling back to super.onClose", t);
            super.onClose();
        }
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        try {
            // Forge 1.20.1-friendly blit overload (ints + explicit texture size)
            guiGraphics.blit(
                    INVENTORY_TEXTURE,
                    this.leftPos,
                    this.topPos,
                    0,
                    0,
                    this.imageWidth,
                    this.imageHeight,
                    176,
                    128
            );
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] renderBg failed, falling back to simple fill", t);
            guiGraphics.fill(
                    this.leftPos,
                    this.topPos,
                    this.leftPos + this.imageWidth,
                    this.topPos + this.imageHeight,
                    0xC0_000000
            );
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            // Forge 1.20.1: renderBackground only takes GuiGraphics
            this.renderBackground(guiGraphics);

            super.render(guiGraphics, mouseX, mouseY, partialTick);

            float overlayAlpha = 1.0f - getFadeProgress();
            if (overlayAlpha > 0.0f) {
                int alphaInt = (int) (overlayAlpha * 255.0f);
                if (alphaInt < 0) alphaInt = 0;
                if (alphaInt > 255) alphaInt = 255;

                int color = (alphaInt << 24);
                guiGraphics.fill(0, 0, this.width, this.height, color);
            }

            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] render failed", t);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();

        if (!this.closing) {
            if (this.fadeTicks < FADE_TICKS_TOTAL) this.fadeTicks++;
        } else {
            if (this.fadeTicks > 0) {
                this.fadeTicks--;
            } else {
                performCloseToScroll();
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.closing) return true;

        if (button == 0) {
            boolean outside =
                    mouseX < this.leftPos || mouseX >= this.leftPos + this.imageWidth ||
                            mouseY < this.topPos || mouseY >= this.topPos + this.imageHeight;

            if (outside) {
                startClosing();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.closing) return true;

        if (keyCode == GLFW.GLFW_KEY_ESCAPE ||
                keyCode == GLFW.GLFW_KEY_E ||
                keyCode == GLFW.GLFW_KEY_R ||
                keyCode == GLFW.GLFW_KEY_U) {
            startClosing();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // no labels
    }

    private static ResourceLocation ffLoc(String path) {
        return new ResourceLocation(Constants.MOD_ID, path);
    }

}
