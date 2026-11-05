/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.dungeons.profit.DungeonProfitModule;
import sbs.modid.client.dungeons.profit.logic.DungeonProfitTracker;
import sbs.modid.client.dungeons.profit.logic.ProfitStats;
import sbs.modid.client.dungeons.profit.logic.RunLedger;
import sbs.modid.client.dungeons.profit.model.DungeonProfitData;
import sbs.modid.client.dungeons.profit.model.DungeonRun;
import sbs.modid.client.dungeons.profit.render.DungeonProfitHud;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /sbs dungeonprofit}: session, today and all-time figures with their costs, a summary per
 * floor, then every stored run newest first with its cost lines (chest, Kismet, potions, extra) in
 * columns beside the profit. The floor filter is the config's (Dungeons &gt; Dungeon Profit Tracker).
 *
 * <p>The panel is sized from the viewport with no fixed minimum, the list scrolls with the shared
 * {@link SciFiScrollbar}, and the lines - text already cut to the measured column - are rebuilt
 * only when the ledger or the panel width changes, never per frame (both per {@code ui/AGENTS.md}).
 */
public final class DungeonProfitScreen extends Screen {

    private static final int MAX_W = 520;
    private static final int MAX_H = 420;
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    /** One prepared row: left text already fitted, right text and its x offset from the right. */
    private record Line(Component left, Component right, int rightW, int leftColor, int rightColor, Cells cells) {
        Line(Component left, Component right, int rightW, int leftColor, int rightColor) {
            this(left, right, rightW, leftColor, rightColor, null);
        }
    }

    /**
     * A run-table row's columns, each right-aligned: {@code right[j]} is the gap from the text's right
     * edge to cell {@code j}'s right edge, shared by every row of the table so the columns line up.
     */
    private record Cells(Component[] text, int[] width, int[] right, int lastColor) {
    }

    private static final String[] RUN_COLUMNS = {"chest", "kismet", "potions", "extra", "profit"};
    private static final int CELL_GAP = 8;

    private final Screen parent;
    private final SciFiScrollbar bar = new SciFiScrollbar();
    private int scroll;
    private int rowH;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int dividerY;
    private int listTop;
    private int listBottom;

    private List<Line> lines = List.of();
    private int builtRevision = -1;
    private int builtWidth = -1;
    private String builtFilter = "";

