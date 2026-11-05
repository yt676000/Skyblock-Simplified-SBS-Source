/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.combat.diana.logic.DianaEvent;
import sbs.modid.client.combat.diana.logic.DianaLootLedger;
import sbs.modid.client.combat.diana.logic.DianaLootPrices;
import sbs.modid.client.combat.diana.logic.DianaTracker;
import sbs.modid.client.combat.diana.model.LootPriceSource;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.prices.PriceVersion;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The Diana Profit card: what the event has paid, per active hour and per burrow, and the drops worth
 * the most. Built once per ledger change, price refresh or setting change; a frame only draws.
 *
 * <p>Drawn where the Diana cards are - in the Hub while the ritual is running - and self-hiding until
 * something has been earned.
 */
public final class DianaProfitHud {

    private static final int PAD = 5;
    private static final int LINE_GAP = 2;
    private static final int MIN_W = 130;
    private static final int COLUMN_GAP = 12;
    private static final int TOP_DROPS = 3;
    private static final int GAIN = 0xFF57D977;
    private static final int LOSS = 0xFFFF6060;

    /** Everything a snapshot depends on. */
    private record Version(int ledger, int prices, boolean allTime, String source, boolean countCoins,
                           int costPerHour) {
    }

    /** One priced line of the table, shared with {@code /sbs diana profit}. */
    public record Priced(String name, long count, Long each, long value) {
    }

    private static volatile HudCard.Snapshot snapshot;

    private DianaProfitHud() {
    }

    /** Drawn from the HUD pass. */
    public static void render(GuiGraphicsExtractor g) {
        SBSConfig.DianaSettings cfg = ConfigManager.getInstance().get().diana;
        if (!cfg.enabled || !cfg.lootProfit || HudLayout.isHidden(HudElement.DIANA_PROFIT)
                || !DianaEvent.awake() || !SkyBlockLocation.onIsland("Hub")) {
            return;
        }
        int ledger = DianaTracker.getInstance().lootVersion();
        int prices = PriceVersion.get();
        HudCard.Snapshot card = snapshot;
        // Field by field against the built version, so an unchanged frame allocates no key.
        if (card == null || !(card.version() instanceof Version built) || built.ledger() != ledger
                || built.prices() != prices || built.allTime() != cfg.lootShowAllTime
                || !java.util.Objects.equals(built.source(), cfg.lootPriceSource)
                || built.countCoins() != cfg.lootCountCoins || built.costPerHour() != cfg.lootCostPerHour) {
            card = build(new Version(ledger, prices, cfg.lootShowAllTime, cfg.lootPriceSource,
                    cfg.lootCountCoins, cfg.lootCostPerHour));
            snapshot = card;
        }
        if (card.rows().isEmpty()) {
            return;
        }
        HudElement.Bounds b = HudElement.DIANA_PROFIT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.DIANA_PROFIT, x, y, card.width(), card.height());
        HudLayout.begin(g, HudElement.DIANA_PROFIT);
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

    /** The ledger the card is showing under the current setting. */
    public static DianaLootLedger ledger(SBSConfig.DianaSettings cfg) {
        DianaTracker tracker = DianaTracker.getInstance();
        return cfg.lootShowAllTime ? tracker.lootAllTime() : tracker.lootSession();
    }

    /** Every item in {@code ledger} priced under {@code source}, most valuable first. Coins are not included. */
    public static List<Priced> priced(DianaLootLedger ledger, LootPriceSource source) {
        List<Priced> out = new ArrayList<>();
        for (Map.Entry<String, Long> entry : ledger.items().entrySet()) {
            Long each = DianaLootPrices.price(entry.getKey(), source);
            long value = each == null ? 0L : each * entry.getValue();
            out.add(new Priced(ledger.nameOf(entry.getKey()), entry.getValue(), each, value));
        }
        out.sort(Comparator.comparingLong(Priced::value).reversed());
        return out;
    }

    /** The profit of {@code ledger} under the current settings. */
    public static long profit(DianaLootLedger ledger, SBSConfig.DianaSettings cfg) {
        LootPriceSource source = LootPriceSource.byName(cfg.lootPriceSource);
        return ledger.profit(id -> DianaLootPrices.price(id, source), cfg.lootCountCoins,
                Math.max(0, cfg.lootCostPerHour));
    }

    private static HudCard.Snapshot build(Version v) {
        SBSConfig.DianaSettings cfg = ConfigManager.getInstance().get().diana;
        DianaLootLedger ledger = ledger(cfg);
        if (!ledger.earned()) {
            return new HudCard.Snapshot(List.of(), 0, 0, v);
        }
        Font font = Minecraft.getInstance().font;
        long profit = profit(ledger, cfg);
        List<HudCard.Line> lines = new ArrayList<>();
        lines.add(HudCard.Line.of(font, v.allTime() ? "Diana Profit (all time)" : "Diana Profit",
                SBSTheme.ACCENT_BRIGHT));
        lines.add(HudCard.Line.of(font, "Profit", SBSTheme.TEXT_MUTED, signed(profit), profit >= 0 ? GAIN : LOSS));
        lines.add(HudCard.Line.of(font, "Per hour", SBSTheme.TEXT_MUTED,
                ledger.activeMs < DianaLootLedger.MIN_RATE_MS ? "-"
                        : signed(DianaLootLedger.perHour(profit, ledger.activeMs)), SBSTheme.TEXT));
        lines.add(HudCard.Line.of(font, "Per burrow", SBSTheme.TEXT_MUTED,
                ledger.burrows == 0 ? "-" : signed(DianaLootLedger.perBurrow(profit, ledger.burrows)),
                SBSTheme.TEXT));
        lines.add(HudCard.Line.of(font, "Burrows", SBSTheme.TEXT_MUTED,
                ledger.burrows + " · " + clock(ledger.activeMs), SBSTheme.TEXT));
        if (v.countCoins() && ledger.coins > 0) {
            lines.add(HudCard.Line.of(font, "Coins", SBSTheme.TEXT_MUTED, NumberDisplay.format(ledger.coins),
                    GAIN));
        }
        int shown = 0;
        for (Priced item : priced(ledger, LootPriceSource.byName(v.source()))) {
            if (shown++ >= TOP_DROPS) {
                break;
            }
            String value = item.each() == null ? "no price" : NumberDisplay.format(item.value());
            lines.add(HudCard.Line.of(font, item.count() + "x " + item.name(), SBSTheme.TEXT_MUTED, value,
                    SBSTheme.TEXT));
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

    private static String signed(long value) {
        return (value > 0 ? "+" : "") + NumberDisplay.format(value);
    }

    /** "1h 05m" - active time, which runs to hours. */
    public static String clock(long millis) {
        long minutes = Math.max(0L, millis) / 60_000L;
        return minutes / 60 + "h " + String.format(java.util.Locale.ROOT, "%02d", minutes % 60) + "m";
    }
}
