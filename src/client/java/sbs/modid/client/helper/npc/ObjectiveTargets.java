/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.npc;

import sbs.modid.client.helper.npc.SkyblockNpcs.Npc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a scoreboard Objective points at: a catalogued NPC, or a catalogued place, or nothing.
 *
 * <h2>The wording is not known yet</h2>
 * The play-instance logs hold no {@code [SBS][Npc]} line - the reader has never run live - so the
 * example lines in the tests are {@code ESTIMATED} ("Talk to Oringo", "Go to the Barn"). The matcher
 * is therefore tolerant rather than grammatical: colour codes and the {@code ⏣} marker stripped,
 * case ignored, and the text <i>searched</i> for known names rather than parsed for a sentence
 * shape. Once real lines arrive (the tracker logs every change) this is where they get checked.
 *
 * <h2>Order and refusals</h2>
 * <ol>
 *   <li>A "collect / obtain / gather / craft" objective has no single place to go, so it yields
 *       nothing - no guessed location, not even an NPC the sentence happens to mention.</li>
 *   <li>NPCs first, longest name first (so "Rabbit Bro" beats "Bro").</li>
 *   <li>Places second, longest first, matched on whole words and at least {@link #MIN_PLACE}
 *       characters, because a place name is ordinary English ("Farm", "Barn") far more often than an
 *       NPC's is.</li>
 *   <li>Anything else: nothing. A name nobody catalogued is never guessed.</li>
 * </ol>
 *
 * <p>Pure: text and catalogues in, a target out.
 */
public final class ObjectiveTargets {

    /** Shortest place name that may match - below this ordinary words collide with place names. */
    static final int MIN_PLACE = 4;

    private static final Pattern GATHER =
            Pattern.compile("^(?:collect|obtain|gather|craft|acquire)\\b", Pattern.CASE_INSENSITIVE);

    /** A named place with coordinates: a map location, or a zone resolved to one of its locations. */
    public record Place(String name, String island, int x, int y, int z) {
    }

    public enum Kind { NPC, PLACE }

    /** The resolved target. */
    public record Target(Kind kind, String name, String island, double x, double y, double z) {
    }

    private ObjectiveTargets() {
    }

    /** Colour codes and the zone marker gone, whitespace collapsed, lower case. */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("§.", "").replace('⏣', ' ').replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The target {@code text} names.
     *
     * @param npcsLongestFirst the NPC catalogue, longest names first
     * @param places           the place catalogue, any order
     * @param includePlaces    the "Include Places" setting
     */
    public static Target match(String text, List<Npc> npcsLongestFirst, List<Place> places,
                               boolean includePlaces) {
        String haystack = normalise(text);
        if (haystack.isEmpty() || GATHER.matcher(haystack).find()) {
            return null;
        }
        for (Npc npc : npcsLongestFirst) {
            if (haystack.contains(npc.name().toLowerCase(Locale.ROOT))) {
                return new Target(Kind.NPC, npc.name(), npc.island(), npc.x(), npc.y(), npc.z());
            }
        }
        if (!includePlaces || places == null) {
            return null;
        }
        List<Place> sorted = new ArrayList<>(places);
        sorted.sort(Comparator.comparingInt((Place p) -> p.name().length()).reversed());
        for (Place place : sorted) {
            String name = place.name().toLowerCase(Locale.ROOT).trim();
            if (name.length() < MIN_PLACE) {
                continue;
            }
            if (Pattern.compile("(?<![a-z0-9])" + Pattern.quote(name) + "(?![a-z0-9])")
                    .matcher(haystack).find()) {
                return new Target(Kind.PLACE, place.name(), place.island(), place.x() + 0.5, place.y(),
                        place.z() + 0.5);
            }
        }
        return null;
    }
}
