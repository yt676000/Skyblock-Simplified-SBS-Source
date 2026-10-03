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
import sbs.modid.client.dungeons.run.model.DungeonState;

import java.util.ArrayList;
import java.util.List;

/**
 * Locates the room the player is standing in using the <b>known grid scheme</b>: Hypixel dungeon rooms sit
 * on a fixed world grid – slots repeat every {@value #GRID} blocks with room NW corners at
 * {@code ≡ -8 (mod 32)} (physical room pos: {@code ((x+8.5) snapped to 32) - 8}),
 * each room interior spanning {@code corner .. corner+30}. The room's <b>shape</b> (1x1 … 1x4, 2x2, L)
 * comes from the dungeon minimap: connected same-colour segments around the player's cell
 * (room segments, our {@link RoomMapReader#readRoom()}).
 *
 * <p><b>The map is the authority; physical detection is the fallback.</b> Once the map has
 * identified the player's room, its colour fixes the size class (hard Hypixel facts):
 * <ul>
 *   <li><b>purple / red / pink / yellow / orange</b> (puzzle, blood, fairy, miniboss, trap) –
 *       always exactly 1x1, on the map and in the world;</li>
 *   <li><b>green</b> (entrance) – painted 1x1 on the map, but physically up to 1x2: its second
 *       32x32 half overhangs past the map border and is therefore never painted;</li>
 *   <li><b>brown</b> (normal) – the only type with multi-cell shapes; the footprint is the map's
 *       own full-edge segment fill, validated against the legal catalog
 *       ({@link RoomMapReader#isLegalShape}: 1x1..1x4, 2x2, corner, L).</li>
 * </ul>
 * The void-gap detection ({@link DungeonRoomBorders} / {@link DungeonGridRooms}) runs <b>only</b>
 * when the map cannot rule – not calibrated, tile not painted yet – so developer verification
 * still works anywhere, and a broken map never blocks the tracker entirely.
 */
public final class DungeonRoomLocator {

    /** Room slot pitch of the Hypixel dungeon grid. */
    public static final int GRID = 32;
    /** Room NW corners sit at {@code ≡ -GRID_SHIFT (mod GRID)}. */
    public static final int GRID_SHIFT = 8;
    /** A room interior spans {@code corner .. corner + ROOM_SPAN} (31 blocks). */
    public static final int ROOM_SPAN = 30;

    /** A located room: footprint, source, and (map path only) the painted room info incl. clear state. */
    public record Located(DungeonRoomBorders.Borders borders, boolean fromMap, RoomMapReader.MapRoom mapRoom) {
    }

    private DungeonRoomLocator() {
    }

    /** The NW-corner coordinate of the grid slot containing world coordinate {@code c}. */
    public static int cornerCoord(int c) {
        return Math.floorDiv(c + GRID_SHIFT, GRID) * GRID - GRID_SHIFT;
    }

    /**
     * The {@value #GRID}x{@value #GRID} cell a <b>room-relative</b> (canonical, NW-corner-based)
     * coordinate falls into, packed reversibly as {@code cellX<<32 | cellZ}.
     *
     * <p>The single definition of "which cell is this" for the whole room signature pipeline: the
     * scanner buckets its sweep by this key and the matcher derives it from the stored relative
     * coordinates, so both always agree on the cell split. Relative coordinates are corner-based, so
     * a cell index is just the coordinate divided by the grid pitch.
     */
    public static long relativeCellKey(int relativeX, int relativeZ) {
        return ((long) Math.floorDiv(relativeX, GRID) << 32)
                | (Math.floorDiv(relativeZ, GRID) & 0xFFFFFFFFL);
    }

    /** The cell X index packed into a {@link #relativeCellKey}. */
    public static int cellKeyX(long key) {
        return (int) (key >> 32);
    }

    /** The cell Z index packed into a {@link #relativeCellKey}. */
    public static int cellKeyZ(long key) {
        return (int) key;
    }

