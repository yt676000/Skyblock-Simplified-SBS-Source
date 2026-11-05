/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudraprofit.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.combat.kuudra.logic.KuudraTracker;
import sbs.modid.client.combat.kuudraprofit.logic.KuudraProfitStats;
import sbs.modid.client.combat.kuudraprofit.logic.KuudraProfitTracker;
import sbs.modid.client.combat.kuudraprofit.logic.KuudraRunLedger;
import sbs.modid.client.combat.kuudraprofit.model.KuudraRun;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.KuudraProfitSettings;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Kuudra Profit card: last run, session total, profit per hour, runs, top drop.
 *
 * <p>Rows are rebuilt only when the ledger's revision or the tier filter changes, at most once a
 * second; per frame the prepared components are drawn and nothing else. Profit carries its sign as
 * well as its colour, and a figure with an unpriced item or key ends in {@code +}.
 */
public final class KuudraProfitHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int COLUMN_GAP = 10;
    private static final long REBUILD_MS = 1_000L;
    private static final int GREEN = 0xFF57D977;
    private static final int RED = 0xFFFF6B6B;
    private static final Component TITLE = Component.literal("Kuudra Profit");

    private record Row(Component label, Component value, int labelW, int valueW, int color) {
    }

    private static List<Row> rows = List.of();
    private static int width;
    private static int height;
    private static int builtRevision = -1;
    private static int builtFilter = -1;
    private static long builtAt;

    private KuudraProfitHud() {
    }

    private static KuudraProfitSettings cfg() {
        return ConfigManager.getInstance().get().kuudraProfit;
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        KuudraProfitSettings settings = cfg();
        if (!settings.enabled || !settings.card || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.KUUDRA_PROFIT)) {
            return;
        }
        // Island binding: the Hollow, and the Crimson Isle where keys are bought and runs queued.
        if (!settings.showEverywhere && !KuudraTracker.getInstance().inHollow()
                && !SkyBlockLocation.onIsland("Crimson Isle")) {
            return;
        }
        KuudraRunLedger ledger = KuudraProfitTracker.getInstance().ledger();
        long now = System.currentTimeMillis();
        if ((ledger.revision() != builtRevision || settings.tierFilter != builtFilter)
                && now - builtAt >= REBUILD_MS) {
            builtAt = now;
            builtRevision = ledger.revision();
            builtFilter = settings.tierFilter;
            rebuild(ledger, settings.tierFilter);
        }
        HudElement.Bounds bounds = HudElement.KUUDRA_PROFIT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = (int) bounds.x();
        int y = (int) bounds.y();
        HudLayout.begin(g, HudElement.KUUDRA_PROFIT);
        HudLayout.measure(HudElement.KUUDRA_PROFIT, x, y, width, height);
        HudCard.draw(g, x, y, width, height);
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;
        int iy = y + PAD;
        g.text(font, TITLE, x + PAD, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        int right = x + width - PAD;
        for (Row row : rows) {
            g.text(font, row.label, x + PAD, iy, SBSTheme.TEXT_MUTED);
            g.text(font, row.value, right - row.valueW, iy, row.color);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static void rebuild(KuudraRunLedger ledger, int filter) {
        Font font = Minecraft.getInstance().font;
        List<Row> built = new ArrayList<>();
        KuudraRun last = ledger.lastRun();
        if (last != null) {
            String label = "Last run (" + tier(last.tier) + (last.won ? "" : ", lost") + ")";
            built.add(row(font, label, coins(last.profit(), last.incomplete()), last.profit()));
        }
        KuudraProfitStats session = KuudraProfitStats.since(ledger.data().runs, ledger.sessionStart(), filter);
        built.add(row(font, "Session" + (filter == 0 ? "" : " T" + filter),
                coins(session.profit(), session.incomplete()), session.profit()));
        built.add(row(font, "Runs", String.valueOf(session.runs()), 0));
        if (session.runs() > 0) {
            built.add(row(font, "Per run", coins(session.perRun(), session.incomplete()), session.perRun()));
        }
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

    /** {@code "T5"}, or {@code "T?"} when the tier was never read. */
    public static String tier(int tier) {
        return tier >= 1 && tier <= 5 ? "T" + tier : "T?";
    }

    /** {@code +1.2M}, {@code -400k}, {@code +1.2M+} when something had no price. */
    public static String coins(long value, boolean incomplete) {
        return (value > 0 ? "+" : "") + NumberDisplay.format(value) + (incomplete ? "+" : "");
    }
}
