/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudraprofit.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.kuudraprofit.KuudraProfitModule;
import sbs.modid.client.combat.kuudraprofit.logic.KuudraProfitStats;
import sbs.modid.client.combat.kuudraprofit.logic.KuudraProfitTracker;
import sbs.modid.client.combat.kuudraprofit.logic.KuudraRunLedger;
import sbs.modid.client.combat.kuudraprofit.model.KuudraProfitData;
import sbs.modid.client.combat.kuudraprofit.model.KuudraRun;
import sbs.modid.client.combat.kuudraprofit.render.KuudraProfitHud;
import sbs.modid.client.core.config.ConfigManager;
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
 * {@code /sbs kuudraprofit}: session, today and all-time figures, a summary per tier, then every
 * stored run newest first. The tier filter is the config's (Combat &gt; Kuudra Profit Tracker).
 *
 * <p>Sized from the viewport with no fixed minimum, scrolled with the shared {@link SciFiScrollbar},
 * and the lines - text already cut to the measured column - are rebuilt only when the ledger, the
 * filter or the panel width changes, never per frame (both per {@code ui/AGENTS.md}).
 */
public final class KuudraProfitScreen extends Screen {

    private static final int MAX_W = 520;
    private static final int MAX_H = 420;
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    /** One prepared row: left text already fitted, right text and its width. */
    private record Line(Component left, Component right, int rightW, int leftColor, int rightColor) {
    }

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
    private int builtFilter = -1;

    public KuudraProfitScreen(Screen parent) {
        super(Component.literal("Kuudra Profit"));
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
        int gap = 4;
        int third = Math.max(1, (contentW - gap * 2) / 3);
        addRenderableWidget(new SciFiButton(innerX, buttonY, third, buttonH, Component.literal("Reset Session"),
                () -> KuudraProfitTracker.getInstance().resetSession()));
        addRenderableWidget(new SciFiButton(innerX + third + gap, buttonY, third, buttonH,
                Component.literal("Reset All"), KuudraProfitModule::confirmReset));
        addRenderableWidget(new SciFiButton(innerX + (third + gap) * 2, buttonY, contentW - (third + gap) * 2,
                buttonH, Component.literal("Close"), this::onClose));
        builtWidth = -1;
    }

    // ------------------------------------------------------------------ content

    private void refresh() {
        KuudraRunLedger ledger = KuudraProfitTracker.getInstance().ledger();
        int filter = ConfigManager.getInstance().get().kuudraProfit.tierFilter;
        if (ledger.revision() == builtRevision && contentW == builtWidth && filter == builtFilter) {
            return;
        }
        builtRevision = ledger.revision();
        builtWidth = contentW;
        builtFilter = filter;
        lines = build(ledger, filter, contentW - SciFiScrollbar.WIDTH - 4);
    }

