package com.hoptimization.modules.visual.jarvis;

import com.hoptimization.modules.misc.LanguageModule;
import com.hoptimization.modules.visual.jarvis.BaseIntrusionMonitor.IntruderEntry;
import com.hoptimization.modules.visual.jarvis.BedDefenseAnalyzer.DefenseEntry;
import com.hoptimization.modules.visual.jarvis.BedMiningMath.DefenseBlockKind;
import com.hoptimization.modules.visual.jarvis.BedWarsContext.TeamColor;
import com.hoptimization.modules.visual.jarvis.BedWarsContext.TrackedBed;
import com.hoptimization.ui.anim.AnimDuration;
import com.hoptimization.ui.anim.Animation;
import com.hoptimization.ui.anim.Easing;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.HudEditScreen;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.BuiltinThemes;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

/**
 * Theme-aware, zero-allocation HUD renderer for the Jarvis tactical bed defense radar
 * ({@code jarvis_radar}) and base intrusion alert banner ({@code jarvis_alert}).
 */
public final class JarvisHudRenderer {
    public static final float RADAR_WIDTH = 240.0F;
    public static final float ALERT_WIDTH = 256.0F;
    public static final int MAX_VISIBLE_BEDS = 8;

    private static final float TEAM_DOT_SIZE = 6.0F;
    private static final float TEAM_DOT_RADIUS = 3.0F;
    private static final float BLOCK_ICON_SCALE = 0.75F;
    private static final float BLOCK_ICON_SIZE = 12.0F;
    private static final float TEAM_NAME_MAX_W = 44.0F;
    private static final float FOOTER_TOOL_MAX_W = 84.0F;
    private static final float ALERT_BAR_WIDTH = 2.0F;
    private static final float HIGH_BREAK_WARNING_SECS = 8.0F;

    private static final int DIST_CACHE_LIMIT = 256;
    private static final int SEC_TENTHS_CACHE_LIMIT = 600;
    private static final int PLUS_CACHE_LIMIT = 16;
    private static final int HOTBAR_SLOTS = 9;

    private static final String[] DIST_LABELS = new String[DIST_CACHE_LIMIT + 1];
    private static final String[] SEC_LABELS = new String[SEC_TENTHS_CACHE_LIMIT + 1];
    private static final String[] PLUS_LABELS = new String[PLUS_CACHE_LIMIT + 1];
    private static final String[] SLOT_LABELS = new String[HOTBAR_SLOTS];
    private static final String SEP_DOT = " \u00b7 ";
    private static final String INFINITE_BREAK_LABEL = "\u221e";

    static {
        for (int i = 0; i <= DIST_CACHE_LIMIT; i++) {
            DIST_LABELS[i] = Integer.toString(i) + "m";
        }
        for (int i = 0; i <= SEC_TENTHS_CACHE_LIMIT; i++) {
            SEC_LABELS[i] = Integer.toString(i / 10) + "." + Integer.toString(i % 10) + "s";
        }
        for (int i = 0; i <= PLUS_CACHE_LIMIT; i++) {
            PLUS_LABELS[i] = "+" + Integer.toString(i);
        }
        for (int i = 0; i < HOTBAR_SLOTS; i++) {
            SLOT_LABELS[i] = "[" + Integer.toString(i + 1) + "]";
        }
    }

    private final Animation alertFadeAnim = new Animation(0.0F, AnimDuration.NORMAL, Easing.EASE_OUT_CUBIC);

    // Pre-allocated formatting scratch buffers & last-value caches (zero per-frame heap allocations).
    private final StringBuilder numScratch = new StringBuilder(16);
    private final int[] cachedRowDistMeters = new int[MAX_VISIBLE_BEDS + 2];
    private final String[] cachedRowDistStr = new String[MAX_VISIBLE_BEDS + 2];
    private final int[] cachedRowSecTenths = new int[MAX_VISIBLE_BEDS + 2];
    private final String[] cachedRowSecStr = new String[MAX_VISIBLE_BEDS + 2];

    private int cachedAlertDistMeters = -1;
    private String cachedAlertDistStr = "0m";
    private int cachedExtraCount = -1;
    private String cachedExtraStr = "+1";

    private int cachedLanguageRevision = -1;
    private String cachedLanguageCode = null;
    private String labelTitle = "Jarvis";
    private String labelStatusMatch = "BedWars";
    private String labelStatusAlways = "Always";
    private String labelStatusStandby = "Stand-by";
    private String labelStandbyHint = "Waiting for BedWars match";
    private String labelScanning = "Scanning enemy beds...";
    private String labelEasiest = "EASIEST TARGET";
    private String labelNaked = "Naked";
    private String labelCached = "CACHED";
    private String labelDirect = "Direct";
    private String labelSteps = "steps";
    private String labelSlot = "Slot";
    private String labelHand = "Hand";
    private String labelAlertHeader = "BASE INTRUSION";
    private String labelThreatTnt = "TNT";
    private String labelThreatFireball = "Fireball";
    private String labelThreatPickaxe = "Pickaxe";
    private String labelThreatAxe = "Axe";
    private String labelThreatShears = "Shears";
    private String labelThreatInvis = "Invis";
    private String labelThreatHigh = "High";
    private final String[] labelTeams = new String[TeamColor.values().length];

    // Alert fade-out snapshot so the banner fades smoothly after an intruder exits range.
    private boolean hasAlertSnapshot = false;
    private String snapAlertName = "";
    private TeamColor snapAlertTeam = TeamColor.UNKNOWN;
    private double snapAlertDist = 0.0D;
    private boolean snapAlertInvis = false;
    private boolean snapAlertHigh = false;
    private byte snapAlertThreat = IntruderEntry.THREAT_NONE;
    private int snapAlertTotalCount = 0;

    private ItemStack previewToolStack = null;

