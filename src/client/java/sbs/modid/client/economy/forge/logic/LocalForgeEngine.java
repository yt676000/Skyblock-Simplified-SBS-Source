/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.forge.logic;

import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.bazaar.logic.BazaarApiClient;
import sbs.modid.client.economy.bazaar.logic.LocalFlipEngine;
import sbs.modid.client.economy.bazaar.model.BazaarOrder;
import sbs.modid.client.economy.forge.model.LocalForgeFlip;
import sbs.modid.client.economy.recipe.model.ForgeRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The client-side forge-flip ranking: what the backend's {@code /api/forge} does, computed from the
 * one Bazaar snapshot the mod already holds, with no network of its own and no price history.
 *
 * <p><b>What it is for.</b> Without a licence — or without a route to the server — the Forge Flips
 * window used to be an empty box with an error in it. This fills it. It is deliberately <i>not</i>
 * hobbled: a fallback that visibly does its best is a better argument for the paid version than one
 * that is obviously crippled. What it cannot do it cannot do for a structural reason, stated plainly
 * in the UI rather than engineered in.
 *
 * <p><b>The calculation, ported from {@code compute_forge_flips}.</b> Buy the ingredients with buy
 * orders, forge, sell the result with a sell offer, take the bazaar tax off the sale, divide by the
 * forge time:
 *
 * <pre>{@code
 *   cost    = Σ ingredient.count × ingredientPrice
 *   revenue = outputCount × outputPrice × (1 − tax)
 *   perHour = (revenue − cost) ÷ (durationSeconds ÷ 3600)
 * }</pre>
 *
 * <p><b>Where it differs from the backend, and why.</b>
 * <ul>
 *   <li><b>Prices come off the order book, not the weighted averages.</b> The server's collector
 *       stores only {@code quick_status}, so it has nothing else; the client fetches Hypixel directly
 *       and has all 30 levels per side. So a buy order is priced one tick above the best bid and a
 *       sell offer one tick below the best ask — what it actually costs to be first in the queue,
 *       which is a different number from an average across a slice of the book. This makes the
 *       "weaker" ranking better than the server's on exactly this point.</li>
 *   <li><b>The rating is the <i>now</i> figure alone.</b> The server weights 50 % now / 25 % day
 *       average / 25 % week average; the client has no history, and {@code _forge_rating} already
 *       normalises over the states that are present rather than counting a missing one as zero — so
 *       one state present means the rating <i>is</i> that state. No fudge factor was invented to
 *       stand in for the two missing ones.</li>
 *   <li><b>Volume is read off the side that decides the trade.</b> The backend reports a forge
 *       output's weekly volume from {@code sell_week} (units sold <i>into</i> buy orders). A forge
 *       output is sold through a sell offer, so what decides whether it moves is how many are bought
 *       <i>out of</i> sell offers. Both sides are used here: the result's demand, and the tightest
 *       ingredient's supply.</li>
 * </ul>
 *
 * <p><b>What it structurally cannot know</b>, all of it downstream of having no history: whether a
 * price is unusual <i>for this item</i> rather than merely what it is right now, whether an
 * ingredient is trending up while the run is in the forge, and whether the book is a manipulation
 * spike that will have closed by the time an 8-hour recipe finishes. The last one matters more here
 * than it does for bazaar flips, because a forge run commits you for the whole duration.
 *
 * <p>It also does not model the time to <i>acquire</i> the ingredients — neither does the backend. A
 * recipe whose inputs take a day to buy at the bid price ranks as though they were already in hand.
 * The tightest supply is reported on every row so that is visible rather than hidden.
 *
 * <p>Pure and stateless: one static entry point over an immutable snapshot, safe to call from any
 * background thread. Never call it from the client thread — it walks every recipe's whole ingredient
 * list against the book.
 */
public final class LocalForgeEngine {

    /** Bazaar prices live on a 0.1-coin grid, so undercutting by one tick is undercutting by 0.1. */
    private static final double TICK = 0.1;

    private static final double SECONDS_PER_HOUR = 3600.0;
    private static final double HOURS_PER_WEEK = 168.0;

    /** Keeps one pass bounded. There are only ~120 forge recipes, so this is a guard, not a cut. */
    private static final int MAX_KEPT = 100;
    private static final int MAX_FILTERED = 100;

    private LocalForgeEngine() {
    }

