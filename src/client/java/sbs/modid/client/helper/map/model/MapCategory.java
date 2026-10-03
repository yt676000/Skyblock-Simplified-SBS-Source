/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.model;

import java.util.Locale;

/**
 * What kind of place a map entry is: what colour it gets on the map and which glyph marks it.
 *
 * <p><b>Resolved by name, never deserialised directly.</b> The island files name their category as a
 * string and {@link #of} looks it up, because a category this build has never heard of must not take
 * a whole island's map down with it - Gson maps an unknown enum constant to {@code null}, and a
 * {@code null} category would then NPE in the renderer. An unrecognised name becomes {@link #POI},
 * which is exactly the behaviour that lets a data file mention a category a later build adds.
 */
public enum MapCategory {

    /** A fast-travel arrival point. Drawn differently from a POI - it is how you get places. */
    WARP("Warp", 0x5AC8FA, "◈"),

    BANK("Bank", 0xFFD24A, "$"),
    AUCTION("Auction House", 0xFFA33F, "⚖"),
    BAZAAR("Bazaar", 0x64D98A, "⇄"),
    SHOP("Shop", 0x9BE86A, "•"),
    MUSEUM("Museum", 0xD9A8FF, "❖"),

    /** Somebody you talk to: quest givers, merchants with no shop building, trackers. */
    NPC("NPC", 0x8FD14D, "☻"),

    /** Skill hubs and gathering spots. */
    MINING("Mining", 0xB0BEC5, "⛏"),
    FARMING("Farming", 0x8BC34A, "❀"),
    FISHING("Fishing", 0x4FC3F7, "≈"),
    FORAGING("Foraging", 0x66BB6A, "♣"),

    /** Fighting: mob areas, arenas, the slayer boards. */
    COMBAT("Combat", 0xFF7043, "⚔"),
    BOSS("Boss", 0xFF4444, "☠"),
    SLAYER("Slayer", 0xE05CE0, "☠"),

    /** Dungeon entrances and the dungeon hub's own furniture. */
    DUNGEON("Dungeon", 0xA98BFF, "⌂"),

    /** A portal, lift, or any other way off this island that is not a warp command. */
    PORTAL("Portal", 0x7EE0FF, "◎"),

    /** Events and timed content: Dark Auction, Jacob's contests, the carnival. */
    EVENT("Event", 0xFFC85C, "★"),

    /** A collectable or puzzle: fairy souls, crystals, secrets. */
    SECRET("Secret", 0xFFE082, "✦"),

    /** Anything with no better home. Also the fallback for an unknown category name. */
    POI("Point of Interest", 0x9FB3C8, "▪");

    private final String displayName;
    private final int rgb;
    private final String glyph;

    MapCategory(String displayName, int rgb, String glyph) {
        this.displayName = displayName;
        this.rgb = rgb;
        this.glyph = glyph;
    }

    public String displayName() {
        return displayName;
    }

    /** Marker colour, {@code RRGGBB}. */
    public int rgb() {
        return rgb;
    }

    /** The single character drawn inside the marker on the map. */
    public String glyph() {
        return glyph;
    }

    /**
     * The category a data file's name means, or {@link #POI} when the name is blank, unknown, or a
     * category only a newer build knows. Case- and space-insensitive, so {@code "auction house"} and
     * {@code "AUCTION_HOUSE"} both land on {@link #AUCTION}.
     */
    public static MapCategory of(String name) {
        if (name == null || name.isBlank()) {
            return POI;
        }
        String key = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (MapCategory category : values()) {
            if (category.name().equals(key)) {
                return category;
            }
        }
        // A couple of spellings the data is likely to use for a category whose constant is shorter.
        return switch (key) {
            case "AUCTION_HOUSE", "AH" -> AUCTION;
            case "MERCHANT", "SHOPS" -> SHOP;
            case "MOB", "MOBS", "ARENA" -> COMBAT;
            case "QUEST", "QUESTS" -> NPC;
            case "TRAVEL", "FAST_TRAVEL" -> WARP;
            default -> POI;
        };
    }
}
