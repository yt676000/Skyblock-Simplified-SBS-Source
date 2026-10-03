/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.economy.pricehistory.ui.PriceHistoryOverlay;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The in-game "browser" onto skyblocksimplified.info: renders one item's price history (the same
 * data the website shows, fetched live from the price API) or the general item search, inside any
 * host-provided rectangle. Hosts are the movable container overlay ({@link PriceHistoryOverlay})
 * and the fixed full-screen view ({@code PriceHistoryScreen}); both forward input here.
 *
 * <p>Charts show the average line(s), the min–max band and (for AH items) the per-bucket sales
 * volume, so price manipulation – spikes far outside the band, huge volume bursts – is visible at
 * a glance. Hovering the chart shows the exact values of a bucket; the "Web" button opens the same
 * item on the real website ({@code #/item/<ID>}).
 */
public final class PriceHistoryView {

    /** The website the "Web" button opens (per-item deep link: {@code #/item/<ID>}). */
    private static String site() {
        return PriceApi.getInstance().siteBase();
    }

    private static final int HEADER_H = 14;
    private static final int TABS_H = 12;
    private static final int ROW_H = 12;
    private static final int GAP = 3;
    private static final int SEARCH_LIMIT = 200;
    private static final int QUERY_LIMIT = 48;

    // Muted chart tones (band / volume) on top of the SBS palette.
    private static final int BAND_FILL = 0x333FB4FF;
    private static final int VOLUME_FILL = 0x5557D977;
    private static final int GRID = 0x223FB4FF;
    private static final int LINE_A = SBSTheme.ACCENT;      // buy avg / AH avg
    private static final int LINE_B = 0xFF57D977;           // sell avg (bazaar)
    private static final int LINE_MEDIAN = 0xFFE0A14D;      // AH median
    private static final int LBIN_LINE = 0xFF57D977;

    /** The website's chart ranges. */
    private enum Range {
        D1("1D", "1d"), D7("7D", "7d"), D30("30D", "30d"), Y1("1Y", "1y"), ALL("ALL", "all");

        final String label;
        final String param;

        Range(String label, String param) {
            this.label = label;
            this.param = param;
        }
    }

    // Host-assigned bounds (set every frame before render / input).
    private int x;
    private int y;
    private int w;
    private int h;

    // Item mode.
    private boolean searchMode = true;
    private List<String> candidates = List.of();
    private int candidateIndex;
    private String itemId = "";
    private Range range = Range.D7;
    private volatile PriceApi.ItemData data;
    private volatile String error;
    private volatile boolean loading;
    private int requestSeq;

    // Search mode.
    private String query = "";
    private boolean searchFocused;
    /** Ctrl+A state: the whole query is selected – the next edit replaces / clears it. */
    private boolean allSelected;
    /** Swallows the char event of the very hotkey press that opened (and focused) the search. */
    private long searchOpenedAt;
    private int listScroll;
    private volatile String[][] catalogue;
    private volatile String catalogueError;
    private boolean catalogueRequested;
    private List<String[]> filtered = List.of();
    private String filterFor;

    // Hit boxes recomputed every frame (screen coordinates).
    private int backX;
    private int backW;
    private int webX;
    private int webW;
    private int tabY;
    private int[] tabXs = new int[Range.values().length + 1];
    private int chartX;
    private int chartY;
    private int chartW;
    private int chartH;
    private int listY;
    private int listRows;

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    /** Opens the general item search (the website's start page). */
    public void openSearch() {
        searchMode = true;
        searchFocused = true;
        searchOpenedAt = System.currentTimeMillis();
        ensureCatalogue();
    }

    /**
     * Opens an item's chart. {@code candidates} are tried in order (SkyBlock id first, then the
     * normalized-name fallbacks) until the API knows one – mirrors the tooltip price lookups.
     */
    public void openItem(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            openSearch();
            return;
        }
        this.candidates = candidates;
        this.candidateIndex = 0;
        this.itemId = candidates.get(0);
        this.searchMode = false;
        this.searchFocused = false;
        fetch();
    }

    public boolean isSearchFocused() {
        return searchMode && searchFocused;
    }

    public void unfocusSearch() {
        searchFocused = false;
        allSelected = false;
    }

    private void fetch() {
        final int seq = ++requestSeq;
        data = null;
        error = null;
        loading = true;
        final String id = itemId;
        PriceApi.getInstance().fetchItem(id, range.param, (result, err) -> {
            if (seq != requestSeq) {
                return; // stale response (range switched / another item opened)
            }
            if ("unknown_item".equals(err) && candidateIndex + 1 < candidates.size()) {
                // Try the next lookup candidate on the client thread's next interaction-free path.
                candidateIndex++;
                itemId = candidates.get(candidateIndex);
                fetch();
                return;
            }
            data = result;
            error = "unknown_item".equals(err) ? "No price data for this item" : err;
            loading = false;
        });
    }

    private void ensureCatalogue() {
        if (catalogue != null || catalogueRequested) {
            return;
        }
        catalogueRequested = true;
        PriceApi.getInstance().fetchItems((result, err) -> {
            catalogue = result;
            catalogueError = err;
        });
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        if (!PriceApi.hasToken()) {
            g.centeredText(font, Component.literal("No licence token set."),
                    x + w / 2, y + h / 2 - 10, SBSTheme.WARN);
            g.centeredText(font, Component.literal("Enter it in the Licence Token module."),
                    x + w / 2, y + h / 2 + 2, SBSTheme.TEXT_MUTED);
            return;
        }
        if (searchMode) {
            renderSearch(g, font, mouseX, mouseY);
        } else {
            renderItem(g, font, mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------
    // Search mode
    // ------------------------------------------------------------------

    private void renderSearch(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        ensureCatalogue();

        // Search bar (same look as the Recipe Viewer bar).
        int border = searchFocused ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER;
        SciFiRender.roundedRectWithBorder(g, x, y, w, HEADER_H, SBSTheme.CORNER_RADIUS,
                SBSTheme.SEARCH_FILL, border);
        int textY = y + (HEADER_H - font.lineHeight) / 2 + 1;
        if (query.isEmpty() && !searchFocused) {
            g.text(font, Component.literal("Search items..."), x + 5, textY, SBSTheme.TEXT_MUTED);
        } else {
            String shown = font.plainSubstrByWidth(query, w - 12, true);
            if (allSelected) { // Ctrl+A selection backdrop
                g.fill(x + 4, textY - 1, x + 5 + font.width(shown) + 1, textY + font.lineHeight, 0x803FB4FF);
            }
            g.text(font, Component.literal(shown), x + 5, textY, SBSTheme.TEXT);
            if (searchFocused && !allSelected && (System.currentTimeMillis() / 500) % 2 == 0) {
                int caretX = x + 5 + font.width(shown) + 1;
                g.fill(caretX, textY - 1, caretX + 1, textY + font.lineHeight, SBSTheme.ACCENT_BRIGHT);
            }
        }

        listY = y + HEADER_H + GAP;
        listRows = Math.max(0, (y + h - listY) / ROW_H);

        if (catalogue == null) {
            g.centeredText(font, Component.literal(
                            catalogueError != null ? catalogueError : "Loading items..."),
                    x + w / 2, listY + 8, catalogueError != null ? SBSTheme.WARN : SBSTheme.TEXT_MUTED);
            return;
        }

        List<String[]> list = filteredList();
        if (list.isEmpty()) {
            g.centeredText(font, Component.literal("No matching items."),
                    x + w / 2, listY + 8, SBSTheme.TEXT_MUTED);
            return;
        }
        int maxScroll = Math.max(0, list.size() - listRows);
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));

        for (int row = 0; row < listRows; row++) {
            int index = listScroll + row;
            if (index >= list.size()) {
                break;
            }
            String[] entry = list.get(index);
            int rowY = listY + row * ROW_H;
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hovered) {
                SciFiRender.roundedRect(g, x, rowY, w, ROW_H - 1, 2, SBSTheme.CARD_BG_HOVER);
            }
            boolean bazaar = entry.length > 1 && "bz".equals(entry[1]);
            String tag = bazaar ? "BZ" : "AH";
            int tagColor = bazaar ? LINE_B : SBSTheme.ACCENT;
            int tagW = font.width(tag);
            String id = font.plainSubstrByWidth(entry[0], w - tagW - 10, false);
            g.text(font, Component.literal(id), x + 3, rowY + 2, hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
            g.text(font, Component.literal(tag), x + w - tagW - 3, rowY + 2, tagColor);
        }
        if (maxScroll > 0) {
            g.text(font, Component.literal((listScroll + 1) + "-"
                            + Math.min(list.size(), listScroll + listRows) + "/" + list.size()),
                    x + w - 40, y + (HEADER_H - font.lineHeight) / 2 + 1, SBSTheme.TEXT_MUTED);
        }
    }

    private List<String[]> filteredList() {
        String key = query.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (key.equals(filterFor) && !filtered.isEmpty()) {
            return filtered;
        }
        filterFor = key;
        listScroll = 0;
        List<String[]> result = new ArrayList<>();
        String[][] all = catalogue;
        if (all != null) {
            for (String[] entry : all) {
                if (entry.length > 0 && (key.isEmpty() || entry[0].contains(key))) {
                    result.add(entry);
                    if (key.isEmpty() && result.size() >= 5000) {
                        break; // plenty; keeps the row math cheap
                    }
                }
            }
            if (result.size() > SEARCH_LIMIT && !key.isEmpty()) {
                result = result.subList(0, SEARCH_LIMIT);
            }
        }
        filtered = result;
        return filtered;
    }

    // ------------------------------------------------------------------
    // Item mode
    // ------------------------------------------------------------------

    private void renderItem(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        // Header row: [< Search]   ITEM_ID   [Web]
        backW = font.width("< Search") + 8;
        backX = x;
        webW = font.width("Web") + 8;
        webX = x + w - webW;
        drawMiniButton(g, font, backX, y, backW, HEADER_H, "< Search",
                hovered(mouseX, mouseY, backX, y, backW, HEADER_H));
        drawMiniButton(g, font, webX, y, webW, HEADER_H, "Web",
                hovered(mouseX, mouseY, webX, y, webW, HEADER_H));
        int titleSpace = webX - (backX + backW) - 8;
        String title = font.plainSubstrByWidth(itemId, Math.max(10, titleSpace), false);
        g.centeredText(font, Component.literal(title), x + w / 2,
                y + (HEADER_H - font.lineHeight) / 2 + 1, SBSTheme.ACCENT_BRIGHT);

        // Range tabs.
        tabY = y + HEADER_H + GAP;
        Range[] ranges = Range.values();
        int tabW = w / ranges.length;
        for (int i = 0; i < ranges.length; i++) {
            tabXs[i] = x + i * tabW;
        }
        tabXs[ranges.length] = x + w;
        for (int i = 0; i < ranges.length; i++) {
            boolean selected = ranges[i] == range;
            boolean hover = hovered(mouseX, mouseY, tabXs[i], tabY, tabXs[i + 1] - tabXs[i], TABS_H);
            if (selected || hover) {
                SciFiRender.roundedRect(g, tabXs[i] + 1, tabY, tabXs[i + 1] - tabXs[i] - 2, TABS_H, 2,
                        selected ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG);
            }
            if (selected) {
                g.fill(tabXs[i] + 3, tabY + TABS_H - 1, tabXs[i + 1] - 3, tabY + TABS_H, SBSTheme.ACCENT);
            }
            g.centeredText(font, Component.literal(ranges[i].label),
                    (tabXs[i] + tabXs[i + 1]) / 2, tabY + 2,
                    selected ? SBSTheme.ACCENT_BRIGHT : (hover ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED));
        }

        // Chart area + footer stats line.
        int footerH = font.lineHeight + 2;
        chartX = x;
        chartY = tabY + TABS_H + GAP;
        chartW = w;
        chartH = y + h - footerH - GAP - chartY;
        if (chartH < 30) {
            return;
        }

        PriceApi.ItemData current = data;
        if (loading && current == null) {
            g.centeredText(font, Component.literal("Loading " + itemId + "..."),
                    x + w / 2, chartY + chartH / 2 - 4, SBSTheme.TEXT_MUTED);
            return;
        }
        if (current == null) {
            g.centeredText(font, Component.literal(error != null ? error : "No data."),
                    x + w / 2, chartY + chartH / 2 - 4, SBSTheme.WARN);
            return;
        }
        drawChart(g, font, current, mouseX, mouseY);
        drawFooter(g, font, current, y + h - footerH + 2);
    }

    private static boolean hovered(int mouseX, int mouseY, int bx, int by, int bw, int bh) {
        return mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
    }

    private static void drawMiniButton(GuiGraphicsExtractor g, Font font, int bx, int by, int bw, int bh,
                                       String label, boolean hover) {
        SciFiRender.roundedRectWithBorder(g, bx, by, bw, bh, 3,
                hover ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.centeredText(font, Component.literal(label), bx + bw / 2,
                by + (bh - font.lineHeight) / 2 + 1, hover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
    }

    // ------------------------------------------------------------------
    // Chart
    // ------------------------------------------------------------------

    private void drawChart(GuiGraphicsExtractor g, Font font, PriceApi.ItemData item,
                           int mouseX, int mouseY) {
        double[][] rows = item.h;
        boolean bazaar = item.isBazaar();
        if (rows == null || rows.length == 0) {
            g.centeredText(font, Component.literal("No history for this range."),
                    x + w / 2, chartY + chartH / 2 - 4, SBSTheme.TEXT_MUTED);
            return;
        }

        int labelW = 30;
        int plotX = chartX + labelW;
        int plotW = chartW - labelW - 2;
        int plotY = chartY + 2;
        int plotH = chartH - font.lineHeight - 4; // x-axis labels below
        if (plotW < 20 || plotH < 20) {
            return;
        }

        long tMin = Long.MAX_VALUE;
        long tMax = Long.MIN_VALUE;
        for (double[] row : rows) {
            long ts = (long) row[0];
            tMin = Math.min(tMin, ts);
            tMax = Math.max(tMax, ts);
        }
        if (tMax <= tMin) {
            tMax = tMin + 1;
        }

        // Aggregate buckets into pixel columns: line A/B averages, min–max band, sales volume.
        double[] sumA = new double[plotW];
        double[] sumB = new double[plotW];
        int[] count = new int[plotW];
        double[] lows = new double[plotW];
        double[] highs = new double[plotW];
        double[] volume = new double[plotW];
        long[] colTs = new long[plotW];
        java.util.Arrays.fill(lows, Double.NaN);
        for (double[] row : rows) {
            if (row.length < (bazaar ? 8 : 7)) {
                continue;
            }
            int col = (int) ((row[0] - tMin) * (plotW - 1) / (tMax - tMin));
            col = Math.max(0, Math.min(plotW - 1, col));
            double a = bazaar ? row[2] : row[2];               // buyAvg / avg
            double b = bazaar ? row[3] : row[3];               // sellAvg / median
            double low = bazaar ? Math.min(row[4], row[6]) : row[4];
            double high = bazaar ? Math.max(row[5], row[7]) : row[5];
            sumA[col] += a;
            sumB[col] += b;
            count[col]++;
            lows[col] = Double.isNaN(lows[col]) ? low : Math.min(lows[col], low);
            highs[col] = Double.isNaN(highs[col]) || highs[col] < high ? high : highs[col];
            if (!bazaar && row.length > 6) {
                volume[col] += row[6];
            }
            colTs[col] = (long) row[0];
        }

        // Y scale over everything visible (band + lines + lowest BIN).
        double vMin = Double.MAX_VALUE;
        double vMax = -Double.MAX_VALUE;
        double maxVolume = 0;
        for (int c = 0; c < plotW; c++) {
            if (count[c] == 0) {
                continue;
            }
            vMin = Math.min(vMin, Double.isNaN(lows[c]) ? sumA[c] / count[c] : lows[c]);
            vMax = Math.max(vMax, Double.isNaN(highs[c]) ? sumA[c] / count[c] : highs[c]);
            maxVolume = Math.max(maxVolume, volume[c]);
        }
        double lbin = !bazaar && item.lb != null && item.lb.length > 0 ? item.lb[0] : Double.NaN;
        if (!Double.isNaN(lbin)) {
            vMin = Math.min(vMin, lbin);
            vMax = Math.max(vMax, lbin);
        }
        if (vMin > vMax) {
            g.centeredText(font, Component.literal("No history for this range."),
                    x + w / 2, chartY + chartH / 2 - 4, SBSTheme.TEXT_MUTED);
            return;
        }
        double pad = (vMax - vMin) * 0.05;
        if (pad <= 0) {
            pad = Math.max(vMax * 0.05, 0.5);
        }
        vMin -= pad;
        vMax += pad;
        final double yMin = vMin;
        final double yMax = vMax;
        final int py = plotY;
        final int ph = plotH;

        // Grid + y labels.
        for (int line = 0; line <= 2; line++) {
            int gy = plotY + line * (plotH - 1) / 2;
            g.fill(plotX, gy, plotX + plotW, gy + 1, GRID);
            double value = yMax - (yMax - yMin) * line / 2.0;
            g.text(font, Component.literal(fmt(value)), chartX, gy - (line == 0 ? 0 : font.lineHeight / 2),
                    SBSTheme.TEXT_MUTED);
        }

        // Volume bars (AH sales per bucket) along the chart floor.
        if (maxVolume > 0) {
            for (int c = 0; c < plotW; c++) {
                if (volume[c] <= 0) {
                    continue;
                }
                int barH = (int) Math.max(1, Math.round(volume[c] / maxVolume * (plotH * 0.18)));
                g.fill(plotX + c, plotY + plotH - barH, plotX + c + 1, plotY + plotH, VOLUME_FILL);
            }
        }

        // Min–max band.
        for (int c = 0; c < plotW; c++) {
            if (count[c] == 0 || Double.isNaN(lows[c])) {
                continue;
            }
            int yHigh = mapY(highs[c], yMin, yMax, py, ph);
            int yLow = mapY(lows[c], yMin, yMax, py, ph);
            g.fill(plotX + c, yHigh, plotX + c + 1, Math.max(yHigh + 1, yLow + 1), BAND_FILL);
        }

        // Price lines.
        drawSeries(g, plotX, plotW, sumA, count, yMin, yMax, py, ph, LINE_A);
        drawSeries(g, plotX, plotW, sumB, count, yMin, yMax, py, ph, bazaar ? LINE_B : LINE_MEDIAN);

        // Lowest BIN marker (dashed, current value – the number every flip check starts from).
        if (!Double.isNaN(lbin)) {
            int ly = mapY(lbin, yMin, yMax, py, ph);
            for (int c = 0; c < plotW; c += 6) {
                g.fill(plotX + c, ly, plotX + Math.min(plotW, c + 3), ly + 1, LBIN_LINE);
            }
        }

        // Legend (top-left corner of the plot).
        String legendA = bazaar ? "Buy" : "Avg";
        String legendB = bazaar ? "Sell" : "Median";
        int lx = plotX + 3;
        int lyy = plotY + 2;
        g.fill(lx, lyy + 2, lx + 5, lyy + 5, LINE_A);
        g.text(font, Component.literal(legendA), lx + 7, lyy, SBSTheme.TEXT_MUTED);
        int lx2 = lx + 7 + font.width(legendA) + 8;
        g.fill(lx2, lyy + 2, lx2 + 5, lyy + 5, bazaar ? LINE_B : LINE_MEDIAN);
        g.text(font, Component.literal(legendB), lx2 + 7, lyy, SBSTheme.TEXT_MUTED);

        // X-axis labels (start / end of the visible span).
        boolean shortSpan = (tMax - tMin) <= 26 * 3600;
        g.text(font, Component.literal(fmtTime(tMin, shortSpan)), plotX, plotY + plotH + 2, SBSTheme.TEXT_MUTED);
        String endLabel = fmtTime(tMax, shortSpan);
        g.text(font, Component.literal(endLabel), plotX + plotW - font.width(endLabel),
                plotY + plotH + 2, SBSTheme.TEXT_MUTED);

        // Hover readout: nearest column with data.
        if (hovered(mouseX, mouseY, plotX, plotY, plotW, plotH)) {
            int col = -1;
            for (int offset = 0; offset < plotW && col < 0; offset++) {
                int left = mouseX - plotX - offset;
                int right = mouseX - plotX + offset;
                if (left >= 0 && count[left] > 0) {
                    col = left;
                } else if (right < plotW && count[right] > 0) {
                    col = right;
                }
            }
            if (col >= 0) {
                g.fill(plotX + col, plotY, plotX + col + 1, plotY + plotH, 0x66FFFFFF);
                List<String> lines = new ArrayList<>();
                lines.add(fmtTime(colTs[col], true));
                lines.add(legendA + " " + fmt(sumA[col] / count[col]));
                lines.add(legendB + " " + fmt(sumB[col] / count[col]));
                if (!Double.isNaN(lows[col])) {
                    lines.add("Range " + fmt(lows[col]) + " - " + fmt(highs[col]));
                }
                if (!bazaar && volume[col] > 0) {
                    lines.add("Sales " + (long) volume[col]);
                }
                int boxW = 0;
                for (String line : lines) {
                    boxW = Math.max(boxW, font.width(line));
                }
                boxW += 8;
                int boxH = lines.size() * font.lineHeight + 6;
                int boxX = mouseX < plotX + plotW / 2 ? plotX + plotW - boxW - 2 : plotX + 2;
                int boxY = plotY + 2;
                SciFiRender.roundedRectWithBorder(g, boxX, boxY, boxW, boxH, 3,
                        0xF0081827, SBSTheme.CARD_BORDER);
                for (int i = 0; i < lines.size(); i++) {
                    g.text(font, Component.literal(lines.get(i)), boxX + 4,
                            boxY + 3 + i * font.lineHeight, i == 0 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
                }
            }
        }
    }

    /** Connects per-column averages with solid vertical spans (crisp 1px polyline). */
    private static void drawSeries(GuiGraphicsExtractor g, int plotX, int plotW, double[] sums, int[] count,
                                   double yMin, double yMax, int py, int ph, int color) {
        int prevY = Integer.MIN_VALUE;
        for (int c = 0; c < plotW; c++) {
            if (count[c] == 0) {
                continue;
            }
            int cy = mapY(sums[c] / count[c], yMin, yMax, py, ph);
            if (prevY == Integer.MIN_VALUE) {
                g.fill(plotX + c, cy, plotX + c + 1, cy + 1, color);
            } else {
                int top = Math.min(prevY, cy);
                int bottom = Math.max(prevY, cy);
                g.fill(plotX + c, top, plotX + c + 1, bottom + 1, color);
            }
            prevY = cy;
        }
    }

    private static int mapY(double value, double vMin, double vMax, int plotY, int plotH) {
        double t = (value - vMin) / (vMax - vMin);
        t = Math.max(0, Math.min(1, t));
        return plotY + (int) Math.round((1.0 - t) * (plotH - 1));
    }

    private void drawFooter(GuiGraphicsExtractor g, Font font, PriceApi.ItemData item, int footerY) {
        String text;
        if (item.isBazaar()) {
            double[] last = item.s != null && item.s.length > 0 ? item.s[item.s.length - 1] : null;
            text = last != null && last.length >= 5
                    ? "Instabuy " + fmt(last[1]) + "  Instasell " + fmt(last[2])
                    + "  Vol/wk " + fmt(last[3]) + " / " + fmt(last[4])
                    : "Bazaar item";
        } else {
            String lb = item.lb != null && item.lb.length >= 2
                    ? "LBIN " + fmt(item.lb[0]) + " (" + (long) item.lb[1] + " offers)"
                    : "No BIN offers";
            int sales = item.s != null ? item.s.length : 0;
            text = lb + "  •  " + sales + (sales == 200 ? "+" : "") + " sales/24h";
        }
        g.text(font, Component.literal(font.plainSubstrByWidth(text, w, false)), x, footerY, SBSTheme.TEXT_MUTED);
    }

    // ------------------------------------------------------------------
    // Input (forwarded by the hosts; coordinates are screen coordinates)
    // ------------------------------------------------------------------

    /** Handles a left click; returns true when the click hit something of the view. */
    public boolean mouseClicked(double mx, double my) {
        if (!PriceApi.hasToken()) {
            return false;
        }
        int imx = (int) mx;
        int imy = (int) my;
        if (searchMode) {
            if (hovered(imx, imy, x, y, w, HEADER_H)) {
                searchFocused = true;
                return true;
            }
            searchFocused = false;
            if (catalogue != null && imx >= x && imx < x + w && imy >= listY && imy < listY + listRows * ROW_H) {
                int index = listScroll + (imy - listY) / ROW_H;
                List<String[]> list = filteredList();
                if (index >= 0 && index < list.size()) {
                    openItem(List.of(list.get(index)[0]));
                    return true;
                }
            }
            return false;
        }
        if (hovered(imx, imy, backX, y, backW, HEADER_H)) {
            openSearch();
            return true;
        }
        if (hovered(imx, imy, webX, y, webW, HEADER_H)) {
            // Native confirm-link flow (same as the Recipe Viewer's wiki link); the SBS-tracked
            // screen is used because Minecraft's screen field/method shape differs across versions.
            ConfirmLinkScreen.confirmLinkNow(
                    sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen(),
                    site() + "#/item/" + itemId);
            return true;
        }
        Range[] ranges = Range.values();
        if (imy >= tabY && imy < tabY + TABS_H) {
            for (int i = 0; i < ranges.length; i++) {
                if (imx >= tabXs[i] && imx < tabXs[i + 1]) {
                    if (range != ranges[i]) {
                        range = ranges[i];
                        fetch();
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** Mouse-wheel over the search list; returns true when consumed. */
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!searchMode || scrollY == 0 || catalogue == null) {
            return false;
        }
        if (mx < x || mx > x + w || my < listY || my > listY + listRows * ROW_H) {
            return false;
        }
        int maxScroll = Math.max(0, filteredList().size() - listRows);
        listScroll = Math.max(0, Math.min(maxScroll, listScroll - (int) Math.signum(scrollY) * 3));
        return true;
    }

    /** GLFW key codes for the search field. */
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;

    /**
     * Handles a key while the search field is focused (Escape / Enter release focus, Backspace
     * deletes, Ctrl+A selects all); returns true when the key must be consumed by the host.
     */
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (!isSearchFocused()) {
            return false;
        }
        int key = event.key();
        if (key == KEY_ESCAPE || key == KEY_ENTER || key == KEY_NUMPAD_ENTER) {
            searchFocused = false;
            allSelected = false;
        } else if (key == KEY_BACKSPACE) {
            if (allSelected) {
                query = "";
                allSelected = false;
            } else if (!query.isEmpty()) {
                query = query.substring(0, query.length() - 1);
            }
        } else if (event.isSelectAll()) {
            allSelected = !query.isEmpty();
        }
        return true; // consume everything while typing ('E' must not close the container)
    }

    /** Feeds a typed character into the focused search field; returns true when consumed. */
    public boolean charTyped(String characters) {
        if (!isSearchFocused()) {
            return false;
        }
        if (System.currentTimeMillis() - searchOpenedAt < 100) {
            return true; // the GLFW char event of the opening hotkey itself – discard it
        }
        if (allSelected) {
            query = characters;
            allSelected = false;
            return true;
        }
        if (query.length() < QUERY_LIMIT) {
            query = query + characters;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private static String fmtTime(long unixSeconds, boolean withTime) {
        return (withTime ? DATE_TIME : DATE)
                .format(Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()));
    }

    /**
     * Compact coin format: 1.2B / 34.5M / 850K / 123 / 0.4.
     *
     * <p>Always short, whatever "Shorten Numbers" says - these are chart axis labels, and a grouped
     * eight-digit price would run into the plot. The sub-1000 decimals stay too: a Bazaar item can
     * cost 0.4 coins, and rounding that to "0" makes the whole chart meaningless.
     */
    private static String fmt(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000) {
            return sbs.modid.client.core.util.NumberDisplay.shorten(value);
        }
        if (abs >= 100) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String trim(double value) {
        return Math.abs(value) >= 100
                ? String.valueOf(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
