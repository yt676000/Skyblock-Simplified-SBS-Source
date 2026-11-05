/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ice Fill, the pure half: turns the walkable ice tiles found in the room into floors, and picks
 * each floor's entry and exit tile. No Minecraft types, so the tests drive it from text layouts.
 *
 * <p><b>What a tile is</b> (wiki and estimate, unverified in 26.2): an {@code ice} or
 * {@code packed_ice} block with air above it. A floor is one 4-connected group of tiles at one
 * height; the floors are separate groups at rising heights, entered from the room's entrance side.
 *
 * <p><b>Entry and exit are inferred, not read</b> ({@code ESTIMATED}). The floors line up along the
 * room's axis: the axis runs from the lowest floor's centre to the highest floor's centre, and a
 * floor's entry is its tile furthest back along that axis, its exit the one furthest forward - ties
 * broken towards the line between the floor centres, where the gaps in the walls sit. A wrong pick
 * makes the route search fail or draw a route that ends at the wrong side; the solver logs both
 * tiles so one run settles it.
 */
public final class IceFillBoard {

    /** Smaller groups are decoration or a stray block, not a floor. */
    static final int MIN_FLOOR_TILES = 5;

    /** One ice block a player can walk on, by the ice block's own position. */
    public record Tile(int x, int y, int z) {
    }

    /**
     * One floor as a dense grid. {@code cells[i]} is a tile at {@code (minX + i % width, y,
     * minZ + i / width)}; entry and exit are cell indices.
     */
    public record Floor(int y, int minX, int minZ, int width, int depth, boolean[] cells, int tiles,
                        int entry, int exit) {

        public int index(int x, int z) {
            int dx = x - minX;
            int dz = z - minZ;
            if (dx < 0 || dz < 0 || dx >= width || dz >= depth) {
                return -1;
            }
            int i = dz * width + dx;
            return cells[i] ? i : -1;
        }

        public int xOf(int index) {
            return minX + index % width;
        }

        public int zOf(int index) {
            return minZ + index / width;
        }

        /** {@code "(x,z)"}, for the log. */
        public String describe(int index) {
            return index < 0 ? "none" : "(" + xOf(index) + "," + zOf(index) + ")";
        }
    }

    private IceFillBoard() {
    }

