/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.logic;

/**
 * The Voidgloom Seraph's "Hits" shield, read off its nametag: while it is up the health readout is
 * replaced by the number of hits the shield still takes ({@code "☠ Voidgloom Seraph 34 Hits"}), and
 * the boss cannot die.
 *
 * <p>The one parser for that tag – the slayer helpers read the count from it and the carry counter
 * reads "is the shield up" from it. Both run on the 250 ms entity scan, so neither allocates a copy
 * of the tag.
 */
public final class VoidgloomShield {

    private VoidgloomShield() {
    }

    /** Whether a colour-stripped tag mentions "hits" at all – the shield is up. */
    public static boolean isShieldTag(String stripped) {
        return stripped != null && indexOfHits(stripped) >= 0;
    }

    /**
     * The hits the shield still takes, from a colour-stripped tag – the number right before
     * "Hits", thousands separators allowed. {@code -1} when the tag carries no such count.
     */
    public static int parseHits(String stripped) {
        if (stripped == null) {
            return -1;
        }
        int at = indexOfHits(stripped);
        if (at < 0) {
            return -1;
        }
        int end = at;
        while (end > 0 && stripped.charAt(end - 1) == ' ') {
            end--;
        }
        int start = end;
        while (start > 0 && (isDigit(stripped.charAt(start - 1)) || stripped.charAt(start - 1) == ',')) {
            start--;
        }
        int value = 0;
        boolean any = false;
        for (int i = start; i < end; i++) {
            char c = stripped.charAt(i);
            if (isDigit(c)) {
                value = value * 10 + (c - '0');
                any = true;
                if (value > 1_000_000) {
                    return -1;
                }
            }
        }
        return any ? value : -1;
    }

    /**
     * Position of "hits" in the text, any case; {@code -1} when absent. Char-wise case folding, so
     * the skull glyph in front of the name costs no lower-cased copy.
     */
    private static int indexOfHits(String text) {
        for (int i = 0; i + 4 <= text.length(); i++) {
            if (text.regionMatches(true, i, "hits", 0, 4)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
