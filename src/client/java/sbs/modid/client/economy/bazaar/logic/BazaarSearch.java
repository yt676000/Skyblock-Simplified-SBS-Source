/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.logic;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The text a {@code /bz <text>} link searches for - one rule for every place the mod offers one (the
 * order-status chat line, Best Flips, the Garden visitor buttons), so they cannot drift apart.
 *
 * <ul>
 *   <li><b>Symbols go</b> ({@code [^A-Za-z0-9 '-]}): a rune diamond or a star in a display name makes
 *       the Bazaar search match nothing at all.</li>
 *   <li><b>An enchanted book drops its tier</b> ({@code ENCHANTMENT_} ids): the Bazaar search does not
 *       know "Venomous VI"; the enchantment's own page lists every tier.</li>
 *   <li><b>Empty text means no link</b>: callers check {@link #hasQuery} and leave the link off,
 *       rather than offering one that opens an empty search.</li>
 * </ul>
 *
 * <p>From {@code docs/features/bazaar-order-message-links.md}, where the rules were first written.
 */
public final class BazaarSearch {

    private static final Pattern JUNK = Pattern.compile("[^A-Za-z0-9 '-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private BazaarSearch() {
    }

    /**
     * The search text for an item, or {@code ""} when nothing usable is left.
     *
     * @param itemId      the SkyBlock / Bazaar product id, may be {@code null} (only decides the
     *                    enchanted-book rule)
     * @param displayName what the player sees the item called
     */
    public static String query(String itemId, String displayName) {
        if (displayName == null) {
            return "";
        }
        String name = SPACES.matcher(JUNK.matcher(displayName).replaceAll(" ").trim()).replaceAll(" ");
        if (itemId != null && itemId.toUpperCase(Locale.ROOT).startsWith("ENCHANTMENT_")) {
            int space = name.lastIndexOf(' ');
            if (space > 0) {
                name = name.substring(0, space);
            }
        }
        return name;
    }

    public static boolean hasQuery(String query) {
        return query != null && !query.isBlank();
    }

    /** The command a link runs, without the leading slash: {@code bz Enchanted Hay Bale}. */
    public static String command(String query) {
        return "bz " + query;
    }
}
