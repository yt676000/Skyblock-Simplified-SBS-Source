/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.logic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The counts behind the Diana Profit card, and the maths over them. Pure and Gson-friendly: the
 * all-time copy is the profile file.
 *
 * <h2>Counted, not valued</h2>
 *
 * <p>Items are stored as counts and priced when the card is built, so the total follows the market
 * rather than freezing the price of the day each feather dropped - the same argument the Dice Profit
 * ledger makes. Coins are coins and are stored as such.
 *
 * <h2>Active time</h2>
 *
 * <p>An hour is an hour spent digging. Every burrow, creature and drop is an activity; the gap since
 * the previous one is added to {@link #activeMs} only if it is at most {@link #IDLE_MS}. A longer gap
 * - a break, a dungeon run, an evening off between two sessions of the all-time ledger - adds
 * nothing at all, rather than its first three minutes: the first three minutes of a break are not
 * digging either.
 */
public final class DianaLootLedger {

    /** A gap between two activities longer than this is a break and counts for nothing. */
    public static final long IDLE_MS = 3 * 60 * 1000L;

    /** Below this much active time a per-hour figure is noise, and is reported as zero. */
    public static final long MIN_RATE_MS = 60 * 1000L;

    private static final long HOUR_MS = 60 * 60 * 1000L;

    /** Price id -> how many. Discovery order. Coins are not in here. */
    public Map<String, Long> items = new LinkedHashMap<>();

    /** Price id -> the name the drop line used, for ids the catalogue does not know. */
    public Map<String, String> names = new LinkedHashMap<>();

    public long coins;
    public long burrows;
    public long mobs;
    public long activeMs;
    public long lastActivityAt;

    /** One activity at {@code now}: adds the gap since the last if it was not a break. */
    public void activity(long now) {
        if (lastActivityAt > 0L) {
            long gap = now - lastActivityAt;
            if (gap > 0L && gap <= IDLE_MS) {
                activeMs += gap;
            }
        }
        if (now > lastActivityAt) {
            lastActivityAt = now;
        }
    }

    public void burrow(long now) {
        burrows++;
        activity(now);
    }

    public void mob(long now) {
        mobs++;
        activity(now);
    }

    /** One payout off {@link DianaLoot#parse}. */
    public void loot(DianaLoot.Loot loot, long now) {
        if (loot == null || loot.count() <= 0) {
            return;
        }
        if (loot.source() == DianaLoot.Source.COINS) {
            coins += loot.count();
        } else {
            items().merge(loot.id(), loot.count(), Long::sum);
            if (DianaLoot.byId(loot.id()) == null) {
                names().putIfAbsent(loot.id(), loot.name());
            }
        }
        activity(now);
    }

    /** Whether anything at all has been earned. */
    public boolean earned() {
        return coins > 0 || !items().isEmpty();
    }

    /**
     * Everything earned, priced through {@code price} (an id to coins each, or {@code null} for
     * unpriced, which counts as zero), less the custom cost for the active time.
     */
    public long profit(Function<String, Long> price, boolean countCoins, long costPerHour) {
        long total = countCoins ? coins : 0L;
        for (Map.Entry<String, Long> entry : items().entrySet()) {
            Long each = price.apply(entry.getKey());
            if (each != null && each > 0) {
                total += each * entry.getValue();
            }
        }
        if (costPerHour > 0) {
            total -= Math.round((double) costPerHour * activeMs / HOUR_MS);
        }
        return total;
    }

    /** {@code value} over {@code activeMs}, per hour; zero below {@link #MIN_RATE_MS}. */
    public static long perHour(long value, long activeMs) {
        return activeMs < MIN_RATE_MS ? 0L : Math.round((double) value * HOUR_MS / activeMs);
    }

    /** {@code value} per burrow dug; zero before the first. */
    public static long perBurrow(long value, long burrows) {
        return burrows <= 0 ? 0L : Math.round((double) value / burrows);
    }

    /** The display name for a price id. */
    public String nameOf(String id) {
        DianaLoot.Item item = DianaLoot.byId(id);
        if (item != null) {
            return item.displayName();
        }
        String name = names().get(id);
        return name != null ? name : id;
    }

    /** The item counts, never {@code null} - an older or hand-edited file may lack the field. */
    public Map<String, Long> items() {
        if (items == null) {
            items = new LinkedHashMap<>();
        }
        return items;
    }

    private Map<String, String> names() {
        if (names == null) {
            names = new LinkedHashMap<>();
        }
        return names;
    }
}
