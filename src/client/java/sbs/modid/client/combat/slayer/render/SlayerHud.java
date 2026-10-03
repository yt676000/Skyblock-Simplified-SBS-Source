/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.slayer.logic.SlayerTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;

import sbs.modid.client.combat.carry.model.SlayerBoss;
import sbs.modid.client.combat.slayer.logic.RngMeterTracker;
import sbs.modid.client.combat.slayer.model.RngMeterState;
import sbs.modid.client.core.util.NumberDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Slayer session tracker card: boss counter (+fails), average kill time, estimated bosses/h,
 * session profit (+/h) and the drop list with per-item values. Self-hiding: draws only while the
 * session has recent slayer activity. Movable / scalable via the GUI editor.
 */
public final class SlayerHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MAX_DROPS = 8;
    private static final int GAIN_COLOR = 0xFF57D977;

    private SlayerHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().slayer;
        SlayerTracker tracker = SlayerTracker.getInstance();
        if (!cfg.enabled || !cfg.showOverlay || Minecraft.getInstance().player == null
                || !tracker.sessionVisible() || HudLayout.isHidden(HudElement.SLAYER_TRACKER)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.SLAYER_TRACKER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.SLAYER_TRACKER);
        draw(g, tracker, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, SlayerTracker tracker, int x, int y) {
        Font font = Minecraft.getInstance().font;

        String title = tracker.questBoss() != null
                ? tracker.questBoss().shortLabel() + " Slayer" : "Slayer";
        List<String[]> rows = new ArrayList<>();  // [label, value]
        rows.add(new String[] {"Bosses", tracker.bossesSlain()
                + (tracker.questsFailed() > 0 ? "  (" + tracker.questsFailed() + " failed)" : "")});
        if (tracker.avgKillSeconds() > 0) {
            rows.add(new String[] {"Avg kill", String.format(Locale.US, "%.1fs", tracker.avgKillSeconds())});
        }
        if (tracker.bossesPerHour() > 0) {
            rows.add(new String[] {"Bosses/h", String.format(Locale.US, "%.1f", tracker.bossesPerHour())});
        }
        double profit = tracker.profit();
        if (profit > 0) {
            String perHour = tracker.profitPerHour() > 0
                    ? "  (" + coins(tracker.profitPerHour()) + "/h)" : "";
            rows.add(new String[] {"Profit", coins(profit) + perHour});
        }

        // Drop list, most valuable first.
        List<String[]> dropRows = new ArrayList<>();
        tracker.drops().entrySet().stream()
                .sorted((a, b) -> Double.compare(
                        tracker.price(b.getKey()) * b.getValue(),
                        tracker.price(a.getKey()) * a.getValue()))
                .limit(MAX_DROPS)
                .forEach(entry -> dropRows.add(new String[] {
                        entry.getValue() + "x " + displayName(entry.getKey()),
                        coins(tracker.price(entry.getKey()) * entry.getValue())}));

        List<String[]> rngRows = rngRows(tracker);

        int lineH = font.lineHeight + LINE_GAP;
        int contentW = font.width(title) + 10;
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        for (String[] row : dropRows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
        }
        for (String[] row : rngRows) {
            if (row != BAR_ROW) {
                contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(row[1]));
            }
        }
        int width = Math.max(150, PAD * 2 + contentW);
        int height = PAD * 2 + lineH * (1 + rows.size() + dropRows.size() + rngRows.size()) - LINE_GAP;
        HudLayout.measure(HudElement.SLAYER_TRACKER, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;

        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, SBSTheme.TEXT);
            iy += lineH;
        }
        for (String[] row : dropRows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT);
            g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, GAIN_COLOR);
            iy += lineH;
        }
        for (String[] row : rngRows) {
            if (row == BAR_ROW) {
                drawBar(g, ix, iy + 1, right - ix, font.lineHeight - 2, rngFraction(tracker));
            } else {
                g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
                g.text(font, Component.literal(row[1]), right - font.width(row[1]), iy, SBSTheme.TEXT);
            }
            iy += lineH;
        }
    }

    // ------------------------------------------------------------------ RNG meter

    /** Marks the row the progress bar is drawn in; compared by identity. */
    private static final String[] BAR_ROW = {"", ""};

    /** The slayer whose meter the card shows: the running quest's, else the last one that moved. */
    private static SlayerBoss rngBoss(SlayerTracker tracker) {
        SlayerBoss quest = tracker.questBoss();
        return quest != null ? quest : RngMeterTracker.getInstance().mostRecent();
    }

    private static double rngFraction(SlayerTracker tracker) {
        RngMeterState state = RngMeterTracker.getInstance().state(rngBoss(tracker));
        return state == null ? -1 : state.fraction();
    }

    /**
     * The RNG meter rows: drop, bar, progress, gains, the estimate and the drop's value. Empty when
     * the feature is off or this slayer's meter has never been read.
     */
    private static List<String[]> rngRows(SlayerTracker tracker) {
        List<String[]> out = new ArrayList<>();
        var cfg = ConfigManager.getInstance().get().slayer;
        SlayerBoss boss = rngBoss(tracker);
        RngMeterTracker meters = RngMeterTracker.getInstance();
        RngMeterState state = cfg.rngMeter ? meters.state(boss) : null;
        if (state == null || state.current < 0) {
            return out;
        }
        String approx = state.approximate ? "~" : "";
        out.add(new String[] {"RNG Meter", state.selectedDrop != null ? state.selectedDrop : "no drop selected"});
        if (state.goal > 0) {
            out.add(BAR_ROW);
            out.add(new String[] {String.format(Locale.US, "%s%.1f%%", approx, state.fraction() * 100),
                    approx + NumberDisplay.grouped(state.current) + " / " + coins(state.goal)});
        } else {
            out.add(new String[] {"Goal unknown", "open the Slayer menu"});
        }
        String last = state.lastGain > 0 ? "+" + NumberDisplay.grouped(state.lastGain) : "?";
        long sessionGain = meters.sessionGain(boss);
        out.add(new String[] {"Last boss / session", last + " / +" + NumberDisplay.grouped(sessionGain)});
        long left = state.bossesLeft(meters.averageGain(boss));
        if (left >= 0) {
            out.add(new String[] {"Bosses left (est.)", "~" + NumberDisplay.grouped(left)});
        }
        if (cfg.rngMeterDropValue && state.selectedDrop != null) {
            String id = SlayerTracker.resolveItemId(state.selectedDrop);
            double value = id == null ? 0 : tracker.price(id);
            if (value > 0) {
                out.add(new String[] {"Drop value", coins(value)});
            }
        }
        return out;
    }

    private static void drawBar(GuiGraphicsExtractor g, int x, int y, int w, int h, double fraction) {
        g.fill(x, y, x + w, y + h, SBSTheme.CARD_BORDER);
        if (fraction > 0) {
            int filled = (int) Math.round((w - 2) * Math.min(1.0, fraction));
            g.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, SBSTheme.ACCENT);
        }
    }

    private static String displayName(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        if (entry != null && entry.name != null && !entry.name.isEmpty()) {
            return entry.name;
        }
        String[] words = itemId.toLowerCase(Locale.ROOT).split("_");
        StringBuilder pretty = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (pretty.length() > 0) {
                    pretty.append(' ');
                }
                pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return pretty.toString();
    }

    /** 12_400_000 -> "12.4M", 830_000 -> "830K", 950 -> "950" (unless "Shorten Numbers" is off). */
    private static String coins(double value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
