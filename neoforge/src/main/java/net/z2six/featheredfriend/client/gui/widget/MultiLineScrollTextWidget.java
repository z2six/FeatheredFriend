// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/widget/MultiLineScrollTextWidget.java
package net.z2six.featheredfriend.client.gui.widget;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * MultiLineScrollTextWidget
 *
 * A simple multi-line text widget that:
 *  - Stores a single String with optional '\n' characters.
 *  - Wraps text into visual lines based on pixel width.
 *  - Limits the number of visible lines.
 *  - Draws a blinking caret when focused.
 *
 * Supports an optional custom font id (e.g. featheredfriend:gothic12_8) for rendering.
 */
public class MultiLineScrollTextWidget extends AbstractWidget {

    private static final Logger LOG = LogUtils.getLogger();

    private final Font font;
    private final int maxChars;
    private final int maxLines;
    private final Component placeholder;
    private boolean editable = true;

    private String text = "";
    private int cursorIndex = 0;
    private int tickCount = 0;

    private final int textColor = 0x000000;
    private final int placeholderColor = 0x707070;

    private final List<LineInfo> visualLines = new ArrayList<>();

    @Nullable
    private ResourceLocation customFontId;

    private record LineInfo(int start, int end) {
    }

    public MultiLineScrollTextWidget(
            @NotNull Font font,
            int x,
            int y,
            int width,
            int height,
            int maxChars,
            int maxLines,
            @NotNull Component placeholder
    ) {
        this(font, x, y, width, height, maxChars, maxLines, placeholder, null);
    }

