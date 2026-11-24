package com.hoptimization.modules.ui;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.FontToken;
import com.hoptimization.ui.font.VanillaTtfFontBridge;
import com.hoptimization.ui.hud.HudElement;
import com.hoptimization.ui.hud.HudOverlayRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Modern, soft-styled custom Scoreboard HUD replacing vanilla's dim grey sidebar box.
 * Supports frosted glass / theme rounded cards, full {@code §} formatting codes, optional hiding of
 * right-hand red score numbers, and drag-and-scale positioning in {@link com.hoptimization.ui.hud.HudEditScreen}.
 */
public class ScoreboardHud extends Module implements HudElement {
    public static final String ID = "scoreboard";

    private static final int MAX_ROWS = 15;
    private static final float TITLE_FONT_SIZE = FontToken.CAPTION.getSize();
    private static final float ROW_FONT_SIZE = FontToken.CAPTION.getSize();
    private static final float PAD_X = UiTokens.HUD_PADDING_H;
    private static final float PAD_Y = UiTokens.SPACE_8;
    private static final float TITLE_HEIGHT = 14.0f;
    private static final float DIVIDER_GAP = UiTokens.SPACE_4;
    private static final float ROW_HEIGHT = 12.0f;
    private static final float SCORE_GAP = UiTokens.SPACE_8;
    private static final float MIN_WIDTH = 112.0f;

    private static final String[] PREVIEW_ROWS = new String[] {
        "\u00A7711/23/26  \u00A78m104A",
        "",
        "\u00A7fNext Event: \u00A7aDiamond II",
        "",
        "\u00A7cR \u00A7fRed: \u00A7a\u2713",
        "\u00A79B \u00A7fBlue: \u00A7a\u2713",
        "\u00A7eY \u00A7fYellow: \u00A7a\u2713",
        "\u00A7aG \u00A7fGreen: \u00A7c\u2717"
    };

    private static final Comparator<Score> SCORE_ORDER = new Comparator<Score>() {
        @Override
        public int compare(Score a, Score b) {
            int cmp = Integer.compare(b.getScorePoints(), a.getScorePoints());
            if (cmp != 0) {
                return cmp;
            }
            String na = a.getPlayerName();
            String nb = b.getPlayerName();
            if (na == null) {
                return nb == null ? 0 : -1;
            }
            return nb == null ? 1 : na.compareToIgnoreCase(nb);
        }
    };

    private static volatile ScoreboardHud instance;

    private final ModeSetting style;
    private final BooleanSetting hideNumbers;
    private final BooleanSetting textShadow;

    private final List<Score> scoreScratch = new ArrayList<Score>(16);
    private final List<String> rowTextScratch = new ArrayList<String>(16);
    private final List<String> rowScoreScratch = new ArrayList<String>(16);
    private final StringBuilder sanitizeScratch = new StringBuilder(64);

    private float cachedWidth = 142.0f;
    private float cachedHeight = 132.0f;

    /**
     * Creates the Scoreboard HUD module (enabled by default) and registers it with {@link HudOverlayRenderer}.
     */
    public ScoreboardHud() {
        super(ID, Category.UI);
        this.style = addSetting(
            new ModeSetting("style", "setting.hoptimization.scoreboard.style.name", "card", "card", "text")
        );
        this.hideNumbers = addSetting(
            new BooleanSetting("hide_numbers", "setting.hoptimization.scoreboard.hide_numbers.name", true)
        );
        this.textShadow = addSetting(
            new BooleanSetting("text_shadow", "setting.hoptimization.scoreboard.text_shadow.name", false)
        );
        instance = this;
        setEnabled(true);
        HudOverlayRenderer.registerElement(this);
        HudOverlayRenderer.ensureRegistered();
    }

    @Override
    public void resetToDefaults() {
        super.resetToDefaults();
        setEnabled(true);
    }

    /**
     * Returns whether the custom modern Scoreboard HUD is currently enabled (suppressing vanilla's sidebar).
     *
     * @return {@code true} if active
     */
    public static boolean isActive() {
        ScoreboardHud inst = instance;
        return inst != null && inst.isEnabled();
    }

    @Override
    public String getHudId() {
        return getId();
    }

