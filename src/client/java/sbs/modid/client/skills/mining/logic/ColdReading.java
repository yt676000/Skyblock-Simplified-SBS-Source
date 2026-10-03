/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Glacite Cold stat out of a colour-stripped HUD line. Pure, so it is unit-tested.
 *
 * <p><b>Written against shapes, not a capture.</b> No Cold line has ever been logged (2026-10-01:
 * zero readings in ~1.9M instance log lines and the layout scans). The wiki says the sidebar shows
 * it; whether it reads {@code "Cold: -12❄"}, {@code "Cold: 12"} or {@code "❄ 12"} is unknown, so all
 * three shapes are accepted and the value is returned as a magnitude. {@code ColdTracker} logs every
 * candidate line as {@code [SBS][Cold]} so the real one can tighten this.
 */
public final class ColdReading {

    /** The snowflake Hypixel uses for frost stats (U+2744). */
    public static final char SNOWFLAKE = '❄';

    /** "Cold: -12", "Cold 12❄" - the word, then a (possibly signed) number. */
    private static final Pattern WORD = Pattern.compile("\\bcold\\b\\s*:?\\s*([-+]?\\d{1,4})",
            Pattern.CASE_INSENSITIVE);
    /** "❄ -12" or "-12❄" - the glyph right next to a number. */
    private static final Pattern GLYPH = Pattern.compile(
            "(?:" + SNOWFLAKE + "\\s*([-+]?\\d{1,4}))|(?:([-+]?\\d{1,4})\\s*" + SNOWFLAKE + ")");

    private ColdReading() {
    }

    /** Whether a line is worth logging as a capture: it says "cold" or carries the snowflake. */
    public static boolean candidate(String line) {
        return line != null && (line.toLowerCase(Locale.ROOT).contains("cold")
                || line.indexOf(SNOWFLAKE) >= 0);
    }

    /**
     * The Cold magnitude a line states, or {@code -1} when it states none. "Cold Resistance" (the
     * stat, in HotM and item lore) is never a reading.
     */
    public static int parse(String line) {
        if (line == null || line.isEmpty()) {
            return -1;
        }
        if (line.toLowerCase(Locale.ROOT).contains("cold resistance")) {
            return -1;
        }
        Matcher word = WORD.matcher(line);
        if (word.find()) {
            return magnitude(word.group(1));
        }
        Matcher glyph = GLYPH.matcher(line);
        if (glyph.find()) {
            return magnitude(glyph.group(1) != null ? glyph.group(1) : glyph.group(2));
        }
        return -1;
    }

    private static int magnitude(String number) {
        try {
            return Math.abs(Integer.parseInt(number.replace("+", "")));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
