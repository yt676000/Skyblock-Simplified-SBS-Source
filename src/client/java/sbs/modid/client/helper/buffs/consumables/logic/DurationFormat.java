/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.logic;

/** The remaining-time text of the card and the alerts. Pure. */
public final class DurationFormat {

    private DurationFormat() {
    }

    /**
     * Two units at most, largest first: {@code 2d 5h}, {@code 16h 42m}, {@code 4m 59s}, {@code 12s};
     * {@code ?} when unknown (negative).
     */
    public static String remaining(long ms) {
        if (ms < 0) {
            return "?";
        }
        long s = ms / 1_000L;
        long d = s / 86_400L;
        long h = (s % 86_400L) / 3_600L;
        long m = (s % 3_600L) / 60L;
        long sec = s % 60L;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + sec + "s";
        }
        return sec + "s";
    }
}
