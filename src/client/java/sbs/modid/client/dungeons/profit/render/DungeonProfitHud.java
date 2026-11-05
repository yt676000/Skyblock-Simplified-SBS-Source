/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.DungeonProfitSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.dungeons.profit.logic.DungeonProfitTracker;
import sbs.modid.client.dungeons.profit.logic.ProfitStats;
import sbs.modid.client.dungeons.profit.logic.RunLedger;
import sbs.modid.client.dungeons.profit.model.DungeonRun;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dungeon Profit card: last run, its costs, session total, profit per hour, runs, top drop.
 *
 * <p>The rows are rebuilt only when the ledger's revision or a setting the rows depend on changes,
 * and at most once a second; per frame the prepared components are drawn and nothing else. Profit
 * carries its sign as well as its colour, and a total with an unpriced item ends in {@code +} so
 * nothing is told by colour alone and no floor is shown as a figure.
 */
public final class DungeonProfitHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int COLUMN_GAP = 10;
    private static final long REBUILD_MS = 1_000L;
    private static final int GREEN = 0xFF57D977;
    private static final int RED = 0xFFFF6B6B;
    private static final Component TITLE = Component.literal("Dungeon Profit");

    private record Row(Component label, Component value, int labelW, int valueW, int color) {
    }

    private static List<Row> rows = List.of();
    private static int width;
    private static int height;
    private static int builtRevision = -1;
    private static String builtFilter = "";
    private static boolean builtCosts;
    private static long builtAt;

    private DungeonProfitHud() {
    }

    private static DungeonProfitSettings cfg() {
        return ConfigManager.getInstance().get().dungeonProfit;
    }

    /**
     * Island binding: the Dungeon Hub only - not inside the Catacombs, where the run keeps being
     * tracked - unless asked for everywhere.
     */
    static boolean shownOn(boolean showEverywhere, boolean inDungeonHub) {
        return showEverywhere || inDungeonHub;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        DungeonProfitSettings settings = cfg();
        if (!settings.enabled || !settings.card || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.DUNGEON_PROFIT)) {
            return;
        }
        if (!shownOn(settings.showEverywhere, SkyBlockLocation.onIsland("Dungeon Hub"))) {
            return;
        }
        RunLedger ledger = DungeonProfitTracker.getInstance().ledger();
        long now = System.currentTimeMillis();
        if ((ledger.revision() != builtRevision || !settings.floorFilter.equals(builtFilter)
                || settings.cardCosts != builtCosts) && now - builtAt >= REBUILD_MS) {
            builtAt = now;
            builtRevision = ledger.revision();
            builtFilter = settings.floorFilter;
            builtCosts = settings.cardCosts;
            rebuild(ledger, settings.floorFilter, settings.cardCosts);
        }
        HudElement.Bounds bounds = HudElement.DUNGEON_PROFIT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) bounds.x();
        int y = (int) bounds.y();
        HudLayout.begin(g, HudElement.DUNGEON_PROFIT);
        HudLayout.measure(HudElement.DUNGEON_PROFIT, x, y, width, height);
        HudCard.draw(g, x, y, width, height);
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;
        int iy = y + PAD;
        g.text(font, TITLE, x + PAD, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        int right = x + width - PAD;
        for (Row row : rows) {
            g.text(font, row.label, x + PAD, iy, SBSTheme.TEXT_MUTED);
            if (row.value != null) {
                g.text(font, row.value, right - row.valueW, iy, row.color);
            }
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static void rebuild(RunLedger ledger, String filter, boolean showCosts) {
        Font font = Minecraft.getInstance().font;
        List<Row> built = new ArrayList<>();
        DungeonRun current = ledger.current();
        if (current != null) {
            built.add(row(font, "This run (" + current.floor + ")", coins(current.profit(), current.incomplete()),
                    current.profit()));
        }
        DungeonRun last = ledger.lastRun();
        if (last != null) {
            built.add(row(font, "Last run (" + last.floor + ")", coins(last.profit(), last.incomplete()),
                    last.profit()));
        }
        DungeonRun costed = current != null ? current : last;
        if (showCosts && costed != null && costed.cost() > 0) {
            built.add(row(font, "Costs", costBreakdown(ProfitStats.Costs.of(costed)), 0));
        }
        ProfitStats session = ProfitStats.since(ledger.data().runs, ledger.sessionStart(), filter);
        built.add(row(font, "Session" + (filter.isEmpty() ? "" : " " + filter),
                coins(session.profit(), session.incomplete()), session.profit()));
        built.add(row(font, "Runs", String.valueOf(session.runs()), 0));
        if (session.perHour() != 0) {
            built.add(row(font, "Per hour", coins(session.perHour(), session.incomplete()), session.perHour()));
        }
        if (!session.topDrop().isEmpty()) {
            built.add(row(font, "Top drop", session.topDrop() + " " + NumberDisplay.format(session.topDropValue()), 1));
        }
        int labelW = 0;
        int valueW = 0;
        for (Row row : built) {
            labelW = Math.max(labelW, row.labelW);
            valueW = Math.max(valueW, row.valueW);
        }
        rows = List.copyOf(built);
        width = PAD * 2 + Math.max(font.width(TITLE), labelW + COLUMN_GAP + valueW);
        height = PAD * 2 + (font.lineHeight + LINE_GAP) * (1 + rows.size()) - LINE_GAP;
    }

    private static Row row(Font font, String label, String value, long sign) {
        int color = sign > 0 ? GREEN : sign < 0 ? RED : SBSTheme.TEXT;
        return new Row(Component.literal(label), Component.literal(value), font.width(label), font.width(value),
                color);
    }

    /**
     * {@code "chest 4M, 1 Kismet 1.2M, 1 potion 34k"}: the cost lines that are not zero, each named,
     * so a cost is never told by position alone.
     */
    public static String costBreakdown(ProfitStats.Costs c) {
        List<String> parts = new ArrayList<>(4);
        if (c.chest() > 0) {
            parts.add("chest " + NumberDisplay.format(c.chest()));
        }
        if (c.kismets() > 0) {
            parts.add(c.kismets() + " Kismet " + NumberDisplay.format(c.kismet()));
        }
        if (c.potions() > 0) {
            parts.add(c.potions() + (c.potions() == 1 ? " potion " : " potions ") + NumberDisplay.format(c.potion()));
        }
        if (c.extra() > 0) {
            parts.add("extra " + NumberDisplay.format(c.extra()));
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    /** {@code +1.2M}, {@code -400k}, {@code +1.2M+} when something had no price. */
    public static String coins(long value, boolean incomplete) {
        return (value > 0 ? "+" : "") + NumberDisplay.format(value) + (incomplete ? "+" : "");
    }
}
