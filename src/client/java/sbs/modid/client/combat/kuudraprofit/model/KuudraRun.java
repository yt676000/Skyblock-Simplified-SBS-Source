/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudraprofit.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One Kuudra run's books. Public fields so Gson reads and writes it field by field; only the player's
 * own loot and run numbers are in here. Every coin figure is the value when it was booked.
 */
public final class KuudraRun {

    /** How many item names an unpriced list keeps; the count is exact either way. */
    public static final int MAX_NAMES = 8;

    /** Wall clock, epoch millis: when the run ended (KUUDRA DOWN / DEFEAT, or the first chest). */
    public long endMs;
    /** From the run's start to its end; 0 when the start was never seen. */
    public long durationMs;
    /** 1-5, or 0 when the sidebar never said. */
    public int tier;
    /** KUUDRA DOWN; {@code false} for DEFEAT. */
    public boolean won;

    /** Priced chest loot, essence included. */
    public long income;
    /** Items without a price: the income is a floor, shown with a trailing {@code +}. */
    public int unpriced;
    public List<String> unpricedNames = new ArrayList<>();

    public int paidChests;
    public int freeChests;
    /** What the keys for the paid chests cost. */
    public long keyCost;
    /** A paid chest whose key had no price: the cost is unknown. */
    public boolean keyUnpriced;

    public String topDrop = "";
    public long topDropValue;

    public long profit() {
        return income - keyCost;
    }

    public boolean incomplete() {
        return unpriced > 0 || keyUnpriced;
    }

    public int chests() {
        return paidChests + freeChests;
    }
}
