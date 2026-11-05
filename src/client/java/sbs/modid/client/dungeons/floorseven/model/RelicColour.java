/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.model;

import java.util.Locale;

/**
 * The five Corrupted Relics of the M7 dragon phase, named by the colour word Hypixel prints in the
 * pickup line ({@code "<player> picked the Corrupted Red Relic!"}, CONFIRMED).
 *
 * <p>{@link #name()} is an id: it keys the bundled point data and the config filter, so it is never
 * renamed or reordered (root {@code AGENTS.md}, "Identity"). Every marker drawn for a relic carries
 * its {@link #label()} as text as well as its colour, so the five stay distinguishable without
 * telling red from orange or green from blue.
 */
public enum RelicColour {
    RED("Red", 0xFFFF5555),
    ORANGE("Orange", 0xFFFFAA00),
    GREEN("Green", 0xFF55FF55),
    BLUE("Blue", 0xFF55AAFF),
    PURPLE("Purple", 0xFFC060FF);

    private static final RelicColour[] VALUES = values();

    private final String label;
    private final int argb;

    RelicColour(String label, int argb) {
        this.label = label;
        this.argb = argb;
    }

    /** The colour word as Hypixel prints it ("Red"). */
    public String label() {
        return label;
    }

    /** The marker colour, opaque ARGB. */
    public int argb() {
        return argb;
    }

    /** {@code "#RRGGBB"}, the form a waypoint's colour field takes. */
    public String hex() {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    /** The relic a colour word names, case-insensitively, or {@code null}. */
    public static RelicColour byWord(String word) {
        if (word == null) {
            return null;
        }
        for (RelicColour colour : VALUES) {
            if (colour.label.equalsIgnoreCase(word.trim())) {
                return colour;
            }
        }
        return null;
    }

    /** The first relic colour whose word occurs in {@code text}, or {@code null}. */
    public static RelicColour findIn(String text) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (RelicColour colour : VALUES) {
            if (lower.contains(colour.label.toLowerCase(Locale.ROOT))) {
                return colour;
            }
        }
        return null;
    }
}
