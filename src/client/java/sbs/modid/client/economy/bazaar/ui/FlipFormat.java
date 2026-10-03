/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.ui;

import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.bazaar.model.LocalFlip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared wording for locally computed flips, so the Best Flips overlay and the Bazaar Flips screen
 * describe the same numbers the same way.
 *
 * <p>Two rules run through all of it. Estimates are <b>labelled</b> as estimates — a bare "2.4M/hour"
 * is read as a promise, and this one is a projection from a single snapshot under assumptions that may
 * already be stale. And every result carries its <b>inputs</b>: the spread it came from, the volumes
 * on both sides, the share of them assumed. A user who can see those can decide the estimate is wrong,
 * which is the only defence a number like this has.
 */
public final class FlipFormat {

    private FlipFormat() {
    }

    /** The headline figure, always marked as an estimate rather than stated as a rate. */
    public static String perHour(double profitPerHour) {
        return "~" + NumberDisplay.format(profitPerHour) + "/h";
    }

    /**
     * The price line: what you bid, what you ask, and what is left per unit <i>after tax</i>.
     * The tax is named because it is the difference between this ranking and a naive one.
     */
    public static String priceLine(LocalFlip flip) {
        return String.format(Locale.ROOT, "buy %s -> sell %s  ·  %s/unit after %.3f%% tax",
                coins(flip.buyOrderPrice()), coins(flip.sellOfferPrice()),
                NumberDisplay.format(flip.unitMargin()), flip.taxRate() * 100);
    }

    /**
     * The assumptions line: the two-sided flow, the share of it claimed, and what that leaves. Written
     * as the arithmetic it is ("12.0k/h x 15% = 1.8k/h") so the share factor is visibly a guess the
     * user can change rather than a hidden constant.
     */
    public static String flowLine(LocalFlip flip, SBSConfig.BazaarSettings settings) {
        return String.format(Locale.ROOT, "flow %s/h x %d%% share = %s/h  ·  %s per order  ·  %s capital",
                NumberDisplay.shorten(flip.flowPerHour()), settings.localFlipSharePct,
                NumberDisplay.shorten(flip.unitsPerHour()), NumberDisplay.shorten(flip.unitsPerOrder()),
                coins(flip.capital()));
    }

    /** The market-shape line: spread, both weekly volumes, both order counts, and the confidence. */
    public static String marketLine(LocalFlip flip) {
        return String.format(Locale.ROOT,
                "spread %.1f%%  ·  %s bought / %s sold per week  ·  %d/%d orders  ·  [%s]",
                flip.spreadPct(), NumberDisplay.shorten(flip.demandWeek()),
                NumberDisplay.shorten(flip.supplyWeek()), flip.buyOrderCount(), flip.sellOrderCount(),
                flip.confidence().badge());
    }

    // ------------------------------------------------------------------
    // Compact forms, for the in-Bazaar overlay
    // ------------------------------------------------------------------
    // That window starts at 340px and can be dragged narrower still, where the lines above would be
    // cut off mid-number — and a truncated figure is worse than a shorter one, because it still looks
    // like a figure. These carry the same inputs in a form that fits, and the full set is one tooltip
    // away in the Bazaar Flips screen.

    /** "buy 405.8 -> sell 696.1 · 290.3/u net" */
    public static String compactPrices(LocalFlip flip) {
        return "buy " + coins(flip.buyOrderPrice()) + " -> sell " + coins(flip.sellOfferPrice())
                + "  ·  " + NumberDisplay.format(flip.unitMargin()) + "/u net";
    }

    /** "flow 12k/h x 15% = 1.8k/h · 2.1M capital" */
    public static String compactFlow(LocalFlip flip, SBSConfig.BazaarSettings settings) {
        return "flow " + NumberDisplay.shorten(flip.flowPerHour()) + "/h x "
                + settings.localFlipSharePct + "% = " + NumberDisplay.shorten(flip.unitsPerHour())
                + "/h  ·  " + coins(flip.capital()) + " capital";
    }

