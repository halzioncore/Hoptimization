package com.hoptimization.ui.component;

import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;

import java.util.List;

/**
 * Stateless renderer and CJK + ASCII word/character wrapping utility for floating tooltips.
 * Uses {@code surface4}, popup shadow ({@code offsetY = 8, blur = 24}), {@code caption} ({@code 11px}),
 * and a {@code 220px} maximum logical width per {@code 02_美术规范.md §10}.
 */
public final class TooltipRenderer {
    public static final float MAX_WIDTH = 220.0f;
    public static final float PADDING_H = UiTokens.SPACE_8;
    public static final float PADDING_V = UiTokens.SPACE_6;
    public static final float LINE_GAP = UiTokens.SPACE_2;
    public static final float CURSOR_OFFSET_X = 10.0f;
    public static final float CURSOR_OFFSET_Y = 14.0f;
    public static final float VIEWPORT_MARGIN = UiTokens.SPACE_4;

    private TooltipRenderer() {
    }

    /**
     * Wraps {@code text} into lines fitting within {@code maxTextWidth}, handling explicit newlines,
     * CJK character boundaries, and ASCII word/character breaks.
     *
     * @param font         font renderer used to measure glyphs
     * @param text         source tooltip text
     * @param maxTextWidth maximum line width in logical pixels
     * @param outLines     destination list cleared and populated with wrapped lines
     */
    public static void wrapText(
        TtfFontRenderer font,
        String text,
        float maxTextWidth,
        List<String> outLines
    ) {
        outLines.clear();
        if (text == null || text.isEmpty() || maxTextWidth <= 0.0f) {
            return;
        }
        int len = text.length();
        int paraStart = 0;
        for (int i = 0; i <= len; i++) {
            if (i == len || text.charAt(i) == '\n') {
                int paraEnd = i;
                if (paraEnd > paraStart && text.charAt(paraEnd - 1) == '\r') {
                    paraEnd--;
                }
                wrapParagraph(font, text, paraStart, paraEnd, maxTextWidth, outLines);
                paraStart = i + 1;
            }
        }
    }

