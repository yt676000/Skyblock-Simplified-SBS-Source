/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.bazaar.logic.LocalFlipEngine;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.skills.hunting.model.ShardId;
import sbs.modid.client.skills.hunting.model.HuntingBoxShard;
import sbs.modid.client.skills.hunting.model.ShardRarity;

import java.util.ArrayList;
import java.util.List;

/**
 * What the shards in the Hunting Box are worth - as <b>three separate answers</b>, because a shard
 * has three uses whose values diverge sharply and a single summed number is the least useful of them.
 *
 * <ul>
 *   <li><b>Raw sell</b> - the Bazaar instant-sell price minus the sell tax. Coins today.</li>
 *   <li><b>Syphon</b> - shards spent levelling an attribute pay no coins at all, and this is often
 *       the highest-value use. It is reported as <i>progress</i>, never converted to coins.</li>
 *   <li><b>Fusion</b> - not computed here. The recipe graph is not in any keyless Hypixel source
 *       (checked 2026-08-10: none of the 320 {@code SHARD_*} Bazaar products appear in the items
 *       resource at all), so a fusion figure would be invented. See the feature spec.</li>
 * </ul>
 *
 * <p><b>The safety property this class exists for.</b> A player who sells shards they needed for an
 * attribute because a coin total told them to has been harmed by the feature. So the sellable figure
 * is built on {@link #surplus}, which is a <i>lower bound</i>: it is what is left after setting aside
 * a full zero-to-max attribute's worth, whatever the player's current attribute level actually is.
 * Since attribute levels are not readable today, the alternative would be assuming level zero and
 * calling the rest sellable - which is the same claim with the harm pointed the other way.
 */
public final class ShardValuation {

    /** Below this bid/ask ratio the instant-sell price is a fair description of the shard's worth. */
    private static final double SPREAD_WARN_RATIO = 3.0;

    /** A stack worth more than this share of a week's traded volume will not sell at the shown price. */
    private static final double VOLUME_WARN_SHARE = 0.10;

    private ShardValuation() {
    }

    /**
     * One shard's three answers.
     *
     * @param shard        the box row this was computed from
     * @param unitSell     instant-sell price per shard before tax, 0 when the Bazaar does not know it
     * @param taxedUnit    the same after the sell tax
     * @param surplus      shards beyond a full attribute's worth - the only ones safe to call sellable
     * @param needed       shards still to collect for one attribute, or -1 when the requirement is unknown
     * @param wideSpread   the instant-sell price is far below what the shard sells for on an offer
     * @param thinForStack the surplus stack is large against what this shard actually trades weekly
     */
    public record Valued(HuntingBoxShard shard, long unitSell, long taxedUnit, int surplus,
                         int needed, boolean wideSpread, boolean thinForStack) {

        /** Coins the surplus would fetch on an instant sell, after tax. */
        public long surplusValue() {
            return taxedUnit * Math.max(0, surplus);
        }

        /** Coins the whole stack would fetch after tax - shown, but never as the headline. */
        public long rawValue() {
            return taxedUnit * Math.max(0, shard.count());
        }

        public boolean priced() {
            return unitSell > 0;
        }
    }

    /** Everything the box adds up to, plus what could not be answered. */
    public record BoxValue(List<Valued> rows, long rawTotal, long surplusTotal, int unpriced,
                           int needingShards, long priceAgeMs) {
    }

