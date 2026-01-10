// MultiLineScrollTextWidget.java
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
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * MultiLineScrollTextWidget
 *
 * (Logic unchanged; only removed JetBrains annotations for Forge 1.20.1 compile.)
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

    private int textColor = 0x000000;
    private int placeholderColor = 0x707070;

    private int alpha = 255;

    private final List<LineInfo> visualLines = new ArrayList<>();

    private ResourceLocation customFontId;

    private final boolean allowNewlines;

    private int selectionStart = -1;
    private int selectionEnd = -1;
    private int selectionAnchor = -1;

    private final HashMap<Integer, Integer> charWidthCache = new HashMap<>();

    private record LineInfo(int start, int end) {
    }

    public MultiLineScrollTextWidget(
            Font font,
            int x,
            int y,
            int width,
            int height,
            int maxChars,
            int maxLines,
            Component placeholder
    ) {
        this(font, x, y, width, height, maxChars, maxLines, placeholder, null, true);
    }

    public MultiLineScrollTextWidget(
            Font font,
            int x,
            int y,
            int width,
            int height,
            int maxChars,
            int maxLines,
            Component placeholder,
            ResourceLocation customFontId
    ) {
        this(font, x, y, width, height, maxChars, maxLines, placeholder, customFontId, true);
    }

    public MultiLineScrollTextWidget(
            Font font,
            int x,
            int y,
            int width,
            int height,
            int maxChars,
            int maxLines,
            Component placeholder,
            ResourceLocation customFontId,
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

    public void setCustomFontId(ResourceLocation fontId) {
        this.customFontId = fontId;

        try {
            this.charWidthCache.clear();
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] Failed to clear font caches", t);
        }

        LOG.debug("[MultiLineScrollTextWidget] setCustomFontId -> {}", fontId);
    }

    public void setTextColor(int argb) {
        try {
            this.textColor = argb;
            LOG.debug("[MultiLineScrollTextWidget] setTextColor -> 0x{}", Integer.toHexString(argb));
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] setTextColor failed", t);
        }
    }

    public void setPlaceholderColor(int rgb) {
        try {
            this.placeholderColor = rgb;
            LOG.debug("[MultiLineScrollTextWidget] setPlaceholderColor -> 0x{}", Integer.toHexString(rgb));
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] setPlaceholderColor failed", t);
        }
    }

    public void setAlpha(int alpha) {
        try {
            if (alpha < 0) alpha = 0;
            if (alpha > 255) alpha = 255;
            this.alpha = alpha;
            LOG.debug("[MultiLineScrollTextWidget] setAlpha -> {}", this.alpha);
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] setAlpha failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Selection helpers
    // ---------------------------------------------------------------------

    private boolean hasSelection() {
        return selectionStart >= 0 && selectionEnd > selectionStart && selectionEnd <= text.length();
    }

    public void setCursorToEnd() {
        try {
            moveCursorToEnd(false);
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] setCursorToEnd failed", t);
        }
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
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            if (this.text.isEmpty() && !this.isFocused()) {
                drawStringWithFont(
                        guiGraphics,
                        this.placeholder,
                        this.getX() + 2,
                        this.getY() + 2,
                        placeholderColor
                );
                return;
            }

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

                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\n') {
                    line = line.substring(0, line.length() - 1);
                    lineEnd--;
                }

                if (hasSelection()) {
                    int selStart = this.selectionStart;
                    int selEnd = this.selectionEnd;

                    int overlapStart = Math.max(lineStart, selStart);
                    int overlapEnd = Math.min(lineEnd, selEnd);

                    if (overlapStart < overlapEnd) {
                        String beforeSelection = safeSubstring(this.text, lineStart, overlapStart);
                        String selectedPart = safeSubstring(this.text, overlapStart, overlapEnd);

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

                        guiGraphics.fill(x0, y0, x1, y1, 0x8066AAFF);
                    }
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

            if (this.isFocused() && (this.tickCount / 6) % 2 == 0) {
                drawCaret(guiGraphics);
            }
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] renderWidget failed", t);
        }
    }

    private void drawCaret(GuiGraphics guiGraphics) {
        try {
            int caretX = this.getX() + 2;
            int caretY = this.getY() + 2;

            reflowLines();

            if (allowNewlines
                    && !this.text.isEmpty()
                    && this.text.charAt(this.text.length() - 1) == '\n'
                    && this.cursorIndex == this.text.length()
                    && !this.visualLines.isEmpty()) {

                int lineIdx = this.visualLines.size();
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

                if (cursorIndex > end ||
                        (cursorIndex == end && end > start && this.text.charAt(end - 1) == '\n')) {
                    lineIdx++;
                    continue;
                }

                String beforeCaret = safeSubstring(this.text, start, cursorIndex);
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
            GuiGraphics guiGraphics,
            Component base,
            int x,
            int y,
            int color
    ) {
        Component toDraw = applyCustomFont(base);
        int argb = (this.alpha << 24) | (color & 0x00FFFFFF);
        guiGraphics.drawString(this.font, toDraw, x, y, argb, false);
    }

    private int measureCharWidth(char c) {
        try {
            int fontHash = (this.customFontId != null) ? this.customFontId.hashCode() : 0;
            int key = (fontHash * 31) ^ (int) c;

            Integer cached = this.charWidthCache.get(key);
            if (cached != null) {
                return cached;
            }

            Component comp = applyCustomFont(Component.literal(String.valueOf(c)));
            int w = this.font.width(comp);

            this.charWidthCache.put(key, w);
            return w;
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] measureCharWidth failed, falling back to vanilla width", t);
            try {
                return this.font.width(String.valueOf(c));
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    private Component applyCustomFont(Component base) {
        if (this.customFontId == null) {
            return base;
        }

        try {
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
    // Input handling (unchanged)
    // ---------------------------------------------------------------------

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.isFocused() || !this.active || !this.editable) {
            return false;
        }

        if (codePoint == '\r') {
            return false;
        }

        if (codePoint == '\n') {
            if (!allowNewlines) {
                return false;
            }
            try {
                if (!canInsertNewlineHere()) {
                    return true;
                }
                insertText("\n");
                return true;
            } catch (Throwable t) {
                LOG.error("[MultiLineScrollTextWidget] charTyped newline failed", t);
                return false;
            }
        }

        if (!isAllowedCharacter(codePoint)) {
            return false;
        }

        try {
            if (this.text.length() >= maxChars) {
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
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (allowNewlines) {
                        if (!canInsertNewlineHere()) {
                            yield true;
                        }
                        insertText("\n");
                        yield true;
                    }
                    yield false;
                }

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

                case GLFW.GLFW_KEY_LEFT -> {
                    moveCursorByWordOrChar(-1, ctrl, shift);
                    yield true;
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    moveCursorByWordOrChar(1, ctrl, shift);
                    yield true;
                }

                case GLFW.GLFW_KEY_UP -> {
                    moveCaretVertically(-1);
                    yield true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    moveCaretVertically(1);
                    yield true;
                }

                case GLFW.GLFW_KEY_HOME -> {
                    moveCursorToStart(shift);
                    yield true;
                }
                case GLFW.GLFW_KEY_END -> {
                    moveCursorToEnd(shift);
                    yield true;
                }

                case GLFW.GLFW_KEY_A -> {
                    if (ctrl) {
                        selectAll();
                        yield true;
                    }
                    yield false;
                }

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
    // Internal helpers (unchanged)
    // ---------------------------------------------------------------------

    private static boolean isAllowedCharacter(char c) {
        return c >= 32 && c != 127;
    }

    private boolean canInsertNewlineHere() {
        try {
            if (this.text.isEmpty()) {
                return true;
            }

            reflowLines();

            if (this.cursorIndex == this.text.length() && this.visualLines.size() >= this.maxLines) {
                LOG.debug("[MultiLineScrollTextWidget] Blocking newline: caret at end and maxLines already used");
                return false;
            }

            return true;
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] canInsertNewlineHere failed, allowing newline by default", t);
            return true;
        }
    }

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
            Component comp = applyCustomFont(Component.literal(String.valueOf(c)));
            int w = this.font.width(comp);

            if (relX < currentX + w / 2) {
                return lineStart + i;
            }

            currentX += w;
            lastIndex = lineStart + i + 1;
        }

        return lastIndex;
    }

    private void moveCaretVertically(int direction) {
        try {
            reflowLines();
            if (visualLines.isEmpty()) {
                return;
            }

            if (allowNewlines
                    && !this.text.isEmpty()
                    && this.text.charAt(this.text.length() - 1) == '\n'
                    && this.cursorIndex == this.text.length()
                    && !this.visualLines.isEmpty()) {

                int currentLineIdx = this.visualLines.size();
                int columnX = 0;

                int targetLineIdx = currentLineIdx + direction;
                if (targetLineIdx < 0 || targetLineIdx >= this.visualLines.size()) {
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
                return;
            }

            LineInfo targetInfo = visualLines.get(targetLineIdx);
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
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] moveCaretVertically failed", t);
        }
    }

    private void insertText(String toInsert) {
        if (toInsert.isEmpty()) {
            return;
        }

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

        if (hasSelection()) {
            deleteSelection();
            return;
        }

        if (direction < 0) {
            if (this.cursorIndex <= 0) {
                return;
            }
            String before = safeSubstring(this.text, 0, this.cursorIndex - 1);
            String after = safeSubstring(this.text, this.cursorIndex, this.text.length());
            this.text = before + after;
            this.cursorIndex--;
        } else if (direction > 0) {
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

            while (i > 0 && Character.isWhitespace(this.text.charAt(i - 1))) {
                i--;
            }

            while (i > 0 && !Character.isWhitespace(this.text.charAt(i - 1))) {
                i--;
            }

            return i;
        } else {
            if (idx >= len) {
                return len;
            }

            int i = idx;

            while (i < len && Character.isWhitespace(this.text.charAt(i))) {
                i++;
            }

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
            if (mc == null) {
                return;
            }
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
            if (mc == null) {
                return;
            }
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
            if (mc == null) {
                return;
            }
            String clip = mc.keyboardHandler.getClipboard();
            if (clip == null || clip.isEmpty()) {
                return;
            }

            clip = clip.replace("\r\n", "\n").replace('\r', '\n');

            if (!allowNewlines) {
                clip = clip.replace("\n", " ");
            }

            insertText(clip);
        } catch (Throwable t) {
            LOG.error("[MultiLineScrollTextWidget] pasteFromClipboard failed", t);
        }
    }

    private boolean wouldFitWithInsertion(String toInsert) {
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
            return false;
        }
    }

    private boolean wouldFitText(String candidate) {
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
                    lineEnd++;
                    break;
                }

                int charWidth = measureCharWidth(c);

                if (currentWidth + charWidth > maxWidth) {
                    if (lastSpace > lineStart) {
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
                lineEnd = Math.min(lineStart + 1, len);
            }

            idx = lineEnd;
        }

        return true;
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
                        lineEnd++;
                        break;
                    }

                    int charWidth = measureCharWidth(c);

                    if (currentWidth + charWidth > maxWidth) {
                        if (lastSpace > lineStart) {
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

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput);
    }
}
