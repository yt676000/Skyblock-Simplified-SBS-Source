/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import sbs.modid.client.core.dev.RoomMapReader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Determines a dungeon room's footprint <b>directly from the world</b>, using the one unambiguous
 * physical fact of the Hypixel dungeon grid: between two 31-block room cells lies a <b>1-block gap
 * line</b> that is completely empty over the full world height – except where a door bridges it
 * (the ~3-wide coal / red-clay wall or the open ~3-wide air passage, always centred on the edge).
 *
 * <p>So each edge of a 32-grid cell classifies exactly:
 * <ul>
 *   <li><b>void</b> – every gap column empty → a room boundary;</li>
 *   <li><b>door</b> – only a small centred cluster of gap columns has blocks → a boundary into the
 *       NEXT room;</li>
 *   <li><b>same room</b> – the gap line is broadly built through (a multi-cell room is one
 *       continuous build, its floor spans the internal grid lines) → the room continues.</li>
 * </ul>
 * A cell flood fill over the "same room" edges yields the exact footprint – no map, no calibration,
 * no colour matching, and a door can never merge two rooms because a doorway only ever blocks a few
 * of the 31 gap columns. This is the primary size source; the dungeon map still supplies colour and
 * clear state.
 *
 * <p><b>Map cross-check.</b> Once the dungeon map is anchored, its own painted footprint is used as a
 * second opinion on every edge: the fill only crosses into a neighbour cell when the map <i>also</i>
 * reads "same room" there (both cells explored and joined across the <i>full</i> shared edge). Where
 * the map paints a passage / door connector, an empty lane, or a different-colour neighbour, that edge
 * is a border even if the physical gap line looks built through – so the two independent signals
 * (void geometry + map paint) must agree before two cells merge. When the map has not painted both
 * cells yet (progressive reveal) it stays silent and the void gap line decides alone, and a locked
 * room is re-detected periodically as the map fills in.
 *
 * <p>Costs a few thousand cached column checks once per room entry ({@code VoidLookup} reads
 * chunk-section emptiness flags; an edge early-outs as "same room" after 7 hits). Missing data can
 * never grow the footprint: gap columns in unloaded chunks are skipped (never counted as "built"),
 * and expansion additionally requires the neighbour cell to be loaded and to have a floor. (The
 * lookup's unloaded-as-solid default used to count such columns as built-through, which merged the
 * room with its neighbour while chunks were still streaming in – a 1x4 hallway briefly read 1x5.)
 */
public final class DungeonGridRooms {

    /** No Hypixel room has more than 4 segments (1x4 / 2x2 / L); 5 tolerates an exotic L. */
    private static final int MAX_CELLS = 5;

    /**
     * More than this many non-void columns on a 31-column gap line = the room is built through the
     * gap (same room). A door blocks at most ~5 centred columns (3-wide passage plus frame), so 6
     * keeps a healthy margin on both sides.
     */
    private static final int DOOR_MAX_COLUMNS = 6;

    /** One room cell, keyed by its NW corner world coordinates. */
    private record Cell(int x, int z) {
    }

    private DungeonGridRooms() {
    }

