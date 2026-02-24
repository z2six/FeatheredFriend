// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/EnderPearlInventoryScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.menu.ScrollSealingMenu;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/EnderPearlInventoryScreen.java
 *
 * EnderPearlInventoryScreen
 *
 * Alternate view for ScrollSealingMenu that shows:
 *  - 1x9 attachment bar (top row) using the custom "inventory.png" texture.
 *  - Player inventory (3x9).
 *  - Player hotbar (1x9).
 *
 * Texture:
 *  - inventory.png (176x128) in the same folder as other scrollscreen textures.
 *
 * Behaviour:
 *  - Uses the same ScrollSealingMenu instance as ScrollSealingScreen.
 *  - Fades in when opened and fades out when closed by the player.
 *  - ESC or clicking outside the inventory GUI:
 *      * Starts a fade-out and then returns to ScrollSealingScreen instead of
 *        actually closing the container (attachments stay in the menu).
 *  - No labels/text are rendered on top of the inventory texture.
 */
public class EnderPearlInventoryScreen extends AbstractContainerScreen<ScrollSealingMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation INVENTORY_TEXTURE =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/scrollscreen/inventory.png");

    /**
     * Number of ticks for the fade-in / fade-out.
     *  - 20 ticks = 1 second; here we use 10 ticks for a quick fade.
     */
    private static final int FADE_TICKS_TOTAL = 10;

    // Current fade progress in ticks: 0..FADE_TICKS_TOTAL
    private int fadeTicks = 0;

    // True once we've started closing (fade-out).
    private boolean closing = false;

    // Guard to ensure we only perform the close action once.
    private boolean closeActionPerformed = false;

    public EnderPearlInventoryScreen(@NotNull ScrollSealingMenu menu,
                                     @NotNull Inventory playerInventory,
                                     @NotNull Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 128;

        // We won't render any labels, so label positions don't matter.
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
        this.inventoryLabelX = 10000;
        this.inventoryLabelY = 10000;
    }

    /**
     * Called from ScrollSealingScreen when the pearl icon is clicked.
     *
     * Opens this inventory screen using the same ScrollSealingMenu instance.
     */
    public static void handlePearlClicked(Minecraft mc) {
        try {
            if (mc == null) {
                LOG.warn("[EnderPearlInventoryScreen] handlePearlClicked: Minecraft instance is null");
                return;
            }
            if (mc.player == null) {
                LOG.warn("[EnderPearlInventoryScreen] handlePearlClicked: player is null");
                return;
            }

            if (!(mc.player.containerMenu instanceof ScrollSealingMenu menu)) {
                LOG.warn("[EnderPearlInventoryScreen] handlePearlClicked: current containerMenu is not ScrollSealingMenu (got: {})",
                        mc.player.containerMenu.getClass().getName());
                return;
            }

            LOG.debug("[EnderPearlInventoryScreen] Opening attachment inventory for player={}",
                    mc.player.getGameProfile().getName());

            mc.setScreen(new EnderPearlInventoryScreen(
                    menu,
                    mc.player.getInventory(),
                    Component.empty() // title ignored, labels not drawn
            ));
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] handlePearlClicked failed", t);
        }
    }

    @Override
    protected void init() {
        super.init();
        try {
            LOG.debug("[EnderPearlInventoryScreen] init at leftPos={}, topPos={}", this.leftPos, this.topPos);
            try {
                this.menu.setClientSlotsVisible(true);
            } catch (Throwable ignored) {
            }
            this.fadeTicks = 0;
            this.closing = false;
            this.closeActionPerformed = false;
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] init failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Fade helpers
    // ---------------------------------------------------------------------

    /**
     * 0.0 -> fully hidden (screen covered by black)
     * 1.0 -> fully visible (no fade overlay)
     */
    private float getFadeProgress() {
        if (FADE_TICKS_TOTAL <= 0) {
            return 1.0f;
        }
        float t = this.fadeTicks / (float) FADE_TICKS_TOTAL;
        if (t < 0.0f) t = 0.0f;
        if (t > 1.0f) t = 1.0f;
        return t;
    }

    private void startClosing() {
        if (this.closing) {
            return;
        }
        LOG.debug("[EnderPearlInventoryScreen] startClosing: beginning fade-out");
        this.closing = true;
    }

    private void performCloseToScroll() {
        if (this.closeActionPerformed) {
            return;
        }
        this.closeActionPerformed = true;

        try {
            Minecraft mc = this.minecraft;
            if (mc == null || mc.player == null) {
                LOG.warn("[EnderPearlInventoryScreen] performCloseToScroll: minecraft or player null, delegating to super.onClose");
                super.onClose();
                return;
            }

            ScrollSealingMenu menu = this.menu;
            if (menu != null) {
                // Tell the scroll GUI to skip its intro animation next time we open it for this menu.
                menu.setClientSkipIntroAnimation(true);
            }

            LOG.debug("[EnderPearlInventoryScreen] performCloseToScroll: reopening ScrollSealingScreen instead of closing container");

            mc.setScreen(new ScrollSealingScreen(
                    this.menu,
                    mc.player.getInventory(),
                    Component.translatable("screen.featheredfriend.scroll_sealing")
            ));
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] performCloseToScroll failed; falling back to super.onClose", t);
            super.onClose();
        }
    }

    // ---------------------------------------------------------------------
    // Background rendering
    // ---------------------------------------------------------------------

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics,
                            float partialTick,
                            int mouseX,
                            int mouseY) {
        try {
            guiGraphics.blit(
                    INVENTORY_TEXTURE,
                    this.leftPos,
                    this.topPos,
                    0.0f,
                    0.0f,
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
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            // 1) Normal container background (dim world, etc.)
            this.renderBackground(guiGraphics);

            // 2) Normal container rendering (GUI + slots/items)
            super.render(guiGraphics, mouseX, mouseY, partialTick);

            // 3) Apply a global fade overlay on top of everything.
            float fadeProgress = getFadeProgress();
            float overlayAlpha = 1.0f - fadeProgress;

            if (overlayAlpha > 0.0f) {
                if (overlayAlpha > 1.0f) {
                    overlayAlpha = 1.0f;
                }

                int alphaInt = (int) (overlayAlpha * 255.0f);
                if (alphaInt < 0) alphaInt = 0;
                if (alphaInt > 255) alphaInt = 255;

                int color = (alphaInt << 24); // ARGB: alpha in high byte, RGB = 0 (black)

                guiGraphics.fill(
                        0,
                        0,
                        this.width,
                        this.height,
                        color
                );
            }

            // 4) Tooltips on top of everything.
            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] render failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Ticking (drives fade in/out)
    // ---------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        try {
            if (!this.closing) {
                // Fade in
                if (this.fadeTicks < FADE_TICKS_TOTAL) {
                    this.fadeTicks++;
                }
            } else {
                // Fade out
                if (this.fadeTicks > 0) {
                    this.fadeTicks--;
                } else {
                    // Fade fully completed, perform close action if not yet done.
                    performCloseToScroll();
                }
            }
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] containerTick failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            // If we're already closing, swallow clicks.
            if (this.closing) {
                return true;
            }

            // Click outside inventory GUI -> behave like ESC: trigger fade-out
            if (button == 0) {
                if (mouseX < this.leftPos || mouseX >= this.leftPos + this.imageWidth ||
                        mouseY < this.topPos || mouseY >= this.topPos + this.imageHeight) {

                    LOG.debug("[EnderPearlInventoryScreen] Click outside GUI -> starting fade-out / return to ScrollSealingScreen");
                    startClosing();
                    return true;
                }
            }

            return super.mouseClicked(mouseX, mouseY, button);
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] mouseClicked failed", t);
            return false;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            // If we're already closing, swallow all keys.
            if (this.closing) {
                return true;
            }

            // Treat ESC and the same blocked keys as "close overlay and go back to scroll" (with fade).
            if (keyCode == GLFW.GLFW_KEY_ESCAPE ||
                    keyCode == GLFW.GLFW_KEY_E ||
                    keyCode == GLFW.GLFW_KEY_R ||
                    keyCode == GLFW.GLFW_KEY_U) {
                LOG.debug("[EnderPearlInventoryScreen] keyPressed {} -> starting fade-out / return to ScrollSealingScreen", keyCode);
                startClosing();
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] keyPressed failed", t);
            return false;
        }
    }

    @Override
    public void onClose() {
        try {
            // In normal player flows we close via fade -> performCloseToScroll().
            // onClose() will typically be called only if the server closes this container.
            LOG.debug("[EnderPearlInventoryScreen] onClose invoked externally; delegating to super");
            super.onClose();
        } catch (Throwable t) {
            LOG.error("[EnderPearlInventoryScreen] onClose failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Intentionally empty: no "Scroll Attachments" / "Inventory" labels.
    }
}