    /**
     * The outcome of one pass.
     *
     * @param kept     entries that passed every filter, best profit-per-forge-hour first
     * @param filtered entries excluded by a filter, best first — shown behind the UI's toggle
     * @param dataTsMs Hypixel's own {@code lastUpdated} for the snapshot, epoch millis
     * @param recipes  how many forge recipes were known at all
     * @param priced   how many of them could be priced end to end on this snapshot
     */
    public record Result(List<LocalForgeFlip> kept, List<LocalForgeFlip> filtered, long dataTsMs,
                         int recipes, int priced) {

        /** True when the recipe archive has not been downloaded yet — a different state from "none". */
        public boolean noRecipes() {
            return recipes == 0;
        }
    }

    /**
     * One product's usable prices, resolved once.
     *
     * @param bid     highest standing buy order — what you undercut to sell instantly
     * @param ask     lowest standing sell offer — what you take to buy instantly
     * @param demand  units bought OUT of sell offers per week (fills a sell offer)
     * @param supply  units sold INTO buy orders per week (fills a buy order)
     * @param book    true when both sides came off the order book rather than the summary averages
     */
    private record Quote(double bid, double ask, long demand, long supply, boolean book) {

        /**
         * What one unit costs to acquire with a buy order: one tick above the best bid, and never
         * above the ask — bidding over the cheapest sell offer is paying more than simply taking it.
         *
         * <p>The tick is only applied when there <i>is</i> a book. On the summary path the number is
         * a weighted average across a slice of the book, and undercutting an average by 0.1 is false
         * precision dressed up as care. {@link LocalFlipEngine} draws the same line in the same
         * place; the two engines have to agree or the same item prices differently in two windows.
         */
        double acquirePrice() {
            return book ? Math.min(BazaarOrder.snapToTick(bid + TICK), ask)
                    : BazaarOrder.snapToTick(bid);
        }

        /** What one unit fetches as a sell offer: one tick below the best ask, never below the bid. */
        double offerPrice() {
            return book ? Math.max(BazaarOrder.snapToTick(ask - TICK), bid)
                    : BazaarOrder.snapToTick(ask);
        }
    }

    /**
     * Ranks every known forge recipe against one snapshot.
     *
     * @param response the shared Bazaar snapshot; never fetched here, only read
     * @param recipes  output id → recipe, from the repo cache
     * @param forge    the live forge config (budget, thresholds)
     * @param bazaar   the bazaar config, for the Bazaar Flipper level that sets the sell tax
     */
    public static Result compute(BazaarApiClient.Response response, Map<String, ForgeRecipe> recipes,
                                 SBSConfig.ForgeSettings forge, SBSConfig.BazaarSettings bazaar) {
        int known = recipes == null ? 0 : recipes.size();
        if (response == null || response.products == null || known == 0) {
            return new Result(List.of(), List.of(), response == null ? 0L : response.lastUpdated,
                    known, 0);
        }
        // The sell tax is a property of the player's account, not of this feature: the same Bazaar
        // Flipper upgrade sets it for bazaar flips, so it is read from the same place rather than
        // duplicated into a forge setting that could disagree with it.
        double tax = LocalFlipEngine.taxRate(bazaar.bazaarFlipperLevel);
        long budget = Math.max(0, forge.budget);
        int runsFloor = Math.max(0, forge.localMinWeeklyRuns);
        long minProfit = Math.max(0, forge.localMinProfit);

        List<LocalForgeFlip> kept = new ArrayList<>();
        List<LocalForgeFlip> filtered = new ArrayList<>();
        int priced = 0;

        for (ForgeRecipe recipe : recipes.values()) {
            if (recipe == null || !recipe.usable()) {
                continue;
            }
            LocalForgeFlip flip = evaluate(recipe, response.products, tax, minProfit, runsFloor);
            if (flip == null) {
                continue;
            }
            priced++;
            // The budget is a hard cut, exactly as ?budget= is server-side: an entry you cannot
            // afford is not a judgement call, so it does not belong behind the filtered toggle.
            if (budget > 0 && flip.cost() > budget) {
                continue;
            }
            (flip.kept() ? kept : filtered).add(flip);
        }

        Comparator<LocalForgeFlip> byRate =
                Comparator.comparingDouble(LocalForgeFlip::profitPerHour).reversed();
        kept.sort(byRate);
        filtered.sort(byRate);
        return new Result(
                List.copyOf(kept.subList(0, Math.min(MAX_KEPT, kept.size()))),
                List.copyOf(filtered.subList(0, Math.min(MAX_FILTERED, filtered.size()))),
                response.lastUpdated, known, priced);
    }