    /** "spr 21.6% · 57/45 orders · top 22%/34% · book" */
    public static String compactMarket(LocalFlip flip) {
        return String.format(Locale.ROOT, "spr %.1f%%  ·  %d/%d orders  ·  top %.0f%%/%.0f%%  ·  %s",
                flip.spreadPct(), flip.buyOrderCount(), flip.sellOrderCount(),
                flip.buyConcentration() * 100, flip.sellConcentration() * 100,
                flip.confidence().badge());
    }

    /**
     * Greedy word wrap to a pixel width, so the disclaimer says all of itself at any window size
     * instead of being cut off by {@code plainSubstrByWidth}. {@code colour} is re-applied to every
     * line, since a legacy code does not survive the break.
     *
     * <p>Kept as the flip screens' entry point, but the wrapping itself is
     * {@link sbs.modid.client.ui.render.RowText#wrap} - the same prose problem turned up in the
     * in-development notice, and two greedy wrappers that disagree about a trailing space is the
     * kind of difference nobody finds by reading.
     */
    public static List<String> wrap(net.minecraft.client.gui.Font font, String text, int maxWidth,
                                    String colour) {
        return sbs.modid.client.ui.render.RowText.wrap(font, text, maxWidth, colour);
    }

    /** Every input behind one result, for a tooltip that has room for all of it. */
    public static List<String> inputLines(LocalFlip flip, SBSConfig.BazaarSettings settings) {
        List<String> out = new ArrayList<>();
        out.add("§7Buy order at §6" + coins(flip.buyOrderPrice())
                + " §8(one tick above the best bid)");
        out.add("§7Sell offer at §6" + coins(flip.sellOfferPrice())
                + " §8(one tick below the best ask)");
        out.add("§7Sell tax §f" + String.format(Locale.ROOT, "%.3f%%", flip.taxRate() * 100)
                + " §8(Bazaar Flipper level " + settings.bazaarFlipperLevel + ")");
        out.add("§7Margin after tax §a" + NumberDisplay.format(flip.unitMargin()) + " §7per unit");
        out.add("");
        out.add("§7Bought per week §f" + NumberDisplay.format(flip.demandWeek())
                + " §8(fills your sell offer)");
        out.add("§7Sold per week §f" + NumberDisplay.format(flip.supplyWeek())
                + " §8(fills your buy order)");
        out.add("§7Two-sided flow §f" + NumberDisplay.shorten(flip.flowPerHour())
                + "/h §8(the smaller side, ÷168)");
        out.add("§7Assumed share §f" + settings.localFlipSharePct + "% §8(you are not the only flipper)");
        out.add("§7Order size §f" + NumberDisplay.format(flip.unitsPerOrder())
                + " §8(cap " + NumberDisplay.shorten(
                        sbs.modid.client.economy.bazaar.logic.LocalFlipEngine.ORDER_SIZE_CAP) + ")");
        out.add("§7Capital tied up §6" + coins(flip.capital()));
        out.add("");
        out.add(String.format(Locale.ROOT, "§7Book: %d buy / %d sell orders, top level holds "
                        + "%.0f%% / %.0f%%", flip.buyOrderCount(), flip.sellOrderCount(),
                flip.buyConcentration() * 100, flip.sellConcentration() * 100));
        out.add("§8" + flip.confidence().explanation());
        return out;
    }

    /** "§8data 40s old" — shown on every result, because a projection from stale data is stale. */
    public static String dataAge(long dataTsMs) {
        if (dataTsMs <= 0) {
            return "data age unknown";
        }
        return "data " + duration(Math.max(0, System.currentTimeMillis() - dataTsMs) / 1000L) + " old";
    }

    /** Compact coin figure honouring the mod-wide "Shorten Numbers" setting. */
    public static String coins(double value) {
        return NumberDisplay.format(value);
    }

    /** "45s" / "12m" / "3h 05m" / "2d 4h". */
    public static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        if (seconds < 86400) {
            return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
        }
        return String.format(Locale.ROOT, "%dd %dh", seconds / 86400, (seconds % 86400) / 3600);
    }
}