    /**
     * Constructs a reusable Jarvis HUD renderer with pre-allocated caches.
     */
    public JarvisHudRenderer() {
        for (int i = 0; i < this.cachedRowDistMeters.length; i++) {
            this.cachedRowDistMeters[i] = -1;
            this.cachedRowDistStr[i] = "0m";
            this.cachedRowSecTenths[i] = -1;
            this.cachedRowSecStr[i] = "0.0s";
        }
        TeamColor[] teams = TeamColor.values();
        for (int i = 0; i < teams.length; i++) {
            this.labelTeams[i] = teams[i].getId();
        }
    }

    /**
     * Resets alert fade animation and cached intruder snapshot when Jarvis disables or world unloads.
     */
    public void resetAnimations() {
        this.alertFadeAnim.snapTo(0.0F);
        this.hasAlertSnapshot = false;
        this.snapAlertTotalCount = 0;
    }

    /**
     * Returns the fixed logical width of the tactical radar HUD element.
     *
     * @return radar width in logical UI pixels
     */
    public float getRadarWidth() {
        return RADAR_WIDTH;
    }

    /**
     * Computes the exact logical height of the tactical radar HUD matching the current render layout.
     *
     * @param ctx               BedWars context
     * @param analyzer          bed defense analyzer
     * @param pathfinder        active mining pathfinder
     * @param miningPathEnabled whether mining path calculation/display is enabled
     * @param editMode          true when queried inside {@link HudEditScreen}
     * @return radar height in logical UI pixels
     */
    public float getRadarHeight(
        BedWarsContext ctx,
        BedDefenseAnalyzer analyzer,
        BedMiningPathfinder pathfinder,
        boolean miningPathEnabled,
        boolean editMode
    ) {
        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();
        TtfFontRenderer overlineFont = FontManager.overline();

        float padV = UiTokens.HUD_PADDING_V;
        float headerH = Math.max(bodyFont.getHeight(), overlineFont.getHeight() + UiTokens.SPACE_4);
        float rowH = Math.max(BLOCK_ICON_SIZE + UiTokens.SPACE_4, captionFont.getHeight() + UiTokens.SPACE_4);

        boolean effectiveActive = ctx != null && ctx.isEffectiveActive();
        int rankedCount = analyzer != null ? Math.min(MAX_VISIBLE_BEDS, analyzer.getRankedCount()) : 0;
        boolean showPreview = editMode && (!effectiveActive || rankedCount == 0);

        if (!effectiveActive && !showPreview) {
            return padV * 2.0F + headerH + UiTokens.SPACE_6 + captionFont.getHeight();
        }

        if (showPreview) {
            float easiestH = UiTokens.SPACE_4 * 2.0F + overlineFont.getHeight()
                + UiTokens.SPACE_2 + bodyFont.getHeight();
            int previewRows = 3;
            float rowsH = previewRows * rowH + (previewRows - 1) * UiTokens.SPACE_4;
            float footerH = UiTokens.SPACE_6 + 1.0F + UiTokens.SPACE_4
                + Math.max(BLOCK_ICON_SIZE, captionFont.getHeight());
            return padV * 2.0F + headerH + UiTokens.SPACE_6 + easiestH + UiTokens.SPACE_6 + rowsH + footerH;
        }

        if (rankedCount == 0) {
            return padV * 2.0F + headerH + UiTokens.SPACE_6 + captionFont.getHeight();
        }

        float easiestH = UiTokens.SPACE_4 * 2.0F + overlineFont.getHeight()
            + UiTokens.SPACE_2 + bodyFont.getHeight();
        float rowsH = rankedCount * rowH + Math.max(0, rankedCount - 1) * UiTokens.SPACE_4;
        float totalH = padV * 2.0F + headerH + UiTokens.SPACE_6 + easiestH + UiTokens.SPACE_6 + rowsH;

        boolean hasFooter = miningPathEnabled && pathfinder != null && pathfinder.hasValidPath();
        if (hasFooter) {
            totalH += UiTokens.SPACE_6 + 1.0F + UiTokens.SPACE_4
                + Math.max(BLOCK_ICON_SIZE, captionFont.getHeight());
        }
        return totalH;
    }

    /**
     * Returns the fixed logical width of the base intrusion alert banner.
     *
     * @return alert banner width in logical UI pixels
     */
    public float getAlertWidth() {
        return ALERT_WIDTH;
    }

    /**
     * Computes the exact logical height of the base intrusion alert banner.
     *
     * @return alert banner height in logical UI pixels
     */
    public float getAlertHeight() {
        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();
        TtfFontRenderer overlineFont = FontManager.overline();
        float topRowH = Math.max(overlineFont.getHeight(), captionFont.getHeight());
        return UiTokens.HUD_PADDING_V * 2.0F + topRowH + UiTokens.SPACE_4 + bodyFont.getHeight();
    }

