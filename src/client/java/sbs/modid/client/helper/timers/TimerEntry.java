/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers;

/**
 * One row of the event-timer card: a label, the value to show, and how sure we are of it.
 *
 * @param label     left-hand caption ("Dark Auction")
 * @param value     right-hand text, already formatted ("12m 40s", "Day 3", "Rivers of Lava")
 * @param certainty how the value was arrived at - the card marks anything that is not measured
 * @param color     ARGB for the value, or {@code 0} to colour it by certainty (the calendar rows
 *                  colour by state instead: running green, upcoming yellow)
 */
public record TimerEntry(String label, String value, Certainty certainty, int color) {

    public TimerEntry(String label, String value, Certainty certainty) {
        this(label, value, certainty, 0);
    }

    /**
     * Why the distinction matters: a countdown the player trusts and then misses is worse than no
     * countdown. Anything Hypixel did not actually state is marked, so a wrong guess reads as a
     * guess rather than as a promise.
     */
    public enum Certainty {
        /** Read straight off the client or the server's own text - as true as the game is. */
        MEASURED,
        /** Derived from a known, fixed cadence (the real-hour Dark Auction slot). */
        DERIVED,
        /** Extrapolated from a cycle whose phase we learned by watching one happen. */
        LEARNED,
        /** The cycle is known but its phase is not yet - shown as a hint, never as a number. */
        UNKNOWN
    }

    public static TimerEntry measured(String label, String value) {
        return new TimerEntry(label, value, Certainty.MEASURED);
    }

    public static TimerEntry derived(String label, String value) {
        return new TimerEntry(label, value, Certainty.DERIVED);
    }
}
