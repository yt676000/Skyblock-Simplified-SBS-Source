/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.streamer.model;

import java.util.Locale;

/**
 * What Streamer Mode does with a name: leave it, blank it, show something else instead, or blur it.
 *
 * <p>Stored in the config by {@link #name()} rather than by ordinal - an ordinal is a number whose
 * meaning changes the day a constant is inserted, and a config written by an older build would then
 * silently mean something else.
 */
public enum NameMode {

    /** The name is shown exactly as the server sent it. */
    OFF("Off"),

    /** The name is removed, leaving nothing where it was. */
    BLANK("Blank"),

    /**
     * The name is replaced by the text the player set. Labelled "Replace"; the stored constant keeps
     * its old name so existing configs still read.
     */
    CUSTOM("Replace"),

    /**
     * The name is drawn in Minecraft's obfuscated style ({@code §k}): same length, same place, but
     * unreadable. Appended last so no stored value changes meaning.
     */
    BLUR("Blur");

    private final String displayName;

    NameMode(String displayName) {
        this.displayName = displayName;
    }

    /** The label shown in the config. */
    public String displayName() {
        return displayName;
    }

    /** Whether this mode changes anything at all. */
    public boolean active() {
        return this != OFF;
    }

    /**
     * The stored value as a constant, falling back to {@link #OFF} for anything unrecognised.
     *
     * <p>Unrecognised means a config from a newer build, or one somebody edited by hand. Falling
     * back to "off" is the safe direction for exactly one of the two features on this enum and the
     * dangerous direction for the other, so this is deliberately not the whole answer: Streamer Mode
     * has its own master switch, and a player who turned that on has said what they want even if one
     * mode below it cannot be read.
     */
    public static NameMode parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return OFF;
        }
    }
}