    /**
     * Renders pre-wrapped tooltip lines inside a {@code surface4} floating card with popup shadow,
     * clamped inside the provided viewport bounds.
     *
     * @param lines          pre-wrapped tooltip lines
     * @param boxWidth       precomputed tooltip card width
     * @param boxHeight      precomputed tooltip card height
     * @param anchorX        preferred logical X (usually cursor X + offset)
     * @param anchorY        preferred logical Y (usually cursor Y + offset)
     * @param viewportX      viewport left bound
     * @param viewportY      viewport top bound
     * @param viewportWidth  viewport width
     * @param viewportHeight viewport height
     * @param alpha          effective tooltip opacity in {@code [0, 1]}
     * @param theme          active theme data
     * @param font           caption font renderer
     */
    public static void renderWrapped(
        List<String> lines,
        float boxWidth,
        float boxHeight,
        float anchorX,
        float anchorY,
        float viewportX,
        float viewportY,
        float viewportWidth,
        float viewportHeight,
        float alpha,
        ThemeData theme,
        TtfFontRenderer font
    ) {
        if (lines == null || lines.isEmpty() || alpha <= 0.001f || theme == null || font == null) {
            return;
        }

        float drawX = anchorX;
        float drawY = anchorY;

        float rightLimit = viewportX + viewportWidth - VIEWPORT_MARGIN;
        float bottomLimit = viewportY + viewportHeight - VIEWPORT_MARGIN;
        if (drawX + boxWidth > rightLimit) {
            drawX = Math.max(viewportX + VIEWPORT_MARGIN, rightLimit - boxWidth);
        }
        if (drawX < viewportX + VIEWPORT_MARGIN) {
            drawX = viewportX + VIEWPORT_MARGIN;
        }

        if (drawY + boxHeight > bottomLimit) {
            float flippedY = anchorY - CURSOR_OFFSET_Y - UiTokens.SPACE_6 - boxHeight;
            if (flippedY >= viewportY + VIEWPORT_MARGIN) {
                drawY = flippedY;
            } else {
                drawY = Math.max(viewportY + VIEWPORT_MARGIN, bottomLimit - boxHeight);
            }
        }
        if (drawY < viewportY + VIEWPORT_MARGIN) {
            drawY = viewportY + VIEWPORT_MARGIN;
        }

        float radius = theme.radiusControl();
        if (com.hoptimization.ui.theme.ThemeManager.isFrostedGlass()) {
            boolean isLight = theme.getMode() == com.hoptimization.ui.theme.ThemeMode.LIGHT;
            RenderUtil.drawFrostedRoundedRectWithShadow(
                drawX,
                drawY,
                boxWidth,
                boxHeight,
                radius,
                RenderUtil.withAlpha(theme.surface4(), isLight ? 0.76f : 0.68f),
                isLight ? theme.borderStrong() : 0x38FFFFFF,
                UiTokens.BORDER_WIDTH,
                isLight ? 0.14f : 0.09f,
                alpha,
                0.0f,
                UiTokens.SHADOW_POPUP_OFFSET_Y,
                UiTokens.SHADOW_POPUP_BLUR,
                theme.popupShadowColor()
            );
        } else {
            RenderUtil.drawRoundedRectWithShadow(
                drawX,
                drawY,
                boxWidth,
                boxHeight,
                radius,
                RenderUtil.withAlpha(theme.surface4(), alpha),
                RenderUtil.withAlpha(theme.border(), alpha),
                UiTokens.BORDER_WIDTH,
                0.0f,
                UiTokens.SHADOW_POPUP_OFFSET_Y,
                UiTokens.SHADOW_POPUP_BLUR,
                RenderUtil.withAlpha(theme.popupShadowColor(), alpha)
            );
        }

        float textX = drawX + PADDING_H;
        float lineY = drawY + PADDING_V;
        float lineStep = font.getHeight() + LINE_GAP;
        int textColor = RenderUtil.withAlpha(theme.textPrimary(), alpha);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isEmpty()) {
                font.drawString(line, textX, lineY, textColor);
            }
            lineY += lineStep;
        }
    }

    /**
     * Measures the maximum line width among wrapped lines.
     *
     * @param font  caption font renderer
     * @param lines wrapped lines
     * @return widest line width in logical pixels
     */
    public static float measureMaxLineWidth(TtfFontRenderer font, List<String> lines) {
        float max = 0.0f;
        for (int i = 0; i < lines.size(); i++) {
            float w = font.getWidth(lines.get(i));
            if (w > max) {
                max = w;
            }
        }
        return max;
    }

    /**
     * Computes the tooltip card height for {@code lineCount} lines using the supplied font.
     *
     * @param font      caption font renderer
     * @param lineCount number of wrapped lines
     * @return card height in logical pixels
     */
    public static float computeBoxHeight(TtfFontRenderer font, int lineCount) {
        if (lineCount <= 0) {
            return 0.0f;
        }
        return lineCount * font.getHeight()
            + Math.max(0, lineCount - 1) * LINE_GAP
            + PADDING_V * 2.0f;
    }

    /**
     * Returns the standard caption font renderer for tooltips.
     *
     * @return {@code caption} ({@code 11px}) font renderer
     */
    public static TtfFontRenderer getTooltipFont() {
        return FontManager.caption();
    }

    private static void wrapParagraph(
        TtfFontRenderer font,
        String text,
        int start,
        int end,
        float maxTextWidth,
        List<String> outLines
    ) {
        if (start >= end) {
            outLines.add("");
            return;
        }

        StringBuilder line = new StringBuilder(32);
        int i = start;
        while (i < end) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (cp == ' ' || cp == '\t') {
                if (line.length() > 0) {
                    String candidate = line.toString() + " ";
                    if (font.getWidth(candidate) <= maxTextWidth) {
                        line.append(' ');
                    } else {
                        flushTrimmedLine(line, outLines);
                    }
                }
                i += charCount;
                continue;
            }

            if (isCjkOrBreakable(cp)) {
                String ch = text.substring(i, i + charCount);
                if (line.length() > 0 && font.getWidth(line.toString() + ch) > maxTextWidth) {
                    flushTrimmedLine(line, outLines);
                }
                line.append(ch);
                i += charCount;
                continue;
            }

            // Collect contiguous ASCII/Latin word so words stay unbroken unless wider than maxTextWidth.
            int wordEnd = i + charCount;
            while (wordEnd < end) {
                int nextCp = text.codePointAt(wordEnd);
                if (nextCp == ' ' || nextCp == '\t' || isCjkOrBreakable(nextCp)) {
                    break;
                }
                wordEnd += Character.charCount(nextCp);
            }
            String word = text.substring(i, wordEnd);
            if (line.length() > 0) {
                if (font.getWidth(line.toString() + word) <= maxTextWidth) {
                    line.append(word);
                } else {
                    flushTrimmedLine(line, outLines);
                    appendWordWithCharFallback(font, word, maxTextWidth, line, outLines);
                }
            } else {
                appendWordWithCharFallback(font, word, maxTextWidth, line, outLines);
            }
            i = wordEnd;
        }

        if (line.length() > 0) {
            flushTrimmedLine(line, outLines);
        }
    }

    private static void appendWordWithCharFallback(
        TtfFontRenderer font,
        String word,
        float maxTextWidth,
        StringBuilder line,
        List<String> outLines
    ) {
        if (font.getWidth(word) <= maxTextWidth) {
            line.append(word);
            return;
        }
        int idx = 0;
        int wordLen = word.length();
        while (idx < wordLen) {
            int cp = word.codePointAt(idx);
            int step = Character.charCount(cp);
            String ch = word.substring(idx, idx + step);
            if (line.length() > 0 && font.getWidth(line.toString() + ch) > maxTextWidth) {
                flushTrimmedLine(line, outLines);
            }
            line.append(ch);
            idx += step;
        }
    }

    private static void flushTrimmedLine(StringBuilder line, List<String> outLines) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        outLines.add(line.substring(0, end));
        line.setLength(0);
    }

    /**
     * Identifies CJK ideographs, kana, hangul, and fullwidth/CJK punctuation that can wrap per character.
     */
    static boolean isCjkOrBreakable(int cp) {
        if (cp >= 0x4E00 && cp <= 0x9FFF) {
            return true;
        }
        if (cp >= 0x3400 && cp <= 0x4DBF) {
            return true;
        }
        if (cp >= 0x3000 && cp <= 0x30FF) {
            return true;
        }
        if (cp >= 0xF900 && cp <= 0xFAFF) {
            return true;
        }
        if (cp >= 0xFF00 && cp <= 0xFFEF) {
            return true;
        }
        return cp >= 0xAC00 && cp <= 0xD7AF;
    }
}
