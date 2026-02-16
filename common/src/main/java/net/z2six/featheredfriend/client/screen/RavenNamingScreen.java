// neoforge/src/main/java/net/z2six/featheredfriend/client/screen/RavenNamingScreen.java
package net.z2six.featheredfriend.client.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.platform.Services;
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
    private static final ResourceLocation BG_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/namingscreen/naming-gui.png");
    private static final ResourceLocation GOTHIC_FONT_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gothic12");

    private static final int BG_WIDTH = 206;
    private static final int BG_HEIGHT = 135;

    // Texture-local layout regions.
    private static final int TITLE_X0 = 39;
    private static final int TITLE_Y0 = 63;
    private static final int TITLE_X1 = 175;
    private static final int TITLE_Y1 = 80;

    private static final int NAME_X0 = 30;
    private static final int NAME_Y0 = 105;
    private static final int NAME_X1 = 180;
    private static final int NAME_Y1 = 115;

    private final int ravenEntityId;

    private int bgLeft;
    private int bgTop;

    private EditBox nameBox;
    private Button doneButton;
    private Button cancelButton;

    private boolean useVanillaFontForGothicText = Services.PLATFORM.isUseVanillaFontForGothicText();
    private int caretBlinkTicks = 0;

    public RavenNamingScreen(int ravenEntityId) {
        super(Component.translatable("screen.featheredfriend.feathered_friend_naming.title"));
        this.ravenEntityId = ravenEntityId;
    }

    @Override
    protected void init() {
        super.init();
        try {
            this.bgLeft = (this.width - BG_WIDTH) / 2;
            this.bgTop = (this.height - BG_HEIGHT) / 2;

            int nameX = this.bgLeft + NAME_X0;
            int nameY = this.bgTop + NAME_Y0;
            int nameW = NAME_X1 - NAME_X0;
            int nameH = NAME_Y1 - NAME_Y0;

            this.nameBox = new EditBox(
                    this.font,
                    nameX,
                    nameY,
                    nameW,
                    nameH,
                    Component.translatable("screen.featheredfriend.feathered_friend_naming.input")
            );
            this.nameBox.setMaxLength(MAX_NAME_CHARS);
            this.nameBox.setFocused(true);
            this.nameBox.setValue("");
            this.nameBox.setBordered(false);
            this.nameBox.setTextColor(0xFFFFFF);
            this.nameBox.setTextColorUneditable(0xFFFFFF);

            // Input logic only; we render text manually to support gothic font style.
            this.addWidget(this.nameBox);

            int buttonY = this.bgTop + BG_HEIGHT + 8;
            int buttonW = 95;
            int buttonGap = 10;
            int total = buttonW * 2 + buttonGap;
            int buttonLeft = (this.width - total) / 2;
            this.doneButton = Button.builder(
                            Component.translatable("gui.done"),
                            btn -> onDone()
                    )
                    .bounds(buttonLeft, buttonY, buttonW, 20)
                    .build();

            this.cancelButton = Button.builder(
                            Component.translatable("gui.cancel"),
                            btn -> onCancel()
                    )
                    .bounds(buttonLeft + buttonW + buttonGap, buttonY, buttonW, 20)
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
            refreshFontPreferenceIfNeeded();
            this.caretBlinkTicks++;
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
            this.renderBlurredBackground(partialTick);
            guiGraphics.blit(BG_TEXTURE, this.bgLeft, this.bgTop, 0, 0, BG_WIDTH, BG_HEIGHT, BG_WIDTH, BG_HEIGHT);

            Font renderFont = this.font;

            int titleX = this.bgLeft + TITLE_X0;
            int titleY = this.bgTop + TITLE_Y0;
            int titleW = TITLE_X1 - TITLE_X0;
            int titleH = TITLE_Y1 - TITLE_Y0;

            drawCenteredTextInRect(
                    guiGraphics,
                    renderFont,
                    gothic(Component.translatable("screen.featheredfriend.feathered_friend_naming.title").copy().withStyle(s -> s.withColor(0xFFFFFF))),
                    titleX,
                    titleY,
                    titleW,
                    titleH,
                    0xFFFFFF
            );

            int nameX = this.bgLeft + NAME_X0;
            int nameY = this.bgTop + NAME_Y0;
            int nameW = NAME_X1 - NAME_X0;
            int nameH = NAME_Y1 - NAME_Y0;

            String value = (this.nameBox != null) ? this.nameBox.getValue() : "";
            if (value == null) value = "";
            Component nameComponent = value.isBlank()
                    ? gothic(Component.translatable("screen.featheredfriend.feathered_friend_naming.input"))
                    : gothic(Component.literal(value));

            int nameColor = value.isBlank() ? 0x9FA3A7 : 0x000000;
            drawCenteredTextInRect(guiGraphics, renderFont, nameComponent, nameX, nameY, nameW, nameH, nameColor);

            if (this.nameBox != null && this.nameBox.isFocused() && ((this.caretBlinkTicks / 6) % 2 == 0)) {
                int textW = renderFont.width(nameComponent);
                int caretX = (nameX + (nameW / 2) + (textW / 2)) + 1;
                int caretY = nameY + ((nameH - renderFont.lineHeight) / 2);
                guiGraphics.fill(caretX, caretY, caretX + 1, caretY + renderFont.lineHeight, 0xFF000000);
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (this.nameBox == null) {
                return handled;
            }
            boolean inName =
                    mouseX >= (this.bgLeft + NAME_X0) && mouseX <= (this.bgLeft + NAME_X1) &&
                            mouseY >= (this.bgTop + NAME_Y0) && mouseY <= (this.bgTop + NAME_Y1);
            if (inName) {
                this.nameBox.setFocused(true);
                return true;
            }
            return handled;
        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] mouseClicked failed", t);
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

            LOG.debug("[RavenNamingScreen] onDone: sending name='{}' for ravenEntityId={}", name, ravenEntityId);
            Services.PLATFORM.sendRavenNameChosenToServer(ravenEntityId, name);

            this.onClose();

        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] onDone failed", t);
            this.onClose();
        }
    }

    private void onCancel() {
        try {
            LOG.debug("[RavenNamingScreen] onCancel: closing without sending name (ravenEntityId={})", ravenEntityId);
            Services.PLATFORM.sendRavenNameCancelledToServer(ravenEntityId);
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

    private void refreshFontPreferenceIfNeeded() {
        boolean now = Services.PLATFORM.isUseVanillaFontForGothicText();
        if (now == this.useVanillaFontForGothicText) {
            return;
        }
        this.useVanillaFontForGothicText = now;
    }

    private @NotNull Component gothic(@NotNull Component text) {
        try {
            if (this.useVanillaFontForGothicText) {
                return text;
            }
            MutableComponent m = text.copy();
            Style style = m.getStyle().withFont(GOTHIC_FONT_ID);
            m.setStyle(style);
            return m;
        } catch (Throwable t) {
            LOG.error("[RavenNamingScreen] gothic() failed, falling back to default font", t);
            return text;
        }
    }

    private static void drawCenteredTextInRect(@NotNull GuiGraphics g,
                                               @NotNull Font font,
                                               @NotNull Component text,
                                               int x,
                                               int y,
                                               int w,
                                               int h,
                                               int color) {
        int tx = x + ((w - font.width(text)) / 2);
        int ty = y + ((h - font.lineHeight) / 2);
        g.drawString(font, text, tx, ty, color, false);
    }
}