    /**
     * Renders the Tactical Bed Defense Radar HUD element ({@code jarvis_radar}).
     *
     * @param x                 logical left X
     * @param y                 logical top Y
     * @param theme             active theme tokens
     * @param followTheme       true to use active theme tokens, false for default Obsidian Gold tokens
     * @param cardMode          true for card background style, false for shadowed plain text
     * @param alwaysMode        true when {@code work_mode} is {@code always}
     * @param miningPathEnabled true when mining path display is enabled
     * @param editMode          true when rendering inside {@link HudEditScreen}
     * @param ctx               BedWars context
     * @param analyzer          bed defense analyzer
     * @param pathfinder        active mining pathfinder
     */
    public void renderRadar(
        float x,
        float y,
        ThemeData theme,
        boolean followTheme,
        boolean cardMode,
        boolean alwaysMode,
        boolean miningPathEnabled,
        boolean editMode,
        BedWarsContext ctx,
        BedDefenseAnalyzer analyzer,
        BedMiningPathfinder pathfinder
    ) {
        refreshLabelsIfNeeded();
        ThemeData activeTheme = resolveEffectiveTheme(theme, followTheme);

        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();
        TtfFontRenderer overlineFont = FontManager.overline();

        float w = RADAR_WIDTH;
        float h = getRadarHeight(ctx, analyzer, pathfinder, miningPathEnabled, editMode);
        if (cardMode) {
            drawThemedHudCard(x, y, w, h, activeTheme, 1.0F);
        }

        boolean shadow = !cardMode;
        float padH = UiTokens.HUD_PADDING_H;
        float padV = UiTokens.HUD_PADDING_V;
        float innerLeft = x + padH;
        float innerRight = x + w - padH;
        float innerW = w - padH * 2.0F;
        float cursorY = y + padV;

        boolean inMatch = ctx != null && ctx.isInBedWarsMatch();
        boolean effectiveActive = ctx != null && ctx.isEffectiveActive();
        int rankedCount = analyzer != null ? Math.min(MAX_VISIBLE_BEDS, analyzer.getRankedCount()) : 0;
        boolean showPreview = editMode && (!effectiveActive || rankedCount == 0);

        // Header row: "Jarvis" + Status badge
        float headerH = Math.max(bodyFont.getHeight(), overlineFont.getHeight() + UiTokens.SPACE_4);
        String statusText;
        int statusColor;
        if (showPreview || inMatch) {
            statusText = this.labelStatusMatch;
            statusColor = activeTheme.success();
        } else if (alwaysMode) {
            statusText = this.labelStatusAlways;
            statusColor = activeTheme.warning();
        } else {
            statusText = this.labelStatusStandby;
            statusColor = activeTheme.textTertiary();
        }

        float titleY = cursorY + (headerH - bodyFont.getHeight()) * 0.5F;
        HudRenderHelper.drawHudText(bodyFont, this.labelTitle, innerLeft, titleY, activeTheme.textPrimary(), shadow);

        float badgeTextW = overlineFont.getWidth(statusText);
        float badgeH = overlineFont.getHeight() + UiTokens.SPACE_4;
        float badgeW = badgeTextW + UiTokens.SPACE_8;
        float badgeX = innerRight - badgeW;
        float badgeY = cursorY + (headerH - badgeH) * 0.5F;
        if (cardMode) {
            RenderUtil.drawRoundedRect(
                badgeX,
                badgeY,
                badgeW,
                badgeH,
                activeTheme.radiusPill(badgeH),
                activeTheme.surface2()
            );
        }
        float badgeTextY = badgeY + (badgeH - overlineFont.getHeight()) * 0.5F;
        HudRenderHelper.drawHudTextCentered(
            overlineFont,
            statusText,
            badgeX + badgeW * 0.5F,
            badgeTextY,
            statusColor,
            shadow
        );

        cursorY += headerH + UiTokens.SPACE_6;

        if (!effectiveActive && !showPreview) {
            HudRenderHelper.drawHudText(
                captionFont,
                this.labelStandbyHint,
                innerLeft,
                cursorY,
                activeTheme.textSecondary(),
                shadow
            );
            return;
        }

        if (showPreview) {
            renderPreviewRadarContent(
                innerLeft,
                innerRight,
                innerW,
                cursorY,
                activeTheme,
                cardMode,
                shadow,
                bodyFont,
                captionFont,
                overlineFont
            );
            return;
        }

        if (rankedCount == 0) {
            HudRenderHelper.drawHudText(
                captionFont,
                this.labelScanning,
                innerLeft,
                cursorY,
                activeTheme.textTertiary(),
                shadow
            );
            return;
        }

        // Highlighted "Easiest Target" row at top of radar.
        int bestSlot = analyzer.getRankedBedSlot(0);
        TrackedBed bestBed = bestSlot >= 0 ? ctx.getBed(bestSlot) : null;
        DefenseEntry bestEntry = bestSlot >= 0 ? analyzer.getEntry(bestSlot) : null;
        if (bestBed != null && bestEntry != null) {
            float easiestH = UiTokens.SPACE_4 * 2.0F + overlineFont.getHeight()
                + UiTokens.SPACE_2 + bodyFont.getHeight();
            renderEasiestRow(
                innerLeft,
                innerRight,
                innerW,
                cursorY,
                easiestH,
                bestBed.getTeamColor(),
                bestEntry.isNakedBed(),
                bestEntry.getMinBreakSeconds(),
                bestEntry.getDistance(),
                activeTheme,
                cardMode,
                shadow,
                bodyFont,
                captionFont,
                overlineFont,
                0
            );
            cursorY += easiestH + UiTokens.SPACE_6;
        }

        // Sorted active enemy bed rows (ascending by easeScore).
        float rowH = Math.max(BLOCK_ICON_SIZE + UiTokens.SPACE_4, captionFont.getHeight() + UiTokens.SPACE_4);
        for (int rank = 0; rank < rankedCount; rank++) {
            int slot = analyzer.getRankedBedSlot(rank);
            TrackedBed bed = ctx.getBed(slot);
            DefenseEntry entry = analyzer.getEntry(slot);
            if (bed == null || entry == null) {
                continue;
            }
            boolean cached = bed.getStatus() == TrackedBed.STATUS_CACHED;
            renderBedRow(
                innerLeft,
                innerRight,
                cursorY,
                rowH,
                bed.getTeamColor(),
                entry,
                cached,
                activeTheme,
                shadow,
                captionFont,
                overlineFont,
                rank + 1
            );
            cursorY += rowH + (rank < rankedCount - 1 ? UiTokens.SPACE_4 : 0.0F);
        }

        // Bottom compact mining path footer when BedMiningPathfinder has an active path.
        if (miningPathEnabled && pathfinder != null && pathfinder.hasValidPath()) {
            cursorY += UiTokens.SPACE_6;
            RenderUtil.drawRect(innerLeft, cursorY, innerW, 1.0F, activeTheme.border());
            cursorY += 1.0F + UiTokens.SPACE_4;
            int stepCount = pathfinder.getStepCount();
            boolean naked = pathfinder.isNakedBed();
            float totalSec = pathfinder.getTotalSeconds();
            int bestToolSlot = (!naked && stepCount > 0) ? pathfinder.getStepToolSlot(0) : -1;
            ItemStack toolStack = resolvePlayerHotbarStack(bestToolSlot);
            renderPathFooter(
                innerLeft,
                innerRight,
                cursorY,
                naked,
                stepCount,
                totalSec,
                bestToolSlot,
                toolStack,
                activeTheme,
                shadow,
                captionFont
            );
        }
    }

