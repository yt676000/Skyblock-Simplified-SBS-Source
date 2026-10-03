/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.model;

/**
 * What a blocked interaction would have done to the item - the unit both the config toggles and the
 * player-facing message are expressed in.
 *
 * <p>{@link #UNKNOWN} is not a failure state, it is the designed answer for a screen
 * {@code DestructiveScreens} does not recognise. Hypixel rewords its menus, so the title table is
 * always somewhat out of date; treating an unrecognised menu as "possibly destructive, ask first"
 * is what keeps a stale pattern from costing an item.
 */
public enum ProtectionCategory {

    DROP("Drop", "dropped"),
    SELL("Sell", "sold"),
    SALVAGE("Salvage", "salvaged"),
    SACK("Sacks", "put into a sack"),
    AUCTION("Auction", "put up for auction"),
    CONSUME("Consume", "consumed by this menu"),
    /** A screen we do not recognise as safe. Always confirms, never hard-blocks. */
    UNKNOWN("Unknown Menus", "moved into a menu SBS does not recognise");

    private final String displayName;
    private final String verb;

    ProtectionCategory(String displayName, String verb) {
        this.displayName = displayName;
        this.verb = verb;
    }

    /** Label for the settings row of this category. */
    public String displayName() {
        return displayName;
    }

    /** Past participle used in the refusal message ("... would be sold"). */
    public String verb() {
        return verb;
    }

    /**
     * Whether this category may only ever ask, never refuse outright - regardless of the configured
     * mode.
     *
     * <p>{@link #AUCTION} because listing a protected item is a thing people do on purpose, and
     * {@link #UNKNOWN} because a hard block on a guess would refuse legitimate actions in menus that
     * are not destructive at all.
     */
    public boolean confirmOnly() {
        return this == AUCTION || this == UNKNOWN;
    }
}