    @Override
    public String getHudDisplayName() {
        return getDisplayName();
    }

    @Override
    public boolean isHudEnabled() {
        return isEnabled();
    }

    @Override
    public float getHudWidth() {
        return this.cachedWidth;
    }

    @Override
    public float getHudHeight() {
        return this.cachedHeight;
    }

    @Override
    public float getDefaultHudX(float screenWidth, float screenHeight) {
        return Math.max(0.0f, screenWidth - this.cachedWidth - UiTokens.HUD_SCREEN_MARGIN);
    }

    @Override
    public float getDefaultHudY(float screenWidth, float screenHeight) {
        return Math.max(UiTokens.HUD_SCREEN_MARGIN, (screenHeight - this.cachedHeight) * 0.5f);
    }

    @Override
    public void renderHud(float x, float y, float partialTicks, ThemeData theme, boolean editMode) {
        String title = collectScoreboardRows(editMode);
        if (title == null) {
            return;
        }

        float uiScale = FontManager.getUiScale();
        boolean showNumbers = !this.hideNumbers.isEnabled();
        boolean cardMode = !this.style.is("text");
        boolean shadow = !cardMode || this.textShadow.isEnabled();

        float maxContentW = VanillaTtfFontBridge.measureFormattedUiWidth(title, TITLE_FONT_SIZE, uiScale);
        int rowCount = this.rowTextScratch.size();
        for (int i = 0; i < rowCount; i++) {
            float lineW = VanillaTtfFontBridge.measureFormattedUiWidth(
                this.rowTextScratch.get(i), ROW_FONT_SIZE, uiScale
            );
            if (showNumbers) {
                float scoreW = VanillaTtfFontBridge.measureFormattedUiWidth(
                    this.rowScoreScratch.get(i), ROW_FONT_SIZE, uiScale
                );
                lineW += SCORE_GAP + scoreW;
            }
            if (lineW > maxContentW) {
                maxContentW = lineW;
            }
        }

        float dividerSpace = rowCount > 0 ? (DIVIDER_GAP * 2.0f + UiTokens.BORDER_WIDTH) : 0.0f;
        float boxW = Math.max(MIN_WIDTH, maxContentW + PAD_X * 2.0f);
        float boxH = PAD_Y * 2.0f + TITLE_HEIGHT + dividerSpace + rowCount * ROW_HEIGHT;
        this.cachedWidth = boxW;
        this.cachedHeight = boxH;

        if (cardMode) {
            HudRenderHelper.drawHudCard(x, y, boxW, boxH, theme, 1.0f);
        }

        float titleW = VanillaTtfFontBridge.measureFormattedUiWidth(title, TITLE_FONT_SIZE, uiScale);
        float titleX = x + (boxW - titleW) * 0.5f;
        float titleY = y + PAD_Y;
        VanillaTtfFontBridge.drawFormattedUiString(
            title, titleX, titleY, theme.textPrimary(), shadow, TITLE_FONT_SIZE, uiScale
        );

        if (rowCount > 0 && cardMode) {
            float dividerY = titleY + TITLE_HEIGHT + DIVIDER_GAP;
            RenderUtil.drawRect(
                x + PAD_X,
                dividerY,
                Math.max(0.0f, boxW - PAD_X * 2.0f),
                UiTokens.BORDER_WIDTH,
                theme.border()
            );
        }

        float curY = titleY + TITLE_HEIGHT + dividerSpace;
        float leftX = x + PAD_X;
        float rightX = x + boxW - PAD_X;
        int defaultRowColor = theme.textPrimary();
        int scoreColor = RenderUtil.withAlpha(theme.danger(), 0.90f);

        for (int i = 0; i < rowCount; i++) {
            String rowText = this.rowTextScratch.get(i);
            if (!rowText.isEmpty()) {
                VanillaTtfFontBridge.drawFormattedUiString(
                    rowText, leftX, curY, defaultRowColor, shadow, ROW_FONT_SIZE, uiScale
                );
            }
            if (showNumbers) {
                String scoreStr = this.rowScoreScratch.get(i);
                float scoreW = VanillaTtfFontBridge.measureFormattedUiWidth(scoreStr, ROW_FONT_SIZE, uiScale);
                VanillaTtfFontBridge.drawFormattedUiString(
                    scoreStr, rightX - scoreW, curY, scoreColor, shadow, ROW_FONT_SIZE, uiScale
                );
            }
            curY += ROW_HEIGHT;
        }
    }