    private List<Line> build(KuudraRunLedger ledger, int filter, int textW) {
        List<Line> out = new ArrayList<>();
        KuudraProfitData data = ledger.data();
        long midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        out.add(line("Totals (" + (filter == 0 ? "all tiers" : "T" + filter) + ")", "", SBSTheme.ACCENT, textW));
        stats(out, "Session", KuudraProfitStats.since(data.runs, ledger.sessionStart(), filter), textW);
        stats(out, "Today", KuudraProfitStats.since(data.runs, midnight, filter), textW);
        stats(out, "All time", KuudraProfitStats.allTime(data, filter), textW);

        out.add(line("", "", SBSTheme.TEXT, textW));
        out.add(line("Per tier", "runs  per run  per hour  best", SBSTheme.ACCENT, textW));
        for (Map.Entry<String, KuudraProfitData.TierTotals> e : data.tiers.entrySet()) {
            if (filter != 0 && !KuudraProfitData.tierKey(filter).equals(e.getKey())) {
                continue;
            }
            KuudraProfitData.TierTotals t = e.getValue();
            long perRun = t.runs == 0 ? 0 : t.profit / t.runs;
            long perHour = t.durationMs < 60_000L ? 0 : Math.round(t.profit / (t.durationMs / 3_600_000.0));
            String right = t.runs + "  " + KuudraProfitHud.coins(perRun, t.incomplete) + "  "
                    + (perHour == 0 ? "-" : KuudraProfitHud.coins(perHour, t.incomplete)) + "  "
                    + (t.best == Long.MIN_VALUE ? "-" : KuudraProfitHud.coins(t.best, false));
            String left = e.getKey() + (t.runs > t.won ? "  " + (t.runs - t.won) + " lost" : "")
                    + keysHeld(ledger, e.getKey());
            out.add(line(left, right, SBSTheme.TEXT, textW));
        }

        out.add(line("", "", SBSTheme.TEXT, textW));
        out.add(line("Runs (newest first)", "profit", SBSTheme.ACCENT, textW));
        int shown = 0;
        for (int i = data.runs.size() - 1; i >= 0; i--) {
            KuudraRun run = data.runs.get(i);
            if (filter != 0 && filter != run.tier) {
                continue;
            }
            shown++;
            StringBuilder left = new StringBuilder();
            left.append(WHEN.format(Instant.ofEpochMilli(run.endMs))).append("  ").append(KuudraProfitHud.tier(run.tier));
            if (!run.won) {
                left.append("  lost");
            }
            if (run.durationMs > 0) {
                left.append("  ").append(duration(run.durationMs));
            }
            if (!run.topDrop.isEmpty()) {
                left.append("  ").append(run.topDrop);
            }
            if (run.unpriced > 0) {
                left.append("  ").append(run.unpriced).append(" unpriced");
            }
            if (run.keyUnpriced) {
                left.append("  key price unknown");
            }
            String profit = KuudraProfitHud.coins(run.profit(), run.incomplete());
            int color = run.profit() > 0 ? SBSTheme.TOGGLE_ON : run.profit() < 0 ? SBSTheme.WARN : SBSTheme.TEXT;
            out.add(new Line(fit(left.toString(), textW - font.width(profit) - 6), Component.literal(profit),
                    font.width(profit), SBSTheme.TEXT, color));
        }
        if (shown == 0) {
            out.add(line("No run yet. A run is booked when Kuudra goes down.", "", SBSTheme.TEXT_MUTED, textW));
        }
        return List.copyOf(out);
    }

    private static String keysHeld(KuudraRunLedger ledger, String tierKey) {
        if (tierKey.length() != 2 || !Character.isDigit(tierKey.charAt(1))) {
            return "";
        }
        int held = ledger.keysHeld(tierKey.charAt(1) - '0');
        return held > 0 ? "  " + held + " key" + (held == 1 ? "" : "s") + " held" : "";
    }

    private void stats(List<Line> out, String label, KuudraProfitStats s, int textW) {
        String right = s.runs() + " runs  " + KuudraProfitHud.coins(s.profit(), s.incomplete())
                + (s.perHour() != 0 ? "  " + KuudraProfitHud.coins(s.perHour(), s.incomplete()) + "/h" : "");
        out.add(line(label, right, SBSTheme.TEXT, textW));
    }

    /** The right text is the value and never yields; the label is cut to what is left. */
    private Line line(String left, String right, int color, int textW) {
        int rightW = right.isEmpty() ? 0 : font.width(right);
        return new Line(fit(left, Math.max(0, textW - rightW - (rightW > 0 ? 6 : 0))), Component.literal(right),
                rightW, color, SBSTheme.TEXT_MUTED);
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
            Font font = KuudraProfitScreen.this.font;
            g.fill(0, 0, KuudraProfitScreen.this.width, KuudraProfitScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (dividerY - panelY - font.lineHeight) / 2;
            g.centeredText(font, KuudraProfitScreen.this.title, panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
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
            }
            bar.render(g, scroll, mouseX, mouseY);
        }
    }
}
