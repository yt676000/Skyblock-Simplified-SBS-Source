/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.minions.model;

/**
 * Why a minion has stopped working, as far as the hologram over it can be read.
 *
 * <p>Three reasons rather than one, because they need different things done about them: a full
 * minion wants emptying, a blocked one wants the blocks around it clearing, and an unrecognised
 * one wants looking at. They are also coloured differently for exactly that reason.
 *
 * <p>Deliberately <b>not</b> a list of Hypixel's own wordings. The wording is
 * {@code ESTIMATED} and lives in the player's config, where one trip to the island can correct
 * it; what a wording <i>means</i> is what belongs in code.
 */
public enum MinionStopReason {

    /** Storage is full. Nothing more is produced until it is emptied. */
    FULL("Storage full", 0xFFFF6B6B),

    /** The minion cannot reach or place what it needs - blocks in the way, no room to generate. */
    BLOCKED("Blocked", 0xFFFFA33F),

    /** Something is written over the minion that none of the player's keywords matched. */
    OTHER("Stopped", 0xFFFFD65A);

    private final String displayName;
    private final int color;

    MinionStopReason(String displayName, int color) {
        this.displayName = displayName;
        this.color = color;
    }

    /** How the reason reads on the label and in the alert. */
    public String displayName() {
        return displayName;
    }

    /** ARGB for the box and the label. */
    public int color() {
        return color;
    }
}
