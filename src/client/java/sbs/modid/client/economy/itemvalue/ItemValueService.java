/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.itemvalue;

import net.minecraft.world.item.ItemStack;
import sbs.modid.client.economy.pricehistory.logic.PriceApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prices everything the {@link ItemModifiers} parser found on an item and feeds the
 * {@link ItemValueOverlay} table. All requests go through the shared single-thread
 * {@link PriceApi} executor – strictly sequential, fully asynchronous (the render thread never
 * blocks) – and every fetched item is remembered for the session, so re-checking items is free.
 *
 * <p><b>Enchanted books</b> are priced by tier composition: two books of a tier combine into the
 * next, so tier T can be built from {@code 2^(T-k)} books of any lower tier k. For every tier that
 * actually exists in the market catalogue the composed cost is evaluated and the CHEAPEST wins –
 * for thin markets like Chimera V that is 16× Chimera I, for books whose high tier is cheap/dropped
 * directly it is simply that book.
 */
public final class ItemValueService {

    private static final ItemValueService INSTANCE = new ItemValueService();

    /** One priced line of the value table (stats are pre-scaled by count / composition factor). */
    public static final class Row {
        public final String label;
        public volatile String itemId;
        public volatile String note;
        public volatile PriceStats stats;
        public volatile boolean failed;

        /**
         * The "now" price worked out on the client from the warm Bazaar / auction caches, set the
         * moment the window opens. It fills the Now column before – and, with no licence token,
         * <i>instead of</i> – the history request; see {@link #localNow}.
         */
        public volatile Long localNow;

        Row(String label, String itemId) {
            this.label = label;
            this.itemId = itemId;
        }
    }

    /** The five table columns. */
    public record PriceStats(double now, double day, double week, double month, double all) {

        PriceStats scaled(double factor) {
            return new PriceStats(now * factor, day * factor, week * factor,
                    month * factor, all * factor);
        }
    }

    /** Session-long price memory: item id → in-flight or completed price data. */
    private final Map<String, CompletableFuture<PriceApi.ItemData>> priceCache = new ConcurrentHashMap<>();

    /** All known market item ids (for book-tier discovery); fetched once, retried on failure. */
    private volatile CompletableFuture<Set<String>> catalogueFuture;

    private ItemValueService() {
    }

    public static ItemValueService getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** Parses the hovered stack and opens the value window; prices stream in asynchronously. */
    public void check(ItemStack stack) {
        List<ItemModifiers.Part> parts = ItemModifiers.parse(stack);
        if (parts.isEmpty()) {
            return;
        }
        String title = stack.getHoverName().getString()
                .replaceAll(String.valueOf((char) 0x00A7) + ".", "").trim();
        openRows(title, parts);
    }