    public MultiLineScrollTextWidget(
            @NotNull Font font,
            int x,
            int y,
            int width,
            int height,
            int maxChars,
            int maxLines,
            @NotNull Component placeholder,
            @Nullable ResourceLocation customFontId
    ) {
        super(x, y, width, height, placeholder);
        this.font = font;
        this.maxChars = Math.max(1, maxChars);
        this.maxLines = Math.max(1, maxLines);
        this.placeholder = placeholder;
        this.customFontId = customFontId;

        this.setFocused(false);
        this.active = true;
        this.visible = true;

        LOG.debug(
                "[MultiLineScrollTextWidget] Created at ({},{}) size=({},{}) maxChars={} maxLines={} fontId={}",
                x, y, width, height, this.maxChars, this.maxLines, this.customFontId
        );

        reflowLines();
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    public void tick() {
        this.tickCount++;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String newText) {
        if (newText == null) {
            newText = "";
        }
        if (newText.length() > maxChars) {
            newText = newText.substring(0, maxChars);
        }
        this.text = newText;
        this.cursorIndex = Math.min(this.cursorIndex, this.text.length());
        reflowLines();
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    public void setCustomFontId(@Nullable ResourceLocation fontId) {
        this.customFontId = fontId;
        LOG.debug("[MultiLineScrollTextWidget] setCustomFontId -> {}", fontId);
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    @Override
    protected void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            // No background; parchment art from the screen is visible.

            if (this.text.isEmpty() && !this.isFocused()) {
                // Placeholder text
                drawStringWithFont(
                        guiGraphics,
                        this.placeholder,
                        this.getX() + 2,
                        this.getY() + 2,
                        placeholderColor
                );
                return;
            }

            // Ensure visual lines are up-to-date
            reflowLines();

            int lineY = this.getY() + 2;
            int lineIndex = 0;

            for (LineInfo info : visualLines) {
                if (lineIndex >= maxLines) {
                    break;
                }
                String line = safeSubstring(this.text, info.start(), info.end());

                // Strip trailing '\n' for rendering
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\n') {
                    line = line.substring(0, line.length() - 1);
                }

                drawStringWithFont(
                        guiGraphics,
                        Component.literal(line),
                        this.getX() + 2,
                        lineY,
                        textColor
                );

                lineY += this.font.lineHeight;
                lineIndex++;
            }

            // Caret
            if (this.isFocused() && (this.tickCount / 6) % 2 == 0) {
                drawCaret(guiGraphics);
            }
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] renderWidget failed", t);
        }
    }

    private void drawCaret(@NotNull GuiGraphics guiGraphics) {
        try {
            int caretX = this.getX() + 2;
            int caretY = this.getY() + 2;

            // Recompute in case something changed
            reflowLines();

            int lineIdx = 0;
            boolean placed = false;

            for (LineInfo info : visualLines) {
                if (lineIdx >= maxLines) break;

                int start = info.start();
                int end = info.end();

                if (cursorIndex < start) {
                    break;
                }

                if (cursorIndex <= end) {
                    String beforeCaret = safeSubstring(this.text, start, cursorIndex);
                    // Remove trailing newline when computing width
                    if (!beforeCaret.isEmpty() && beforeCaret.charAt(beforeCaret.length() - 1) == '\n') {
                        beforeCaret = beforeCaret.substring(0, beforeCaret.length() - 1);
                    }

                    int width = this.font.width(beforeCaret);
                    caretX = this.getX() + 2 + width;
                    caretY = this.getY() + 2 + (lineIdx * this.font.lineHeight);
                    placed = true;
                    break;
                }

                lineIdx++;
            }

            if (!placed) {
                // Default: end of first line if we couldn't place it
                caretX = this.getX() + 2 + this.font.width(this.text);
                caretY = this.getY() + 2;
            }

            int top = caretY;
            int bottom = caretY + this.font.lineHeight;
            guiGraphics.fill(caretX, top, caretX + 1, bottom, 0xFF000000);
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] drawCaret failed", t);
        }
    }

    private void drawStringWithFont(
            @NotNull GuiGraphics guiGraphics,
            @NotNull Component base,
            int x,
            int y,
            int color
    ) {
        Component toDraw = applyCustomFont(base);
        guiGraphics.drawString(this.font, toDraw, x, y, color, false);
    }

    private void drawStringWithFont(
            @NotNull GuiGraphics guiGraphics,
            @NotNull Component base,
            int x,
            int y,
            int color,
            boolean shadow
    ) {
        Component toDraw = applyCustomFont(base);
        guiGraphics.drawString(this.font, toDraw, x, y, color, shadow);
    }

    private Component applyCustomFont(@NotNull Component base) {
        if (this.customFontId == null) {
            return base;
        }

        try {
            // Defensive: if the font family isn't actually loaded, don't force it (avoids tofu)
            var rm = Minecraft.getInstance().getResourceManager();

            // This looks for assets/<ns>/font/<path>.json
            ResourceLocation fontJson = ResourceLocation.fromNamespaceAndPath(
                    this.customFontId.getNamespace(),
                    "font/" + this.customFontId.getPath() + ".json"
            );

            boolean exists = rm.getResource(fontJson).isPresent();

            if (!exists) {
                LOG.debug("[MultiLineScrollTextWidget] Font resource {} not present, falling back to default", fontJson);
                return base;
            }

            MutableComponent mutable = base.copy();
            Style style = mutable.getStyle().withFont(this.customFontId);
            mutable.setStyle(style);
            return mutable;
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] applyCustomFont failed, falling back to default font", t);
            return base;
        }
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.isFocused() || !this.active || !this.editable) {
            return false;
        }

        // Allow newline input now – this is the main text area use case.
        if (codePoint == '\r') {
            return false;
        }

        if (codePoint == '\n') {
            try {
                insertText("\n");
                return true;
            } catch (Throwable t) {
                LOG.error("[MultiLineScrollTextWidget] charTyped newline failed", t);
                return false;
            }
        }

        // Simple "allowed character" check: no control chars except space
        if (!isAllowedCharacter(codePoint)) {
            return false;
        }

        try {
            if (this.text.length() >= maxChars) {
                // Consume input but don't add any more characters.
                return true;
            }

            insertText(String.valueOf(codePoint));
            return true;
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] charTyped failed", t);
            return false;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.isFocused() || !this.active || !this.editable) {
            return false;
        }

        try {
            return switch (keyCode) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    deleteFromCursor(-1);
                    yield true;
                }
                case GLFW.GLFW_KEY_DELETE -> {
                    deleteFromCursor(1);
                    yield true;
                }
                case GLFW.GLFW_KEY_LEFT -> {
                    moveCursor(-1);
                    yield true;
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    moveCursor(1);
                    yield true;
                }
                case GLFW.GLFW_KEY_HOME -> {
                    moveCursorToStart();
                    yield true;
                }
                case GLFW.GLFW_KEY_END -> {
                    moveCursorToEnd();
                    yield true;
                }
                default -> false;
            };
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] keyPressed failed", t);
            return false;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible) {
            return false;
        }

        if (button != 0) {
            return false;
        }

        boolean inside =
                mouseX >= this.getX() && mouseX < this.getX() + this.width &&
                        mouseY >= this.getY() && mouseY < this.getY() + this.height;

        if (!inside) {
            return false;
        }

        try {
            this.setFocused(true);
            // For now: put cursor at end when clicking.
            this.cursorIndex = this.text.length();
            return true;
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] mouseClicked failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private static boolean isAllowedCharacter(char c) {
        // Roughly: printable ASCII + whatever else the user types, minus control chars.
        // You can tighten this later if needed.
        return c >= 32 && c != 127;
    }

    private void insertText(@NotNull String toInsert) {
        if (toInsert.isEmpty()) {
            return;
        }

        int allowed = Math.min(toInsert.length(), maxChars - this.text.length());
        if (allowed <= 0) {
            return;
        }

        String insert = toInsert.substring(0, allowed);
        String before = safeSubstring(this.text, 0, this.cursorIndex);
        String after = safeSubstring(this.text, this.cursorIndex, this.text.length());

        this.text = before + insert + after;
        this.cursorIndex += insert.length();

        LOG.debug("[MultiLineScrollTextWidget] insertText: newText='{}'", this.text);

        reflowLines();
    }

    private void deleteFromCursor(int direction) {
        if (this.text.isEmpty()) {
            return;
        }

        if (direction < 0) {
            // Backspace
            if (this.cursorIndex <= 0) {
                return;
            }
            String before = safeSubstring(this.text, 0, this.cursorIndex - 1);
            String after = safeSubstring(this.text, this.cursorIndex, this.text.length());
            this.text = before + after;
            this.cursorIndex--;
        } else if (direction > 0) {
            // Delete
            if (this.cursorIndex >= this.text.length()) {
                return;
            }
            String before = safeSubstring(this.text, 0, this.cursorIndex);
            String after = safeSubstring(this.text, this.cursorIndex + 1, this.text.length());
            this.text = before + after;
        }

        LOG.debug("[MultiLineScrollTextWidget] deleteFromCursor: newText='{}'", this.text);

        reflowLines();
    }

    private void moveCursor(int delta) {
        int newIndex = this.cursorIndex + delta;
        newIndex = Math.max(0, Math.min(newIndex, this.text.length()));
        this.cursorIndex = newIndex;
    }

    private void moveCursorToStart() {
        this.cursorIndex = 0;
    }

    private void moveCursorToEnd() {
        this.cursorIndex = this.text.length();
    }

    private void reflowLines() {
        try {
            this.visualLines.clear();
            if (this.text.isEmpty()) {
                return;
            }

            int maxWidth = this.width - 4;
            if (maxWidth <= 0) {
                return;
            }

            int idx = 0;
            String t = this.text;

            while (idx < t.length() && this.visualLines.size() < maxLines) {
                int lineStart = idx;
                int lineEnd = idx;
                int lastSpace = -1;
                int currentWidth = 0;

                while (lineEnd < t.length()) {
                    char c = t.charAt(lineEnd);

                    if (c == '\n') {
                        lineEnd++; // include newline in this logical line
                        break;
                    }

                    int charWidth = font.width(String.valueOf(c));
                    if (currentWidth + charWidth > maxWidth) {
                        if (lastSpace > lineStart) {
                            // Wrap at last space
                            lineEnd = lastSpace + 1;
                        }
                        break;
                    }

                    currentWidth += charWidth;
                    if (c == ' ') {
                        lastSpace = lineEnd;
                    }
                    lineEnd++;
                }

                if (lineEnd == lineStart) {
                    // Safety: avoid infinite loops if something goes wrong.
                    lineEnd = Math.min(lineStart + 1, t.length());
                }

                this.visualLines.add(new LineInfo(lineStart, lineEnd));
                idx = lineEnd;
            }
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] reflowLines failed", t);
        }
    }

    private static String safeSubstring(String s, int start, int end) {
        int len = s.length();
        int s0 = Math.max(0, Math.min(start, len));
        int e0 = Math.max(0, Math.min(end, len));
        if (s0 >= e0) {
            return "";
        }
        return s.substring(s0, e0);
    }

    // ---------------------------------------------------------------------
    // Narration
    // ---------------------------------------------------------------------

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput narrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput);
    }
}
