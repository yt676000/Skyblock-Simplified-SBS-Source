/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.chocolate.model;

import java.util.ArrayList;
import java.util.List;

/**
 * What one look at the Chocolate Factory menu yielded.
 *
 * <p>A class rather than a record because Gson builds it field by field - this is what gets written
 * to the per-profile file so the Time Tower and barn cards have something to say once the menu is
 * shut.
 *
 * <p><b>Every field is "as of {@link #capturedAt}", never "now".</b> The menu is the only source
 * this feature has; outside it nothing is live. Anything drawn from a snapshot says how old it is,
 * because a charge count presented as current is a confident wrong number the moment the player
 * walks away.
 *
 * <p>Unknown numbers are {@code -1}, not {@code 0}: a barn with no rabbits and a barn whose
 * capacity line could not be read are different facts and only one of them is worth a warning.
 */
public final class ChocolateSnapshot {

    /** Field value meaning "the menu did not tell us". */
    public static final long UNKNOWN = -1L;

    /** Chocolate in hand at capture time. */
    public long balance = UNKNOWN;
    /** Chocolate produced per second at capture time. */
    public double perSecond = UNKNOWN;
    /** All-time chocolate, when the menu stated it. */
    public long allTime = UNKNOWN;

    /** Every purchasable slot the menu showed, ranked or not. */
    public List<FactoryUpgrade> upgrades = new ArrayList<>();

    /** Time Tower charges ready and the maximum, or {@link #UNKNOWN}. */
    public long towerCharges = UNKNOWN;
    public long towerMaxCharges = UNKNOWN;
    /** Seconds the tower has left while running; {@code 0} when idle, {@link #UNKNOWN} unread. */
    public long towerActiveSeconds = UNKNOWN;

    /** Rabbit Barn occupancy and capacity, or {@link #UNKNOWN}. */
    public long barnRabbits = UNKNOWN;
    public long barnCapacity = UNKNOWN;

    /** When this was read, in {@code System.currentTimeMillis()} terms. */
    public long capturedAt;

    /** Gson needs a no-arg constructor. */
    public ChocolateSnapshot() {
    }

    /** Whether anything has ever been captured for this profile. */
    public boolean empty() {
        return capturedAt == 0;
    }

    /**
     * Whether the barn is at or past {@code warnAtPercent} full.
     *
     * <p>False whenever either number is unknown. A warning nobody can act on because the figures
     * behind it were never read is worse than no warning.
     */
    public boolean barnFull(int warnAtPercent) {
        if (barnRabbits < 0 || barnCapacity <= 0) {
            return false;
        }
        return barnRabbits * 100L >= barnCapacity * (long) warnAtPercent;
    }

    /** Whether a charge is ready while the tower is not running. Unknown counts as "no". */
    public boolean towerIdleWithCharge() {
        return towerCharges > 0 && towerActiveSeconds == 0;
    }
}
