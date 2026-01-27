// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollViewAttachmentInventoryScreen.java
package net.z2six.featheredfriend.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.neoforge.menu.ScrollViewMenu;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/client/gui/ScrollViewAttachmentInventoryScreen.java
 *
 * Shows scroll attachments (1x9) + player inventory/hotbar for a viewed scroll.
 * Uses ScrollViewMenu as backing so taking items out is server-authoritative.
 *
 * Design goals:
 *  - This screen should NOT close the underlying container when returning to ScrollViewScreen.
 *  - We fade in on open and fade out on close.
 *  - If a parent ScrollViewScreen instance is provided, we return to it (same instance, animation preserved).
 *  - If parent is missing, we fall back to a proper close (closeContainer + setScreen(null)).
 */
public class ScrollViewAttachmentInventoryScreen extends AbstractContainerScreen<ScrollViewMenu> {

    private static final Logger LOG = LogUtils.getLogger();

    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/scrollscreen/inventory.png");

    private static final int TEX_W = 176;
    private static final int TEX_H = 128;

    private static final int FADE_TICKS_TOTAL = 10;

    // fadeTicks goes 0 -> FADE_TICKS_TOTAL during fade-in
    // then FADE_TICKS_TOTAL -> 0 during fade-out
    private int fadeTicks = 0;

    private boolean closing = false;
    private boolean closeActionPerformed = false;

    /**
     * If non-null: when closing, we return to this screen instance instead of closing the container.
     */
    private final ScrollViewScreen parent;

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /**
     * Primary constructor: use this when opening from ScrollViewScreen, so we can return to it.
     */
    public ScrollViewAttachmentInventoryScreen(@NotNull ScrollViewMenu menu,
                                               @NotNull Inventory playerInventory,
                                               @NotNull Component title,
                                               @NotNull ScrollViewScreen parent) {
        super(menu, playerInventory, title);
        this.parent = parent;
        configureDimensions();
        LOG.debug("[ScrollViewAttachmentInventoryScreen] ctor(menu, inv, title, parent) containerId={} parentPresent=true",
                safeContainerId());
    }

    /**
     * Fallback constructor: parent is not available.
     * This exists mainly so accidental calls do not break compilation (and to support the static helper).
     */
    public ScrollViewAttachmentInventoryScreen(@NotNull ScrollViewMenu menu,
                                               @NotNull Inventory playerInventory,
                                               @NotNull Component title) {
        super(menu, playerInventory, title);
        this.parent = null;
        configureDimensions();
        LOG.debug("[ScrollViewAttachmentInventoryScreen] ctor(menu, inv, title) containerId={} parentPresent=false",
                safeContainerId());
    }

    private void configureDimensions() {
        this.imageWidth = TEX_W;
        this.imageHeight = TEX_H;

        // Hide vanilla labels
        this.titleLabelX = 10000;
        this.titleLabelY = 10000;
        this.inventoryLabelX = 10000;
        this.inventoryLabelY = 10000;
    }

