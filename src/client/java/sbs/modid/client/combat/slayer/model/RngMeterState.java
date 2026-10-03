/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.model;

/**
 * One slayer's RNG meter as last read. Mutable and Gson-friendly (it is the persisted form).
 *
 * <p>{@link #current} is exact from either source: the menu prints it in full and the chat line
 * after a boss prints the new total. {@link #goal} only ever comes from the menu, and only
 * abbreviated there ({@code 3.5M}), so it is -1 until the menu has been seen once.
 */
public final class RngMeterState {

    public long current = -1;
    public long goal = -1;
    public String selectedDrop;
    /** When the menu was last read, and when a chat line last moved the meter (epoch ms, 0 = never). */
    public long menuAt;
    public long chatAt;
    /** XP the last boss with a known gain added, -1 = none yet. Persisted so the estimate survives a restart. */
    public long lastGain = -1;
    /** Whether {@link #current} came from an abbreviated chat total ({@code 27.3K}). */
    public boolean approximate;

    /**
     * Applies the total a chat line reported and returns what this boss added, or -1 when that
     * cannot be known exactly.
     *
     * <p>A total below the previous one means the meter paid out its drop and started again, so
     * the gain is the new total itself, counted from zero. An abbreviated total ({@code 27.3K}) is
     * only good to the last digit shown: it still moves the meter, marked approximate, but no gain
     * is derived from it or from the reading after it, so the average stays exact.
     */
    public long applyChatTotal(long total, boolean exact, long now) {
        long gain;
        if (current < 0 || !exact || approximate) {
            gain = -1;
        } else if (total >= current) {
            gain = total - current;
        } else {
            gain = total;
        }
        current = total;
        approximate = !exact;
        chatAt = now;
        if (gain > 0) {
            lastGain = gain;
        }
        return gain;
    }

    /** Takes a menu reading: everything it shows replaces what was known. */
    public void applyMenu(long menuCurrent, long menuGoal, String drop, long now) {
        current = menuCurrent;
        approximate = false;
        if (menuGoal > 0) {
            goal = menuGoal;
        }
        selectedDrop = drop;
        menuAt = now;
    }

    /** Fraction filled in [0, 1], or -1 while the goal is unknown. */
    public double fraction() {
        if (goal <= 0 || current < 0) {
            return -1;
        }
        return Math.min(1.0, (double) current / goal);
    }

    /**
     * Bosses still needed at {@code averageGain} XP each - an ESTIMATE, since the gain per boss
     * depends on the tier killed. -1 when the goal or the average is unknown.
     */
    public long bossesLeft(double averageGain) {
        if (goal <= 0 || current < 0 || averageGain <= 0) {
            return -1;
        }
        long remaining = Math.max(0, goal - current);
        return (long) Math.ceil(remaining / averageGain);
    }
}