    /**
     * Values the stored box against the live Bazaar.
     *
     * <p>The sell side is used throughout: a box is stock you would be selling, and pricing it at the
     * buy side would inflate every figure by the Bazaar spread.
     */
    public static BoxValue value(List<HuntingBoxShard> shards) {
        double tax = LocalFlipEngine.taxRate(ConfigManager.getInstance().get().bazaar.bazaarFlipperLevel);
        BazaarPriceCache prices = BazaarPriceCache.getInstance();

        List<Valued> rows = new ArrayList<>(shards.size());
        long rawTotal = 0;
        long surplusTotal = 0;
        int unpriced = 0;
        int needing = 0;

        for (HuntingBoxShard shard : shards) {
            // Through the market's own spelling. The box keys a shard ATTRIBUTE_SHARD_<NAME> while
            // the Bazaar trades it as SHARD_<NAME>, so asking with our id finds no product at all.
            String product = ShardId.bazaarId(shard.id());
            BazaarPriceCache.BzPrice price = product == null ? null : prices.priceOf(product);
            long unit = price == null ? 0 : Math.max(0, price.sell());
            long taxed = Math.round(unit * (1.0 - tax));

            int surplus = surplus(shard);
            int needed = needed(shard);
            if (needed > 0) {
                needing++;
            }

            boolean wideSpread = wideSpread(price);
            boolean thin = thinForStack(product, surplus);

            Valued valued = new Valued(shard, unit, taxed, surplus, needed, wideSpread, thin);
            rows.add(valued);
            if (unit <= 0) {
                unpriced++;   // never counted as zero: an unpriced shard is a hole in the total
            } else {
                rawTotal += valued.rawValue();
                surplusTotal += valued.surplusValue();
            }
        }
        return new BoxValue(List.copyOf(rows), rawTotal, surplusTotal, unpriced, needing,
                prices.priceAgeMs());
    }

    /**
     * Whether a shard's book is wide enough that neither quoted price describes what a trade costs.
     *
     * <p>A property of the book, not of the direction you are trading in, which is why this is shared
     * rather than written twice: the missing-shard list buys and this class sells, and both are
     * misled by the same spread. Measured 2026-08-10, {@code SHARD_VULTURE} costs 229x more to
     * instant-buy than instant-selling pays, {@code HEWVER} 222x and {@code FUNGLOOM} 185x - and a
     * list <i>sorted by price</i> is exactly where that bites, since the cheapest-looking row may be
     * the one nobody is selling.
     */
    public static boolean wideSpread(BazaarPriceCache.BzPrice price) {
        return price != null && price.sell() > 0 && price.buy() > price.sell() * SPREAD_WARN_RATIO;
    }

    /**
     * Shards beyond what one attribute could ever need - the only stack it is safe to think of as
     * sellable.
     *
     * <p>Deliberately pessimistic in the one direction that matters. The requirement is the full
     * zero-to-max count, so a player who has already levelled the attribute halfway is told to keep
     * more than they need. The opposite error - assuming the attribute is at zero and calling the
     * rest surplus - is the one that costs somebody their shards, and it is the error this feature
     * exists to avoid. Returns 0 while the requirement is unknown, which is the same choice made
     * again: unknown means keep.
     */
    public static int surplus(HuntingBoxShard shard) {
        ShardRarity rarity = shard.rarity();
        if (!rarity.known()) {
            return 0;
        }
        return Math.max(0, shard.count() - rarity.toMax());
    }

    /** Shards still to collect for one attribute, or -1 when the requirement is unknown. */
    public static int needed(HuntingBoxShard shard) {
        ShardRarity rarity = shard.rarity();
        if (!rarity.known()) {
            return -1;
        }
        return Math.max(0, rarity.toMax() - shard.count());
    }

    /**
     * Whether selling this many at once is large against what the shard actually trades.
     *
     * <p>Shards are not the thin market the request assumed - measured 2026-08-10, their median week
     * is 21,597 against 740 for the rest of the Bazaar - but 71 of the 320 do under 5,000 a week, and
     * on those a big stack still moves the price it was quoted at.
     */
    private static boolean thinForStack(String bazaarId, int amount) {
        if (amount <= 0 || bazaarId == null) {
            return false;
        }
        BazaarPriceCache.BzVolume volume = BazaarPriceCache.getInstance().volumeOf(bazaarId);
        if (volume == null || volume.sellWeek() <= 0) {
            return false;   // no volume data is not evidence of a thin market
        }
        return amount > volume.sellWeek() * VOLUME_WARN_SHARE;
    }
}
