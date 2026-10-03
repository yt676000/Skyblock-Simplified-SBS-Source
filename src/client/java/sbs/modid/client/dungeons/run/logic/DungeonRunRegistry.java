/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-run memory of every room the player has visited: the matched database name, how many secrets the
 * room has in total (from the bundled database) and which ones were already collected (clicked chests /
 * flipped levers). Keyed by the room's world NW corner, which is stable for the whole run, and linked to
 * its map-grid cell so the SBS dungeon map can label rooms after the player has moved on.
 *
 * <p>Cleared when the player leaves The Catacombs. Collected secrets survive re-entering a room – the
 * tracker seeds its hidden-waypoint set from here, so the "found / total" counter never resets mid-run.
 */
public final class DungeonRunRegistry {

    private static final DungeonRunRegistry INSTANCE = new DungeonRunRegistry();

    /** Everything remembered about one visited room. */
    public static final class RunRoom {
        private String name;
        private int secretsTotal = -1;
        private final Set<String> foundSecrets = new HashSet<>();

        /** The matched database room name, or {@code null} while unidentified. */
        public String name() {
            return name;
        }

        /** Total secrets (waypoints) the database lists for this room, or {@code -1} when unknown. */
        public int secretsTotal() {
            return secretsTotal;
        }

        public int secretsFound() {
            return foundSecrets.size();
        }

        /** The collected secret (waypoint) names – read-only view for seeding the tracker. */
        public Set<String> foundSecrets() {
            return foundSecrets;
        }

        void identify(String name, int secretsTotal) {
            // A CHANGED name means the earlier identification was wrong, so the secrets collected
            // under it are meaningless - keeping them produced impossible counters like "9/7".
            // Re-identifying the same room (every re-lock does) must of course keep them.
            if (this.name != null && !this.name.equals(name)) {
                foundSecrets.clear();
            }
            this.name = name;
            this.secretsTotal = secretsTotal;
        }

        void addSecret(String waypointName) {
            foundSecrets.add(waypointName);
        }

        /** Back to "visited but unidentified" – everything here hung on a name that was withdrawn. */
        void forgetIdentity() {
            name = null;
            secretsTotal = -1;
            foundSecrets.clear();
        }
    }

    private final Map<Long, RunRoom> byCorner = new HashMap<>();
    private final Map<Long, Long> mapCellToCorner = new HashMap<>();

    private DungeonRunRegistry() {
    }

    public static DungeonRunRegistry getInstance() {
        return INSTANCE;
    }

    /** Packs a room's world NW corner into its registry key. */
    public static long cornerKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** The room registered for this corner, created on first visit. */
    public RunRoom getOrCreate(long cornerKey) {
        return byCorner.computeIfAbsent(cornerKey, k -> new RunRoom());
    }

    /** Marks a room as identified (name + secrets total from the database). */
    public void identify(long cornerKey, String name, int secretsTotal) {
        getOrCreate(cornerKey).identify(name, secretsTotal);
    }

    /**
     * Withdraws a room's identification (name, totals, collected secrets), keeping the room itself.
     * Called when the footprint the name was derived from turns out to be wrong: the run room is
     * keyed by its NW corner, which a footprint correction does not change, so without this the wrong
     * name outlives the footprint that produced it and sits on the map tile for the whole run.
     */
    public void forgetIdentity(long cornerKey) {
        RunRoom room = byCorner.get(cornerKey);
        if (room != null) {
            room.forgetIdentity();
        }
    }

    /** Records a collected secret for the room. */
    public void addSecret(long cornerKey, String waypointName) {
        getOrCreate(cornerKey).addSecret(waypointName);
    }

    /** Links the room to its NW map-grid cell so the map HUD can find it. */
    public void linkMapCell(int nwCellX, int nwCellZ, long cornerKey) {
        mapCellToCorner.put(cellKey(nwCellX, nwCellZ), cornerKey);
    }

    /**
     * Drops every map-cell link while keeping the rooms themselves (names, secrets). Called when the
     * world&lt;-&gt;map anchor is re-committed: the links were derived from the old correspondence, so
     * leaving them in place labels the wrong tiles with the wrong rooms. The caller rebuilds them
     * from the footprints it remembers.
     */
    public void clearMapCells() {
        mapCellToCorner.clear();
    }

    /** The visited room whose NW segment sits at this map-grid cell, or {@code null}. */
    public RunRoom atMapCell(int nwCellX, int nwCellZ) {
        Long corner = mapCellToCorner.get(cellKey(nwCellX, nwCellZ));
        return corner == null ? null : byCorner.get(corner);
    }

    /** Forgets the whole run (player left The Catacombs). */
    public void clear() {
        byCorner.clear();
        mapCellToCorner.clear();
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }
}