    /**
     * Renders the Base Intrusion Alert Banner HUD element ({@code jarvis_alert}).
     *
     * @param x           logical left X
     * @param y           logical top Y
     * @param theme       active theme tokens
     * @param followTheme true to use active theme tokens, false for default Obsidian Gold tokens
     * @param cardMode    true for card background style, false for shadowed plain text
     * @param enabled     true when base intrusion alerts are enabled
     * @param editMode    true when rendering inside {@link HudEditScreen}
     * @param ctx         BedWars context
     * @param monitor     base intrusion monitor
     */
    public void renderAlertBanner(
        float x,
        float y,
        ThemeData theme,
        boolean followTheme,
        boolean cardMode,
        boolean enabled,
        boolean editMode,
        BedWarsContext ctx,
        BaseIntrusionMonitor monitor
    ) {
        refreshLabelsIfNeeded();
        ThemeData activeTheme = resolveEffectiveTheme(theme, followTheme);

        long now = System.nanoTime();
        boolean live = enabled
            && ctx != null
            && ctx.isEffectiveActive()
            && monitor != null
            && monitor.hasActiveAlert();

        if (live) {
            IntruderEntry primary = monitor.getIntruder(0);
            if (primary != null) {
                this.snapAlertName = primary.getPlayerName();
                this.snapAlertTeam = primary.getTeamColor();
                this.snapAlertDist = primary.getDistXZ();
                this.snapAlertInvis = primary.isInvisible();
                this.snapAlertHigh = primary.isHighGround();
                this.snapAlertThreat = primary.getThreatKind();
                this.snapAlertTotalCount = monitor.getActiveCount();
                this.hasAlertSnapshot = true;
            } else {
                live = false;
            }
        }

        boolean previewSample = editMode && !live;
        float alpha;
        String playerName;
        TeamColor teamColor;
        double distXZ;
        boolean invisible;
        boolean highGround;
        byte threatKind;
        int totalCount;

        if (previewSample) {
            alpha = 1.0F;
            playerName = "EnemyRusher";
            teamColor = TeamColor.RED;
            distXZ = 14.0D;
            invisible = false;
            highGround = true;
            threatKind = IntruderEntry.THREAT_TNT;
            totalCount = 2;
        } else {
            if (!this.hasAlertSnapshot) {
                return;
            }
            if (live) {
                this.alertFadeAnim.setTarget(1.0F, AnimDuration.NORMAL.getMillis());
            } else {
                this.alertFadeAnim.setTarget(0.0F, AnimDuration.NORMAL.getCloseMillis());
            }
            alpha = this.alertFadeAnim.get(now);
            if (alpha <= 0.01F) {
                if (!live) {
                    this.hasAlertSnapshot = false;
                }
                return;
            }
            playerName = this.snapAlertName;
            teamColor = this.snapAlertTeam;
            distXZ = this.snapAlertDist;
            invisible = this.snapAlertInvis;
            highGround = this.snapAlertHigh;
            threatKind = this.snapAlertThreat;
            totalCount = this.snapAlertTotalCount;
        }

        float w = ALERT_WIDTH;
        float h = getAlertHeight();
        if (cardMode) {
            drawThemedHudCard(x, y, w, h, activeTheme, alpha);
        }

        // 2px semantic danger left bar inset cleanly inside the card bounds.
        float barX = x + UiTokens.SPACE_4;
        float barY = y + UiTokens.HUD_PADDING_V;
        float barH = h - UiTokens.HUD_PADDING_V * 2.0F;
        RenderUtil.drawRoundedRect(
            barX,
            barY,
            ALERT_BAR_WIDTH,
            barH,
            1.0F,
            RenderUtil.withAlpha(activeTheme.danger(), alpha)
        );

        boolean shadow = !cardMode;
        TtfFontRenderer bodyFont = FontManager.body();
        TtfFontRenderer captionFont = FontManager.caption();
        TtfFontRenderer overlineFont = FontManager.overline();

        float contentLeft = x + UiTokens.SPACE_12;
        float contentRight = x + w - UiTokens.HUD_PADDING_H;
        float topY = y + UiTokens.HUD_PADDING_V;
        float topRowH = Math.max(overlineFont.getHeight(), captionFont.getHeight());

        int dangerColor = RenderUtil.withAlpha(activeTheme.danger(), alpha);
        int warningColor = RenderUtil.withAlpha(activeTheme.warning(), alpha);
        int primaryColor = RenderUtil.withAlpha(activeTheme.textPrimary(), alpha);
        int secondaryColor = RenderUtil.withAlpha(activeTheme.textSecondary(), alpha);

        // Top row: alert header + optional +N count + distance
        float headerY = topY + (topRowH - overlineFont.getHeight()) * 0.5F;
        HudRenderHelper.drawHudText(overlineFont, this.labelAlertHeader, contentLeft, headerY, dangerColor, shadow);

        String distStr = formatAlertDistance((int) Math.round(distXZ));
        float distY = topY + (topRowH - captionFont.getHeight()) * 0.5F;
        float rightCursor = contentRight;
        HudRenderHelper.drawHudTextRight(captionFont, distStr, rightCursor, distY, secondaryColor, shadow);
        rightCursor -= captionFont.getWidth(distStr);

        if (totalCount > 1) {
            String extraStr = formatExtraIntruders(totalCount - 1);
            rightCursor -= UiTokens.SPACE_6;
            HudRenderHelper.drawHudTextRight(captionFont, extraStr, rightCursor, distY, dangerColor, shadow);
        }

        // Bottom row: team color dot + intruder name + right-aligned threat tags
        float bottomY = topY + topRowH + UiTokens.SPACE_4;
        float dotY = bottomY + (bodyFont.getHeight() - TEAM_DOT_SIZE) * 0.5F;
        int dotColor = RenderUtil.withAlpha(
            (teamColor != null ? teamColor : TeamColor.UNKNOWN).getArgb(),
            alpha
        );
        RenderUtil.drawRoundedRect(contentLeft, dotY, TEAM_DOT_SIZE, TEAM_DOT_SIZE, TEAM_DOT_RADIUS, dotColor);

        float nameX = contentLeft + TEAM_DOT_SIZE + UiTokens.SPACE_6;
        float tagRight = contentRight;
        float tagY = bottomY + (bodyFont.getHeight() - captionFont.getHeight()) * 0.5F;

        if (highGround) {
            tagRight = drawThreatTagRight(
                captionFont,
                this.labelThreatHigh,
                tagRight,
                tagY,
                warningColor,
                shadow
            );
        }
        String heldTag = resolveThreatTag(threatKind);
        if (heldTag != null) {
            int heldColor = (threatKind == IntruderEntry.THREAT_TNT || threatKind == IntruderEntry.THREAT_FIREBALL)
                ? dangerColor
                : warningColor;
            tagRight = drawThreatTagRight(captionFont, heldTag, tagRight, tagY, heldColor, shadow);
        }
        if (invisible) {
            tagRight = drawThreatTagRight(captionFont, this.labelThreatInvis, tagRight, tagY, dangerColor, shadow);
        }

        float nameMaxW = Math.max(32.0F, tagRight - nameX - UiTokens.SPACE_4);
        String safeName = (playerName != null && !playerName.isEmpty()) ? playerName : "Enemy";
        bodyFont.drawStringWithEllipsis(safeName, nameX, bottomY, nameMaxW, primaryColor, shadow);
    }

