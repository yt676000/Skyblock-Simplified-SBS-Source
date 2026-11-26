/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.recipe.logic;

import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.recipe.model.ItemRef;
import sbs.modid.client.economy.recipe.model.SbsRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The price table under a recipe in the Recipe Viewer: every ingredient and the result, each at the
 * Bazaar insta-buy price and at the buy-order price, plus the total for one craft.
 *
 * <p>The buy-order figure is the product's instant-sell price - the top buy order on the book, which
 * is what a new order has to beat. An item that is not on the Bazaar has one price (lowest BIN, via
 * {@link PriceEstimator#buyPrice}) and shows it in both columns, flagged {@link Line#bazaar} false.
 */
public final class RecipePricing {

    /** One unit price pair; either side {@code null} when unknown. */
    public record Unit(Long insta, Long order, boolean bazaar) {
        static final Unit NONE = new Unit(null, null, false);
    }

    /** One row: an item, how many, and both totals for that many ({@code null} when unpriced). */
    public record Line(ItemRef item, int count, Long insta, Long order, boolean bazaar) {
    }

    /**
     * The whole table.
     *
     * @param complete false when at least one ingredient has no price, so the totals are a floor
     */
    public record Table(Line result, List<Line> ingredients, long totalInsta, long totalOrder,
                        boolean complete) {
    }

    private RecipePricing() {
    }

    /** The table for a recipe against the live caches. */
    public static Table of(SbsRecipe recipe) {
        return of(recipe, RecipePricing::livePrice);
    }

    /** The table for a recipe against any price source (tests pass a map). */
    public static Table of(SbsRecipe recipe, Function<String, Unit> prices) {
        List<Line> lines = new ArrayList<>();
        long insta = 0;
        long order = 0;
        boolean complete = true;
        for (ItemRef ing : recipe.ingredients) {
            Line line = line(ing, prices);
            lines.add(line);
            if (line.insta() == null || line.order() == null) {
                complete = false;
            } else {
                insta += line.insta();
                order += line.order();
            }
        }
        Line result = recipe.result == null ? null : line(recipe.result, prices);
        return new Table(result, List.copyOf(lines), insta, order, complete);
    }

    private static Line line(ItemRef item, Function<String, Unit> prices) {
        int count = Math.max(1, item.count);
        Unit unit = prices.apply(item.lookupId());
        if (unit == null) {
            unit = Unit.NONE;
        }
        return new Line(item, count, times(unit.insta(), count), times(unit.order(), count), unit.bazaar());
    }

    private static Long times(Long unit, int count) {
        return unit == null ? null : unit * count;
    }

    /** Bazaar first (insta-buy / top buy order), else the single lowest-BIN-style price. */
    static Unit livePrice(String lookupId) {
        if (lookupId == null || lookupId.isEmpty()) {
            return Unit.NONE;
        }
        String id = lookupId.toUpperCase(Locale.ROOT);
        BazaarPriceCache.BzPrice bz = BazaarPriceCache.getInstance().get(id);
        if (bz != null && (bz.buy() > 0 || bz.sell() > 0)) {
            return new Unit(bz.buy() > 0 ? bz.buy() : null, bz.sell() > 0 ? bz.sell() : null, true);
        }
        Long single = PriceEstimator.getInstance().buyPrice(id);
        return single == null ? Unit.NONE : new Unit(single, single, false);
    }
}