    /**
     * Locates the room at the player's position: map segments + fixed grid when a dungeon map is
     * usable, otherwise the void-gap fallback. {@code null} when neither finds a room (e.g. the map
     * has not painted the player's cell yet – simply retry on the next move).
     */
    public static Located locate(Level level, BlockPos player) {
        RoomMapReader.MapRoom room = RoomMapReader.readRoom();

        // The MAP is the shape authority: whenever it has identified the player's room, its painted
        // footprint IS the room ("map sagt 1x4, grid sagt 1x5 -> map gewinnt"). Only NORMAL (brown)
        // rooms are multi-cell on Hypixel, so the colour fixes the size class:
        //   - non-brown  -> exactly the single cell (entrance/blood/fairy/miniboss/puzzle/trap are
        //     always 1x1 - a hard Hypixel fact);
        //   - brown      -> the map's own full-edge segments, which read a multi-cell brown room
        //     exactly and may grow it.
        // The physical void data is then only used to place that shape on the fixed 32-grid
        // (fromSegments anchors on the player's own world cell) and to drop phantom cells with no
        // floor - so the lock covers the WHOLE room (the scan area too), world-aligned, and can
        // never grow into the void. Deliberately NOT gated on the entrance anchor: rejoining a run
        // mid-dungeon never captures it, and that gate left the fill's over-merge in charge.
        RoomMapReader.MapSnapshot snapshot = DungeonState.getInstance().snapshot();
        if (room != null && !room.offsets().isEmpty()) {
            List<int[]> segments = isNormalRoom(room)
                    ? floorFiltered(level, player, room.offsets())
                    : SINGLE_CELL;
            DungeonRoomBorders.Borders base = fromSegments(player, segments);
            // The map cannot paint past its own border, and it always paints the entrance as a
            // single tile even when the physical room overhangs it (user-observed 1x2 entrance
            // reaching past the map edge). So brown rooms and the entrance may GROW into cells the
            // map does not paint AT ALL - where the map paints, it stays authoritative.
            if (isNormalRoom(room) || isEntranceRoom(room)) {
                DungeonRoomBorders.Borders grown =
                        growUnpainted(level, player, base, snapshot, isEntranceRoom(room));
                if (grown != null) {
                    base = grown;
                }
            }
            return new Located(base, true, room);
        }

        // Map silent (tile not painted yet / no usable map): physical detection carries alone.
        DungeonRoomBorders.Borders grid = DungeonGridRooms.detect(level, player, snapshot);
        if (grid != null) {
            return new Located(grid, room != null, room);
        }
        DungeonRoomBorders.Borders borders = DungeonRoomBorders.detectAround(level, player);
        return borders == null ? null : new Located(borders, false, null);
    }

    /** The player's own cell only – the footprint of every non-brown (always 1x1) room. */
    private static final List<int[]> SINGLE_CELL = List.of(new int[] {0, 0});

    /** Whether the identified map room is a NORMAL (brown) room – the only multi-cell room type. */
    private static boolean isNormalRoom(RoomMapReader.MapRoom room) {
        return "brown".equals(room.colorName());
    }

    /** Whether the identified map room is the green entrance (map-1x1, physically maybe bigger). */
    private static boolean isEntranceRoom(RoomMapReader.MapRoom room) {
        return "green".equals(room.colorName());
    }

    /**
     * Extends a map-authoritative footprint into cells the map does not paint <b>at all</b> – the
     * entrance overhang, or a room clipped by the map border. The physical grid fill must contain
     * every painted cell of the map footprint (superset), and every extra cell it adds must be
     * unpainted on the map; anything else and the map wins outright ({@code null} = keep the map
     * footprint). Inactive until the world&lt;-&gt;map anchor is captured, because without it painted
     * and unpainted cells cannot be told apart.
     */
    private static DungeonRoomBorders.Borders growUnpainted(Level level, BlockPos player,
                                                            DungeonRoomBorders.Borders mapBorders,
                                                            RoomMapReader.MapSnapshot snapshot,
                                                            boolean entrance) {
        if (!RoomMapReader.isAnchored()) {
            return null;
        }
        java.util.Map<Long, RoomMapReader.MapTile> tiles = DungeonGridRooms.mapTiles(snapshot);
        if (tiles == null) {
            return null;
        }
        DungeonRoomBorders.Borders fill = DungeonGridRooms.detect(level, player, snapshot);
        if (fill == null || fill.rects().size() <= mapBorders.rects().size()) {
            return null;   // the fill offers nothing beyond the map footprint
        }
        // The grown footprint must still be a legal Hypixel shape - and the entrance specifically
        // is never bigger than 1x2 (its single unpainted overhang half), so a fill that merged a
        // neighbour room into it can never win.
        List<int[]> fillOffsets = cellOffsets(fill);
        if (fillOffsets == null || !RoomMapReader.isLegalShape(fillOffsets)
                || (entrance && fillOffsets.size() > 2)) {
            return null;
        }
        java.util.Set<Long> mapCells = cellCorners(mapBorders);
        java.util.Set<Long> fillCells = cellCorners(fill);
        if (!fillCells.containsAll(mapCells)) {
            return null;   // the fill contradicts painted cells - the map is the authority there
        }
        for (long key : fillCells) {
            if (mapCells.contains(key)) {
                continue;
            }
            int cellX = (int) (key >> 32);
            int cellZ = (int) key;
            int[] mapCell = RoomMapReader.worldToMapCellIndex(cellX, cellZ);
            if (mapCell == null || tiles.containsKey(DungeonGridRooms.cellKey(mapCell[0], mapCell[1]))) {
                return null;   // the map paints something there - it already ruled on that cell
            }
        }
        return fill;
    }