    private String collectScoreboardRows(boolean editMode) {
        this.scoreScratch.clear();
        this.rowTextScratch.clear();
        this.rowScoreScratch.clear();

        ScoreObjective objective = resolveActiveObjective();
        if (objective == null) {
            if (!editMode) {
                return null;
            }
            for (int i = 0; i < PREVIEW_ROWS.length; i++) {
                this.rowTextScratch.add(PREVIEW_ROWS[i]);
                this.rowScoreScratch.add(Integer.toString(PREVIEW_ROWS.length - i));
            }
            return "\u00A7e\u00A7lBED WARS";
        }

        Scoreboard scoreboard = objective.getScoreboard();
        Collection<Score> rawScores = scoreboard.getSortedScores(objective);
        for (Score score : rawScores) {
            if (score != null && score.getPlayerName() != null && !score.getPlayerName().startsWith("#")) {
                this.scoreScratch.add(score);
            }
        }
        Collections.sort(this.scoreScratch, SCORE_ORDER);
        int limit = Math.min(MAX_ROWS, this.scoreScratch.size());
        for (int i = 0; i < limit; i++) {
            Score score = this.scoreScratch.get(i);
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            String formatted = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            this.rowTextScratch.add(sanitizeScoreboardText(formatted));
            this.rowScoreScratch.add(Integer.toString(score.getScorePoints()));
        }
        String displayName = objective.getDisplayName();
        return sanitizeScoreboardText(displayName);
    }

    /**
     * Strips Hypixel-style invisible anti-duplicate placeholder emojis (supplementary Unicode code points
     * {@code >= 0x10000} and non-UI symbol dingbats) that vanilla 1.8.9's bitmap font ignores but TTF fonts render.
     */
    private String sanitizeScoreboardText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        this.sanitizeScratch.setLength(0);
        int len = raw.length();
        boolean modified = false;
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < len && Character.isLowSurrogate(raw.charAt(i + 1))) {
                    i++;
                }
                modified = true;
                continue;
            }
            if (Character.isLowSurrogate(c) || isHiddenPlaceholderSymbol(c)) {
                modified = true;
                continue;
            }
            this.sanitizeScratch.append(c);
        }
        return modified ? this.sanitizeScratch.toString() : raw;
    }

    private static boolean isHiddenPlaceholderSymbol(char c) {
        // Keep common legitimate Minecraft scoreboard symbols (checkmarks, crosses, stars, hearts, bullets, blocks).
        if (c == '\u2713' || c == '\u2714' || c == '\u2717' || c == '\u2718'
            || c == '\u2605' || c == '\u2606' || c == '\u2764' || c == '\u2665'
            || c == '\u25CF' || c == '\u25AA' || c == '\u25AB' || c == '\u25A0'
            || c == '\u2588' || c == '\u258C' || c == '\u2590' || c == '\u25B6'
            || c == '\u25C0' || c == '\u2022' || c == '\u00B7' || c == '\u2192'
            || c == '\u2190' || c == '\u2191' || c == '\u2193') {
            return false;
        }
        // Strip Misc Symbols (U+2600-U+26FF), Dingbats (U+2700-U+27BF), and Variation Selectors (U+FE00-U+FE0F).
        return (c >= '\u2600' && c <= '\u27BF')
            || (c >= '\uFE00' && c <= '\uFE0F')
            || (c >= '\uE000' && c <= '\uF8FF');
    }

    private static ScoreObjective resolveActiveObjective() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return null;
        }
        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return null;
        }
        ScoreObjective objective = null;
        ScorePlayerTeam playerTeam = scoreboard.getPlayersTeam(mc.thePlayer.getName());
        if (playerTeam != null) {
            int colorIndex = playerTeam.getChatFormat().getColorIndex();
            if (colorIndex >= 0) {
                objective = scoreboard.getObjectiveInDisplaySlot(3 + colorIndex);
            }
        }
        if (objective == null) {
            objective = scoreboard.getObjectiveInDisplaySlot(1);
        }
        return objective;
    }
}
