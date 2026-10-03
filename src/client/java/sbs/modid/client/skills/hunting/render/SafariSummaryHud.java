/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.skills.hunting.logic.SafariTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Safari card: the shard list of the trip you just finished, drawn for as long as the module's
 * display time says, and - when the live counter is switched on - the same list while you are still
 * in there.
 *
 * <p>Everything it draws comes from {@link SafariTracker}; the card itself keeps no state, so it can
 * never disagree with the chat summary printed from the same trip. It is self-hiding: outside the
 * display window with no live trip on, it draws nothing at all.
 *
 * <p>Shard names are painted in the colour chat wrote them in, which is their rarity. That is the
 * one thing a list of names does not tell you at a glance, and it costs nothing - the colour came in
 * with the line.
 */
public final class SafariSummaryHud {

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;
    /** Extra air above the footer rows, so the total does not read as another shard. */
    private static final int SECTION_GAP = 3;
    /** The list is capped so a long trip cannot grow the card past the screen. */
    private static final int MAX_ROWS = 8;
    private static final int MIN_WIDTH = 128;

    private static final int VALUE_COLOR = 0xFFFFD64D;   // coin yellow, as on the fishing card

    private SafariSummaryHud() {
    }

    private static SBSConfig.SafariSettings cfg() {
        return ConfigManager.getInstance().get().safari;
    }

    /** One drawn line: a label, an optional right-aligned value, and their colours. */
    private record Row(String left, String right, int leftColor, int rightColor, boolean spaced) {
    }

    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.SafariSettings cfg = cfg();
        if (!cfg.enabled || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.SAFARI_SUMMARY)) {
            return;
        }
        SafariTracker tracker = SafariTracker.getInstance();
        boolean summary = tracker.summaryVisible();
        boolean live = !summary && cfg.liveCounter && tracker.inside();
        if (!summary && !live) {
            return;
        }
        List<Row> rows = summary ? summaryRows(cfg, tracker) : liveRows(cfg, tracker);
        if (rows.isEmpty()) {
            return;
        }
        draw(g, summary ? "Safari Summary" : "Safari Trip", rows);
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    /** The finished trip: its shards, the total, the value and how long it took. */
    private static List<Row> summaryRows(SBSConfig.SafariSettings cfg, SafariTracker tracker) {
        SafariTracker.Summary summary = tracker.lastSummary();
        if (summary == null || summary.isEmpty()) {
            return List.of();
        }
        List<Row> rows = shardRows(cfg, tracker, summary.entries());
        rows.add(new Row("Total", countAndValue(cfg, summary.totalShards(), summary.value()),
                SBSTheme.ACCENT, cfg.showValues && summary.value() > 0 ? VALUE_COLOR : SBSTheme.TEXT,
                true));
        rows.add(new Row("Time", SafariTracker.duration(summary.durationMs()),
                SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, false));
        return rows;
    }

    /** The running trip - the same list, with a clock that is still moving. */
    private static List<Row> liveRows(SBSConfig.SafariSettings cfg, SafariTracker tracker) {
        List<SafariTracker.Entry> entries = tracker.liveEntries();
        List<Row> rows = shardRows(cfg, tracker, entries);
        if (entries.isEmpty()) {
            rows.add(new Row("no shards yet", "", SBSTheme.TEXT_MUTED, 0, false));
        }
        double value = 0;
        for (SafariTracker.Entry entry : entries) {
            value += tracker.price(entry.id()) * entry.count();
        }
        rows.add(new Row("Total", countAndValue(cfg, tracker.liveTotal(), value),
                SBSTheme.ACCENT, cfg.showValues && value > 0 ? VALUE_COLOR : SBSTheme.TEXT, true));
        rows.add(new Row("Time", SafariTracker.duration(tracker.tripMillis()),
                SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED, false));
        return rows;
    }

    /** One row per shard kind, capped, with the overflow collapsed into a muted line. */
    private static List<Row> shardRows(SBSConfig.SafariSettings cfg, SafariTracker tracker,
                                       List<SafariTracker.Entry> entries) {
        List<Row> rows = new ArrayList<>(entries.size() + 3);
        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            SafariTracker.Entry entry = entries.get(i);
            double worth = cfg.showValues ? tracker.price(entry.id()) * entry.count() : 0;
            String right = "x" + entry.count();
            if (worth > 0) {
                right += " · " + NumberDisplay.format(worth);
            }
            rows.add(new Row(entry.name(), right,
                    entry.color() == 0 ? SBSTheme.TEXT : entry.color(),
                    worth > 0 ? VALUE_COLOR : SBSTheme.TEXT, false));
        }
        if (entries.size() > shown) {
            rows.add(new Row("+" + (entries.size() - shown) + " more kinds", "",
                    SBSTheme.TEXT_MUTED, 0, false));
        }
        return rows;
    }

    /** "14" or "14 · 4.1M", depending on whether values are shown and known. */
    private static String countAndValue(SBSConfig.SafariSettings cfg, int count, double value) {
        if (!cfg.showValues || value <= 0) {
            return String.valueOf(count);
        }
        return count + " · " + NumberDisplay.format(value);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private static void draw(GuiGraphicsExtractor g, String header, List<Row> rows) {
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;

        // Self-sizing: the card fits its widest row, so a long shard name never overflows it.
        int contentW = font.width(header);
        for (Row row : rows) {
            contentW = Math.max(contentW,
                    font.width(row.left()) + (row.right().isEmpty() ? 0 : 10 + font.width(row.right())));
        }
        int width = Math.max(MIN_WIDTH, contentW + PAD * 2);
        int height = PAD * 2 + font.lineHeight + 2 - LINE_GAP;
        for (Row row : rows) {
            height += lineH + (row.spaced() ? SECTION_GAP : 0);
        }

        HudElement.Bounds b = HudElement.SAFARI_SUMMARY.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.SAFARI_SUMMARY, x, y, width, height);

        HudLayout.begin(g, HudElement.SAFARI_SUMMARY);
        HudCard.draw(g, x, y, width, height);

        int left = x + PAD;
        int right = x + width - PAD;
        int ty = y + PAD;
        g.text(font, Component.literal(header), left, ty, SBSTheme.ACCENT_BRIGHT);
        ty += font.lineHeight + 2;
        for (Row row : rows) {
            if (row.spaced()) {
                ty += SECTION_GAP;
            }
            g.text(font, Component.literal(row.left()), left, ty, row.leftColor());
            if (!row.right().isEmpty()) {
                g.text(font, Component.literal(row.right()),
                        right - font.width(row.right()), ty, row.rightColor());
            }
            ty += lineH;
        }
        HudLayout.end(g);
    }
}
