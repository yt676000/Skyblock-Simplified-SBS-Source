/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.trophyfish.logic;

import sbs.modid.client.skills.trophyfish.model.TrophyTier;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one fish's per-tier counts out of its lore in Odger's Trophy Fishing menu.
 *
 * <p><b>ASSUMED, not verified.</b> Nobody has captured this menu. The rule is kept deliberately
 * loose - a lore line that <i>starts</i> with a tier name, then either a number (the count), a tick
 * (caught, count not shown) or a cross (not caught) - and deliberately strict about what it records:
 * a fish is only returned when all four tiers were read. A half-read fish would overwrite real counts
 * with zeros, which is worse than not syncing. The tracker logs every fish it could not read, so the
 * first visit in game shows the real lore.
 */
public final class TrophyMenuParser {

    /** A tier line whose count is not printed - a tick with no number. */
    public static final int CAUGHT_UNKNOWN_COUNT = -1;

    private static final Pattern TIER_LINE = Pattern.compile(
            "^(bronze|silver|gold|diamond)\\b[^0-9✔✓✖✘✗x]*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*)");

    private TrophyMenuParser() {
    }

    /** Whether a normalised (lower-case, stripped) menu title is the Trophy Fishing menu. */
    public static boolean isTrophyMenu(String normalisedTitle) {
        return normalisedTitle != null && normalisedTitle.contains("trophy fish");
    }

    /**
     * The four counts ({@link TrophyTier} order) in {@code lore}, or {@code null} unless every tier
     * was read. {@link #CAUGHT_UNKNOWN_COUNT} marks a tier shown as caught without a number.
     *
     * @param lore plain lore lines, colour codes already removed
     */
    public static int[] counts(List<String> lore) {
        if (lore == null) {
            return null;
        }
        int[] counts = new int[TrophyTier.values().length];
        boolean[] seen = new boolean[counts.length];
        for (String raw : lore) {
            Matcher m = TIER_LINE.matcher(raw.trim());
            if (!m.matches()) {
                continue;
            }
            TrophyTier tier = TrophyTier.byWord(m.group(1));
            Integer value = value(m.group(2).trim());
            if (tier == null || value == null || seen[tier.ordinal()]) {
                continue;   // first line per tier wins; a later "Gold" mention is not the count
            }
            counts[tier.ordinal()] = value;
            seen[tier.ordinal()] = true;
        }
        for (boolean s : seen) {
            if (!s) {
                return null;
            }
        }
        return counts;
    }

    private static Integer value(String rest) {
        Matcher number = NUMBER.matcher(rest);
        if (number.find()) {
            try {
                return Integer.parseInt(number.group(1).replace(",", ""));
            } catch (NumberFormatException tooBig) {
                return null;
            }
        }
        String lower = rest.toLowerCase(Locale.ROOT);
        if (rest.contains("✔") || rest.contains("✓")) {
            return CAUGHT_UNKNOWN_COUNT;
        }
        if (rest.contains("✖") || rest.contains("✘") || rest.contains("✗")
                || lower.startsWith("x") || lower.contains("not caught")) {
            return 0;
        }
        return null;
    }
}