    /**
     * One recipe, or {@code null} when it is not an opportunity at all: an ingredient or the result
     * has no bazaar price, or the run does not clear its own cost once the tax is taken.
     *
     * <p>Those are not "filtered" — they are simply not flips, and putting a hundred unprofitable
     * recipes behind the filtered toggle would bury the handful that are genuine judgement calls.
     */
    private static LocalForgeFlip evaluate(ForgeRecipe recipe,
                                           Map<String, BazaarApiClient.Product> products,
                                           double tax, long minProfit, int runsFloor) {
        Quote output = quote(products.get(recipe.outputId));
        if (output == null) {
            return null; // the result is not a bazaar good — nothing to sell it into
        }

        boolean book = output.book();
        double cost = 0;
        double instantCost = 0;
        double supplyRuns = Double.MAX_VALUE;
        String tightest = null;
        List<LocalForgeFlip.Ingredient> lines = new ArrayList<>(recipe.inputs.size());

        for (ForgeRecipe.Ingredient input : recipe.inputs) {
            Quote quote = quote(products.get(input.itemId));
            if (quote == null) {
                // An unpriceable ingredient makes the whole recipe unpriceable. Costing it at zero
                // would rank it first, which is the worst possible way to be wrong here.
                return null;
            }
            book &= quote.book();
            double unit = quote.acquirePrice();
            cost += unit * input.count;
            instantCost += quote.ask() * input.count;
            lines.add(new LocalForgeFlip.Ingredient(input.itemId, input.count, unit,
                    unit * input.count));
            double runs = quote.supply() / input.count;
            if (runs < supplyRuns) {
                supplyRuns = runs;
                tightest = input.itemId;
            }
        }
        if (cost <= 0 || recipe.durationSeconds <= 0) {
            return null;
        }

        double revenue = output.offerPrice() * recipe.outputCount * (1.0 - tax);
        double profit = revenue - cost;
        if (profit <= 0) {
            return null; // the run does not pay for its own ingredients — not a flip, at any speed
        }
        double instantRevenue = output.bid() * recipe.outputCount * (1.0 - tax);

        double hours = recipe.durationSeconds / SECONDS_PER_HOUR;
        double perHour = profit / hours;
        double instantPerHour = (instantRevenue - instantCost) / hours;
        double demandRuns = output.demand() / Math.max(1.0, recipe.outputCount);
        if (supplyRuns == Double.MAX_VALUE) {
            supplyRuns = 0;
        }

        LocalForgeFlip.Filter filter = null;
        if (demandRuns < runsFloor) {
            filter = LocalForgeFlip.Filter.DEMAND;
        } else if (supplyRuns < runsFloor) {
            filter = LocalForgeFlip.Filter.SUPPLY;
        } else if (profit < minProfit) {
            filter = LocalForgeFlip.Filter.PROFIT;
        }

        return new LocalForgeFlip(recipe.outputId, recipe.label(), recipe.outputCount,
                recipe.durationSeconds, recipe.requirement == null ? "" : recipe.requirement,
                List.copyOf(lines), cost, revenue, profit, perHour, profit / cost * 100.0, tax,
                instantCost, instantRevenue, instantPerHour, demandRuns, supplyRuns, tightest,
                book ? LocalForgeFlip.Confidence.BOOK : LocalForgeFlip.Confidence.SUMMARY, filter);
    }

    /**
     * One product's prices, or {@code null} when it has none usable.
     *
     * <p>Hypixel's summaries are named for the action <b>you</b> take, so they read inside-out: the
     * bids you sell into live in {@code sell_summary}, the asks you buy from live in {@code
     * buy_summary}. Getting this backwards silently inverts every price in the ranking, so the
     * mapping is spelled out at the one place it happens.
     */
    private static Quote quote(BazaarApiClient.Product product) {
        if (product == null) {
            return null;
        }
        BazaarApiClient.Summary topBid = best(product.sell_summary, true);
        BazaarApiClient.Summary topAsk = best(product.buy_summary, false);
        BazaarApiClient.QuickStatus quick = product.quick_status;
        long demand = quick == null ? 0 : Math.max(0, quick.buyMovingWeek);
        long supply = quick == null ? 0 : Math.max(0, quick.sellMovingWeek);

        if (topBid != null && topAsk != null) {
            return new Quote(topBid.pricePerUnit, topAsk.pricePerUnit, demand, supply, true);
        }
        // No book on one side this snapshot. The weighted averages answer a different question and the
        // entry is marked down for it, but dropping the recipe outright would hide items that are only
        // briefly bookless — and one bookless ingredient would take a whole recipe with it.
        if (quick == null || quick.buyPrice <= 0 || quick.sellPrice <= 0) {
            return null;
        }
        return new Quote(quick.sellPrice, quick.buyPrice, demand, supply, false);
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

    /** Weekly unit volume expressed per hour, for anything that wants a rate rather than a total. */
    public static double perHour(long weekly) {
        return weekly / HOURS_PER_WEEK;
    }
}
