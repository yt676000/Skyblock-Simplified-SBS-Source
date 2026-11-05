/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.dice.model;

/**
 * The two Archfiend dice and what the wiki says about them. Costs and payouts are WIKI certainty
 * until the probe in {@code DiceTracker} has measured them against the purse; see
 * {@code docs/features/dice-profit.md}.
 */
public enum DiceKind {
    // Wiki: "666.7k" per roll; 666,666 or 666,667 is not settled yet.
    ARCHFIEND("Archfiend Dice", "ARCHFIEND_DICE", 666_666L, 15_000_000L),
    HIGH_CLASS("High Class Dice", "HIGH_CLASS_ARCHFIEND_DICE", 6_666_666L, 100_000_000L);

    /** The dye a 7 gives, as the item catalogue names it (not {@code ARCHFIEND_DYE}). */
    public static final String DYE_ID = "DYE_ARCHFIEND";

    private final String label;
    private final String itemId;
    private final long rollCost;
    private final long sixPayout;

    DiceKind(String label, String itemId, long rollCost, long sixPayout) {
        this.label = label;
        this.itemId = itemId;
        this.rollCost = rollCost;
        this.sixPayout = sixPayout;
    }

    public String label() {
        return label;
    }

    public String itemId() {
        return itemId;
    }

    public long rollCost() {
        return rollCost;
    }

    public long sixPayout() {
        return sixPayout;
    }
}
