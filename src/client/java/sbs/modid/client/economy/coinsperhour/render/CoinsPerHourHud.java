/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.coinsperhour.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig.CoinsPerHourSettings;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.coinsperhour.logic.CoinLedger;
import sbs.modid.client.economy.coinsperhour.logic.CoinLines.Source;
import sbs.modid.client.economy.coinsperhour.logic.CoinsPerHourTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Coins per Hour card: earned per active hour, the session's earned / spent / moved, active time,
 * and optionally where the coins came from and went. The unknown bucket is always shown when it has
 * anything in it - hiding it would make the breakdown look complete when it is not.
 */
public final class CoinsPerHourHud {

    private static final int PAD = 5;
    private static final int MIN_WIDTH = 140;
    private static final int GAIN = 0xFF57D977;
    private static final int LOSS = 0xFFFF6060;

    /**
     * The card as last built. Rebuilt only when its key below moves: a booked figure, the active /
     * idle flag, a setting, or the one-second clock the active time and the hourly rate read.
     * Render thread only.
     */
    private static HudCard.Snapshot card;
    private static int builtLedger = -1;
    private static long builtSecond = -1;
    private static boolean builtActive;
    private static boolean builtBazaarClaims;
    private static boolean builtAuctionClaims;
    private static boolean builtBreakdown;

    private CoinsPerHourHud() {
    }

    /** Drawn from the HUD pass; self-hiding until the first purse reading. */
    public static void render(GuiGraphicsExtractor g) {
        CoinsPerHourSettings cfg = ConfigManager.getInstance().get().coinsPerHour;
        if (!cfg.enabled || HudLayout.isHidden(HudElement.COINS_PER_HOUR)
                || Minecraft.getInstance().player == null) {
            return;
        }
        CoinsPerHourTracker tracker = CoinsPerHourTracker.getInstance();
        CoinLedger ledger = tracker.ledger();
        if (!ledger.started()) {
            return;
        }
        long now = System.currentTimeMillis();
        int version = ledger.version();
        long second = now / 1000L;
        boolean active = ledger.active(now, tracker.idleMs());
        if (card == null || version != builtLedger || second != builtSecond || active != builtActive
                || cfg.bazaarClaimsEarn != builtBazaarClaims || cfg.auctionClaimsEarn != builtAuctionClaims
                || cfg.showBreakdown != builtBreakdown) {
            builtLedger = version;
            builtSecond = second;
            builtActive = active;
            builtBazaarClaims = cfg.bazaarClaimsEarn;
            builtAuctionClaims = cfg.auctionClaimsEarn;
            builtBreakdown = cfg.showBreakdown;
            card = build(ledger, cfg, active);
        }
        HudCard.Snapshot current = card;

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        HudElement.Bounds b = HudElement.COINS_PER_HOUR.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.COINS_PER_HOUR, x, y, current.width(), current.height());

        HudLayout.begin(g, HudElement.COINS_PER_HOUR);
        HudCard.draw(g, x, y, current.width(), current.height());
        int ix = x + PAD;
        int right = x + current.width() - PAD;
        int iy = y + PAD;
        for (HudCard.Line line : current.rows()) {
            HudCard.drawLine(g, font, line, ix, right, iy);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    private static HudCard.Snapshot build(CoinLedger ledger, CoinsPerHourSettings cfg, boolean active) {
        long earned = ledger.earned(cfg.bazaarClaimsEarn, cfg.auctionClaimsEarn);
        long rate = ledger.perHour(earned);

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Per hour", rate > 0 ? NumberDisplay.format(rate) : "-", GAIN));
        rows.add(new Row("Earned", NumberDisplay.format(earned), GAIN));
        rows.add(new Row("Spent", NumberDisplay.format(ledger.spent()), LOSS));
        long movedIn = ledger.transferIn() + ledger.uncountedClaims(cfg.bazaarClaimsEarn, cfg.auctionClaimsEarn);
        if (movedIn > 0 || ledger.transferOut() > 0) {
            rows.add(new Row("Moved", "+" + NumberDisplay.format(movedIn) + " / -"
                    + NumberDisplay.format(ledger.transferOut()), SBSTheme.TEXT_MUTED));
        }
        rows.add(new Row("Active", clock(ledger.activeMs()) + (active ? "" : " (idle)"),
                active ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED));
        if (cfg.showBreakdown) {
            for (Map.Entry<Source, Long> entry
                    : ledger.earnedBySource(cfg.bazaarClaimsEarn, cfg.auctionClaimsEarn).entrySet()) {
                rows.add(new Row("  + " + label(entry.getKey()),
                        NumberDisplay.format(entry.getValue()), SBSTheme.TEXT_MUTED));
            }
            for (Map.Entry<Source, Long> entry : ledger.spentBySource().entrySet()) {
                rows.add(new Row("  - " + label(entry.getKey()),
                        NumberDisplay.format(entry.getValue()), SBSTheme.TEXT_MUTED));
            }
        }

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        List<HudCard.Line> lines = new ArrayList<>(rows.size() + 1);
        lines.add(HudCard.Line.of(font, "Coins per Hour", SBSTheme.ACCENT_BRIGHT));
        int contentW = lines.get(0).leftWidth();
        for (Row row : rows) {
            HudCard.Line line = HudCard.Line.of(font, row.label(), SBSTheme.TEXT_MUTED, row.value(), row.colour());
            lines.add(line);
            contentW = Math.max(contentW, line.width(12));
        }
        int width = Math.max(MIN_WIDTH, contentW + PAD * 2);
        int height = PAD * 2 + lineH * lines.size() - 2;
        return new HudCard.Snapshot(List.copyOf(lines), width, height, null);
    }

    private record Row(String label, String value, int colour) {
    }

    private static String label(Source source) {
        return switch (source) {
            case BAZAAR -> "Bazaar";
            case AUCTION -> "Auction";
            case NPC -> "NPC";
            case BANK -> "Bank";
            case PURCHASE -> "Purchases";
            case OTHER -> "Other";
            case UNKNOWN -> "Unexplained";
        };
    }

    /** 3_725_000 ms -> "1h 02m". */
    private static String clock(long ms) {
        long minutes = ms / 60_000L;
        return String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60);
    }
}
