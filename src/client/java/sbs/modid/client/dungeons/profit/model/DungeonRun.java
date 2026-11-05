/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One dungeon run's books: what came in, what went out. A class with public fields so Gson can read
 * and write it field by field; only the player's own items and run numbers are in here - never a
 * teammate's name.
 *
 * <p>Every coin figure is the value at the moment it was booked, so the history does not drift with
 * the market.
 */
public final class DungeonRun {

    /** How many item names an unpriced list keeps; the count is exact either way. */
    public static final int MAX_NAMES = 8;

    /** Wall clock, epoch millis. {@code endMs} is 0 while the run is open. */
    public long startMs;
    public long endMs;
    /** {@code "F7"}, {@code "M3"}, {@code "E"}, or {@code "?"} when the scoreboard never said. */
    public String floor = "?";
    /** From {@code Team Score: N (S+)}; -1 when the summary was not seen. */
    public int score = -1;
    public String grade = "";

    /** Priced loot: chest items, essence, rare drops. */
    public long income;
    /** Items booked without a price: the income is a floor, shown with a trailing {@code +}. */
    public int unpriced;
    public List<String> unpricedNames = new ArrayList<>();

    /** Coins paid to open chests, as their preview menu priced them. */
    public long chestCost;
    public int chests;
    /** A paid chest whose price was never seen: the cost is a floor. */
    public boolean costUnknown;
    public int kismets;
    public long kismetCost;
    public boolean kismetUnpriced;
    /** Dungeon Potions drunk before the run started, and what they cost. */
    public int potions;
    public long potionCost;
    public boolean potionUnpriced;
    /** The player's own per-run cost setting, as it was when the run finished. */
    public long userCost;

    /** Chests claimed from Croesus after the run and booked onto it. */
    public int croesusClaims;
    public List<String> rareDrops = new ArrayList<>();
    public String topDrop = "";
    public long topDropValue;
    /**
     * A Croesus claim no stored run could take. Counted in coin totals, never as a run, so a claim
     * cannot make the run count or the per-run average lie.
     */
    public boolean claimOnly;

    public long profit() {
        return income - cost();
    }

    /** Every cost line together: chests, Kismet Feathers, potions, the player's own extra cost. */
    public long cost() {
        return chestCost + kismetCost + potionCost + userCost;
    }

    /** Whether something in here had no price, so the profit is a lower or upper bound. */
    public boolean incomplete() {
        return unpriced > 0 || costUnknown || kismetUnpriced || potionUnpriced;
    }

    public long durationMs() {
        return endMs > startMs ? endMs - startMs : 0L;
    }

    public boolean finished() {
        return endMs > 0;
    }

    /** Whether anything was booked; a run entered and left with nothing is not kept. */
    public boolean empty() {
        return income == 0 && unpriced == 0 && chests == 0 && kismets == 0 && potions == 0 && score < 0;
    }
}
