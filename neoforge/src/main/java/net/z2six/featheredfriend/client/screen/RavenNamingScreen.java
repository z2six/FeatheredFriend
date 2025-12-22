// neoforge/src/main/java/net/z2six/featheredfriend/client/screen/RavenNamingScreen.java
package net.z2six.featheredfriend.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.z2six.featheredfriend.network.FFNetwork;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

/**
 * Simple GUI that lets the player name their newly tamed raven.
 *  - Single text box.
 *  - Max 26 characters (enforced client-side; server also enforces).
 *  - "Done" and "Cancel" buttons.
 */
public class RavenNamingScreen extends Screen {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int MAX_NAME_CHARS = 26;

    private final int ravenEntityId;

    private EditBox nameBox;
    private Button doneButton;
    private Button cancelButton;

    public RavenNamingScreen(int ravenEntityId) {
        // Use a literal so we don't see raw translation keys
        super(Component.literal("Name your raven"));
        this.ravenEntityId = ravenEntityId;
    }

    @Override
    protected void init() {
        super.init();
        try {
            int centerX = this.width / 2;
            int centerY = this.height / 2;

            this.nameBox = new EditBox(
                    this.font,
                    centerX - 100,
                    centerY - 20,
                    200,
                    20,
                    Component.literal("Name your raven")
            );
            this.nameBox.setMaxLength(MAX_NAME_CHARS);
            this.nameBox.setFocused(true);
            this.nameBox.setValue("");

            this.addRenderableWidget(this.nameBox);

            this.doneButton = Button.builder(
                            Component.translatable("gui.done"),
                            btn -> onDone()
                    )
                    .bounds(centerX - 100, centerY + 10, 95, 20)
                    .build();

            this.cancelButton = Button.builder(
                            Component.translatable("gui.cancel"),
                            btn -> onCancel()
                    )
                    .bounds(centerX + 5, centerY + 10, 95, 20)
                    .build();

            this.addRenderableWidget(this.doneButton);
            this.addRenderableWidget(this.cancelButton);

        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] init failed", t);
        }
    }

    @Override
    public void tick() {
        try {
            // No per-tick call needed for EditBox on 1.21.x
            super.tick();
        } catch (Throwable t) {
            LOG.warn("[RavenNamingScreen] tick failed safely: {}", t.toString());
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics,
                       int mouseX,
                       int mouseY,
                       float partialTick) {
        try {
            // 1.21+ signature: (GuiGraphics, int, int, float)
            this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

            // Single label: "Name your raven:"
            guiGraphics.drawCenteredString(
                    this.font,
                    Component.literal("Name your raven:"),
                    this.width / 2,
                    this.height / 2 - 35,
                    0xFFFFFF
            );

            if (this.nameBox != null) {
                this.nameBox.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            super.render(guiGraphics, mouseX, mouseY, partialTick);

        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] render failed", t);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                onDone();
                return true;
            }

            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                onCancel();
                return true;
            }

            if (this.nameBox != null && this.nameBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);

        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] keyPressed failed", t);
            return false;
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        try {
            if (this.nameBox != null && this.nameBox.charTyped(codePoint, modifiers)) {
                return true;
            }
            return super.charTyped(codePoint, modifiers);
        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] charTyped failed", t);
            return false;
        }
    }

    private void onDone() {
        try {
            if (this.minecraft == null) return;

            String name = (this.nameBox != null) ? this.nameBox.getValue() : "";
            if (name == null) name = "";

            if (name.length() > MAX_NAME_CHARS) {
                name = name.substring(0, MAX_NAME_CHARS);
            }

            LOG.info("[RavenNamingScreen] onDone: sending name='{}' for ravenEntityId={}", name, ravenEntityId);
            FFNetwork.sendRavenNameChosenToServer(ravenEntityId, name);

            this.onClose();

        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] onDone failed", t);
            this.onClose();
        }
    }

    private void onCancel() {
        try {
            LOG.info("[RavenNamingScreen] onCancel: closing without sending name (ravenEntityId={})", ravenEntityId);
            this.onClose();
        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] onCancel failed", t);
            this.onClose();
        }
    }

    @Override
    public void onClose() {
        try {
            Minecraft mc = this.minecraft;
            if (mc != null) {
                mc.setScreen(null);
            }
        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] onClose failed", t);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
