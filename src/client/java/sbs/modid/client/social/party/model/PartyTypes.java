/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The party-type catalogue shared by the finder sidebar and the create dialog: server keys,
 * display labels and the option lists of the type-specific fields (fishing locations, dungeon
 * floors, Kuudra tiers, Diana modes).
 */
public final class PartyTypes {

    /** Server keys in sidebar order ("" = All in the finder; create skips it). */
    public static final List<String> KEYS =
            List.of("diana", "fishing", "mining", "combat", "dungeons",
                    "kuudra", "kuudra_pieces", "custom");

    /** Display names for keys the simple capitalizer can't render nicely. */
    private static final Map<String, String> LABELS = Map.of("kuudra_pieces", "Kuudra Pieces");

    /** The Kuudra armor pieces selectable in the "Kuudra Pieces" party type. */
    public static final String[] KUUDRA_PIECES = {"Helmet", "Chestplate", "Leggings", "Boots"};

    public static final String[] FISHING_LOCATIONS = {
            "Any", "Hotspot", "Backwater Bayou", "Isle", "Crystal Hollows", "Galatea", "Lotus Atoll"};

    /** Dungeon floors; index 0 = Any. Keys match the server's cata_time:<floor> stats (f7/m6...). */
    public static final String[] FLOORS = {
            "Any", "F1", "F2", "F3", "F4", "F5", "F6", "F7",
            "M1", "M2", "M3", "M4", "M5", "M6", "M7"};

    public static final String[] KUUDRA_TIERS = {"Basic", "Hot", "Burning", "Fiery", "Infernal"};
    /** The profile stat suffixes matching {@link #KUUDRA_TIERS} (kuudra_<key>). */
    public static final String[] KUUDRA_TIER_KEYS = {"none", "hot", "burning", "fiery", "infernal"};

    public static final String[] GRIFFIN = {"Any", "Legendary", "Mythic"};
    public static final String[] MODES = {"Everyone kills own", "One kills"};

    private PartyTypes() {
    }

    public static String label(String key) {
        if (key == null || key.isEmpty()) {
            return "All";
        }
        String custom = LABELS.get(key);
        if (custom != null) {
            return custom;
        }
        return Character.toUpperCase(key.charAt(0)) + key.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The next create-dialog type after {@code key} (wraps; unknown keys restart at the first). */
    public static String next(String key) {
        int index = KEYS.indexOf(key);
        return KEYS.get((index + 1) % KEYS.size());
    }
}