    private float drawThreatTagRight(
        TtfFontRenderer font,
        String label,
        float rightX,
        float y,
        int color,
        boolean shadow
    ) {
        HudRenderHelper.drawHudTextRight(font, label, rightX, y, color, shadow);
        return rightX - font.getWidth(label) - UiTokens.SPACE_6;
    }

    private void renderEasiestRow(
        float left,
        float right,
        float width,
        float top,
        float height,
        TeamColor teamColor,
        boolean nakedBed,
        float breakSeconds,
        double distance,
        ThemeData theme,
        boolean cardMode,
        boolean shadow,
        TtfFontRenderer bodyFont,
        TtfFontRenderer captionFont,
        TtfFontRenderer overlineFont,
        int cacheSlot
    ) {
        if (cardMode) {
            RenderUtil.drawRoundedRect(
                left,
                top,
                width,
                height,
                theme.nestedRadius(theme.radiusCard(), UiTokens.SPACE_4),
                theme.surface2()
            );
        }

        // Accent thin vertical indicator marking the recommended rush target.
        RenderUtil.drawRoundedRect(
            left + UiTokens.SPACE_2,
            top + UiTokens.SPACE_4,
            2.0F,
            height - UiTokens.SPACE_8,
            1.0F,
            theme.accent()
        );

        float textX = left + UiTokens.SPACE_8;
        float textRight = right - UiTokens.SPACE_6;
        float line1Y = top + UiTokens.SPACE_4;
        HudRenderHelper.drawHudText(overlineFont, this.labelEasiest, textX, line1Y, theme.accent(), shadow);

        float line2Y = line1Y + overlineFont.getHeight() + UiTokens.SPACE_2;
        float dotY = line2Y + (bodyFont.getHeight() - TEAM_DOT_SIZE) * 0.5F;
        TeamColor safeTeam = teamColor != null ? teamColor : TeamColor.UNKNOWN;
        RenderUtil.drawRoundedRect(textX, dotY, TEAM_DOT_SIZE, TEAM_DOT_SIZE, TEAM_DOT_RADIUS, safeTeam.getArgb());

        float teamX = textX + TEAM_DOT_SIZE + UiTokens.SPACE_6;
        String teamName = getTeamLabel(safeTeam);

        String distStr = formatRowDistance(cacheSlot, (int) Math.round(distance));
        float distY = line2Y + (bodyFont.getHeight() - captionFont.getHeight()) * 0.5F;
        HudRenderHelper.drawHudTextRight(captionFont, distStr, textRight, distY, theme.textSecondary(), shadow);

        float breakRight = textRight - captionFont.getWidth(distStr) - UiTokens.SPACE_8;
        String breakLabel = nakedBed ? this.labelNaked : formatRowSeconds(cacheSlot, breakSeconds);
        int breakColor = nakedBed ? theme.success() : resolveBreakTimeColor(breakSeconds, theme);
        HudRenderHelper.drawHudTextRight(captionFont, breakLabel, breakRight, distY, breakColor, shadow);

        float maxTeamW = Math.max(24.0F, breakRight - captionFont.getWidth(breakLabel) - UiTokens.SPACE_6 - teamX);
        bodyFont.drawStringWithEllipsis(teamName, teamX, line2Y, maxTeamW, theme.textPrimary(), shadow);
    }

