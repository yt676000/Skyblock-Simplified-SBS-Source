/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.dice.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.dice.logic.DiceLedger;
import sbs.modid.client.economy.dice.logic.DiceTracker;
import sbs.modid.client.economy.dice.logic.DiceVisibility;
import sbs.modid.client.economy.dice.model.DiceKind;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dice Profit card: for each dice kind that was rolled, rolls, spent, won, dyes and the net.
 * Built once per tracker change, price change or setting change; a frame only draws.
 */
public final class DiceProfitHud {

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 130;
    private static final int COLUMN_GAP = 12;
    private static final int GAIN = 0xFF57D977;
    private static final int LOSS = 0xFFFF6060;

    /** Everything a snapshot depends on. Prices by value: the net moves with the market. */
    private record Version(int tracker, boolean allTime, boolean distribution, boolean countLoss,
                           Long archfiend, Long highClass, Long dye) {
    }

    private static volatile HudCard.Snapshot snapshot;

    private DiceProfitHud() {
    }

    /** Drawn from the HUD pass; self-hiding until something was rolled. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.DiceProfitSettings cfg = ConfigManager.getInstance().get().diceProfit;
        if (!cfg.enabled || HudLayout.isHidden(HudElement.DICE_PROFIT) || Minecraft.getInstance().player == null) {
            return;
        }
        boolean editor = GuiStateManager.getInstance().getCurrentScreen() instanceof HudEditorScreen;
        if (!DiceVisibility.shown(cfg.showOnlyAfterRoll, cfg.showSeconds, DiceTracker.getInstance().lastRollAt(),
                System.currentTimeMillis(), editor)) {
            return;
        }
        LbinCache lbin = LbinCache.getInstance();
        Version version = new Version(DiceTracker.getInstance().version(), cfg.showAllTime, cfg.showDistribution,
                cfg.countDiceLoss, lbin.getLbin(DiceKind.ARCHFIEND.itemId()),
                lbin.getLbin(DiceKind.HIGH_CLASS.itemId()), lbin.getLbin(DiceKind.DYE_ID));
        HudCard.Snapshot card = snapshot;
        if (card == null || !card.isFor(version)) {
            card = build(version);
            snapshot = card;
        }
        if (card.rows().isEmpty()) {
            return;
        }
        HudElement.Bounds b = HudElement.DICE_PROFIT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.DICE_PROFIT, x, y, card.width(), card.height());
        HudLayout.begin(g, HudElement.DICE_PROFIT);
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
        DiceTracker tracker = DiceTracker.getInstance();
        DiceLedger ledger = v.allTime() ? tracker.allTime() : tracker.session();
        List<HudCard.Line> lines = new ArrayList<>();
        for (DiceKind kind : DiceKind.values()) {
            DiceLedger.Counts c = ledger.get(kind);
            if (c == null || c.rolls == 0) {
                continue;
            }
            Long dicePrice = kind == DiceKind.ARCHFIEND ? v.archfiend() : v.highClass();
            long net = c.net(dicePrice, v.dye(), v.countLoss());
            lines.add(HudCard.Line.of(font, kind.label(), SBSTheme.ACCENT));
            lines.add(HudCard.Line.of(font, "Rolls", SBSTheme.TEXT_MUTED, String.valueOf(c.rolls), SBSTheme.TEXT));
            lines.add(HudCard.Line.of(font, "Spent", SBSTheme.TEXT_MUTED, NumberDisplay.format(c.spent), LOSS));
            lines.add(HudCard.Line.of(font, "Won", SBSTheme.TEXT_MUTED, NumberDisplay.format(c.won), GAIN));
            if (c.dyes > 0) {
                lines.add(HudCard.Line.of(font, "Dyes", SBSTheme.TEXT_MUTED,
                        c.dyes + (v.dye() == null ? " (no price)" : ""), GAIN));
            }
            if (v.countLoss() && c.diceLost > 0) {
                lines.add(HudCard.Line.of(font, "Dice lost", SBSTheme.TEXT_MUTED,
                        c.diceLost + (dicePrice == null ? " (no price)" : ""), LOSS));
            }
            lines.add(HudCard.Line.of(font, "Net", SBSTheme.TEXT_MUTED,
                    (net > 0 ? "+" : "") + NumberDisplay.format(net), net >= 0 ? GAIN : LOSS));
            if (v.distribution()) {
                StringBuilder faces = new StringBuilder();
                for (int face = 1; face <= 7; face++) {
                    if (face > 1) {
                        faces.append(' ');
                    }
                    faces.append(face).append(':').append(c.faces[face]);
                }
                lines.add(HudCard.Line.of(font, faces.toString(), SBSTheme.TEXT_MUTED));
            }
        }
        if (lines.isEmpty()) {
            return new HudCard.Snapshot(List.of(), 0, 0, v);
        }
        lines.add(0, HudCard.Line.of(font, v.allTime() ? "Dice Profit (all time)" : "Dice Profit",
                SBSTheme.ACCENT_BRIGHT));
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
