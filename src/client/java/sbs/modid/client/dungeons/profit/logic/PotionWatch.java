/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Dungeon Potions were drunk before a run started. Pure: inventory counts in, consumed potion
 * ids out, so every rule is unit tested without a game.
 *
 * <p><b>The window.</b> Hypixel prints no "you drank" line (none in the maintainer's 1.8.9 logs), so
 * consumption is read from the inventory: from entering the dungeon ({@link #arm()}) to the
 * {@code "You can no longer consume or splash any potions"} line ({@link #disarm()}), a
 * {@code Dungeon N Potion} stack that shrinks by exactly one is one potion drunk. After the line no
 * potion can be drunk, so nothing is watched.
 *
 * <p><b>What is not a drink.</b> Like the fishing bait diff: while any screen is open (moving items,
 * a carried stack, a chest) or the drop key was pressed, a shrink only moves the baseline. A stack
 * shrinking by more than one in one look is a stack moved, never a drink.
 *
 * <p><b>Inventory only.</b> SBS does not read the Potion Bag, so a potion drunk straight from the bag
 * is not seen; the setting text says so.
 */
public final class PotionWatch {

    /** {@code "Dungeon VII Potion"}. */
    private static final Pattern NAME = Pattern.compile("^Dungeon ([IVX]+) Potion$");
    private static final List<String> NUMERALS = List.of("I", "II", "III", "IV", "V", "VI", "VII");

    private boolean armed;
    private Map<String, Integer> baseline;

    /** {@code "Dungeon VII Potion"} -> {@code DUNGEON_POTION_7}; {@code null} for any other name. */
    public static String idOf(String plainName) {
        if (plainName == null) {
            return null;
        }
        Matcher m = NAME.matcher(plainName.trim());
        if (!m.matches()) {
            return null;
        }
        int level = NUMERALS.indexOf(m.group(1)) + 1;
        return level >= 1 ? "DUNGEON_POTION_" + level : null;
    }

    /** {@code DUNGEON_POTION_7} -> {@code "Dungeon VII Potion"}, the chat and Bazaar name. */
    public static String nameOf(String id) {
        int level = id == null || id.isEmpty() ? 0 : id.charAt(id.length() - 1) - '0';
        return level >= 1 && level <= NUMERALS.size() ? "Dungeon " + NUMERALS.get(level - 1) + " Potion" : id;
    }

    /**
     * The price one consumed potion is booked at. The purchase price (what this session's
     * {@code "You bought"} line paid) when preferred and known, otherwise the market; either one
     * stands in for the other when only it is known. {@code null} only when neither is.
     */
    public static Long price(boolean preferPurchase, Long purchase, Long market) {
        if (preferPurchase && purchase != null) {
            return purchase;
        }
        return market != null ? market : purchase;
    }

    /** The dungeon was entered: potions may be drunk until the start line. */
    public void arm() {
        armed = true;
        baseline = null;
    }

    /** The start line, or the dungeon left. */
    public void disarm() {
        armed = false;
        baseline = null;
    }

    public boolean armed() {
        return armed;
    }

    /**
     * One look at the inventory.
     *
     * @param counts  potion id -> how many the inventory holds now (ids from {@link #idOf})
     * @param blocked a screen is open or the drop key was pressed: shrinks only move the baseline
     * @return the potion ids drunk since the last look, one entry per potion
     */
    public List<String> observe(Map<String, Integer> counts, boolean blocked) {
        if (!armed) {
            return List.of();
        }
        Map<String, Integer> previous = baseline;
        baseline = new HashMap<>(counts);
        if (previous == null || blocked) {
            return List.of();
        }
        List<String> drunk = new ArrayList<>(1);
        for (Map.Entry<String, Integer> e : previous.entrySet()) {
            int now = counts.getOrDefault(e.getKey(), 0);
            if (e.getValue() - now == 1) {
                drunk.add(e.getKey());
            }
        }
        return drunk;
    }
}
