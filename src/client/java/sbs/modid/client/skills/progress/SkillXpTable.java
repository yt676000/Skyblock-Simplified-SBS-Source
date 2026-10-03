/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.progress;

/**
 * Hypixel SkyBlock's skill XP requirements, and the trick that turns them into a level number.
 *
 * <p>The action bar reports a gain as {@code "+40 Combat (312,540/1,000,000)"}: progress into the
 * current level, and the XP that level requires. It never states the level itself. But the
 * requirement table is <b>strictly increasing</b>, so every requirement value belongs to exactly one
 * level – which means the level can be recovered from the denominator alone, with no API call and no
 * profile lookup. {@code 1,000,000} can only be level 27's requirement, so the player is level 27
 * working toward 28.
 *
 * <p>If a requirement is not in the table (Runecrafting and Social use their own curves, and Hypixel
 * may add tiers), {@link #levelFor} returns {@link #UNKNOWN_LEVEL} and the overlay simply omits the
 * level while still showing XP, rate and ETA – nothing else degrades.
 */
public final class SkillXpTable {

    /** Returned by {@link #levelFor} when the requirement matches no known level. */
    public static final int UNKNOWN_LEVEL = -1;

    /**
     * {@code XP_TO_NEXT[n]} = XP required to advance from level {@code n} to {@code n + 1}.
     * Index 0 is the 50 XP from level 0 to 1; the last entry is level 59 → 60.
     */
    private static final long[] XP_TO_NEXT = {
            50, 125, 200, 300, 500, 750, 1_000, 1_500, 2_000, 3_500,
            5_000, 7_500, 10_000, 15_000, 20_000, 30_000, 50_000, 75_000, 100_000, 200_000,
            300_000, 400_000, 500_000, 600_000, 700_000, 800_000, 900_000, 1_000_000, 1_100_000, 1_200_000,
            1_300_000, 1_400_000, 1_500_000, 1_600_000, 1_700_000, 1_800_000, 1_900_000, 2_000_000, 2_100_000, 2_200_000,
            2_300_000, 2_400_000, 2_500_000, 2_600_000, 2_750_000, 2_900_000, 3_100_000, 3_400_000, 3_700_000, 4_000_000,
            4_300_000, 4_600_000, 4_900_000, 5_200_000, 5_500_000, 5_800_000, 6_100_000, 6_400_000, 6_700_000, 7_000_000};

    private SkillXpTable() {
    }

    /**
     * The level whose XP requirement is {@code requirement}, i.e. the level the player is currently
     * on, or {@link #UNKNOWN_LEVEL} if no level requires exactly that much.
     *
     * @param requirement the denominator from the action bar
     */
    public static int levelFor(double requirement) {
        long needle = Math.round(requirement);
        for (int level = 0; level < XP_TO_NEXT.length; level++) {
            if (XP_TO_NEXT[level] == needle) {
                return level;
            }
        }
        return UNKNOWN_LEVEL;
    }

    /** The highest level this table describes (i.e. the last reachable level). */
    public static int maxLevel() {
        return XP_TO_NEXT.length;
    }
}
