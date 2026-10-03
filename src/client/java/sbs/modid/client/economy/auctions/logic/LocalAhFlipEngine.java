/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.auctions.logic;

import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.prices.LiveAuctionIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The client-side AH flip ranking, built from <b>live auctions only</b>.
 *
 * <p><b>What it does.</b> For each item currently on the auction house it compares the cheapest BIN
 * listing against the next-cheapest one. If the cheapest is far enough below the rest of its own
 * live market, that is a flip: you buy it, and the next seller's price is what the item costs
 * everybody else afterwards.
 *
 * <p><b>What that is not.</b> It is not a valuation. The second-cheapest listing is an asking price,
 * not evidence of anything — a market where the two cheapest are both mispriced looks identical from
 * here, and so does an item that simply has a wide spread at the bottom. There is no sale history in
 * this engine: nothing is remembered between crawls, nothing is averaged over time, and no figure it
 * produces is a claim about what an item sells for. The two guards it does have are structural
 * rather than statistical — a minimum discount, and a minimum number of listings, because two
 * listings are two opinions and not a market.
 *
 * <p><b>Where the data comes from.</b> {@link LiveAuctionIndex}, which the mod's existing auction
 * crawl fills as a side effect of the lowest-BIN pass. No request of its own, and nothing leaves the
 * machine.
 *
 * <p>Pure and stateless: one static entry point over an immutable index snapshot, safe to call from
 * any background thread, never from the client thread.
 */
public final class LocalAhFlipEngine {

    /** Keeps one pass bounded regardless of how generous the thresholds are. */
    private static final int MAX_RESULTS = 40;

    private LocalAhFlipEngine() {
    }

    /**
     * One flip candidate, before it becomes a feed entry.
     *
     * @param resale        the next-cheapest live listing's price — what the item costs others after
     *                      you have taken the cheapest one off the market
     * @param profit        {@code resale × (1 − tax) − price}
     * @param discountPct   how far under {@code resale} the listing sits
     * @param liveListings  how many BIN listings of this item exist right now
     */
    public record Candidate(String auctionId, String displayName, long price, long resale,
                            long profit, double discountPct, int liveListings, long endAtMs) {
    }

    /**
     * Scans the current index.
     *
     * @param settings the AH flip config: price range and the two local guards
     * @return candidates, best profit first; empty when the index has not been built yet
     */
    public static List<Candidate> compute(SBSConfig.AhFlipAlertSettings settings) {
        Map<String, LiveAuctionIndex.Entry> index = LiveAuctionIndex.getInstance().snapshot();
        if (index.isEmpty()) {
            return List.of();
        }
        double tax = 1.0 - Math.max(0, Math.min(50, settings.localAhTaxPct)) / 100.0;
        int minDiscount = Math.max(1, settings.localMinDiscountPct);
        int minListings = Math.max(2, settings.localMinListings);
        long min = Math.max(0, settings.minPrice);
        long max = settings.maxPrice > 0 ? settings.maxPrice : Long.MAX_VALUE;

        List<Candidate> out = new ArrayList<>();
        for (LiveAuctionIndex.Entry entry : index.values()) {
            LiveAuctionIndex.Listing lowest = entry.lowest();
            long resale = entry.secondPrice();
            if (lowest == null || resale <= 0 || entry.totalListings() < minListings) {
                continue;
            }
            if (lowest.price() < min || lowest.price() > max) {
                continue;
            }
            double discount = (resale - lowest.price()) * 100.0 / resale;
            if (discount < minDiscount) {
                continue;
            }
            long profit = Math.round(resale * tax) - lowest.price();
            if (profit <= 0) {
                continue;
            }
            out.add(new Candidate(lowest.auctionId(), entry.displayName(), lowest.price(), resale,
                    profit, discount, entry.totalListings(), lowest.endAtMs()));
        }
        out.sort(Comparator.comparingLong(Candidate::profit).reversed());
        return List.copyOf(out.subList(0, Math.min(MAX_RESULTS, out.size())));
    }

    /** When the index these candidates came from was built, epoch millis; 0 = never. */
    public static long dataTsMs() {
        return LiveAuctionIndex.getInstance().updatedAtMs();
    }

    /** True once a crawl has produced an index — a distinct state from "found nothing". */
    public static boolean ready() {
        return LiveAuctionIndex.getInstance().ready();
    }
}
