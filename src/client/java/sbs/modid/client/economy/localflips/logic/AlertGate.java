/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.logic;

import sbs.modid.client.economy.localflips.model.LocalFlip;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides which SOFORT finds earn a chat line, a sound and a toast, and makes sure each auction
 * earns them once.
 *
 * <p>The gate is the player's two thresholds: net profit at least {@code minProfit}, and safety at
 * least {@code minSafetyPct} - except for bids, which carry no safety figure and are therefore never
 * filtered on it. A flip with no net profit at all is not alerted.
 *
 * <p>The "once" memory lives as long as the client, not the connection: right after every reconnect
 * the program re-sends every still-valid find, and a restart of the flip program must not repeat the
 * alerts the player already had. The memory is bounded; the oldest ids leave first.
 *
 * <p>Client thread only.
 */
public final class AlertGate {

    /** Far more ids than the program can report in a session; bounds the memory, nothing else. */
    static final int REMEMBERED = 10_000;

    /**
     * A find older than this when it arrives is not news. It is what the program re-sends after a
     * connect or a reconnect, and alerting it would print every valid find in one burst.
     */
    public static final double STALE_SECONDS = 120;

    private final Set<String> alerted = new LinkedHashSet<>();

    /** True when the flip clears the player's thresholds. */
    public static boolean passes(LocalFlip flip, long minProfit, int minSafetyPct) {
        if (!flip.has(flip.netProfit()) || flip.netProfit() < minProfit) {
            return false;
        }
        int safety = flip.safetyPct();
        // Bids have no safety figure; the threshold does not apply to them.
        return safety < 0 || flip.kind() == LocalFlip.Kind.GEBOT || safety >= minSafetyPct;
    }

    /**
     * True only the first time an auction is asked about. Every later call - same connection or not -
     * answers false.
     */
    public boolean firstTime(String auction) {
        if (!alerted.add(auction)) {
            return false;
        }
        if (alerted.size() > REMEMBERED) {
            Iterator<String> oldest = alerted.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /**
     * The whole decision for one received SOFORT find: thresholds, freshness, then the once-only
     * memory. A stale find is remembered as alerted, so it stays quiet on later reconnects too.
     *
     * @param nowSeconds wall clock in unix seconds, the clock {@code found_at} is on
     */
    public boolean shouldAlert(LocalFlip flip, long minProfit, int minSafetyPct, double nowSeconds) {
        boolean stale = !Double.isNaN(flip.foundAt()) && nowSeconds - flip.foundAt() > STALE_SECONDS;
        if (stale) {
            alerted.add(flip.auction());
            return false;
        }
        if (!passes(flip, minProfit, minSafetyPct)) {
            return false;
        }
        return firstTime(flip.auction());
    }
}
