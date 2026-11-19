/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.bestiary;

import java.util.function.LongFunction;

/**
 * One pinned mob's progress as the HUD shows it, and the arithmetic behind the row:
 * {@code "Zombie · Tier 12 · 1,234 / 1,500 (82%) · 8,766 to max"}.
 *
 * <p>The middle pair is the progress inside the current tier when it is known (menu lore or the tab
 * widget), else the overall pair; "to max" always comes from the overall total. Pure, so the maths
 * is tested without a game.
 *
 * @param tier       0 when unknown
 * @param tierKills  -1 when unknown
 * @param tierNeeded -1 when unknown
 * @param kills      overall kills, -1 when the mob was never read
 * @param maxKills   overall kills to max, -1 when unknown
 */
public record BestiaryProgress(String name, int tier, long tierKills, long tierNeeded,
                               long kills, long maxKills, boolean maxed) {

    public boolean loaded() {
        return kills >= 0 || maxed;
    }

    public boolean isMaxed() {
        return maxed || (maxKills > 0 && kills >= maxKills);
    }

    /** Kills left to max; 0 when maxed, -1 when the max is unknown. */
    public long toMax() {
        if (isMaxed()) {
            return 0;
        }
        return maxKills < 0 || kills < 0 ? -1 : Math.max(0, maxKills - kills);
    }

    /** The pair the row shows: tier progress when known, else the overall one. {@code null} if neither. */
    public long[] pair() {
        if (tierKills >= 0 && tierNeeded > 0) {
            return new long[] {tierKills, tierNeeded};
        }
        if (kills >= 0 && maxKills > 0) {
            return new long[] {kills, maxKills};
        }
        return null;
    }

    /** Whole percent of {@link #pair()}, floored and capped at 100; -1 without a pair. */
    public int percent() {
        long[] pair = pair();
        if (pair == null) {
            return -1;
        }
        return (int) Math.min(100, pair[0] * 100 / pair[1]);
    }

    /** The HUD row text, numbers formatted by {@code fmt}. */
    public String text(LongFunction<String> fmt) {
        StringBuilder out = new StringBuilder(name);
        if (tier > 0) {
            out.append(" · Tier ").append(tier);
        }
        if (!loaded()) {
            return out.append(" · open the Bestiary").toString();
        }
        if (isMaxed()) {
            return out.append(" · MAX").toString();
        }
        long[] pair = pair();
        if (pair != null) {
            out.append(" · ").append(fmt.apply(pair[0])).append(" / ").append(fmt.apply(pair[1]))
                    .append(" (").append(percent()).append("%)");
        } else {
            out.append(" · ").append(fmt.apply(kills)).append(" kills");
        }
        long left = toMax();
        if (left > 0) {
            out.append(" · ").append(fmt.apply(left)).append(" to max");
        }
        return out.toString();
    }
}
