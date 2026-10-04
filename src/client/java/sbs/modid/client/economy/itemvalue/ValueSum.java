/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.itemvalue;

import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.NumberDisplay;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * "What is all of this worth": the one summing rule behind the container value card and the storage
 * page values. Totals {@link ItemAppraisal#of} - warm local caches only, nothing is fetched - with
 * each stack's count already inside its {@link ItemAppraisal.Appraisal#total}.
 *
 * <p>A stack that could not be fully priced still adds what it could and counts as a miss, and a
 * total with any miss is a <b>floor</b>: shown with a trailing {@code +}, the convention the dungeon
 * chest overlay also uses. Only real SkyBlock items can miss - Hypixel fills menus with unnamed glass
 * panes and decorative heads, and reporting those as unpriced would turn every total into a floor.
 */
public final class ValueSum {

    /** A total, and how many items in it were not (fully) priced. */
    public record Total(long value, int unpriced) {

        public static final Total ZERO = new Total(0, 0);

        /** Whether {@link #value} is a lower bound rather than a figure. */
        public boolean floor() {
            return unpriced > 0;
        }

        /** Whether there is anything worth showing: a value, or at least a known miss. */
        public boolean any() {
            return value > 0 || unpriced > 0;
        }

        public Total plus(Total other) {
            return new Total(value + other.value, unpriced + other.unpriced);
        }

        /** "1.2M", "1.2M+" for a floor; follows the mod-wide Shorten Numbers setting. */
        public String label() {
            return NumberDisplay.format(value) + (floor() ? "+" : "");
        }
    }

    private ValueSum() {
    }

    /** Sums real item stacks with the live appraisal. */
    public static Total of(Iterable<ItemStack> stacks) {
        return of(stacks, ItemAppraisal::of, stack -> SkyblockItem.id(stack) != null, ItemStack::isEmpty);
    }

    /**
     * The rule itself, over any item type so it can be tested without a game.
     *
     * @param appraise  values one item, its stack count included
     * @param canMiss   whether an item counts as a miss when it is not fully priced
     * @param empty     items to skip entirely
     */
    public static <T> Total of(Iterable<T> items, Function<T, ItemAppraisal.Appraisal> appraise,
                               Predicate<T> canMiss, Predicate<T> empty) {
        long value = 0;
        int misses = 0;
        for (T item : items) {
            if (item == null || empty.test(item)) {
                continue;
            }
            ItemAppraisal.Appraisal appraisal = appraise.apply(item);
            if (appraisal.priced()) {
                value += appraisal.total();
                if (!appraisal.complete()) {
                    misses++;
                }
            } else if (canMiss.test(item)) {
                misses++;
            }
        }
        return new Total(value, misses);
    }
}
