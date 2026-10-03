/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.skills.hunting.model.HuntingBoxShard;
import sbs.modid.client.skills.hunting.model.ShardDefinition;
import sbs.modid.client.skills.hunting.model.ShardPriceSource;
import sbs.modid.client.skills.hunting.model.ShardRarity;
import sbs.modid.client.skills.hunting.model.ShardSort;
import sbs.modid.client.skills.hunting.model.ShardState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The join between "what shards exist" ({@link ShardCatalog}) and "which of them you have"
 * ({@link ShardOwnership}) - the rows the panel draws.
 *
 * <p><b>Missing is now a set difference over static data, and that is the change.</b> The list used
 * to be built from a catalogue assembled out of the live Bazaar map and whatever menus had been
 * opened, which made "missing" a function of what the GUI had shown - circular, and quietly wrong
 * whenever a network pull was slow. It is now the bundled catalogue minus the shards this profile
 * has been seen to own, with the unconsumable ones removed from the total because they can never be
 * collected and a row for one could never be closed.
 *
 * <p><b>There is no coverage or completeness figure any more.</b> A missing list built from a
 * bundled file does not need to know how many pages of a menu have been read - the question it used
 * to ask ("have I seen enough to be sure?") was unanswerable in practice and gated the feature on
 * its own ignorance.
 *
 * <p><b>Amounts are exact where something measured them and estimated where nothing did.</b> The
 * Hunting Box states {@code "Owned: N Shards"} outright; the Attribute Menu yields a derived figure
 * once {@code shards.json} carries a levelling row. Failing both, the remainder falls back to
 * {@link ShardRarity#toMax()} - a {@link sbs.modid.client.helper.rift.model.Certainty#WIKI} figure,
 * marked as such on the row and in the footer. An estimated remainder over-states the gap, which
 * points the error at buying too much rather than at telling somebody a gap is closed when it is not.
 *
 * <p>Nothing here touches the Bazaar API or a menu. It is recomputed from two in-memory maps when
 * the panel refreshes, never per frame.
 */
public final class ShardProgress {

    /** What the list is allowed to contain. */
    public record Scope(boolean includeOwned, boolean includePartial) {
    }

    /**
     * One row of the panel.
     *
     * @param id           the canonical shard id, {@code ATTRIBUTE_SHARD_<NAME>}
     * @param name         the display name from the catalogue
     * @param rarity       the rarity, {@link ShardRarity#UNKNOWN} until something states it
     * @param state        where this shard stands for this profile
     * @param need         shards still to collect, or {@code -1} when not even estimable
     * @param needMeasured whether {@link #need} was derived from a reading rather than assumed from
     *                     the rarity - the difference between a fact and a wiki number
     * @param unitPrice    one shard from the chosen side of the book, or {@code null} when unpriced
     * @param totalCost    {@link #unitPrice} times {@link #need}, or {@code null} when either is unknown
     * @param inBox        how many sit in the Hunting Box already - acquired but not yet spent
     * @param wideSpread   the book is wide enough that the quoted price is not what a trade costs
     * @param nameKnown    whether the catalogue carries a real display name for it
     */
    public record Row(String id, String name, ShardRarity rarity, ShardState state, int need,
                      boolean needMeasured, Long unitPrice, Long totalCost, int inBox,
                      boolean wideSpread, boolean nameKnown) {

        public boolean priced() {
            return unitPrice != null;
        }

        public boolean needKnown() {
            return need >= 0;
        }
    }

    /**
     * The headline numbers.
     *
     * @param listed     rows after the scope filter - what the panel is showing
     * @param missing    shards not collected at all
     * @param partial    shards part-collected
     * @param owned      shards finished
     * @param unknown    always {@code 0} now; kept so the footer's shape does not change
     * @param total      the consumable catalogue - what the other four add up to
     * @param totalCost  what closing every listed gap costs, or {@code null} when nothing is priced
     * @param unpriced   listed rows the Bazaar has no row for
     * @param estimated  listed rows whose amount came from the rarity rather than from a reading
     * @param priceAgeMs how stale the prices are
     */
    public record Summary(int listed, int missing, int partial, int owned, int unknown, int total,
                          Long totalCost, int unpriced, int estimated, long priceAgeMs) {
    }

    private ShardProgress() {
    }

    /**
     * Every consumable shard with its state resolved, narrowed to {@code scope} and unsorted.
     *
     * <p>The single computation the panel and {@link #summarise} both read from, so the count in the
     * footer can never disagree with the list above it.
     */
    public static List<Row> rows(Scope scope, ShardPriceSource source) {
        ShardOwnership ownership = ShardOwnership.getInstance();
        Map<String, Integer> inBox = heldInBox();

        List<Row> rows = new ArrayList<>();
        for (ShardDefinition shard : ShardCatalog.consumable()) {
            String id = shard.key();
            ShardOwnership.Owned owned = ownership.get(id);
            int held = inBox.getOrDefault(id, 0);
            ShardState state = state(owned, held);
            if (state == ShardState.OWNED && !scope.includeOwned()) {
                continue;
            }
            if (state == ShardState.PARTIAL && !scope.includePartial()) {
                continue;
            }

            ShardRarity rarity = shard.rarity();
            int tier = owned == null ? -1 : owned.tier;
            int derived = tier < 0 ? ShardLevelling.UNKNOWN : ShardLevelling.toMaxFrom(rarity, tier);
            boolean measured = derived >= 0;
            int need = measured ? Math.max(0, derived - held) : estimate(state, rarity, held);

            Long unit = ShardBazaar.unitPrice(id, source);
            Long cost = unit != null && need > 0 ? unit * need : null;
            // Every row here comes out of the catalogue, so every name is a catalogue name. The flag
            // stays on the record because the panel still draws an un-named row muted, and a future
            // source - a shard seen in game that the bundled file does not carry - would set it
            // false. It cannot be false today, and saying so beats an expression that looks like a
            // test and is really a constant.
            rows.add(new Row(id, shard.display(), rarity, state, need, measured, unit, cost, held,
                    ShardBazaar.wideSpread(id), true));
        }
        return rows;
    }

    /** How many of each shard the Hunting Box holds, summed over its rows. */
    private static Map<String, Integer> heldInBox() {
        Map<String, Integer> inBox = new HashMap<>();
        for (HuntingBoxShard shard : HuntingBoxStore.getInstance().shards()) {
            inBox.merge(shard.id(), shard.count(), Integer::sum);
        }
        return inBox;
    }

    /**
     * Where a shard stands.
     *
     * <p><b>A shard nothing has ever stated is missing</b> - that is the definition the request
     * asked for, and it is answerable now precisely because the catalogue no longer comes from the
     * GUI. A shard sitting in the box has been collected whatever any menu did or did not say about
     * it, and is never reported as missing.
     *
     * <p>Holding shards promotes to {@link ShardState#PARTIAL} rather than to {@code OWNED}, which
     * is the honest answer rather than a cautious one: holding shards says the collecting has
     * started and says nothing about whether the attribute they feed is finished.
     */
    private static ShardState state(ShardOwnership.Owned owned, int held) {
        if (owned != null && owned.tier >= ShardLevelling.MAX_TIER) {
            return ShardState.OWNED;
        }
        if (held > 0 || (owned != null && owned.owned())) {
            return ShardState.PARTIAL;
        }
        return ShardState.MISSING;
    }

    /**
     * The remainder for a shard nothing measured, from the rarity's zero-to-max requirement.
     *
     * <p>{@link ShardState#PARTIAL} gets the full requirement rather than a fraction of it, because
     * nothing here knows how much of it is already done. That over-states the gap, which is the
     * direction that costs coins rather than the direction that costs an attribute - and the row
     * says the figure is estimated, so the player is not being told a guess is a measurement.
     */
    private static int estimate(ShardState state, ShardRarity rarity, int held) {
        if (state == ShardState.OWNED) {
            return 0;
        }
        if (!rarity.known()) {
            return -1;
        }
        // What the box already holds is not still to be collected. The same subtraction
        // ShardValuation.needed makes, and the reason a partly held shard stops being quoted at its
        // full zero-to-max price on a shopping list.
        return Math.max(0, rarity.toMax() - Math.max(0, held));
    }

    /** The headline numbers over the whole consumable catalogue, with {@code listed} describing {@code rows}. */
    public static Summary summarise(List<Row> rows) {
        ShardOwnership ownership = ShardOwnership.getInstance();
        Map<String, Integer> inBox = heldInBox();

        int missing = 0;
        int partial = 0;
        int owned = 0;
        int total = 0;
        for (ShardDefinition shard : ShardCatalog.consumable()) {
            total++;
            // Through the same rule the rows use, or the footer would count a shard missing that the
            // list above it is showing as held.
            switch (state(ownership.get(shard.key()), inBox.getOrDefault(shard.key(), 0))) {
                case OWNED -> owned++;
                case PARTIAL -> partial++;
                default -> missing++;
            }
        }

        long cost = 0;
        boolean anyPriced = false;
        int unpriced = 0;
        int estimated = 0;
        for (Row row : rows) {
            if (!row.needMeasured() && row.needKnown() && row.need() > 0) {
                estimated++;
            }
            if (row.totalCost() == null) {
                // Never counted as zero: an unpriced row is a hole in the total, and a total that
                // quietly swallowed it would be wrong in a way nobody could see.
                if (!row.priced()) {
                    unpriced++;
                }
                continue;
            }
            anyPriced = true;
            cost += row.totalCost();
        }
        return new Summary(rows.size(), missing, partial, owned, 0, total,
                anyPriced ? cost : null, unpriced, estimated,
                BazaarPriceCache.getInstance().priceAgeMs());
    }

    /**
     * Orders {@code rows} in place.
     *
     * <p>Two rules on top of the key. <b>Unknowns always sort last</b>, whichever direction is
     * chosen: an unpriced row treated as zero would lead a cheapest-first list and read as free,
     * which is the single most misleading thing this panel could do. And <b>ties break on the
     * name</b>, so the list never reshuffles itself between two frames that agree on the key.
     */
    public static void sort(List<Row> rows, ShardSort sort, boolean descending) {
        rows.sort((a, b) -> {
            int unknowns = compareKnown(a, b, sort);
            if (unknowns != 0) {
                return unknowns;
            }
            int primary = switch (sort) {
                case PRICE -> Long.compare(a.unitPrice() == null ? 0 : a.unitPrice(),
                        b.unitPrice() == null ? 0 : b.unitPrice());
                case COST -> Long.compare(a.totalCost() == null ? 0 : a.totalCost(),
                        b.totalCost() == null ? 0 : b.totalCost());
                case RARITY -> Integer.compare(a.rarity().ordinal(), b.rarity().ordinal());
                case AMOUNT -> Integer.compare(a.need(), b.need());
                case NAME -> 0;
            };
            if (descending) {
                primary = -primary;
            }
            return primary != 0 ? primary : a.name().compareToIgnoreCase(b.name());
        });
    }

    /** Rows whose sort key is unknown go after those whose key is known, in both directions. */
    private static int compareKnown(Row a, Row b, ShardSort sort) {
        boolean knownA = switch (sort) {
            case PRICE -> a.priced();
            case COST -> a.totalCost() != null;
            case AMOUNT -> a.needKnown();
            case RARITY -> a.rarity() != ShardRarity.UNKNOWN;
            case NAME -> true;
        };
        boolean knownB = switch (sort) {
            case PRICE -> b.priced();
            case COST -> b.totalCost() != null;
            case AMOUNT -> b.needKnown();
            case RARITY -> b.rarity() != ShardRarity.UNKNOWN;
            case NAME -> true;
        };
        return knownA == knownB ? 0 : knownA ? -1 : 1;
    }
}
