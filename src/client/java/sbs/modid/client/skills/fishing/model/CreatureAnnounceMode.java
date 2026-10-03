/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.model;

/**
 * What one named sea creature does when it spawns, overriding its tier's switch.
 *
 * <p>Three states rather than a checkbox, because a checkbox cannot say the two things a player
 * actually wants from this list. Rarity is the base rule – "tell me about Legendary and up" – and
 * the exceptions run in both directions: a Titanoboa is Mythic and worth hearing about even when
 * the Mythic switch is off for a session, while a Squid stays silent even if every tier is on.
 * {@link #AUTO} is the absence of an exception, and is what every creature is until the player
 * clicks its row.
 */
public enum CreatureAnnounceMode {

    /** No exception: this creature follows its rarity's switch. */
    AUTO("Auto", "§7"),

    /** Always announce, whatever the rarity switches say. */
    ALWAYS("Always", "§a"),

    /** Never announce, whatever the rarity switches say. */
    NEVER("Never", "§c");

    private final String displayName;
    private final String code;

    CreatureAnnounceMode(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String displayName() {
        return displayName;
    }

    /** The legacy § code the selection screen paints the row's state in. */
    public String code() {
        return code;
    }

    /** The next state in the cycle – one click on a row in the selection screen. */
    public CreatureAnnounceMode next() {
        CreatureAnnounceMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
