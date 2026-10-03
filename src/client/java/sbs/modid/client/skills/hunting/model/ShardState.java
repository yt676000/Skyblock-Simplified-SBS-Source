/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

/**
 * Where one shard stands in the Attribute Menu, as four states rather than two.
 *
 * <p><b>{@link #UNKNOWN} is the point of this enum.</b> The menu's wording has never been read off a
 * live client, so an entry whose lore no reader could parse is a thing we do not know rather than a
 * thing the player is missing. Collapsing it into {@link #MISSING} would send somebody shopping for
 * shards they already hold, which is the one failure this feature must not have; collapsing it into
 * {@link #OWNED} would hide a real gap. It is therefore its own state, excluded from the missing
 * list and counted in the footer, exactly as {@code AccessoryProgress} keeps its third state instead
 * of dropping it.
 *
 * <p>{@link #PARTIAL} exists for the same reason in the other direction: a shard collected halfway
 * is still a gap, and the amount left is the whole answer under the attribute-progress reading of
 * the feature. See {@code docs/features/missing-shards-list.md}.
 */
public enum ShardState {

    /** The attribute this shard feeds is finished - nothing left to collect. */
    OWNED("Owned"),

    /** Some of it collected, below the maximum. Still a gap, just a smaller one. */
    PARTIAL("Partial"),

    /** None of it collected. */
    MISSING("Missing"),

    /** The entry was found but nothing in it could be read. Never treated as a gap. */
    UNKNOWN("Not read");

    private final String displayName;

    ShardState(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether this state belongs on a "what do I still need" list. */
    public boolean gap() {
        return this == MISSING || this == PARTIAL;
    }

    /** The stored name, tolerating anything an older or newer build wrote. */
    public static ShardState byName(String name) {
        if (name != null) {
            for (ShardState state : values()) {
                if (state.name().equalsIgnoreCase(name)) {
                    return state;
                }
            }
        }
        return UNKNOWN;
    }
}