    private int safeContainerId() {
        try {
            return this.menu != null ? this.menu.containerId : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    // ---------------------------------------------------------------------
    // External open helper (optional)
    // ---------------------------------------------------------------------

    /**
     * Convenience helper. Opens the attachment inventory screen for the current ScrollViewMenu.
     *
     * If the current screen is a ScrollViewScreen, we use it as parent so we can return without
     * restarting animation.
     */
    public static void handlePearlClicked(@NotNull Minecraft mc) {
        try {
            if (mc.player == null) {
                LOG.warn("[ScrollViewAttachmentInventoryScreen] handlePearlClicked: player null");
                return;
            }

            if (!(mc.player.containerMenu instanceof ScrollViewMenu menu)) {
                LOG.warn("[ScrollViewAttachmentInventoryScreen] handlePearlClicked: containerMenu is not ScrollViewMenu (got: {})",
                        mc.player.containerMenu != null ? mc.player.containerMenu.getClass().getName() : "null");
                return;
            }

            Screen current = mc.screen;
            ScrollViewScreen parent = (current instanceof ScrollViewScreen sv) ? sv : null;

            LOG.debug("[ScrollViewAttachmentInventoryScreen] handlePearlClicked: opening attachment screen containerId={} parentPresent={}",
                    menu.containerId, parent != null);

            if (parent != null) {
                mc.setScreen(new ScrollViewAttachmentInventoryScreen(menu, mc.player.getInventory(), Component.literal(""), parent));
            } else {
                mc.setScreen(new ScrollViewAttachmentInventoryScreen(menu, mc.player.getInventory(), Component.literal("")));
            }

        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] handlePearlClicked failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Init / tick / fade
    // ---------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        try {
            LOG.debug("[ScrollViewAttachmentInventoryScreen] init leftPos={} topPos={} w={} h={} containerId={} parentPresent={}",
                    this.leftPos, this.topPos, this.imageWidth, this.imageHeight, safeContainerId(), this.parent != null);

            this.fadeTicks = 0;              // start fully covered (fade-in from black)
            this.closing = false;
            this.closeActionPerformed = false;
        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] init failed", t);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        try {
            if (!this.closing) {
                // Fade in (0 -> FADE_TICKS_TOTAL)
                if (this.fadeTicks < FADE_TICKS_TOTAL) {
                    this.fadeTicks++;
                }
            } else {
                // Fade out (FADE_TICKS_TOTAL -> 0)
                if (this.fadeTicks > 0) {
                    this.fadeTicks--;
                } else {
                    performCloseAction();
                }
            }
        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] containerTick failed", t);
        }
    }

    private float fadeProgress01() {
        try {
            if (FADE_TICKS_TOTAL <= 0) return 1.0f;
            float v = this.fadeTicks / (float) FADE_TICKS_TOTAL;
            if (v < 0.0f) v = 0.0f;
            if (v > 1.0f) v = 1.0f;
            return v;
        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] fadeProgress01 failed", t);
            return 1.0f;
        }
    }

    private void startClosing(@NotNull String reason) {
        try {
            if (this.closing) {
                LOG.debug("[ScrollViewAttachmentInventoryScreen] startClosing ignored (already closing) reason={}", reason);
                return;
            }
            this.closing = true;
            LOG.debug("[ScrollViewAttachmentInventoryScreen] startClosing reason={} containerId={}", reason, safeContainerId());
        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] startClosing failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        try {
            guiGraphics.blit(
                    INVENTORY_TEXTURE,
                    this.leftPos,
                    this.topPos,
                    0.0f,
                    0.0f,
                    this.imageWidth,
                    this.imageHeight,
                    TEX_W,
                    TEX_H
            );
        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] renderBg failed", t);
            guiGraphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, 0xC0_000000);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            super.render(guiGraphics, mouseX, mouseY, partialTick);

            // Fade overlay:
            //  - during fade-in: fadeTicks increases -> overlay alpha decreases from 1 -> 0
            //  - during fade-out: fadeTicks decreases -> overlay alpha increases from 0 -> 1
            float fade = fadeProgress01();
            float overlayAlpha = 1.0f - fade;

            if (overlayAlpha > 0.0f) {
                if (overlayAlpha > 1.0f) overlayAlpha = 1.0f;

                int a = (int) (overlayAlpha * 255.0f);
                if (a < 0) a = 0;
                if (a > 255) a = 255;

                int color = (a << 24); // black with alpha
                guiGraphics.fill(0, 0, this.width, this.height, color);
            }

            this.renderTooltip(guiGraphics, mouseX, mouseY);
        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] render failed", t);
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // no labels
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (this.closing) return true;

            // Click outside GUI -> close (fade out)
            if (button == 0) {
                boolean inside =
                        mouseX >= this.leftPos && mouseX < (this.leftPos + this.imageWidth) &&
                                mouseY >= this.topPos && mouseY < (this.topPos + this.imageHeight);

                if (!inside) {
                    LOG.debug("[ScrollViewAttachmentInventoryScreen] mouseClicked outside GUI -> closing");
                    startClosing("mouseOutside");
                    return true;
                }
            }

            return super.mouseClicked(mouseX, mouseY, button);
        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] mouseClicked failed", t);
            return false;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            if (this.closing) return true;

            if (keyCode == GLFW.GLFW_KEY_ESCAPE ||
                    keyCode == GLFW.GLFW_KEY_E ||
                    keyCode == GLFW.GLFW_KEY_R ||
                    keyCode == GLFW.GLFW_KEY_U) {
                LOG.debug("[ScrollViewAttachmentInventoryScreen] keyPressed {} -> closing", keyCode);
                startClosing("key:" + keyCode);
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] keyPressed failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Close logic (IMPORTANT: do NOT close container when returning to parent)
    // ---------------------------------------------------------------------

    @Override
    public void onClose() {
        // Important: If something external tries to close us (e.g. screen swap),
        // we treat it like a "startClosing" so we can return to parent cleanly.
        try {
            LOG.debug("[ScrollViewAttachmentInventoryScreen] onClose invoked; starting fade-out (parentPresent={})", this.parent != null);
            startClosing("onClose");
        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] onClose failed; forcing close action", t);
            performCloseAction();
        }
    }

    private void performCloseAction() {
        if (this.closeActionPerformed) return;
        this.closeActionPerformed = true;

        try {
            Minecraft mc = this.minecraft;
            if (mc == null) {
                LOG.warn("[ScrollViewAttachmentInventoryScreen] performCloseAction: minecraft null -> nothing to do");
                return;
            }

            if (this.parent != null) {
                // Return to parent WITHOUT closing container.
                LOG.debug("[ScrollViewAttachmentInventoryScreen] performCloseAction: returning to parent ScrollViewScreen (same instance) containerId={}",
                        safeContainerId());
                mc.setScreen(this.parent);
                return;
            }

            // No parent: do a proper close (close container + clear screen)
            LOG.warn("[ScrollViewAttachmentInventoryScreen] performCloseAction: parent missing -> closing container as fallback containerId={}",
                    safeContainerId());

            try {
                if (mc.player != null) {
                    mc.player.closeContainer();
                }
            } catch (Throwable tClose) {
                LOG.error("[ScrollViewAttachmentInventoryScreen] performCloseAction: closeContainer failed", tClose);
            }

            try {
                mc.setScreen(null);
            } catch (Throwable tScreen) {
                LOG.error("[ScrollViewAttachmentInventoryScreen] performCloseAction: setScreen(null) failed", tScreen);
            }

        } catch (Throwable t) {
            LOG.error("[ScrollViewAttachmentInventoryScreen] performCloseAction failed", t);
        }
    }
}
