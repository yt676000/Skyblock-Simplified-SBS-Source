/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One island's map: its places, its fast-travel points, and the extent the two are drawn in.
 *
 * <p>Loaded from {@code assets/skyblock-simplified-sbs/map/<file>.json} by
 * {@link sbs.modid.client.helper.map.logic.MapDatabase}. Everything the map screen and the navigator
 * need is in here, so adding an island is a JSON file plus one line in the index - no code.
 *
 * <p><b>{@link #island} is the field that matters for routing.</b> It must be the island name
 * {@link sbs.modid.client.core.location.SkyBlockLocation#island()} reports, because SkyBlock gives
 * every island its own coordinate space over one Minecraft dimension: a waypoint at (27, 68, 33) is
 * the Museum on the Hub and a random rock anywhere else. Every publish is gated on being on this
 * island, exactly as the NPC locator gates its own.
 */
public final class IslandMap {

    /** Stable short id, used by the index and as the screen's tab key ("hub"). */
    public String id = "";

    /** Title shown above the map ("Hub"). Falls back to {@link #island}. */
    public String name = "";

    /**
     * The island name as the live location service reports it ("Hub", "The Farming Islands"). The
     * gate for publishing waypoints - see the class docs.
     */
    public String island = "";

    /**
     * The command that gets you to this island from anywhere ("/warp hub"). The first link of a
     * cross-island chain; blank means "no direct travel command", and the navigator then just routes
     * once you arrive under your own steam.
     */
    public String travel = "";

    /** Optional note shown under the title (how to reach an island with no warp, for instance). */
    public String note = "";

    /** The rectangle the map is drawn in. Computed from the data when absent - see {@link #bounds()}. */
    public Bounds bounds;

    public List<MapWarp> warps = new ArrayList<>();
    public List<MapLocation> locations = new ArrayList<>();

    /** Gson needs a no-arg constructor. */
    public IslandMap() {
    }

    /** The world-space rectangle a map covers, in block coordinates (X/Z; height is ignored). */
    public static final class Bounds {
        public int minX;
        public int minZ;
        public int maxX;
        public int maxZ;

        public Bounds() {
        }

        public Bounds(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        public int width() {
            return Math.max(1, maxX - minX);
        }

        public int depth() {
            return Math.max(1, maxZ - minZ);
        }
    }

    public String displayName() {
        return name == null || name.isBlank() ? island : name;
    }

    /**
     * Finishes parsing: drops entries too broken to draw, back-links every location to this map, and
     * fills in a {@link Bounds} when the file did not give one.
     *
     * <p>Called once at load. Doing it here rather than in the loader keeps "what a valid island map
     * looks like" in the type that defines it.
     */
    public void link() {
        if (locations == null) {
            locations = new ArrayList<>();
        }
        if (warps == null) {
            warps = new ArrayList<>();
        }
        locations.removeIf(location -> location == null || !location.valid());
        warps.removeIf(warp -> warp == null || warp.command == null || warp.command.isBlank());
        for (MapLocation location : locations) {
            location.map = this;
        }
        if (bounds == null || bounds.width() <= 1 || bounds.depth() <= 1) {
            bounds = computeBounds();
        }
    }

    /**
     * A bounding box around everything on the island, padded so nothing sits on the very edge.
     *
     * <p>Exists so a hand-written island file can omit {@code bounds} entirely and still draw
     * sensibly - one less thing to get wrong, and it self-corrects as places are added.
     */
    private Bounds computeBounds() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (MapLocation location : locations) {
            minX = Math.min(minX, location.x);
            maxX = Math.max(maxX, location.x);
            minZ = Math.min(minZ, location.z);
            maxZ = Math.max(maxZ, location.z);
        }
        for (MapWarp warp : warps) {
            if (!warp.hasPosition()) {
                continue;
            }
            minX = Math.min(minX, warp.x);
            maxX = Math.max(maxX, warp.x);
            minZ = Math.min(minZ, warp.z);
            maxZ = Math.max(maxZ, warp.z);
        }
        if (minX > maxX || minZ > maxZ) {
            return new Bounds(-100, -100, 100, 100);   // an island with nothing on it yet
        }
        int padding = Math.max(16, Math.max(maxX - minX, maxZ - minZ) / 10);
        return new Bounds(minX - padding, minZ - padding, maxX + padding, maxZ + padding);
    }

    /** The bounds to draw in; never {@code null} once {@link #link()} has run. */
    public Bounds bounds() {
        if (bounds == null) {
            bounds = computeBounds();
        }
        return bounds;
    }

    /** The warp with this id, or {@code null}. */
    public MapWarp warpById(String warpId) {
        if (warpId == null || warpId.isBlank()) {
            return null;
        }
        for (MapWarp warp : warps) {
            if (warpId.equalsIgnoreCase(warp.id)) {
                return warp;
            }
        }
        return null;
    }

    /**
     * The warps that could serve {@code location}, nearest first.
     *
     * <p>A list rather than a single answer because the nearest warp may turn out to be one the
     * player has not unlocked, and the navigator then wants the next one down instead of giving up.
     * An explicit {@link MapLocation#warp} override is put first - the data is saying it knows
     * better than the geometry, which is exactly the case where the numbers lie.
     */
    public List<MapWarp> warpsNearest(MapLocation location) {
        List<MapWarp> candidates = new ArrayList<>();
        for (MapWarp warp : warps) {
            if (warp.hasPosition()) {
                candidates.add(warp);
            }
        }
        candidates.sort((a, b) -> Double.compare(
                a.horizontalDistanceTo(location.x, location.z),
                b.horizontalDistanceTo(location.x, location.z)));

        MapWarp explicit = warpById(location.warp);
        if (explicit != null) {
            candidates.remove(explicit);
            candidates.addFirst(explicit);
        }
        return candidates;
    }

    /** Every distinct category present on this island, for the map legend and the filter row. */
    public List<MapCategory> categories() {
        List<MapCategory> out = new ArrayList<>();
        for (MapLocation location : locations) {
            MapCategory category = location.categoryOrDefault();
            if (!out.contains(category)) {
                out.add(category);
            }
        }
        return out;
    }
}
