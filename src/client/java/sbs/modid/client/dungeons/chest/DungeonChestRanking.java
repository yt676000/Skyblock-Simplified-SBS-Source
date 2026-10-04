/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.chest;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ranks every reward chest in the open menu against each other, so the one worth opening is visible
 * without hovering all of them in turn.
 *
 * <p>A dungeon run offers several chests at once and the interesting question is comparative: which
 * of these makes money, and which makes the most. Reading that off five tooltips one after another is
 * exactly the work a mod should be doing for you - so the profitable ones are marked, and the best
 * one is marked differently.
 *
 * <p><b>Throttled</b>, because the caller is a render hook: unguarded, every frame would strip and
 * parse the lore of every slot in the menu. The prices behind it move on a background poller measured
 * in seconds, so a quarter of a second is already far finer than the data it reads. The menu's state
 * id is checked as well, so claiming a chest re-ranks at once instead of waiting out the interval.
 */
public final class DungeonChestRanking {

    private static final long REFRESH_MS = 250L;

    private static Ranking cached = new Ranking(Map.of(), Map.of(), -1);
    private static long cachedAt;
    private static int cachedStateId = Integer.MIN_VALUE;
    private static AbstractContainerMenu cachedMenu;

    private DungeonChestRanking() {
    }

    /** The current ranking, recomputed only when the throttle expires or the menu's contents change. */
    public static Ranking get(AbstractContainerMenu menu, int upper) {
        long now = System.currentTimeMillis();
        int stateId = menu.getStateId();
        if (menu == cachedMenu && stateId == cachedStateId && now - cachedAt < REFRESH_MS) {
            return cached;
        }
        cachedMenu = menu;
        cachedStateId = stateId;
        cachedAt = now;
        cached = scan(menu, upper);
        return cached;
    }

    /**
     * Every chest in the menu, the profitable ones on their own, and the single best slot.
     *
     * <p><b>{@code chests} carries the losers too, and that is what makes one scan serve both
     * readers.</b> The highlight only ever wanted the winners; drawing a number on each chest wants
     * all of them, and re-scanning for the rest would mean pricing the same menu twice a frame
     * against the same caches to reach the same answers. {@code profitable} stays a separate map
     * rather than a filter at each call site, so "is this one marked" is still one lookup.
     *
     * @param chests     slot index to the valued chest, in menu order
     * @param profitable slot index to profit, for the strictly positive ones only
     * @param bestSlot   the highest-profit slot, or {@code -1} when none turns a profit
     */
    public record Ranking(Map<Integer, DungeonChestValue.Chest> chests,
                          Map<Integer, Long> profitable, int bestSlot) {

        /** Whether anything is worth marking. Unchanged in meaning: no profitable chest. */
        public boolean isEmpty() {
            return profitable.isEmpty();
        }

        /** Whether the menu held no chest at all - the test for "say nothing here". */
        public boolean noChests() {
            return chests.isEmpty();
        }
    }

    /**
     * Scans the menu's upper container for reward chests and keeps the ones that turn a profit.
     *
     * <p>Only strictly positive chests reach {@code profitable}; every chest reaches {@code chests},
     * losers included, so a caller that wants to say "this one costs you 400k" has the number without
     * a second pass. Ties keep the first slot, so the marking cannot flicker between two equal chests
     * from frame to frame.
     *
     * @param upper number of slots belonging to the menu itself (the player inventory is excluded by
     *              the caller, since a chest can never be in there)
     */
    private static Ranking scan(AbstractContainerMenu menu, int upper) {
        Map<Integer, DungeonChestValue.Chest> chests = new LinkedHashMap<>();
        for (int i = 0; i < upper && i < menu.getItems().size(); i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            DungeonChestValue.Chest chest = DungeonChestValue.of(stack);
            if (chest != null) {
                chests.put(i, chest);
            }
        }
        Map<Integer, Long> profitable = new HashMap<>();
        int bestSlot = -1;
        for (var entry : marks(chests).entrySet()) {
            if (entry.getValue() == Mark.LOSS) {
                continue;
            }
            profitable.put(entry.getKey(), chests.get(entry.getKey()).profit());
            if (entry.getValue() == Mark.BEST) {
                bestSlot = entry.getKey();
            }
        }
        return new Ranking(Map.copyOf(chests), Map.copyOf(profitable), bestSlot);
    }

    /** What a chest is drawn as: the single best, another that profits, or one that does not. */
    public enum Mark {
        BEST, PROFIT, LOSS
    }

    /**
     * The ranking rule itself, for any set of chests - menu slots here, world positions in
     * {@link RewardChestBoard}. One rule, so the chest that is green in the menu is the chest that
     * is green in the room.
     *
     * <p>Strictly positive profit is {@code PROFIT}, the highest of those {@code BEST}; zero or less
     * is {@code LOSS}. Ties go to the first in {@code chests}' iteration order, so pass an ordered
     * map when the order must be stable from one call to the next.
     */
    public static <K> Map<K, Mark> marks(Map<K, DungeonChestValue.Chest> chests) {
        K bestKey = null;
        long best = Long.MIN_VALUE;
        Map<K, Mark> out = new LinkedHashMap<>();
        for (var entry : chests.entrySet()) {
            long profit = entry.getValue().profit();
            if (profit <= 0) {
                out.put(entry.getKey(), Mark.LOSS);
                continue;
            }
            out.put(entry.getKey(), Mark.PROFIT);
            if (profit > best) {
                best = profit;
                bestKey = entry.getKey();
            }
        }
        if (bestKey != null) {
            out.put(bestKey, Mark.BEST);
        }
        return out;
    }
}
