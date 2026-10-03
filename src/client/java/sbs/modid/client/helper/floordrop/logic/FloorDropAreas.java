/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.floordrop.logic;

import sbs.modid.client.core.keybind.IslandCatalog;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.List;
import java.util.Locale;

/**
 * Where floor drops exist: the Galatea region and the instance reached from it.
 *
 * <p><b>Four names, three places.</b> The request names "Galatea, Torrhus Canyon and the Safari
 * Zone", and none of those three is spelt the way the live game spells it:
 * <ul>
 *   <li><b>Galatea is a region, not an island.</b> The tab list's {@code Area:} row says "Moonglade
 *       Marsh" or "Torrhus Canyon" and never "Galatea", which only ever turns up as a {@code ⏣}
 *       scoreboard zone. {@link IslandCatalog} seeds it as an island of its own with no zones, so
 *       both it and the marsh have to be listed for either half of the location to answer.</li>
 *   <li><b>Torrhus Canyon</b> is a real island and needs nothing special.</li>
 *   <li><b>"Safari Zone" is what the alpha called it</b>; the live game says "Critter Safari".
 *       {@link SkyBlockLocation#isCritterSafari(String, String)} answers both, as exact aliases, and
 *       refuses the canyon zone "Critter Safari Entrance" before it asks anything else.</li>
 * </ul>
 *
 * <p><b>The decision is a pure function of the two location halves</b>, exactly as
 * {@link SkyBlockLocation#isCritterSafari(String, String)} is, so it can be checked without a game
 * behind it - which is the only way an area gate ever gets checked before it ships. {@link #live()}
 * is the wrapper that reads the halves and passes them in.
 *
 * <p><b>No coordinates, here or anywhere in this feature.</b> Every previous Safari feature had to
 * refuse the entrance because it published fixed coordinates belonging to the instance, which land
 * in unrelated terrain when drawn on the canyon. This one boxes live entities wherever they are, so
 * it has no coordinate space to get wrong. The exact test is still used rather than a loose one,
 * because being right for the right reason is what stops the next reader loosening it.
 */
public final class FloorDropAreas {

    /**
     * The islands floor drops are found on, as {@link IslandCatalog} spells them.
     *
     * <p>"Galatea" is in the list beside "Moonglade Marsh" on purpose and is not a duplicate of it:
     * the catalogue resolves "Galatea" to itself, because it is seeded as an island before the marsh
     * lists it as one of its zones. Dropping either name leaves half the region ungated depending on
     * which of the two sources answered first.
     */
    public static final List<String> ISLANDS = List.of(
            "Moonglade Marsh",
            "Torrhus Canyon",
            "Galatea",
            SkyBlockLocation.CRITTER_SAFARI);

    private FloorDropAreas() {
    }

    /** Whether the player is somewhere floor drops exist, by the live location. */
    public static boolean live() {
        return matches(SkyBlockLocation.zone(), SkyBlockLocation.island());
    }

    /**
     * The decision alone: whether either half of the location describes one of {@link #ISLANDS}.
     *
     * <p>The Safari is asked first and through its own exact test, so the entrance cannot answer for
     * the instance. It would be an enabled area either way - the entrance is a Torrhus Canyon zone
     * and the canyon is on the list - but "gated correctly by accident" is how the next feature
     * inherits a broken test.
     *
     * <p>Everything else resolves through {@link IslandCatalog}, which is where the zone-to-island
     * pairing lives. Reading either half directly would be a second location source, and the whole
     * point of {@link SkyBlockLocation} is that there is only one.
     *
     * @param zone   the sidebar's {@code ⏣} name, as {@link SkyBlockLocation#zone()} reports it
     * @param island the island, as {@link SkyBlockLocation#island()} reports it
     */
    public static boolean matches(String zone, String island) {
        if (SkyBlockLocation.isCritterSafari(zone, island)) {
            return true;
        }
        return resolvesToListedIsland(island) || resolvesToListedIsland(zone);
    }

    /** Whether one half of the location names, or resolves up to, a listed island. */
    private static boolean resolvesToListedIsland(String half) {
        if (half == null || half.isBlank()) {
            return false;
        }
        String trimmed = half.trim();
        if (listed(trimmed)) {
            return true;
        }
        return listed(IslandCatalog.islandForArea(trimmed));
    }

    private static boolean listed(String island) {
        if (island == null) {
            return false;
        }
        String lower = island.trim().toLowerCase(Locale.ROOT);
        for (String candidate : ISLANDS) {
            if (candidate.toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }

    /** The gated areas as one line, for the settings page. */
    public static String describe() {
        return String.join(", ", ISLANDS);
    }
}
