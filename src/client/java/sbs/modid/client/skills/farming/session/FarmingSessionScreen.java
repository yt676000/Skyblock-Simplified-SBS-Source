/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.session;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Farming Session Summary: one session's lines, each with its change against the session before
 * it, then the stored history. Clicking a history row shows that session (against its own previous).
 *
 * <p>Read-only. The panel is sized from the viewport with no fixed minimum, and the list scrolls
 * with the shared {@link SciFiScrollbar} - both per {@code ui/AGENTS.md}.
 */
public final class FarmingSessionScreen extends Screen {

    private static final int ROW_H = 11;
    private static final int MAX_W = 520;
    private static final int MAX_H = 420;
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    /** One drawn row; {@code historyIndex} >= 0 makes it clickable. */
    private record Line(String left, String right, String delta, int leftColor, int deltaColor,
                        int historyIndex) {
    }

    private final Screen parent;
    private final SciFiScrollbar bar = new SciFiScrollbar();
    private int selected;
    private int scroll;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int dividerY;
    private int listTop;
    private int listBottom;

    public FarmingSessionScreen(Screen parent) {
        super(Component.literal("Farming Session"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int margin = Math.min(SBSTheme.SCREEN_MARGIN, Math.max(4, this.width / 20));
        panelW = Math.min(this.width - margin * 2, MAX_W);
        panelH = Math.min(this.height - margin * 2, MAX_H);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = Math.min(SBSTheme.PANEL_PADDING, Math.max(4, panelW / 30));
        int header = Math.min(SBSTheme.HEADER_HEIGHT, font.lineHeight + 10);
        dividerY = panelY + header;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;
        listTop = dividerY + Math.min(SBSTheme.GAP_AFTER_HEADER, 4);
        int buttonH = Math.min(SBSTheme.SEARCH_HEIGHT, font.lineHeight + 8);
        int closeY = panelY + panelH - pad - buttonH;
        listBottom = closeY - 4;

        addRenderableOnly(new PanelRenderable());
        addRenderableWidget(new SciFiButton(innerX, closeY, contentW, buttonH, Component.literal("Close"),
                this::onClose));
    }

    // ------------------------------------------------------------------ content

    private List<Line> lines() {
        List<FarmingSessionRecord> history = FarmingSessionTracker.getInstance().history();
        List<Line> out = new ArrayList<>();
        if (history.isEmpty()) {
            var cfg = ConfigManager.getInstance().get().gardenHelpers;
            out.add(text("No farming session yet.", SBSTheme.TEXT));
            out.add(text("A session starts with the first crop you break with a farming tool", SBSTheme.TEXT_MUTED));
            out.add(text("on a farming island, and ends when you leave, after "
                    + cfg.farmingSessionEndMinutes + " min without a crop,", SBSTheme.TEXT_MUTED));
            out.add(text("or with /sbs farming end. Crops need the tab Collection widget (/widgets).",
                    SBSTheme.TEXT_MUTED));
            return out;
        }
        selected = Math.max(0, Math.min(selected, history.size() - 1));
        FarmingSessionRecord r = history.get(selected);
        FarmingSessionRecord p = FarmingSessionTracker.getInstance().previous(selected);

        out.add(new Line(WHEN.format(Instant.ofEpochMilli(r.startedAt)) + " - " + r.endReason,
                p == null ? "first session" : "vs " + WHEN.format(Instant.ofEpochMilli(p.startedAt)),
                "", SBSTheme.ACCENT_BRIGHT, SBSTheme.TEXT_MUTED, -1));
        out.add(row("Active time", duration(r.activeMs), r.activeMs, p == null ? null : (double) p.activeMs,
                v -> duration((long) v)));
        out.add(row("Crops", NumberDisplay.format(r.totalCrops()), r.totalCrops(),
                p == null ? null : (double) p.totalCrops(), NumberDisplay::format));
        for (Map.Entry<String, Long> crop : r.crops.entrySet()) {
            Double before = p == null ? null : (double) p.crops.getOrDefault(crop.getKey(), 0L);
            out.add(row("  " + crop.getKey(), NumberDisplay.format(crop.getValue()), crop.getValue(), before,
                    NumberDisplay::format));
        }
        out.add(row("Crop coins" + (r.cropUnpriced ? " (some unpriced)" : ""), coins(r.cropCoins),
                r.cropCoins, p == null ? null : (double) p.cropCoins, NumberDisplay::format));
        out.add(row("Pest kills", String.valueOf(r.pestKills), r.pestKills,
                p == null ? null : (double) p.pestKills, v -> String.valueOf(Math.round(v))));
        out.add(row("Pest loot", coins(r.pestCoins), r.pestCoins,
                p == null ? null : (double) p.pestCoins, NumberDisplay::format));
        if (r.drops.isEmpty()) {
            // Never "0 coins" as a fact: no farming drop line has ever been seen in a log.
            out.add(new Line("Rare drops", "none seen (unverified)", "", SBSTheme.TEXT, SBSTheme.TEXT_MUTED, -1));
        } else {
            out.add(row("Rare drops (unverified)" + (r.dropUnpriced ? ", some unpriced" : ""),
                    r.totalDrops() + " - " + coins(r.dropCoins), r.dropCoins,
                    p == null ? null : (double) p.dropCoins, NumberDisplay::format));
            for (Map.Entry<String, Integer> drop : r.drops.entrySet()) {
                out.add(new Line("  " + drop.getKey(), String.valueOf(drop.getValue()), "",
                        SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, -1));
            }
        }
        out.add(row("Total coins", coins(r.totalCoins()), r.totalCoins(),
                p == null ? null : (double) p.totalCoins(), NumberDisplay::format));
        out.add(row("Coins per hour", r.coinsPerHour() > 0 ? coins(Math.round(r.coinsPerHour())) : "-",
                r.coinsPerHour(), p == null || p.coinsPerHour() <= 0 ? null : p.coinsPerHour(),
                NumberDisplay::format));

        out.add(text("", SBSTheme.TEXT));
        out.add(text("History (click to view)", SBSTheme.ACCENT));
        for (int i = 0; i < history.size(); i++) {
            FarmingSessionRecord h = history.get(i);
            String left = (i == selected ? "> " : "  ") + WHEN.format(Instant.ofEpochMilli(h.startedAt))
                    + "  " + duration(h.activeMs);
            String right = NumberDisplay.format(h.totalCrops()) + " crops  " + coins(h.totalCoins());
            out.add(new Line(left, right, "", i == selected ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT,
                    SBSTheme.TEXT_MUTED, i));
        }
        return out;
    }

    private static Line text(String text, int color) {
        return new Line(text, "", "", color, color, -1);
    }

    private static Line row(String label, String value, double now, Double previous,
                            java.util.function.DoubleFunction<String> format) {
        String delta = FarmingSessionRecord.delta(now, previous, format);
        int color = delta.startsWith("+") ? SBSTheme.TOGGLE_ON
                : delta.startsWith("-") ? SBSTheme.WARN : SBSTheme.TEXT_MUTED;
        return new Line(label, value, delta, SBSTheme.TEXT, color, -1);
    }

    private static String coins(long value) {
        return NumberDisplay.format(value);
    }

    /** "1h 12m", "12m 30s", "45s". */
    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        return m < 60 ? m + "m " + (s % 60) + "s" : (m / 60) + "h " + (m % 60) + "m";
    }

    // ------------------------------------------------------------------ input

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_H);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        if (bar.handleClick(event.x(), event.y(), scroll, value -> scroll = value)) {
            return true;
        }
        if (event.x() < innerX || event.x() > innerX + contentW
                || event.y() < listTop || event.y() >= listTop + visibleRows() * ROW_H) {
            return false;
        }
        List<Line> list = lines();
        int index = scroll + (int) ((event.y() - listTop) / ROW_H);
        if (index >= 0 && index < list.size() && list.get(index).historyIndex() >= 0) {
            selected = list.get(index).historyIndex();
            scroll = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), bar.maxScroll()));
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
            Font font = FarmingSessionScreen.this.font;
            g.fill(0, 0, FarmingSessionScreen.this.width, FarmingSessionScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            int titleY = panelY + (dividerY - panelY - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Farming Session"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            g.fill(innerX, dividerY, innerX + contentW, dividerY + 1, SBSTheme.ACCENT);

            List<Line> list = lines();
            int visible = visibleRows();
            bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, visible * ROW_H, list.size(), visible);
            scroll = Math.max(0, Math.min(scroll, bar.maxScroll()));
            // The bar's width is reserved always, so a list that starts scrolling does not reflow.
            int textW = contentW - SciFiScrollbar.WIDTH - 4;
            for (int i = 0; i < visible && scroll + i < list.size(); i++) {
                Line line = list.get(scroll + i);
                int y = listTop + i * ROW_H;
                boolean hover = line.historyIndex() >= 0 && mouseX >= innerX && mouseX < innerX + textW
                        && mouseY >= y && mouseY < y + ROW_H;
                if (hover) {
                    g.fill(innerX, y - 1, innerX + textW, y + ROW_H - 1, SBSTheme.CARD_BG_HOVER);
                }
                int deltaW = line.delta().isEmpty() ? 0 : font.width(line.delta()) + 6;
                int rightW = line.right().isEmpty() ? 0 : font.width(line.right()) + 6;
                int leftW = Math.max(0, textW - deltaW - rightW);
                g.text(font, Component.literal(font.plainSubstrByWidth(line.left(), leftW, false)),
                        innerX, y, line.leftColor());
                if (rightW > 0) {
                    g.text(font, Component.literal(line.right()), innerX + textW - deltaW - rightW + 6, y,
                            SBSTheme.TEXT);
                }
                if (deltaW > 0) {
                    g.text(font, Component.literal(line.delta()), innerX + textW - deltaW + 6, y,
                            line.deltaColor());
                }
            }
            bar.render(g, scroll, mouseX, mouseY);
        }
    }
}
