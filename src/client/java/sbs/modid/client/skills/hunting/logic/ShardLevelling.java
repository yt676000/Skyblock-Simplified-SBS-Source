/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import sbs.modid.client.helper.rift.model.Certainty;
import sbs.modid.client.skills.hunting.model.ShardRarity;

import java.util.List;

/**
 * How many shards an attribute has taken so far - <b>derived, never guessed</b>.
 *
 * <p><b>The arithmetic.</b> The Attribute Menu states two things about an attribute: the tier it is
 * at, written after its name ("Berry Eater IX"), and how many more shards it wants, written in its
 * lore ("Syphon 12 shards to level up!"). Neither is an amount owned. Together with a table of what
 * each tier costs they are:
 *
 * <pre>{@code owned = cumulative(tier + 1) - remaining}</pre>
 *
 * <p>That is exact, not an estimate: {@code cumulative(tier + 1)} is what reaching the next tier
 * takes in total, and {@code remaining} is how much of that is still outstanding, so the difference
 * is what has already gone in. The previous code had neither number and assumed the attribute was at
 * zero, which is why every figure in this feature was a lower bound.
 *
 * <p><b>The table ships empty, and an empty table yields "unknown" rather than a number.</b> The
 * per-tier split is in no keyless source - Hypixel's items resource carries no shards at all
 * (re-checked 2026-08-20) and taking it from another mod is forbidden. So {@link #ownedFrom} returns
 * {@link #UNKNOWN} until {@code shards.json} carries a row for that rarity, and the Hunting Box's
 * own {@code "Owned: N Shards"} line - which is exact and needs no table - is what answers in the
 * meantime. A wrong table would produce a confident wrong number, which is the one outcome this
 * feature must not have: a player who sells shards they needed has been actively harmed by it.
 *
 * <p>Whatever a filled table says is {@link Certainty#WIKI} until somebody watches it happen, and
 * the UI draws {@link ShardRarity#COUNT_CERTAINTY} beside every figure that came from one - the same
 * tag the whole-ladder totals already carry, because the two came from the same place.
 */
public final class ShardLevelling {

    /** Returned when the table cannot answer. Never {@code 0}, which would read as "none owned". */
    public static final int UNKNOWN = -1;

    /** The highest tier an attribute reaches. Only used to bound a reading, never to compute one. */
    public static final int MAX_TIER = 10;

    private ShardLevelling() {
    }

    /**
     * Shards owned towards an attribute, or {@link #UNKNOWN}.
     *
     * @param rarity    the shard's rarity - which levelling row applies
     * @param tier      the tier the attribute is at now, {@code 0} for one not yet unlocked
     * @param remaining the "Syphon N shards to level up/unlock!" count, or negative when unread
     */
    public static int ownedFrom(ShardRarity rarity, int tier, int remaining) {
        if (remaining < 0 || tier < 0 || tier > MAX_TIER) {
            return UNKNOWN;
        }
        int target = cumulative(rarity, tier + 1);
        if (target == UNKNOWN) {
            return UNKNOWN;
        }
        // A remainder larger than the whole next tier means one of the two readings was not what it
        // looked like. Reporting nothing beats reporting a negative amount dressed up as a count.
        return remaining > target ? UNKNOWN : target - remaining;
    }

    /**
     * Shards needed in total to reach {@code tier}, or {@link #UNKNOWN} when the table cannot say.
     *
     * <p>{@code cumulative(rarity, 1)} is what tier 1 alone costs; {@code cumulative(rarity, 0)} is
     * zero, because an attribute at tier 0 has taken nothing.
     */
    public static int cumulative(ShardRarity rarity, int tier) {
        if (tier <= 0) {
            return 0;
        }
        List<Integer> table = table(rarity);
        if (table.size() < tier) {
            return UNKNOWN;   // no row, or a row that stops short of the tier being asked about
        }
        int total = 0;
        for (int index = 0; index < tier; index++) {
            Integer cost = table.get(index);
            if (cost == null || cost < 0) {
                return UNKNOWN;
            }
            total += cost;
        }
        return total;
    }

    /**
     * Shards needed to take an attribute from where it is to the maximum, or {@link #UNKNOWN}.
     *
     * <p>What a shopping list wants. Falls back to nothing rather than to
     * {@link ShardRarity#toMax()} when the table is empty: that constant is a whole-ladder total
     * from the same unverified source and mixing the two would make it impossible to tell a derived
     * figure from an assumed one, which is the distinction the whole feature turns on.
     */
    public static int toMaxFrom(ShardRarity rarity, int tier) {
        int max = cumulative(rarity, MAX_TIER);
        int here = cumulative(rarity, Math.max(0, tier));
        if (max == UNKNOWN || here == UNKNOWN) {
            return UNKNOWN;
        }
        return Math.max(0, max - here);
    }

    /** Whether the catalogue carries a usable levelling row for a rarity. */
    public static boolean known(ShardRarity rarity) {
        return !table(rarity).isEmpty();
    }

    /** The per-tier costs for a rarity, index 0 being tier 1. Empty when the file states none. */
    private static List<Integer> table(ShardRarity rarity) {
        if (rarity == null || rarity == ShardRarity.UNKNOWN) {
            return List.of();
        }
        return ShardCatalog.levelling(rarity.name());
    }
}