    private void renderBedRow(
        float left,
        float right,
        float top,
        float height,
        TeamColor teamColor,
        DefenseEntry entry,
        boolean cached,
        ThemeData theme,
        boolean shadow,
        TtfFontRenderer captionFont,
        TtfFontRenderer overlineFont,
        int cacheSlot
    ) {
        TeamColor safeTeam = teamColor != null ? teamColor : TeamColor.UNKNOWN;
        float dotY = top + (height - TEAM_DOT_SIZE) * 0.5F;
        RenderUtil.drawRoundedRect(left, dotY, TEAM_DOT_SIZE, TEAM_DOT_SIZE, TEAM_DOT_RADIUS, safeTeam.getArgb());

        float textY = top + (height - captionFont.getHeight()) * 0.5F;
        float nameX = left + TEAM_DOT_SIZE + UiTokens.SPACE_6;
        captionFont.drawStringWithEllipsis(
            getTeamLabel(safeTeam),
            nameX,
            textY,
            TEAM_NAME_MAX_W,
            theme.textPrimary(),
            shadow
        );

        float iconStartX = nameX + TEAM_NAME_MAX_W + UiTokens.SPACE_6;
        float iconY = top + (height - BLOCK_ICON_SIZE) * 0.5F;
        if (entry.isNakedBed() || entry.getLayerCount() == 0) {
            HudRenderHelper.drawHudText(captionFont, this.labelNaked, iconStartX, textY, theme.success(), shadow);
        } else {
            int layers = Math.min(BedDefenseAnalyzer.MAX_LAYER_ICONS, entry.getLayerCount());
            float iconX = iconStartX;
            for (int i = 0; i < layers; i++) {
                DefenseBlockKind kind = entry.getLayerKind(i);
                ItemStack stack = kind != null ? kind.getIconStack() : null;
                if (stack != null && stack.getItem() != null) {
                    HudRenderHelper.drawItemStack(stack, iconX, iconY, BLOCK_ICON_SCALE);
                    iconX += BLOCK_ICON_SIZE + UiTokens.SPACE_2;
                }
            }
        }

        // Right-aligned columns: [cached tag] [break seconds] [distance]
        float rightCursor = right;
        String distStr = formatRowDistance(cacheSlot, (int) Math.round(entry.getDistance()));
        HudRenderHelper.drawHudTextRight(captionFont, distStr, rightCursor, textY, theme.textSecondary(), shadow);
        rightCursor -= captionFont.getWidth(distStr) + UiTokens.SPACE_6;

        float breakSecs = entry.isNakedBed() ? 0.0F : entry.getMinBreakSeconds();
        String secStr = formatRowSeconds(cacheSlot, breakSecs);
        int secColor = entry.isNakedBed() ? theme.success() : resolveBreakTimeColor(breakSecs, theme);
        HudRenderHelper.drawHudTextRight(captionFont, secStr, rightCursor, textY, secColor, shadow);
        rightCursor -= captionFont.getWidth(secStr);

        if (cached) {
            rightCursor -= UiTokens.SPACE_6;
            float cachedY = top + (height - overlineFont.getHeight()) * 0.5F;
            HudRenderHelper.drawHudTextRight(
                overlineFont,
                this.labelCached,
                rightCursor,
                cachedY,
                theme.warning(),
                shadow
            );
        }
    }

    private void renderPathFooter(
        float left,
        float right,
        float top,
        boolean nakedBed,
        int stepCount,
        float totalSeconds,
        int bestToolSlot,
        ItemStack toolStack,
        ThemeData theme,
        boolean shadow,
        TtfFontRenderer captionFont
    ) {
        float rowH = Math.max(BLOCK_ICON_SIZE, captionFont.getHeight());
        float textY = top + (rowH - captionFont.getHeight()) * 0.5F;

        if (nakedBed) {
            float x = HudRenderHelper.drawHudText(
                captionFont,
                this.labelDirect,
                left,
                textY,
                theme.success(),
                shadow
            );
            x = HudRenderHelper.drawHudText(captionFont, SEP_DOT, x, textY, theme.textTertiary(), shadow);
            HudRenderHelper.drawHudText(
                captionFont,
                SEC_LABELS[0],
                x,
                textY,
                theme.textSecondary(),
                shadow
            );
            return;
        }

        String stepNumStr = HudRenderHelper.formatInt(Math.max(0, stepCount), this.numScratch);
        float x = HudRenderHelper.drawHudText(captionFont, stepNumStr, left, textY, theme.textPrimary(), shadow);
        x = HudRenderHelper.drawHudText(captionFont, " ", x, textY, theme.textSecondary(), shadow);
        x = HudRenderHelper.drawHudText(captionFont, this.labelSteps, x, textY, theme.textSecondary(), shadow);
        x = HudRenderHelper.drawHudText(captionFont, SEP_DOT, x, textY, theme.textTertiary(), shadow);
        String secStr = formatRowSeconds(MAX_VISIBLE_BEDS + 1, totalSeconds);
        HudRenderHelper.drawHudText(captionFont, secStr, x, textY, theme.textPrimary(), shadow);

        // Right side: Step 0 recommended tool/slot
        float rightCursor = right;
        if (bestToolSlot >= 0 && bestToolSlot < HOTBAR_SLOTS) {
            if (toolStack != null && toolStack.getItem() != null) {
                float iconX = rightCursor - BLOCK_ICON_SIZE;
                float iconY = top + (rowH - BLOCK_ICON_SIZE) * 0.5F;
                HudRenderHelper.drawItemStack(toolStack, iconX, iconY, BLOCK_ICON_SCALE);
                rightCursor = iconX - UiTokens.SPACE_4;
            }
            String slotBadge = SLOT_LABELS[bestToolSlot];
            HudRenderHelper.drawHudTextRight(captionFont, slotBadge, rightCursor, textY, theme.textPrimary(), shadow);
            rightCursor -= captionFont.getWidth(slotBadge) + UiTokens.SPACE_4;
            float avail = rightCursor - left;
            if (avail > 24.0F) {
                float labelW = Math.min(FOOTER_TOOL_MAX_W, captionFont.getWidth(this.labelSlot));
                if (avail >= labelW) {
                    HudRenderHelper.drawHudTextRight(
                        captionFont,
                        this.labelSlot,
                        rightCursor,
                        textY,
                        theme.textSecondary(),
                        shadow
                    );
                }
            }
        } else {
            HudRenderHelper.drawHudTextRight(
                captionFont,
                this.labelHand,
                rightCursor,
                textY,
                theme.textSecondary(),
                shadow
            );
        }
    }

