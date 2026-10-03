/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.skills.foraging.model.WaypointPresetData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shipped location presets: bundled, cached and backend-refreshed through the shared
 * {@link VersionedDataStore}.
 *
 * <p>Nothing here knows what the player changed - that is {@link WaypointPresetOverrides}, and
 * keeping the two apart is what lets this file be replaced wholesale on update without disturbing a
 * single correction.
 *
 * <p>An empty or unreadable file is a degraded state, not an error: the store keeps whatever it
 * already had (the bundled copy is loaded before any fetch is even made), and with nothing at all
 * every query here answers empty, so the feature is simply inert.
 */
public final class WaypointPresetDatabase {

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/waypoints/presets.json";

    /** The highest schema this build reads; bump only alongside a parser change. */
    private static final int SUPPORTED_SCHEMA = 3;

    private static final VersionedDataStore<WaypointPresetData> STORE = new VersionedDataStore<>(
            "WaypointPresets", RESOURCE,
            SBSFiles.root().resolve("data").resolve("waypoint_presets.json"),
            null, WaypointPresetData.class, SUPPORTED_SCHEMA);

    /** The document the last {@link #link} ran over, so linking happens once per new document. */
    private static WaypointPresetData linked;

    private WaypointPresetDatabase() {
    }

    /** Loads the bundled and cached copies. Call on client init, after the config. */
    public static void load() {
        STORE.load();
        link();
    }

    private static synchronized void link() {
        WaypointPresetData document = STORE.get();
        if (document == null || document == linked) {
            return;
        }
        document.link();
        linked = document;
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Presets] {} group(s), {} point(s), data v{}",
                document.groups.size(), document.pointCount(), document.dataVersion());
    }

    private static WaypointPresetData data() {
        WaypointPresetData document = STORE.get();
        if (document != null && document != linked) {
            link();
        }
        return document;
    }

    /** Every group in the file, in file order. Empty when nothing is loaded. */
    public static List<WaypointPresetData.Group> groups() {
        WaypointPresetData document = data();
        return document == null || document.groups == null ? List.of() : document.groups;
    }

    /** The group with this id, or {@code null}. */
    public static WaypointPresetData.Group group(String id) {
        if (id == null) {
            return null;
        }
        for (WaypointPresetData.Group group : groups()) {
            if (id.equals(group.id)) {
                return group;
            }
        }
        return null;
    }

    /**
     * Every group that belongs where the player is standing - all of them, not the first.
     *
     * <p>Two groups can share an island (Torrhus Canyon has trees and hives), and stopping at the
     * first match is the bug that would hide one of them. Island level, because that is the level at
     * which coordinates mean anything: a zone is not an island and every zone shares its island's
     * coordinate space.
     *
     * <p><b>Asked through {@link #matchesIsland}, never by comparing {@link SkyBlockLocation#island()}
     * as a string.</b> For an ordinary island that means {@link SkyBlockLocation#onIsland}, which
     * resolves the live island <i>and</i> the live zone through {@code IslandCatalog} - what makes a
     * group survive not knowing what the tab list calls the place. For the Critter Safari it means
     * {@link SkyBlockLocation#inCritterSafari()}, because that same resolution is a guess and the
     * Safari is entered from a canyon zone carrying its whole name, so the guess put the player in an
     * instance they were standing outside of.
     *
     * <p><b>A group may narrow itself further with a zone, and then both have to agree.</b> That is
     * the only way to express "the Cavern biome of the Critter Safari": the biome is a zone, the
     * coordinates are the island's, and an island-only test would draw a cavern's markers across the
     * whole Safari. The island half stays {@link #matchesIsland}'s job - a second hand-rolled island
     * compare beside it is the exact bug the paragraph above describes.
     */
    public static List<WaypointPresetData.Group> activeHere() {
        if (SkyBlockLocation.island().isEmpty() && SkyBlockLocation.zone().isEmpty()) {
            return List.of();   // nothing readable: not on SkyBlock, or mid-warp
        }
        String zone = SkyBlockLocation.zone();
        boolean inSafari = SkyBlockLocation.inCritterSafari();
        List<WaypointPresetData.Group> out = new ArrayList<>(2);
        for (WaypointPresetData.Group group : groups()) {
            if (matchesIsland(group.island, inSafari) && matchesZone(zone, group.zone)) {
                out.add(group);
            }
        }
        return out;
    }

    /**
     * Whether a group's island scope is where the player is - {@link SkyBlockLocation#onIsland} for
     * every ordinary island, and the exact instance test for the Critter Safari.
     *
     * <p><b>The Safari cannot go through {@code onIsland}</b>, and that is the bug this method was
     * split out for. {@code onIsland} falls back to resolving the live <i>zone</i> up through
     * {@code IslandCatalog}, which is a seed-table guess and the right kind of guess for an ordinary
     * island. For an instance it is not: the Safari is entered from a canyon zone whose name contains
     * the island's, so a fallback that reasons from names put the player in the Safari while they
     * were standing on Torrhus Canyon, and the whole set drew hundreds of blocks away in terrain it
     * has nothing to do with.
     *
     * <p><b>The exclusion runs both ways.</b> Inside the Safari, a group scoped to any other island
     * is refused outright rather than asked - the tab list's {@code Area:} row can go on naming the
     * island the instance was entered from, and Torrhus Canyon's hives at the canyon's coordinates
     * are exactly as wrong inside the Safari as the Safari's critters are outside it. One instance,
     * one coordinate space, one set of markers.
     *
     * <p>A blank scope still means "any island", unchanged: it is the documented meaning of the empty
     * field and no shipped group uses it.
     */
    private static boolean matchesIsland(String island, boolean inSafari) {
        if (island == null || island.isBlank()) {
            return true;
        }
        if (SkyBlockLocation.CRITTER_SAFARI.equalsIgnoreCase(island.trim())) {
            return inSafari;
        }
        if (inSafari) {
            return false;
        }
        return SkyBlockLocation.onIsland(island);
    }

    /**
     * Whether the live zone is the group's, contains-matched the way every hand-written zone filter
     * in the mod is. An unreadable zone fails a group that asks for one: a zone-scoped set drawn
     * because the sidebar had not arrived yet is a set drawn in the wrong place.
     */
    private static boolean matchesZone(String zone, String want) {
        if (want == null || want.isBlank()) {
            return true;
        }
        return zone != null && zone.toLowerCase(Locale.ROOT)
                .contains(want.trim().toLowerCase(Locale.ROOT));
    }

    /** Every group whose rows belong on {@code moduleId}'s settings page, in file order. */
    public static List<WaypointPresetData.Group> forModule(String moduleId) {
        List<WaypointPresetData.Group> out = new ArrayList<>();
        for (WaypointPresetData.Group group : groups()) {
            if (group.ownedBy(moduleId)) {
                out.add(group);
            }
        }
        return out;
    }

    /** Whether any preset data is loaded at all. */
    public static boolean hasData() {
        return data() != null;
    }

    /** Which copy is live and at what version, for the settings status line. */
    public static String status() {
        WaypointPresetData document = data();
        if (document == null) {
            return "no preset data loaded - the feature is inert";
        }
        return document.groups.size() + " group(s), " + document.pointCount() + " point(s) ("
                + STORE.source() + " v" + document.dataVersion() + ")";
    }
}