    /**
     * The floors in the room, lowest first, with entry and exit chosen. Groups smaller than
     * {@link #MIN_FLOOR_TILES} are dropped. Empty when nothing qualifies.
     */
    public static List<Floor> floors(Collection<Tile> tiles) {
        List<List<Tile>> groups = group(tiles);
        groups.removeIf(g -> g.size() < MIN_FLOOR_TILES);
        if (groups.isEmpty()) {
            return List.of();
        }
        groups.sort(Comparator.comparingInt((List<Tile> g) -> g.get(0).y()).thenComparingDouble(g -> -g.size()));

        double[][] centres = new double[groups.size()][];
        for (int i = 0; i < groups.size(); i++) {
            centres[i] = centre(groups.get(i));
        }
        // The axis floors advance along; one floor alone gives none, and then entry/exit stay unknown.
        double ax = centres[centres.length - 1][0] - centres[0][0];
        double az = centres[centres.length - 1][1] - centres[0][1];
        double len = Math.sqrt(ax * ax + az * az);
        // Snapped to the dominant cardinal direction: the room is grid-aligned, and the raw line
        // between centroids tilts whenever holes in the ice pull a centroid aside - enough to make
        // a corner of the back row "furthest back" (2026-10-16: entries logged at x -26/-27/-28 on
        // floors centred on x -25, where the maintainer actually stepped on).
        double sx = len < 1 ? 0 : Math.abs(ax) >= Math.abs(az) ? Math.signum(ax) : 0;
        double sz = len < 1 ? 0 : Math.abs(ax) >= Math.abs(az) ? 0 : Math.signum(az);
        List<Floor> out = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            out.add(toFloor(groups.get(i), sx, sz));
        }
        return List.copyOf(out);
    }

    /** 4-connected groups of tiles sharing a height. */
    static List<List<Tile>> group(Collection<Tile> tiles) {
        Map<Long, Tile> byKey = new HashMap<>();
        for (Tile tile : tiles) {
            byKey.put(key(tile.x(), tile.y(), tile.z()), tile);
        }
        Map<Long, Boolean> seen = new HashMap<>();
        List<List<Tile>> groups = new ArrayList<>();
        ArrayDeque<Tile> queue = new ArrayDeque<>();
        for (Tile seed : tiles) {
            if (seen.putIfAbsent(key(seed.x(), seed.y(), seed.z()), Boolean.TRUE) != null) {
                continue;
            }
            List<Tile> group = new ArrayList<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                Tile t = queue.poll();
                group.add(t);
                visit(byKey, seen, queue, t.x() + 1, t.y(), t.z());
                visit(byKey, seen, queue, t.x() - 1, t.y(), t.z());
                visit(byKey, seen, queue, t.x(), t.y(), t.z() + 1);
                visit(byKey, seen, queue, t.x(), t.y(), t.z() - 1);
            }
            groups.add(group);
        }
        return groups;
    }

    private static void visit(Map<Long, Tile> byKey, Map<Long, Boolean> seen, ArrayDeque<Tile> queue,
                              int x, int y, int z) {
        long k = key(x, y, z);
        Tile t = byKey.get(k);
        if (t != null && seen.putIfAbsent(k, Boolean.TRUE) == null) {
            queue.add(t);
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static double[] centre(List<Tile> group) {
        double sx = 0;
        double sz = 0;
        for (Tile t : group) {
            sx += t.x();
            sz += t.z();
        }
        return new double[] {sx / group.size(), sz / group.size()};
    }

    private static Floor toFloor(List<Tile> group, double ax, double az) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Tile t : group) {
            minX = Math.min(minX, t.x());
            minZ = Math.min(minZ, t.z());
            maxX = Math.max(maxX, t.x());
            maxZ = Math.max(maxZ, t.z());
        }
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        boolean[] cells = new boolean[width * depth];
        int entry = -1;
        int exit = -1;
        double entryAlong = Double.MAX_VALUE;
        double exitAlong = -Double.MAX_VALUE;
        double entrySide = Double.MAX_VALUE;
        double exitSide = Double.MAX_VALUE;
        boolean axis = ax != 0 || az != 0;
        // The floor's bounding-box centre, not its centroid: the wall gaps sit on the room's centre
        // line, and holes in the ice move the centroid off it.
        double cx = (minX + maxX) / 2.0;
        double cz = (minZ + maxZ) / 2.0;
        for (Tile t : group) {
            int i = (t.z() - minZ) * width + (t.x() - minX);
            cells[i] = true;
            if (!axis) {
                continue;
            }
            double dx = t.x() - cx;
            double dz = t.z() - cz;
            double along = dx * ax + dz * az;
            double side = Math.abs(dx * -az + dz * ax);
            if (along < entryAlong - 1e-6 || (Math.abs(along - entryAlong) <= 1e-6 && side < entrySide)) {
                entryAlong = along;
                entrySide = side;
                entry = i;
            }
            if (along > exitAlong + 1e-6 || (Math.abs(along - exitAlong) <= 1e-6 && side < exitSide)) {
                exitAlong = along;
                exitSide = side;
                exit = i;
            }
        }
        return new Floor(group.get(0).y(), minX, minZ, width, depth, cells, group.size(), entry, exit);
    }

    /**
     * What to walk on one floor now.
     *
     * @param route  the remaining route (cell indices, first = where to stand now), or {@code null}
     * @param reason why there is no route; {@code "done"} when the floor is fully walked
     */
    public record Plan(IceFillRoute.Result route, String reason) {
    }

    /**
     * Plans the rest of a floor. {@code covered[i]} marks walked tiles (packed ice); {@code player}
     * is the cell the player stands on, or -1 when they are not on this floor.
     *
     * <ul>
     *   <li>Nothing walked yet and the player off the floor: the whole floor, entry to exit.</li>
     *   <li>Player on the floor: from their tile over every unwalked tile to the exit - a re-plan
     *       after any deviation.</li>
     *   <li>Partly walked with the player off it: no route - where the walk resumes is unknown.</li>
     * </ul>
     */
    public static Plan plan(Floor floor, boolean[] covered, int player) {
        boolean[] cells = floor.cells();
        boolean[] open = new boolean[cells.length];
        int remaining = 0;
        int walked = 0;
        for (int i = 0; i < open.length; i++) {
            if (!cells[i]) {
                continue;
            }
            if (covered[i]) {
                walked++;
            } else {
                open[i] = true;
                remaining++;
            }
        }
        if (remaining == 0) {
            return new Plan(null, "done");
        }
        if (floor.entry() < 0 || floor.exit() < 0) {
            return new Plan(null, "entry/exit unknown");
        }
        int start;
        if (player >= 0 && cells[player]) {
            start = player;
            open[player] = true;
        } else if (walked == 0) {
            start = floor.entry();
        } else {
            return new Plan(null, "partly walked, player off the floor");
        }
        if (!open[floor.exit()]) {
            return new Plan(null, "exit already walked");
        }
        IceFillRoute.Result route = IceFillRoute.solve(floor.width(), floor.depth(), open, start, floor.exit());
        return route == null ? new Plan(null, "no route") : new Plan(route, null);
    }
}
