/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.model;

import java.util.ArrayList;
import java.util.List;

/**
 * How the missing-shard list is ordered. Labels stay short: they are drawn as segments, side by side.
 *
 * <p>{@link #COST} rather than {@link #PRICE} is the one that answers "what should I buy next" - a
 * cheap shard you need ninety-six of is not a cheap shard. Both are offered because the player asked
 * for price sorting by name, and because the unit price is what the Bazaar quotes back at them.
 *
 * <p>Persisted by {@link #name()} through {@link #byName}, never by ordinal: reordering or renaming a
 * constant must not silently resolve a stored preference to a different one.
 */
public enum ShardSort {

    /** Instant-buy (or buy-order) price for one shard. */
    PRICE("Price"),

    /** Price times the amount still needed - what closing this one gap costs. */
    COST("Cost"),

    NAME("Name"),

    RARITY("Rarity"),

    /** How many shards are still to collect. */
    AMOUNT("Need");

    private final String displayName;

    ShardSort(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** The labels in declaration order, for a segmented switch. */
    public static List<String> labels() {
        List<String> out = new ArrayList<>(values().length);
        for (ShardSort sort : values()) {
            out.add(sort.displayName());
        }
        return out;
    }

    /** The persisted name, tolerating anything an older or newer build wrote. */
    public static ShardSort byName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException | NullPointerException bad) {
            return PRICE;
        }
    }
}
