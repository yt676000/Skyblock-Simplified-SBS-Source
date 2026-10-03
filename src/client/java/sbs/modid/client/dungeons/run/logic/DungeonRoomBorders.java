/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects a dungeon room's real borders from the <b>void gaps</b> between rooms: the 1- or 3-block wide
 * seams where absolutely no block exists from the bottom of the world to the sky. A column inside a room
 * always has at least its floor, so "column is completely empty" is a perfect, unambiguous wall signal –
 * no grid origin, no calibration.
 *
 * <p>Starting just inside the room (offset from the door anchor along the door facing), rays are walked
 * along both horizontal axes until they hit a void column; the four hits span a rectangle. Interior
 * points of every known rectangle are then re-probed the same way, which discovers the extra arms of
 * L-shaped rooms; the footprint is the union of all found rectangles (exact for 1x1 / 1xN / 2x2 / L).
 *
 * <p>The void test reads chunk sections ({@link LevelChunkSection#hasOnlyAir()}), so an empty column
 * costs a handful of flag checks instead of ~380 block reads, and every column/chunk is cached for the
 * duration of one detection. Detection is heavy-ish (a few hundred column checks) and must only run once
 * per room entry – the result is an immutable {@link Borders} that render code can use as-is.
 */
public final class DungeonRoomBorders {

    /** Hard cap on wall-search distance (largest rooms – 1x4 – are ~128 blocks long). */
    private static final int MAX_SPAN = 160;
    /** Smallest believable room dimension – rejects detections started inside a doorway bridge. */
    private static final int MIN_ROOM_DIM = 16;
    /** Interior re-probe grid step (fine enough to land inside every arm of an L). */
    private static final int SAMPLE_STEP = 12;
    /** Upper bound on expansion passes (footprints stabilise after 2 in practice). */
    private static final int MAX_PASSES = 4;
    /** Distance from the door anchor to the probe start – clear of the 5-deep doorway. */
    private static final int DOOR_CLEARANCE = 4;
    /** Approximate cell pitch, only used to describe the size as "1x1" / "2x2" / "L". */
    private static final int CELL_PITCH = 32;

    /** An axis-aligned run of room columns (inclusive world coordinates, XZ only). */
    public record Rect(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        boolean containsRect(Rect other) {
            return other.minX >= minX && other.maxX <= maxX && other.minZ >= minZ && other.maxZ <= maxZ;
        }
    }

    /**
     * The detected room footprint: the exact void-bounded rectangles, their overall bounding box (min /
     * max carry the door-floor Y) and a human-readable size such as {@code "1x1"}, {@code "2x2"} or
     * {@code "L (3 cells)"}.
     */
    public record Borders(List<Rect> rects, BlockPos min, BlockPos max, String shape) {

        /**
         * Is the position horizontally inside the room footprint (Y is ignored – rooms are tall)?
         *
         * <p>Points on the <b>1-block seam between two cells of the same room</b> count as inside:
         * cell rects span {@code corner..corner+30} while the next cell starts at {@code corner+32},
         * so the seam column at {@code corner+31} – walkable, built-through floor in every
         * multi-cell room – lies in no rect. Rejecting it made "outside the room borders" fire while
         * standing mid-room. A seam point is within one block of at least two rects and inside the
         * overall bounding box; a point one block outside the room's outer wall is within one block
         * of only a single rect (or already outside the box) and stays rejected.
         */
        public boolean contains(BlockPos pos) {
            int x = pos.getX();
            int z = pos.getZ();
            int adjacent = 0;
            for (Rect rect : rects) {
                if (rect.contains(x, z)) {
                    return true;
                }
                int dx = Math.max(0, Math.max(rect.minX() - x, x - rect.maxX()));
                int dz = Math.max(0, Math.max(rect.minZ() - z, z - rect.maxZ()));
                if (Math.max(dx, dz) <= 1) {
                    adjacent++;
                }
            }
            return adjacent >= 2
                    && x >= min.getX() && x <= max.getX() && z >= min.getZ() && z <= max.getZ();
        }
    }

    private DungeonRoomBorders() {
    }

    /**
     * Detects the borders of the room on the {@code into} side of a door.
     *
     * @param level      the client level
     * @param doorAnchor the door's floor-centre anchor block (from {@link DungeonDoorScanner})
     * @param into       the facing from the door into the room
     * @return the footprint, or {@code null} when no void-bounded room is found (e.g. outside a dungeon)
     */
    public static Borders detect(Level level, BlockPos doorAnchor, Direction into) {
        return detectFrom(level,
                doorAnchor.getX() + into.getStepX() * DOOR_CLEARANCE,
                doorAnchor.getZ() + into.getStepZ() * DOOR_CLEARANCE,
                doorAnchor.getY());
    }

    /**
     * Detects the borders of the room the given position is standing in – no door required, so this
     * works from any entrance (or after a teleport). Returns {@code null} when the position is not
     * inside a void-bounded room, including when it is standing on a doorway bridge between two rooms
     * (the bridge is only 3 wide, which fails the {@link #MIN_ROOM_DIM} sanity check).
     */
    public static Borders detectAround(Level level, BlockPos start) {
        return detectFrom(level, start.getX(), start.getZ(), start.getY());
    }

    private static Borders detectFrom(Level level, int startX, int startZ, int floorY) {
        VoidLookup lookup = new VoidLookup(level);
        if (lookup.isVoid(startX, startZ)) {
            return null; // start column must be room floor – anything else means we are not in a room
        }

        List<Rect> rects = new ArrayList<>();
        Rect first = crossRect(lookup, startX, startZ);
        if (first == null) {
            return null; // a wall was farther than any dungeon room can be – not void-bounded terrain
        }
        if (first.maxX() - first.minX() + 1 < MIN_ROOM_DIM || first.maxZ() - first.minZ() + 1 < MIN_ROOM_DIM) {
            return null; // narrower than any real room: we are standing in a doorway bridge, not a room
        }
        rects.add(first);

        // Expansion: re-probe interior points of every rect; a probe inside an undiscovered arm of an
        // L-shape spans farther than the rects found so far and is added to the union.
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (Rect rect : List.copyOf(rects)) {
                for (int x = rect.minX() + 1; x <= rect.maxX(); x += SAMPLE_STEP) {
                    for (int z = rect.minZ() + 1; z <= rect.maxZ(); z += SAMPLE_STEP) {
                        if (lookup.isVoid(x, z)) {
                            continue; // probe grid can land on a void notch inside the bounding area
                        }
                        Rect probe = crossRect(lookup, x, z);
                        if (probe != null && addIfNew(rects, probe)) {
                            changed = true;
                        }
                    }
                }
            }
            if (!changed) {
                break;
            }
        }

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Rect rect : rects) {
            minX = Math.min(minX, rect.minX());
            minZ = Math.min(minZ, rect.minZ());
            maxX = Math.max(maxX, rect.maxX());
            maxZ = Math.max(maxZ, rect.maxZ());
        }
        BlockPos min = new BlockPos(minX, floorY, minZ);
        BlockPos max = new BlockPos(maxX, floorY, maxZ);
        return new Borders(List.copyOf(rects), min, max, shapeOf(rects, min, max));
    }

    /**
     * Walks the four axis rays from {@code (x,z)} until each hits a void column; the last non-void
     * columns span the rectangle. {@code null} when a ray exceeds {@link #MAX_SPAN}.
     */
    private static Rect crossRect(VoidLookup lookup, int x, int z) {
        int maxX = walk(lookup, x, z, 1, 0);
        int minX = walk(lookup, x, z, -1, 0);
        int maxZ = walk(lookup, x, z, 0, 1);
        int minZ = walk(lookup, x, z, 0, -1);
        if (maxX == Integer.MIN_VALUE || minX == Integer.MIN_VALUE
                || maxZ == Integer.MIN_VALUE || minZ == Integer.MIN_VALUE) {
            return null;
        }
        return new Rect(minX, minZ, maxX, maxZ);
    }

    /** The last non-void coordinate along one ray, or {@link Integer#MIN_VALUE} when no wall in range. */
    private static int walk(VoidLookup lookup, int x, int z, int stepX, int stepZ) {
        for (int i = 1; i <= MAX_SPAN; i++) {
            int cx = x + stepX * i;
            int cz = z + stepZ * i;
            if (lookup.isVoid(cx, cz)) {
                return stepX != 0 ? cx - stepX : cz - stepZ;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Adds {@code probe} unless an existing rect already fully contains it; drops rects it swallows. */
    private static boolean addIfNew(List<Rect> rects, Rect probe) {
        for (Rect rect : rects) {
            if (rect.containsRect(probe)) {
                return false;
            }
        }
        rects.removeIf(probe::containsRect);
        rects.add(probe);
        return true;
    }

    /** Renders the footprint as "1x1" / "1x3" / "2x2", or "L (n cells)" when the box is not filled. */
    private static String shapeOf(List<Rect> rects, BlockPos min, BlockPos max) {
        int cellsX = Math.max(1, Math.round((max.getX() - min.getX() + 1) / (float) CELL_PITCH));
        int cellsZ = Math.max(1, Math.round((max.getZ() - min.getZ() + 1) / (float) CELL_PITCH));
        int covered = 0;
        for (int ix = 0; ix < cellsX; ix++) {
            for (int iz = 0; iz < cellsZ; iz++) {
                int cx = Math.min(min.getX() + ix * CELL_PITCH + CELL_PITCH / 2, max.getX());
                int cz = Math.min(min.getZ() + iz * CELL_PITCH + CELL_PITCH / 2, max.getZ());
                for (Rect rect : rects) {
                    if (rect.contains(cx, cz)) {
                        covered++;
                        break;
                    }
                }
            }
        }
        if (covered >= cellsX * cellsZ) {
            return cellsX + "x" + cellsZ;
        }
        return "L (" + covered + " cells)";
    }

    // ---- void-column test ----------------------------------------------------------------------------

    /**
     * Column emptiness test with per-detection caching. A column is <b>void</b> when no section of its
     * chunk contains a block in it – empty sections are skipped via {@link LevelChunkSection#hasOnlyAir()},
     * so the common case costs only a few flag reads. Unloaded chunks count as <b>solid</b> so a room is
     * never cut short by missing data.
     */
    // Package-visible: DungeonGridRooms reuses the cached void-column test for its edge strips.
    static final class VoidLookup {
        private final Level level;
        private final Map<Long, Boolean> columns = new HashMap<>();
        private final Map<Long, LevelChunk> chunks = new HashMap<>();

        VoidLookup(Level level) {
            this.level = level;
        }

        boolean isVoid(int x, int z) {
            long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
            Boolean cached = columns.get(key);
            if (cached != null) {
                return cached;
            }
            boolean result = compute(x, z);
            columns.put(key, result);
            return result;
        }

        private boolean compute(int x, int z) {
            if (!level.hasChunkAt(x, z)) {
                return false;
            }
            long chunkKey = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
            LevelChunk chunk = chunks.computeIfAbsent(chunkKey, k -> level.getChunk(x >> 4, z >> 4));
            int lx = x & 15;
            int lz = z & 15;
            for (LevelChunkSection section : chunk.getSections()) {
                if (section.hasOnlyAir()) {
                    continue;
                }
                for (int y = 0; y < 16; y++) {
                    if (!section.getBlockState(lx, y, lz).isAir()) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
