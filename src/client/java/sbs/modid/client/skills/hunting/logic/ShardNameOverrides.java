/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import sbs.modid.SkyblockSimplifiedSBS;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The escape hatch for shard names that do not normalise onto their own id.
 *
 * <p><b>Nearly nothing needs to be here, and that is by design.</b> The general rule - upper-case
 * the display name, collapse everything non-alphanumeric to underscores - already lands on the id
 * for nearly every shard in the catalogue, because the catalogue's own name index compares
 * letters-and-digits-only in lower case. Punctuation, capitalisation and spacing therefore cannot
 * cause a miss on their own. What this table is for is the case the general rule cannot reach: a
 * display name whose <i>words</i> differ from its id's.
 *
 * <p><b>Every entry is evidenced.</b> Each one was read off a live Hunting Box and Attribute Menu
 * (2026-10-19): the in-game name on the left, and on the right the Bazaar product that shard
 * still trades under, the only catalogue entry left unclaimed for it. Writing speculative entries
 * would be worse than having none: an override that maps a correctly-resolving name onto the wrong
 * shard is silent, permanent and points the player at a shard they do not need. {@link #noteUnmatched} logs,
 * once per name, every display name that reached a shard-shaped screen and resolved to nothing -
 * which is the evidence an entry is written from.
 *
 * <p><b>Adding one</b> is a single line in {@link #OVERRIDES}: the display name as Hypixel writes it
 * on the left, the catalogue's own {@code name} on the right. Both are normalised here, so the
 * spelling on the left only has to be right about the words.
 */
public final class ShardNameOverrides {

    /**
     * Display name (as seen) to catalogue name (as filed), keyed by {@link #normalise}.
     *
     * <p>Hypixel renamed these in game and kept the old Bazaar product, so the name normalises onto
     * a product that does not exist and the shard both drops out of the catalogue and prices as
     * unknown. See the class comment for why an unverified entry is worse than a missing one.
     */
    private static final Map<String, String> OVERRIDES = Map.of(
            normalise("Zealot Bruiser"), "BRUISER",
            normalise("Abyssal Lanternfish"), "ABYSSAL_LANTERN",
            normalise("Wither Spectre"), "WITHER_SPECTER",
            normalise("Loch Emperor"), "SEA_EMPEROR",
            normalise("Packrat"), "PACKRAT_SKULL");

    /** Names already logged as unmatched, so one broken name is one line rather than one per tick. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** A cap, so a screen full of unreadable entries cannot fill the log file. */
    private static final int LOG_LIMIT = 40;

    private ShardNameOverrides() {
    }

    /**
     * The catalogue name a display name should be read as, or {@code null} when no override applies
     * and the general rule stands.
     */
    public static String canonicalNameFor(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        return OVERRIDES.get(normalise(displayName));
    }

    /**
     * Records that a name on a shard-shaped screen resolved to nothing.
     *
     * <p>At {@code info}, deliberately: {@code debug} is off in every instance anybody plays on, and
     * a feature built on unverified wording whose only diagnostic is switched off has no diagnostic.
     * That exact mistake cost this feature a release - see {@code docs/issues/skills.md}.
     */
    public static void noteUnmatched(String displayName, String context) {
        if (displayName == null || displayName.isBlank() || LOGGED.size() >= LOG_LIMIT) {
            return;
        }
        if (LOGGED.add(normalise(displayName))) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Shards] no catalogue entry for \"{}\" seen in {} - add an override if it "
                            + "is a shard", displayName, context);
        }
    }

    /** Forgets what has been logged, so a fresh session reports afresh. */
    public static void reset() {
        LOGGED.clear();
    }

    /** Letters and digits only, lower case - the same key the catalogue's name index uses. */
    private static String normalise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
