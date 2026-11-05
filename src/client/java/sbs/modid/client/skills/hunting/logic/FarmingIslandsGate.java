/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

/**
 * The pelt hunt's "are we on the Farming Islands" decision, as a pure function.
 *
 * <p>An unknown island is <i>not</i> the Farming Islands - that used to answer yes, so every lobby
 * join and every non-SkyBlock lobby ran the hunt's scans. The one case the old fallback protected is
 * kept explicitly: a short read gap right after the Farming Islands were last seen (a tab-list or
 * scoreboard hiccup mid-chase) still counts as there, so the box does not blink out.
 */
final class FarmingIslandsGate {

    /** How long an unreadable island still counts as the Farming Islands after the last real read. */
    static final long GRACE_MS = 10_000L;

    private FarmingIslandsGate() {
    }

    /**
     * @param islandKnown     the island can be read right now
     * @param onFarming       the read island is the Farming Islands
     * @param nowMs           the current time
     * @param lastFarmingAtMs when the Farming Islands were last read, {@code 0} if never
     */
    static boolean decide(boolean islandKnown, boolean onFarming, long nowMs, long lastFarmingAtMs) {
        if (islandKnown) {
            return onFarming;
        }
        return lastFarmingAtMs != 0 && nowMs - lastFarmingAtMs <= GRACE_MS;
    }
}
