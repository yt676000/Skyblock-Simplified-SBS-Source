/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.prices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What is <b>on the auction house right now</b>, per item: the cheapest few BIN listings and how
 * many there are in total.
 *
 * <p>Built by {@link LbinCache} out of the crawl it already runs — the same pass over every auction
 * page that produces the lowest-BIN tooltip. Nothing here fetches: one crawl, several readers, which
 * is the rule this codebase already applies to the bazaar snapshot. A second crawl for the flip
 * ranking would have doubled a request stream that already sits near the rate limit.
 *
 * <p><b>Live listings only, by design.</b> There is no sale history in here and no derived value: an
 * entry is a list of auctions a player could open and buy this minute. Everything a reader can
 * conclude from it — that something is listed below the rest of its own market, that four of a thing
 * are for sale — is a statement about the present, which is the only thing this data supports.
 *
 * <p>Published as an immutable snapshot behind a {@code volatile} reference, so readers are
 * lock-free and never see a half-built index.
 */
public final class LiveAuctionIndex {

    private static final LiveAuctionIndex INSTANCE = new LiveAuctionIndex();

    /**
     * How many of the cheapest listings are kept per item. Enough to see the shape of the low end of
     * a market — is the cheapest one an outlier or is the whole stack there — without holding the
     * whole auction house, which is ~70k listings a crawl.
     */
    public static final int KEEP_PER_ITEM = 6;

    /** One live BIN listing. {@code auctionId} is the 32-hex form {@code /viewauction} takes. */
    public record Listing(String auctionId, long price, long endAtMs) {
    }

    /**
     * One item's low end of the market.
     *
     * @param displayName  the auction's own item name, as Hypixel wrote it
     * @param cheapest     up to {@link #KEEP_PER_ITEM} listings, cheapest first
     * @param totalListings how many BIN listings of this item exist in total
     */
    public record Entry(String displayName, List<Listing> cheapest, int totalListings) {

        /** The cheapest listing, or {@code null} when the entry somehow carries none. */
        public Listing lowest() {
            return cheapest.isEmpty() ? null : cheapest.get(0);
        }

        /**
         * The price the <b>next</b> seller is asking, or {@code 0} when this is the only listing.
         *
         * <p>This is the whole of the local flip idea and its whole limit: if you buy the cheapest
         * one, the second cheapest is what the item now costs anyone else, so it is the most that
         * asking price could be worth to you. It is not what the item is <i>worth</i> — a market
         * where both listings are mispriced looks exactly the same from here.
         */
        public long secondPrice() {
            return cheapest.size() < 2 ? 0 : cheapest.get(1).price();
        }
    }

    /** Item key (see {@link sbs.modid.client.core.item.SkyblockItem#normalizeName}) to its entry. */
    private volatile Map<String, Entry> index = Map.of();

    /** When the crawl that produced the current index finished, epoch millis; 0 = never. */
    private volatile long updatedAtMs;

    private LiveAuctionIndex() {
    }

    public static LiveAuctionIndex getInstance() {
        return INSTANCE;
    }

    /** The current index; empty before the first crawl completes, never {@code null}. */
    public Map<String, Entry> snapshot() {
        return index;
    }

    public Entry get(String key) {
        return key == null ? null : index.get(key);
    }

    public long updatedAtMs() {
        return updatedAtMs;
    }

    /** True once a crawl has published something. A distinct state from "nothing is cheap enough". */
    public boolean ready() {
        return !index.isEmpty();
    }

    /** Swaps in a finished index. Called only by {@link LbinCache} at the end of a crawl. */
    void publish(Map<String, Entry> next, long atMs) {
        this.index = Map.copyOf(next);
        this.updatedAtMs = atMs;
    }

    /**
     * Accumulator used while a crawl is running: keeps only the cheapest {@link #KEEP_PER_ITEM}
     * listings per item plus a running total, so the whole auction house never has to be held at
     * once. Not thread-safe — one crawl thread owns it.
     */
    public static final class Builder {

        private final Map<String, List<Listing>> cheapest = new java.util.HashMap<>();
        private final Map<String, Integer> totals = new java.util.HashMap<>();
        private final Map<String, String> names = new java.util.HashMap<>();

        /** Offers one listing to the index; kept only if it is among the cheapest so far. */
        public void offer(String key, String displayName, Listing listing) {
            if (key == null || key.isEmpty() || listing == null || listing.price() <= 0) {
                return;
            }
            totals.merge(key, 1, Integer::sum);
            names.putIfAbsent(key, displayName == null ? key : displayName);
            List<Listing> list = cheapest.computeIfAbsent(key, unused -> new ArrayList<>(KEEP_PER_ITEM + 1));
            // Insertion sort into a list that is never longer than KEEP_PER_ITEM + 1. Sorting the
            // whole market per item at the end would be the obvious version and is what makes a
            // 70k-auction crawl slow enough to notice.
            int at = 0;
            while (at < list.size() && list.get(at).price() <= listing.price()) {
                at++;
            }
            if (at >= KEEP_PER_ITEM) {
                return;
            }
            list.add(at, listing);
            if (list.size() > KEEP_PER_ITEM) {
                list.remove(list.size() - 1);
            }
        }

        /** Freezes what was collected into the shared index. */
        public void publish(long atMs) {
            Map<String, Entry> built = new java.util.HashMap<>(cheapest.size());
            for (Map.Entry<String, List<Listing>> entry : cheapest.entrySet()) {
                built.put(entry.getKey(), new Entry(names.get(entry.getKey()),
                        List.copyOf(entry.getValue()),
                        totals.getOrDefault(entry.getKey(), entry.getValue().size())));
            }
            getInstance().publish(Collections.unmodifiableMap(built), atMs);
        }
    }
}
