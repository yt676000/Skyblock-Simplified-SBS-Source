/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.museum.logic;

import sbs.modid.client.economy.itemvalue.ItemAppraisal;
import sbs.modid.client.helper.museum.model.MuseumCatalog;
import sbs.modid.client.helper.museum.model.MuseumCatalog.Category;
import sbs.modid.client.helper.museum.model.MuseumCatalog.Donation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The rows of the Missing Donations screen: every donation still missing from a fully seen category,
 * priced from warm local caches only ({@link ItemAppraisal#price}) - never a request.
 *
 * <p>Unpriced is never zero. A set with some pieces unpriced carries the priced sum and
 * {@code partial}, shown as {@code N+}; an item nobody prices has {@code price == null}, shows
 * {@code ?}, and sorts last on the two price-based orders rather than first as a free item would.
 */
public final class MissingDonations {

    public enum Sort {
        XP_PER_COIN("XP / coin"),
        XP("XP"),
        PRICE("Price");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One missing donation. {@code price} is null when nothing prices it. */
    public record Row(Donation donation, Long price, boolean partial, boolean higherTierDonated) {

        /** XP per million coins, or {@code -1} when there is no price to divide by. */
        public double xpPerMillion() {
            return price == null || price <= 0 ? -1 : donation.xp() * 1_000_000.0 / price;
        }
    }

    /** One category: its rows, or why there are none. */
    public record Section(Category category, boolean known, int pagesSeen, int pages, int xpLeft,
                          int xpTotal, long seenAt, List<Row> rows) {
    }

    private MissingDonations() {
    }

    public static List<Section> sections(Sort sort) {
        MuseumCatalog catalog = MuseumCatalog.current();
        MuseumStore store = MuseumStore.getInstance();
        Map<Category, List<Row>> byCategory = new EnumMap<>(Category.class);
        Map<Category, Integer> total = new EnumMap<>(Category.class);
        for (Donation donation : catalog.all()) {
            total.merge(donation.category(), donation.xp(), Integer::sum);
            if (!Boolean.FALSE.equals(store.donated(donation.category(), donation.key()))) {
                continue;   // donated, or not known yet
            }
            byCategory.computeIfAbsent(donation.category(), c -> new ArrayList<>())
                    .add(priced(catalog, store, donation));
        }
        List<Section> out = new ArrayList<>();
        for (Category category : Category.values()) {
            if (!total.containsKey(category)) {
                continue;
            }
            List<Row> rows = byCategory.getOrDefault(category, new ArrayList<>());
            rows.sort(comparator(sort));
            int left = 0;
            for (Row row : rows) {
                left += row.donation().xp();
            }
            int[] coverage = store.coverage(category);
            out.add(new Section(category, store.known(category), coverage[0], coverage[1], left,
                    total.get(category), store.seenAt(category), List.copyOf(rows)));
        }
        return out;
    }

    private static Row priced(MuseumCatalog catalog, MuseumStore store, Donation donation) {
        boolean higher = MuseumStatus.higherTierDonated(catalog, store, donation);
        if (!donation.set()) {
            return new Row(donation, ItemAppraisal.price(donation.key(), ItemAppraisal.Side.SELL),
                    false, higher);
        }
        long sum = 0;
        int priced = 0;
        for (String piece : donation.pieces()) {
            Long price = ItemAppraisal.price(piece, ItemAppraisal.Side.SELL);
            if (price != null) {
                sum += price;
                priced++;
            }
        }
        if (priced == 0) {
            return new Row(donation, null, false, higher);
        }
        return new Row(donation, sum, priced < donation.pieces().size(), higher);
    }

    static Comparator<Row> comparator(Sort sort) {
        Comparator<Row> byName = Comparator.comparing(row -> row.donation().displayName());
        return switch (sort) {
            // Unpriced rows last in both price orders: a missing price is not a free item.
            case XP_PER_COIN -> Comparator.<Row>comparingInt(row -> row.price() == null ? 1 : 0)
                    .thenComparing(Comparator.comparingDouble(Row::xpPerMillion).reversed())
                    .thenComparing(byName);
            case XP -> Comparator.<Row>comparingInt(row -> -row.donation().xp()).thenComparing(byName);
            case PRICE -> Comparator.<Row>comparingInt(row -> row.price() == null ? 1 : 0)
                    .thenComparingLong(row -> row.price() == null ? 0 : row.price())
                    .thenComparing(byName);
        };
    }
}
