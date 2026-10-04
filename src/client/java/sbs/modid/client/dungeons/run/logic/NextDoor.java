/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import sbs.modid.client.core.dev.RoomMapReader;
import sbs.modid.client.core.dev.RoomMapReader.DoorType;
import sbs.modid.client.core.dev.RoomMapReader.MapSnapshot;
import sbs.modid.client.core.dev.RoomMapReader.MapTile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the <b>next key door</b> of a Catacombs run from the dungeon map alone: the wither / blood
 * connectors that are still closed and sit on the explored frontier, ranked so the first one is the
 * door the party should open next. Pure over a {@link MapSnapshot}, so it is unit-tested on synthetic
 * maps; {@link WitherDoorTracker} feeds it the live snapshot and draws the result.
 *
 * <p><b>Frontier.</b> A wither door is a candidate when exactly one of the two tiles it joins is
 * explored (painted in a room colour) and the other is unexplored or not painted at all - the door
 * between the known dungeon and the unknown. The blood door only needs one explored side, because
 * how the blood room's own tile is painted before its door opens is not verified.
 *
 * <p><b>Ranking.</b> Wither doors before the blood door, unless the party holds the blood key; within
 * a kind, nearest to the player's map cell first (straight line, in cell units).
 */
public final class NextDoor {

    /** Y band of the map-derived door box (ESTIMATED: the doorway height of a Catacombs room). */
    public static final int DOOR_MIN_Y = 69;
    public static final int DOOR_MAX_Y = 72;

    /**
     * One key-door connector: stored on the west ({@code east == true}) or north ({@code east ==
     * false}) tile of the edge it sits on, exactly like {@link MapTile#doorEast()} / {@link
     * MapTile#doorSouth()}.
     */
    public record Door(int cellX, int cellZ, boolean east, DoorType type) {

        /** Stable key of this edge for the per-run opened set. */
        public long edge() {
            return edgeKey(cellX, cellZ, east);
        }

        /** Midpoint of the connector in map-cell units (cell {@code i} covers {@code [i, i+1)}). */
        public double mapX() {
            return east ? cellX + 1.0 : cellX + 0.5;
        }

        public double mapZ() {
            return east ? cellZ + 0.5 : cellZ + 1.0;
        }
    }

    private NextDoor() {
    }

    public static long edgeKey(int cellX, int cellZ, boolean east) {
        return ((long) cellX << 33) ^ ((long) (cellZ & 0xFFFFFFFFL) << 1) ^ (east ? 1L : 0L);
    }

    /** Every key door on the map that is not in {@code opened} and sits on the frontier, unranked. */
    public static List<Door> closedFrontier(MapSnapshot snapshot, Set<Long> opened) {
        if (snapshot == null) {
            return List.of();
        }
        Map<Long, MapTile> tiles = new HashMap<>();
        for (MapTile tile : snapshot.tiles()) {
            tiles.put(cellKey(tile.cellX(), tile.cellZ()), tile);
        }
        List<Door> doors = new ArrayList<>();
        for (MapTile tile : snapshot.tiles()) {
            consider(tile, tile.doorEast(), true, tiles.get(cellKey(tile.cellX() + 1, tile.cellZ())), opened, doors);
            consider(tile, tile.doorSouth(), false, tiles.get(cellKey(tile.cellX(), tile.cellZ() + 1)), opened, doors);
        }
        return doors;
    }

    private static void consider(MapTile tile, DoorType type, boolean east, MapTile neighbour,
                                 Set<Long> opened, List<Door> out) {
        if (!type.keyDoor()) {
            return;
        }
        Door door = new Door(tile.cellX(), tile.cellZ(), east, type);
        if (opened.contains(door.edge())) {
            return;
        }
        boolean here = explored(tile);
        boolean there = explored(neighbour);
        boolean frontier = type == DoorType.BLOOD ? (here || there) : (here != there);
        if (frontier) {
            out.add(door);
        }
    }

    private static boolean explored(MapTile tile) {
        return tile != null && !tile.unexplored();
    }

    /** {@link #closedFrontier} ranked: the first entry is the next door. */
    public static List<Door> ranked(MapSnapshot snapshot, Set<Long> opened, boolean bloodKey) {
        List<Door> doors = new ArrayList<>(closedFrontier(snapshot, opened));
        if (doors.isEmpty()) {
            return doors;
        }
        double px = snapshot.playerCellX();
        double pz = snapshot.playerCellZ();
        Comparator<Door> byKind = Comparator.comparingInt(
                door -> (door.type() == DoorType.BLOOD) == bloodKey ? 0 : 1);
        doors.sort(byKind.thenComparingDouble(door -> distanceSq(door, px, pz)));
        return doors;
    }

    /** The next door, or {@code null} when the map shows no closed key door on the frontier. */
    public static Door next(MapSnapshot snapshot, Set<Long> opened, boolean bloodKey) {
        List<Door> doors = ranked(snapshot, opened, bloodKey);
        return doors.isEmpty() ? null : doors.get(0);
    }

    /** The door of {@code doors} nearest to a map position, if within {@code maxCells}; else {@code null}. */
    public static Door nearest(List<Door> doors, double mapX, double mapZ, double maxCells) {
        Door best = null;
        double bestSq = maxCells * maxCells;
        for (Door door : doors) {
            double d = distanceSq(door, mapX, mapZ);
            if (d <= bestSq) {
                best = door;
                bestSq = d;
            }
        }
        return best;
    }

    private static double distanceSq(Door door, double x, double z) {
        double dx = door.mapX() - x;
        double dz = door.mapZ() - z;
        return dx * dx + dz * dz;
    }

    /**
     * The world box {@code {minX, minY, minZ, maxX, maxY, maxZ}} (inclusive) of a door, derived from
     * the map: a 3x3 footprint centred on the seam between the two cells, at the doorway height. The
     * size is ESTIMATED; near the player the scanned coal cluster replaces it. {@code null} while the
     * world&lt;-&gt;map anchor is not captured.
     */
    public static int[] worldBox(Door door) {
        int[] corner = RoomMapReader.cellToWorld(door.cellX(), door.cellZ());
        return corner == null ? null : worldBox(corner[0], corner[1], door.east());
    }

    /** {@link #worldBox(Door)} for a cell whose world NW corner is known. */
    public static int[] worldBox(int cornerX, int cornerZ, boolean east) {
        int seam = DungeonRoomLocator.ROOM_SPAN + 1;
        int mid = DungeonRoomLocator.ROOM_SPAN / 2;
        int cx = cornerX + (east ? seam : mid);
        int cz = cornerZ + (east ? mid : seam);
        return new int[] {cx - 1, DOOR_MIN_Y, cz - 1, cx + 1, DOOR_MAX_Y, cz + 1};
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }
}
