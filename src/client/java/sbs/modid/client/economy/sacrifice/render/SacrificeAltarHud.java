/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.sacrifice.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.sacrifice.logic.SacrificeLedger;
import sbs.modid.client.economy.sacrifice.logic.SacrificeTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.ui.HudEditorScreen;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Sacrifice Altar card: sacrifices, essence, bonus drops, what went in and the net. Shown in
 * The End once anything was sacrificed. Built once per tracker change, setting change or price
 * refresh; a frame only draws.
 */
public final class SacrificeAltarHud {

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 130;
    private static final int COLUMN_GAP = 12;
    private static final int GAIN = 0xFF57D977;
    private static final int LOSS = 0xFFFF6060;
    /** Prices are re-read at most this often; the card's net follows the market at that pace. */
    private static final long PRICE_REFRESH_MS = 5_000L;

    private record Version(int tracker, boolean allTime, boolean sellSide, long priceTick) {
    }

    private static volatile HudCard.Snapshot snapshot;

    private SacrificeAltarHud() {
    }

    /** Drawn from the HUD pass; self-hiding outside The End and until something was sacrificed. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.SacrificeAltarSettings cfg = ConfigManager.getInstance().get().sacrificeAltar;
        if (!cfg.enabled || HudLayout.isHidden(HudElement.SACRIFICE_ALTAR) || Minecraft.getInstance().player == null) {
            return;
        }
        SacrificeTracker tracker = SacrificeTracker.getInstance();
        boolean editor = GuiStateManager.getInstance().getCurrentScreen() instanceof HudEditorScreen;
        // Only at the altar (asked 2026-10-16), not all over The End.
        if (!editor && !tracker.nearAltar()) {
            return;
        }
        Version version = new Version(tracker.version(), cfg.showAllTime, cfg.sellSide,
                System.currentTimeMillis() / PRICE_REFRESH_MS);
        HudCard.Snapshot card = snapshot;
        if (card == null || !card.isFor(version)) {
            card = build(version);
            snapshot = card;
        }
        if (card.rows().isEmpty()) {
            return;
        }
        HudElement.Bounds b = HudElement.SACRIFICE_ALTAR.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.SACRIFICE_ALTAR, x, y, card.width(), card.height());
        HudLayout.begin(g, HudElement.SACRIFICE_ALTAR);
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
        SacrificeTracker tracker = SacrificeTracker.getInstance();
        SacrificeLedger ledger = v.allTime() ? tracker.allTime() : tracker.session();
        if (ledger.sacrifices == 0) {
            return new HudCard.Snapshot(List.of(), 0, 0, v);
        }
        Long essencePrice = SacrificeTracker.price(SacrificeTracker.ESSENCE_ID);
        List<HudCard.Line> lines = new ArrayList<>();
        lines.add(HudCard.Line.of(font, v.allTime() ? "Sacrifice Altar (all time)" : "Sacrifice Altar",
                SBSTheme.ACCENT_BRIGHT));
        lines.add(HudCard.Line.of(font, "Sacrifices", SBSTheme.TEXT_MUTED,
                String.valueOf(ledger.sacrifices), SBSTheme.TEXT));
        lines.add(HudCard.Line.of(font, "Dragon Essence", SBSTheme.TEXT_MUTED,
                ledger.essence + (essencePrice == null ? " (no price)" : ""), GAIN));
        for (Map.Entry<String, Long> e : ledger.bonus().entrySet()) {
            String name = ledger.bonusNames().getOrDefault(e.getKey(), e.getKey());
            Long unit = SacrificeTracker.price(e.getKey());
            lines.add(HudCard.Line.of(font, "  " + name, SBSTheme.TEXT_MUTED,
                    e.getValue() + "x" + (unit == null ? " (no price)" : ""), GAIN));
        }
        lines.add(HudCard.Line.of(font, "Items given", SBSTheme.TEXT_MUTED,
                (ledger.approxCosts > 0 ? "~" : "") + NumberDisplay.format(ledger.cost), LOSS));
        long net = ledger.net(essencePrice, SacrificeTracker::price);
        lines.add(HudCard.Line.of(font, "Net", SBSTheme.TEXT_MUTED,
                (net > 0 ? "+" : "") + NumberDisplay.format(net), net >= 0 ? GAIN : LOSS));
        if (ledger.bestItem != null) {
            lines.add(HudCard.Line.of(font, "Best", SBSTheme.TEXT_MUTED,
                    ledger.bestItem + " " + (ledger.bestNet > 0 ? "+" : "") + NumberDisplay.format(ledger.bestNet),
                    ledger.bestNet >= 0 ? GAIN : LOSS));
        }
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