    /**
     * The footprint's cell offsets relative to its NW cell (the {@link RoomMapReader#isLegalShape}
     * frame), or {@code null} when any rect is not a plain grid cell – irregular void-detected
     * rects carry no cell structure to validate.
     */
    public static List<int[]> cellOffsets(DungeonRoomBorders.Borders borders) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (DungeonRoomBorders.Rect rect : borders.rects()) {
            if (rect.maxX() - rect.minX() != ROOM_SPAN || rect.maxZ() - rect.minZ() != ROOM_SPAN
                    || cornerCoord(rect.minX()) != rect.minX() || cornerCoord(rect.minZ()) != rect.minZ()) {
                return null;
            }
            minX = Math.min(minX, rect.minX());
            minZ = Math.min(minZ, rect.minZ());
        }
        List<int[]> offsets = new ArrayList<>(borders.rects().size());
        for (DungeonRoomBorders.Rect rect : borders.rects()) {
            offsets.add(new int[] {(rect.minX() - minX) / GRID, (rect.minZ() - minZ) / GRID});
        }
        return offsets;
    }

    /**
     * Whether a detected footprint can be a real Hypixel room at all.
     *
     * <p>Two rules, in order of authority: a footprint made of clean grid cells is checked against
     * {@link RoomMapReader#isLegalShape} outright, and anything else falls back to its bounding span
     * - every legal shape keeps at least one axis at two cells or fewer, so a 3x3 or 5x3 box cannot
     * be one room whatever produced it.
     *
     * <p>Shared on purpose. {@link DungeonRoomTracker} asks it before locking a room, and the boss
     * detector in {@link DungeonStateManager} asks the same question of the ground the player is
     * standing on - "is this a room" has to mean the same thing in both places, or the boss check
     * would be answering a subtly different question from the one the tracker answers.
     */
    public static boolean isPlausibleRoom(DungeonRoomBorders.Borders found) {
        if (found == null) {
            return false;
        }
        List<int[]> offsets = cellOffsets(found);
        if (offsets != null) {
            return RoomMapReader.isLegalShape(offsets);
        }
        int cellsX = (int) Math.ceil((found.max().getX() - found.min().getX() + 1) / (double) GRID);
        int cellsZ = (int) Math.ceil((found.max().getZ() - found.min().getZ() + 1) / (double) GRID);
        return cellsX <= 4 && cellsZ <= 4 && Math.min(cellsX, cellsZ) <= 2;
    }

    /** The NW corner of every footprint rect, packed reversibly ({@code x<<32 | z}). */
    private static java.util.Set<Long> cellCorners(DungeonRoomBorders.Borders borders) {
        java.util.Set<Long> keys = new java.util.HashSet<>();
        for (DungeonRoomBorders.Rect rect : borders.rects()) {
            keys.add(((long) rect.minX() << 32) | (rect.minZ() & 0xFFFFFFFFL));
        }
        return keys;
    }

    /**
     * Drops any map segment whose cell centre is genuinely void (empty, no floor) in a loaded chunk –
     * a defensive guard so a mis-anchored map painting a phantom brown cell over the void can never
     * grow the box into nothing. The player's own cell is always kept, and cells in unloaded chunks
     * are kept (the lookup reports them solid), so a legitimate far segment is never dropped.
     */
    private static List<int[]> floorFiltered(Level level, BlockPos player, List<int[]> offsets) {
        int baseX = cornerCoord(player.getX());
        int baseZ = cornerCoord(player.getZ());
        DungeonRoomBorders.VoidLookup lookup = new DungeonRoomBorders.VoidLookup(level);
        List<int[]> kept = new ArrayList<>(offsets.size());
        for (int[] off : offsets) {
            boolean origin = off[0] == 0 && off[1] == 0;
            if (origin || !lookup.isVoid(baseX + off[0] * GRID + 15, baseZ + off[1] * GRID + 15)) {
                kept.add(off);
            }
        }
        if (kept.isEmpty()) {
            kept.add(new int[] {0, 0});
        }
        return kept;
    }

    /** Builds the footprint from map segment offsets: one 31x31 grid slot rect per segment. */
    private static DungeonRoomBorders.Borders fromSegments(BlockPos player, List<int[]> offsets) {
        int baseX = cornerCoord(player.getX());
        int baseZ = cornerCoord(player.getZ());

        List<DungeonRoomBorders.Rect> rects = new ArrayList<>(offsets.size());
        int minIx = Integer.MAX_VALUE, minIz = Integer.MAX_VALUE, maxIx = Integer.MIN_VALUE, maxIz = Integer.MIN_VALUE;
        for (int[] off : offsets) {
            int cx = baseX + off[0] * GRID;
            int cz = baseZ + off[1] * GRID;
            rects.add(new DungeonRoomBorders.Rect(cx, cz, cx + ROOM_SPAN, cz + ROOM_SPAN));
            minIx = Math.min(minIx, off[0]);
            minIz = Math.min(minIz, off[1]);
            maxIx = Math.max(maxIx, off[0]);
            maxIz = Math.max(maxIz, off[1]);
        }

        int floorY = player.getY();
        BlockPos min = new BlockPos(baseX + minIx * GRID, floorY, baseZ + minIz * GRID);
        BlockPos max = new BlockPos(baseX + maxIx * GRID + ROOM_SPAN, floorY, baseZ + maxIz * GRID + ROOM_SPAN);

        return new DungeonRoomBorders.Borders(List.copyOf(rects), min, max, RoomMapReader.shapeOf(offsets));
    }
}
