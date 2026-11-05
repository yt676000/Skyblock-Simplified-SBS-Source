/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers.weather;

import java.util.List;
import java.util.Locale;

/**
 * Which weathers each island has and what they give - bundled, versioned, and tagged with how sure
 * we are of each island.
 *
 * <p>Source: the wiki's Weather page and the 0.27.2 changelog (2026-10-06). The wiki's Mild/Extreme
 * columns look swapped in places, so every island starts at {@link Certainty#WIKI} with the
 * changelog's reading, and moves to {@link Certainty#CONFIRMED} only once the probe has seen that
 * island's extreme weather run on an extreme hour. Bump {@link #VERSION} with any edit, and record
 * the probe evidence in {@code docs/features/weather-timer.md}.
 *
 * <p>Stats are names only. The wiki lists amounts, but they disagree between the page and the
 * changelog, and a wrong number on a timer card is worse than none.
 */
public final class WeatherData {

    /** Bumped with every edit to the table below. */
    public static final int VERSION = 1;

    public enum Certainty { WIKI, CONFIRMED }

    /**
     * One island's two weathers.
     *
     * @param area    the island or zone as {@code SkyBlockLocation} names it
     * @param mild    the ordinary weather's name
     * @param extreme the every-third-hour weather's name
     * @param stats   what both give, names only
     * @param bonus   the extreme weather's exclusive bonus, or {@code ""}
     */
    public record Island(String area, String mild, String extreme, String stats, String bonus,
                         Certainty certainty) {

        /** The weather this island runs in an hour that is (or is not) extreme. */
        public String weather(boolean isExtreme) {
            return isExtreme ? extreme : mild;
        }
    }

    private static final List<Island> ISLANDS = List.of(
            new Island("Spider's Den", "Rain", "Thunderstorm",
                    "Fishing Speed, Combat Wisdom, Sea Creature Chance", "Toxic Rain Slimes", Certainty.WIKI),
            new Island("Backwater Bayou", "Smog", "Acid Rain", "", "", Certainty.WIKI),
            new Island("Crimson Isle", "Ashfall", "Hellstorm", "", "", Certainty.WIKI),
            new Island("Moonglade Marsh", "Moonfall", "Thunderstorm", "", "", Certainty.WIKI),
            new Island("Lotus Atoll", "Tropical Rain", "Blossoming", "", "", Certainty.WIKI),
            new Island("The Garden", "Rain", "Blooming",
                    "Farming Fortune, Bonus Pest Chance, Overbloom", "+10% Sowdust", Certainty.WIKI),
            // Glacite Tunnels is a zone of the Dwarven Mines; it comes first so the zone wins.
            new Island("Glacite Tunnels", "Breeze", "Snowstorm", "", "+10% Glacite Powder", Certainty.WIKI),
            new Island("Dwarven Mines", "Breeze", "Mist",
                    "Mining Speed, Mining Fortune, Fishing Speed", "+10% Mithril Powder", Certainty.WIKI),
            new Island("Crystal Hollows", "Breeze", "Rockfall", "", "+10% Gemstone Powder", Certainty.WIKI),
            new Island("The End", "Wispfall", "Voidstorm", "", "+1% Superior Dragon chance", Certainty.WIKI),
            new Island("Jerry's Workshop", "Breeze", "Blizzard", "", "", Certainty.WIKI));

    private WeatherData() {
    }

    public static List<Island> islands() {
        return ISLANDS;
    }

    /** The island with weather named exactly (ignoring case) {@code area}, or {@code null}. */
    public static Island find(String area) {
        if (area == null || area.isBlank()) {
            return null;
        }
        String key = area.trim().toLowerCase(Locale.ROOT);
        for (Island island : ISLANDS) {
            if (island.area().toLowerCase(Locale.ROOT).equals(key)) {
                return island;
            }
        }
        return null;
    }

    /** The zone's entry if it has one, else the island's: Glacite Tunnels before Dwarven Mines. */
    public static Island forLocation(String zone, String island) {
        Island byZone = find(zone);
        return byZone != null ? byZone : find(island);
    }

    /** Every weather name across all islands, for the probe's chat filter. */
    public static boolean namesWeather(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("weather")) {
            return true;
        }
        for (Island island : ISLANDS) {
            if (containsWord(lower, island.mild()) || containsWord(lower, island.extreme())) {
                return true;
            }
        }
        return false;
    }

    /** Whole-word, case-insensitive: "Rain" must not match "Acid Rain"'s neighbour "Training". */
    static boolean containsWord(String lowerText, String name) {
        String needle = name.toLowerCase(Locale.ROOT);
        int at = lowerText.indexOf(needle);
        while (at >= 0) {
            int end = at + needle.length();
            boolean before = at == 0 || !Character.isLetter(lowerText.charAt(at - 1));
            boolean after = end == lowerText.length() || !Character.isLetter(lowerText.charAt(end));
            if (before && after) {
                return true;
            }
            at = lowerText.indexOf(needle, at + 1);
        }
        return false;
    }
}
