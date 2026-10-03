/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.rift.model;

/**
 * How much a Rift number is worth trusting.
 *
 * <p>Rift mechanics are documented far better than they are verified, and a timer built on a wiki
 * figure that turned out to be stale is worse than no timer at all - the player stops watching for
 * the thing themselves. So every hard-coded duration, threshold and drain rate carries one of these,
 * the UI shows it, and the difference between "we watched this happen" and "a wiki said so" is
 * visible at the moment the number is being relied on rather than buried in a comment.
 *
 * <p>The intended lifecycle is that values start at {@link #WIKI} and are promoted to
 * {@link #CONFIRMED} once a live client has been watched doing it, one at a time.
 */
public enum Certainty {

    /** Watched happening in game and matched to the number. Drawn normally. */
    CONFIRMED("confirmed", "§a"),

    /**
     * Taken from a public wiki and not yet verified in game. Drawn dimmed, and labelled where there
     * is room for a label - this is the state most Rift constants ship in.
     */
    WIKI("unconfirmed", "§e"),

    /** Inferred from how something behaves, with nothing to check it against. Drawn dimmest. */
    ESTIMATED("estimated", "§8"),

    /** Not known at all - the UI says so instead of showing a number. */
    UNKNOWN("unknown", "§8");

    private final String displayName;
    private final String colorCode;

    Certainty(String displayName, String colorCode) {
        this.displayName = displayName;
        this.colorCode = colorCode;
    }

    public String displayName() {
        return displayName;
    }

    /** The legacy colour code a label carrying a value of this certainty is drawn in. */
    public String colorCode() {
        return colorCode;
    }

    /** Whether a value of this certainty should be shown without a caveat. */
    public boolean trusted() {
        return this == CONFIRMED;
    }
}
