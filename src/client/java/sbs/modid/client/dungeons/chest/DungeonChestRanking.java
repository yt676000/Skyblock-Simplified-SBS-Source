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
        Map<Integer, DungeonChestValue.Chest> chests = new HashMap<>();
        Map<Integer, Long> profitable = new HashMap<>();
        int bestSlot = -1;
        long best = Long.MIN_VALUE;
        for (int i = 0; i < upper && i < menu.getItems().size(); i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            DungeonChestValue.Chest chest = DungeonChestValue.of(stack);
            if (chest == null) {
                continue;
            }
            chests.put(i, chest);
            long profit = chest.profit();
            if (profit <= 0) {
                continue;
            }
            profitable.put(i, profit);
            if (profit > best) {
                best = profit;
                bestSlot = i;
            }
        }
        return new Ranking(Map.copyOf(chests), Map.copyOf(profitable), bestSlot);
    }
}
