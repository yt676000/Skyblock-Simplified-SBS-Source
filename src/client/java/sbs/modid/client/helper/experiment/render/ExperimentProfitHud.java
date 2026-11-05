/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.helper.experiment.logic.ExperimentLedger;
import sbs.modid.client.helper.experiment.logic.ExperimentProfit;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Experiment Profit card: claims, value won, cost and net for today (or this session).
 * Built once per tracker change, setting change or {@value #PRICE_REFRESH_MS} ms price refresh;
 * a frame only draws.
 */
public final class ExperimentProfitHud {

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 130;
    private static final int COLUMN_GAP = 12;
    private static final int GAIN = 0xFF57D977;
    private static final int LOSS = 0xFFFF6060;
    /** Prices of many items feed the value, so it is re-priced on a clock rather than per price. */
    private static final long PRICE_REFRESH_MS = 5_000L;

    /** Everything a snapshot depends on. */
    private record Version(int tracker, boolean session, int side, int cost, long priceTick) {
    }

    private static volatile HudCard.Snapshot snapshot;

    private ExperimentProfitHud() {
    }

    /** Drawn from the HUD pass; self-hiding until something was claimed. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.ExperimentationSettings cfg = ConfigManager.getInstance().get().experimentation;
        if (!cfg.profitTracker || HudLayout.isHidden(HudElement.EXPERIMENT_PROFIT)
                || Minecraft.getInstance().player == null) {
            return;
        }
        Version version = new Version(ExperimentProfit.getInstance().version(), cfg.profitShowSession,
                cfg.profitPriceSide, cfg.profitSessionCost, System.currentTimeMillis() / PRICE_REFRESH_MS);
        HudCard.Snapshot card = snapshot;
        if (card == null || !card.isFor(version)) {
            card = build(version);
            snapshot = card;
        }
        if (card.rows().isEmpty()) {
            return;
        }
        HudElement.Bounds b = HudElement.EXPERIMENT_PROFIT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.EXPERIMENT_PROFIT, x, y, card.width(), card.height());
        HudLayout.begin(g, HudElement.EXPERIMENT_PROFIT);
        HudCard.draw(g, x, y, card.width(), card.height());
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + LINE_GAP;
        int iy = y + PAD;
        for (HudCard.Line line : card.rows()) {
            HudCard.drawLine(g, font, line, x + PAD, x + card.width() - PAD, iy);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static HudCard.Snapshot build(Version v) {
        Font font = Minecraft.getInstance().font;
        ExperimentProfit tracker = ExperimentProfit.getInstance();
        ExperimentLedger ledger = v.session() ? tracker.session() : tracker.today();
        if (ledger.claims() == 0) {
            return new HudCard.Snapshot(List.of(), 0, 0, v);
        }
        ExperimentLedger.Pricer pricer = ExperimentProfit.pricer(v.side());
        ExperimentLedger.Value value = ledger.value(pricer);
        long cost = ledger.cost(v.cost());
        long net = value.coins() - cost;
        List<HudCard.Line> lines = new ArrayList<>();
        lines.add(HudCard.Line.of(font, v.session() ? "Experiments (session)" : "Experiments (today)",
                SBSTheme.ACCENT_BRIGHT));
        lines.add(HudCard.Line.of(font, "Claims", SBSTheme.TEXT_MUTED, String.valueOf(ledger.claims()),
                SBSTheme.TEXT));
        lines.add(HudCard.Line.of(font, "Value won", SBSTheme.TEXT_MUTED, NumberDisplay.format(value.coins())
                + (value.unpriced() > 0 ? " (+" + value.unpriced() + " no price)" : ""), GAIN));
        if (cost > 0) {
            lines.add(HudCard.Line.of(font, "Cost", SBSTheme.TEXT_MUTED, NumberDisplay.format(cost), LOSS));
        }
        if (ledger.experience() > 0) {
            lines.add(HudCard.Line.of(font, "Enchanting XP", SBSTheme.TEXT_MUTED,
                    NumberDisplay.format(ledger.experience()), SBSTheme.TEXT));
        }
        lines.add(HudCard.Line.of(font, "Net", SBSTheme.TEXT_MUTED,
                (net > 0 ? "+" : "") + NumberDisplay.format(net), net >= 0 ? GAIN : LOSS));
        long perClaim = net / ledger.claims();
        lines.add(HudCard.Line.of(font, "Per claim", SBSTheme.TEXT_MUTED,
                (perClaim > 0 ? "+" : "") + NumberDisplay.format(perClaim), perClaim >= 0 ? GAIN : LOSS));
        int contentW = 0;
        for (HudCard.Line line : lines) {
            contentW = Math.max(contentW, line.width(COLUMN_GAP));
        }
        int lineH = font.lineHeight + LINE_GAP;
        int width = Math.max(MIN_W, contentW + PAD * 2);
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        return new HudCard.Snapshot(List.copyOf(lines), width, height, v);
    }
}
