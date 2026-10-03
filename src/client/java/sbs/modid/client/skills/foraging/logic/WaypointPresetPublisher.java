/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.logic;

import net.minecraft.core.BlockPos;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.pathfinding.Waypoint;
import sbs.modid.client.core.pathfinding.WaypointStore;
import sbs.modid.client.skills.foraging.model.HoneyTimer;
import sbs.modid.client.skills.foraging.model.WaypointPresetData;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes the preset groups of the island underfoot as transient waypoints, and clears them again
 * when the player leaves.
 *
 * <p><b>Transient, never persisted.</b> An island's worth of markers must not reach {@code
 * config.json} - it would grow without bound and be rewritten on every island change, and none of it
 * is worth surviving a restart because it is derived from data the mod reloads anyway. That is what
 * {@link WaypointStore#setTransient} is for, and the whole set is swapped in one call so a stale
 * marker cannot be left behind.
 *
 * <p><b>This is the island scoping.</b> There is no island field on {@code Waypoint} - every SkyBlock
 * island shares one Minecraft dimension but has its own coordinate space - so scoping is done by only
 * ever publishing the groups whose island matches, exactly as the Fairy Souls and NPC locator
 * features do. Two groups on two islands therefore cannot render together: the player cannot stand on
 * both.
 *
 * <p><b>A group may narrow itself to one zone inside the island</b>, and that is the only way to say
 * "the Cavern biome of the Critter Safari": the biome is a zone, while the coordinates belong to the
 * island's space. Both halves are then required, so leaving the zone unpublishes that group's markers
 * without touching any other group standing on the same island.
 *
 * <p><b>Recomputed only when something changed.</b> The tick runs on every client tick, so the guard
 * is the point: the island, the zone, the override generation and the toggle state are compared
 * against what was last published, and the common case is three comparisons and a return.
 */
public final class WaypointPresetPublisher {

    private static final WaypointPresetPublisher INSTANCE = new WaypointPresetPublisher();

    /** What the last publish was built from, so an unchanged world is an early return. */
    private String publishedIsland = "";
    private String publishedZone = "";
    private int publishedGeneration = -1;
    private boolean publishedAnything;

    /** How many points the last publish emitted, for the settings status line. */
    private int publishedPoints;

    /**
     * The markers this publisher currently owns, so their sub-labels can be rewritten in place.
     *
     * <p>Kept here rather than fetched from {@link WaypointStore} because these objects are ours:
     * a per-tick countdown has to mutate the marker instead of re-publishing it (a set swap
     * invalidates the pathfinder, and at tick rate that is a search that restarts forever), and
     * mutating only the list we built is what keeps this publisher out of every other one's set.
     */
    private List<Waypoint> published = List.of();

    /** Whether anything was written last time, so clearing costs nothing while nothing is running. */
    private boolean decorated;

    private WaypointPresetPublisher() {
    }

    public static WaypointPresetPublisher getInstance() {
        return INSTANCE;
    }

    /**
     * Client tick. Cheap while nothing has changed; that is what the comparison below is for.
     *
     * <p><b>The zone is part of the key, not only the island.</b> An instanced island is entered from
     * a zone of the island it hangs off - the Critter Safari from Torrhus Canyon's "Critter Safari
     * Entrance" - and the tab list's {@code Area:} row can go on naming the outer island across that
     * boundary. With the island alone as the key, walking in changes nothing the guard can see and
     * the groups scoped to the inner island are never published at all. A zone change is still a
     * rare event, and a republish is a handful of objects and one {@code setTransient}.
     *
     * <p>It earns its place a second time now that a group can scope itself to a zone: walking from
     * the Cavern into the next biome changes nothing about the island, and without the zone here the
     * cavern's markers would stay published across the whole Safari.
     */
    public void onClientTick() {
        String island = SkyBlockLocation.island();
        String zone = SkyBlockLocation.zone();
        int generation = WaypointPresetOverrides.getInstance().generation();
        if (!island.equals(publishedIsland) || !zone.equals(publishedZone)
                || generation != publishedGeneration) {
            publishedIsland = island;
            publishedZone = zone;
            publishedGeneration = generation;
            republish();
        }
        // Runs on every tick rather than only on a republish: a countdown changes without anything
        // about the world changing, and it is a handful of string builds over a handful of markers.
        decorate();
    }

    /**
     * Rewrites the sub-label of every marker that has live state to show.
     *
     * <p>Owned by this class rather than reached in from the honey module, because these
     * {@link Waypoint} objects are this publisher's. The timer answers "what should this position
     * say"; who is allowed to write it stays here.
     */
    private void decorate() {
        if (published.isEmpty()) {
            return;
        }
        List<HoneyTimer> timers = HoneyTreeTimers.getInstance().waypointTimers(publishedIsland);
        if (timers.isEmpty()) {
            if (decorated) {
                for (Waypoint waypoint : published) {
                    waypoint.subLabel = "";
                    waypoint.subLabelColorHex = "";
                }
                decorated = false;
            }
            return;
        }
        decorated = true;
        for (Waypoint waypoint : published) {
            HoneyTimer match = null;
            for (HoneyTimer timer : timers) {
                if (timer.x == waypoint.x && timer.y == waypoint.y && timer.z == waypoint.z) {
                    match = timer;
                    break;
                }
            }
            String[] sub = match == null ? null : HoneyTreeTimers.subLabel(match);
            waypoint.subLabel = sub == null ? "" : sub[0];
            waypoint.subLabelColorHex = sub == null ? "" : sub[1];
        }
    }

    /** Forces a rebuild - the settings screen calls this after a toggle so the world follows at once. */
    public void refresh() {
        publishedGeneration = -1;
        onClientTick();
    }

    /** Drops everything this publisher owns. */
    public void clear() {
        publishedPoints = 0;
        published = List.of();
        if (publishedAnything) {
            WaypointStore.clearTransient(Waypoint.SOURCE_PRESET);
            publishedAnything = false;
        }
    }

    /**
     * Why nothing is on screen, in one line - because "the island did not match" is otherwise a
     * silent no-op, and a feature that draws nothing without saying why is the thing that makes a
     * player think it is broken when it is working exactly as told.
     */
    public String status() {
        String island = SkyBlockLocation.island();
        if (island == null || island.isEmpty()) {
            return "not on SkyBlock (or the island is not readable yet) - nothing is drawn";
        }
        String zone = SkyBlockLocation.zone();
        String where = zone.isEmpty() || zone.equalsIgnoreCase(island)
                ? "\"" + island + "\""
                : "\"" + island + "\" / \"" + zone + "\"";
        List<WaypointPresetData.Group> here = WaypointPresetDatabase.activeHere();
        if (here.isEmpty()) {
            return "in " + where + " - no preset group belongs here";
        }
        if (publishedPoints == 0) {
            return "in " + where + " - " + here.size()
                    + " group(s) belong here, all switched off above";
        }
        return "in " + where + " - drawing " + publishedPoints + " point(s) from "
                + here.size() + " group(s) that belong here";
    }

    private void republish() {
        List<WaypointPresetData.Group> groups = WaypointPresetDatabase.activeHere();
        if (groups.isEmpty()) {
            clear();
            return;
        }
        WaypointPresetOverrides overrides = WaypointPresetOverrides.getInstance();
        List<Waypoint> out = new ArrayList<>();
        for (WaypointPresetData.Group group : groups) {
            if (!overrides.groupEnabled(group.id, group.enabledByDefault)) {
                continue;
            }
            String groupColor = groupColor(group, overrides);
            int range = overrides.groupMaxDistance(group.id, group.maxDistance);
            for (WaypointPresetData.Point point : group.points) {
                Waypoint waypoint = build(group, point, groupColor, range, overrides);
                if (waypoint != null) {
                    out.add(waypoint);
                }
            }
        }
        if (out.isEmpty()) {
            clear();
            return;
        }
        WaypointStore.setTransient(Waypoint.SOURCE_PRESET, out);
        publishedAnything = true;
        publishedPoints = out.size();
        published = out;
    }

    /** The player's colour for a group when they set one, else the group's shipped colour. */
    private static String groupColor(WaypointPresetData.Group group, WaypointPresetOverrides overrides) {
        WaypointPresetOverrides.GroupOverride record = overrides.group(group.id);
        if (record != null && record.colorHex != null && !record.colorHex.isBlank()) {
            return record.colorHex;
        }
        return group.colorHex == null ? "" : group.colorHex;
    }

    /**
     * One point as a waypoint, with the player's overrides applied, or {@code null} when it is
     * deleted or switched off.
     */
    private static Waypoint build(WaypointPresetData.Group group, WaypointPresetData.Point point,
                                  String groupColor, int maxDistance,
                                  WaypointPresetOverrides overrides) {
        WaypointPresetOverrides.PointOverride record = overrides.point(group.id, point.id);
        if (record != null && (record.deleted || Boolean.FALSE.equals(record.enabled))) {
            return null;
        }
        int x = point.x;
        int y = point.y;
        int z = point.z;
        if (record != null && record.x != null && record.y != null && record.z != null) {
            x = record.x;
            y = record.y;
            z = record.z;
        }
        String label = record != null && record.name != null && !record.name.isBlank()
                ? record.name
                : (point.name == null || point.name.isBlank() ? group.name : point.name);

        Waypoint waypoint = new Waypoint(label, new BlockPos(x, y, z),
                WaypointStore.currentDimension(), Waypoint.SOURCE_PRESET);
        waypoint.colorHex = record != null && record.colorHex != null && !record.colorHex.isBlank()
                ? record.colorHex
                : groupColor;
        waypoint.maxDistance = maxDistance;
        // Unverified coordinates must not be pointed at with a walking route as though they were
        // known good; the marker and its distance are the honest amount to claim.
        waypoint.routable = false;
        return waypoint;
    }
}
