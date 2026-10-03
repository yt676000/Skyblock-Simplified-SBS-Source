/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

/**
 * Fires once when Cold crosses the warning threshold and re-arms once it has dropped back below it
 * (warming up at a campfire, or the reset on leaving). Pure, so the crossing rules are tested.
 */
public final class ColdAlarm {

    private boolean armed = true;

    /**
     * Feeds one reading. Returns {@code true} exactly on the reading that crosses {@code threshold}
     * while armed. An unknown reading ({@code < 0}) changes nothing - it is not a reset.
     */
    public boolean update(int cold, int threshold) {
        if (cold < 0 || threshold <= 0) {
            return false;
        }
        if (cold < threshold) {
            armed = true;
            return false;
        }
        if (armed) {
            armed = false;
            return true;
        }
        return false;
    }

    /** The threshold as a share of the cap, at least 1. */
    public static int threshold(int cap, int percent) {
        return Math.max(1, (int) Math.ceil(cap * Math.max(1, Math.min(100, percent)) / 100.0));
    }
}
