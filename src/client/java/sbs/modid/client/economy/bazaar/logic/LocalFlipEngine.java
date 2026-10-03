/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.logic;

import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.bazaar.model.BazaarOrder;
import sbs.modid.client.economy.bazaar.model.LocalFlip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The client-side bazaar flip ranking, computed from the one Bazaar snapshot the mod already holds,
 * with no network of its own and no price history.
 *
 * <p><b>What it is for.</b> Without a licence the Best Flips window used to be an empty box. This
 * fills it, from the order-book top, both sides' volumes, the real sell tax and the real order caps.
 * Every figure here comes out of a single snapshot with nothing behind it, so the whole pass is tuned
 * to under-promise: a listed flip that does not materialise costs the player coins and their trust in
 * the number, while one it declined to list costs them an entry they will never know about. The
 * thresholds sit where that trade puts them, not where the most entries survive.
 *
 * <p><b>The estimate.</b> {@code profit/hour ≈ realistic fill rate × per-unit margin}, where:
 * <ul>
 *   <li><b>margin</b> is the gap between what it costs to sit at the top of the buy book and what it
 *       costs to be the cheapest sell offer, <i>minus the sell tax</i>. Leaving the tax out overstates
 *       every result, and it overstates it most on exactly the high-volume low-margin items that are
 *       otherwise the best flips — a 1.25% cut of a 2% spread is most of the profit.</li>
 *   <li><b>fill rate</b> is bounded by <b>both</b> sides: {@code min(demand, supply)}. An item people
 *       dump constantly but nobody buys does not flip, and neither does the reverse; using either
 *       figure alone reports both as excellent.</li>
 *   <li>that flow is then multiplied by a <b>share factor</b>, because the player does not capture the
 *       whole market, and capped by the in-game order size limit.</li>
 * </ul>
 *
 * <p><b>What it structurally cannot know</b> (all of it downstream of having no history): whether a
 * spread is unusual <i>for this item</i>, whether volume is rising or collapsing rather than merely
 * being what it is right now, and whether the current book is a manipulation spike that will close
 * before an order fills. The filters in {@link #filterOf} are the best proxy available from a single
 * snapshot, and they are proxies.
 *
 * <p>Pure and stateless: one static entry point over an immutable snapshot, safe to call from any
 * background thread. Never call it from the client thread — it walks every product's whole book.
 */
public final class LocalFlipEngine {

    /**
     * The largest single bazaar order, in units, for a stackable item (1,120 stacks). Unstackables
     * cap at 256, but those are not bazaar goods, so one constant covers every product here.
     *
     * <p>Wiki-sourced (Hypixel SkyBlock Wiki, "Bazaar"); unlike the tax and the slot count it does not
     * vary per player, so there is nothing for the player to correct and no setting for it.
     */
    public static final long ORDER_SIZE_CAP = 71_680L;

    /** The volume figures are 7-day totals; this is what turns them into a rate. */
    private static final double HOURS_PER_WEEK = 168.0;

    /** A flip needs two of the player's order slots at once: the buy order and the sell offer. */
    private static final int SLOTS_PER_FLIP = 2;

    /** Bazaar prices live on a 0.1-coin grid, so undercutting by one tick is undercutting by 0.1. */
    private static final double TICK = 0.1;

    /**
     * Net margin, after tax, below which a product is not reported at all — as a percentage of what
     * the buy leg costs.
     *
     * <p>A margin of a few tenths of a percent is inside one snapshot's own noise: the book moves by
     * more than that between Hypixel generating the data and the player reading the row, and the first
     * undercut takes whatever is left. With no history there is nothing to separate one of those from
     * a real edge, so none of them are offered as one.
     */
    private static final double MIN_NET_MARGIN_PCT = 0.6;

    /**
     * Keeps one pass bounded regardless of how many products clear the filters.
     *
     * <p>The kept list is the shorter of the two deliberately: order slots cap a player at
     * {@link #concurrentFlips} flips at once — fourteen even fully upgraded — so entries ranked far
     * below that line are ordering for its own sake, and a long list reads as more opportunity than
     * the player can actually hold.
     */
    private static final int MAX_KEPT = 25;
    private static final int MAX_FILTERED = 100;

    private LocalFlipEngine() {
    }

    /**
     * The outcome of one pass.
     *
     * @param kept       entries that passed every filter, best profit-per-hour first
     * @param filtered   entries excluded by a filter, best first — shown behind the UI's toggle so a
     *                   user who reads the market better than this heuristic can see what it dropped
     * @param dataTsMs   Hypixel's own {@code lastUpdated} for the snapshot, epoch millis
     * @param scanned    how many products were examined
     * @param concurrent how many of these flips the player's order slots allow at the same time
     */
    public record Result(List<LocalFlip> kept, List<LocalFlip> filtered, long dataTsMs,
                         int scanned, int concurrent) {
    }

    // ------------------------------------------------------------------
    // Per-player limits. Both vary with the Bazaar Flipper account upgrade.
    // ------------------------------------------------------------------

    /**
     * The bazaar sell tax as a fraction, for a Bazaar Flipper level.
     *
     * <p>Base 1.25% on all sales — instant sells and filled sell offers alike; the buy side is not
     * taxed. Bazaar Flipper I takes it to 1.125%, level II to 1.00%. Level 0 is the default because it
     * is the <b>conservative</b> end: assuming the highest tax under-promises, and under-promising is
     * much cheaper here than the reverse.
     */
    public static double taxRate(int flipperLevel) {
        return switch (clampLevel(flipperLevel)) {
            case 1 -> 0.01125;
            case 2 -> 0.01000;
            default -> 0.01250;
        };
    }

    /**
     * How many bazaar orders and offers may be open at once: 14 by default, +7 per Bazaar Flipper
     * level to 28. This is what makes a long ranking a lie — a player cannot run twenty flips, so the
     * UI says how many of them fit.
     */
    public static int orderSlots(int flipperLevel) {
        return 14 + 7 * clampLevel(flipperLevel);
    }

    /** How many simultaneous flips those slots buy: each flip holds a buy order and a sell offer. */
    public static int concurrentFlips(int flipperLevel) {
        return orderSlots(flipperLevel) / SLOTS_PER_FLIP;
    }

    private static int clampLevel(int level) {
        return Math.max(0, Math.min(2, level));
    }

    // ------------------------------------------------------------------
    // The pass
    // ------------------------------------------------------------------

    /**
     * Ranks every product in one snapshot.
     *
     * @param response the shared Bazaar snapshot; never fetched here, only read
     * @param settings the live bazaar config (thresholds, share factor, budget, flipper level)
     */
    public static Result compute(BazaarApiClient.Response response, SBSConfig.BazaarSettings settings) {
        if (response == null || response.products == null) {
            return new Result(List.of(), List.of(), 0L, 0, concurrentFlips(settings.bazaarFlipperLevel));
        }
        double tax = taxRate(settings.bazaarFlipperLevel);
        double share = Math.max(1, Math.min(100, settings.localFlipSharePct)) / 100.0;
        long budget = Math.max(0, settings.flipsBudget);

        List<LocalFlip> kept = new ArrayList<>();
        List<LocalFlip> filtered = new ArrayList<>();
        int scanned = 0;

        for (Map.Entry<String, BazaarApiClient.Product> entry : response.products.entrySet()) {
            BazaarApiClient.Product product = entry.getValue();
            if (product == null) {
                continue;
            }
            scanned++;
            LocalFlip flip = evaluate(entry.getKey(), product, tax, share, budget, settings);
            if (flip == null) {
                continue;
            }
            (flip.kept() ? kept : filtered).add(flip);
        }

        Comparator<LocalFlip> byProfit = Comparator.comparingDouble(LocalFlip::profitPerHour).reversed();
        kept.sort(byProfit);
        filtered.sort(byProfit);
        return new Result(
                List.copyOf(kept.subList(0, Math.min(MAX_KEPT, kept.size()))),
                List.copyOf(filtered.subList(0, Math.min(MAX_FILTERED, filtered.size()))),
                response.lastUpdated,
                scanned,
                concurrentFlips(settings.bazaarFlipperLevel));
    }

    /**
     * One product, or {@code null} when it is not an opportunity at all (no usable book, or the
     * margin is zero/negative once the tax is taken). Those are not "filtered" — they are simply not
     * flips, and putting them behind the filtered toggle would bury the entries that <i>are</i>
     * judgement calls under thousands that are not.
     */
    private static LocalFlip evaluate(String itemId, BazaarApiClient.Product product, double tax,
                                      double share, long budget, SBSConfig.BazaarSettings settings) {
        // Hypixel's summaries are named for the action YOU take, so they read inside-out: the bids you
        // sell into live in sell_summary, the asks you buy from live in buy_summary. Getting this
        // backwards silently inverts every spread in the ranking, so it is spelled out at the one
        // place the mapping happens rather than left to the field names.
        List<BazaarApiClient.Summary> bids = product.sell_summary;   // buy orders — you outbid these
        List<BazaarApiClient.Summary> asks = product.buy_summary;    // sell offers — you undercut these

        BazaarApiClient.Summary topBid = best(bids, true);
        BazaarApiClient.Summary topAsk = best(asks, false);

        LocalFlip.Confidence confidence = LocalFlip.Confidence.BOOK;
        double bidPrice;
        double askPrice;
        if (topBid != null && topAsk != null) {
            // The price that matters is what it takes to be FIRST in the queue, not the book's average:
            // one tick above the best bid, one tick below the best ask.
            bidPrice = BazaarOrder.snapToTick(topBid.pricePerUnit + TICK);
            askPrice = BazaarOrder.snapToTick(topAsk.pricePerUnit - TICK);
        } else {
            // No book on one side this snapshot. The weighted averages are the wrong number for a queue
            // price and the entry is marked down for it, but dropping the product outright would hide
            // items that are only briefly bookless.
            BazaarApiClient.QuickStatus quick = product.quick_status;
            if (quick == null || quick.buyPrice <= 0 || quick.sellPrice <= 0) {
                return null;
            }
            confidence = LocalFlip.Confidence.SUMMARY;
            bidPrice = BazaarOrder.snapToTick(quick.sellPrice);
            askPrice = BazaarOrder.snapToTick(quick.buyPrice);
        }
        if (bidPrice <= 0 || askPrice <= bidPrice) {
            return null;
        }

        // The sell side is taxed, the buy side is not. This subtraction is the whole reason an untaxed
        // ranking and this one disagree most sharply on the items that look best.
        double unitMargin = askPrice * (1.0 - tax) - bidPrice;
        if (unitMargin <= 0) {
            return null; // the spread does not survive the tax — not a flip, at any volume
        }
        if (unitMargin / bidPrice * 100.0 < MIN_NET_MARGIN_PCT) {
            return null; // inside the snapshot's own noise; nothing here can tell it from an edge
        }
        double spreadPct = (askPrice - bidPrice) / bidPrice * 100.0;

        BazaarApiClient.QuickStatus quick = product.quick_status;
        // demand = units bought OUT of sell offers (fills your sell offer);
        // supply = units sold INTO buy orders (fills your buy order).
        long demandWeek = quick == null ? 0 : Math.max(0, quick.buyMovingWeek);
        long supplyWeek = quick == null ? 0 : Math.max(0, quick.sellMovingWeek);
        int buyOrderCount = quick == null ? 0 : Math.max(0, quick.buyOrders);
        int sellOrderCount = quick == null ? 0 : Math.max(0, quick.sellOrders);

        // Bounded by BOTH sides. Either one alone rates a one-sided market as a fine opportunity.
        double flowPerHour = Math.min(demandWeek, supplyWeek) / HOURS_PER_WEEK;

        double capturable = flowPerHour * share;
        // One order can hold at most the in-game cap, so an hour cannot move more than that through a
        // single order slot however busy the item is. A figure the player cannot physically reach is
        // not a conservative estimate, it is a wrong one.
        long unitsPerOrder = (long) Math.min(ORDER_SIZE_CAP, Math.max(0, Math.floor(capturable)));
        if (budget > 0) {
            unitsPerOrder = Math.min(unitsPerOrder, (long) Math.floor(budget / bidPrice));
        }
        if (unitsPerOrder <= 0) {
            return null; // cannot place a single unit within the budget, or nothing flows at all
        }
        double unitsPerHour = Math.min(capturable, ORDER_SIZE_CAP);
        if (budget > 0) {
            unitsPerHour = Math.min(unitsPerHour, unitsPerOrder);
        }
        double profitPerHour = unitsPerHour * unitMargin;
        double capital = unitsPerOrder * bidPrice;

        double bidConcentration = concentration(bids);
        double askConcentration = concentration(asks);

        LocalFlip.Filter filter = filterOf(settings, spreadPct, demandWeek, supplyWeek,
                buyOrderCount, sellOrderCount, bidConcentration, askConcentration);

        return new LocalFlip(itemId, bidPrice, askPrice, tax, unitMargin, spreadPct,
                demandWeek, supplyWeek, flowPerHour, unitsPerHour, profitPerHour,
                unitsPerOrder, capital, buyOrderCount, sellOrderCount,
                bidConcentration, askConcentration, confidence, filter);
    }

    /**
     * Which anomaly signal, if any, this entry trips.
     *
     * <p>Deliberately several signals rather than one rule. A spread cap on its own is only a symptom
     * check — it drops the occasional genuine wide-spread opportunity while happily keeping a
     * manipulated item parked just under the line. The volume floors catch the items too thin to fill
     * either side, the order counts catch a book only one person is in, and the concentration check
     * catches a single wall standing in for a market — the shape manipulation actually has. First trip
     * wins, so the reported reason is the strongest objection.
     *
     * <p>Every floor here is a config value with a pessimistic default. They are set for the player who
     * takes a listed row at face value, not for the one who reads the book themselves: that player can
     * loosen any of them, and the filtered list behind the UI toggle shows what each one dropped.
     */
    private static LocalFlip.Filter filterOf(SBSConfig.BazaarSettings settings, double spreadPct,
                                             long demandWeek, long supplyWeek, int buyOrderCount,
                                             int sellOrderCount, double bidConcentration,
                                             double askConcentration) {
        if (spreadPct > settings.localFlipMaxSpreadPct) {
            return LocalFlip.Filter.SPREAD;
        }
        if (demandWeek < settings.localFlipMinWeeklyVolume) {
            return LocalFlip.Filter.DEMAND;
        }
        if (supplyWeek < settings.localFlipMinWeeklyVolume) {
            return LocalFlip.Filter.SUPPLY;
        }
        if (buyOrderCount < settings.localFlipMinOrders) {
            return LocalFlip.Filter.BUY_ORDERS;
        }
        if (sellOrderCount < settings.localFlipMinOrders) {
            return LocalFlip.Filter.SELL_ORDERS;
        }
        double limit = settings.localFlipMaxConcentrationPct / 100.0;
        if (bidConcentration > limit || askConcentration > limit) {
            return LocalFlip.Filter.CONCENTRATION;
        }
        return null;
    }

    /**
     * What share of a side's <i>visible</i> units sit at its single best price level.
     *
     * <p>A healthy book spreads its depth over many levels; one level holding most of it is a wall,
     * and a wall is what a manipulated book looks like from a snapshot. Hypixel returns at most 30
     * levels, so this is a ratio within the top of the book, not the whole of it — which is the right
     * scope anyway, since the deep tail is not what an order competes against.
     *
     * <p>{@code 0} when the book is empty or carries no amounts, so an absent book never reads as
     * infinitely concentrated and trips the filter on missing data alone.
     */
    private static double concentration(List<BazaarApiClient.Summary> book) {
        if (book == null || book.isEmpty()) {
            return 0;
        }
        long total = 0;
        long top = 0;
        for (BazaarApiClient.Summary level : book) {
            if (level == null || level.amount <= 0) {
                continue;
            }
            total += level.amount;
            top = Math.max(top, level.amount);
        }
        return total <= 0 ? 0 : (double) top / total;
    }

    /**
     * The best level of a side: the highest bid, or the lowest ask. Scans rather than trusting index
     * 0, so it stays correct whichever way the API happens to order the list.
     */
    private static BazaarApiClient.Summary best(List<BazaarApiClient.Summary> book, boolean highest) {
        if (book == null) {
            return null;
        }
        BazaarApiClient.Summary best = null;
        for (BazaarApiClient.Summary level : book) {
            if (level == null || level.pricePerUnit <= 0) {
                continue;
            }
            if (best == null || (highest ? level.pricePerUnit > best.pricePerUnit
                    : level.pricePerUnit < best.pricePerUnit)) {
                best = level;
            }
        }
        return best;
    }
}
