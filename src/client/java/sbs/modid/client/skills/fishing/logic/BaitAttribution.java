/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import java.util.function.ToIntFunction;

/**
 * Which bait a cast is booked against when nothing the client can see says so - a sack-fed cast
 * changes no stack, so the choice is a rule, not an observation. Pure, for the tests.
 *
 * <p>The rule: the Bait Sack's <b>favourite</b> while the sack still holds it (Hypixel consumes the
 * favourite first), then the previous rule - the bait last seen being consumed, then the first bait
 * the sack holds. Observations always beat this: the rod's "Baits Remaining" display, a loose stack
 * shrinking and the per-bait reconcile at the next look into the sack each book what actually went.
 */
public final class BaitAttribution {

    private BaitAttribution() {
    }

    /**
     * The bait one sack-fed cast consumes, or {@code null} when the sack (as last seen) is empty.
     *
     * @param favourite     the sack's favourite, or null
     * @param lastConsumed  the bait last seen consumed, or null
     * @param sackOrder     the sack's baits in menu order
     * @param sackRemaining what the sack still holds of one bait, net of this period's estimates
     */
    public static String castBait(String favourite, String lastConsumed, Iterable<String> sackOrder,
                                  ToIntFunction<String> sackRemaining) {
        if (favourite != null && sackRemaining.applyAsInt(favourite) > 0) {
            return favourite;
        }
        if (lastConsumed != null && sackRemaining.applyAsInt(lastConsumed) > 0) {
            return lastConsumed;
        }
        for (String id : sackOrder) {
            if (sackRemaining.applyAsInt(id) > 0) {
                return id;
            }
        }
        return null;
    }
}
