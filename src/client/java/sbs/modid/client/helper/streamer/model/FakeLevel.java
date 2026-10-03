/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.streamer.model;

import java.util.regex.Pattern;

/**
 * The SkyBlock level Streamer Mode draws in front of your own name - "[312]" - coloured the way
 * Hypixel colours it: the colour steps up every 40 levels, so a fake level looks like a real one of
 * that height rather than one colour for everything.
 *
 * <p><b>ESTIMATED, all of it.</b> Hypixel sends the level's colour as a component style, not as a
 * {@code §} code, so the play-instance chat logs (which keep only the codes) show every level as a
 * bare "[376]" and cannot confirm a single colour here. The table is the standard Hypixel ladder;
 * one wrong step is one wrong line in {@link #COLOURS}.
 */
public final class FakeLevel {

    /** Highest level the setting accepts; Hypixel's current cap is below this. */
    public static final int MAX = 999;

    /** Colour code per 40-level step, from level 0 up. The last one covers everything above. */
    private static final String[] COLOURS =
            {"7", "f", "e", "a", "2", "b", "3", "9", "d", "5", "6", "c", "4"};

    /**
     * The real level bracket, its space, and an optional emblem plus space, anchored to the end of
     * the text before the rank bracket (or the name). Group 1 is the bracket alone - only that is
     * replaced, so the emblem keeps its own colour.
     */
    public static final Pattern REAL_LEVEL = Pattern.compile("(\\[\\d{1,3}\\]) (?:\\S{1,2} )?$");

    /** Longest {@link #REAL_LEVEL} can be: "[999] " plus a two-char emblem and its space. */
    public static final int LOOKBACK = 9;

    private FakeLevel() {
    }

    /** The colour code for {@code level}. */
    public static String colour(int level) {
        int step = Math.max(0, level) / 40;
        return COLOURS[Math.min(step, COLOURS.length - 1)];
    }

    /** The bracket as drawn: dark-grey brackets around the level in its step colour. */
    public static String render(int level) {
        int clamped = Math.max(0, Math.min(MAX, level));
        return "§8[§" + colour(clamped) + clamped + "§8]";
    }
}
