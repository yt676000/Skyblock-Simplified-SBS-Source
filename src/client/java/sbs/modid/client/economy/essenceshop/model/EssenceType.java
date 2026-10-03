/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop.model;

import java.util.Locale;

/**
 * One essence currency - the id the perk table, the item registry and the Bazaar all agree on
 * ({@code ESSENCE_WITHER}), plus the two spellings the player sees.
 *
 * <p><b>Deliberately not an enum.</b> Three sources in this repository already disagree about which
 * essences exist: the perk table has eleven and lists Safari, the star-cost table names nine and has
 * no Fossil, and our bundled item map has {@code ESSENCE_SUN} but no Safari. An enum would be a
 * hard-coded snapshot of that disagreement and would be wrong the day Hypixel adds the next one, so
 * the type set comes from the data at runtime and an id nobody knows is simply not a shop.
 */
public record EssenceType(String id, String shortName) {

    private static final String PREFIX = "ESSENCE_";

    /**
     * The type an item / perk-table id names, or {@code null} when it names none.
     *
     * <p>Being an {@code ESSENCE_*} id is <b>not</b> on its own enough to be a shop currency -
     * {@code ESSENCE_SHOP} is a menu icon, not an essence. Callers pair this with a lookup in the
     * perk table, which is the only list that decides what a shop is.
     */
    public static EssenceType fromId(String rawId) {
        if (rawId == null) {
            return null;
        }
        String id = rawId.trim().toUpperCase(Locale.ROOT);
        if (!id.startsWith(PREFIX)) {
            return null;
        }
        String body = id.substring(PREFIX.length());
        if (body.isEmpty()) {
            return null;
        }
        return new EssenceType(id, pretty(body));
    }

    /** What the game calls it: {@code "Wither Essence"}. */
    public String displayName() {
        return shortName + " Essence";
    }

    /**
     * The {@code /bz} search string. Hypixel's Bazaar search matches <b>names</b>, so the product id
     * this type is keyed on everywhere else is the wrong string to send.
     */
    public String bazaarQuery() {
        return displayName().toLowerCase(Locale.ROOT);
    }

    /** {@code "WITHER"} → {@code "Wither"}, {@code "SEA_CREATURE"} → {@code "Sea Creature"}. */
    private static String pretty(String body) {
        StringBuilder out = new StringBuilder(body.length());
        for (String word : body.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