    /** Value check for catalogue-only refs (Recipe Viewer panel items carry no NBT). */
    public void checkPlain(List<String> candidates, String name) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        openRows(name, List.of(ItemModifiers.Part.item(name + " (base)", candidates.get(0), 1)));
    }

    private void openRows(String title, List<ItemModifiers.Part> parts) {
        // The history columns are a paid, networked feature; the Now column is not. Without a
        // licence token there is nothing to ask for, so no request is made at all and the table is
        // simply the local answer - which is the whole of what most checks are actually after.
        boolean history = PriceApi.hasToken();
        List<Row> rows = new ArrayList<>();
        for (ItemModifiers.Part part : parts) {
            Row row = new Row(part.label, part.isBook() ? null : part.itemId);
            row.localNow = localNow(part);
            rows.add(row);
            if (part.itemId == null && !part.isBook()) {
                row.failed = true;      // a component we recognised but cannot map to a market item
            } else if (!history) {
                row.failed = true;      // no token: the Now column stands alone, history reads "-"
            } else if (part.isBook()) {
                resolveBook(row, part);
            } else {
                resolveItem(row, part.itemId, part.count);
            }
        }
        ItemValueOverlay.getInstance().open(title, rows, !history);
    }

    /**
     * The part's price right now, straight from the caches the client already keeps warm – no
     * network, no licence token, no wait.
     *
     * <p><b>Buy side</b>, to match what the Now column has always meant here: for a Bazaar product
     * {@link #statsOf} reads the latest instabuy, i.e. what assembling this item would cost you
     * today, not what you would net selling the pieces off.
     */
    private static Long localNow(ItemModifiers.Part part) {
        return ItemAppraisal.partPrice(part, ItemAppraisal.Side.BUY);
    }

    // ------------------------------------------------------------------
    // Pricing
    // ------------------------------------------------------------------

    private void resolveItem(Row row, String itemId, double count) {
        fetchSafe(itemId).thenAccept(data -> {
            if (data == null) {
                row.failed = true;
                return;
            }
            row.stats = statsOf(data).scaled(count);
        });
    }

    /**
     * Book pricing under the {@link EnchantMarketRules}: stacking enchants are ONE base book
     * (they level by gameplay, e.g. Champion X ≠ 512 books); tiers above an enchant's combine
     * limit are drop/craft-only (Smite VII = Smite VI + Severed Hand) and count only as their own
     * book's market price; everything else evaluates every existing tier k ≤ T as {@code 2^(T-k)}
     * books and keeps the cheapest.
     */
    private void resolveBook(Row row, ItemModifiers.Part part) {
        catalogue().thenAccept(ids -> {
            record Candidate(String id, double factor) {
            }
            List<Candidate> candidates = new ArrayList<>();
            if (EnchantMarketRules.isStacking(part.enchantKey)) {
                for (int k = 1; k <= part.enchantTier && candidates.isEmpty(); k++) {
                    String id = bookId(part.enchantKey, k, ids);
                    if (id != null) {
                        candidates.add(new Candidate(id, 1));
                        row.note = "T" + k + " book, levels by use";
                    }
                }
            } else if (part.enchantTier > EnchantMarketRules.maxCombinableTier(part.enchantKey)) {
                String id = bookId(part.enchantKey, part.enchantTier, ids);
                if (id != null) {
                    candidates.add(new Candidate(id, 1));
                }
            } else {
                for (int k = 1; k <= part.enchantTier; k++) {
                    String id = bookId(part.enchantKey, k, ids);
                    if (id != null) {
                        candidates.add(new Candidate(id, Math.pow(2, part.enchantTier - k)));
                    }
                }
            }
            if (candidates.isEmpty()) {
                row.failed = true;
                return;
            }
            List<CompletableFuture<PriceApi.ItemData>> futures = new ArrayList<>();
            for (Candidate candidate : candidates) {
                futures.add(fetchSafe(candidate.id()));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenRun(() -> {
                Candidate best = null;
                PriceStats bestStats = null;
                for (int i = 0; i < candidates.size(); i++) {
                    PriceApi.ItemData data = futures.get(i).getNow(null);
                    if (data == null) {
                        continue;
                    }
                    PriceStats stats = statsOf(data).scaled(candidates.get(i).factor());
                    if (stats.now() <= 0) {
                        continue;
                    }
                    if (bestStats == null || stats.now() < bestStats.now()) {
                        best = candidates.get(i);
                        bestStats = stats;
                    }
                }
                if (best == null) {
                    row.failed = true;
                    return;
                }
                row.itemId = best.id();
                if (best.factor() > 1) {
                    row.note = "= " + (long) best.factor() + "x " + tierSuffix(best.id());
                }
                row.stats = bestStats;
            });
        });
    }

    /** "ENCHANTED_BOOK_ULTIMATE_CHIMERA_1" → "T1" (display hint for composed book prices). */
    private static String tierSuffix(String bookIdWithTier) {
        int underscore = bookIdWithTier.lastIndexOf('_');
        return underscore >= 0 ? "T" + bookIdWithTier.substring(underscore + 1) : bookIdWithTier;
    }

    /**
     * The catalogue id of one enchant tier's book, or {@code null} when the market does not know
     * it. The backend uses Bazaar-style {@code ENCHANTMENT_<ENCHANT>_<TIER>} ids (verified:
     * ENCHANTMENT_ULTIMATE_CHIMERA_1, ENCHANTMENT_SHARPNESS_7); the {@code ENCHANTED_BOOK_} form
     * and an {@code ULTIMATE_}-stripped variant are tried as fallbacks for data-source drift.
     */
    private static String bookId(String enchantKey, int tier, Set<String> ids) {
        String key = enchantKey.toUpperCase(Locale.ROOT);
        String[] prefixes = {"ENCHANTMENT_", "ENCHANTED_BOOK_"};
        for (String prefix : prefixes) {
            String direct = prefix + key + "_" + tier;
            if (ids.contains(direct)) {
                return direct;
            }
            if (key.startsWith("ULTIMATE_")) {
                String stripped = prefix + key.substring("ULTIMATE_".length()) + "_" + tier;
                if (ids.contains(stripped)) {
                    return stripped;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Fetch plumbing (sequential via PriceApi's single-thread executor, remembered per session)
    // ------------------------------------------------------------------

    /** Cached fetch that never completes exceptionally – {@code null} on failure (and retried later). */
    private CompletableFuture<PriceApi.ItemData> fetchSafe(String itemId) {
        String key = itemId.toUpperCase(Locale.ROOT);
        return priceCache.computeIfAbsent(key, id -> {
            CompletableFuture<PriceApi.ItemData> future = new CompletableFuture<>();
            PriceApi.getInstance().fetchItem(id, "all", (data, error) -> {
                if (data == null) {
                    priceCache.remove(id, future); // failures are not remembered – retry next check
                }
                future.complete(data);
            });
            return future;
        });
    }

    private CompletableFuture<Set<String>> catalogue() {
        CompletableFuture<Set<String>> future = catalogueFuture;
        if (future != null) {
            return future;
        }
        synchronized (this) {
            if (catalogueFuture == null) {
                CompletableFuture<Set<String>> created = new CompletableFuture<>();
                catalogueFuture = created;
                PriceApi.getInstance().fetchItems((items, error) -> {
                    if (items == null) {
                        catalogueFuture = null; // retry on the next value check
                        created.complete(Set.of());
                        return;
                    }
                    Set<String> ids = new HashSet<>();
                    for (String[] entry : items) {
                        if (entry != null && entry.length > 0) {
                            ids.add(entry[0].toUpperCase(Locale.ROOT));
                        }
                    }
                    created.complete(ids);
                });
            }
            return catalogueFuture;
        }
    }

    // ------------------------------------------------------------------
    // Column math
    // ------------------------------------------------------------------

    /**
     * The five columns from one item's full history: "now" is the lowest BIN (AH) or the latest
     * instabuy (Bazaar; per spec the last sales are used when {@code lb} is absent); the window
     * averages use the matching per-bucket column – Bazaar {@code buyAvg} (= average instabuy),
     * AH the per-bucket <b>minimum</b> sale, the closest proxy for "average lowest BIN over the
     * window" the history offers (the plain sale average mixes in high-value attribute/skin
     * variants and overstates a clean item's worth). Each window falls back to the next-shorter
     * value when the data is too sparse.
     */
    static PriceStats statsOf(PriceApi.ItemData data) {
        long nowSec = System.currentTimeMillis() / 1000L;
        double[][] history = data.h != null ? data.h : new double[0][];
        // Bazaar h: [ts, bucket, buyAvg, ...] -> 2; AH h: [ts, bucket, avg, median, min, ...] -> 4.
        int column = data.isBazaar() ? 2 : 4;

        double now = Double.NaN;
        if (data.isBazaar()) {
            if (data.s != null && data.s.length > 0) {
                double[] last = data.s[data.s.length - 1];
                if (last.length > 1) {
                    now = last[1]; // instabuy
                }
            }
        } else {
            if (data.lb != null && data.lb.length > 0 && data.lb[0] > 0) {
                now = data.lb[0];
            } else if (data.s != null && data.s.length > 0) {
                double[] newest = data.s[0]; // AH sales are newest-first
                if (newest.length > 2 && newest[2] > 0) {
                    now = newest[1] / newest[2]; // total price / amount = unit price
                }
            }
        }

        double all = averageSince(history, 0, column);
        double month = averageSince(history, nowSec - 30L * 86400L, column);
        double week = averageSince(history, nowSec - 7L * 86400L, column);
        double day = averageSince(history, nowSec - 86400L, column);

        if (!(now > 0)) {
            now = history.length > 0 ? lastBucketValue(history, column) : 0;
        }
        if (!(day > 0)) {
            day = now;
        }
        if (!(week > 0)) {
            week = day;
        }
        if (!(month > 0)) {
            month = week;
        }
        if (!(all > 0)) {
            all = month;
        }
        return new PriceStats(Math.max(0, now), day, week, month, all);
    }

    /** Mean of the given bucket column since the timestamp (short/zero rows fall back to avg). */
    private static double averageSince(double[][] history, long minTs, int column) {
        double sum = 0;
        int buckets = 0;
        for (double[] row : history) {
            double value = bucketValue(row, column);
            if (row.length > 0 && row[0] >= minTs && value > 0) {
                sum += value;
                buckets++;
            }
        }
        return buckets > 0 ? sum / buckets : 0;
    }

    private static double lastBucketValue(double[][] history, int column) {
        for (int i = history.length - 1; i >= 0; i--) {
            double value = bucketValue(history[i], column);
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    /** One bucket's price in the wanted column, falling back to the avg column (2) when absent. */
    private static double bucketValue(double[] row, int column) {
        if (row.length > column && row[column] > 0) {
            return row[column];
        }
        return row.length > 2 ? row[2] : 0;
    }
}
