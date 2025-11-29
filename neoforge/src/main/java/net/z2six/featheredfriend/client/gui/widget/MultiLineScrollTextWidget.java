// neoforge/src/main/java/net/z2six/featheredfriend/client/gui/widget/MultiLineScrollTextWidget.java
package net.z2six.featheredfriend.client.gui.widget;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
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
 * // neoforge/src/main/java/net/z2six/featheredfriend/client/gui/widget/MultiLineScrollTextWidget.java
 *
 * MultiLineScrollTextWidget
 *
 * A simple multi-line text widget that:
 *  - Stores a single String with optional '\n' characters.
 *  - Wraps text into visual lines based on pixel width.
 *  - Limits the number of visible lines.
 *  - Draws a blinking caret when focused.
 *
 * Supports an optional custom font id (e.g. featheredfriend:gothic12) for rendering.
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

    // Text color is now configurable per-instance (default: black).
    private int textColor = 0x000000;
    private final int placeholderColor = 0x707070;

    private final List<LineInfo> visualLines = new ArrayList<>();

    @Nullable
    private ResourceLocation customFontId;

    // Whether this widget should accept '\n' input (ENTER).
    private final boolean allowNewlines;

    // Selection state (global indices into `text`)
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private int selectionAnchor = -1;

    private record LineInfo(int start, int end) {
    }

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /**
     * Simple constructor with default behavior: allows newlines.
     */
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
        this(font, x, y, width, height, maxChars, maxLines, placeholder, null, true);
    }

    /**
     * Constructor with custom font id and default allowNewlines=true.
     */
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
        this(font, x, y, width, height, maxChars, maxLines, placeholder, customFontId, true);
    }

    /**
     * Full constructor: lets callers choose both font and newline behavior.
     * Matches ScrollSealingScreen’s usage:
     *   new MultiLineScrollTextWidget(..., placeholder, GOTHIC_FONT_ID, allowNewlines)
     */
    public MultiLineScrollTextWidget(
            @NotNull Font font,
            int x,
            int y,
            int width,
            int height,
            int maxChars,
            int maxLines,
            @NotNull Component placeholder,
            @Nullable ResourceLocation customFontId,
            boolean allowNewlines
    ) {
        super(x, y, width, height, placeholder);
        this.font = font;
        this.maxChars = Math.max(1, maxChars);
        this.maxLines = Math.max(1, maxLines);
        this.placeholder = placeholder;
        this.customFontId = customFontId;
        this.allowNewlines = allowNewlines;

        this.setFocused(false);
        this.active = true;
        this.visible = true;

        LOG.debug(
                "[MultiLineScrollTextWidget] Created at ({},{}) size=({},{}) maxChars={} maxLines={} fontId={} allowNewlines={}",
                x, y, width, height, this.maxChars, this.maxLines, this.customFontId, this.allowNewlines
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
        clearSelection();
        reflowLines();
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    public void setCustomFontId(@Nullable ResourceLocation fontId) {
        this.customFontId = fontId;
        LOG.debug("[MultiLineScrollTextWidget] setCustomFontId -> {}", fontId);
    }

    /**
     * Allow callers to override the text color for this widget instance.
     * Used by RecipientOverlay to make the filter text white without affecting other widgets.
     */
    public void setTextColor(int argb) {
        try {
            this.textColor = argb;
            LOG.debug("[MultiLineScrollTextWidget] setTextColor -> 0x{}", Integer.toHexString(argb));
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] setTextColor failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Selection helpers
    // ---------------------------------------------------------------------

    private boolean hasSelection() {
        return selectionStart >= 0 && selectionEnd > selectionStart && selectionEnd <= text.length();
    }

    private void clearSelection() {
        selectionStart = -1;
        selectionEnd = -1;
        selectionAnchor = -1;
    }

    private void selectAll() {
        if (text.isEmpty()) {
            clearSelection();
            cursorIndex = 0;
            return;
        }
        selectionStart = 0;
        selectionEnd = text.length();
        selectionAnchor = 0;
        cursorIndex = text.length();
    }

    private void deleteSelection() {
        if (!hasSelection()) {
            return;
        }

        try {
            int start = selectionStart;
            int end = selectionEnd;

            String before = safeSubstring(this.text, 0, start);
            String after = safeSubstring(this.text, end, this.text.length());

            this.text = before + after;
            this.cursorIndex = start;
            clearSelection();

            LOG.debug("[MultiLineScrollTextWidget] deleteSelection: newText='{}'", this.text);

            reflowLines();
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] deleteSelection failed", t);
        }
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

                int lineStart = info.start();
                int lineEnd = info.end();

                String line = safeSubstring(this.text, lineStart, lineEnd);

                // Strip trailing '\n' for rendering
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\n') {
                    line = line.substring(0, line.length() - 1);
                    lineEnd--;
                }

                // Selection background for this line (if any)
                if (hasSelection()) {
                    int selStart = this.selectionStart;
                    int selEnd = this.selectionEnd;

                    int overlapStart = Math.max(lineStart, selStart);
                    int overlapEnd = Math.min(lineEnd, selEnd);

                    if (overlapStart < overlapEnd) {
                        String beforeSelection = safeSubstring(this.text, lineStart, overlapStart);
                        String selectedPart = safeSubstring(this.text, overlapStart, overlapEnd);

                        // Ignore trailing newline when computing width for highlight
                        if (!selectedPart.isEmpty() && selectedPart.charAt(selectedPart.length() - 1) == '\n') {
                            selectedPart = selectedPart.substring(0, selectedPart.length() - 1);
                        }

                        Component beforeComp = applyCustomFont(Component.literal(beforeSelection));
                        Component selectedComp = applyCustomFont(Component.literal(selectedPart));

                        int baseX = this.getX() + 2;
                        int x0 = baseX + this.font.width(beforeComp);
                        int x1 = x0 + this.font.width(selectedComp);
                        int y0 = lineY;
                        int y1 = lineY + this.font.lineHeight;

                        // Semi-transparent blue-ish selection
                        guiGraphics.fill(x0, y0, x1, y1, 0x8066AAFF);
                    }
                }

                // Draw the text itself
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

            // SPECIAL CASE: caret is immediately after a trailing newline.
            // Visually we want it at the start of a "virtual" next line.
            if (allowNewlines
                    && !this.text.isEmpty()
                    && this.text.charAt(this.text.length() - 1) == '\n'
                    && this.cursorIndex == this.text.length()
                    && !this.visualLines.isEmpty()) {

                int lineIdx = this.visualLines.size(); // virtual empty line after last
                caretX = this.getX() + 2;
                caretY = this.getY() + 2 + (lineIdx * this.font.lineHeight);

                int top = caretY;
                int bottom = caretY + this.font.lineHeight;
                guiGraphics.fill(caretX, top, caretX + 1, bottom, 0xFF000000);
                return;
            }

            int lineIdx = 0;
            boolean placed = false;

            for (LineInfo info : visualLines) {
                if (lineIdx >= maxLines) break;

                int start = info.start();
                int end = info.end();

                if (cursorIndex < start) {
                    break;
                }

                // Boundary at end of a '\n' line belongs to next line.
                if (cursorIndex > end ||
                        (cursorIndex == end && end > start && this.text.charAt(end - 1) == '\n')) {
                    lineIdx++;
                    continue;
                }

                // At this point, start <= cursorIndex <= end, and either cursorIndex < end
                // or cursorIndex == end with last char != '\n'.
                String beforeCaret = safeSubstring(this.text, start, cursorIndex);
                // Remove trailing newline when computing width
                if (!beforeCaret.isEmpty() && beforeCaret.charAt(beforeCaret.length() - 1) == '\n') {
                    beforeCaret = beforeCaret.substring(0, beforeCaret.length() - 1);
                }

                Component beforeComp = applyCustomFont(Component.literal(beforeCaret));
                int width = this.font.width(beforeComp);
                caretX = this.getX() + 2 + width;
                caretY = this.getY() + 2 + (lineIdx * this.font.lineHeight);
                placed = true;
                break;
            }

            if (!placed) {
                // Default: end of all text if we couldn't place it on a line
                Component fullComp = applyCustomFont(Component.literal(this.text));
                caretX = this.getX() + 2 + this.font.width(fullComp);
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

    @SuppressWarnings("unused")
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
            var rm = Minecraft.getInstance().getResourceManager();

            // Looks for assets/<ns>/font/<path>.json
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

        // Carriage return: ignore.
        if (codePoint == '\r') {
            return false;
        }

        // Newlines via charTyped (secondary path; main is keyPressed ENTER).
        if (codePoint == '\n') {
            if (!allowNewlines) {
                return false;
            }
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

        boolean ctrl = Screen.hasControlDown();
        boolean shift = Screen.hasShiftDown();

        try {
            return switch (keyCode) {
                // ENTER / keypad ENTER -> newline (when allowed)
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (allowNewlines) {
                        // NEW: don't move caret / insert newline if it would create a "virtual" line
                        // beyond our maxLines (caret at end of text and already full).
                        if (!canInsertNewlineHere()) {
                            // Consume the key so it doesn't bubble, but do nothing visually.
                            yield true;
                        }
                        insertText("\n");
                        yield true;
                    }
                    yield false;
                }

                // Deletion
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (ctrl) {
                        deletePreviousWord();
                    } else {
                        deleteFromCursor(-1);
                    }
                    yield true;
                }
                case GLFW.GLFW_KEY_DELETE -> {
                    if (ctrl) {
                        deleteNextWord();
                    } else {
                        deleteFromCursor(1);
                    }
                    yield true;
                }

                // Left / Right arrows (with Ctrl / Shift support)
                case GLFW.GLFW_KEY_LEFT -> {
                    moveCursorByWordOrChar(-1, ctrl, shift);
                    yield true;
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    moveCursorByWordOrChar(1, ctrl, shift);
                    yield true;
                }

                // Up / Down arrows: move caret between lines, but DO NOT change focus.
                case GLFW.GLFW_KEY_UP -> {
                    moveCaretVertically(-1);
                    yield true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    moveCaretVertically(1);
                    yield true;
                }

                // HOME: start of current visual line (not whole text)
                case GLFW.GLFW_KEY_HOME -> {
                    moveCursorToStart(shift);
                    yield true;
                }
                // END: end of full text
                case GLFW.GLFW_KEY_END -> {
                    moveCursorToEnd(shift);
                    yield true;
                }

                // Ctrl+A: select all
                case GLFW.GLFW_KEY_A -> {
                    if (ctrl) {
                        selectAll();
                        yield true;
                    }
                    yield false;
                }

                // Clipboard
                case GLFW.GLFW_KEY_C -> {
                    if (ctrl) {
                        copySelectionToClipboard();
                        yield true;
                    }
                    yield false;
                }
                case GLFW.GLFW_KEY_X -> {
                    if (ctrl) {
                        cutSelectionToClipboard();
                        yield true;
                    }
                    yield false;
                }
                case GLFW.GLFW_KEY_V -> {
                    if (ctrl) {
                        pasteFromClipboard();
                        yield true;
                    }
                    yield false;
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

            // Place caret at click position instead of just "end of text"
            int indexAtClick = getIndexAtPosition(mouseX, mouseY);
            this.cursorIndex = Math.max(0, Math.min(indexAtClick, this.text.length()));
            clearSelection();
            this.selectionAnchor = this.cursorIndex;

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
        return c >= 32 && c != 127;
    }

    /**
     * Decide whether we should allow inserting a newline at the current caret position.
     *
     * Special case we care about:
     *  - If the caret is at the *end of the text*
     *  - AND reflowed text already uses all maxLines
     *  -> then inserting a newline would only create a "virtual" extra line where the caret
     *     moves below the visible box, with no room for any characters.
     *  In that case we return false (block ENTER).
     */
    private boolean canInsertNewlineHere() {
        try {
            // If we don't even have text, always allow (first newline just starts a line).
            if (this.text.isEmpty()) {
                return true;
            }

            reflowLines();

            // Only care about the edge case when:
            //  - caret is exactly at the end of the text
            //  - we already have maxLines visible lines
            if (this.cursorIndex == this.text.length() && this.visualLines.size() >= this.maxLines) {
                LOG.debug("[MultiLineScrollTextWidget] Blocking newline: caret at end and maxLines already used");
                return false;
            }

            return true;
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] canInsertNewlineHere failed, allowing newline by default", t);
            // Fail-safe: don't unexpectedly block typing if something goes wrong.
            return true;
        }
    }

    /**
     * Computes the global text index closest to the given mouse position.
     */
    private int getIndexAtPosition(double mouseX, double mouseY) {
        if (this.text.isEmpty() || this.visualLines.isEmpty()) {
            return this.text.length();
        }

        int baseX = this.getX() + 2;
        int baseY = this.getY() + 2;
        int lineHeight = this.font.lineHeight;

        int relY = (int) Math.floor(mouseY - baseY);
        int lineIdx;
        if (relY <= 0) {
            lineIdx = 0;
        } else {
            lineIdx = relY / lineHeight;
        }

        if (lineIdx < 0) {
            lineIdx = 0;
        }
        if (lineIdx >= this.visualLines.size()) {
            lineIdx = this.visualLines.size() - 1;
        }

        LineInfo info = this.visualLines.get(lineIdx);
        int lineStart = info.start();
        int lineEnd = info.end();

        String line = safeSubstring(this.text, lineStart, lineEnd);

        // Strip any trailing newline when computing width positions
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\n') {
            line = line.substring(0, line.length() - 1);
            lineEnd--;
        }

        int relX = (int) Math.floor(mouseX - baseX);
        if (relX <= 0) {
            return lineStart;
        }

        int currentX = 0;
        int lastIndex = lineStart;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            String s = String.valueOf(c);
            Component comp = applyCustomFont(Component.literal(s));
            int w = this.font.width(comp);

            // If click is in the left half of this char, snap before it.
            if (relX < currentX + w / 2) {
                return lineStart + i;
            }

            currentX += w;
            lastIndex = lineStart + i + 1;
        }

        // Click is past end of line -> place at line end (before newline if present)
        return lastIndex;
    }

    /**
     * Compute caret movement to previous/next line, without changing widget focus.
     */
    private void moveCaretVertically(int direction) {
        try {
            reflowLines();
            if (visualLines.isEmpty()) {
                return;
            }

            // SPECIAL CASE: caret is after trailing newline -> treat as virtual line after last.
            if (allowNewlines
                    && !this.text.isEmpty()
                    && this.text.charAt(this.text.length() - 1) == '\n'
                    && this.cursorIndex == this.text.length()
                    && !this.visualLines.isEmpty()) {

                int currentLineIdx = this.visualLines.size(); // virtual line index
                int columnX = 0; // start of line

                int targetLineIdx = currentLineIdx + direction;
                if (targetLineIdx < 0 || targetLineIdx >= this.visualLines.size()) {
                    // No valid target line
                    return;
                }

                LineInfo targetInfo = this.visualLines.get(targetLineIdx);
                int lineStart = targetInfo.start();
                int lineEnd = targetInfo.end();
                String lineText = safeSubstring(this.text, lineStart, lineEnd);

                boolean endsWithNewline = !lineText.isEmpty() && lineText.charAt(lineText.length() - 1) == '\n';
                if (endsWithNewline) {
                    lineText = lineText.substring(0, lineText.length() - 1);
                }

                int runningX = 0;
                int bestIndex = lineStart;
                int bestDiff = Math.abs(columnX);

                for (int i = 0; i <= lineText.length(); i++) {
                    if (i > 0) {
                        char c = lineText.charAt(i - 1);
                        Component comp = applyCustomFont(Component.literal(String.valueOf(c)));
                        runningX += this.font.width(comp);
                    }
                    int diff = Math.abs(runningX - columnX);
                    if (diff <= bestDiff) {
                        bestDiff = diff;
                        bestIndex = lineStart + i;
                    }
                }

                updateCursorAndSelection(bestIndex, false);
                return;
            }

            // Normal vertical movement path.
            int currentLineIdx = 0;
            int columnX = 0;
            boolean found = false;

            for (int i = 0; i < visualLines.size(); i++) {
                LineInfo info = visualLines.get(i);
                int start = info.start();
                int end = info.end();

                if (cursorIndex < start) {
                    break;
                }

                // Boundary at end of a '\n' line belongs to next line.
                if (cursorIndex > end ||
                        (cursorIndex == end && end > start && this.text.charAt(end - 1) == '\n')) {
                    continue;
                }

                String beforeCaret = safeSubstring(this.text, start, cursorIndex);
                if (!beforeCaret.isEmpty() && beforeCaret.charAt(beforeCaret.length() - 1) == '\n') {
                    beforeCaret = beforeCaret.substring(0, beforeCaret.length() - 1);
                }

                Component comp = applyCustomFont(Component.literal(beforeCaret));
                columnX = this.font.width(comp);
                currentLineIdx = i;
                found = true;
                break;
            }

            if (!found) {
                // Fallback: assume last line.
                int lastIdx = visualLines.size() - 1;
                LineInfo info = visualLines.get(lastIdx);
                String beforeCaret = safeSubstring(this.text, info.start(), this.cursorIndex);
                if (!beforeCaret.isEmpty() && beforeCaret.charAt(beforeCaret.length() - 1) == '\n') {
                    beforeCaret = beforeCaret.substring(0, beforeCaret.length() - 1);
                }
                Component comp = applyCustomFont(Component.literal(beforeCaret));
                columnX = this.font.width(comp);
                currentLineIdx = lastIdx;
            }

            int targetLineIdx = currentLineIdx + direction;
            if (targetLineIdx < 0 || targetLineIdx >= visualLines.size()) {
                // No line above/below -> do nothing.
                return;
            }

            // Place caret in target line at position whose x is closest to columnX.
            LineInfo targetInfo = visualLines.get(targetLineIdx);
            int lineStart = targetInfo.start();
            int lineEnd = targetInfo.end();
            String lineText = safeSubstring(this.text, lineStart, lineEnd);

            // Strip visual newline for measuring positions, but keep indices consistent.
            boolean endsWithNewline = !lineText.isEmpty() && lineText.charAt(lineText.length() - 1) == '\n';
            if (endsWithNewline) {
                lineText = lineText.substring(0, lineText.length() - 1);
            }

            int runningX = 0;
            int bestIndex = lineStart;
            int bestDiff = Math.abs(columnX);

            // Evaluate all caret positions between characters (0..lineText.length()).
            for (int i = 0; i <= lineText.length(); i++) {
                if (i > 0) {
                    char c = lineText.charAt(i - 1);
                    Component comp = applyCustomFont(Component.literal(String.valueOf(c)));
                    runningX += this.font.width(comp);
                }
                int diff = Math.abs(runningX - columnX);
                if (diff <= bestDiff) {
                    bestDiff = diff;
                    bestIndex = lineStart + i;
                }
            }

            // Move caret there, no shift-selection for now (keep this simple & robust).
            updateCursorAndSelection(bestIndex, false);
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] moveCaretVertically failed", t);
        }
    }

    private void insertText(@NotNull String toInsert) {
        if (toInsert.isEmpty()) {
            return;
        }

        // Delete selection first, like normal editors do.
        if (hasSelection()) {
            deleteSelection();
        }

        int remaining = maxChars - this.text.length();
        if (remaining <= 0) {
            return;
        }

        int allowed = Math.min(toInsert.length(), remaining);
        String insert = toInsert.substring(0, allowed);

        if (!wouldFitWithInsertion(insert)) {
            LOG.debug("[MultiLineScrollTextWidget] insertText blocked because it would overflow the text box");
            return;
        }

        String before = safeSubstring(this.text, 0, this.cursorIndex);
        String after = safeSubstring(this.text, this.cursorIndex, this.text.length());

        this.text = before + insert + after;
        this.cursorIndex += insert.length();
        clearSelection();
        this.selectionAnchor = this.cursorIndex;

        LOG.debug("[MultiLineScrollTextWidget] insertText: newText='{}'", this.text);

        reflowLines();
    }

    private void deleteFromCursor(int direction) {
        if (this.text.isEmpty()) {
            return;
        }

        // If there's a selection, delete that instead.
        if (hasSelection()) {
            deleteSelection();
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

    private void moveCursorByWordOrChar(int direction, boolean ctrl, boolean shift) {
        int newIndex;
        if (ctrl) {
            newIndex = findWordBoundary(direction);
        } else {
            newIndex = this.cursorIndex + direction;
        }
        newIndex = Math.max(0, Math.min(newIndex, this.text.length()));

        updateCursorAndSelection(newIndex, shift);
    }

    private void updateCursorAndSelection(int newIndex, boolean shift) {
        if (!shift) {
            this.cursorIndex = newIndex;
            clearSelection();
            this.selectionAnchor = newIndex;
        } else {
            if (this.selectionAnchor < 0) {
                this.selectionAnchor = this.cursorIndex;
            }
            this.cursorIndex = newIndex;
            this.selectionStart = Math.min(this.selectionAnchor, this.cursorIndex);
            this.selectionEnd = Math.max(this.selectionAnchor, this.cursorIndex);
        }
    }

    /**
     * HOME: move to start of the *current visual line* (not absolute start of text).
     */
    private void moveCursorToStart(boolean shift) {
        try {
            reflowLines();
            if (visualLines.isEmpty()) {
                updateCursorAndSelection(0, shift);
                return;
            }

            int targetIndex = 0;
            boolean found = false;

            for (LineInfo info : visualLines) {
                int start = info.start();
                int end = info.end();

                if (cursorIndex < start) {
                    break;
                }

                // Boundary at end of '\n' line belongs to next line.
                if (cursorIndex > end ||
                        (cursorIndex == end && end > start && this.text.charAt(end - 1) == '\n')) {
                    continue;
                }

                targetIndex = start;
                found = true;
                break;
            }

            if (!found) {
                targetIndex = 0;
            }

            updateCursorAndSelection(targetIndex, shift);
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] moveCursorToStart(line) failed", t);
            updateCursorAndSelection(0, shift);
        }
    }

    private void moveCursorToEnd(boolean shift) {
        updateCursorAndSelection(this.text.length(), shift);
    }

    private int findWordBoundary(int direction) {
        if (this.text.isEmpty()) {
            return 0;
        }

        int len = this.text.length();
        int idx = this.cursorIndex;

        if (direction < 0) {
            if (idx <= 0) {
                return 0;
            }

            int i = idx;

            // Skip any whitespace directly before the cursor
            while (i > 0 && Character.isWhitespace(this.text.charAt(i - 1))) {
                i--;
            }

            // Skip the previous "word"
            while (i > 0 && !Character.isWhitespace(this.text.charAt(i - 1))) {
                i--;
            }

            return i;
        } else {
            if (idx >= len) {
                return len;
            }

            int i = idx;

            // Skip any whitespace directly after the cursor
            while (i < len && Character.isWhitespace(this.text.charAt(i))) {
                i++;
            }

            // Skip the next "word"
            while (i < len && !Character.isWhitespace(this.text.charAt(i))) {
                i++;
            }

            return i;
        }
    }

    private void deletePreviousWord() {
        if (this.text.isEmpty()) {
            return;
        }

        // If there's a selection, just delete that.
        if (hasSelection()) {
            deleteSelection();
            return;
        }

        int newPos = findWordBoundary(-1);
        if (newPos == this.cursorIndex) {
            return;
        }

        try {
            String before = safeSubstring(this.text, 0, newPos);
            String after = safeSubstring(this.text, this.cursorIndex, this.text.length());
            this.text = before + after;
            this.cursorIndex = newPos;
            clearSelection();
            this.selectionAnchor = this.cursorIndex;

            LOG.debug("[MultiLineScrollTextWidget] deletePreviousWord: newText='{}'", this.text);

            reflowLines();
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] deletePreviousWord failed", t);
        }
    }

    private void deleteNextWord() {
        if (this.text.isEmpty()) {
            return;
        }

        // If there's a selection, just delete that.
        if (hasSelection()) {
            deleteSelection();
            return;
        }

        int newPos = findWordBoundary(1);
        if (newPos == this.cursorIndex) {
            return;
        }

        try {
            String before = safeSubstring(this.text, 0, this.cursorIndex);
            String after = safeSubstring(this.text, newPos, this.text.length());
            this.text = before + after;
            clearSelection();
            this.selectionAnchor = this.cursorIndex;

            LOG.debug("[MultiLineScrollTextWidget] deleteNextWord: newText='{}'", this.text);

            reflowLines();
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] deleteNextWord failed", t);
        }
    }

    private void copySelectionToClipboard() {
        if (!hasSelection()) {
            return;
        }

        try {
            String selected = safeSubstring(this.text, selectionStart, selectionEnd);
            Minecraft mc = Minecraft.getInstance();
            mc.keyboardHandler.setClipboard(selected);
            LOG.debug("[MultiLineScrollTextWidget] Copied selection to clipboard");
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] copySelectionToClipboard failed", t);
        }
    }

    private void cutSelectionToClipboard() {
        if (!hasSelection()) {
            return;
        }

        try {
            String selected = safeSubstring(this.text, selectionStart, selectionEnd);
            Minecraft mc = Minecraft.getInstance();
            mc.keyboardHandler.setClipboard(selected);
            LOG.debug("[MultiLineScrollTextWidget] Cut selection to clipboard");
            deleteSelection();
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] cutSelectionToClipboard failed", t);
        }
    }

    private void pasteFromClipboard() {
        try {
            Minecraft mc = Minecraft.getInstance();
            String clip = mc.keyboardHandler.getClipboard();
            if (clip == null || clip.isEmpty()) {
                return;
            }

            // Normalize line endings
            clip = clip.replace("\r\n", "\n").replace('\r', '\n');

            // If this widget does not allow newlines, strip them.
            if (!allowNewlines) {
                clip = clip.replace("\n", " ");
            }

            insertText(clip);
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] pasteFromClipboard failed", t);
        }
    }

    /**
     * Checks if inserting the given string at the current cursor would still fit inside our
     * width/height (maxLines) constraints.
     */
    private boolean wouldFitWithInsertion(@NotNull String toInsert) {
        try {
            if (toInsert.isEmpty()) {
                return false;
            }

            int remaining = maxChars - this.text.length();
            if (remaining <= 0) {
                return false;
            }

            int allowed = Math.min(toInsert.length(), remaining);
            String insert = toInsert.substring(0, allowed);

            String before = safeSubstring(this.text, 0, this.cursorIndex);
            String after = safeSubstring(this.text, this.cursorIndex, this.text.length());
            String candidate = before + insert + after;

            return wouldFitText(candidate);
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] wouldFitWithInsertion failed", t);
            // Fail safe: don't allow insertion if we couldn't decide.
            return false;
        }
    }

    /**
     * Simulate line wrapping for a given candidate string and check whether it would
     * exceed maxLines.
     */
    private boolean wouldFitText(@NotNull String candidate) {
        if (candidate.isEmpty()) {
            return true;
        }

        int maxWidth = this.width - 4;
        if (maxWidth <= 0) {
            return false;
        }

        int len = candidate.length();
        int idx = 0;
        int lines = 0;

        while (idx < len) {
            lines++;
            if (lines > maxLines) {
                return false;
            }

            int lineStart = idx;
            int lineEnd = idx;
            int lastSpace = -1;
            int currentWidth = 0;

            while (lineEnd < len) {
                char c = candidate.charAt(lineEnd);

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
                lineEnd = Math.min(lineStart + 1, len);
            }

            idx = lineEnd;
        }

        return true;
    }

    private void moveCursor(int delta) {
        int newIndex = this.cursorIndex + delta;
        newIndex = Math.max(0, Math.min(newIndex, this.text.length()));
        this.cursorIndex = newIndex;
        clearSelection();
        this.selectionAnchor = this.cursorIndex;
    }

    private void moveCursorToStart() {
        moveCursorToStart(false);
    }

    private void moveCursorToEnd() {
        moveCursorToEnd(false);
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