    private void renderPreviewRadarContent(
        float innerLeft,
        float innerRight,
        float innerW,
        float cursorY,
        ThemeData theme,
        boolean cardMode,
        boolean shadow,
        TtfFontRenderer bodyFont,
        TtfFontRenderer captionFont,
        TtfFontRenderer overlineFont
    ) {
        float easiestH = UiTokens.SPACE_4 * 2.0F + overlineFont.getHeight()
            + UiTokens.SPACE_2 + bodyFont.getHeight();
        renderEasiestRow(
            innerLeft,
            innerRight,
            innerW,
            cursorY,
            easiestH,
            TeamColor.RED,
            true,
            0.0F,
            38.0D,
            theme,
            cardMode,
            shadow,
            bodyFont,
            captionFont,
            overlineFont,
            0
        );
        cursorY += easiestH + UiTokens.SPACE_6;

        float rowH = Math.max(BLOCK_ICON_SIZE + UiTokens.SPACE_4, captionFont.getHeight() + UiTokens.SPACE_4);
        renderPreviewBedRow(
            innerLeft, innerRight, cursorY, rowH,
            TeamColor.RED, true, null, null, 0.0F, 38, false,
            theme, shadow, captionFont, overlineFont, 1
        );
        cursorY += rowH + UiTokens.SPACE_4;

        renderPreviewBedRow(
            innerLeft, innerRight, cursorY, rowH,
            TeamColor.YELLOW, false, DefenseBlockKind.WOOL, DefenseBlockKind.WOOD, 1.2F, 64, false,
            theme, shadow, captionFont, overlineFont, 2
        );
        cursorY += rowH + UiTokens.SPACE_4;

        renderPreviewBedRow(
            innerLeft, innerRight, cursorY, rowH,
            TeamColor.AQUA, false, DefenseBlockKind.END_STONE, DefenseBlockKind.GLASS, 3.4F, 96, true,
            theme, shadow, captionFont, overlineFont, 3
        );
        cursorY += rowH + UiTokens.SPACE_6;

        RenderUtil.drawRect(innerLeft, cursorY, innerW, 1.0F, theme.border());
        cursorY += 1.0F + UiTokens.SPACE_4;
        renderPathFooter(
            innerLeft,
            innerRight,
            cursorY,
            false,
            2,
            1.2F,
            0,
            getPreviewToolStack(),
            theme,
            shadow,
            captionFont
        );
    }

    private void renderPreviewBedRow(
        float left,
        float right,
        float top,
        float height,
        TeamColor teamColor,
        boolean naked,
        DefenseBlockKind kind0,
        DefenseBlockKind kind1,
        float breakSecs,
        int distMeters,
        boolean cached,
        ThemeData theme,
        boolean shadow,
        TtfFontRenderer captionFont,
        TtfFontRenderer overlineFont,
        int cacheSlot
    ) {
        float dotY = top + (height - TEAM_DOT_SIZE) * 0.5F;
        RenderUtil.drawRoundedRect(left, dotY, TEAM_DOT_SIZE, TEAM_DOT_SIZE, TEAM_DOT_RADIUS, teamColor.getArgb());

        float textY = top + (height - captionFont.getHeight()) * 0.5F;
        float nameX = left + TEAM_DOT_SIZE + UiTokens.SPACE_6;
        captionFont.drawStringWithEllipsis(
            getTeamLabel(teamColor),
            nameX,
            textY,
            TEAM_NAME_MAX_W,
            theme.textPrimary(),
            shadow
        );

        float iconStartX = nameX + TEAM_NAME_MAX_W + UiTokens.SPACE_6;
        float iconY = top + (height - BLOCK_ICON_SIZE) * 0.5F;
        if (naked) {
            HudRenderHelper.drawHudText(captionFont, this.labelNaked, iconStartX, textY, theme.success(), shadow);
        } else {
            float iconX = iconStartX;
            ItemStack s0 = kind0 != null ? kind0.getIconStack() : null;
            if (s0 != null && s0.getItem() != null) {
                HudRenderHelper.drawItemStack(s0, iconX, iconY, BLOCK_ICON_SCALE);
                iconX += BLOCK_ICON_SIZE + UiTokens.SPACE_2;
            }
            ItemStack s1 = kind1 != null ? kind1.getIconStack() : null;
            if (s1 != null && s1.getItem() != null) {
                HudRenderHelper.drawItemStack(s1, iconX, iconY, BLOCK_ICON_SCALE);
            }
        }

        float rightCursor = right;
        String distStr = formatRowDistance(cacheSlot, distMeters);
        HudRenderHelper.drawHudTextRight(captionFont, distStr, rightCursor, textY, theme.textSecondary(), shadow);
        rightCursor -= captionFont.getWidth(distStr) + UiTokens.SPACE_6;

        String secStr = formatRowSeconds(cacheSlot, breakSecs);
        int secColor = naked ? theme.success() : resolveBreakTimeColor(breakSecs, theme);
        HudRenderHelper.drawHudTextRight(captionFont, secStr, rightCursor, textY, secColor, shadow);
        rightCursor -= captionFont.getWidth(secStr);

        if (cached) {
            rightCursor -= UiTokens.SPACE_6;
            float cachedY = top + (height - overlineFont.getHeight()) * 0.5F;
            HudRenderHelper.drawHudTextRight(
                overlineFont,
                this.labelCached,
                rightCursor,
                cachedY,
                theme.warning(),
                shadow
            );
        }
    }

    private ItemStack getPreviewToolStack() {
        if (this.previewToolStack == null && Items.iron_axe != null) {
            this.previewToolStack = new ItemStack(Items.iron_axe);
        }
        return this.previewToolStack;
    }