    /**
     * The room footprint at the player's position via gap-line classification cross-checked against
     * the anchored dungeon map, or {@code null} when the player's own cell has no floor (not standing
     * in a room) or the fill exceeds every legal room size (caller falls back / retries).
     *
     * @param snapshot the current map snapshot for the edge cross-check, or {@code null} to fall back
     *                 to void-only detection (also used automatically until the map is anchored)
     */
    public static DungeonRoomBorders.Borders detect(Level level, BlockPos player,
                                                    RoomMapReader.MapSnapshot snapshot) {
        DungeonRoomBorders.VoidLookup lookup = new DungeonRoomBorders.VoidLookup(level);
        int startX = DungeonRoomLocator.cornerCoord(player.getX());
        int startZ = DungeonRoomLocator.cornerCoord(player.getZ());
        if (lookup.isVoid(startX + 15, startZ + 15)) {
            return null;   // own cell centre has no blocks at all - not standing in a room
        }

        Map<Long, RoomMapReader.MapTile> mapTiles = mapTiles(snapshot);

        Set<Cell> cells = new HashSet<>();
        Queue<Cell> queue = new ArrayDeque<>();
        Cell start = new Cell(startX, startZ);
        cells.add(start);
        queue.add(start);
        int grid = DungeonRoomLocator.GRID;
        int span = DungeonRoomLocator.ROOM_SPAN;
        while (!queue.isEmpty()) {
            Cell cell = queue.poll();
            // Gap lines: east x=corner+31 / west x=corner-1 (strip along Z); south / north along X.
            tryExpand(cells, queue, lookup, level, mapTiles, cell, 1, 0, cell.x + grid, cell.z, cell.x + span + 1, cell.z, true);
            tryExpand(cells, queue, lookup, level, mapTiles, cell, -1, 0, cell.x - grid, cell.z, cell.x - 1, cell.z, true);
            tryExpand(cells, queue, lookup, level, mapTiles, cell, 0, 1, cell.x, cell.z + grid, cell.x, cell.z + span + 1, false);
            tryExpand(cells, queue, lookup, level, mapTiles, cell, 0, -1, cell.x, cell.z - grid, cell.x, cell.z - 1, false);
            if (cells.size() > MAX_CELLS) {
                sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][GridRooms] fill exceeded {} cells around {},{} - rejecting",
                        MAX_CELLS, startX, startZ);
                return null;
            }
        }

        // Assemble the footprint exactly like the map path does (one rect per cell).
        List<DungeonRoomBorders.Rect> rects = new ArrayList<>(cells.size());
        List<int[]> offsets = new ArrayList<>(cells.size());
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell cell : cells) {
            rects.add(new DungeonRoomBorders.Rect(cell.x, cell.z, cell.x + span, cell.z + span));
            offsets.add(new int[] {(cell.x - startX) / grid, (cell.z - startZ) / grid});
            minX = Math.min(minX, cell.x);
            minZ = Math.min(minZ, cell.z);
            maxX = Math.max(maxX, cell.x + span);
            maxZ = Math.max(maxZ, cell.z + span);
        }
        int floorY = player.getY();
        return new DungeonRoomBorders.Borders(List.copyOf(rects),
                new BlockPos(minX, floorY, minZ), new BlockPos(maxX, floorY, maxZ),
                RoomMapReader.shapeOf(offsets));
    }

    /** Adds the neighbour cell to the fill when the void gap line AND the map both read "same room". */
    private static void tryExpand(Set<Cell> cells, Queue<Cell> queue,
                                  DungeonRoomBorders.VoidLookup lookup, Level level,
                                  Map<Long, RoomMapReader.MapTile> mapTiles, Cell cell, int dirSignX, int dirSignZ,
                                  int neighborX, int neighborZ, int gapX, int gapZ, boolean alongZ) {
        Cell neighbor = new Cell(neighborX, neighborZ);
        if (cells.contains(neighbor)) {
            return;
        }
        // Never grow into missing data: the neighbour must be loaded and have a floor of its own.
        if (!level.hasChunkAt(neighborX + 15, neighborZ + 15)
                || lookup.isVoid(neighborX + 15, neighborZ + 15)) {
            return;
        }
        // Map veto: where the anchored map paints a passage / different room, this edge is a border
        // even if the physical gap line looks built through (two independent signals must agree).
        if (mapPaintsBorder(mapTiles, cell.x, cell.z, dirSignX, dirSignZ)) {
            return;
        }
        if (!gapLineBuiltThrough(lookup, level, gapX, gapZ, alongZ)) {
            return;   // void or a door - the neighbour is a different room
        }
        cells.add(neighbor);
        queue.add(neighbor);
    }

    /** The snapshot's tiles keyed by map cell, or {@code null} while the map is not anchored. */
    // Package-visible: DungeonRoomLocator reuses it for the map-edge overhang check.
    static Map<Long, RoomMapReader.MapTile> mapTiles(RoomMapReader.MapSnapshot snapshot) {
        if (snapshot == null || !RoomMapReader.isAnchored()) {
            return null;
        }
        Map<Long, RoomMapReader.MapTile> byCell = new java.util.HashMap<>();
        for (RoomMapReader.MapTile tile : snapshot.tiles()) {
            byCell.put(cellKey(tile.cellX(), tile.cellZ()), tile);
        }
        return byCell;
    }

    /**
     * Whether the map paints a <b>border</b> on the edge of world cell {@code (cellX,cellZ)} in the
     * given direction: both cells are explored/painted but the map does not join them across the full
     * shared edge (a door connector, an empty lane, or a different-colour neighbour). Returns
     * {@code false} – deferring to the void gap line – when the map is silent (not anchored, no tile,
     * or a cell still unexplored), so the map can only ever <i>split</i> the void fill, never grow it.
     */
    private static boolean mapPaintsBorder(Map<Long, RoomMapReader.MapTile> mapTiles,
                                           int cellX, int cellZ, int dirSignX, int dirSignZ) {
        if (mapTiles == null) {
            return false;
        }
        int grid = DungeonRoomLocator.GRID;
        int[] cur = RoomMapReader.worldToMapCellIndex(cellX, cellZ);
        int[] nb = RoomMapReader.worldToMapCellIndex(cellX + dirSignX * grid, cellZ + dirSignZ * grid);
        if (cur == null || nb == null) {
            return false;
        }
        RoomMapReader.MapTile curTile = mapTiles.get(cellKey(cur[0], cur[1]));
        RoomMapReader.MapTile nbTile = mapTiles.get(cellKey(nb[0], nb[1]));
        if (curTile == null || nbTile == null || curTile.unexplored() || nbTile.unexplored()) {
            return false;   // the map has not painted both cells yet - let the void gap line decide
        }
        // The full-edge join is stored on the west (X edges) / north (Z edges) tile of the pair.
        boolean joined;
        if (dirSignX > 0) {
            joined = curTile.joinEast();
        } else if (dirSignX < 0) {
            joined = nbTile.joinEast();
        } else if (dirSignZ > 0) {
            joined = curTile.joinSouth();
        } else {
            joined = nbTile.joinSouth();
        }
        return !joined;
    }

    // Package-visible: keys for the mapTiles(...) lookup (same packing as the fill uses).
    static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    /**
     * Whether the 1-block gap line is broadly built through (same room). Counts non-void columns
     * along the 31-column strip; a doorway's centred cluster stays at or below
     * {@link #DOOR_MAX_COLUMNS}, a continuous room build exceeds it almost immediately.
     *
     * <p>Columns in unloaded chunks are <b>skipped</b>, not counted: the lookup reports them as
     * solid, and counting them used to read a half-loaded gap line as "built through", merging two
     * rooms until the chunks finished streaming (the 1x4-hallway-as-1x5 bug). Skipping keeps the
     * invariant that missing data can only ever shrink the fill, never grow it.
     */
    private static boolean gapLineBuiltThrough(DungeonRoomBorders.VoidLookup lookup, Level level,
                                               int gapX, int gapZ, boolean alongZ) {
        int blocked = 0;
        for (int i = 0; i <= DungeonRoomLocator.ROOM_SPAN; i++) {
            int x = alongZ ? gapX : gapX + i;
            int z = alongZ ? gapZ + i : gapZ;
            if (!level.hasChunkAt(x, z)) {
                continue;   // unknown column - must never count towards "built through"
            }
            if (!lookup.isVoid(x, z)) {
                blocked++;
                if (blocked > DOOR_MAX_COLUMNS) {
                    return true;
                }
            }
        }
        return false;
    }
}