    public DungeonProfitScreen(Screen parent) {
        super(Component.literal("Dungeon Profit"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rowH = font.lineHeight + 2;
        int margin = Math.min(SBSTheme.SCREEN_MARGIN, Math.max(4, this.width / 20));
        panelW = Math.max(1, Math.min(this.width - margin * 2, MAX_W));
        panelH = Math.max(1, Math.min(this.height - margin * 2, MAX_H));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = Math.min(SBSTheme.PANEL_PADDING, Math.max(4, panelW / 30));
        int header = Math.min(SBSTheme.HEADER_HEIGHT, font.lineHeight + 10);
        dividerY = panelY + header;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;
        listTop = dividerY + 4;
        int buttonH = Math.min(SBSTheme.SEARCH_HEIGHT, font.lineHeight + 8);
        int buttonY = panelY + panelH - pad - buttonH;
        listBottom = buttonY - 4;

        addRenderableOnly(new PanelRenderable());
        // Three buttons share the row; their labels are short enough for a third of 1280x720 @ 4.
        int gap = 4;
        int third = Math.max(1, (contentW - gap * 2) / 3);
        addRenderableWidget(new SciFiButton(innerX, buttonY, third, buttonH, Component.literal("Reset Session"),
                () -> DungeonProfitTracker.getInstance().resetSession()));
        addRenderableWidget(new SciFiButton(innerX + third + gap, buttonY, third, buttonH,
                Component.literal("Reset All"), DungeonProfitModule::confirmReset));
        addRenderableWidget(new SciFiButton(innerX + (third + gap) * 2, buttonY, contentW - (third + gap) * 2,
                buttonH, Component.literal("Close"), this::onClose));
        builtWidth = -1;
    }

    // ------------------------------------------------------------------ content

    private void refresh() {
        RunLedger ledger = DungeonProfitTracker.getInstance().ledger();
        String filter = ConfigManager.getInstance().get().dungeonProfit.floorFilter;
        if (ledger.revision() == builtRevision && contentW == builtWidth && filter.equals(builtFilter)) {
            return;
        }
        builtRevision = ledger.revision();
        builtWidth = contentW;
        builtFilter = filter;
        lines = build(ledger, filter, contentW - SciFiScrollbar.WIDTH - 4);
    }

    private List<Line> build(RunLedger ledger, String filter, int textW) {
        List<Line> out = new ArrayList<>();
        DungeonProfitData data = ledger.data();
        long midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String scope = filter.isEmpty() ? "all floors" : filter;
        out.add(line("Totals (" + scope + ")", "", SBSTheme.ACCENT, textW));
        stats(out, "Session", ProfitStats.since(data.runs, ledger.sessionStart(), filter), textW);
        stats(out, "Today", ProfitStats.since(data.runs, midnight, filter), textW);
        stats(out, "All time", ProfitStats.allTime(data, filter), textW);

        out.add(line("", "", SBSTheme.TEXT, textW));
        out.add(line("Per floor", "runs  per run  per hour  best", SBSTheme.ACCENT, textW));
        for (Map.Entry<String, DungeonProfitData.FloorTotals> e : data.floors.entrySet()) {
            if (!filter.isEmpty() && !filter.equals(e.getKey())) {
                continue;
            }
            DungeonProfitData.FloorTotals t = e.getValue();
            long perRun = t.runs == 0 ? 0 : t.profit / t.runs;
            long perHour = t.durationMs < 60_000L ? 0 : Math.round(t.profit / (t.durationMs / 3_600_000.0));
            String right = t.runs + "  " + DungeonProfitHud.coins(perRun, t.incomplete) + "  "
                    + (perHour == 0 ? "-" : DungeonProfitHud.coins(perHour, t.incomplete)) + "  "
                    + (t.best == Long.MIN_VALUE ? "-" : DungeonProfitHud.coins(t.best, false));
            out.add(line(e.getKey() + (t.rareDrops > 0 ? "  " + t.rareDrops + " rare" : ""), right,
                    SBSTheme.TEXT, textW));
            costs(out, ProfitStats.Costs.of(t), textW);
        }

        out.add(line("", "", SBSTheme.TEXT, textW));
        // The run table: left text per run, then one right-aligned column per cost line and profit.
        List<String> lefts = new ArrayList<>();
        List<String[]> cells = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        lefts.add("Runs (newest first)");
        cells.add(RUN_COLUMNS);
        colors.add(SBSTheme.TEXT_MUTED);
        int shown = 0;
        for (int i = data.runs.size() - 1; i >= 0; i--) {
            DungeonRun run = data.runs.get(i);
            if (!filter.isEmpty() && !filter.equals(run.floor)) {
                continue;
            }
            shown++;
            StringBuilder left = new StringBuilder();
            left.append(WHEN.format(Instant.ofEpochMilli(run.endMs))).append("  ").append(run.floor);
            if (run.claimOnly) {
                left.append("  Croesus claim");
            } else {
                if (run.score >= 0) {
                    left.append("  ").append(run.score).append(' ').append(run.grade);
                }
                left.append("  ").append(duration(run.durationMs()));
            }
            if (run.kismets > 0) {
                left.append("  ").append(run.kismets).append(" Kismet");
            }
            if (run.potions > 0) {
                left.append("  ").append(run.potions).append(run.potions == 1 ? " potion" : " potions");
            }
            if (!run.topDrop.isEmpty()) {
                left.append("  ").append(run.topDrop);
            }
            if (run.unpriced > 0) {
                left.append("  ").append(run.unpriced).append(" unpriced");
            }
            if (run.costUnknown) {
                left.append("  chest cost unknown");
            }
            int color = run.profit() > 0 ? SBSTheme.TOGGLE_ON : run.profit() < 0 ? SBSTheme.WARN : SBSTheme.TEXT;
            lefts.add(left.toString());
            cells.add(new String[] {
                    cost(run.chestCost, run.costUnknown), cost(run.kismetCost, run.kismetUnpriced),
                    cost(run.potionCost, run.potionUnpriced), cost(run.userCost, false),
                    DungeonProfitHud.coins(run.profit(), run.incomplete())});
            colors.add(color);
        }
        table(out, lefts, cells, colors, textW);
        if (shown == 0) {
            out.add(line("No run yet. A run starts when you enter the Catacombs.", "", SBSTheme.TEXT_MUTED, textW));
        }
        return List.copyOf(out);
    }

    private void stats(List<Line> out, String label, ProfitStats s, int textW) {
        String right = s.runs() + " runs  " + DungeonProfitHud.coins(s.profit(), s.incomplete())
                + (s.perHour() != 0 ? "  " + DungeonProfitHud.coins(s.perHour(), s.incomplete()) + "/h" : "");
        out.add(line(label, right, SBSTheme.TEXT, textW));
        costs(out, s.costs(), textW);
    }

    /** An indented "costs" line under a total, when anything was paid. */
    private void costs(List<Line> out, ProfitStats.Costs c, int textW) {
        if (c.total() > 0 || c.kismets() > 0 || c.potions() > 0) {
            out.add(line("  costs", DungeonProfitHud.costBreakdown(c), SBSTheme.TEXT_MUTED, textW));
        }
    }

    /** A cost cell: {@code -} for none, a trailing {@code +} when part of it had no price. */
    private static String cost(long coins, boolean unknown) {
        return (coins == 0 && !unknown ? "-" : NumberDisplay.format(coins)) + (unknown ? "+" : "");
    }

    /**
     * Lays the run table out: every column as wide as its widest cell, the left text cut to what the
     * columns leave. Row 0 is the header.
     */
    private void table(List<Line> out, List<String> lefts, List<String[]> rows, List<Integer> colors, int textW) {
        int columns = RUN_COLUMNS.length;
        int[] colW = new int[columns];
        for (String[] row : rows) {
            for (int j = 0; j < columns; j++) {
                colW[j] = Math.max(colW[j], width(row[j]));
            }
        }
        int[] right = new int[columns];
        int edge = 0;
        for (int j = columns - 1; j >= 0; j--) {
            right[j] = edge;
            edge += colW[j] + CELL_GAP;
        }
        int leftW = Math.max(0, textW - edge);
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            Component[] text = new Component[columns];
            int[] w = new int[columns];
            for (int j = 0; j < columns; j++) {
                text[j] = Component.literal(row[j]);
                w[j] = width(row[j]);
            }
            boolean header = i == 0;
            out.add(new Line(fit(lefts.get(i), leftW), Component.empty(), 0,
                    header ? SBSTheme.ACCENT : SBSTheme.TEXT, SBSTheme.TEXT_MUTED,
                    new Cells(text, w, right, colors.get(i))));
        }
    }

    /** The right text is the value and never yields; the label is cut to what is left. */
    private Line line(String left, String right, int color, int textW) {
        int rightW = right.isEmpty() ? 0 : width(right);
        return new Line(fit(left, Math.max(0, textW - rightW - (rightW > 0 ? 6 : 0))), Component.literal(right),
                rightW, color, SBSTheme.TEXT_MUTED);
    }

    private int width(String text) {
        return font.width(text);
    }

    private Component fit(String text, int maxW) {
        return Component.literal(font.width(text) <= maxW ? text : font.plainSubstrByWidth(text, Math.max(0, maxW - 6)) + "..");
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 3600 ? (s / 60) + "m " + (s % 60) + "s" : (s / 3600) + "h " + (s % 3600 / 60) + "m";
    }

    // ------------------------------------------------------------------ input

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / Math.max(1, rowH));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (bar.handleClick(event.x(), event.y(), scroll, value -> scroll = value)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY) * 3, bar.maxScroll()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return bar.handleDrag(event.y(), value -> scroll = value) || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return bar.release() || super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ render

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            refresh();
            Font font = DungeonProfitScreen.this.font;
            g.fill(0, 0, DungeonProfitScreen.this.width, DungeonProfitScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (dividerY - panelY - font.lineHeight) / 2;
            g.centeredText(font, DungeonProfitScreen.this.title, panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            g.fill(innerX, dividerY, innerX + contentW, dividerY + 1, SBSTheme.ACCENT);

            int visible = visibleRows();
            bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, visible * rowH, lines.size(), visible);
            scroll = Math.max(0, Math.min(scroll, bar.maxScroll()));
            int textRight = innerX + contentW - SciFiScrollbar.WIDTH - 4;
            for (int i = 0; i < visible && scroll + i < lines.size(); i++) {
                Line line = lines.get(scroll + i);
                int y = listTop + i * rowH;
                g.text(font, line.left(), innerX, y, line.leftColor());
                if (line.rightW() > 0) {
                    g.text(font, line.right(), textRight - line.rightW(), y, line.rightColor());
                }
                Cells cells = line.cells();
                if (cells != null) {
                    int last = cells.text().length - 1;
                    for (int j = 0; j <= last; j++) {
                        g.text(font, cells.text()[j], textRight - cells.right()[j] - cells.width()[j], y,
                                j == last ? cells.lastColor() : SBSTheme.TEXT_MUTED);
                    }
                }
            }
            bar.render(g, scroll, mouseX, mouseY);
        }
    }
}