    private static ItemStack resolvePlayerHotbarStack(int slot) {
        if (slot < 0 || slot >= HOTBAR_SLOTS) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return null;
        }
        return mc.thePlayer.inventory.getStackInSlot(slot);
    }

    private static void drawThemedHudCard(
        float x,
        float y,
        float width,
        float height,
        ThemeData theme,
        float alpha
    ) {
        HudRenderHelper.drawHudCard(x, y, width, height, theme, alpha);
    }

    private static ThemeData resolveEffectiveTheme(ThemeData theme, boolean followTheme) {
        if (followTheme) {
            return theme != null ? theme : ThemeManager.current();
        }
        return BuiltinThemes.OBSIDIAN_GOLD;
    }

    private static int resolveBreakTimeColor(float seconds, ThemeData theme) {
        if (Float.isInfinite(seconds) || Float.isNaN(seconds) || seconds >= HIGH_BREAK_WARNING_SECS) {
            return theme.warning();
        }
        return theme.textPrimary();
    }

    private String resolveThreatTag(byte threatKind) {
        switch (threatKind) {
            case IntruderEntry.THREAT_TNT:
                return this.labelThreatTnt;
            case IntruderEntry.THREAT_FIREBALL:
                return this.labelThreatFireball;
            case IntruderEntry.THREAT_PICKAXE:
                return this.labelThreatPickaxe;
            case IntruderEntry.THREAT_AXE:
                return this.labelThreatAxe;
            case IntruderEntry.THREAT_SHEARS:
                return this.labelThreatShears;
            default:
                return null;
        }
    }

    private String getTeamLabel(TeamColor teamColor) {
        TeamColor safe = teamColor != null ? teamColor : TeamColor.UNKNOWN;
        return this.labelTeams[safe.ordinal()];
    }

    private String formatRowDistance(int slot, int meters) {
        int clamped = Math.max(0, meters);
        if (clamped <= DIST_CACHE_LIMIT) {
            return DIST_LABELS[clamped];
        }
        if (clamped != this.cachedRowDistMeters[slot]) {
            this.cachedRowDistMeters[slot] = clamped;
            this.numScratch.setLength(0);
            this.numScratch.append(clamped).append('m');
            this.cachedRowDistStr[slot] = this.numScratch.toString();
        }
        return this.cachedRowDistStr[slot];
    }

    private String formatRowSeconds(int slot, float seconds) {
        if (Float.isInfinite(seconds) || Float.isNaN(seconds)) {
            return INFINITE_BREAK_LABEL;
        }
        int tenths = Math.max(0, Math.round(seconds * 10.0F));
        if (tenths <= SEC_TENTHS_CACHE_LIMIT) {
            return SEC_LABELS[tenths];
        }
        if (tenths != this.cachedRowSecTenths[slot]) {
            this.cachedRowSecTenths[slot] = tenths;
            this.numScratch.setLength(0);
            this.numScratch.append(tenths / 10).append('.').append(tenths % 10).append('s');
            this.cachedRowSecStr[slot] = this.numScratch.toString();
        }
        return this.cachedRowSecStr[slot];
    }

    private String formatAlertDistance(int meters) {
        int clamped = Math.max(0, meters);
        if (clamped <= DIST_CACHE_LIMIT) {
            return DIST_LABELS[clamped];
        }
        if (clamped != this.cachedAlertDistMeters) {
            this.cachedAlertDistMeters = clamped;
            this.numScratch.setLength(0);
            this.numScratch.append(clamped).append('m');
            this.cachedAlertDistStr = this.numScratch.toString();
        }
        return this.cachedAlertDistStr;
    }

    private String formatExtraIntruders(int extraCount) {
        int clamped = Math.max(1, extraCount);
        if (clamped <= PLUS_CACHE_LIMIT) {
            return PLUS_LABELS[clamped];
        }
        if (clamped != this.cachedExtraCount) {
            this.cachedExtraCount = clamped;
            this.numScratch.setLength(0);
            this.numScratch.append('+').append(clamped);
            this.cachedExtraStr = this.numScratch.toString();
        }
        return this.cachedExtraStr;
    }

    private void refreshLabelsIfNeeded() {
        int rev = LanguageModule.getLanguageRevision();
        String code = LanguageModule.getEffectiveLanguageCode();
        if (rev == this.cachedLanguageRevision && code.equals(this.cachedLanguageCode)) {
            return;
        }
        this.cachedLanguageRevision = rev;
        this.cachedLanguageCode = code;

        this.labelTitle = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.radar.title", "Jarvis");
        this.labelStatusMatch = HudRenderHelper.resolveLang(
            "ui.hoptimization.jarvis.radar.status.match",
            "BedWars"
        );
        this.labelStatusAlways = HudRenderHelper.resolveLang(
            "ui.hoptimization.jarvis.radar.status.always",
            "Always"
        );
        this.labelStatusStandby = HudRenderHelper.resolveLang(
            "ui.hoptimization.jarvis.radar.status.standby",
            "Stand-by"
        );
        this.labelStandbyHint = HudRenderHelper.resolveLang(
            "ui.hoptimization.jarvis.radar.standby_hint",
            "Waiting for BedWars match"
        );
        this.labelScanning = HudRenderHelper.resolveLang(
            "ui.hoptimization.jarvis.radar.scanning",
            "Scanning enemy beds..."
        );
        this.labelEasiest = HudRenderHelper.resolveLang(
            "ui.hoptimization.jarvis.radar.easiest",
            "EASIEST TARGET"
        );
        this.labelNaked = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.radar.naked", "Naked");
        this.labelCached = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.radar.cached", "CACHED");
        this.labelDirect = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.radar.path.direct", "Direct");
        this.labelSteps = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.radar.path.steps", "steps");
        this.labelSlot = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.radar.path.slot", "Slot");
        this.labelHand = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.radar.path.hand", "Hand");
        this.labelAlertHeader = HudRenderHelper.resolveLang(
            "ui.hoptimization.jarvis.alert.header",
            "BASE INTRUSION"
        );
        this.labelThreatTnt = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.threat.tnt", "TNT");
        this.labelThreatFireball = HudRenderHelper.resolveLang(
            "ui.hoptimization.jarvis.threat.fireball",
            "Fireball"
        );
        this.labelThreatPickaxe = HudRenderHelper.resolveLang(
            "ui.hoptimization.jarvis.threat.pickaxe",
            "Pickaxe"
        );
        this.labelThreatAxe = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.threat.axe", "Axe");
        this.labelThreatShears = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.threat.shears", "Shears");
        this.labelThreatInvis = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.threat.invisible", "Invis");
        this.labelThreatHigh = HudRenderHelper.resolveLang("ui.hoptimization.jarvis.threat.high_ground", "High");

        TeamColor[] teams = TeamColor.values();
        for (int i = 0; i < teams.length; i++) {
            this.labelTeams[i] = HudRenderHelper.resolveLang(teams[i].getLangKey(), teams[i].getId());
        }
    }
}
