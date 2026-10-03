/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.NucleusRunSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.helper.map.logic.HollowsTracker;
import sbs.modid.client.skills.mining.nucleus.logic.NucleusPricing;
import sbs.modid.client.skills.mining.nucleus.logic.NucleusRunLedger;
import sbs.modid.client.skills.mining.nucleus.logic.NucleusRunTracker;
import sbs.modid.client.skills.mining.nucleus.model.Crystal;
import sbs.modid.client.skills.mining.nucleus.model.NucleusRunData;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Nucleus Run card: the run in progress (time on the Hollows, the five crystals, what it has
 * earned and used so far), the last run, and the lifetime figures - each as <i>Nucleus</i> (the
 * bundle less the costs) beside <i>Run total</i> (that plus the chest and sack loot on the way).
 *
 * <p>Text is rebuilt twice a second and only drawn per frame. Profit carries its sign as well as its
 * colour, and the crystals say "placed N/5" in words beside the coloured letters, so nothing is told
 * by colour alone. An unpriced item is said out loud rather than shown as a confident total.
 */
public final class NucleusRunHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int COLUMN_GAP = 10;
    private static final long REBUILD_MS = 500L;
    private static final int GREEN = 0xFF57D977;
    private static final int RED = 0xFFFF6B6B;
    private static final int WARN = 0xFFFFC12E;

    private static List<Row> rows = List.of();
    private static String title = "";
    private static String crystals = "";
    private static long builtAt;

    private NucleusRunHud() {
    }

    private static NucleusRunSettings cfg() {
        return ConfigManager.getInstance().get().nucleusRun;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        NucleusRunSettings settings = cfg();
        if (!settings.enabled || !settings.card || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.NUCLEUS_RUN)) {
            return;
        }
        // Island binding: the run's content is the Crystal Hollows, so that is where the card shows,
        // unless the player asked for it everywhere. The run itself is not dropped on leaving.
        if (!settings.showEverywhere && !SkyBlockLocation.onIsland(HollowsTracker.ISLAND)) {
            return;
        }
        NucleusRunTracker tracker = NucleusRunTracker.getInstance();
        if (tracker.ledger().current() == null && tracker.ledger().lastRun() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - builtAt >= REBUILD_MS) {
            builtAt = now;
            rebuild(tracker);
        }
        HudElement.Bounds bounds = HudElement.NUCLEUS_RUN.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.NUCLEUS_RUN);
        draw(g, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void rebuild(NucleusRunTracker tracker) {
        NucleusRunLedger ledger = tracker.ledger();
        NucleusRunData.Run run = ledger.current();
        NucleusRunData.Totals totals = ledger.data().totals;
        List<Row> built = new ArrayList<>();

        title = "Nucleus Run" + (run != null ? "  " + NucleusRunTracker.duration(run.onlineMs) : "");
        crystals = crystalLine(ledger);

        built.add(new Row("", "Nucleus", "Run total", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        NucleusPricing.Summary live = tracker.preview();
        if (run != null && live != null) {
            built.add(plain("Revenue", live.nucleusRevenue(), live.nucleusRevenue() + live.lootRevenue()));
            built.add(plain("Costs", -live.costs(), -live.costs()));
            built.add(profit("This run", live.nucleusProfit(), live.runProfit()));
        }
        NucleusRunData.Run last = ledger.lastRun();
        if (last != null) {
            built.add(profit(last.partial ? "Last run*" : "Last run", last.nucleusProfit, last.runProfit));
        }
        if (totals.runs > 0) {
            built.add(profit("Avg / run", totals.nucleusProfit / totals.runs, totals.runProfit / totals.runs));
            if (totals.onlineMs >= 60_000L) {
                double hours = totals.onlineMs / 3_600_000.0;
                built.add(profit("Per hour", Math.round(totals.nucleusProfit / hours),
                        Math.round(totals.runProfit / hours)));
            }
            built.add(profit("Total (" + totals.runs + (totals.runs == 1 ? " run)" : " runs)"),
                    totals.nucleusProfit, totals.runProfit));
        }
        if (run != null && !run.nonCoin.isEmpty()) {
            StringBuilder extra = new StringBuilder();
            for (Map.Entry<String, Long> entry : run.nonCoin.entrySet()) {
                if (!extra.isEmpty()) {
                    extra.append(", ");
                }
                extra.append('+').append(NumberDisplay.format(entry.getValue())).append(' ').append(entry.getKey());
            }
            built.add(new Row(extra.toString(), "", "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        }
        if (live != null && !live.unpriced().isEmpty()) {
            built.add(new Row(live.unpriced().size() + " unpriced (counted 0)", "", "", WARN, WARN));
        }
        if (last != null && last.partial) {
            built.add(new Row("* partly tracked", "", "", SBSTheme.TEXT_MUTED, SBSTheme.TEXT_MUTED));
        }
        rows = List.copyOf(built);
    }

    private static String crystalLine(NucleusRunLedger ledger) {
        StringBuilder line = new StringBuilder();
        int placed = 0;
        int found = 0;
        for (Crystal crystal : Crystal.values()) {
            Crystal.State state = ledger.crystalState(crystal);
            String colour = switch (state) {
                case PLACED -> "§a";
                case FOUND -> "§f";
                case NONE -> "§8";
            };
            if (state == Crystal.State.PLACED) {
                placed++;
            }
            if (state != Crystal.State.NONE) {
                found++;
            }
            line.append(colour).append(crystal.shortName()).append(' ');
        }
        return line.append("§7 found ").append(found).append("/5, placed ").append(placed).append("/5")
                .toString();
    }

    private static Row plain(String label, long nucleus, long total) {
        return new Row(label, signed(nucleus), signed(total), SBSTheme.TEXT, SBSTheme.TEXT);
    }

    private static Row profit(String label, long nucleus, long total) {
        return new Row(label, signed(nucleus), signed(total), nucleus < 0 ? RED : GREEN, total < 0 ? RED : GREEN);
    }

    private static String signed(long value) {
        return (value > 0 ? "+" : "") + NumberDisplay.format(value);
    }

    private static void draw(GuiGraphicsExtractor g, int x, int y) {
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;

        // Measure every column from the strings actually drawn this time.
        int labelW = 0;
        int nucleusW = 0;
        int totalW = 0;
        int spanW = Math.max(font.width(title), font.width(crystals));
        for (Row row : rows) {
            if (row.nucleus.isEmpty() && row.total.isEmpty()) {
                spanW = Math.max(spanW, font.width(row.label));
                continue;
            }
            labelW = Math.max(labelW, font.width(row.label));
            nucleusW = Math.max(nucleusW, font.width(row.nucleus));
            totalW = Math.max(totalW, font.width(row.total));
        }
        int tableW = labelW + COLUMN_GAP + nucleusW + COLUMN_GAP + totalW;
        int width = PAD * 2 + Math.max(tableW, spanW);
        int height = PAD * 2 + lineH * (2 + rows.size()) - LINE_GAP;
        HudLayout.measure(HudElement.NUCLEUS_RUN, x, y, width, height);
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int nucleusRight = right - totalW - COLUMN_GAP;
        int iy = y + PAD;
        g.text(font, Component.literal(title), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        g.text(font, Component.literal(crystals), ix, iy, SBSTheme.TEXT);
        iy += lineH;
        for (Row row : rows) {
            g.text(font, Component.literal(row.label), ix, iy, row.nucleus.isEmpty() && row.total.isEmpty()
                    ? row.nucleusColor : SBSTheme.TEXT_MUTED);
            if (!row.nucleus.isEmpty()) {
                g.text(font, Component.literal(row.nucleus), nucleusRight - font.width(row.nucleus), iy,
                        row.nucleusColor);
            }
            if (!row.total.isEmpty()) {
                g.text(font, Component.literal(row.total), right - font.width(row.total), iy, row.totalColor);
            }
            iy += lineH;
        }
    }

    private record Row(String label, String nucleus, String total, int nucleusColor, int totalColor) {
    }
}
