/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.tablist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.PlayerTabOverlayAccessor;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.streamer.logic.StreamerNames;
import sbs.modid.client.helper.timers.ServerWorldTime;
import sbs.modid.client.ui.hud.logic.ServerStatsTracker;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the SBS Tab-List: the player list (held tab key) restyled as an SBS panel.
 *
 * <p>The content is exactly what vanilla would show – the same sorted entries (via the vanilla
 * comparator and 80-entry cap through {@link PlayerTabOverlayAccessor}), the same display names
 * (through {@link PlayerTabOverlay#getNameForDisplay}, so Hypixel's colours and the SBS presence
 * badge both survive), the same header/footer and objective scores. Only the paint changes: one
 * rounded panel drawn through {@link SciFiRender}, which is the choke point the UI style switch
 * re-skins – so the tab list follows Classic/Futuristic/Glass/... automatically, and every colour
 * comes from the mutable {@link SBSTheme} constants rather than literals.
 *
 * <p><b>Which rows are players decides what a row gets.</b> On Hypixel most of the list is not
 * players at all: the side columns are fake entries carrying the Info / Skills / Stats widgets, and
 * the vanilla overlay gives each of them a default skin face and a signal-strength icon anyway. Both
 * are asked per row through {@link TabWidgets#isPlayer}, so "Fairy Souls: 80/80" is text and
 * a player is a player. Both can be put back to the vanilla every-row behaviour.
 *
 * <p>The one thing here that is <b>not</b> the server's: the stats strip along the top (instance,
 * tick rate, frame rate, ping). Those four numbers come from {@link ServerStatsTracker} and
 * {@link ServerWorldTime}, the same sources the Server Stats card reads.
 *
 * <p>Everything is read live from {@link SBSConfig.TabListSettings} each frame; dragging an opacity
 * slider shows on the very next frame. By default the panel is near-invisible (backgrounds ~90%+
 * transparent) while the text stays fully opaque – the list floats over the world instead of
 * blacking out half the screen.
 *
 * <p>Grid metrics stay vanilla (8px rows on a 9px stride, 20 rows per column): Hypixel fills whole
 * 20-row columns with fake entries, and a taller stride would push a 4-column SkyBlock tab plus its
 * footer off small GUI-scale screens.
 */
public final class TabListRenderer {

    /** Inner padding between the panel edge and its content. */
    private static final int PAD = 6;

    /** Vanilla grid metrics: 8px slot on a 9px stride, at most 20 rows per column. */
    private static final int ROW_H = 8;
    private static final int ROW_STRIDE = 9;
    private static final int MAX_ROWS = 20;

    /** Gap between two columns of the grid. */
    private static final int COL_GAP = 5;

    /** Vertical gap between the header / grid / footer sections. */
    private static final int SECTION_GAP = 5;

    /** Horizontal gap either side of a stats-strip rule. */
    private static final int STAT_GAP = 6;

    /** The panel's distance from the top screen edge (vanilla starts its list at 10, too). */
    private static final int TOP_Y = 10;

    /** Vanilla's translucency for spectator names (white at ~56% alpha). */
    private static final int SPECTATOR_ALPHA = 0x90;

    /** Width the skin face plus its gap takes in front of a name. */
    private static final int HEAD_W = 10;

    private static final Identifier PING_UNKNOWN = Identifier.withDefaultNamespace("icon/ping_unknown");
    private static final Identifier PING_1 = Identifier.withDefaultNamespace("icon/ping_1");
    private static final Identifier PING_2 = Identifier.withDefaultNamespace("icon/ping_2");
    private static final Identifier PING_3 = Identifier.withDefaultNamespace("icon/ping_3");
    private static final Identifier PING_4 = Identifier.withDefaultNamespace("icon/ping_4");
    private static final Identifier PING_5 = Identifier.withDefaultNamespace("icon/ping_5");

    private TabListRenderer() {
    }

    private static SBSConfig.TabListSettings cfg() {
        return ConfigManager.getInstance().get().tabList;
    }

    /** Whether the SBS tab list should take over the vanilla player list right now. */
    public static boolean active() {
        return cfg().enabled;
    }

    /**
     * Whether the connection bars are switched off - asked by the vanilla overlay's own ping hook as
     * well, so the setting means the same thing whichever list is drawing. Only the blanket switch
     * is asked there: "players only" needs a row to decide about, and the vanilla hook draws one row
     * at a time without saying which.
     */
    public static boolean pingHidden() {
        return cfg().hidePing;
    }

    /** The ping style actually in force: "Hide Ping" wins over the style cycle. */
    private static int pingMode(SBSConfig.TabListSettings cfg) {
        return cfg.hidePing ? SBSConfig.TabListSettings.PING_HIDDEN : cfg.pingDisplay;
    }

    /**
     * One prepared row: the entry, its resolved display name, the optional objective score, and
     * whether it is a player at all - resolved once here rather than per draw call, because the
     * width pass and the paint pass have to agree about it or the columns misalign.
     */
    private record Row(PlayerInfo info, Component name, Component score, boolean player) {
    }

    /** One cell of the stats strip: its label, its value and the colour the value is drawn in. */
    private record Stat(String label, String value, int rgb) {
    }

    /**
     * Draws the SBS tab list. Assumes {@link #active()}; the caller (the {@code PlayerTabOverlay}
     * mixin) checks that and cancels the vanilla render, so this method owns the whole surface.
     */
    public static void render(PlayerTabOverlay overlay, GuiGraphicsExtractor g, int guiWidth,
                              Scoreboard scoreboard, Objective objective) {
        SBSConfig.TabListSettings cfg = cfg();
        Font font = Minecraft.getInstance().font;
        PlayerTabOverlayAccessor access = (PlayerTabOverlayAccessor) overlay;

        int textAlpha = alpha(cfg.textOpacity);
        int bgAlpha = alpha(cfg.backgroundOpacity);

        // ---- content -------------------------------------------------------
        List<PlayerInfo> entries = access.skyblockSimplified$playerInfos();
        List<Row> rows = new ArrayList<>(entries.size());
        int nameW = 0;
        int scoreW = 0;
        for (PlayerInfo info : entries) {
            Component name = overlay.getNameForDisplay(info);
            boolean player = TabWidgets.isPlayer(info);
            // The face is part of the row's width, and only some rows have one - so it is measured
            // per row rather than added once to the total, or the widget columns sit 10px short.
            nameW = Math.max(nameW, headWidth(cfg, player) + font.width(name));
            Component score = null;
            if (objective != null && info.getGameMode() != GameType.SPECTATOR) {
                // HEARTS objectives are drawn as their number too - SkyBlock never sends them, and
                // a numeric fallback beats re-implementing the blinking heart state machine.
                ReadOnlyScoreInfo scoreInfo = scoreboard.getPlayerScoreInfo(
                        ScoreHolder.fromGameProfile(info.getProfile()), objective);
                if (scoreInfo != null) {
                    score = scoreInfo.formatValue(
                            objective.numberFormatOrDefault(StyledFormat.PLAYER_LIST_DEFAULT));
                    scoreW = Math.max(scoreW, font.width(score));
                }
            }
            rows.add(new Row(info, name, score, player));
        }

        List<Stat> stats = cfg.statsHeader ? stats(cfg) : List.of();
        List<FormattedCharSequence> header = cfg.showHeader
                ? splitOrNull(font, access.skyblockSimplified$header(), guiWidth - 50) : null;
        List<FormattedCharSequence> footer = cfg.showFooter
                ? splitOrNull(font, access.skyblockSimplified$footer(), guiWidth - 50) : null;
        if (rows.isEmpty() && header == null && footer == null && stats.isEmpty()) {
            return;
        }

        // ---- layout --------------------------------------------------------
        // Vanilla's column split: grow the column count until no column exceeds 20 rows.
        int count = rows.size();
        int rowsPerCol = count;
        int cols = 1;
        while (rowsPerCol > MAX_ROWS) {
            cols++;
            rowsPerCol = (count + cols - 1) / cols;
        }

        // Hidden ping reserves no width at all, so the columns close up instead of leaving a gap -
        // and with "players only" on, a list of nothing but widget rows reserves none either.
        int pingW = pingWidth(cfg, font, rows);
        int entryW = 2 + nameW + (scoreW > 0 ? scoreW + 4 : 0) + pingW + 2;
        int gridW = count > 0 ? cols * entryW + (cols - 1) * COL_GAP : 0;
        int statsW = statsWidth(font, stats);

        int panelW = Math.max(Math.max(gridW, statsW),
                Math.max(maxLineWidth(font, header), maxLineWidth(font, footer))) + PAD * 2;
        int panelH = PAD * 2
                + (stats.isEmpty() ? 0 : font.lineHeight + SECTION_GAP)
                + (header != null ? header.size() * font.lineHeight + SECTION_GAP : 0)
                + (rowsPerCol > 0 ? rowsPerCol * ROW_STRIDE - 1 : 0)
                + (footer != null ? SECTION_GAP + footer.size() * font.lineHeight : 0);

        int x = (guiWidth - panelW) / 2;
        int y = TOP_Y;

        // ---- panel ---------------------------------------------------------
        int radius = SBSTheme.HUD_CORNER;
        if (cfg.showBorder) {
            int borderAlpha = alpha(cfg.borderOpacity);
            if (borderAlpha > 0) {
                SciFiRender.roundedRect(g, x - 1, y - 1, panelW + 2, panelH + 2, radius + 1,
                        argb(borderAlpha, SBSTheme.ACCENT));
            }
        }
        if (bgAlpha > 0) {
            SciFiRender.roundedRect(g, x, y, panelW, panelH, radius, argb(bgAlpha, SBSTheme.HUD_CARD_BG));
        }
        // Rules are decoration, so they follow the background slider - a player fading the panel out
        // is not left with a set of floating accent lines over the world.
        int ruleAlpha = Math.min(255, bgAlpha * 2);

        int textY = y + PAD;
        if (!stats.isEmpty()) {
            drawStats(g, font, stats, x, panelW, textY, textAlpha, ruleAlpha, cfg.textShadow);
            textY += font.lineHeight;
            rule(g, x + PAD, textY + 1, x + panelW - PAD, ruleAlpha);
            textY += SECTION_GAP;
        }
        if (header != null) {
            textY = drawCenteredLines(g, font, header, x, panelW, textY, textAlpha, cfg.textShadow);
            rule(g, x + PAD, textY + 1, x + panelW - PAD, ruleAlpha);
            textY += SECTION_GAP;
        }

        // ---- grid ----------------------------------------------------------
        int gridX = x + (panelW - gridW) / 2;
        int gridH = rowsPerCol > 0 ? rowsPerCol * ROW_STRIDE - 1 : 0;
        int cellRadius = Math.min(2, radius);
        for (int slot = 0; slot < cols * rowsPerCol; slot++) {
            int cellX = gridX + (slot / rowsPerCol) * (entryW + COL_GAP);
            int cellY = textY + (slot % rowsPerCol) * ROW_STRIDE;
            if (cfg.rowCells && bgAlpha > 0) {
                // Cells stack on the panel fill, so occupied grid area reads slightly darker -
                // the same layering vanilla's slot boxes have, in the themed palette.
                SciFiRender.roundedRect(g, cellX, cellY, entryW, ROW_H, cellRadius,
                        argb(bgAlpha, SBSTheme.HUD_TRACK));
            }
            if (slot >= rows.size() || textAlpha <= 0) {
                continue;
            }
            drawEntry(g, font, cfg, rows.get(slot), cellX, cellY, entryW, pingW, textAlpha);
        }
        if (cfg.columnDividers && cols > 1 && gridH > 0) {
            int dividerAlpha = alpha(cfg.dividerOpacity);
            for (int col = 1; col < cols; col++) {
                int dividerX = gridX + col * (entryW + COL_GAP) - (COL_GAP + 1) / 2;
                if (dividerAlpha > 0) {
                    g.fill(dividerX, textY, dividerX + 1, textY + gridH,
                            argb(dividerAlpha, SBSTheme.ACCENT));
                }
            }
        }
        textY += gridH;

        if (footer != null) {
            rule(g, x + PAD, textY + 2, x + panelW - PAD, ruleAlpha);
            textY += SECTION_GAP;
            drawCenteredLines(g, font, footer, x, panelW, textY, textAlpha, cfg.textShadow);
        }
    }

    /** One grid entry: head, display name, right-aligned score and ping. */
    private static void drawEntry(GuiGraphicsExtractor g, Font font, SBSConfig.TabListSettings cfg,
                                  Row row, int cellX, int cellY, int entryW, int pingW, int textAlpha) {
        int innerX = cellX + 2;
        int tint = argb(textAlpha, 0xFFFFFF);
        if (headWidth(cfg, row.player()) > 0) {
            PlayerFaceExtractor.extractRenderState(g, row.info().getSkin(), innerX, cellY, ROW_H, tint);
            innerX += HEAD_W;
        }

        boolean spectator = row.info().getGameMode() == GameType.SPECTATOR;
        int nameColor = spectator ? argb(Math.min(textAlpha, SPECTATOR_ALPHA), 0xFFFFFF) : tint;
        g.text(font, row.name(), innerX, cellY, nameColor, cfg.textShadow);

        int rightX = cellX + entryW - 2;
        if (row.score() != null) {
            g.text(font, row.score(), rightX - pingW - font.width(row.score()), cellY, tint,
                    cfg.textShadow);
        }
        if (!showsPing(cfg, row.player())) {
            return;
        }
        int latency = row.info().getLatency();
        switch (pingMode(cfg)) {
            case SBSConfig.TabListSettings.PING_BARS -> g.blitSprite(RenderPipelines.GUI_TEXTURED,
                    pingSprite(latency), rightX - 10, cellY, 10, ROW_H, tint);
            case SBSConfig.TabListSettings.PING_NUMBER -> {
                String text = latency < 0 ? "?" : Integer.toString(latency);
                g.text(font, text, rightX - font.width(text), cellY,
                        argb(textAlpha, pingRgb(latency)), cfg.textShadow);
            }
            default -> {
            }
        }
    }

    /** Width the skin face takes in front of this row's name - 0 when it does not get one. */
    private static int headWidth(SBSConfig.TabListSettings cfg, boolean player) {
        return cfg.showHeads && (player || !cfg.headsPlayersOnly) ? HEAD_W : 0;
    }

    /** Whether this row shows a ping at all. */
    private static boolean showsPing(SBSConfig.TabListSettings cfg, boolean player) {
        return pingMode(cfg) != SBSConfig.TabListSettings.PING_HIDDEN
                && (player || !cfg.pingPlayersOnly);
    }

    /**
     * Width reserved at the right of every cell for the ping. One number for the whole grid, from
     * the rows that actually show one: a column whose widest entry is a widget row still has to line
     * up with the column beside it that holds players.
     */
    private static int pingWidth(SBSConfig.TabListSettings cfg, Font font, List<Row> rows) {
        int mode = pingMode(cfg);
        if (mode == SBSConfig.TabListSettings.PING_HIDDEN) {
            return 0;
        }
        boolean any = false;
        int width = font.width("?");
        for (Row row : rows) {
            if (!showsPing(cfg, row.player())) {
                continue;
            }
            any = true;
            int latency = row.info().getLatency();
            if (latency >= 0) {
                width = Math.max(width, font.width(Integer.toString(latency)));
            }
        }
        if (!any) {
            return 0;
        }
        return mode == SBSConfig.TabListSettings.PING_BARS ? 12 : width + 3;
    }

    // ------------------------------------------------------------------
    // Stats strip
    // ------------------------------------------------------------------

    /**
     * The stats cells to draw, in order. A value that cannot be read right now is left out rather
     * than shown as a zero or a dash: off Hypixel there is no instance name at all, and a strip that
     * permanently says "Server ?" is noise, not information.
     */
    private static List<Stat> stats(SBSConfig.TabListSettings cfg) {
        Minecraft minecraft = Minecraft.getInstance();
        List<Stat> out = new ArrayList<>(4);
        if (cfg.statsServer) {
            String server = serverName();
            if (server != null) {
                // Through Streamer Mode's redaction, so an instance id being hidden everywhere else
                // on screen is not published by the tab list the moment tab is held.
                out.add(new Stat("Server", StreamerNames.getInstance().apply(server),
                        SBSTheme.ACCENT & 0xFFFFFF));
            }
        }
        if (cfg.statsTps) {
            double tps = ServerStatsTracker.getInstance().tps();
            if (tps >= 0) {
                out.add(new Stat("TPS", String.format(java.util.Locale.ROOT, "%.1f", tps),
                        tpsRgb(tps)));
            }
        }
        if (cfg.statsFps) {
            out.add(new Stat("FPS", Integer.toString(Math.max(0, minecraft.getFps())), 0xFFFFFF));
        }
        if (cfg.statsPing) {
            // Asking is also what drives the 2s measurement cadence, exactly as on the scoreboard -
            // so the number is fed by the same call that draws it.
            int ping = ServerStatsTracker.getInstance().ping(minecraft);
            if (ping >= 0) {
                out.add(new Stat("Ping", ping + "ms", pingRgb(ping)));
            }
        }
        return out;
    }

    /**
     * The instance id, re-read at most once a second.
     *
     * <p>{@link ServerWorldTime#serverName()} colour-strips and pattern-matches every one of the up
     * to 80 tab entries to find one line, which is nothing at all where it is normally called from -
     * a tick handler - and a per-frame sweep here, on a path that runs for as long as the key is
     * held. The id changes once per server hop, so a second of staleness costs nothing - and the
     * re-read is unconditional, so a hop cannot leave the old lobby's id up for longer than that
     * (nor a null hanging around after leaving Hypixel).
     */
    private static String serverName() {
        long now = System.currentTimeMillis();
        if (now - serverNameReadAt > SERVER_NAME_MAX_AGE_MS) {
            serverNameReadAt = now;
            serverName = ServerWorldTime.serverName();
        }
        return serverName;
    }

    private static final long SERVER_NAME_MAX_AGE_MS = 1_000;
    private static String serverName;
    private static long serverNameReadAt;

    /** Total width of the strip, rules and gaps included - the panel has to be at least this wide. */
    private static int statsWidth(Font font, List<Stat> stats) {
        if (stats.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (Stat stat : stats) {
            width += statWidth(font, stat);
        }
        return width + (stats.size() - 1) * (STAT_GAP * 2 + 1);
    }

    private static int statWidth(Font font, Stat stat) {
        return font.width(stat.label()) + 4 + font.width(stat.value());
    }

    /** The strip itself: muted label, coloured value, a thin rule between the cells. */
    private static void drawStats(GuiGraphicsExtractor g, Font font, List<Stat> stats,
                                  int x, int panelW, int textY, int textAlpha, int ruleAlpha,
                                  boolean shadow) {
        int cursor = x + (panelW - statsWidth(font, stats)) / 2;
        for (int i = 0; i < stats.size(); i++) {
            Stat stat = stats.get(i);
            if (textAlpha > 0) {
                g.text(font, stat.label(), cursor, textY,
                        argb(textAlpha, SBSTheme.TEXT_MUTED), shadow);
                g.text(font, stat.value(), cursor + font.width(stat.label()) + 4, textY,
                        argb(textAlpha, stat.rgb()), shadow);
            }
            cursor += statWidth(font, stat);
            if (i < stats.size() - 1) {
                if (ruleAlpha > 0) {
                    g.fill(cursor + STAT_GAP, textY - 1, cursor + STAT_GAP + 1,
                            textY + font.lineHeight - 2, argb(ruleAlpha, SBSTheme.ACCENT));
                }
                cursor += STAT_GAP * 2 + 1;
            }
        }
    }

    /** A horizontal section rule, skipped entirely when the background is faded out. */
    private static void rule(GuiGraphicsExtractor g, int x0, int y, int x1, int ruleAlpha) {
        if (ruleAlpha > 0) {
            g.fill(x0, y, x1, y + 1, argb(ruleAlpha, SBSTheme.ACCENT));
        }
    }

    /** Draws each line centred in the panel; returns the y below the last line. */
    private static int drawCenteredLines(GuiGraphicsExtractor g, Font font,
                                         List<FormattedCharSequence> lines, int x, int panelW,
                                         int textY, int textAlpha, boolean shadow) {
        for (FormattedCharSequence line : lines) {
            if (textAlpha > 0) {
                g.text(font, line, x + (panelW - font.width(line)) / 2, textY,
                        argb(textAlpha, 0xFFFFFF), shadow);
            }
            textY += font.lineHeight;
        }
        return textY;
    }

    /** The header/footer wrapped to the screen, or null when absent/blank so it takes no space. */
    private static List<FormattedCharSequence> splitOrNull(Font font, Component text, int maxWidth) {
        if (text == null || text.getString().isBlank()) {
            return null;
        }
        return font.split(text, Math.max(50, maxWidth));
    }

    private static int maxLineWidth(Font font, List<FormattedCharSequence> lines) {
        if (lines == null) {
            return 0;
        }
        int width = 0;
        for (FormattedCharSequence line : lines) {
            width = Math.max(width, font.width(line));
        }
        return width;
    }

    /** Vanilla's latency buckets for the connection-bars sprite. */
    private static Identifier pingSprite(int latency) {
        if (latency < 0) {
            return PING_UNKNOWN;
        }
        if (latency < 150) {
            return PING_5;
        }
        if (latency < 300) {
            return PING_4;
        }
        if (latency < 600) {
            return PING_3;
        }
        if (latency < 1000) {
            return PING_2;
        }
        return PING_1;
    }

    /** Traffic-light colour for the numeric ping readout. */
    private static int pingRgb(int latency) {
        if (latency < 0) {
            return 0x8FA9C8; // unknown - muted
        }
        if (latency < 150) {
            return 0x55FF55;
        }
        if (latency < 300) {
            return 0xFFFF55;
        }
        if (latency < 600) {
            return 0xFFAA00;
        }
        return 0xFF5555;
    }

    /**
     * Traffic-light colour for the tick rate. 20 is the ceiling by definition, so unlike the frame
     * rate this is a number with a known "correct" value and a colour on it says something.
     */
    private static int tpsRgb(double tps) {
        if (tps >= 19.5) {
            return 0x55FF55;
        }
        if (tps >= 17) {
            return 0xFFFF55;
        }
        return tps >= 14 ? 0xFFAA00 : 0xFF5555;
    }

    /**
     * An opacity percentage as an alpha byte. Values 1-3 are bumped to 4: the font renderer treats
     * an (almost) zero alpha as "no alpha given" and draws fully opaque, which would make the slider
     * jump from invisible straight to solid at its bottom end.
     */
    private static int alpha(int opacityPercent) {
        int alpha = Math.max(0, Math.min(100, opacityPercent)) * 255 / 100;
        return alpha > 0 && alpha < 4 ? 4 : alpha;
    }

    /** RGB (any alpha stripped, e.g. a theme constant) under the given alpha byte. */
    private static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
