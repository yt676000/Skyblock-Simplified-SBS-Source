/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.playerviewer.logic;

import java.util.Map;

/**
 * Stable ids of the independently validated sections of a profile view. A section whose data fails
 * validation, or whose inventory blob fails to decode, is listed under the view's
 * {@code unavailable} array and its page shows a neutral "unavailable" message.
 *
 * <p>These ids are also the keys pages are registered with in the navigation, so they are never
 * renamed.
 */
public final class PvSections {

    public static final String LEVELING = "leveling";
    public static final String ACCESSORIES = "accessories";
    public static final String SKILLS = "skills";
    public static final String DUNGEONS = "dungeons";
    public static final String SLAYER = "slayer";
    public static final String MINING = "mining";
    public static final String STATS = "stats";
    public static final String DIANA = "diana";
    public static final String BESTIARY = "bestiary";
    public static final String CRIMSON = "crimson";
    public static final String PETS = "pets";
    public static final String INVENTORY = "inventory";
    public static final String COLLECTIONS = "collections";
    public static final String FORAGING = "foraging";
    public static final String FISHING = "fishing";
    public static final String FARMING = "farming";
    public static final String HOPPITY = "hoppity";
    public static final String RIFT = "rift";
    public static final String LOADOUTS = "loadouts";
    public static final String MUSEUM = "museum";
    public static final String LOCATION = "location";
    public static final String SKILL_TREE = "skill_tree";

    /** View keys that belong to a section: they are left out of the view when it is unavailable. */
    public static final Map<String, String> VIEW_KEY_SECTION = Map.ofEntries(
            Map.entry("dungeons", DUNGEONS), Map.entry("catacombs_bar", DUNGEONS),
            Map.entry("classes", DUNGEONS), Map.entry("slayer_detail", SLAYER),
            Map.entry("slayers", SLAYER), Map.entry("diana", DIANA), Map.entry("crimson", CRIMSON),
            Map.entry("kuudra", CRIMSON), Map.entry("collections", COLLECTIONS),
            Map.entry("mining", MINING), Map.entry("foraging", FORAGING), Map.entry("fishing", FISHING),
            Map.entry("farming", FARMING), Map.entry("hoppity", HOPPITY), Map.entry("rift", RIFT),
            Map.entry("accessories", ACCESSORIES), Map.entry("loadouts", LOADOUTS),
            Map.entry("pets", PETS), Map.entry("skills", SKILLS), Map.entry("extras", STATS),
            Map.entry("sb_bar", LEVELING),
            Map.entry("heart_trees", SKILL_TREE));

    private PvSections() {
    }
}
