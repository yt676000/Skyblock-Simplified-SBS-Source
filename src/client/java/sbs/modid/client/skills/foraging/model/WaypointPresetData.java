/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.model;

import sbs.modid.client.core.data.VersionedDocument;
import sbs.modid.client.helper.rift.model.Certainty;

import java.util.ArrayList;
import java.util.List;

/**
 * The shipped location presets: named groups of world positions, scoped to an island and optionally
 * to one zone inside it.
 *
 * <p>One file for every preset, not one per feature - adding a set is adding a group, which is the
 * whole point of the mechanism. Loaded through the shared {@code VersionedDataStore}, so a corrected
 * copy can reach a client from the backend without a mod release, and a copy this build cannot read
 * is refused rather than misparsed.
 *
 * <p><b>The preset is never written to.</b> Everything the player changes - disabling a point,
 * correcting a coordinate, recolouring a group - lives in {@code WaypointPresetOverrides} as a delta
 * keyed by id. That split is what lets this file be replaced wholesale on update without touching a
 * single player edit, and what makes "reset to defaults" simply deleting the override.
 *
 * <p><b>Ids are assigned once and never reused.</b> Overrides and any future per-point state key off
 * them, so a renumbered id silently moves a player's correction onto a different tree. A moved point
 * keeps its id; a removed point's id is retired.
 */
public final class WaypointPresetData implements VersionedDocument {

    /**
     * Bumped to 2 when group {@code kind} arrived. A build that predates it would read a hive group
     * with no kind to read it by and fall back to matching on position alone, so refusing the whole
     * document and serving the bundled copy is the correct degradation.
     *
     * <p>Bumped to 3 for {@link Group#zone}, {@link Group#module} and {@link Group#maxDistance}. The
     * same argument applies to each: a v2 build reads a zone-scoped group with no zone field to read,
     * so it would draw a cavern's worth of markers across the whole island, and it would list a group
     * belonging to another module's page on the Galatea page instead. Refusing the document is the
     * correct degradation; a v3 build still reads a v2 file, where all three fields are simply absent
     * and mean "no zone scope, the Galatea page, no range limit".
     */
    public int schemaVersion = 3;

    public int dataVersion;
    public String generatedAt = "";

    /** The settings page a group belongs to when it does not name one. */
    public static final String DEFAULT_MODULE = "galatea_waypoints";

    public List<Group> groups = new ArrayList<>();

    /** One named set of positions on one island. */
    public static final class Group {

        /** Stable id. The key every override record and every future timer entry uses. */
        public String id = "";

        /** Shown on the settings row and in the world label prefix. */
        public String name = "";

        /** {@link PresetKind}, as a string in the file; {@link #kind()} resolves it. */
        public String kind = "";

        /**
         * The island as {@code SkyBlockLocation.island()} spells it - "Moonglade Marsh", never
         * "Galatea", which is the region and only ever a scoreboard zone. Empty matches everywhere.
         */
        public String island = "";

        /**
         * An optional second scope <i>inside</i> the island: the {@code ⏣} zone as
         * {@code SkyBlockLocation.zone()} spells it, contains-matched, so "Cavern" covers whatever
         * flavour text Hypixel wraps around it. Empty means the whole island, which is what every
         * group did before this field.
         *
         * <p>Contains-matched rather than exact for the reason {@code SafariTracker} gives for its
         * own zone word: Hypixel renames and re-flavours areas, and a set of markers that dies on a
         * rename nobody can correct from data is a set that dies for good. Deliberately never used
         * <i>instead</i> of {@link #island} - "Cavern" alone would light up the Deep Caverns.
         */
        public String zone = "";

        /**
         * Which settings page owns this group's rows: an {@code SbsModule.id()}. Empty means
         * {@code galatea_waypoints}, the page every group belonged to before there was a second one.
         *
         * <p>Exists because the Galatea page generates its rows by looping over the whole file, so
         * without this a group added for another feature would silently appear on it. A group's
         * <i>data</i> stays in the one file - that is the property the mechanism exists for - while
         * its <i>controls</i> go where the player will look for them.
         */
        public String module = "";

        /**
         * How far away this group's markers are still drawn, in blocks; {@code 0} means no limit,
         * which is what every group did before this field. The player's own value overrides it.
         */
        public int maxDistance;

        /** Group colour as {@code RRGGBB}; empty falls back to the global waypoint preset. */
        public String colorHex = "";

        /** Whether this group draws before the player has said anything about it. */
        public boolean enabledByDefault = true;

        /** How far the coordinates are to be trusted; shown to the player rather than hidden. */
        public String certainty = "";

        public List<Point> points = new ArrayList<>();

        /** Gson needs a no-arg constructor. */
        public Group() {
        }

        public PresetKind kind() {
            return PresetKind.parse(kind);
        }

        /**
         * The declared certainty, defaulting to {@link Certainty#ESTIMATED} rather than to
         * {@code CONFIRMED}: a group that forgot to say must read as unverified, never as verified.
         */
        public Certainty certainty() {
            if (certainty == null || certainty.isBlank()) {
                return Certainty.ESTIMATED;
            }
            try {
                return Certainty.valueOf(certainty.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return Certainty.ESTIMATED;
            }
        }

        /** Whether the entry is complete enough to use at all. */
        public boolean valid() {
            return id != null && !id.isBlank() && points != null && !points.isEmpty();
        }

        /** Whether the settings page of {@code moduleId} is the one that shows this group's rows. */
        public boolean ownedBy(String moduleId) {
            String owner = module == null || module.isBlank() ? DEFAULT_MODULE : module.trim();
            return owner.equalsIgnoreCase(moduleId);
        }

        /**
         * Where this group is drawn, in one line, for a status row - because "nothing is on screen"
         * has to be explainable without reading the data file.
         */
        public String scope() {
            String where = island == null || island.isBlank() ? "any island" : island;
            return zone == null || zone.isBlank() ? where : where + ", zone \"" + zone + "\"";
        }
    }

    /** One position in a group. */
    public static final class Point {

        /** Stable id, unique across the whole file. */
        public String id = "";

        public int x;
        public int y;
        public int z;

        /** Shown in the world label. Falls back to the group name when blank. */
        public String name = "";

        /** Gson needs a no-arg constructor. */
        public Point() {
        }

        public boolean valid() {
            return id != null && !id.isBlank();
        }
    }

    /** Gson needs a no-arg constructor. */
    public WaypointPresetData() {
    }

    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    /**
     * Only a document carrying nothing usable is rejected. A file with one group is a normal first
     * pass, and an empty {@code groups} array is the deliberate "the feature is inert" state - which
     * is a degraded state, not an error, so it must not be able to throw.
     */
    @Override
    public boolean valid() {
        if (groups == null) {
            return false;
        }
        for (Group group : groups) {
            if (group != null && group.valid()) {
                return true;
            }
        }
        return false;
    }

    /** Drops unusable entries once, when the document becomes live. */
    public void link() {
        if (groups == null) {
            groups = new ArrayList<>();
            return;
        }
        groups.removeIf(group -> group == null || group.id == null || group.id.isBlank());
        for (Group group : groups) {
            if (group.points == null) {
                group.points = new ArrayList<>();
            }
            group.points.removeIf(point -> point == null || !point.valid());
        }
    }

    /** How many points across every group, for the settings status line. */
    public int pointCount() {
        int total = 0;
        for (Group group : groups) {
            total += group.points.size();
        }
        return total;
    }
}
